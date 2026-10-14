package cn.qihangerp.agent.policy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent工具策略动态配置实体。
 *
 * <p>DB中配置的策略优先于 @ToolPolicy 注解，支持运行时动态修改工具自治等级。
 * merchant_id 为 NULL 表示全局默认策略，具体商户ID可覆盖全局配置。</p>
 *
 * @author 回溯开发-第二类补全
 * @since 2026-10-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_tool_policy")
public class AgentToolPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具名，格式：ClassName.methodName */
    private String toolName;

    /** 商户ID，NULL为全局默认策略 */
    private Long merchantId;

    /** 自治等级：OBSERVE/SUGGEST/DRAFT/EXECUTE/ESCALATE */
    private String autonomyLevel;

    /** 工具分类 */
    private String category;

    /** 工具描述 */
    private String description;

    /** 是否需要租户隔离 */
    private Boolean requiresTenantIsolation;

    /** 是否允许并发调用 */
    private Boolean allowConcurrent;

    /** 是否需要审计日志 */
    private Boolean requiresAudit;

    /** 批量操作上限，0=不限制 */
    private Integer maxBatchSize;

    /** 超时秒数，0=不限制 */
    private Integer timeoutSeconds;

    /** 是否启用 */
    private Boolean enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
