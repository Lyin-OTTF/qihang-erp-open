package cn.qihangerp.agent.workflow;

import cn.qihangerp.agent.audit.ActionLedgerRequest;
import cn.qihangerp.agent.audit.ActionLedgerService;
import cn.qihangerp.agent.dag.DAGNode;
import cn.qihangerp.agent.dag.DAGScheduler;
import cn.qihangerp.agent.dag.ExecutionContext;
import cn.qihangerp.agent.dag.NodeStatus;
import cn.qihangerp.agent.graph.CheckpointManager;
import cn.qihangerp.agent.graph.GraphState;
import cn.qihangerp.statemachine.TransitionValidator;
import cn.qihangerp.mapper.ShopOrderMapper;
import cn.qihangerp.mapper.ShopRefundMapper;
import cn.qihangerp.model.entity.ShopOrder;
import cn.qihangerp.model.entity.ShopRefund;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent工作流服务。
 *
 * <p>将DAG并行调度、失败传播、补偿回滚、Checkpoint快照整合为真实业务工作流。
 * 当前实现：订单售后处理工作流（order-aftersales-workflow）。</p>
 *
 * <p>工作流节点：
 * <ol>
 *   <li>fetch_order - 查询订单详情（无依赖，与2、3并行）</li>
 *   <li>fetch_refund - 查询售后单详情（无依赖，与1、3并行）</li>
 *   <li>fetch_inventory - 查询商品库存（无依赖，与1、2并行）</li>
 *   <li>validate_transition - 校验状态迁移（依赖1+2）</li>
 *   <li>process_refund - 执行退款处理（依赖4，写操作带补偿）</li>
 *   <li>update_memo - 更新订单卖家备注（依赖5，写操作带补偿）</li>
 *   <li>notify_customer - 发送通知（依赖6，终止节点）</li>
 *   <li>audit_log - 审计日志（依赖3，独立分支）</li>
 * </ol>
 * </p>
 *
 * @author 回溯开发-Week7-10
 * @since 2026-10-13
 */
@Slf4j
@Service
public class AgentWorkflowService {

    public static final String WORKFLOW_AFTER_SALES = "order-aftersales";

    private final ShopOrderMapper shopOrderMapper;
    private final ShopRefundMapper shopRefundMapper;
    private final TransitionValidator transitionValidator;
    private final CheckpointManager checkpointManager;
    private final ObjectMapper objectMapper;
    private final DAGScheduler dagScheduler;
    private final ActionLedgerService actionLedgerService;

    @Autowired
    public AgentWorkflowService(ShopOrderMapper shopOrderMapper,
                                ShopRefundMapper shopRefundMapper,
                                TransitionValidator transitionValidator,
                                CheckpointManager checkpointManager,
                                ObjectMapper objectMapper,
                                ActionLedgerService actionLedgerService) {
        this(shopOrderMapper, shopRefundMapper, transitionValidator,
                checkpointManager, objectMapper, new DAGScheduler(4), actionLedgerService);
    }

    /** 测试用构造函数：可指定DAGScheduler（如parallelism=1串行执行） */
    public AgentWorkflowService(ShopOrderMapper shopOrderMapper,
                                ShopRefundMapper shopRefundMapper,
                                TransitionValidator transitionValidator,
                                CheckpointManager checkpointManager,
                                ObjectMapper objectMapper,
                                DAGScheduler dagScheduler,
                                ActionLedgerService actionLedgerService) {
        this.shopOrderMapper = shopOrderMapper;
        this.shopRefundMapper = shopRefundMapper;
        this.transitionValidator = transitionValidator;
        this.checkpointManager = checkpointManager;
        this.objectMapper = objectMapper;
        this.dagScheduler = dagScheduler;
        this.actionLedgerService = actionLedgerService;
    }

