// 缓存命中/未命中响应时间对比（k6）
//
// 目的：把"加了 Redis 缓存变快了"变成可复现的数字——同一商品，
// cached=true（命中 Redis）与 cached=false（直连 MySQL）两组请求交替打，比较 P50/P95/P99。
//
// 用法：
//   k6 run -e BASE_URL=http://localhost:8080 -e GOODS_ID=1 benchmark/k6/cache-compare.js
//
// 前提：应用以 --spring.profiles.active=benchmark 启动。
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const GOODS_ID = __ENV.GOODS_ID || '1';

// 两条链路分开打点，才能在报告里直接对比
const cacheHit = new Trend('cache_read_ms', true);
const dbDirect = new Trend('db_read_ms', true);
const missAndBackfill = new Counter('cache_backfill_total');

export const options = {
  scenarios: {
    compare: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 32),
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    // 直连 DB 的 P95 应明显高于缓存读；若相等说明缓存未生效
    db_read_ms: ['p(95)<200'],
    cache_read_ms: ['p(95)<50'],
  },
};

export default function () {
  const useCache = __ITER % 2 === 0; // 交替采样，避免两组落在不同负载区间
  const url = `${BASE_URL}/api/benchmark/goods/${GOODS_ID}?cached=${useCache}`;
  const res = http.get(url, { tags: { name: useCache ? 'goods-cached' : 'goods-db' } });

  if (useCache) {
    cacheHit.add(res.timings.duration);
  } else {
    dbDirect.add(res.timings.duration);
  }

  const parsed = res.status === 200 ? safeJson(res) : null;
  // 首次访问会回源并回填缓存，计入 miss 次数便于解释第一段的抖动
  if (useCache && parsed && parsed.data && parsed.data.id == null) {
    missAndBackfill.add(1);
  }

  check(res, { 'http 200': (r) => r.status === 200 });
}

function safeJson(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const p = (name, q) => (m[name] && m[name].values[`p(${q})`] != null
    ? m[name].values[`p(${q})`].toFixed(2) : '-');
  const avg = (name) => (m[name] ? m[name].values.avg.toFixed(2) : '-');

  const cacheP50 = Number(p('cache_read_ms', 50));
  const dbP50 = Number(p('db_read_ms', 50));
  const delta = (Number.isFinite(cacheP50) && Number.isFinite(dbP50)) ? (dbP50 - cacheP50).toFixed(2) : '-';
  const ratio = (Number.isFinite(cacheP50) && cacheP50 > 0) ? (dbP50 / cacheP50).toFixed(1) : '-';

  const summary = [
    '# 缓存命中前后响应时间对比（k6）',
    '',
    '| 链路 | 平均 | P50 | P95 | P99 |',
    '|------|------|-----|-----|-----|',
    `| 命中缓存（Redis） | ${avg('cache_read_ms')} | ${p('cache_read_ms', 50)} | ${p('cache_read_ms', 95)} | ${p('cache_read_ms', 99)} |`,
    `| 直连 DB（MySQL） | ${avg('db_read_ms')} | ${p('db_read_ms', 50)} | ${p('db_read_ms', 95)} | ${p('db_read_ms', 99)} |`,
    '',
    `- P50 差值：${delta} ms（DB 慢 ${ratio} 倍）`,
    `- 缓存回填次数：${m.cache_backfill_total ? m.cache_backfill_total.values.count : 0}`,
    '',
    '> 单位均为毫秒。两次采样交替进行，负载条件一致，差值可直接引用。',
  ].join('\n');

  return {
    'stdout': `\n${summary}\n`,
    'benchmark/reports/k6-cache-compare-summary.md': summary,
  };
}
