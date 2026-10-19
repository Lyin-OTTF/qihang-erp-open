#!/usr/bin/env bash
# 一键跑压测基准（Linux/macOS）
#
# 用法：
#   ./benchmark/run-benchmark.sh              # 只跑 Java 基准（需要 MySQL）
#   ./benchmark/run-benchmark.sh --http       # 额外跑 k6（需要 k6 + 已起应用）
#
# 环境变量：
#   MYSQL_URL / MYSQL_USERNAME / MYSQL_PASSWORD   数据库连接（默认 127.0.0.1:3306/qihang-erp）
#   BASE_URL=http://localhost:8080                HTTP 压测目标
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> 1/3 造压测数据"
if command -v mysql >/dev/null 2>&1; then
  mysql -h "${MYSQL_HOST:-127.0.0.1}" -P "${MYSQL_PORT:-3306}" \
        -u "${MYSQL_USERNAME:-root}" ${MYSQL_PASSWORD:+-p"$MYSQL_PASSWORD"} \
        "${MYSQL_DB:-qihang-erp}" < benchmark/sql/seed-benchmark-data.sql
else
  echo "    未找到 mysql 客户端，跳过造数据（请手工执行 benchmark/sql/seed-benchmark-data.sql）"
fi

echo "==> 2/3 Java 基准：库存扣减（LEGACY / ATOMIC / ATOMIC_IDEM 三组对照）"
mvn -B -pl erp-api -am test -Pbenchmark-tests -Dtest=StockDeductBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false

echo "==> 3/3 Java 基准：Redis 命中 vs 直连 DB"
mvn -B -pl erp-api -am test -Pbenchmark-tests -Dtest=RedisCacheBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false

if [[ "${1:-}" == "--http" ]]; then
  echo "==> 可选：HTTP 端到端压测（k6）"
  if command -v k6 >/dev/null 2>&1; then
    k6 run -e BASE_URL="${BASE_URL:-http://localhost:8080}" -e INVENTORY_ID=9900002 benchmark/k6/stock-deduct.js
    k6 run -e BASE_URL="${BASE_URL:-http://localhost:8080}" -e GOODS_ID="${GOODS_ID:-1}" benchmark/k6/cache-compare.js
  else
    echo "    未安装 k6，跳过。安装：https://k6.io/docs/get-started/installation/"
  fi
fi

echo "==> 核对压测结果（是否超卖/账实一致）"
if command -v mysql >/dev/null 2>&1; then
  mysql -h "${MYSQL_HOST:-127.0.0.1}" -P "${MYSQL_PORT:-3306}" \
        -u "${MYSQL_USERNAME:-root}" ${MYSQL_PASSWORD:+-p"$MYSQL_PASSWORD"} \
        "${MYSQL_DB:-qihang-erp}" < benchmark/sql/verify-benchmark-result.sql
fi

echo
echo "报告目录：benchmark/reports/"
ls -1 benchmark/reports/ 2>/dev/null || true
echo
echo "清理压测数据：mysql ... < benchmark/sql/cleanup-benchmark-data.sql"