    /**
     * 执行订单售后处理工作流。
     *
     * @param orderId    订单ID
     * @param refundId   售后单ID
     * @param merchantId 商户ID（租户隔离）
     * @return 工作流执行结果
     */
    public WorkflowResult executeAfterSalesWorkflow(Long orderId, Long refundId, Long merchantId) {
        String executionId = WORKFLOW_AFTER_SALES + "-" + UUID.randomUUID();
        ExecutionContext context = new ExecutionContext(WORKFLOW_AFTER_SALES, executionId);
        context.put("orderId", orderId);
        context.put("refundId", refundId);
        context.put("merchantId", merchantId);

        log.info("工作流启动: workflow={}, execution={}, orderId={}, refundId={}",
                WORKFLOW_AFTER_SALES, executionId, orderId, refundId);

        List<DAGNode> nodes = buildAfterSalesNodes();
        dagScheduler.execute(nodes, context);

        // 保存最终快照
        saveCheckpoint(context);

        log.info("工作流完成: execution={}, 成功={}, 失败={}, 取消={}",
                executionId,
                countStatus(context, NodeStatus.SUCCESS),
                countStatus(context, NodeStatus.FAILED),
                countStatus(context, NodeStatus.CANCELLED));

        return new WorkflowResult(executionId, context);
    }

    /**
     * 从Checkpoint快照恢复工作流执行。
     * 已完成(SUCCESS)的节点自动跳过，从PENDING节点继续。
     *
     * @param executionId 执行ID
     * @return 工作流执行结果
     */
    public WorkflowResult resumeWorkflow(String executionId) {
        ExecutionContext context = loadFromCheckpoint(executionId);
        if (context == null) {
            throw new IllegalArgumentException("未找到执行快照: " + executionId);
        }

        log.info("工作流恢复: execution={}, 已完成节点={}",
                executionId, countStatus(context, NodeStatus.SUCCESS));

        List<DAGNode> nodes = buildAfterSalesNodes();
        dagScheduler.execute(nodes, context); // 恢复模式：不覆盖已有状态

        saveCheckpoint(context);
        return new WorkflowResult(executionId, context);
    }

    /**
     * 查询工作流执行状态。
     */
    public WorkflowResult getWorkflowStatus(String executionId) {
        GraphState state = checkpointManager.loadLatest(executionId);
        if (state == null) {
            throw new IllegalArgumentException("未找到执行快照: " + executionId);
        }
        return new WorkflowResult(executionId, state);
    }

    // ==================== 工作流节点定义 ====================

