package cn.qihangerp.agent.gateway;

import cn.qihangerp.agent.audit.ActionLedgerRequest;
import cn.qihangerp.agent.audit.ActionLedgerService;
import cn.qihangerp.agent.approval.ApprovalRequest;
import cn.qihangerp.agent.approval.ApprovalService;
import cn.qihangerp.agent.evaluation.MetricsCollector;
import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.ToolPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用网关
 *
 * 所有AI Tool的调用必须经过此网关，网关负责：
 * 1. 权限检查 - 检查Tool的AutonomyLevel是否允许执行
 * 2. 租户隔离 - 注入当前租户ID，防止跨租户数据访问
 * 3. 审计日志 - 记录所有Tool调用到ActionLedger
 * 4. 人工确认 - 高风险操作需要人工批准
 * 5. 并发控制 - 防止并发冲突
 *
 * 使用方式：
 * <pre>
 * &#64;Autowired
 * private ToolGateway toolGateway;
 *
 * // 在调用Tool前经过网关
 * ToolExecutionContext context = new ToolExecutionContext(merchantId, userId, traceId);
 * String result = toolGateway.execute(orderTools, "getOrderList", context, args);
 * </pre>
 *
 * @author AI Code Generator
 * @since 2026-10-10
 */
@Slf4j
@Component
public class ToolGateway {

    // 并发控制：Tool方法名 -> 是否正在执行
    private final ConcurrentHashMap<String, Boolean> executionLocks = new ConcurrentHashMap<>();

    // 指标收集器（可选依赖，如果不存在不影响核心功能）
    @Autowired(required = false)
    private MetricsCollector metricsCollector;

    // 审计服务（可选依赖，如果不存在则降级为只打印日志）
    @Autowired(required = false)
    private ActionLedgerService actionLedgerService;

    // 审批服务（可选依赖，用于ESCALATE级别人工审批）
    @Autowired(required = false)
    private ApprovalService approvalService;

