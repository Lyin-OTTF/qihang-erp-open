package cn.qihangerp.agent.policy;

import java.lang.annotation.*;

/**
 * 工具策略注解
 *
 * 用于标注AI Tool类或方法的自治等级和权限策略
 * 必须为每个Tool方法标注此注解，否则ToolGateway会拒绝调用
 *
 * 使用示例：
 * <pre>
 * &#64;Tool(description = "查询订单列表")
 * &#64;ToolPolicy(level = AutonomyLevel.OBSERVE, category = "订单查询")
 * public String getOrderList(...) {
 *     // ...
 * }
 * </pre>
 *
 * @author AI Code Generator
 * @since 2026-10-10
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolPolicy {

    /**
     * 自治等级
     *
     * 默认为OBSERVE（只读），确保未标注的Tool默认是安全的
     *
     * @return 自治等级
     */
    AutonomyLevel level() default AutonomyLevel.OBSERVE;

    /**
     * 工具分类
     *
     * 用于审计日志分类和权限管理
     * 例如："订单查询"、"库存操作"、"财务管理"等
     *
     * @return 工具分类名称
     */
    String category() default "未分类";

    /**
     * 操作描述
     *
     * 简短描述此Tool的作用，用于审批确认弹窗显示
     * 例如："取消订单"、"创建采购单"等
     *
     * @return 操作描述
     */
    String description() default "";

    /**
     * 是否需要租户隔离
     *
     * 如果为true，ToolGateway会强制注入merchantId过滤条件
     *
     * @return true=需要租户隔离，false=不需要（如系统配置类Tool）
     */
    boolean requiresTenantIsolation() default true;

    /**
     * 是否需要审计日志
     *
     * 如果为true，所有调用会记录到ActionLedger
     * 默认为true，确保所有Tool调用都可追溯
     *
     * @return true=需要审计，false=不需要
     */
    boolean requiresAudit() default true;

    /**
     * 最大允许的批量操作数量
     *
     * 用于限制批量操作的规模，防止误操作影响过大
     * 0表示不限制
     *
     * @return 最大批量操作数量
     */
    int maxBatchSize() default 0;

    /**
     * 操作超时时间（秒）
     *
     * 超过此时间会被ToolGateway强制中断
     * 0表示使用默认超时时间（30秒）
     *
     * @return 超时时间（秒）
     */
    int timeoutSeconds() default 0;

    /**
     * 是否允许并发执行
     *
     * 如果为false，同一Tool方法同一时间只能有一个实例在执行
     * 用于防止并发冲突（如库存扣减）
     *
     * @return true=允许并发，false=串行执行
     */
    boolean allowConcurrent() default true;
}
