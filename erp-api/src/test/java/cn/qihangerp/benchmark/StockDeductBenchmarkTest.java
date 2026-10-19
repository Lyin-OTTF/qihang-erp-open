package cn.qihangerp.benchmark;

import cn.qihangerp.mapper.OGoodsInventoryMapper;
import cn.qihangerp.mapper.OGoodsInventoryOperationMapper;
import cn.qihangerp.model.entity.OGoodsInventory;
import cn.qihangerp.model.entity.OGoodsInventoryOperation;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 库存扣减基准（benchmark）。
 *
 * <p>三组对照，同一初始库存、同一并发度：</p>
 * <ul>
 *   <li><b>LEGACY</b>：先 SELECT 判断、再 UPDATE 写回 —— 复刻重构前的实现，用于暴露丢更新；</li>
 *   <li><b>ATOMIC</b>：UPDATE ... WHERE quantity >= ? —— 当前实现，条件判断与写入在同一条 SQL；</li>
 *   <li><b>ATOMIC_IDEM</b>：ATOMIC + 幂等键前置校验 —— 量化幂等能力带来的额外成本。</li>
 * </ul>
 *
 * <p>正确性判据：{@code 成功次数 × 单次扣减 == 实际库存减少量}，且库存不为负。
 * LEGACY 不满足该等式即为"丢更新/超卖"，这正是重构要解决的问题。</p>
 *
 * <p>运行：</p>
 * <pre>
 * mvn -pl erp-api -am test -Dtest=StockDeductBenchmarkTest -Dgroups=benchmark
 * </pre>
 *
 * @author qihang
 */
@Tag("benchmark")
class StockDeductBenchmarkTest {

    private static final int THREADS = 32;
    private static final int OPS_PER_THREAD = 400;
    private static final int DEDUCT_QTY = 1;
    /** 初始库存 = 总需求的一半，确保一半请求会因库存不足失败，制造真实争抢 */
    private static final int INITIAL_STOCK = THREADS * OPS_PER_THREAD / 2;

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static boolean idempotencyColumnReady;

    private static final String SKU_PREFIX = "BM" + (System.currentTimeMillis() % 100000);

