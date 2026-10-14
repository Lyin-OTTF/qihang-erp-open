package cn.qihangerp.erp.serviceImpl.ai;
import org.junit.jupiter.api.Tag;

import cn.qihangerp.agent.tenant.TenantContext;
import cn.qihangerp.mapper.ShopOrderMapper;
import cn.qihangerp.model.entity.ShopOrder;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 DB 租户隔离端到端测试。
 *
 * <p>在 qihang-erp 库的 oms_shop_order 表中插入两个租户的测试订单，
 * 模拟 OrderTools.listOrders 的查询逻辑（含 TenantContext 租户过滤），
 * 验证租户A看不到租户B的数据。测试后清理数据。</p>
 *
 * <p>前置条件：MySQL 127.0.0.1:3306 可访问，oms_shop_order 表存在。</p>
 *
 * @author 回溯开发-阶段1
 * @since 2026-10-12
 */
@Tag("it")
class TenantIsolationE2ETest {

    private static final Long TENANT_A = 999001L;
    private static final Long TENANT_B = 999002L;
    private static final String ORDER_A = "E2E-ISOLATION-A-" + System.currentTimeMillis();
    private static final String ORDER_B = "E2E-ISOLATION-B-" + System.currentTimeMillis();

    private static SqlSessionFactory sqlSessionFactory;
    private static Long idA;
    private static Long idB;

    @BeforeAll
    static void setup() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");

        MybatisConfiguration config = new MybatisConfiguration();
        config.addMapper(ShopOrderMapper.class);
        Environment env = new Environment("e2e", new JdbcTransactionFactory(), (DataSource) ds);
        config.setEnvironment(env);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        // 插入两个租户的测试订单
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ShopOrderMapper mapper = session.getMapper(ShopOrderMapper.class);

            ShopOrder orderA = buildTestOrder(TENANT_A, ORDER_A);
            mapper.insert(orderA);
            idA = orderA.getId();

            ShopOrder orderB = buildTestOrder(TENANT_B, ORDER_B);
            mapper.insert(orderB);
            idB = orderB.getId();
        }
    }

    @AfterAll
    static void cleanup() {
        TenantContext.clear();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ShopOrderMapper mapper = session.getMapper(ShopOrderMapper.class);
            if (idA != null) mapper.deleteById(idA);
            if (idB != null) mapper.deleteById(idB);
        }
    }

    @Test
    void tenantA_cannotSeeTenantB_orders() {
        TenantContext.setTenantId(TENANT_A);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            ShopOrderMapper mapper = session.getMapper(ShopOrderMapper.class);

            // 完全复刻 OrderTools.listOrders 的查询逻辑
            LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<>();
            Long merchantId = TenantContext.getTenantId();
            if (merchantId != null) {
                wrapper.eq(ShopOrder::getMerchantId, merchantId);
            }
            wrapper.ge(ShopOrder::getCreateOn, LocalDateTime.now().minusDays(30));
            wrapper.orderByDesc(ShopOrder::getCreateOn);
            wrapper.last("LIMIT 100");

            List<ShopOrder> list = mapper.selectList(wrapper);

            // 断言1：返回结果中不应包含租户B的测试订单
            boolean foundB = list.stream().anyMatch(o -> ORDER_B.equals(o.getOrderId()));
            assertFalse(foundB, "租户A不应看到租户B的订单 orderId=" + ORDER_B);

            // 断言2：返回结果中应包含租户A的测试订单
            boolean foundA = list.stream().anyMatch(o -> ORDER_A.equals(o.getOrderId()));
            assertTrue(foundA, "租户A应能看到自己的订单 orderId=" + ORDER_A);

            // 断言3：所有返回订单的 merchantId 都是 TENANT_A
            for (ShopOrder o : list) {
                assertEquals(TENANT_A, o.getMerchantId(),
                        "返回的订单 merchantId 应全部为 " + TENANT_A + "，实际: " + o.getMerchantId());
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void tenantB_cannotSeeTenantA_orders() {
        TenantContext.setTenantId(TENANT_B);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            ShopOrderMapper mapper = session.getMapper(ShopOrderMapper.class);

            LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<>();
            Long merchantId = TenantContext.getTenantId();
            if (merchantId != null) {
                wrapper.eq(ShopOrder::getMerchantId, merchantId);
            }
            wrapper.ge(ShopOrder::getCreateOn, LocalDateTime.now().minusDays(30));
            wrapper.orderByDesc(ShopOrder::getCreateOn);
            wrapper.last("LIMIT 100");

            List<ShopOrder> list = mapper.selectList(wrapper);

            boolean foundA = list.stream().anyMatch(o -> ORDER_A.equals(o.getOrderId()));
            assertFalse(foundA, "租户B不应看到租户A的订单");

            boolean foundB = list.stream().anyMatch(o -> ORDER_B.equals(o.getOrderId()));
            assertTrue(foundB, "租户B应能看到自己的订单");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void noTenantContext_seesBothTenants() {
        // 不设置 TenantContext → merchantId 为 null → 不过滤（向后兼容行为）
        try (SqlSession session = sqlSessionFactory.openSession()) {
            ShopOrderMapper mapper = session.getMapper(ShopOrderMapper.class);

            LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<>();
            wrapper.ge(ShopOrder::getCreateOn, LocalDateTime.now().minusDays(30));
            wrapper.orderByDesc(ShopOrder::getCreateOn);
            wrapper.last("LIMIT 100");

            List<ShopOrder> list = mapper.selectList(wrapper);

            boolean foundA = list.stream().anyMatch(o -> ORDER_A.equals(o.getOrderId()));
            boolean foundB = list.stream().anyMatch(o -> ORDER_B.equals(o.getOrderId()));
            assertTrue(foundA, "无租户上下文时应看到租户A的订单");
            assertTrue(foundB, "无租户上下文时应看到租户B的订单");
        }
    }

    private static ShopOrder buildTestOrder(Long merchantId, String orderId) {
        ShopOrder order = new ShopOrder();
        order.setMerchantId(merchantId);
        order.setShopId(1L);
        order.setOrderId(orderId);
        order.setGoodsAmount(100);
        order.setOrderAmount(100);
        order.setPaymentAmount(100);
        order.setOrderStatus(0);
        order.setOrderMode(0);
        order.setCreateOn(LocalDateTime.now());
        return order;
    }
}
