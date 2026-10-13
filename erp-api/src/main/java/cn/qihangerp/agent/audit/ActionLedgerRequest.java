package cn.qihangerp.agent.audit;

import java.time.LocalDateTime;

/**
 * ActionLedger审计日志请求
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
public class ActionLedgerRequest {

    /**
     * 链路追踪ID
     */
    private String traceId;

    /**
     * Tool名称
     */
    private String toolName;

    /**
     * Tool作用域
     */
    private String toolScope;

    /**
     * 自治等级
     */
    private String autonomyLevel;

    /**
     * 租户ID
     */
    private Long merchantId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 输入参数
     */
    private Object inputParams;

    /**
     * 输出结果
     */
    private Object outputResult;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 错误类型
     */
    private String errorType;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 执行耗时（毫秒）
     */
    private Integer durationMs;

    /**
     * 是否为写操作
     */
    private Boolean isWriteOperation;

    /**
     * 是否需要审批
     */
    private Boolean requiresApproval;

    /**
     * 是否已审批
     */
    private Boolean approved;

    /**
     * 请求IP地址
     */
    private String ipAddress;

    /**
     * 用户代理
     */
    private String userAgent;

    // Constructors
    public ActionLedgerRequest() {
    }

    public ActionLedgerRequest(String traceId, String toolName, String toolScope, String autonomyLevel,
                              Long merchantId, Long userId, String sessionId, Object inputParams,
                              Object outputResult, Boolean success, String errorMessage, String errorType,
                              LocalDateTime startTime, LocalDateTime endTime, Integer durationMs,
                              Boolean isWriteOperation, Boolean requiresApproval, Boolean approved,
                              String ipAddress, String userAgent) {
        this.traceId = traceId;
        this.toolName = toolName;
        this.toolScope = toolScope;
        this.autonomyLevel = autonomyLevel;
        this.merchantId = merchantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.inputParams = inputParams;
        this.outputResult = outputResult;
        this.success = success;
        this.errorMessage = errorMessage;
        this.errorType = errorType;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMs = durationMs;
        this.isWriteOperation = isWriteOperation;
        this.requiresApproval = requiresApproval;
        this.approved = approved;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String traceId;
        private String toolName;
        private String toolScope;
        private String autonomyLevel;
        private Long merchantId;
        private Long userId;
        private String sessionId;
        private Object inputParams;
        private Object outputResult;
        private Boolean success;
        private String errorMessage;
        private String errorType;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer durationMs;
        private Boolean isWriteOperation;
        private Boolean requiresApproval;
        private Boolean approved;
        private String ipAddress;
        private String userAgent;

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder toolScope(String toolScope) {
            this.toolScope = toolScope;
            return this;
        }

        public Builder autonomyLevel(String autonomyLevel) {
            this.autonomyLevel = autonomyLevel;
            return this;
        }

        public Builder merchantId(Long merchantId) {
            this.merchantId = merchantId;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder inputParams(Object inputParams) {
            this.inputParams = inputParams;
            return this;
        }

        public Builder outputResult(Object outputResult) {
            this.outputResult = outputResult;
            return this;
        }

        public Builder success(Boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder startTime(LocalDateTime startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(LocalDateTime endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder durationMs(Integer durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder isWriteOperation(Boolean isWriteOperation) {
            this.isWriteOperation = isWriteOperation;
            return this;
        }

        public Builder requiresApproval(Boolean requiresApproval) {
            this.requiresApproval = requiresApproval;
            return this;
        }

        public Builder approved(Boolean approved) {
            this.approved = approved;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public ActionLedgerRequest build() {
            return new ActionLedgerRequest(traceId, toolName, toolScope, autonomyLevel,
                    merchantId, userId, sessionId, inputParams, outputResult, success,
                    errorMessage, errorType, startTime, endTime, durationMs,
                    isWriteOperation, requiresApproval, approved, ipAddress, userAgent);
        }
    }

    // Getters and Setters
    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolScope() {
        return toolScope;
    }

    public void setToolScope(String toolScope) {
        this.toolScope = toolScope;
    }

    public String getAutonomyLevel() {
        return autonomyLevel;
    }

    public void setAutonomyLevel(String autonomyLevel) {
        this.autonomyLevel = autonomyLevel;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Object getInputParams() {
        return inputParams;
    }

    public void setInputParams(Object inputParams) {
        this.inputParams = inputParams;
    }

    public Object getOutputResult() {
        return outputResult;
    }

    public void setOutputResult(Object outputResult) {
        this.outputResult = outputResult;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public Boolean getIsWriteOperation() {
        return isWriteOperation;
    }

    public void setIsWriteOperation(Boolean isWriteOperation) {
        this.isWriteOperation = isWriteOperation;
    }

    public Boolean getRequiresApproval() {
        return requiresApproval;
    }

    public void setRequiresApproval(Boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
    }

    public Boolean getApproved() {
        return approved;
    }

    public void setApproved(Boolean approved) {
        this.approved = approved;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