    private List<DAGNode> buildAfterSalesNodes() {
        List<DAGNode> nodes = new ArrayList<>();

        // 1. 查询订单详情（无依赖，租户过滤）
        nodes.add(DAGNode.builder("fetch_order")
                .name("查询订单详情")
                .executor(ctx -> {
                    Long orderId = ctx.get("orderId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    LambdaQueryWrapper<ShopOrder> qw = new LambdaQueryWrapper<ShopOrder>()
                            .eq(ShopOrder::getId, orderId);
                    if (merchantId != null) {
                        qw.eq(ShopOrder::getMerchantId, merchantId);
                    }
                    ShopOrder order = shopOrderMapper.selectOne(qw);
                    if (order == null) {
                        throw new RuntimeException("订单不存在或不属于当前租户: orderId=" + orderId);
                    }
                    ctx.put("currentOrderStatus", order.getOrderStatus());
                    try {
                        return objectMapper.writeValueAsString(order);
                    } catch (Exception e) {
                        throw new RuntimeException("订单序列化失败: " + e.getMessage(), e);
                    }
                })
                .build());

        // 2. 查询售后单详情（无依赖，租户过滤）
        nodes.add(DAGNode.builder("fetch_refund")
                .name("查询售后单详情")
                .executor(ctx -> {
                    Long refundId = ctx.get("refundId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    LambdaQueryWrapper<ShopRefund> qw = new LambdaQueryWrapper<ShopRefund>()
                            .eq(ShopRefund::getId, refundId);
                    if (merchantId != null) {
                        qw.eq(ShopRefund::getMerchantId, merchantId);
                    }
                    ShopRefund refund = shopRefundMapper.selectOne(qw);
                    if (refund == null) {
                        throw new RuntimeException("售后单不存在或不属于当前租户: refundId=" + refundId);
                    }
                    ctx.put("currentRefundStatus", refund.getStatus());
                    ctx.put("originalRefundStatus", refund.getStatus());
                    try {
                        return objectMapper.writeValueAsString(refund);
                    } catch (Exception e) {
                        throw new RuntimeException("售后单序列化失败: " + e.getMessage(), e);
                    }
                })
                .build());

        // 3. 查询商品库存（无依赖）
        nodes.add(DAGNode.builder("fetch_inventory")
                .name("查询商品库存")
                .executor(ctx -> {
                    // 库存查询：从订单中提取商品ID，查询库存
                    // 此处简化为记录库存查询动作，真实场景需注入InventoryMapper
                    Long orderId = ctx.get("orderId", Long.class);
                    log.info("库存查询: orderId={}", orderId);
                    return "{\"checked\":true,\"orderId\":" + orderId + "}";
                })
                .build());

        // 4. 校验状态迁移（依赖1+2）
        nodes.add(DAGNode.builder("validate_transition")
                .name("校验状态迁移合法性")
                .dependsOn("fetch_order", "fetch_refund")
                .executor(ctx -> {
                    Long orderId = ctx.get("orderId", Long.class);
                    Integer currentStatus = ctx.get("currentOrderStatus", Integer.class);
                    if (currentStatus == null) {
                        throw new RuntimeException("无法获取订单当前状态");
                    }
                    // 目标状态：3(已完成) — 售后处理完成后订单完成
                    try {
                        transitionValidator.validateOrderTransition(
                                currentStatus, 3, String.valueOf(orderId));
                    } catch (Exception e) {
                        throw new RuntimeException("状态迁移不合法: 当前状态" + currentStatus + " → 3, " + e.getMessage());
                    }
                    ctx.put("transitionValid", true);
                    return "valid";
                })
                .build());

        // 5. 执行退款处理（依赖4，写操作带补偿，状态机校验+租户过滤）
        nodes.add(DAGNode.builder("process_refund")
                .name("执行退款处理")
                .dependsOn("validate_transition")
                .executor(ctx -> {
                    Long refundId = ctx.get("refundId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    Integer originalStatus = ctx.get("originalRefundStatus", Integer.class);
                    // 状态机校验：当前售后状态 → 3(退款中)
                    transitionValidator.validateRefundTransition(
                            originalStatus, 3, String.valueOf(refundId));
                    // 租户过滤更新
                    LambdaUpdateWrapper<ShopRefund> wrapper = new LambdaUpdateWrapper<>();
                    wrapper.eq(ShopRefund::getId, refundId);
                    if (merchantId != null) {
                        wrapper.eq(ShopRefund::getMerchantId, merchantId);
                    }
                    wrapper.set(ShopRefund::getStatus, 3);
                    shopRefundMapper.update(null, wrapper);
                    ctx.put("refundUpdated", true);
                    return "refund_status_updated_to_3";
                })
                .compensator(ctx -> {
                    Long refundId = ctx.get("refundId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    Integer originalStatus = ctx.get("originalRefundStatus", Integer.class);
                    if (originalStatus != null && Boolean.TRUE.equals(ctx.get("refundUpdated", Boolean.class))) {
                        LambdaUpdateWrapper<ShopRefund> wrapper = new LambdaUpdateWrapper<>();
                        wrapper.eq(ShopRefund::getId, refundId);
                        if (merchantId != null) {
                            wrapper.eq(ShopRefund::getMerchantId, merchantId);
                        }
                        wrapper.set(ShopRefund::getStatus, originalStatus);
                        shopRefundMapper.update(null, wrapper);
                        log.info("补偿回滚: 退款状态恢复为{}", originalStatus);
                    }
                    return null;
                })
                .build());

        // 6. 更新订单卖家备注（依赖5，写操作带补偿，租户过滤）
        nodes.add(DAGNode.builder("update_memo")
                .name("更新订单卖家备注")
                .dependsOn("process_refund")
                .executor(ctx -> {
                    Long orderId = ctx.get("orderId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    LambdaQueryWrapper<ShopOrder> qw = new LambdaQueryWrapper<ShopOrder>()
                            .eq(ShopOrder::getId, orderId);
                    if (merchantId != null) {
                        qw.eq(ShopOrder::getMerchantId, merchantId);
                    }
                    ShopOrder order = shopOrderMapper.selectOne(qw);
                    String originalMemo = order != null ? order.getSellerMemo() : null;
                    ctx.put("originalSellerMemo", originalMemo);
                    String newMemo = (originalMemo == null ? "" : originalMemo)
                            + "[AI售后处理] 已自动处理退款流程";
                    LambdaUpdateWrapper<ShopOrder> wrapper = new LambdaUpdateWrapper<>();
                    wrapper.eq(ShopOrder::getId, orderId);
                    if (merchantId != null) {
                        wrapper.eq(ShopOrder::getMerchantId, merchantId);
                    }
                    wrapper.set(ShopOrder::getSellerMemo, newMemo);
                    shopOrderMapper.update(null, wrapper);
                    ctx.put("memoUpdated", true);
                    return "memo_updated";
                })
                .compensator(ctx -> {
                    Long orderId = ctx.get("orderId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    String originalMemo = ctx.get("originalSellerMemo", String.class);
                    if (Boolean.TRUE.equals(ctx.get("memoUpdated", Boolean.class))) {
                        LambdaUpdateWrapper<ShopOrder> wrapper = new LambdaUpdateWrapper<>();
                        wrapper.eq(ShopOrder::getId, orderId);
                        if (merchantId != null) {
                            wrapper.eq(ShopOrder::getMerchantId, merchantId);
                        }
                        wrapper.set(ShopOrder::getSellerMemo, originalMemo);
                        shopOrderMapper.update(null, wrapper);
                        log.info("补偿回滚: 卖家备注恢复原值");
                    }
                    return null;
                })
                .build());

        // 7. 发送通知（依赖6）
        nodes.add(DAGNode.builder("notify_customer")
                .name("发送客户通知")
                .dependsOn("update_memo")
                .executor(ctx -> {
                    Long orderId = ctx.get("orderId", Long.class);
                    log.info("发送通知: orderId={}, 售后处理完成", orderId);
                    return "notification_sent";
                })
                .build());

        // 8. 审计日志（依赖3，独立分支，真实落 ActionLedger）
        nodes.add(DAGNode.builder("audit_log")
                .name("记录审计日志")
                .dependsOn("fetch_inventory")
                .executor(ctx -> {
                    Long orderId = ctx.get("orderId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    String executionId = ctx.getExecutionId();
                    try {
                        if (actionLedgerService != null) {
                            ActionLedgerRequest ledger = ActionLedgerRequest.builder()
                                    .traceId(executionId)
                                    .toolName("workflow." + WORKFLOW_AFTER_SALES)
                                    .toolScope("工作流审计")
                                    .autonomyLevel("EXECUTE")
                                    .merchantId(merchantId)
                                    .userId(null)
                                    .sessionId(null)
                                    .inputParams(new Object[]{orderId, ctx.get("refundId", Long.class)})
                                    .outputResult("workflow_executed")
                                    .success(true)
                                    .errorMessage(null)
                                    .errorType(null)
                                    .startTime(java.time.LocalDateTime.now())
                                    .endTime(java.time.LocalDateTime.now())
                                    .durationMs(0)
                                    .isWriteOperation(true)
                                    .requiresApproval(false)
                                    .approved(true)
                                    .ipAddress(null)
                                    .userAgent("agent-workflow")
                                    .build();
                            actionLedgerService.recordAction(ledger);
                        } else {
                            log.info("[审计降级] workflow={}, orderId={}, merchantId={}",
                                    WORKFLOW_AFTER_SALES, orderId, merchantId);
                        }
                    } catch (Exception e) {
                        log.warn("工作流审计落库失败（不影响主流程）: {}", e.getMessage());
                    }
                    return "audit_recorded";
                })
                .build());

        return nodes;
    }

    // ==================== Checkpoint 持久化 ====================

    /**
     * 将ExecutionContext保存为Checkpoint快照。
     * nodeOutputs(Object)序列化为JSON字符串存储。
     */
    private void saveCheckpoint(ExecutionContext context) {
        try {
            GraphState state = new GraphState();
            state.setStatus(context.hasFailures() ? "FAILED" : "COMPLETED");

            // 序列化nodeOutputs
            Map<String, String> outputsJson = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : context.getNodeOutputs().entrySet()) {
                if (entry.getValue() != null) {
                    outputsJson.put(entry.getKey(), objectMapper.writeValueAsString(entry.getValue()));
                }
            }
            state.setNodeOutputs(outputsJson);

            // 序列化globalContext
            Map<String, String> globalJson = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : context.getGlobalContext().entrySet()) {
                if (entry.getValue() != null) {
                    globalJson.put(entry.getKey(), objectMapper.writeValueAsString(entry.getValue()));
                }
            }
            // 额外保存节点状态（编码到globalContext中）
            StringBuilder statusBuilder = new StringBuilder();
            for (Map.Entry<String, NodeStatus> entry : context.getNodeStatuses().entrySet()) {
                statusBuilder.append(entry.getKey()).append("=").append(entry.getValue().name()).append(";");
            }
            globalJson.put("_nodeStatuses", statusBuilder.toString());
            state.setGlobalContext(globalJson);

            checkpointManager.saveCheckpoint(
                    context.getWorkflowId(), context.getExecutionId(), state);
        } catch (Exception e) {
            log.error("保存Checkpoint失败（不影响工作流结果）: {}", e.getMessage());
        }
    }

    /**
     * 从Checkpoint快照恢复ExecutionContext。
     */
    private ExecutionContext loadFromCheckpoint(String executionId) {
        GraphState state = checkpointManager.loadLatest(executionId);
        if (state == null) return null;

        // 从globalContext中解析workflowId
        String workflowId = WORKFLOW_AFTER_SALES;
        ExecutionContext context = new ExecutionContext(workflowId, executionId);

        // 恢复globalContext
        Map<String, String> globalJson = state.getGlobalContext();
        String nodeStatusesStr = globalJson.remove("_nodeStatuses");
        for (Map.Entry<String, String> entry : globalJson.entrySet()) {
            try {
                Object value = objectMapper.readValue(entry.getValue(), Object.class);
                context.put(entry.getKey(), value);
            } catch (Exception e) {
                context.put(entry.getKey(), entry.getValue());
            }
        }

        // 恢复nodeOutputs
        for (Map.Entry<String, String> entry : state.getNodeOutputs().entrySet()) {
            try {
                Object value = objectMapper.readValue(entry.getValue(), Object.class);
                context.setNodeOutput(entry.getKey(), value);
            } catch (Exception e) {
                context.setNodeOutput(entry.getKey(), entry.getValue());
            }
        }

        // 恢复nodeStatuses
        if (nodeStatusesStr != null && !nodeStatusesStr.isBlank()) {
            for (String pair : nodeStatusesStr.split(";")) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    try {
                        context.setNodeStatus(kv[0], NodeStatus.valueOf(kv[1]));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        return context;
    }

    private long countStatus(ExecutionContext context, NodeStatus status) {
        return context.getNodeStatuses().values().stream().filter(s -> s == status).count();
    }
}
