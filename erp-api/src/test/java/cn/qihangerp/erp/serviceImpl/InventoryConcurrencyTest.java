package cn.qihangerp.erp.serviceImpl;
import org.junit.jupiter.api.Tag;

import cn.qihangerp.mapper.OGoodsInventoryMapper;
import cn.qihangerp.mapper.OGoodsInventoryOperationMapper;
import cn.qihangerp.model.entity.OGoodsInventory;
import cn.qihangerp.model.entity.OGoodsInventoryOperation;
import cn.qihangerp.service.impl.OGoodsInventoryServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 库存扣减并发安全测试。
 *
 * <p>验证原子条件更新（UPDATE ... WHERE quantity >= ?）在高并发下不超卖，
 * 且扣减与流水记录在同一事务内。</p>
 *
 * <p>测试场景：初始库存100，50个线程各扣减3，总需求150 > 库存100。
 * 预期：最多33次成功（33*3=99），最终库存>=0，绝不出现负库存。</p>
 */
@Tag("it")
class InventoryConcurrencyTest {

    private static final int INITIAL_STOCK = 100;
    private static final int THREAD_COUNT = 50;
    private static final int DEDUCT_PER_THREAD = 3;
    private static final String SKU_CODE = "CT" + (System.currentTimeMillis() % 100000);

    private static SqlSessionFactory sqlSessionFactory;
    private static Long testInventoryId;

    @BeforeAll
    static void setup() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");

