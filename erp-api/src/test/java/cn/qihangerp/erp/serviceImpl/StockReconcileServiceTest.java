package cn.qihangerp.erp.serviceImpl;

import cn.qihangerp.common.ServiceException;
import cn.qihangerp.mapper.OGoodsInventoryBatchMapper;
import cn.qihangerp.mapper.OGoodsInventoryMapper;
import cn.qihangerp.mapper.OGoodsInventoryOperationMapper;
import cn.qihangerp.mapper.StockReconcileDiffMapper;
import cn.qihangerp.model.entity.OGoodsInventory;
import cn.qihangerp.model.entity.OGoodsInventoryBatch;
import cn.qihangerp.model.entity.OGoodsInventoryOperation;
import cn.qihangerp.model.entity.StockReconcileDiff;
import cn.qihangerp.model.vo.StockReconcileReport;
import cn.qihangerp.service.impl.OGoodsInventoryBatchServiceImpl;
import cn.qihangerp.service.impl.OGoodsInventoryServiceImpl;
import cn.qihangerp.service.impl.StockReconcileServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 库存对账 + 人工补偿集成测试。
 *
 * <p>验证四件事：</p>
 * <ol>
 *   <li>五类差异能被检出（可用量 / 批次合计 / 账实 / 负库存 / 流水链）；</li>
 *   <li>同一库存同一类型不会重复堆积差异（每日对账不刷屏）；</li>
 *   <li>补偿只调账、不凭空改总库存（账实差异补流水，批次差异调明细）；</li>
 *   <li>负库存这类事实异常拒绝自动补偿。</li>
 * </ol>
 *
 * <p>运行：mvn -pl erp-api -am test -Dtest=StockReconcileServiceTest -Dgroups=it</p>
 *
 * @author qihang
 */
@Tag("it")
class StockReconcileServiceTest {

    private static final Long INV_ID = 9_800_001L;
    private static final Long BATCH_ID = 9_800_101L;
    private static final String SKU = "IT-RECON-" + (System.currentTimeMillis() % 100000);

    private static SqlSessionFactory sqlSessionFactory;
    private static MysqlDataSource dataSource;

