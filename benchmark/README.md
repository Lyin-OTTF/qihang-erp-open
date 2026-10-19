# 压测与基准（benchmark）

本目录是启航 ERP 的**可复现压测资产**，对应两条能力声明：

1. **库存扣减：条件原子更新 + 同事务流水**在高并发下不超卖，并给出吞吐/尾延迟数据；
2. **Redis 缓存层：命中前后响应时间对比**，给出量化差值而非定性描述。

> 说明：仓库此前只有 `erp-api/src/test/.../InventoryConcurrencyTest.java`（功能正确性用例），
> 没有可复现的性能基准。本目录补齐这一层，并把"旧实现会丢更新"作为对照组，让优化结论可被验证。

## 目录结构

```
benchmark/
├── README.md                     # 本文件：场景、口径、执行方式、验收阈值
├── run-benchmark.ps1             # Windows 一键执行（Java 基准 + 可选 k6/JMeter）
├── run-benchmark.sh              # Linux/macOS 一键执行
├── k6/
│   ├── stock-deduct.js           # 库存扣减 HTTP 压测（k6）
│   └── cache-compare.js          # 缓存命中/未命中对比（k6）
├── jmeter/
│   ├── stock-deduct.jmx          # 库存扣减 HTTP 压测（JMeter 5.6）
│   └── cache-compare.jmx         # 缓存命中/未命中对比（JMeter 5.6）
├── sql/
│   ├── seed-benchmark-data.sql   # 造压测数据（隔离前缀 BENCH-，不影响业务数据）
│   └── cleanup-benchmark-data.sql
└── reports/                      # 基准输出（Markdown + CSV），git 保留目录
```

## 三种执行方式

| 方式 | 覆盖链路 | 依赖 | 适用 |
|------|---------|------|------|
| **Java 基准**（`StockDeductBenchmarkTest`） | 真实 Mapper SQL + 真实 DB 事务，绕开 HTTP/鉴权 | JDK + MySQL | 定位"数据库/SQL 层"能力上限，验证不超卖 |
| **k6** | 完整 HTTP 栈（Spring MVC → Service → DB） | k6 + 以 `benchmark` profile 启动的应用 | 端到端吞吐与尾延迟 |
| **JMeter** | 同 k6 | JMeter 5.6+ + 同上 | 团队已有 JMeter 资产、需要 HTML 报告时 |

三者使用同一份种子数据与同一套指标口径，结果可横向对照。

## 一、Java 基准（推荐先跑）

```bash
# 默认连 127.0.0.1:3306/qihang-erp，可用环境变量覆盖
export MYSQL_URL="jdbc:mysql://127.0.0.1:3306/qihang-erp?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=1234

mvn -pl erp-api -am test -Pbenchmark-tests -Dtest=StockDeductBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl erp-api -am test -Pbenchmark-tests -Dtest=RedisCacheBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false
```

数据库不可达时用例会被 JUnit `Assumptions` 跳过（不算失败），便于在无 DB 的 CI 上跑全量测试。

**三个对照场景**（同一初始库存、同一并发度）：

| 模式 | 实现 | 预期结论 |
|------|------|---------|
| `LEGACY` | 先 `SELECT` 判断、再 `UPDATE` 写回，模拟重构前代码 | 高并发下出现**丢更新/负库存**，成功次数与库存减少量对不上 |
| `ATOMIC` | `UPDATE ... WHERE quantity >= ? AND available_quantity >= ?` + 同事务写流水 | 零超卖，失败全部来自"库存不足"这一合法原因 |
| `ATOMIC_IDEM` | ATOMIC + 幂等键前置校验 | 与 ATOMIC 吞吐差异即幂等校验的成本（应 < 5%） |

**指标口径**：每个并发线程循环 N 次，记录单次操作耗时（纳秒精度），统计 TPS、P50、P95、P99、Max。
超卖判定：结束后 `quantity < 0` 或 `available_quantity < 0`，或"成功次数 × 单次扣减 ≠ 实际减少量"。

## 二、k6（HTTP 端到端）

```bash
# 1) 以 benchmark profile 启动应用（会注册 /api/benchmark/** 压测接口，生产不激活）
java -jar erp-api/target/erp-api.jar --spring.profiles.active=benchmark

# 2) 造数据
mysql -uroot -p1234 qihang-erp < benchmark/sql/seed-benchmark-data.sql

# 3) 压测
k6 run -e BASE_URL=http://localhost:8080 -e INVENTORY_ID=9900001 benchmark/k6/stock-deduct.js
k6 run -e BASE_URL=http://localhost:8080 -e GOODS_ID=1 benchmark/k6/cache-compare.js
```

`benchmark` profile 下的接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/benchmark/ping` | 健康检查/预热 |
| POST | `/api/benchmark/stock/deduct?inventoryId=&quantity=&key=` | 走与生产一致的 `changeStock`（幂等 + 原子更新 + 流水） |
| GET | `/api/benchmark/goods/{id}?cached=true\|false` | 缓存读 vs 直连 DB 对照 |

安全隐患说明：该 Controller 由 `@Profile("benchmark")` 控制，**不激活 profile 时不会注册**；
`SecurityConfig` 中对 `/api/benchmark/**` 的放行因此在生产是惰性的。请勿在生产以 `benchmark` profile 启动。

## 三、JMeter

```bash
jmeter -n -t benchmark/jmeter/stock-deduct.jmx \
       -JBASE_URL=http://localhost:8080 -JINVENTORY_ID=9900001 \
       -Jthreads=64 -Jloops=200 -l benchmark/reports/jmeter-stock.jtl \
       -e -o benchmark/reports/jmeter-stock-html

jmeter -n -t benchmark/jmeter/cache-compare.jmx \
       -JBASE_URL=http://localhost:8080 -JGOODS_ID=1 \
       -Jthreads=32 -Jloops=500 -l benchmark/reports/jmeter-cache.jtl \
       -e -o benchmark/reports/jmeter-cache-html
```

## 验收阈值（写入 CI/评审时按此判定）

| 场景 | 指标 | 阈值 | 理由 |
|------|------|------|------|
| 库存扣减（ATOMIC） | 超卖次数 | **0** | 条件原子更新的硬性正确性要求 |
| 库存扣减（ATOMIC） | 成功次数 × 单次扣减 = 实际减少量 | **必须相等** | 证明流水与库存严格同事务 |
| 库存扣减（LEGACY 对照） | 超卖/丢更新 | **> 0**（用于证明重构必要性） | 若为 0 说明并发度或时长不足，需加大压力 |
| 库存扣减 | P99 | < 50 ms（本地 MySQL，64 线程） | 单行热点更新的合理尾延迟 |
| 库存扣减 | 幂等校验成本 | < 5%（ATOMIC_IDEM vs ATOMIC） | 一次索引回查的代价 |
| 缓存读 | P50 差值（DB - Redis） | > 0.2 ms | 否则缓存收益不足以覆盖一致性成本 |
| 缓存读 | P99 | < 20 ms | 避免"平均快、长尾慢"的假优化 |

> 阈值是判断"是否值得上线"的门槛，不是绝对性能承诺。硬件、MySQL 参数（`innodb_flush_log_at_trx_commit`、
> `sync_binlog`）、连接池大小都会显著影响绝对值，横向对比必须在同一台机器、同一份数据上完成。

## 数据隔离

种子数据全部使用 `BENCH-` 前缀与固定 ID 段（库存 9900001+），清理脚本按前缀删除，
不会触碰业务数据。压测完成后**必须**执行 `cleanup-benchmark-data.sql`，否则对账任务会把压测数据纳入扫描范围。
