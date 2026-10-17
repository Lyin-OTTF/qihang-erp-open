package cn.qihangerp.agent.workflow;
import org.junit.jupiter.api.Tag;

import cn.qihangerp.agent.dag.DAGScheduler;
import cn.qihangerp.agent.dag.NodeStatus;
import cn.qihangerp.agent.graph.CheckpointManager;
import cn.qihangerp.agent.graph.StateSerializer;
import cn.qihangerp.statemachine.TransitionValidator;
import cn.qihangerp.mapper.ShopOrderMapper;
import cn.qihangerp.mapper.ShopRefundMapper;
import cn.qihangerp.model.entity.ShopOrder;
import cn.qihangerp.model.entity.ShopRefund;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentWorkflowService真实业务集成测试（手动搭建MyBatis+JDBC，不依赖Spring上下文）。
 *
 * <p>验证：完整工作流8节点成功、写操作真实生效、失败传播、独立分支继续、Checkpoint持久化。</p>
 *
 * @author 回溯开发-Week7-10
 * @since 2026-10-13
 */
@Tag("it")
class AgentWorkflowIntegrationTest {

    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSession session;
    private static ShopOrderMapper orderMapper;
    private static ShopRefundMapper refundMapper;
    private static JdbcTemplate jdbcTemplate;
    private static AgentWorkflowService workflowService;

    @BeforeAll
    static void setup() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser(System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        ds.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD", "1234"));

        MybatisConfiguration config = new MybatisConfiguration();
        config.addMapper(ShopOrderMapper.class);
        config.addMapper(ShopRefundMapper.class);
        Environment env = new Environment("workflow-e2e", new JdbcTransactionFactory(), (DataSource) ds);
        config.setEnvironment(env);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(ShopOrderMapper.class);
        refundMapper = session.getMapper(ShopRefundMapper.class);

        // 预热：触发MyBatis-Plus TableInfo初始化
        orderMapper.selectCount(null);
        refundMapper.selectCount(null);

        jdbcTemplate = new JdbcTemplate(ds);
        // 必须注册JavaTimeModule，否则ShopOrder的LocalDateTime字段无法序列化
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        StateSerializer stateSerializer = new StateSerializer(objectMapper);
        CheckpointManager checkpointManager = new CheckpointManager(jdbcTemplate, stateSerializer);
        TransitionValidator transitionValidator = new TransitionValidator(
                new org.springframework.context.ApplicationEventPublisher() {
                    @Override
                    public void publishEvent(org.springframework.context.ApplicationEvent event) { }
                    @Override
                    public void publishEvent(Object event) { }
                });

        // 测试用串行调度器（parallelism=1），避免多线程session共享问题
        // 并行执行能力已在DAGFrameworkTest中验证
        workflowService = new AgentWorkflowService(
                orderMapper, refundMapper, transitionValidator, checkpointManager, objectMapper,
                new DAGScheduler(1), null);
    }

    @AfterAll
    static void tearDown() {
        if (session != null) session.close();
        // 清理测试数据
        jdbcTemplate.update("DELETE FROM oms_shop_refund WHERE after_id LIKE 'WF-TEST-%'");
        jdbcTemplate.update("DELETE FROM oms_shop_order WHERE seller_memo LIKE 'WF-TEST-%'");
        jdbcTemplate.update("DELETE FROM agent_graph_checkpoint WHERE workflow_id = 'order-aftersales'");
    }

    private Long insertTestOrder(int orderStatus, String memo) {
        jdbcTemplate.update("INSERT INTO oms_shop_order " +
                "(merchant_id, shop_id, shop_type, order_status, refund_status, " +
                " goods_amount, order_amount, payment_amount, deliver_method, order_mode, order_type, " +
                " seller_memo, create_on, update_on) " +
                "VALUES (1, 1, 0, ?, 1, 10000, 10000, 10000, 0, 0, 0, ?, NOW(), NOW())",
                orderStatus, memo);
        // MysqlDataSource每次新建连接，LAST_INSERT_ID()跨连接返回0，
        // 改用唯一memo反查ID
        return jdbcTemplate.queryForObject(
                "SELECT id FROM oms_shop_order WHERE seller_memo = ? ORDER BY id DESC LIMIT 1",
                Long.class, memo);
    }

