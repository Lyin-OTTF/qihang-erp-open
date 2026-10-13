package cn.qihangerp.agent.approval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Agent 操作审批记录实体
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Data
@TableName("agent_approval")
public class ApprovalRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private String toolName;
    private String toolDescription;
    private Long merchantId;
    private Long userId;
    private String inputParams;
    private String status;
    private Long approverId;
    private String approveComment;
    private LocalDateTime approvedAt;
    private LocalDateTime executedAt;
    private String executionResult;
    private String errorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 审批状态枚举 */
    public enum Status {
        PENDING, APPROVED, REJECTED, EXECUTED, EXPIRED
    }
}
