# benchmark/reports

压测与基准输出目录。文件由脚本自动生成，按 `<类型>-<时间戳>.md` 命名：

| 文件名 | 来源 | 内容 |
|--------|------|------|
| `stock-deduct-benchmark-*.md` | `StockDeductBenchmarkTest` | LEGACY / ATOMIC / ATOMIC_IDEM 三组对照：TPS、P50/P95/P99、丢更新次数 |
| `redis-cache-benchmark-*.md` | `RedisCacheBenchmarkTest` | Redis GET vs MySQL 主键查询的响应时间对比 |
| `k6-stock-deduct-summary.md` | `k6/stock-deduct.js` | HTTP 端到端扣减：成功/库存不足计数、尾延迟 |
| `k6-cache-compare-summary.md` | `k6/cache-compare.js` | HTTP 端到端缓存命中/未命中对比 |
| `jmeter-*.jtl` / `jmeter-*-html/` | JMeter | 原始采样与 HTML 报告 |

约定：
1. **报告可提交**，是"优化结论有数据支撑"的证据链；
2. 报告中必须记录环境（CPU/内存/MySQL 参数/并发度），否则数字不可比；
3. 每次改动库存或缓存相关代码后重跑，对比同一场景的历史报告，而不是跨场景对比绝对值。
