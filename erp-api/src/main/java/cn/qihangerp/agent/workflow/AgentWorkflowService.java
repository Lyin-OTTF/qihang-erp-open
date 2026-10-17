package cn.qihangerp.agent.workflow;

import cn.qihangerp.agent.audit.ActionLedgerRequest;
import cn.qihangerp.agent.audit.ActionLedgerService;
import cn.qihangerp.agent.dag.DAGNode;
import cn.qihangerp.agent.dag.DAGScheduler;
import cn.qihangerp.agent.dag.ExecutionContext;
import cn.qihangerp.agent.dag.NodeStatus;
import cn.qihangerp.agent.graph.CheckpointManager;
import cn.qihangerp.agent.graph.GraphState;
import cn.qihangerp.erp.notify.NotifierService;
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
import org.springframework.security.access.AccessDeniedException;
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
    public static final String WORKFLOW_APPROVAL_TOOL = "workflow." + WORKFLOW_AFTER_SALES;

    private final ShopOrderMapper shopOrderMapper;
    private final ShopRefundMapper shopRefundMapper;
    private final TransitionValidator transitionValidator;
    private final CheckpointManager checkpointManager;
    private final ObjectMapper objectMapper;
    private final DAGScheduler dagScheduler;
    private final ActionLedgerService actionLedgerService;

    /** 通知服务：走 SysAlertChannel 配置的飞书/钉钉/企微 Webhook；不可用时降级为日志 */
    @Autowired(required = false)
    private NotifierService notifierService;

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
        return executeAfterSalesWorkflow(orderId, refundId, merchantId, null);
    }

    /**
     * 执行订单售后处理工作流，并绑定触发本次执行的审批单。
     */
    public WorkflowResult executeAfterSalesWorkflow(Long orderId, Long refundId,
                                                    Long merchantId, Long approvalId) {
        if (merchantId == null) {
            throw new AccessDeniedException("工作流执行缺少租户上下文");
        }
        String executionId = WORKFLOW_AFTER_SALES + "-" + UUID.randomUUID();
        ExecutionContext context = new ExecutionContext(WORKFLOW_AFTER_SALES, executionId);
        context.put("orderId", orderId);
        context.put("refundId", refundId);
        context.put("merchantId", merchantId);
        if (approvalId != null) {
            context.put("approvalId", approvalId);
        }

        log.info("工作流启动: workflow={}, execution={}, orderId={}, refundId={}",
                WORKFLOW_AFTER_SALES, executionId, orderId, refundId);

        List<DAGNode> nodes = buildAfterSalesNodes();

        // 注册节点完成监听器：每个节点完成后保存 checkpoint，支持中途崩溃恢复
        dagScheduler.setNodeCompleteListener((nodeId, status, ctx) -> {
            if (status == NodeStatus.SUCCESS || status == NodeStatus.FAILED) {
                saveCheckpoint(ctx);
                log.debug("节点完成后保存checkpoint: node={}, status={}", nodeId, status);
            }
        });

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
        return resumeWorkflow(executionId, null);
    }

    /**
     * 从Checkpoint快照恢复工作流执行，并校验执行快照所属租户。
     */
    public WorkflowResult resumeWorkflow(String executionId, Long merchantId) {
        return resumeWorkflow(executionId, merchantId, null);
    }

    /**
     * 从Checkpoint恢复工作流，并校验执行快照与审批单绑定关系。
     */
    public WorkflowResult resumeWorkflow(String executionId, Long merchantId, Long approvalId) {
        ExecutionContext context = loadFromCheckpoint(executionId);
        if (context == null) {
            throw new IllegalArgumentException("未找到执行快照: " + executionId);
        }
        assertWorkflowTenant(context, merchantId);
        assertWorkflowApproval(context, approvalId);

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
        return getWorkflowStatus(executionId, null);
    }

    /**
     * 查询工作流执行状态，并校验执行快照所属租户。
     */
    public WorkflowResult getWorkflowStatus(String executionId, Long merchantId) {
        ExecutionContext context = loadFromCheckpoint(executionId);
        if (context == null) {
            throw new IllegalArgumentException("未找到执行快照: " + executionId);
        }
        assertWorkflowTenant(context, merchantId);
        return new WorkflowResult(executionId, context);
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
                    ctx.put("currentOrderVersion", order.getVersion());
                    if (order.getOrderId() != null) {
                        ctx.put("currentOrderNumber", order.getOrderId());
                    }
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
                    ctx.put("currentRefundVersion", refund.getVersion());
                    if (refund.getShopOrderId() != null) {
                        ctx.put("refundShopOrderId", refund.getShopOrderId());
                    }
                    if (refund.getOrderId() != null) {
                        ctx.put("refundOrderNumber", refund.getOrderId());
                    }
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
                    Long linkedOrderId = ctx.get("refundShopOrderId", Long.class);
                    if (linkedOrderId != null && !orderId.equals(linkedOrderId)) {
                        throw new RuntimeException("售后单不属于当前订单: orderId=" + orderId
                                + ", refundLinkedOrderId=" + linkedOrderId);
                    }
                    // 售后处理启动后订单进入12(退款中)，退款完成后由后续流程关闭订单
                    try {
                        transitionValidator.validateOrderTransition(
                                currentStatus, 12, String.valueOf(orderId));
                    } catch (Exception e) {
                        throw new RuntimeException("状态迁移不合法: 当前状态" + currentStatus + " → 12, " + e.getMessage());
                    }
                    ctx.put("transitionValid", true);
                    return "valid";
                })
                .build());

        // 5. 执行退款处理（依赖4，写操作带补偿，状态机校验+租户过滤）
        // 同时更新售后单状态和订单状态（订单进入退款中）
        nodes.add(DAGNode.builder("process_refund")
                .name("执行退款处理")
                .dependsOn("validate_transition")
                .executor(ctx -> {
                    Long refundId = ctx.get("refundId", Long.class);
                    Long orderId = ctx.get("orderId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    Integer originalRefundStatus = ctx.get("originalRefundStatus", Integer.class);
                    Integer originalOrderStatus = ctx.get("currentOrderStatus", Integer.class);
                    Integer orderVersion = loadOrderVersion(orderId, merchantId);
                    Integer refundVersion = loadRefundVersion(refundId, merchantId);

                    // 状态机校验：当前售后状态 → 3(退款中)
                    if (originalRefundStatus == null) {
                        throw new RuntimeException("无法获取售后单当前状态，refundId=" + refundId);
                    }
                    boolean orderUpdated = false;
                    boolean refundUpdated = false;
                    try {
                        // 订单进入12(退款中)；同状态无需重复更新
                        if (originalOrderStatus == null) {
                            throw new RuntimeException("无法获取订单当前状态，orderId=" + orderId);
                        }
                        if (!Integer.valueOf(12).equals(originalOrderStatus)) {
                            if (!updateOrderStatusOptimistically(
                                    orderId, merchantId, orderVersion, 12)) {
                                throw new RuntimeException("订单状态更新冲突，请刷新后重试: orderId=" + orderId);
                            }
                            orderUpdated = true;
                            orderVersion = incrementVersion(orderVersion);
                            ctx.put("currentOrderVersion", orderVersion);
                            ctx.put("orderStatusUpdated", true);
                            log.info("订单进入退款中: orderId={}, {} -> 12", orderId, originalOrderStatus);
                        }

                        // 新售后单/待用户处理/待买家发货先进入2(处理中)，再进入3(退款中)
                        int workingStatus = originalRefundStatus;
                        if (originalRefundStatus == 0
                                || originalRefundStatus == 5
                                || originalRefundStatus == 6) {
                            transitionValidator.validateRefundTransition(
                                    originalRefundStatus, 2, String.valueOf(refundId));
                            if (!updateRefundStatusOptimistically(
                                    refundId, merchantId, refundVersion, 2)) {
                                throw new RuntimeException("售后状态更新冲突，请刷新后重试: refundId=" + refundId);
                            }
                            refundUpdated = true;
                            workingStatus = 2;
                            refundVersion = incrementVersion(refundVersion);
                            ctx.put("currentRefundVersion", refundVersion);
                            ctx.put("refundUpdated", true);
                        }

                        if (!Integer.valueOf(3).equals(workingStatus)) {
                            transitionValidator.validateRefundTransition(
                                    workingStatus, 3, String.valueOf(refundId));
                            if (!updateRefundStatusOptimistically(
                                    refundId, merchantId, refundVersion, 3)) {
                                throw new RuntimeException("售后状态更新冲突，请刷新后重试: refundId=" + refundId);
                            }
                            refundUpdated = true;
                            refundVersion = incrementVersion(refundVersion);
                            ctx.put("currentRefundVersion", refundVersion);
                            ctx.put("refundUpdated", true);
                        }
                        return "refund_status_updated_to_3_and_order_refunding";
                    } catch (RuntimeException e) {
                        // 当前节点失败时不会触发节点自身compensator，先回滚本次已写入的状态
                        if (refundUpdated) {
                            updateRefundStatusOptimistically(
                                    refundId, merchantId, refundVersion, originalRefundStatus);
                            ctx.put("refundUpdated", false);
                        }
                        if (orderUpdated) {
                            updateOrderStatusOptimistically(
                                    orderId, merchantId, orderVersion, originalOrderStatus);
                            ctx.put("orderStatusUpdated", false);
                        }
                        throw e;
                    }
                })
                .compensator(ctx -> {
                    Long refundId = ctx.get("refundId", Long.class);
                    Long orderId = ctx.get("orderId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    Integer originalRefundStatus = ctx.get("originalRefundStatus", Integer.class);
                    Integer originalOrderStatus = ctx.get("currentOrderStatus", Integer.class);
                    Integer orderVersion = loadOrderVersion(orderId, merchantId);
                    Integer refundVersion = loadRefundVersion(refundId, merchantId);

                    // 回滚售后单状态
                    if (originalRefundStatus != null && Boolean.TRUE.equals(ctx.get("refundUpdated", Boolean.class))) {
                        updateRefundStatusOptimistically(
                                refundId, merchantId, refundVersion, originalRefundStatus);
                        log.info("补偿回滚: 退款状态恢复为{}", originalRefundStatus);
                    }

                    // 回滚订单状态
                    if (originalOrderStatus != null && Boolean.TRUE.equals(ctx.get("orderStatusUpdated", Boolean.class))) {
                        updateOrderStatusOptimistically(
                                orderId, merchantId, orderVersion, originalOrderStatus);
                        log.info("补偿回滚: 订单状态恢复为{}", originalOrderStatus);
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
                    if (order == null) {
                        throw new RuntimeException("订单不存在或不属于当前租户: orderId=" + orderId);
                    }
                    String originalMemo = order.getSellerMemo();
                    ctx.put("originalSellerMemo", originalMemo);
                    ctx.put("currentOrderVersion", order.getVersion());
                    // 修复：检查是否已追加过，避免 resume 重复执行时重复追加
                    String memoSuffix = "[AI售后处理] 已自动处理退款流程";
                    if (originalMemo != null && originalMemo.contains(memoSuffix)) {
                        log.info("卖家备注已包含AI售后标记，跳过重复追加: orderId={}", orderId);
                        ctx.put("memoUpdated", false);
                        return "memo_already_contains_marker_skipped";
                    }
                    String newMemo = (originalMemo == null ? "" : originalMemo)
                            + memoSuffix;
                    if (!updateOrderMemoOptimistically(
                            orderId, merchantId, order.getVersion(), newMemo)) {
                        throw new RuntimeException("订单备注更新冲突，请刷新后重试: orderId=" + orderId);
                    }
                    ctx.put("memoUpdated", true);
                    ctx.put("currentOrderVersion", incrementVersion(order.getVersion()));
                    return "memo_updated";
                })
                .compensator(ctx -> {
                    Long orderId = ctx.get("orderId", Long.class);
                    Long merchantId = ctx.get("merchantId", Long.class);
                    String originalMemo = ctx.get("originalSellerMemo", String.class);
                    Integer orderVersion = ctx.get("currentOrderVersion", Integer.class);
                    if (Boolean.TRUE.equals(ctx.get("memoUpdated", Boolean.class))) {
                        boolean restored = updateOrderMemoOptimistically(
                                orderId, merchantId, orderVersion, originalMemo);
                        if (restored) {
                            ctx.put("currentOrderVersion", incrementVersion(orderVersion));
                            log.info("补偿回滚: 卖家备注恢复原值");
                        }
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
                    Long merchantId = ctx.get("merchantId", Long.class);
                    String title = "售后流程已进入退款处理";
                    String content = "订单 " + orderId + " 的售后流程已进入退款处理，请相关同事知悉。";
                    boolean sent = false;
                    if (notifierService != null) {
                        try {
                            sent = notifierService.notifyAll(title, content);
                        } catch (Exception e) {
                            log.warn("工作流通知发送失败（不影响主流程）: {}", e.getMessage());
                        }
                    }
                    log.info("发送通知: orderId={}, merchantId={}, sent={}", orderId, merchantId, sent);
                    return sent ? "notification_sent" : "notification_skipped";
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

    // ==================== 乐观锁更新 ====================

    private boolean updateOrderStatusOptimistically(Long orderId, Long merchantId,
                                                    Integer expectedVersion, int targetStatus) {
        LambdaUpdateWrapper<ShopOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ShopOrder::getId, orderId);
        if (merchantId != null) {
            wrapper.eq(ShopOrder::getMerchantId, merchantId);
        }
        if (expectedVersion == null) {
            wrapper.isNull(ShopOrder::getVersion);
        } else {
            wrapper.eq(ShopOrder::getVersion, expectedVersion);
        }
        wrapper.set(ShopOrder::getOrderStatus, targetStatus)
                .setSql("version = version + 1");
        return shopOrderMapper.update(null, wrapper) == 1;
    }

    private boolean updateRefundStatusOptimistically(Long refundId, Long merchantId,
                                                     Integer expectedVersion, int targetStatus) {
        LambdaUpdateWrapper<ShopRefund> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ShopRefund::getId, refundId);
        if (merchantId != null) {
            wrapper.eq(ShopRefund::getMerchantId, merchantId);
        }
        if (expectedVersion == null) {
            wrapper.isNull(ShopRefund::getVersion);
        } else {
            wrapper.eq(ShopRefund::getVersion, expectedVersion);
        }
        wrapper.set(ShopRefund::getStatus, targetStatus)
                .setSql("version = version + 1");
        return shopRefundMapper.update(null, wrapper) == 1;
    }

    private boolean updateOrderMemoOptimistically(Long orderId, Long merchantId,
                                                  Integer expectedVersion, String memo) {
        LambdaUpdateWrapper<ShopOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ShopOrder::getId, orderId);
        if (merchantId != null) {
            wrapper.eq(ShopOrder::getMerchantId, merchantId);
        }
        if (expectedVersion == null) {
            wrapper.isNull(ShopOrder::getVersion);
        } else {
            wrapper.eq(ShopOrder::getVersion, expectedVersion);
        }
        wrapper.set(ShopOrder::getSellerMemo, memo)
                .setSql("version = version + 1");
        return shopOrderMapper.update(null, wrapper) == 1;
    }

    private Integer incrementVersion(Integer version) {
        return version == null ? null : version + 1;
    }

    private Integer loadOrderVersion(Long orderId, Long merchantId) {
        LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShopOrder::getId, orderId);
        if (merchantId != null) {
            wrapper.eq(ShopOrder::getMerchantId, merchantId);
        }
        ShopOrder order = shopOrderMapper.selectOne(wrapper);
        return order != null ? order.getVersion() : null;
    }

    private Integer loadRefundVersion(Long refundId, Long merchantId) {
        LambdaQueryWrapper<ShopRefund> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShopRefund::getId, refundId);
        if (merchantId != null) {
            wrapper.eq(ShopRefund::getMerchantId, merchantId);
        }
        ShopRefund refund = shopRefundMapper.selectOne(wrapper);
        return refund != null ? refund.getVersion() : null;
    }

    private void assertWorkflowTenant(ExecutionContext context, Long merchantId) {
        if (merchantId == null) {
            return;
        }
        Long actualTenant = context.get("merchantId", Long.class);
        if (!merchantId.equals(actualTenant)) {
            throw new AccessDeniedException("无权访问其他租户的工作流执行记录");
        }
    }

    private void assertWorkflowApproval(ExecutionContext context, Long approvalId) {
        if (approvalId == null) {
            return;
        }
        Long boundApprovalId = context.get("approvalId", Long.class);
        if (!approvalId.equals(boundApprovalId)) {
            throw new AccessDeniedException("审批单与工作流执行记录不匹配");
        }
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
                value = normalizeContextValue(entry.getKey(), value);
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

    private Object normalizeContextValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if ("orderId".equals(key) || "refundId".equals(key)
                || "merchantId".equals(key) || "approvalId".equals(key)) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    private long countStatus(ExecutionContext context, NodeStatus status) {
        return context.getNodeStatuses().values().stream().filter(s -> s == status).count();
    }
}