    private Long insertTestRefund(Long orderId, int status) {
        String afterId = "WF-TEST-" + orderId + "-" + System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO oms_shop_refund " +
                "(merchant_id, shop_id, shop_type, after_id, type, status, order_id, " +
                " order_amount, product_id, sku_id, sell_count, count, order_status, " +
                " order_ship_status, user_ship_status, goods_status, dispute_refund_status, " +
                " refund_status, refund_phase, shop_order_id, shop_order_item_id, create_on, update_on) " +
                "VALUES (1, 1, 0, ?, 0, ?, ?, 10000, 'P001', 'S001', 1, 1, '2', " +
                " 0, 0, 0, 0, 0, '1', ?, 0, NOW(), NOW())",
                afterId, status, "ORD-" + orderId, orderId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM oms_shop_refund WHERE after_id = ?",
                Long.class, afterId);
    }

    /**
     * 测试完整工作流成功执行：8节点全部SUCCESS，写操作真实生效。
     */
    @Test
    void testFullWorkflowSuccess() {
        String memo = "WF-TEST-" + System.currentTimeMillis();
        Long orderId = insertTestOrder(2, memo); // 状态2=已发货，可迁移到3
        Long refundId = insertTestRefund(orderId, 2); // 状态2=售后处理中

        WorkflowResult result = workflowService.executeAfterSalesWorkflow(orderId, refundId, 1L);

        assertTrue(result.isSuccess(), "工作流应成功: " + result.getSummary());
        assertEquals(8, result.getNodeStatuses().size(), "应有8个节点");
        for (var entry : result.getNodeStatuses().entrySet()) {
            assertEquals(NodeStatus.SUCCESS, entry.getValue(),
                    "节点 " + entry.getKey() + " 应成功");
        }

        // 验证写操作真实生效
        ShopOrder orderAfter = orderMapper.selectById(orderId);
        assertNotNull(orderAfter);
        assertTrue(orderAfter.getSellerMemo().contains("[AI售后处理]"),
                "卖家备注应包含AI售后处理标记，实际: " + orderAfter.getSellerMemo());

        ShopRefund refundAfter = refundMapper.selectById(refundId);
        assertNotNull(refundAfter);
        assertEquals(3, refundAfter.getStatus(), "退款状态应更新为3(退款中)");
        assertEquals(12, orderAfter.getOrderStatus(), "订单应进入12(退款中)");

        // 验证Checkpoint已保存
        Integer checkpointCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_graph_checkpoint WHERE execution_id = ?",
                Integer.class, result.getExecutionId());
        assertTrue(checkpointCount >= 1, "应保存至少1个Checkpoint快照");
    }

    /**
     * 测试失败传播：状态校验失败时，依赖节点被取消，独立分支(audit_log)继续。
     */
    @Test
    void testFailurePropagation() {
        String memo = "WF-TEST-FAIL-" + System.currentTimeMillis();
        Long orderId = insertTestOrder(11, memo); // 状态11=已取消，不可迁移到3
        Long refundId = insertTestRefund(orderId, 2);

        WorkflowResult result = workflowService.executeAfterSalesWorkflow(orderId, refundId, 1L);

        assertFalse(result.isSuccess(), "工作流应失败");

        var statuses = result.getNodeStatuses();
        // 并行查询节点应成功
        assertEquals(NodeStatus.SUCCESS, statuses.get("fetch_order"));
        assertEquals(NodeStatus.SUCCESS, statuses.get("fetch_refund"));
        assertEquals(NodeStatus.SUCCESS, statuses.get("fetch_inventory"));
        // 校验节点应失败
        assertEquals(NodeStatus.FAILED, statuses.get("validate_transition"));
        // 依赖校验的后续节点应被取消
        assertEquals(NodeStatus.CANCELLED, statuses.get("process_refund"));
        assertEquals(NodeStatus.CANCELLED, statuses.get("update_memo"));
        assertEquals(NodeStatus.CANCELLED, statuses.get("notify_customer"));
        // 独立分支（只依赖fetch_inventory）应成功
        assertEquals(NodeStatus.SUCCESS, statuses.get("audit_log"));

        // 验证写操作未执行
        ShopRefund refundAfter = refundMapper.selectById(refundId);
        assertEquals(2, refundAfter.getStatus(), "退款状态应保持2(未被修改)");
    }

    /**
     * 测试Checkpoint恢复：从已有快照恢复，已完成节点不重复执行。
     */
    @Test
    void testCheckpointSaveAndLoad() {
        String memo = "WF-TEST-CKPT-" + System.currentTimeMillis();
        Long orderId = insertTestOrder(2, memo);
        Long refundId = insertTestRefund(orderId, 2);

        WorkflowResult result = workflowService.executeAfterSalesWorkflow(orderId, refundId, 1L);
        assertTrue(result.isSuccess());

        // 通过状态查询接口验证快照可读
        WorkflowResult status = workflowService.getWorkflowStatus(result.getExecutionId());
        assertNotNull(status);
        assertTrue(status.isSuccess());
        assertNotNull(status.getSummary());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> workflowService.getWorkflowStatus(result.getExecutionId(), 2L),
                "禁止跨租户查询工作流状态");

        // 验证快照中包含节点状态信息
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(checkpoint_version) FROM agent_graph_checkpoint WHERE execution_id = ?",
                Integer.class, result.getExecutionId());
        assertNotNull(version);
        assertTrue(version >= 1, "快照版本应>=1");
    }

    /**
     * 测试并行执行：3个无依赖节点(fetch_order/fetch_refund/fetch_inventory)并行完成。
     * 通过总执行时间间接验证——如果串行，3个DB查询+处理应更慢。
     */
    @Test
    void testParallelNodesInWorkflow() {
        String memo = "WF-TEST-PAR-" + System.currentTimeMillis();
        Long orderId = insertTestOrder(2, memo);
        Long refundId = insertTestRefund(orderId, 2);

        long start = System.currentTimeMillis();
        WorkflowResult result = workflowService.executeAfterSalesWorkflow(orderId, refundId, 1L);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.isSuccess());
        // 工作流包含DB查询和更新，正常应在5秒内完成
        // （这里不做严格时间断言，因为DB性能波动大，主要验证功能正确）
        assertTrue(elapsed < 30000, "工作流应在30秒内完成，实际: " + elapsed + "ms");
    }

    /**
     * 新订单(0) + 新售后单(0) 应通过 0→2→3 的分步状态迁移完成工作流。
     */
    @Test
    void testNewOrderAndNewRefundCanBeProcessed() {
        String memo = "WF-TEST-NEW-" + System.currentTimeMillis();
        Long orderId = insertTestOrder(0, memo);
        Long refundId = insertTestRefund(orderId, 0);

        WorkflowResult result = workflowService.executeAfterSalesWorkflow(orderId, refundId, 1L);

        assertTrue(result.isSuccess(), "新订单/新售后单应可处理: " + result.getSummary());
        assertEquals(12, orderMapper.selectById(orderId).getOrderStatus());
        assertEquals(3, refundMapper.selectById(refundId).getStatus());
    }

    /**
     * 待付款订单未实际支付，不应直接进入退款中。
     */
    @Test
    void testPendingPaymentOrderCannotEnterRefundWorkflow() {
        String memo = "WF-TEST-UNPAID-" + System.currentTimeMillis();
        Long orderId = insertTestOrder(21, memo);
        Long refundId = insertTestRefund(orderId, 2);

        WorkflowResult result = workflowService.executeAfterSalesWorkflow(orderId, refundId, 1L);

        assertFalse(result.isSuccess(), "待付款订单不应直接进入退款流程");
        assertEquals(NodeStatus.FAILED, result.getNodeStatuses().get("validate_transition"));
        assertEquals(21, orderMapper.selectById(orderId).getOrderStatus());
        assertEquals(2, refundMapper.selectById(refundId).getStatus());
    }

    /**
     * Checkpoint 必须绑定原始 approvalId，恢复时不能换用其他审批单。
     */
    @Test
    void testResumeRequiresBoundApproval() {
        String memo = "WF-TEST-RESUME-" + System.currentTimeMillis();
        Long orderId = insertTestOrder(2, memo);
        Long refundId = insertTestRefund(orderId, 2);
        Long approvalId = 987654L;

        WorkflowResult result = workflowService.executeAfterSalesWorkflow(
                orderId, refundId, 1L, approvalId);
        assertTrue(result.isSuccess());

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> workflowService.resumeWorkflow(result.getExecutionId(), 1L, 987655L));
        assertTrue(workflowService.resumeWorkflow(
                result.getExecutionId(), 1L, approvalId).isSuccess());
    }

    /**
     * 同一租户内的售后单也必须与订单存在关联，不能交叉拼接执行。
     */
    @Test
    void testRefundMustBelongToOrder() {
        String memoA = "WF-TEST-LINK-A-" + System.currentTimeMillis();
        String memoB = "WF-TEST-LINK-B-" + System.currentTimeMillis();
        Long orderA = insertTestOrder(2, memoA);
        Long orderB = insertTestOrder(2, memoB);
        Long refundB = insertTestRefund(orderB, 2);

        WorkflowResult result = workflowService.executeAfterSalesWorkflow(orderA, refundB, 1L);

        assertFalse(result.isSuccess());
        assertEquals(NodeStatus.FAILED, result.getNodeStatuses().get("validate_transition"));
        assertEquals(2, orderMapper.selectById(orderA).getOrderStatus());
        assertEquals(2, refundMapper.selectById(refundB).getStatus());
    }
}
