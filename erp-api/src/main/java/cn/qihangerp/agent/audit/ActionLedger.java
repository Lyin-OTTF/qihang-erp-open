package cn.qihangerp.agent.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI Agent工具调用审计日志实体
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_action_ledger")
public class ActionLedger {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 链路追踪ID，关联一次对话的所有Tool调用
     */
    private String traceId;

    /**
     * Tool名称（格式：ClassName.methodName）
     */
    private String toolName;

    /**
     * Tool作用域/分类（order/goods/inventory等）
     */
    private String toolScope;

    /**
     * 自治等级（OBSERVE/SUGGEST/DRAFT/EXECUTE/ESCALATE）
     */
    private String autonomyLevel;

    /**
     * 租户ID/商户ID
     */
    private Long merchantId;

    /**
     * 操作用户ID
     */
    private Long userId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 输入参数（JSON格式）
     */
    private String inputParams;

    /**
     * 输出结果（JSON格式）
     */
    private String outputResult;

    /**
     * 是否成功：true=成功，false=失败
     */
    private Boolean success;

    /**
     * 错误信息（失败时记录）
     */
    private String errorMessage;

    /**
     * 错误类型（用于统计分析）
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
     * 是否已审批：true=已批准，false=已拒绝，null=无需审批
     */
    private Boolean approved;

    /**
     * 请求IP地址
     */
    private String ipAddress;

    /**
     * 用户代理（浏览器/客户端信息）
     */
    private String userAgent;

    /**
     * 记录创建时间
     */
    private LocalDateTime createTime;
}
