package cn.qihangerp.agent.evaluation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI Bad Case 反馈实体
 *
 * <p>用户标记AI的错误/无用/不安全回答，用于后续评测与模型优化。</p>
 *
 * @author 回溯开发-阶段2
 * @since 2026-10-12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_bad_case")
public class BadCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联对话traceId，可回溯完整调用链 */
    private String traceId;

    /** 反馈用户ID */
    private Long userId;

    /** 租户ID/商户ID */
    private Long merchantId;

    /** 会话ID */
    private String sessionId;

    /** 用户原始问题 */
    private String question;

    /** AI给出的回答 */
    private String aiAnswer;

    /** 反馈类型：WRONG_ANSWER/UNHELPFUL/UNSAFE/OFF_TOPIC/OTHER */
    private String feedbackType;

    /** 用户补充说明 */
    private String feedbackComment;

    /** 处理状态：OPEN/PROCESSING/RESOLVED/IGNORED */
    private String status;

    /** 处理结果说明 */
    private String resolution;

    /** 处理人ID */
    private Long resolvedBy;

    /** 处理时间 */
    private LocalDateTime resolvedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
