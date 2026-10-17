package cn.qihangerp.agent.approval;
import org.junit.jupiter.api.Tag;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
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
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审批流集成测试（搁置项攻关-任务9）。
 *
 * <p>使用真实 MySQL 验证：创建审批、批准/拒绝、标记执行/失败、
 * isApproved检查、待审批列表、过期清理等完整流程。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Tag("it")
class ApprovalServiceTest {

    private static final String TEST_TRACE = "approval-test-" + System.currentTimeMillis();
    private static SqlSessionFactory sqlSessionFactory;
    private static ApprovalService approvalService;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setup() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser(System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        ds.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD", "1234"));

        MybatisConfiguration config = new MybatisConfiguration();
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        config.addInterceptor(interceptor);
        config.addMapper(ApprovalRequestMapper.class);
        Environment env = new Environment("test", new JdbcTransactionFactory(), (DataSource) ds);
        config.setEnvironment(env);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        jdbcTemplate = new JdbcTemplate((DataSource) ds);

        SqlSession session = sqlSessionFactory.openSession(true);
        ApprovalRequestMapper mapper = session.getMapper(ApprovalRequestMapper.class);
        approvalService = new ApprovalService(mapper, new ObjectMapper(), null);
    }

    @AfterAll
    static void cleanup() {
        jdbcTemplate.update("DELETE FROM agent_approval WHERE trace_id LIKE ?", TEST_TRACE + "%");
    }

    @Test
    void createApproval_persistsRecord() {
        Long id = approvalService.createApproval(
                TEST_TRACE + "-1", "updateOrderSellerMemo", "修改卖家备注",
                1L, 100L, new Object[]{"ORD001", "test memo"});

        assertNotNull(id, "创建审批应返回ID");
        ApprovalRequest found = jdbcTemplate.queryForObject(
                "SELECT * FROM agent_approval WHERE id = ?",
                (rs, row) -> {
                    ApprovalRequest a = new ApprovalRequest();
                    a.setId(rs.getLong("id"));
                    a.setStatus(rs.getString("status"));
                    a.setToolName(rs.getString("tool_name"));
                    a.setTraceId(rs.getString("trace_id"));
                    return a;
                }, id);
        assertNotNull(found);
        assertEquals("PENDING", found.getStatus());
        assertEquals("updateOrderSellerMemo", found.getToolName());
    }

    @Test
    void approve_changesStatus() {
        Long id = approvalService.createApproval(
                TEST_TRACE + "-2", "testTool", "desc", 1L, 100L, new Object[]{});

        boolean result = approvalService.approve(id, 999L, "同意执行");
        assertTrue(result);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM agent_approval WHERE id = ?", String.class, id);
        assertEquals("APPROVED", status);
    }

    @Test
    void reject_changesStatus() {
        Long id = approvalService.createApproval(
                TEST_TRACE + "-3", "testTool", "desc", 1L, 100L, new Object[]{});

        boolean result = approvalService.reject(id, 999L, "风险过高");
        assertTrue(result);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM agent_approval WHERE id = ?", String.class, id);
        assertEquals("REJECTED", status);
    }

    @Test
    void approve_nonPending_returnsFalse() {
        Long id = approvalService.createApproval(
                TEST_TRACE + "-4", "testTool", "desc", 1L, 100L, new Object[]{});
        approvalService.approve(id, 999L, "先批准");

        // 再次批准应失败
        boolean result = approvalService.approve(id, 999L, "重复批准");
        assertFalse(result, "已批准的审批不能再次批准");
    }

    @Test
    void approve_nonExistent_returnsFalse() {
        boolean result = approvalService.approve(999999L, 999L, "不存在");
        assertFalse(result);
    }

    @Test
    void isApproved_returnsTrue_afterApprove() {
        String traceId = TEST_TRACE + "-5";
        approvalService.createApproval(traceId, "riskyTool", "desc", 1L, 100L, new Object[]{});

        assertFalse(approvalService.isApproved(traceId, "riskyTool"), "批准前应为false");

        Long id = approvalService.createApproval(traceId, "riskyTool", "desc", 1L, 100L, new Object[]{});
        approvalService.approve(id, 999L, "同意");

        assertTrue(approvalService.isApproved(traceId, "riskyTool"), "批准后应为true");
    }

