// 库存扣减压测（k6）
// 目标：在 HTTP 端到端层面验证"条件原子更新 + 同事务流水"在高并发下不超卖，并给出吞吐/尾延迟。
//
// 用法：
//   k6 run -e BASE_URL=http://localhost:8080 -e INVENTORY_ID=9900001 benchmark/k6/stock-deduct.js
//
// 前提：应用以 --spring.profiles.active=benchmark 启动，且已执行 benchmark/sql/seed-benchmark-data.sql
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const INVENTORY_ID = __ENV.INVENTORY_ID || '9900001';
const QTY = Number(__ENV.QTY || 1);

// 业务口径指标：k6 默认只看 HTTP 状态码，这里额外区分"扣减成功/库存不足"
const deductOk = new Counter('deduct_ok_total');
const deductInsufficient = new Counter('deduct_insufficient_total');
const deductError = new Counter('deduct_error_total');
const deductLatency = new Trend('deduct_latency_ms', true);

export const options = {
  scenarios: {
    // 阶梯加压：50 → 100 → 200 并发，观察尾延迟随并发的变化
    ramp: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '20s', target: 50 },
        { duration: '30s', target: 100 },
        { duration: '30s', target: 200 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    // 服务端不应出现 5xx：库存不足由业务返回 ok=false，不是错误
    http_req_failed: ['rate<0.001'],
    deduct_latency_ms: ['p(99)<50'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  // 每次请求使用唯一幂等键，测吞吐；若要测"重复提交只扣一次"，
  // 把 key 固定为同一值再观察 deduct_ok_total 与实际库存减少量。
  const key = `K6:${__VU}:${__ITER}:${Date.now()}`;
  const url = `${BASE_URL}/api/benchmark/stock/deduct?inventoryId=${INVENTORY_ID}&quantity=${QTY}&key=${encodeURIComponent(key)}`;
  const res = http.post(url, null, { tags: { name: 'stock-deduct' } });

  deductLatency.add(res.timings.duration);

  const parsed = safeJson(res);
  const ok = parsed && parsed.data && parsed.data.ok === true;
  const insufficient = parsed && parsed.data && parsed.data.ok === false;

  if (res.status !== 200) {
    deductError.add(1);
  } else if (ok) {
    deductOk.add(1);
  } else if (insufficient) {
    deductInsufficient.add(1);
  } else {
    deductError.add(1);
  }

  check(res, {
    'http 200': (r) => r.status === 200,
    '业务有明确结果(ok 非空)': () => ok || insufficient,
  });
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
  const line = (name) => (m[name] ? m[name].values.count : 0);
  const pct = (name, p) => (m[name] && m[name].values[`p(${p})`] != null
    ? m[name].values[`p(${p})`].toFixed(2) : '-');

  const summary = [
    '# 库存扣减压测结果（k6）',
    '',
    `- 扣减成功：${line('deduct_ok_total')}`,
    `- 库存不足：${line('deduct_insufficient_total')}`,
    `- 其他错误：${line('deduct_error_total')}`,
    `- 延迟 P50/P95/P99：${pct('deduct_latency_ms', 50)} / ${pct('deduct_latency_ms', 95)} / ${pct('deduct_latency_ms', 99)} ms`,
    `- HTTP 失败率：${(m.http_req_failed ? m.http_req_failed.values.rate * 100 : 0).toFixed(3)}%`,
    '',
    '> 正确性判定：结束后用 SQL 核对 inventory.quantity 是否等于 初始值 - 成功次数 × 单次扣减，',
    '> 且不为负。SQL 见 benchmark/sql/verify-benchmark-result.sql。',
  ].join('\n');

  return {
    'stdout': `\n${summary}\n`,
    'benchmark/reports/k6-stock-deduct-summary.md': summary,
  };
}