    @BeforeAll
    static void setup() {
        dataSource = new MysqlDataSource();
        dataSource.setUrl(System.getenv().getOrDefault("MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/qihang-erp?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"));
        dataSource.setUser(System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD", "1234"));

        MybatisConfiguration config = new MybatisConfiguration();
        config.addMapper(OGoodsInventoryMapper.class);
        config.addMapper(OGoodsInventoryBatchMapper.class);
        config.addMapper(StockReconcileDiffMapper.class);
        // 手工装配 MyBatis 时不会自动加载 XML，需显式解析（selectFlowStats 定义在 XML 中）
        try (InputStream is = Resources.getResourceAsStream("mapper/OGoodsInventoryOperationMapper.xml")) {
            new XMLMapperBuilder(is, config, "mapper/OGoodsInventoryOperationMapper.xml",
                    config.getSqlFragments()).parse();
        } catch (IOException e) {
            throw new IllegalStateException("加载 OGoodsInventoryOperationMapper.xml 失败", e);
        }
        config.addMapper(OGoodsInventoryOperationMapper.class);
        config.setEnvironment(new Environment("it", new JdbcTransactionFactory(), (DataSource) dataSource));
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        assumeTrue(canConnect(), "MySQL 不可达，跳过对账集成测试");
        assumeTrue(tableExists("o_stock_reconcile_diff"),
                "未执行 Flyway 迁移 V1_32_0（缺 o_stock_reconcile_diff 表），跳过对账集成测试");
    }

    @AfterEach
    void cleanup() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(OGoodsInventoryOperationMapper.class).delete(
                    new LambdaQueryWrapper<OGoodsInventoryOperation>()
                            .eq(OGoodsInventoryOperation::getInventoryDetailId, INV_ID));
            session.getMapper(OGoodsInventoryBatchMapper.class).delete(
                    new LambdaQueryWrapper<OGoodsInventoryBatch>()
                            .eq(OGoodsInventoryBatch::getInventoryId, INV_ID));
            session.getMapper(StockReconcileDiffMapper.class).delete(
                    new LambdaQueryWrapper<StockReconcileDiff>()
                            .eq(StockReconcileDiff::getInventoryId, INV_ID));
            session.getMapper(OGoodsInventoryMapper.class).deleteById(INV_ID);
        }
    }

    @Test
    void detectsAndCompensatesAvailableMismatch() throws Exception {
        seed(100, 100, 0, 100, 100);
        // 人为把可用量写错，模拟"绕过了库存服务直接改表"或历史脏数据
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventory update = new OGoodsInventory();
            update.setId(INV_ID);
            update.setAvailableQuantity(90);
            session.getMapper(OGoodsInventoryMapper.class).updateById(update);
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            StockReconcileServiceImpl service = buildService(session);

            StockReconcileReport report = service.reconcileInventories(null, List.of(INV_ID));
            assertEquals(1, report.getDiffCount(), "应检出 1 条可用量差异");
            StockReconcileDiff diff = firstDiff(session, StockReconcileDiff.TYPE_AVAILABLE_MISMATCH);
            assertNotNull(diff);
            assertEquals(100L, diff.getExpectedValue() == null ? -1 : Long.parseLong(diff.getExpectedValue()));
            assertEquals("90", diff.getActualValue());

            // 重复对账不应重复产生同类差异
            StockReconcileReport again = service.reconcileInventories(null, List.of(INV_ID));
            assertEquals(0, again.getDiffCount(), "同一库存同一类型不应重复堆积差异");

            service.compensate(diff.getId(), "tester", "单测补偿");
            OGoodsInventory after = session.getMapper(OGoodsInventoryMapper.class).selectById(INV_ID);
            assertEquals(100, after.getAvailableQuantity(), "可用量应重算为 quantity-locked");
            assertEquals(100, after.getQuantity(), "补偿不应改动总库存");
        }
    }

    @Test
    void detectsAndCompensatesBatchMismatch() throws Exception {
        seed(100, 100, 0, 60, 100);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            StockReconcileServiceImpl service = buildService(session);
            StockReconcileReport report = service.reconcileInventories(null, List.of(INV_ID));
            assertTrue(report.getDiffCount() >= 1, "应检出批次合计差异");

            StockReconcileDiff diff = firstDiff(session, StockReconcileDiff.TYPE_BATCH_MISMATCH);
            assertNotNull(diff, "缺少批次合计差异记录");
            service.compensate(diff.getId(), "tester", "批次校正");

            OGoodsInventoryBatch batch = session.getMapper(OGoodsInventoryBatchMapper.class).selectById(BATCH_ID);
            assertEquals(100, batch.getCurrentQty(), "批次合计应校正到总库存");
            OGoodsInventory inv = session.getMapper(OGoodsInventoryMapper.class).selectById(INV_ID);
            assertEquals(100, inv.getQuantity(), "批次校正不应改动总库存");
        }
    }

    @Test
    void detectsBookActualMismatchAndCompensatesByFlowOnly() throws Exception {
        // 流水末笔 after=80，库存表却是 100 → 账实不一致（有库存变动没记账）
        seed(100, 100, 0, 100, 80);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            StockReconcileServiceImpl service = buildService(session);
            service.reconcileInventories(null, List.of(INV_ID));

            StockReconcileDiff diff = firstDiff(session, StockReconcileDiff.TYPE_BOOK_ACTUAL_MISMATCH);
            assertNotNull(diff, "缺少账实不一致差异记录");
            assertEquals(20L, diff.getDiffQty());

            service.compensate(diff.getId(), "tester", "补账");
            OGoodsInventory inv = session.getMapper(OGoodsInventoryMapper.class).selectById(INV_ID);
            assertEquals(100, inv.getQuantity(), "补账不应改动实存");

            // 应新增一条 type=5 调整流水，且 after_qty 等于实存
            Long adjustCount = session.getMapper(OGoodsInventoryOperationMapper.class).selectCount(
                    new LambdaQueryWrapper<OGoodsInventoryOperation>()
                            .eq(OGoodsInventoryOperation::getInventoryDetailId, INV_ID)
                            .eq(OGoodsInventoryOperation::getType, 5));
            assertEquals(1L, adjustCount, "应补记 1 条调整流水");

            // 补账后再次对账应无差异
            StockReconcileReport after = service.reconcileInventories(null, List.of(INV_ID));
            assertEquals(0, after.getDiffCount(), "补账后应账实一致");
        }
    }

    @Test
    void negativeStockIsDetectedButNotAutoCompensated() throws Exception {
        seed(-5, 0, 5, 0, -5);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            StockReconcileServiceImpl service = buildService(session);
            service.reconcileInventories(null, List.of(INV_ID));

            StockReconcileDiff diff = firstDiff(session, StockReconcileDiff.TYPE_NEGATIVE_OR_OVERLOCK);
            assertNotNull(diff, "负库存应被检出");

            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.compensate(diff.getId(), "tester", "尝试自动补偿"));
            assertTrue(ex.getMessage().contains("盘点"), "应提示需盘点后人工处理");
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /**
     * 造一条库存 + 一条批次 + 一条流水。
     *
     * @param quantity  库存表总库存
     * @param available 库存表可用量
     * @param locked    库存表锁定量
     * @param batchQty  批次当前数量
     * @param flowAfter 流水末笔的 after_qty（用于制造账实差异）
     */
    private void seed(int quantity, int available, int locked, int batchQty, int flowAfter) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventoryMapper invMapper = session.getMapper(OGoodsInventoryMapper.class);
            invMapper.deleteById(INV_ID);

            OGoodsInventory inv = new OGoodsInventory();
            inv.setId(INV_ID);
            inv.setGoodsId(INV_ID);
            inv.setGoodsNum("IT-GOODS");
            inv.setSkuId(INV_ID);
            inv.setSkuCode(SKU);
            inv.setGoodsName("对账测试商品");
            inv.setQuantity(quantity);
            inv.setLockedQuantity(locked);
            inv.setAvailableQuantity(available);
            inv.setWarehouseId(1L);
            inv.setMerchantId(1L);
            inv.setIsDelete(0);
            inv.setVersion(1);
            inv.setCreateTime(LocalDateTime.now());
            inv.setUpdateTime(LocalDateTime.now());
            invMapper.insert(inv);

            OGoodsInventoryBatchMapper batchMapper = session.getMapper(OGoodsInventoryBatchMapper.class);
            batchMapper.deleteById(BATCH_ID);
            OGoodsInventoryBatch batch = new OGoodsInventoryBatch();
            batch.setId(BATCH_ID);
            batch.setInventoryId(INV_ID);
            batch.setBatchNum("IT-BATCH");
            batch.setOriginQty(Math.max(batchQty, 0));
            batch.setCurrentQty(batchQty);
            batch.setSkuId(INV_ID);
            batch.setGoodsId(INV_ID);
            batch.setSkuCode(SKU);
            batch.setWarehouseId(1L);
            batch.setMerchantId(1L);
            batch.setStockStatus(1);
            batch.setInventoryMode(0);
            batch.setCreateTime(LocalDateTime.now());
            batchMapper.insert(batch);

            OGoodsInventoryOperationMapper opMapper = session.getMapper(OGoodsInventoryOperationMapper.class);
            opMapper.delete(new LambdaQueryWrapper<OGoodsInventoryOperation>()
                    .eq(OGoodsInventoryOperation::getInventoryDetailId, INV_ID));
            OGoodsInventoryOperation op = new OGoodsInventoryOperation();
            op.setMerchantId(1L);
            op.setGoodsId(INV_ID);
            op.setSkuId(INV_ID);
            op.setSkuCode(SKU);
            // batch_id / batch_num 在表上是 NOT NULL 无默认值，必须显式写入
            op.setBatchId(BATCH_ID);
            op.setBatchNum("IT-BATCH");
            op.setType(1);
            op.setInventoryDetailId(INV_ID);
            // 流水数量与 after_qty 保持自洽，使"流水链"校验通过，
            // 这样账实差异只由"库存表与末笔 after 不等"一个原因触发
            op.setQuantity(Math.max(flowAfter, 0));
            op.setBeforeQty(0);
            op.setAfterQty(flowAfter);
            op.setIdempotencyKey("IT:" + SKU + ":1");
            op.setLockedQuantity(0);
            op.setPrice(0.0);
            op.setBizType(10);
            op.setBizId(0L);
            op.setBizNum("IT");
            op.setBizItemId(0L);
            op.setStatus(1);
            op.setWarehouseId(1L);
            op.setPositionId(0L);
            op.setCreateTime(LocalDateTime.now());
            opMapper.insert(op);
        }
    }

    /**
     * 手工装配对账服务（不开 Spring 上下文，保证用例轻量可跑）。
     */
    private StockReconcileServiceImpl buildService(SqlSession session) throws Exception {
        OGoodsInventoryMapper invMapper = session.getMapper(OGoodsInventoryMapper.class);
        OGoodsInventoryOperationMapper opMapper = session.getMapper(OGoodsInventoryOperationMapper.class);
        OGoodsInventoryBatchMapper batchMapper = session.getMapper(OGoodsInventoryBatchMapper.class);
        StockReconcileDiffMapper diffMapper = session.getMapper(StockReconcileDiffMapper.class);

        OGoodsInventoryServiceImpl invService = new OGoodsInventoryServiceImpl(opMapper);
        inject(invService, "baseMapper", invMapper);
        OGoodsInventoryBatchServiceImpl batchService = new OGoodsInventoryBatchServiceImpl();
        inject(batchService, "baseMapper", batchMapper);

        return new StockReconcileServiceImpl(invMapper, opMapper, batchMapper, diffMapper,
                invService, batchService);
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private StockReconcileDiff firstDiff(SqlSession session, int diffType) {
        List<StockReconcileDiff> list = session.getMapper(StockReconcileDiffMapper.class).selectList(
                new LambdaQueryWrapper<StockReconcileDiff>()
                        .eq(StockReconcileDiff::getInventoryId, INV_ID)
                        .eq(StockReconcileDiff::getDiffType, diffType)
                        .orderByDesc(StockReconcileDiff::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean canConnect() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tableExists(String table) {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '"
                             + table + "'")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }
}
