package cn.qihangerp.agent.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记忆条目实体
 *
 * 表示一条记忆数据
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryEntry {

    /**
     * 记忆唯一标识
     */
    private String memoryId;

    /**
     * 记忆层
     */
    private MemoryLayer layer;

    /**
     * 作用域类型
     */
    private String scopeType;

    /**
     * 作用域ID
     */
    private String scopeId;

    /**
     * 记忆键
     */
    private String key;

    /**
     * 记忆值
     */
    private String value;

    /**
     * 值类型（STRING/JSON/LIST/MAP）
     */
    private String valueType;

    /**
     * TTL秒数（0=永久）
     */
    private Integer ttlSeconds;

    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;

    /**
     * 优先级（用于淘汰策略）
     */
    private Integer priority;

    /**
     * 访问次数
     */
    private Integer accessCount;

    /**
     * 最后访问时间
     */
    private LocalDateTime lastAccessedAt;

    /**
     * 商户ID（租户隔离）
     */
    private Long merchantId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 判断是否已过期（计算属性，不参与序列化）
     */
    @JsonIgnore
    public boolean isExpired() {
        if (expiresAt == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * 计算剩余TTL（秒）（计算属性，不参与序列化）
     */
    @JsonIgnore
    public long getRemainingTtlSeconds() {
        if (expiresAt == null) {
            return -1; // 永久
        }
        long remaining = java.time.Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
        return Math.max(0, remaining);
    }

    /**
     * 增加访问次数
     */
    public void incrementAccessCount() {
        if (this.accessCount == null) {
            this.accessCount = 0;
        }
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    /**
     * 创建新的记忆条目
     */
    public static MemoryEntry create(MemoryLayer layer, String scopeId, String key, String value,
                                     Long merchantId, Long userId) {
        MemoryEntry entry = new MemoryEntry();
        entry.setMemoryId(generateMemoryId(layer, scopeId, key));
        entry.setLayer(layer);
        entry.setScopeType(layer.getScopeType());
        entry.setScopeId(scopeId);
        entry.setKey(key);
        entry.setValue(value);
        entry.setValueType("STRING");
        entry.setTtlSeconds(layer.getDefaultTtlSeconds());
        entry.setExpiresAt(LocalDateTime.now().plusSeconds(layer.getDefaultTtlSeconds()));
        entry.setPriority(0);
        entry.setAccessCount(0);
        entry.setMerchantId(merchantId);
        entry.setUserId(userId);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());
        return entry;
    }

    /**
     * 生成记忆ID
     */
    private static String generateMemoryId(MemoryLayer layer, String scopeId, String key) {
        return String.format("%s:%s:%s", layer.name(), scopeId, key);
    }
}