    @BeforeAll
    static void setup() {
        String url = System.getenv().getOrDefault("MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/qihang-erp?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String user = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
        String password = System.getenv().getOrDefault("MYSQL_PASSWORD", "1234");

        PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", url, user, password);
        ds.setPoolMaximumActiveConnections(THREADS + 16);
        ds.setPoolMaximumIdleConnections(THREADS + 16);
        dataSource = ds;

        MybatisConfiguration config = new MybatisConfiguration();
        config.addMapper(OGoodsInventoryMapper.class);
        config.addMapper(OGoodsInventoryOperationMapper.class);
        config.setEnvironment(new Environment("benchmark", new JdbcTransactionFactory(), dataSource));
        // 与生产一致：开启 @Version 乐观锁，否则 updateById 缺少 MP_OPTLOCK_VERSION_ORIGINAL 参数
        MybatisPlusInterceptor mpInterceptor = new MybatisPlusInterceptor();
        mpInterceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        config.addInterceptor(mpInterceptor);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        assumeTrue(canConnect(), "MySQL 不可达，跳过库存扣减基准（设置 MYSQL_URL/MYSQL_USERNAME/MYSQL_PASSWORD 后重跑）");
        idempotencyColumnReady = hasColumn("o_goods_inventory_operation", "idempotency_key");
    }

    @AfterAll
    static void tearDown() {
        if (sqlSessionFactory == null) {
            return;
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventoryMapper invMapper = session.getMapper(OGoodsInventoryMapper.class);
            OGoodsInventoryOperationMapper opMapper = session.getMapper(OGoodsInventoryOperationMapper.class);
            opMapper.delete(new LambdaQueryWrapper<OGoodsInventoryOperation>()
                    .likeRight(OGoodsInventoryOperation::getSkuCode, SKU_PREFIX));
            invMapper.delete(new LambdaQueryWrapper<OGoodsInventory>()
                    .likeRight(OGoodsInventory::getSkuCode, SKU_PREFIX));
        } catch (Exception e) {
            System.err.println("基准数据清理失败: " + e.getMessage());
        }
    }

    @Test
    void benchmarkDeductModes() throws Exception {
        ScenarioResult legacy = runScenario("LEGACY", 1_000_001L);
        ScenarioResult atomic = runScenario("ATOMIC", 1_000_002L);
        ScenarioResult idem = idempotencyColumnReady
                ? runScenario("ATOMIC_IDEM", 1_000_003L)
                : ScenarioResult.skipped("ATOMIC_IDEM", "未执行 V1_32_0 迁移（缺 idempotency_key 列）");

        // 硬性正确性断言：当前实现绝不丢更新
        assertEquals(0L, atomic.lostUpdates, "ATOMIC 模式出现丢更新/超卖：" + atomic.describe());
        if (idem.executed) {
            assertEquals(0L, idem.lostUpdates, "ATOMIC_IDEM 模式出现丢更新/超卖：" + idem.describe());
        }

        String markdown = buildMarkdown(legacy, atomic, idem);
        System.out.println("\n" + markdown);
        Path file = LatencyStats.writeReport("stock-deduct-benchmark", markdown);
        System.out.println("报告已写入: " + (file == null ? "(失败)" : file.toAbsolutePath()));
    }

    // ------------------------------------------------------------------
    // 场景执行
    // ------------------------------------------------------------------

    private ScenarioResult runScenario(String mode, long baseId) throws Exception {
        long inventoryId = seedInventory(baseId, mode);
        LatencyStats stats = new LatencyStats();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<String> errorSamples = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> firstError = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int threadNo = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        long begin = System.nanoTime();
                        int r = executeOnce(mode, inventoryId, threadNo + "-" + i, firstError);
                        stats.record(System.nanoTime() - begin);
                        if (r > 0) {
                            success.incrementAndGet();
                        } else if (r == 0) {
                            insufficient.incrementAndGet();
                        } else {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    if (errorSamples.size() < 3) {
                        errorSamples.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        long wallBegin = System.currentTimeMillis();
        start.countDown();
        done.await(10, TimeUnit.MINUTES);
        long wallMillis = System.currentTimeMillis() - wallBegin;
        pool.shutdown();

        int finalQty = readQuantity(inventoryId);
        long expectedDeducted = (long) success.get() * DEDUCT_QTY;
        long actualDeducted = INITIAL_STOCK - finalQty;
        long lost = expectedDeducted - actualDeducted;

        ScenarioResult r = new ScenarioResult();
        r.mode = mode;
        r.executed = true;
        r.success = success.get();
        r.insufficient = insufficient.get();
        r.errors = errors.get();
        r.finalQty = finalQty;
        r.expectedDeducted = expectedDeducted;
        r.actualDeducted = actualDeducted;
        r.lostUpdates = lost;
        r.oversold = finalQty < 0;
        r.stats = stats;
        r.wallMillis = wallMillis;
        r.errorSamples = new ArrayList<>(errorSamples);
        if (firstError.get() != null) {
            r.errorSamples.add(firstError.get());
        }

        cleanupInventory(inventoryId);
        System.out.printf("[%s] 成功=%d 不足=%d 异常=%d 最终库存=%d 丢更新=%d 耗时=%dms%n",
                mode, r.success, r.insufficient, r.errors, finalQty, lost, wallMillis);
        return r;
    }

    /**
     * 执行一次扣减，返回 1=成功、0=库存不足、-1=异常。
     */
    private int executeOnce(String mode, long inventoryId, String opKey, AtomicReference<String> firstError) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            OGoodsInventoryMapper invMapper = session.getMapper(OGoodsInventoryMapper.class);
            OGoodsInventoryOperationMapper opMapper = session.getMapper(OGoodsInventoryOperationMapper.class);

            switch (mode) {
                case "LEGACY" -> {
                    // 复刻重构前实现：先查后写，两步之间没有原子性保证。
                    // 关键点：updateById 在版本冲突时返回 0，旧代码并不检查返回值，
                    // 于是"业务认为成功、库存其实没扣"——这正是丢更新的来源。
                    OGoodsInventory current = invMapper.selectById(inventoryId);
                    if (current == null || current.getAvailableQuantity() < DEDUCT_QTY) {
                        session.rollback();
                        return 0;
                    }
                    current.setQuantity(current.getQuantity() - DEDUCT_QTY);
                    current.setAvailableQuantity(current.getAvailableQuantity() - DEDUCT_QTY);
                    invMapper.updateById(current);
                    session.commit();
                    return 1;
                }
                case "ATOMIC", "ATOMIC_IDEM" -> {
                    if ("ATOMIC_IDEM".equals(mode)) {
                        Long exist = opMapper.selectCount(new LambdaQueryWrapper<OGoodsInventoryOperation>()
                                .eq(OGoodsInventoryOperation::getIdempotencyKey, "BM:" + opKey));
                        if (exist != null && exist > 0) {
                            session.rollback();
                            return 1;
                        }
                    }
                    int affected = invMapper.deductStockAtomic(inventoryId, DEDUCT_QTY);
                    if (affected == 0) {
                        session.rollback();
                        return 0;
                    }
                    OGoodsInventory after = invMapper.selectById(inventoryId);
                    opMapper.insert(buildOperation(inventoryId, after, "ATOMIC_IDEM".equals(mode) ? "BM:" + opKey : null));
                    session.commit();
                    return 1;
                }
                default -> {
                    session.rollback();
                    return -1;
                }
            }
        } catch (Exception e) {
            firstError.compareAndSet(null, e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ")));
            return -1;
        }
    }

    private OGoodsInventoryOperation buildOperation(long inventoryId, OGoodsInventory inv, String idemKey) {
        OGoodsInventoryOperation op = new OGoodsInventoryOperation();
        op.setMerchantId(0L);
        op.setGoodsId(inv.getGoodsId());
        op.setGoodsNum(inv.getGoodsNum());
        op.setSkuId(inv.getSkuId());
        op.setSkuCode(inv.getSkuCode());
        op.setBatchId(0L);
        op.setBatchNum("");
        op.setType(2);
        op.setInventoryDetailId(inventoryId);
        op.setQuantity(DEDUCT_QTY);
        if (idempotencyColumnReady) {
            // 未执行迁移的库上这些列不存在，保持为 null，MyBatis-Plus 会从 INSERT 中省略
            op.setBeforeQty(inv.getQuantity() + DEDUCT_QTY);
            op.setAfterQty(inv.getQuantity());
            op.setIdempotencyKey(idemKey);
        }
        op.setLockedQuantity(0);
        op.setPrice(0.0);
        op.setBizType(40);
        op.setBizId(0L);
        op.setBizNum("BENCH");
        op.setBizItemId(0L);
        op.setStatus(1);
        op.setRemark("benchmark");
        op.setWarehouseId(0L);
        op.setPositionId(0L);
        op.setCreateTime(LocalDateTime.now());
        op.setUpdateTime(LocalDateTime.now());
        return op;
    }

    // ------------------------------------------------------------------
    // 数据准备与校验
    // ------------------------------------------------------------------

    private long seedInventory(long id, String mode) {
        // 表的列宽限制：sku_code varchar(25)、goods_num varchar(20)，这里用短码避免截断
        String code = switch (mode) {
            case "LEGACY" -> "L";
            case "ATOMIC" -> "A";
            default -> "I";
        };
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventoryMapper mapper = session.getMapper(OGoodsInventoryMapper.class);
            OGoodsInventory inv = new OGoodsInventory();
            inv.setId(id);
            inv.setGoodsId(id);
            inv.setGoodsNum("BG-" + code);
            inv.setSkuId(id);
            inv.setSkuCode(SKU_PREFIX + "-" + code);
            inv.setGoodsName("压测商品-" + mode);
            inv.setQuantity(INITIAL_STOCK);
            inv.setLockedQuantity(0);
            inv.setAvailableQuantity(INITIAL_STOCK);
            inv.setWarehouseId(1L);
            inv.setMerchantId(0L);
            inv.setShopId(0L);
            inv.setStockStatus(1);
            inv.setIsDelete(0);
            inv.setVersion(1);
            inv.setCreateTime(LocalDateTime.now());
            inv.setUpdateTime(LocalDateTime.now());
            mapper.deleteById(id);
            mapper.insert(inv);
            return id;
        }
    }

    private int readQuantity(long inventoryId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventory inv = session.getMapper(OGoodsInventoryMapper.class).selectById(inventoryId);
            return inv == null ? 0 : inv.getQuantity();
        }
    }

