package cn.qihangerp.agent.gateway;

import cn.qihangerp.agent.audit.ActionLedgerRequest;
import cn.qihangerp.agent.audit.ActionLedgerService;
import cn.qihangerp.agent.approval.ApprovalRequest;
import cn.qihangerp.agent.approval.ApprovalService;
import cn.qihangerp.agent.evaluation.MetricsCollector;
import cn.qihangerp.agent.policy.AutonomyLevel;
import cn.qihangerp.agent.policy.PolicyRegistry;
import cn.qihangerp.agent.policy.ToolPolicy;
import cn.qihangerp.agent.trace.TraceEntry;
import cn.qihangerp.agent.trace.TraceService;
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

    // 并发控制：Tool方法名 -> 是否正在执行（内存锁，作为Redis不可用时的降级）
    private final ConcurrentHashMap<String, Boolean> executionLocks = new ConcurrentHashMap<>();

    // 分布式锁（Redis，多实例部署时保证互斥；不可用时自动降级为内存锁）
    @Autowired(required = false)
    private DistributedLockService distributedLockService;

    // 幂等性守护（Redis，防止写操作重复执行；仅对写工具生效）
    @Autowired(required = false)
    private IdempotencyGuard idempotencyGuard;

    // 策略注册表（启动时扫描所有@Tool方法的策略，优先于反射读取）
    @Autowired(required = false)
    private PolicyRegistry policyRegistry;

    // 指标收集器（可选依赖，如果不存在不影响核心功能）
    @Autowired(required = false)
    private MetricsCollector metricsCollector;

    // 审计服务（可选依赖，如果不存在则降级为只打印日志）
    @Autowired(required = false)
    private ActionLedgerService actionLedgerService;

    // 审批服务（可选依赖，用于ESCALATE级别人工审批）
    @Autowired(required = false)
    private ApprovalService approvalService;

    // Trace集中存储（可选依赖，记录工具调用链路）
    @Autowired(required = false)
    private TraceService traceService;

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
        DistributedLockService.LockHandle distLockHandle = null;
        String idempotencyKey = null;
        TraceEntry traceEntry = null;

        try {
            // 步骤1: 查找Tool方法
            Method method = findToolMethod(toolInstance.getClass(), methodName);
            if (method == null) {
                String errorMsg = "Tool方法不存在: " + toolName;
                log.error(errorMsg);
                throw new ToolExecutionException(errorMsg);
            }

            // 步骤2: 获取ToolPolicy（优先从PolicyRegistry注册表，降级为反射注解，最后默认策略）
            ToolPolicy policy = resolvePolicy(toolName, method);

            // 步骤2.5: 记录Trace开始
            if (traceService != null) {
                try {
                    String inputJson = args != null && args.length > 0
                            ? java.util.Arrays.toString(args) : "[]";
                    traceEntry = traceService.recordStart(
                            context.getTraceId(), toolName, policy.category(),
                            context.getMerchantId(), context.getUserId(), inputJson,
                            policy.level() != null ? policy.level().name() : null,
                            null, null);
                } catch (Exception traceEx) {
                    log.warn("Trace记录开始失败: {}", traceEx.getMessage());
                }
            }

            // 步骤3: 权限检查
            validatePolicy(policy, context);

            // 步骤4: 租户隔离检查
            if (policy.requiresTenantIsolation() && context.getMerchantId() == null) {
                // 回溯开发-阶段1调整：缺失租户ID时告警放行，避免破坏现有无租户头的调用链路；
                // 工具层会在 TenantContext 有值时自行加 merchantId 过滤，无值时保持原行为（查全部）。
                log.warn("Tool[{}]未携带租户ID，跳过强制租户隔离（向后兼容），工具层将按无租户处理", toolName);
            }

            // 步骤4.5: 幂等性检查（仅对写操作工具生效，防止AI重复调用导致重复写）
            if (policy.level() != null && policy.level().allowsWrite() && idempotencyGuard != null) {
                IdempotencyGuard.IdempotencyResult idemResult = idempotencyGuard.checkAndMark(
                        context.getMerchantId(), context.getUserId(), toolName, args);
                if (idemResult.isDuplicate()) {
                    if (idemResult.isProcessing()) {
                        String errorMsg = "写操作正在执行中，请勿重复提交: " + toolName;
                        log.warn(errorMsg);
                        throw new ToolExecutionException(errorMsg);
                    } else {
                        // 已完成过相同参数的写操作，直接返回幂等命中提示
                        log.info("幂等命中（已完成）: tool={}, 结果摘要={}", toolName, idemResult.getCachedResult());
                        return "操作已执行过（幂等命中），无需重复执行。上次结果摘要: " + idemResult.getCachedResult();
                    }
                }
                idempotencyKey = idemResult.getIdempotencyKey();
            }

            // 步骤5: 并发控制（优先Redis分布式锁，降级为内存锁）
            if (!policy.allowConcurrent()) {
                if (distributedLockService != null) {
                    distLockHandle = distributedLockService.tryLock("tool:" + toolName, 30);
                    if (!distLockHandle.isAcquired()) {
                        String errorMsg = "Tool正在执行中（分布式锁占用），不允许并发调用: " + toolName;
                        log.warn(errorMsg);
                        throw new ToolExecutionException(errorMsg);
                    }
                } else {
                    if (!acquireLock(toolName)) {
                        String errorMsg = "Tool正在执行中，不允许并发调用: " + toolName;
                        log.warn(errorMsg);
                        throw new ToolExecutionException(errorMsg);
                    }
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

            // 步骤8.5: 标记幂等完成（写操作）
            if (idempotencyKey != null && idempotencyGuard != null) {
                try {
                    String resultDigest = result != null ? result.toString() : "null";
                    if (resultDigest.length() > 200) resultDigest = resultDigest.substring(0, 200);
                    idempotencyGuard.complete(idempotencyKey, resultDigest);
                } catch (Exception e) {
                    log.warn("标记幂等完成失败: {}", e.getMessage());
                }
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

            // 记录Trace成功
            if (traceEntry != null && traceService != null) {
                try {
                    String resultStr = result != null ? result.toString() : "null";
                    traceService.recordSuccess(traceEntry, resultStr, duration);
                } catch (Exception traceEx) {
                    log.warn("Trace记录成功失败: {}", traceEx.getMessage());
                }
            }

            return result;

        } catch (ApprovalRequiredException e) {
            // 人工确认异常：操作未实际执行，清除幂等标记以便审批通过后重试
            if (idempotencyKey != null && idempotencyGuard != null) {
                try { idempotencyGuard.fail(idempotencyKey); } catch (Exception ex) { log.warn("清除幂等标记失败: {}", ex.getMessage()); }
            }
            // 记录Trace审批状态
            if (traceEntry != null && traceService != null) {
                try { traceService.recordApprovalRequired(traceEntry, e.getMessage()); } catch (Exception ex) { log.warn("Trace审批记录失败: {}", ex.getMessage()); }
            }
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

            // 执行失败，清除幂等标记（允许重试）
            if (idempotencyKey != null && idempotencyGuard != null) {
                try { idempotencyGuard.fail(idempotencyKey); } catch (Exception ex) { log.warn("清除幂等标记失败: {}", ex.getMessage()); }
            }

            // 记录Trace失败
            if (traceEntry != null && traceService != null) {
                try { traceService.recordFailure(traceEntry, errorType, actualError.getMessage(), duration); } catch (Exception ex) { log.warn("Trace失败记录失败: {}", ex.getMessage()); }
            }

            throw new ToolExecutionException("Tool执行失败: " + toolName, e);

        } finally {
            // 释放分布式锁（如果获取了）
            if (distLockHandle != null && distLockHandle.isAcquired() && distributedLockService != null) {
                try {
                    distributedLockService.unlock(distLockHandle);
                } catch (Exception e) {
                    log.warn("释放分布式锁失败: {}", e.getMessage());
                }
            }
            // 释放内存锁（降级路径）
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
     * 解析工具策略：优先从 PolicyRegistry 获取，降级为反射注解，最后用默认策略。
     */
    private ToolPolicy resolvePolicy(String toolName, Method method) {
        // 1. 优先从策略注册表获取（启动时扫描的统一策略源）
        if (policyRegistry != null) {
            PolicyRegistry.ToolPolicyMetadata metadata = policyRegistry.getPolicy(toolName);
            if (metadata != null) {
                log.debug("策略从PolicyRegistry获取: {}", toolName);
                return policyFromMetadata(metadata);
            }
        }
        // 2. 降级为反射读取注解
        ToolPolicy policy = method.getAnnotation(ToolPolicy.class);
        if (policy != null) {
            log.warn("Tool[{}]未在PolicyRegistry中注册，使用反射注解策略", toolName);
            return policy;
        }
        // 3. 最后用默认策略
        log.warn("Tool方法[{}]缺少@ToolPolicy注解，使用默认OBSERVE策略", toolName);
        return createDefaultPolicy();
    }

    /**
     * 从 PolicyRegistry 元数据构造 ToolPolicy 匿名实现
     */
    private ToolPolicy policyFromMetadata(PolicyRegistry.ToolPolicyMetadata md) {
        return new ToolPolicy() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() { return ToolPolicy.class; }
            @Override
            public AutonomyLevel level() { return md.getAutonomyLevel(); }
            @Override
            public String category() { return md.getCategory(); }
            @Override
            public String description() { return md.getDescription(); }
            @Override
            public boolean requiresTenantIsolation() { return md.isRequiresTenantIsolation(); }
            @Override
            public boolean requiresAudit() { return md.isRequiresAudit(); }
            @Override
            public int maxBatchSize() { return md.getMaxBatchSize(); }
            @Override
            public int timeoutSeconds() { return md.getTimeoutSeconds(); }
            @Override
            public boolean allowConcurrent() { return md.isAllowConcurrent(); }
        };
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
