package cn.qihangerp.agent.trace;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent调用Trace实体。
 * 记录AI Agent每次工具调用的完整链路追踪信息。
 */
@Data
@TableName("agent_trace")
public class TraceEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private String spanId;
    private String parentSpanId;
    private Long merchantId;
    private Long userId;
    private String toolName;
    private String toolCategory;
    private String action;
    private String inputParams;
    private String outputResult;
    private String errorType;
    private String errorMessage;
    private Long durationMs;
    private String status;
    private String autonomyLevel;
    private String idempotencyKey;
    private String lockKey;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
