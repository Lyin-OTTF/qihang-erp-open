package cn.qihangerp.agent.gateway;

import lombok.Data;

/**
 * Tool执行上下文
 *
 * 封装Tool执行时的上下文信息，包括：
 * - 租户ID（merchantId）- 用于多租户隔离
 * - 用户ID（userId）- 用于审计日志
 * - 追踪ID（traceId）- 用于链路追踪
 * - 审批状态（approved）- 高风险操作是否已获批准
 * - 权限控制标志
 *
 * @author AI Code Generator
 * @since 2026-10-10
 */
@Data
public class ToolExecutionContext {

    /**
     * 租户ID（必填）
     * 用于多租户数据隔离，所有查询会自动带上merchantId过滤条件
     */
    private Long merchantId;

    /**
     * 用户ID（必填）
     * 用于审计日志记录操作人
     */
    private Long userId;

    /**
     * 用户名（可选）
     * 用于审计日志显示
     */
    private String username;

    /**
     * 追踪ID（必填）
     * 用于链路追踪，关联一次完整的对话会话
     */
    private String traceId;

    /**
     * 会话ID（可选）
     * 用于关联同一次AI对话中的多个Tool调用
     */
    private String sessionId;

    /**
     * 是否已获得人工审批
     * 对于ESCALATE级别的Tool，此字段必须为true才能执行
     */
    private boolean approved = false;

    /**
     * 审批ID（可选）
     * 如果此Tool需要审批，记录审批单ID
     */
    private Long approvalId;

    /**
     * 是否允许写操作
     * 默认为true，可以在某些只读场景下设置为false
     */
    private boolean writeAllowed = true;

    /**
     * 批量操作数量
     * 用于限制批量操作的规模，防止误操作
     */
    private int batchSize = 1;

    /**
     * 客户端IP地址（可选）
     * 用于审计日志
     */
    private String clientIp;

    /**
     * 请求来源（可选）
     * 例如："WebUI"、"API"、"Schedule"等
     */
    private String source = "AI";

    /**
     * 构造方法：最小必填参数
     */
    public ToolExecutionContext(Long merchantId, Long userId, String traceId) {
        this.merchantId = merchantId;
        this.userId = userId;
        this.traceId = traceId;
    }

    /**
     * 构造方法：完整参数
     */
    public ToolExecutionContext(Long merchantId, Long userId, String username,
                                String traceId, String sessionId) {
        this.merchantId = merchantId;
        this.userId = userId;
        this.username = username;
        this.traceId = traceId;
        this.sessionId = sessionId;
    }

    /**
     * 构造方法：默认构造（用于Spring注入）
     */
    public ToolExecutionContext() {
    }

    /**
     * 设置为已审批状态
     *
     * @param approvalId 审批单ID
     * @return 当前上下文（链式调用）
     */
    public ToolExecutionContext withApproval(Long approvalId) {
        this.approved = true;
        this.approvalId = approvalId;
        return this;
    }

    /**
     * 设置批量操作数量
     *
     * @param batchSize 批量数量
     * @return 当前上下文（链式调用）
     */
    public ToolExecutionContext withBatchSize(int batchSize) {
        this.batchSize = batchSize;
        return this;
    }

    /**
     * 设置为只读模式
     *
     * @return 当前上下文（链式调用）
     */
    public ToolExecutionContext asReadOnly() {
        this.writeAllowed = false;
        return this;
    }

    /**
     * 设置客户端信息
     *
     * @param clientIp 客户端IP
     * @param source   请求来源
     * @return 当前上下文（链式调用）
     */
    public ToolExecutionContext withClient(String clientIp, String source) {
        this.clientIp = clientIp;
        this.source = source;
        return this;
    }

    /**
     * 校验上下文是否有效
     *
     * @throws IllegalArgumentException 如果必填字段缺失
     */
    public void validate() {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId不能为空");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId不能为空");
        }
    }

    @Override
    public String toString() {
        return String.format("ToolExecutionContext{merchantId=%d, userId=%d, traceId='%s', approved=%s}",
                merchantId, userId, traceId, approved);
    }
}
