package cn.qihangerp.agent.policy;

/**
 * 自治等级枚举
 *
 * 定义AI Agent可以执行的操作级别，用于控制工具调用的权限边界
 *
 * @author AI Code Generator
 * @since 2026-10-10
 */
public enum AutonomyLevel {

    /**
     * 观察模式 - 只读查询，无任何写操作
     *
     * 适用场景：
     * - 数据查询类Tool（订单列表、库存查询等）
     * - 统计报表类Tool
     * - 日志查看类Tool
     *
     * 安全等级：低风险
     */
    OBSERVE("观察模式", "只读查询，无任何写操作", 0),

    /**
     * 建议模式 - 可以生成建议方案，但不执行
     *
     * 适用场景：
     * - 采购建议生成
     * - 库存补货建议
     * - 订单分配建议
     *
     * 安全等级：低风险（仅生成建议，不执行）
     */
    SUGGEST("建议模式", "生成建议方案，不执行", 1),

    /**
     * 草稿模式 - 可以创建草稿记录，等待人工审核
     *
     * 适用场景：
     * - 创建采购单草稿
     * - 创建退款申请草稿
     * - 创建库存调拨单草稿
     *
     * 安全等级：中风险（创建草稿，需人工审批后生效）
     */
    DRAFT("草稿模式", "创建草稿记录，等待审核", 2),

    /**
     * 执行模式 - 可以直接执行写操作，但需要记录审计日志
     *
     * 适用场景：
     * - 低风险的自动化操作（如更新订单备注）
     * - 已经过充分测试的批量操作
     * - 风险可控的数据同步操作
     *
     * 安全等级：中高风险（直接执行，但有审计）
     */
    EXECUTE("执行模式", "直接执行写操作，记录审计", 3),

    /**
     * 升级模式 - 高风险操作，必须升级到人工确认
     *
     * 适用场景：
     * - 取消订单
     * - 删除数据
     * - 批量修改价格
     * - 财务相关操作
     *
     * 安全等级：高风险（必须人工确认后才能执行）
     */
    ESCALATE("升级模式", "高风险操作，必须人工确认", 4);

    private final String displayName;
    private final String description;
    private final int level;

    AutonomyLevel(String displayName, String description, int level) {
        this.displayName = displayName;
        this.description = description;
        this.level = level;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public int getLevel() {
        return level;
    }

    /**
     * 判断当前级别是否允许读操作
     *
     * @return true=允许，false=不允许
     */
    public boolean allowsRead() {
        return true; // 所有级别都允许读操作
    }

    /**
     * 判断当前级别是否允许写操作
     *
     * @return true=允许，false=不允许
     */
    public boolean allowsWrite() {
        return this.level >= EXECUTE.level;
    }

    /**
     * 判断当前级别是否需要人工确认
     *
     * @return true=需要确认，false=不需要
     */
    public boolean requiresApproval() {
        return this == ESCALATE;
    }

    /**
     * 判断当前级别是否需要审计日志
     *
     * @return true=需要审计，false=不需要
     */
    public boolean requiresAudit() {
        return this.level >= DRAFT.level;
    }

    @Override
    public String toString() {
        return displayName + "(" + name() + ")";
    }
}