    @Test
    void isApproved_differentTool_returnsFalse() {
        String traceId = TEST_TRACE + "-6";
        Long id = approvalService.createApproval(traceId, "toolA", "desc", 1L, 100L, new Object[]{});
        approvalService.approve(id, 999L, "同意");

        assertFalse(approvalService.isApproved(traceId, "toolB"), "不同工具不应视为已批准");
    }

    @Test
    void markExecuted_updatesStatus() {
        String traceId = TEST_TRACE + "-7";
        Long id = approvalService.createApproval(traceId, "testTool", "desc", 1L, 100L, new Object[]{});
        approvalService.approve(id, 999L, "同意");

        approvalService.markExecuted(id, "执行成功，更新了3条记录");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM agent_approval WHERE id = ?", String.class, id);
        assertEquals("EXECUTED", status);
    }

    @Test
    void markFailed_returnsToPending() {
        String traceId = TEST_TRACE + "-8";
        Long id = approvalService.createApproval(traceId, "testTool", "desc", 1L, 100L, new Object[]{});
        approvalService.approve(id, 999L, "同意");

        approvalService.markFailed(id, "执行失败：数据库连接超时");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM agent_approval WHERE id = ?", String.class, id);
        assertEquals("PENDING", status, "失败后应回到PENDING允许重试");
    }

    @Test
    void listPending_returnsOnlyPending() {
        String traceId = TEST_TRACE + "-9";
        Long pendingId = approvalService.createApproval(traceId, "toolA", "desc", 1L, 100L, new Object[]{});
        Long approvedId = approvalService.createApproval(traceId, "toolB", "desc", 1L, 100L, new Object[]{});
        approvalService.approve(approvedId, 999L, "同意");

        var page = approvalService.listPending(1L, 1, 50);
        List<ApprovalRequest> pendingList = page.getRecords();

        boolean hasPending = pendingList.stream().anyMatch(a -> pendingId.equals(a.getId()));
        boolean hasApproved = pendingList.stream().anyMatch(a -> approvedId.equals(a.getId()));
        assertTrue(hasPending, "待审批列表应包含PENDING记录");
        assertFalse(hasApproved, "待审批列表不应包含APPROVED记录");
    }

    @Test
    void listByTraceId_returnsAllForTrace() {
        String traceId = TEST_TRACE + "-10";
        approvalService.createApproval(traceId, "toolA", "desc", 1L, 100L, new Object[]{});
        approvalService.createApproval(traceId, "toolB", "desc", 1L, 100L, new Object[]{});

        List<ApprovalRequest> list = approvalService.listByTraceId(traceId);
        assertEquals(2, list.size(), "同一traceId应有2条审批记录");
    }

    @Test
    void tenantScope_blocksCrossTenantReadAndApprove() {
        String traceId = TEST_TRACE + "-11";
        Long tenant1Approval = approvalService.createApproval(
                traceId, "toolA", "desc", 1L, 100L, new Object[]{"A"});
        Long tenant2Approval = approvalService.createApproval(
                traceId, "toolB", "desc", 2L, 200L, new Object[]{"B"});

        List<ApprovalRequest> tenant1List = approvalService.listByTraceId(traceId, 1L);
        assertEquals(1, tenant1List.size());
        assertEquals(tenant1Approval, tenant1List.get(0).getId());
        assertNull(approvalService.getById(tenant2Approval, 1L));
        assertFalse(approvalService.approve(tenant2Approval, 1L, 999L, "越权批准"));
        assertEquals("PENDING", approvalService.getById(tenant2Approval).getStatus());

        assertTrue(approvalService.approve(tenant1Approval, 1L, 999L, "本租户批准"));
    }

    @Test
    void claimApproved_isSingleUseAndMatchesArguments() {
        String traceId = TEST_TRACE + "-12";
        Object[] approvedArgs = new Object[]{100L, 200L};
        Long approvalId = approvalService.createApproval(
                traceId, "workflowTool", "desc", 1L, 100L, approvedArgs);
        approvalService.approve(approvalId, 1L, 999L, "同意");

        assertFalse(approvalService.claimApproved(
                approvalId, "workflowTool", 1L, new Object[]{100L, 201L}),
                "参数不一致不能领取审批单");
        assertTrue(approvalService.claimApproved(
                approvalId, "workflowTool", 1L, approvedArgs));
        assertFalse(approvalService.claimApproved(
                approvalId, "workflowTool", 1L, approvedArgs),
                "同一审批单不能被重复领取");
    }
}
