package cn.qihangerp.agent.evaluation;
import org.junit.jupiter.api.Tag;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bad Case 收集机制真实 DB 集成测试。
 *
 * <p>验证完整链路：BadCaseService.submit → BadCaseMapper.insert → agent_bad_case 表；
 * 以及查询、状态流转、统计等功能。</p>
 *
 * @author 回溯开发-阶段2
 * @since 2026-10-12
 */
@Tag("it")
class BadCasePersistenceTest {

    private static final String TEST_TRACE_ID = "badcase-test-" + System.currentTimeMillis();
    private static SqlSessionFactory sqlSessionFactory;
    private static BadCaseService badCaseService;

    @BeforeAll
    static void setup() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");

        MybatisConfiguration config = new MybatisConfiguration();
        config.addMapper(BadCaseMapper.class);
        Environment env = new Environment("badcase-test", new JdbcTransactionFactory(), (DataSource) ds);
        config.setEnvironment(env);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        SqlSession session = sqlSessionFactory.openSession(true);
        BadCaseMapper mapper = session.getMapper(BadCaseMapper.class);
        badCaseService = new BadCaseService(mapper);
    }

    @AfterAll
    static void cleanup() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            BadCaseMapper mapper = session.getMapper(BadCaseMapper.class);
            List<BadCase> records = mapper.selectList(
                    new LambdaQueryWrapper<BadCase>()
                            .eq(BadCase::getTraceId, TEST_TRACE_ID)
            );
            for (BadCase r : records) {
                mapper.deleteById(r.getId());
            }
        }
    }

    @Test
    void submit_persistsToDb() {
        BadCase bc = BadCase.builder()
                .traceId(TEST_TRACE_ID)
                .userId(1001L)
                .merchantId(2001L)
                .sessionId("sess-001")
                .question("怎么取消订单？")
                .aiAnswer("您可以在订单页面点击取消按钮。")
                .feedbackType("WRONG_ANSWER")
                .feedbackComment("实际没有取消按钮，应该走售后流程")
                .build();

        Long id = badCaseService.submit(bc);
        assertNotNull(id, "submit 应返回记录ID");

        // 直接查库验证
        try (SqlSession session = sqlSessionFactory.openSession()) {
            BadCaseMapper mapper = session.getMapper(BadCaseMapper.class);
            BadCase saved = mapper.selectById(id);
            assertNotNull(saved);
            assertEquals(TEST_TRACE_ID, saved.getTraceId());
            assertEquals(1001L, saved.getUserId());
            assertEquals(2001L, saved.getMerchantId());
            assertEquals("怎么取消订单？", saved.getQuestion());
            assertEquals("WRONG_ANSWER", saved.getFeedbackType());
            assertEquals("OPEN", saved.getStatus(), "新提交的Bad Case状态应为OPEN");
            assertNotNull(saved.getCreatedAt());
        }
    }

    @Test
    void submit_rejectsInvalidFeedbackType() {
        BadCase bc = BadCase.builder()
                .traceId(TEST_TRACE_ID)
                .question("测试")
                .feedbackType("INVALID_TYPE")
                .build();

        assertThrows(IllegalArgumentException.class, () -> badCaseService.submit(bc));
    }

    @Test
    void submit_rejectsEmptyQuestion() {
        BadCase bc = BadCase.builder()
                .traceId(TEST_TRACE_ID)
                .feedbackType("UNHELPFUL")
                .build();

        assertThrows(IllegalArgumentException.class, () -> badCaseService.submit(bc));
    }

    @Test
    void updateStatus_changesStatusAndSetsResolvedAt() {
        // 先提交一条
        BadCase bc = BadCase.builder()
                .traceId(TEST_TRACE_ID)
                .userId(1002L)
                .question("库存查询返回null")
                .aiAnswer("库存为0")
                .feedbackType("UNSAFE")
                .build();
        Long id = badCaseService.submit(bc);

        // 更新为 RESOLVED
        boolean success = badCaseService.updateStatus(id, "RESOLVED", "已修复库存查询bug", 999L);
        assertTrue(success);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            BadCaseMapper mapper = session.getMapper(BadCaseMapper.class);
            BadCase updated = mapper.selectById(id);
            assertEquals("RESOLVED", updated.getStatus());
            assertEquals("已修复库存查询bug", updated.getResolution());
            assertEquals(999L, updated.getResolvedBy());
            assertNotNull(updated.getResolvedAt(), "RESOLVED状态应设置resolvedAt");
        }
    }

    @Test
    void updateStatus_rejectsInvalidStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> badCaseService.updateStatus(1L, "INVALID", null, null));
    }

    @Test
    void countByFeedbackType_returnsCorrectCounts() {
        // 提交不同类型的Bad Case
        badCaseService.submit(BadCase.builder()
                .traceId(TEST_TRACE_ID).userId(1003L).question("q1").feedbackType("WRONG_ANSWER").build());
        badCaseService.submit(BadCase.builder()
                .traceId(TEST_TRACE_ID).userId(1003L).question("q2").feedbackType("WRONG_ANSWER").build());
        badCaseService.submit(BadCase.builder()
                .traceId(TEST_TRACE_ID).userId(1003L).question("q3").feedbackType("UNHELPFUL").build());

        Map<String, Long> stats = badCaseService.countByFeedbackType(null);
        // 只断言本次测试提交的类型（库中可能有其他数据）
        assertTrue(stats.getOrDefault("WRONG_ANSWER", 0L) >= 2,
                "WRONG_ANSWER 应至少2条，实际: " + stats.get("WRONG_ANSWER"));
        assertTrue(stats.getOrDefault("UNHELPFUL", 0L) >= 1,
                "UNHELPFUL 应至少1条，实际: " + stats.get("UNHELPFUL"));
    }

    @Test
    void getByTraceId_returnsRelatedCases() {
        List<BadCase> cases = badCaseService.getByTraceId(TEST_TRACE_ID);
        assertFalse(cases.isEmpty(), "应能查到traceId关联的Bad Case");
        for (BadCase c : cases) {
            assertEquals(TEST_TRACE_ID, c.getTraceId());
        }
    }
}
