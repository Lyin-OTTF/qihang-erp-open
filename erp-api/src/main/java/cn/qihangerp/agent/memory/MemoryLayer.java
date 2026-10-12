package cn.qihangerp.agent.memory;

/**
 * 记忆层枚举
 *
 * 四层分级记忆：
 * - WORKING: 工作记忆（短期，1小时TTL）
 * - SESSION: 会话记忆（中期，1天TTL，存Redis）
 * - USER: 用户记忆（长期，30天TTL，存MySQL）
 * - TEAM: 团队记忆（永久，90天TTL，存MySQL，团队共享）
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
public enum MemoryLayer {

    /**
     * 工作记忆（短期）
     * - TTL: 1小时
     * - 存储: 内存/Redis
     * - 作用域: 单次对话
     * - 示例: "刚才提到的订单号是PO20261011001"
     */
    WORKING("工作记忆", 3600, "CONVERSATION"),

    /**
     * 会话记忆（中期）
     * - TTL: 1天
     * - 存储: Redis
     * - 作用域: 用户会话
     * - 示例: "今天用户询问了3次退款问题"
     */
    SESSION("会话记忆", 86400, "SESSION"),

    /**
     * 用户记忆（长期）
     * - TTL: 30天
     * - 存储: MySQL
     * - 作用域: 单个用户
     * - 示例: "用户偏好：喜欢简洁回答"
     */
    USER("用户记忆", 2592000, "USER"),

    /**
     * 团队记忆（永久）
     * - TTL: 90天
     * - 存储: MySQL
     * - 作用域: 整个商户/团队
     * - 示例: "公司退款政策：7天无理由退货"
     */
    TEAM("团队记忆", 7776000, "MERCHANT");

    private final String displayName;
    private final int defaultTtlSeconds;
    private final String scopeType;

    MemoryLayer(String displayName, int defaultTtlSeconds, String scopeType) {
        this.displayName = displayName;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.scopeType = scopeType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public String getScopeType() {
        return scopeType;
    }

    /**
     * 判断是否需要持久化到MySQL
     */
    public boolean requiresPersistence() {
        return this == USER || this == TEAM;
    }

    /**
     * 判断是否使用Redis存储
     */
    public boolean usesRedis() {
        return this == WORKING || this == SESSION;
    }

    /**
     * 获取存储介质
     */
    public String getStorageMedium() {
        if (this == WORKING || this == SESSION) {
            return "Redis";
        } else {
            return "MySQL";
        }
    }
}
