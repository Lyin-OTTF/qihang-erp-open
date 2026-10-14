package cn.qihangerp.agent.trace;
import org.junit.jupiter.api.Tag;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceService真实DB集成测试。
 * 验证agent_trace表的CRUD和查询能力。
 */
@Tag("it")
class TraceServiceIntegrationTest {

    private TraceService traceService;
    private JdbcTemplate jdbcTemplate;
    private String testTraceId;

    @BeforeEach
    void setUp() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");

        jdbcTemplate = new JdbcTemplate((DataSource) ds);

        // 使用MyBatis-Plus的Configuration（支持BaseMapper方法）
        Environment environment = new Environment("test", new JdbcTransactionFactory(), (DataSource) ds);
        MybatisConfiguration config = new MybatisConfiguration(environment);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TraceMapper.class);
        SqlSessionFactory sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(config);

        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        TraceMapper traceMapper = sqlSessionTemplate.getMapper(TraceMapper.class);
        traceService = new TraceService(traceMapper);

        testTraceId = "test-trace-" + System.currentTimeMillis();
    }

    @AfterEach
    void tearDown() {
        // 清理本次测试写入的 trace，避免污染 agent_trace
        jdbcTemplate.update("DELETE FROM agent_trace WHERE trace_id LIKE ?", testTraceId + "%");
    }

    @Test
    void testRecordAndQueryTraceChain() {
        // 1. 记录开始
        TraceEntry entry = traceService.recordStart(
                testTraceId, "OrderTools.getOrderList", "订单查询",
                1L, 100L, "[{\"page\":1}]", "LOW", null, null);
        assertNotNull(entry);
        assertNotNull(entry.getId());
        assertEquals("RUNNING", entry.getStatus());

        // 2. 记录成功
        traceService.recordSuccess(entry, "{\"total\":10}", 150L);

        // 3. 查询完整链路
        List<TraceEntry> chain = traceService.getTraceChain(testTraceId);
        assertFalse(chain.isEmpty(), "应能查到trace记录");
        assertEquals(1, chain.size());
        assertEquals("SUCCESS", chain.get(0).getStatus());
        assertEquals("OrderTools.getOrderList", chain.get(0).getToolName());
        assertEquals(150L, chain.get(0).getDurationMs());

        // 4. 统计span数
        int count = traceService.countTraceSpans(testTraceId);
        assertEquals(1, count);

        System.out.println("Trace链路查询验证通过: traceId=" + testTraceId + ", spans=" + count);
    }

    @Test
    void testRecordFailureTrace() {
        TraceEntry entry = traceService.recordStart(
                testTraceId + "-fail", "InventoryTools.deductStock", "库存操作",
                1L, 100L, "[{\"skuId\":1,\"qty\":5}]", "HIGH", "idem-key-123", "lock:inventory:1");

        traceService.recordFailure(entry, "INSUFFICIENT_STOCK", "库存不足", 50L);

        List<TraceEntry> failed = traceService.getFailedTraces(1L, 10);
        assertTrue(failed.size() >= 1, "应能查到失败的trace");
        boolean found = failed.stream().anyMatch(t ->
                "INSUFFICIENT_STOCK".equals(t.getErrorType()) && "FAILURE".equals(t.getStatus()));
        assertTrue(found, "应能找到库存不足的失败记录");

        System.out.println("失败Trace记录验证通过: errorType=INSUFFICIENT_STOCK");
    }

    @Test
    void testApprovalRequiredTrace() {
        TraceEntry entry = traceService.recordStart(
                testTraceId + "-approval", "OrderTools.cancelOrder", "订单操作",
                1L, 100L, "[{\"orderId\":\"ORD001\"}]", "CRITICAL", null, null);

        traceService.recordApprovalRequired(entry, "取消订单需要人工确认");

        List<TraceEntry> chain = traceService.getTraceChain(testTraceId + "-approval");
        assertEquals(1, chain.size());
        assertEquals("APPROVAL_REQUIRED", chain.get(0).getStatus());

        System.out.println("审批Trace记录验证通过: status=APPROVAL_REQUIRED");
    }

    @Test
    void testRecentTracesByMerchant() {
        // 插入几条测试数据
        for (int i = 0; i < 3; i++) {
            TraceEntry e = traceService.recordStart(
                    testTraceId + "-recent-" + i, "TestTool.method" + i, "测试分类",
                    1L, 100L, "[]", "LOW", null, null);
            traceService.recordSuccess(e, "ok", 10L);
        }

        List<TraceEntry> recent = traceService.getRecentTraces(1L, 5);
        assertTrue(recent.size() >= 3, "应能查到最近的trace");

        System.out.println("最近Trace查询验证通过: 返回" + recent.size() + "条");
    }
}