        MybatisConfiguration config = new MybatisConfiguration();
        config.addMapper(OGoodsInventoryMapper.class);
        config.addMapper(OGoodsInventoryOperationMapper.class);
        Environment env = new Environment("test", new JdbcTransactionFactory(), (DataSource) ds);
        config.setEnvironment(env);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        // 插入测试库存
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventoryMapper mapper = session.getMapper(OGoodsInventoryMapper.class);
            OGoodsInventory inv = new OGoodsInventory();
            inv.setGoodsId(999001L);
            inv.setGoodsNum("TEST-GOODS");
            inv.setSkuId(999001L);
            inv.setSkuCode(SKU_CODE);
            inv.setGoodsName("并发测试商品");
            inv.setQuantity(INITIAL_STOCK);
            inv.setLockedQuantity(0);
            inv.setAvailableQuantity(INITIAL_STOCK);
            inv.setWarehouseId(1L);
            inv.setMerchantId(1L);
            inv.setShopId(0L);
            inv.setStockStatus(1);
            inv.setIsDelete(0);
            inv.setVersion(1);
            inv.setCreateTime(LocalDateTime.now());
            inv.setUpdateTime(LocalDateTime.now());
            mapper.insert(inv);
            testInventoryId = inv.getId();
        }
    }

    @AfterAll
    static void tearDown() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventoryMapper mapper = session.getMapper(OGoodsInventoryMapper.class);
            OGoodsInventoryOperationMapper opMapper = session.getMapper(OGoodsInventoryOperationMapper.class);
            // 清理流水
            opMapper.delete(new LambdaQueryWrapper<OGoodsInventoryOperation>()
                    .eq(OGoodsInventoryOperation::getInventoryDetailId, testInventoryId));
            // 清理库存
            mapper.deleteById(testInventoryId);
        }
    }

    /**
     * 反射设置ServiceImpl的baseMapper字段（遍历父类查找）。
     */
    private static void setBaseMapper(OGoodsInventoryServiceImpl service, OGoodsInventoryMapper mapper) throws Exception {
        Class<?> clazz = service.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField("baseMapper");
                f.setAccessible(true);
                f.set(service, mapper);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("baseMapper not found in hierarchy");
    }

    /**
     * 核心测试：50线程并发扣减，验证不超卖。
     */
    @Test
    void testConcurrentDeductNoOversell() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Exception> errors = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 所有线程同时起跑
                    // 每个线程开独立session（模拟并发请求）
                    try (SqlSession session = sqlSessionFactory.openSession(true)) {
                        OGoodsInventoryMapper mapper = session.getMapper(OGoodsInventoryMapper.class);
                        int affected = mapper.deductStockAtomic(testInventoryId, DEDUCT_PER_THREAD);
                        if (affected > 0) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 发令枪
        doneLatch.await();
        executor.shutdown();

        // 验证无异常
        assertTrue(errors.isEmpty(), "并发执行不应有异常: " + errors);

        // 查询最终库存
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventoryMapper mapper = session.getMapper(OGoodsInventoryMapper.class);
            OGoodsInventory finalInv = mapper.selectById(testInventoryId);

            // 核心断言1：库存绝不为负
            assertNotNull(finalInv);
            assertTrue(finalInv.getQuantity() >= 0,
                    "库存不能为负! actual=" + finalInv.getQuantity());
            assertTrue(finalInv.getAvailableQuantity() >= 0,
                    "可用库存不能为负! actual=" + finalInv.getAvailableQuantity());

            // 核心断言2：成功次数 * 每次扣减 = 实际减少量
            int expectedDeducted = successCount.get() * DEDUCT_PER_THREAD;
            int actualDeducted = INITIAL_STOCK - finalInv.getQuantity();
            assertEquals(expectedDeducted, actualDeducted,
                    "成功扣减次数与实际减少量不一致: success=" + successCount.get()
                            + ", expectedDeducted=" + expectedDeducted + ", actualDeducted=" + actualDeducted);

            // 核心断言3：成功+失败=总线程数
            assertEquals(THREAD_COUNT, successCount.get() + failCount.get(),
                    "每个线程必须有明确结果");

            // 核心断言4：由于总需求150>库存100，必然有失败
            assertTrue(failCount.get() > 0,
                    "库存不足时应有扣减失败，failCount=" + failCount.get());

            // 核心断言5：成功次数不超过 floor(100/3)=33
            assertTrue(successCount.get() <= INITIAL_STOCK / DEDUCT_PER_THREAD,
                    "成功次数不能超过库存允许的最大值: " + successCount.get());

            System.out.println("=== 并发扣减结果 ===");
            System.out.println("初始库存: " + INITIAL_STOCK);
            System.out.println("线程数: " + THREAD_COUNT + ", 每线程扣减: " + DEDUCT_PER_THREAD);
            System.out.println("成功: " + successCount.get() + ", 失败: " + failCount.get());
            System.out.println("最终库存: " + finalInv.getQuantity());
            System.out.println("实际扣减: " + actualDeducted);
        }
    }

    /**
     * 测试Service层：扣减成功后流水记录同步写入（事务一致性）。
     */
    @Test
    void testServiceDeductWritesFlowLog() throws Exception {
        // 用独立库存避免与并发测试冲突
        String sku = "FT" + (System.currentTimeMillis() % 100000);
        Long invId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OGoodsInventoryMapper mapper = session.getMapper(OGoodsInventoryMapper.class);
            OGoodsInventory inv = new OGoodsInventory();
            inv.setGoodsId(999002L);
            inv.setSkuId(999002L);
            inv.setSkuCode(sku);
            inv.setGoodsName("流水测试商品");
            inv.setQuantity(50);
            inv.setLockedQuantity(0);
            inv.setAvailableQuantity(50);
            inv.setWarehouseId(1L);
            inv.setMerchantId(1L);
            inv.setIsDelete(0);
            inv.setVersion(1);
            inv.setCreateTime(LocalDateTime.now());
            inv.setUpdateTime(LocalDateTime.now());
            mapper.insert(inv);
            invId = inv.getId();
        }

        try {
            // 构建Service（手动注入mapper）
            OGoodsInventoryServiceImpl service;
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                OGoodsInventoryMapper invMapper = session.getMapper(OGoodsInventoryMapper.class);
                OGoodsInventoryOperationMapper opMapper = session.getMapper(OGoodsInventoryOperationMapper.class);
                service = new OGoodsInventoryServiceImpl(opMapper);
                // 反射设置baseMapper（遍历父类查找protected字段）
                setBaseMapper(service, invMapper);

                // 扣减
                boolean result = service.deductStock(invId, 5);
                assertTrue(result, "扣减应成功");

                // 验证库存已扣减
                OGoodsInventory after = invMapper.selectById(invId);
                assertEquals(45, after.getQuantity(), "库存应扣减5");

                // 验证流水已写入
                Long flowCount = opMapper.selectCount(new LambdaQueryWrapper<OGoodsInventoryOperation>()
                        .eq(OGoodsInventoryOperation::getInventoryDetailId, invId));
                assertEquals(1, flowCount, "应写入1条流水");
            }

            // 测试库存不足返回false
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                OGoodsInventoryMapper invMapper = session.getMapper(OGoodsInventoryMapper.class);
                OGoodsInventoryOperationMapper opMapper = session.getMapper(OGoodsInventoryOperationMapper.class);
                service = new OGoodsInventoryServiceImpl(opMapper);
                setBaseMapper(service, invMapper);

                boolean result = service.deductStock(invId, 1000);
                assertFalse(result, "库存不足应返回false");

                // 库存不变
                OGoodsInventory after = invMapper.selectById(invId);
                assertEquals(45, after.getQuantity(), "库存不足时不应扣减");
            }
        } finally {
            // 清理
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                OGoodsInventoryMapper mapper = session.getMapper(OGoodsInventoryMapper.class);
                OGoodsInventoryOperationMapper opMapper = session.getMapper(OGoodsInventoryOperationMapper.class);
                opMapper.delete(new LambdaQueryWrapper<OGoodsInventoryOperation>()
                        .eq(OGoodsInventoryOperation::getInventoryDetailId, invId));
                mapper.deleteById(invId);
            }
        }
    }
}