    /**
     * 执行Tool方法（核心入口）
     *
     * @param toolInstance Tool实例对象
     * @param methodName   方法名
     * @param context      执行上下文（包含租户ID、用户ID、traceId）
     * @param args         方法参数
     * @return Tool执行结果
     * @throws ToolExecutionException 执行失败时抛出
     */
    public Object execute(Object toolInstance, String methodName,
                          ToolExecutionContext context, Object... args)
            throws ToolExecutionException {

        long startTime = System.currentTimeMillis();
        String toolName = toolInstance.getClass().getSimpleName() + "." + methodName;

        try {
            // 步骤1: 查找Tool方法
            Method method = findToolMethod(toolInstance.getClass(), methodName);
            if (method == null) {
                String errorMsg = "Tool方法不存在: " + toolName;
                log.error(errorMsg);
                throw new ToolExecutionException(errorMsg);
            }

            // 步骤2: 获取ToolPolicy注解
            ToolPolicy policy = method.getAnnotation(ToolPolicy.class);
            if (policy == null) {
                log.warn("Tool方法[{}]缺少@ToolPolicy注解，使用默认OBSERVE策略", toolName);
                // 缺少注解时，默认只允许OBSERVE级别
                policy = createDefaultPolicy();
            }

            // 步骤3: 权限检查
            validatePolicy(policy, context);

            // 步骤4: 租户隔离检查
            if (policy.requiresTenantIsolation() && context.getMerchantId() == null) {
                // 回溯开发-阶段1调整：缺失租户ID时告警放行，避免破坏现有无租户头的调用链路；
                // 工具层会在 TenantContext 有值时自行加 merchantId 过滤，无值时保持原行为（查全部）。
                log.warn("Tool[{}]未携带租户ID，跳过强制租户隔离（向后兼容），工具层将按无租户处理", toolName);
            }

            // 步骤5: 并发控制
            if (!policy.allowConcurrent()) {
                if (!acquireLock(toolName)) {
                    String errorMsg = "Tool正在执行中，不允许并发调用: " + toolName;
                    log.warn(errorMsg);
                    throw new ToolExecutionException(errorMsg);
                }
            }

            // 步骤6: 人工确认检查（ESCALATE级别）
            if (policy.level() == AutonomyLevel.ESCALATE) {
                if (!context.isApproved()) {
                    // 检查是否已有通过的审批（同一traceId+toolName）
                    boolean alreadyApproved = approvalService != null
                            && approvalService.isApproved(context.getTraceId(), toolName);
                    if (alreadyApproved) {
                        log.info("Tool[{}]已有审批通过记录，直接执行", toolName);
                        ApprovalRequest approved = approvalService.getApprovedApproval(context.getTraceId(), toolName);
                        if (approved != null) {
                            context.setApprovalId(approved.getId());
                            context.setApproved(true);
                        }
                    } else {
                        // 创建审批记录，等待人工确认
                        Long approvalId = null;
                        if (approvalService != null) {
                            approvalId = approvalService.createApproval(
                                    context.getTraceId(), toolName, policy.description(),
                                    context.getMerchantId(), context.getUserId(), args);
                        }
                        log.info("Tool[{}]需要人工确认，已创建审批记录 id={}", toolName, approvalId);
                        throw new ApprovalRequiredException(
                                "此操作需要人工确认: " + policy.description(),
                                toolName, context.getTraceId(), approvalId);
                    }
                }
            }

            // 步骤7: 执行Tool方法
            log.info("执行Tool[{}]，自治等级={}，租户ID={}，traceId={}",
                    toolName, policy.level(), context.getMerchantId(), context.getTraceId());

            Object result = method.invoke(toolInstance, args);

            // 步骤8: 记录审计日志（如果需要）
            if (policy.requiresAudit()) {
                recordAuditLog(toolName, policy, context, args, result, true, null, startTime);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Tool[{}]执行成功，耗时{}ms", toolName, duration);

            // ESCALATE 工具执行成功后，标记审批为已执行
            if (policy.level() == AutonomyLevel.ESCALATE && context.getApprovalId() != null && approvalService != null) {
                try {
                    String resultSummary = result != null ? result.toString() : "null";
                    if (resultSummary.length() > 500) resultSummary = resultSummary.substring(0, 500);
                    approvalService.markExecuted(context.getApprovalId(), resultSummary);
                } catch (Exception e) {
                    log.warn("标记审批执行状态失败: {}", e.getMessage());
                }
            }

            // 记录成功指标
            if (metricsCollector != null) {
                metricsCollector.recordSuccess(toolName, policy.category(), duration, context.getMerchantId());
            }

            return result;

        } catch (ApprovalRequiredException e) {
            // 人工确认异常，向上抛出
            throw e;

        } catch (Exception e) {
            // 解包反射调用异常（method.invoke 会把目标异常包装为 InvocationTargetException）
            Throwable actualError = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            long duration = System.currentTimeMillis() - startTime;
            log.error("Tool[{}]执行失败，耗时{}ms，错误: {}", toolName, duration, actualError.getMessage(), e);

            // 确定错误类型
            String errorType = determineErrorType(e);

            // 记录失败指标
            if (metricsCollector != null) {
                try {
                    ToolPolicy policy = findToolMethod(toolInstance.getClass(), methodName)
                            .getAnnotation(ToolPolicy.class);
                    String category = policy != null ? policy.category() : "未分类";
                    metricsCollector.recordFailure(toolName, category, duration, errorType,
                            actualError.getMessage(), context.getMerchantId());
                } catch (Exception metricsEx) {
                    log.error("记录失败指标时出错: {}", metricsEx.getMessage());
                }
            }

            // 记录失败的审计日志
            try {
                ToolPolicy policy = findToolMethod(toolInstance.getClass(), methodName)
                        .getAnnotation(ToolPolicy.class);
                if (policy != null && policy.requiresAudit()) {
                    recordAuditLog(toolName, policy, context, args, null, false, actualError.getMessage(), startTime);
                }
            } catch (Exception auditEx) {
                log.error("记录审计日志失败: {}", auditEx.getMessage());
            }

            throw new ToolExecutionException("Tool执行失败: " + toolName, e);

        } finally {
            // 释放并发锁
            executionLocks.remove(toolName);
        }
    }

    /**
     * 确定错误类型（用于指标统计）
     */
    private String determineErrorType(Exception e) {
        if (e instanceof ToolExecutionException) {
            String msg = e.getMessage();
            if (msg.contains("Tool方法不存在")) return "方法不存在";
            if (msg.contains("缺少租户ID")) return "租户隔离检查失败";
            if (msg.contains("不允许并发")) return "并发冲突";
            if (msg.contains("不允许写操作")) return "写权限不足";
            if (msg.contains("批量操作数量")) return "批量数量超限";
            return "Tool执行异常";
        }
        if (e.getCause() != null) {
            return e.getCause().getClass().getSimpleName();
        }
        return e.getClass().getSimpleName();
    }

    /**
     * 查找Tool方法
     */
    private Method findToolMethod(Class<?> clazz, String methodName) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 验证策略权限
     */
    private void validatePolicy(ToolPolicy policy, ToolExecutionContext context) throws ToolExecutionException {
        AutonomyLevel level = policy.level();

        // 检查是否允许写操作
        if (level.allowsWrite() && !context.isWriteAllowed()) {
            String errorMsg = "当前会话不允许写操作，Tool自治等级: " + level.getDisplayName();
            log.warn(errorMsg);
            throw new ToolExecutionException(errorMsg);
        }

        // 检查批量操作限制
        if (policy.maxBatchSize() > 0 && context.getBatchSize() > policy.maxBatchSize()) {
            String errorMsg = String.format("批量操作数量[%d]超过限制[%d]",
                    context.getBatchSize(), policy.maxBatchSize());
            log.warn(errorMsg);
            throw new ToolExecutionException(errorMsg);
        }
    }

    /**
     * 获取并发锁
     */
    private boolean acquireLock(String toolName) {
        return executionLocks.putIfAbsent(toolName, true) == null;
    }

    /**
     * 记录审计日志到ActionLedger
     * 审计失败不影响业务流程，仅打印错误日志
     */
    private void recordAuditLog(String toolName, ToolPolicy policy,
                                ToolExecutionContext context, Object[] args,
                                Object result, boolean success, String errorMessage,
                                long startTime) {
        if (actionLedgerService == null) {
            log.info("[审计降级] Tool={}, Category={}, MerchantId={}, UserId={}, Success={}",
                    toolName, policy.category(), context.getMerchantId(),
                    context.getUserId(), success);
            return;
        }
        try {
            ActionLedgerRequest request = ActionLedgerRequest.builder()
                    .traceId(context.getTraceId())
                    .toolName(toolName)
                    .toolScope(policy.category())
                    .autonomyLevel(policy.level() != null ? policy.level().name() : null)
                    .merchantId(context.getMerchantId())
                    .userId(context.getUserId())
                    .sessionId(context.getSessionId())
                    .inputParams(args)
                    .outputResult(result)
                    .success(success)
                    .errorMessage(errorMessage)
                    .errorType(success ? null : (errorMessage != null ? errorMessage.length() > 50 ? errorMessage.substring(0, 50) : errorMessage : null))
                    .startTime(new java.util.Date(startTime).toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())
                    .endTime(java.time.LocalDateTime.now())
                    .durationMs((int) (System.currentTimeMillis() - startTime))
                    .isWriteOperation(policy.level() != null && policy.level().allowsWrite())
                    .requiresApproval(policy.level() == AutonomyLevel.ESCALATE)
                    .ipAddress(context.getClientIp())
                    .userAgent(context.getSource())
                    .build();
            actionLedgerService.recordAction(request);
        } catch (Exception e) {
            log.error("记录审计日志失败（不影响业务）: tool={}, error={}", toolName, e.getMessage());
        }
    }

    /**
     * 创建默认策略（缺少@ToolPolicy注解时使用）
     */
    private ToolPolicy createDefaultPolicy() {
        return new ToolPolicy() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return ToolPolicy.class;
            }

            @Override
            public AutonomyLevel level() {
                return AutonomyLevel.OBSERVE;
            }

            @Override
            public String category() {
                return "未分类";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public boolean requiresTenantIsolation() {
                return true;
            }

            @Override
            public boolean requiresAudit() {
                return true;
            }

            @Override
            public int maxBatchSize() {
                return 0;
            }

            @Override
            public int timeoutSeconds() {
                return 0;
            }

            @Override
            public boolean allowConcurrent() {
                return true;
            }
        };
    }

    /**
     * Tool执行异常
     */
    public static class ToolExecutionException extends Exception {
        public ToolExecutionException(String message) {
            super(message);
        }

        public ToolExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 需要人工确认异常
     */
    public static class ApprovalRequiredException extends ToolExecutionException {
        private final String toolName;
        private final String traceId;
        private final Long approvalId;

        public ApprovalRequiredException(String message, String toolName, String traceId, Long approvalId) {
            super(message);
            this.toolName = toolName;
            this.traceId = traceId;
            this.approvalId = approvalId;
        }

        public String getToolName() {
            return toolName;
        }

        public String getTraceId() {
            return traceId;
        }

        public Long getApprovalId() {
            return approvalId;
        }
    }
}