    private void cleanupInventory(long inventoryId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(OGoodsInventoryOperationMapper.class).delete(
                    new LambdaQueryWrapper<OGoodsInventoryOperation>()
                            .eq(OGoodsInventoryOperation::getInventoryDetailId, inventoryId));
            session.getMapper(OGoodsInventoryMapper.class).deleteById(inventoryId);
        } catch (Exception e) {
            System.err.println("清理失败: " + e.getMessage());
        }
    }

    private static boolean canConnect() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasColumn(String table, String column) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    private String buildMarkdown(ScenarioResult legacy, ScenarioResult atomic, ScenarioResult idem) {
        StringBuilder sb = new StringBuilder(LatencyStats.header("库存扣减基准",
                String.format("并发 %d 线程 × %d 次，每次扣减 %d，初始库存 %d（约为总需求的 50%%）",
                        THREADS, OPS_PER_THREAD, DEDUCT_QTY, INITIAL_STOCK)));
        sb.append("| 模式 | 成功 | 库存不足 | 异常 | TPS | P50(ms) | P95(ms) | P99(ms) | Max(ms) | 应减 | 实减 | 丢更新 | 判定 |\n");
        sb.append("|------|------|---------|------|-----|---------|---------|---------|---------|------|------|--------|------|\n");
        for (ScenarioResult r : new ScenarioResult[]{legacy, atomic, idem}) {
            if (!r.executed) {
                sb.append("| ").append(r.mode).append(" | - | - | - | - | - | - | - | - | - | - | - | 跳过：")
                        .append(r.skipReason).append(" |\n");
                continue;
            }
            sb.append("| ").append(r.mode)
                    .append(" | ").append(r.success)
                    .append(" | ").append(r.insufficient)
                    .append(" | ").append(r.errors)
                    .append(" | ").append(LatencyStats.n(r.stats.throughput(r.wallMillis)))
                    .append(" | ").append(LatencyStats.f(r.stats.percentileMs(50)))
                    .append(" | ").append(LatencyStats.f(r.stats.percentileMs(95)))
                    .append(" | ").append(LatencyStats.f(r.stats.percentileMs(99)))
                    .append(" | ").append(LatencyStats.f(r.stats.maxMs()))
                    .append(" | ").append(r.expectedDeducted)
                    .append(" | ").append(r.actualDeducted)
                    .append(" | ").append(r.lostUpdates)
                    .append(" | ").append(judge(r))
                    .append(" |\n");
        }
        sb.append("\n");
        boolean hasErrorSample = false;
        for (ScenarioResult r : new ScenarioResult[]{legacy, atomic, idem}) {
            if (r.executed && r.errorSamples != null && !r.errorSamples.isEmpty()) {
                if (!hasErrorSample) {
                    sb.append("### 异常样例\n\n");
                    hasErrorSample = true;
                }
                sb.append("- ").append(r.mode).append("：`").append(r.errorSamples.get(0)).append("`\n");
            }
        }
        if (hasErrorSample) {
            sb.append('\n');
        }
        sb.append("\n## 判读\n\n");
        sb.append("- **丢更新 = 成功次数 × 单次扣减 − 实际库存减少量**。LEGACY 大于 0 说明并发下存在\"卖出去了但没扣账\"，\n");
        sb.append("  等价于超卖；这正是把\"先查后写\"改为条件原子更新要消除的问题。\n");
        sb.append("- ATOMIC 必须为 0：条件更新把\"判断充足\"和\"扣减\"合并成一条 SQL，失败者拿到 0 行受影响，不会写坏库存。\n");
        if (idem.executed && atomic.executed && atomic.stats.throughput(atomic.wallMillis) > 0) {
            double cost = (atomic.stats.throughput(atomic.wallMillis) - idem.stats.throughput(idem.wallMillis))
                    / atomic.stats.throughput(atomic.wallMillis) * 100;
            if (Math.abs(cost) < 5) {
                sb.append("- 幂等校验的吞吐影响在噪声范围内（|差异| < 5%），即一次唯一索引回查的代价可忽略。\n");
            } else {
                sb.append(String.format("- 幂等校验（一次唯一索引回查）带来的吞吐成本约 %.1f%%。%n", cost));
            }
        } else if (!idem.executed) {
            sb.append("- 幂等场景被跳过：请先执行 Flyway 迁移 `V1_32_0__inventory_flow_and_reconcile.sql`。\n");
        }
        sb.append("\n> 绝对 TPS 依赖硬件、MySQL 参数（`innodb_flush_log_at_trx_commit`、`sync_binlog`）与连接池大小，\n");
        sb.append("> 有意义的是三组之间的相对关系与\"丢更新是否为 0\"。\n");
        return sb.toString();
    }

    private String judge(ScenarioResult r) {
        if (!r.executed) {
            return "-";
        }
        if (r.errors > 0) {
            return "⚠️ 执行异常 " + r.errors + " 次";
        }
        if (r.oversold || r.lostUpdates != 0) {
            return "❌ 丢更新/超卖";
        }
        return "✅ 不超卖";
    }

    /** 单个场景的结果 */
    private static class ScenarioResult {
        String mode;
        boolean executed;
        String skipReason;
        int success;
        int insufficient;
        int errors;
        int finalQty;
        long expectedDeducted;
        long actualDeducted;
        long lostUpdates;
        boolean oversold;
        long wallMillis;
        LatencyStats stats;
        List<String> errorSamples;

        static ScenarioResult skipped(String mode, String reason) {
            ScenarioResult r = new ScenarioResult();
            r.mode = mode;
            r.executed = false;
            r.skipReason = reason;
            return r;
        }

        String describe() {
            return "success=" + success + ", expected=" + expectedDeducted
                    + ", actual=" + actualDeducted + ", finalQty=" + finalQty;
        }
    }
}
