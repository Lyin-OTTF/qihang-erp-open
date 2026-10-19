# 一键跑压测基准（Windows PowerShell）
#
# 用法：
#   .\benchmark\run-benchmark.ps1              # 只跑 Java 基准（需要 MySQL）
#   .\benchmark\run-benchmark.ps1 -Http        # 额外跑 k6（需要 k6 + 已起应用）
#
# 环境变量：MYSQL_URL / MYSQL_USERNAME / MYSQL_PASSWORD / BASE_URL
param(
    [switch]$Http,
    [string]$MySqlHost = "127.0.0.1",
    [string]$MySqlPort = "3306",
    [string]$MySqlUser = "root",
    [string]$MySqlPassword = "",
    [string]$MySqlDb = "qihang-erp",
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "==> 1/3 造压测数据" -ForegroundColor Cyan
$mysql = Get-Command mysql -ErrorAction SilentlyContinue
if ($mysql) {
    $pwdArg = if ($MySqlPassword) { "-p$MySqlPassword" } else { "" }
    Get-Content benchmark/sql/seed-benchmark-data.sql -Raw | & mysql -h $MySqlHost -P $MySqlPort -u $MySqlUser $pwdArg $MySqlDb
} else {
    Write-Host "    未找到 mysql 客户端，跳过造数据（请手工执行 benchmark/sql/seed-benchmark-data.sql）" -ForegroundColor Yellow
}

Write-Host "==> 2/3 Java 基准：库存扣减（LEGACY / ATOMIC / ATOMIC_IDEM 三组对照）" -ForegroundColor Cyan
& mvn -B -pl erp-api -am test "-Pbenchmark-tests" "-Dtest=StockDeductBenchmarkTest" "-Dsurefire.failIfNoSpecifiedTests=false"

Write-Host "==> 3/3 Java 基准：Redis 命中 vs 直连 DB" -ForegroundColor Cyan
& mvn -B -pl erp-api -am test "-Pbenchmark-tests" "-Dtest=RedisCacheBenchmarkTest" "-Dsurefire.failIfNoSpecifiedTests=false"

if ($Http) {
    Write-Host "==> 可选：HTTP 端到端压测（k6）" -ForegroundColor Cyan
    if (Get-Command k6 -ErrorAction SilentlyContinue) {
        & k6 run -e BASE_URL=$BaseUrl -e INVENTORY_ID=9900002 benchmark/k6/stock-deduct.js
        & k6 run -e BASE_URL=$BaseUrl -e GOODS_ID=1 benchmark/k6/cache-compare.js
    } else {
        Write-Host "    未安装 k6，跳过。安装：https://k6.io/docs/get-started/installation/" -ForegroundColor Yellow
    }
}

Write-Host "==> 核对压测结果（是否超卖/账实一致）" -ForegroundColor Cyan
if ($mysql) {
    $pwdArg = if ($MySqlPassword) { "-p$MySqlPassword" } else { "" }
    Get-Content benchmark/sql/verify-benchmark-result.sql -Raw | & mysql -h $MySqlHost -P $MySqlPort -u $MySqlUser $pwdArg $MySqlDb
}

Write-Host "`n报告目录：benchmark/reports/" -ForegroundColor Green
Get-ChildItem benchmark/reports -ErrorAction SilentlyContinue | Select-Object Name, Length
Write-Host "清理压测数据：mysql ... < benchmark/sql/cleanup-benchmark-data.sql"
