package cn.qihangerp.agent.audit;
import org.junit.jupiter.api.Tag;

import cn.qihangerp.agent.gateway.ToolGateway;
import cn.qihangerp.agent.gateway.ToolExecutionContext;
import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计落库集成测试（连接真实 MySQL 数据库 qihang-erp）。
 *
 * <p>验证完整链路：ToolGateway.execute → ActionLedgerService.recordAction →
 * ActionLedgerMapper.insert → agent_action_ledger 表。</p>
 *
 * <p>前置条件：MySQL 127.0.0.1:3306 可访问，agent_action_ledger 表已创建。</p>
 *
 * @author 回溯开发-阶段1
 * @since 2026-10-12
 */
@Tag("it")
class ActionLedgerPersistenceTest {

    private static final String TEST_TRACE_ID = "integration-test-" + System.currentTimeMillis();
    private static SqlSessionFactory sqlSessionFactory;
    private static ToolGateway toolGateway;

    @BeforeAll
    static void setup() throws Exception {
        // 1. 真实数据源
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser(System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        ds.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD", "1234"));

        // 2. MyBatis-Plus 配置
        MybatisConfiguration config = new MybatisConfiguration();
        config.addMapper(ActionLedgerMapper.class);
        Environment env = new Environment("integration-test", new JdbcTransactionFactory(), (DataSource) ds);
        config.setEnvironment(env);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        // 3. 构造 ActionLedgerService（真实 mapper + ObjectMapper）
        SqlSession session = sqlSessionFactory.openSession(true);
        ActionLedgerMapper mapper = session.getMapper(ActionLedgerMapper.class);
        ActionLedgerService ledgerService = new ActionLedgerService(mapper, new ObjectMapper());

        // 4. 构造 ToolGateway 并注入 actionLedgerService（字段是 @Autowired(required=false) private）
        toolGateway = new ToolGateway();
        Field field = ToolGateway.class.getDeclaredField("actionLedgerService");
        field.setAccessible(true);
        field.set(toolGateway, ledgerService);
    }

    @AfterAll
    static void cleanup() {
        // 清理测试数据
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ActionLedgerMapper mapper = session.getMapper(ActionLedgerMapper.class);
            List<ActionLedger> records = mapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActionLedger>()
                            .eq(ActionLedger::getTraceId, TEST_TRACE_ID)
            );
            for (ActionLedger r : records) {
                mapper.deleteById(r.getId());
            }
        } catch (Exception e) {
            // 清理失败不影响测试结果
        }
    }

    @Test
    void gatewayExecute_writesAuditRecordToDb() throws Exception {
        TestTool tool = new TestTool();
        ToolExecutionContext ctx = new ToolExecutionContext(1001L, 2001L, TEST_TRACE_ID);
        ctx.setSessionId("sess-integration");
        ctx.setSource("IntegrationTest");

        // 执行工具（走完整网关链路）
        Object result = toolGateway.execute(tool, "readData", ctx, "test-param");

        assertEquals("data:test-param", result);

        // 直接查库验证审计记录
        try (SqlSession session = sqlSessionFactory.openSession()) {
            ActionLedgerMapper mapper = session.getMapper(ActionLedgerMapper.class);
            List<ActionLedger> records = mapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActionLedger>()
                            .eq(ActionLedger::getTraceId, TEST_TRACE_ID)
            );

            assertEquals(1, records.size(), "应写入1条审计记录，实际: " + records.size());
            ActionLedger ledger = records.get(0);
            assertEquals(TEST_TRACE_ID, ledger.getTraceId());
            assertEquals("TestTool.readData", ledger.getToolName());
            assertEquals("集成测试", ledger.getToolScope());
            assertEquals("OBSERVE", ledger.getAutonomyLevel());
            assertEquals(1001L, ledger.getMerchantId());
            assertEquals(2001L, ledger.getUserId());
            assertEquals("sess-integration", ledger.getSessionId());
            assertTrue(ledger.getSuccess(), "审计记录应标记为成功");
            assertNotNull(ledger.getStartTime(), "startTime 不应为 null");
            assertNotNull(ledger.getEndTime(), "endTime 不应为 null");
            assertNotNull(ledger.getDurationMs(), "durationMs 不应为 null");
            assertNotNull(ledger.getInputParams(), "inputParams 应记录入参");
            assertNotNull(ledger.getOutputResult(), "outputResult 应记录出参");
        }
    }

    @Test
    void gatewayExecute_failureAlsoWritesAudit() throws Exception {
        // 用一个会抛异常的工具，验证失败也写审计
        FailingTool failingTool = new FailingTool();
        ToolExecutionContext ctx = new ToolExecutionContext(1002L, 2002L, TEST_TRACE_ID);

        ToolGateway.ToolExecutionException ex = assertThrows(
                ToolGateway.ToolExecutionException.class,
                () -> toolGateway.execute(failingTool, "failMethod", ctx)
        );
        assertNotNull(ex);

        // 查库验证失败审计记录
        try (SqlSession session = sqlSessionFactory.openSession()) {
            ActionLedgerMapper mapper = session.getMapper(ActionLedgerMapper.class);
            List<ActionLedger> records = mapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActionLedger>()
                            .eq(ActionLedger::getTraceId, TEST_TRACE_ID)
                            .eq(ActionLedger::getToolName, "FailingTool.failMethod")
            );
            assertEquals(1, records.size(), "失败也应写入1条审计记录");
            ActionLedger ledger = records.get(0);
            assertFalse(ledger.getSuccess(), "失败记录 success 应为 false");
            assertNotNull(ledger.getErrorMessage(), "失败记录应包含 errorMessage");
        }
    }

    /** 测试用工具：正常执行 */
    public static class TestTool {
        @Tool(description = "测试读取")
        @ToolPolicy(level = AutonomyLevel.OBSERVE, category = "集成测试")
        public String readData(String param) {
            return "data:" + param;
        }
    }

    /** 测试用工具：执行时抛异常 */
    public static class FailingTool {
        @Tool(description = "测试失败")
        @ToolPolicy(level = AutonomyLevel.OBSERVE, category = "集成测试")
        public String failMethod() {
            throw new RuntimeException("故意失败");
        }
    }
}
