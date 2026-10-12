package cn.qihangerp.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 记忆管理器
 *
 * 四层分级记忆管理：
 * - WORKING: 工作记忆（1小时，内存/Redis）
 * - SESSION: 会话记忆（1天，Redis）
 * - USER: 用户记忆（30天，MySQL）
 * - TEAM: 团队记忆（90天，MySQL）
 *
 * 功能：
 * 1. 存储和读取记忆
 * 2. TTL自动过期
 * 3. 租户隔离
 * 4. 敏感信息脱敏
 * 5. 记忆快照与恢复
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryManager {

    private final StringRedisTemplate redisTemplate;
    // MySQL持久化由MemoryRepository负责（稍后实现）
    // private final MemoryRepository memoryRepository;

    // WORKING层内存缓存（用于极短期记忆）
    private final ConcurrentHashMap<String, MemoryEntry> workingMemoryCache = new ConcurrentHashMap<>();

    /**
     * 存储记忆
     *
     * @param layer      记忆层
     * @param scopeId    作用域ID
     * @param key        记忆键
     * @param value      记忆值
     * @param merchantId 商户ID
     * @param userId     用户ID
     */
    public void store(MemoryLayer layer, String scopeId, String key, String value,
                      Long merchantId, Long userId) {
        // 创建记忆条目
        MemoryEntry entry = MemoryEntry.create(layer, scopeId, key, value, merchantId, userId);

        // 敏感信息脱敏
        entry.setValue(SensitiveDataMasker.autoMask(value));

        // 根据记忆层选择存储介质
        if (layer == MemoryLayer.WORKING) {
            storeToWorkingCache(entry);
        } else if (layer == MemoryLayer.SESSION) {
            storeToRedis(entry);
        } else if (layer == MemoryLayer.USER || layer == MemoryLayer.TEAM) {
            storeToMySQL(entry);
            // 同时缓存到Redis提升读取性能
            storeToRedis(entry);
        }

        log.info("记忆已存储: layer={}, scopeId={}, key={}, merchantId={}",
                layer, scopeId, key, merchantId);
    }

    /**
     * 读取记忆
     *
     * @param layer      记忆层
     * @param scopeId    作用域ID
     * @param key        记忆键
     * @param merchantId 商户ID
     * @return 记忆值，不存在或已过期返回null
     */
    public String retrieve(MemoryLayer layer, String scopeId, String key, Long merchantId) {
        String memoryId = generateMemoryId(layer, scopeId, key);

        MemoryEntry entry = null;

        // 优先从缓存读取
        if (layer == MemoryLayer.WORKING) {
            entry = workingMemoryCache.get(memoryId);
        } else {
            // 从Redis读取
            entry = retrieveFromRedis(memoryId);
            // Redis未命中，从MySQL读取
            if (entry == null && layer.requiresPersistence()) {
                entry = retrieveFromMySQL(memoryId);
                // 回写Redis缓存
                if (entry != null) {
                    storeToRedis(entry);
                }
            }
        }

        // 检查是否过期
        if (entry == null || entry.isExpired()) {
            log.debug("记忆不存在或已过期: memoryId={}", memoryId);
            return null;
        }

        // 租户隔离检查
        if (!entry.getMerchantId().equals(merchantId)) {
            log.warn("租户隔离检查失败: memoryId={}, expected={}, actual={}",
                    memoryId, merchantId, entry.getMerchantId());
            return null;
        }

        // 更新访问统计
        entry.incrementAccessCount();

        log.debug("记忆已读取: memoryId={}, value={}", memoryId, entry.getValue());
        return entry.getValue();
    }

    /**
     * 删除记忆
     *
     * @param layer      记忆层
     * @param scopeId    作用域ID
     * @param key        记忆键
     * @param merchantId 商户ID
     */
    public void delete(MemoryLayer layer, String scopeId, String key, Long merchantId) {
        String memoryId = generateMemoryId(layer, scopeId, key);

        if (layer == MemoryLayer.WORKING) {
            workingMemoryCache.remove(memoryId);
        } else if (layer == MemoryLayer.SESSION) {
            redisTemplate.delete(memoryId);
        } else if (layer.requiresPersistence()) {
            deleteFromMySQL(memoryId);
            redisTemplate.delete(memoryId);
        }

        log.info("记忆已删除: memoryId={}", memoryId);
    }

    /**
     * 列出某个作用域下的所有记忆
     *
     * @param layer      记忆层
     * @param scopeId    作用域ID
     * @param merchantId 商户ID
     * @return 记忆键列表
     */
    public List<String> listKeys(MemoryLayer layer, String scopeId, Long merchantId) {
        String pattern = generateMemoryId(layer, scopeId, "*");
        Set<String> keys;

        if (layer == MemoryLayer.WORKING) {
            keys = workingMemoryCache.keySet().stream()
                    .filter(k -> k.startsWith(layer.name() + ":" + scopeId + ":"))
                    .collect(Collectors.toSet());
        } else {
            keys = redisTemplate.keys(pattern);
        }

        if (keys == null) {
            return Collections.emptyList();
        }

        return keys.stream()
                .map(k -> k.substring(k.lastIndexOf(":") + 1))
                .collect(Collectors.toList());
    }

    /**
     * 清空某个作用域的所有记忆
     *
     * @param layer      记忆层
     * @param scopeId    作用域ID
     * @param merchantId 商户ID
     */
    public void clearScope(MemoryLayer layer, String scopeId, Long merchantId) {
        List<String> keys = listKeys(layer, scopeId, merchantId);
        for (String key : keys) {
            delete(layer, scopeId, key, merchantId);
        }
        log.info("作用域记忆已清空: layer={}, scopeId={}, count={}", layer, scopeId, keys.size());
    }

    /**
     * 清理过期记忆（定时任务调用）
     */
    public void cleanupExpiredMemories() {
        log.info("开始清理过期记忆...");

        // 清理WORKING层内存缓存
        workingMemoryCache.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().isExpired();
            if (expired) {
                log.debug("清理过期WORKING记忆: {}", entry.getKey());
            }
            return expired;
        });

        // Redis的TTL自动过期，无需手动清理
        // MySQL的过期记忆由定时任务清理（agent_memory表的expires_at字段）

        log.info("过期记忆清理完成");
    }

    // ==================== 私有方法 ====================

    /**
     * 存储到WORKING内存缓存
     */
    private void storeToWorkingCache(MemoryEntry entry) {
        workingMemoryCache.put(entry.getMemoryId(), entry);
    }

    /**
     * 存储到Redis
     */
    private void storeToRedis(MemoryEntry entry) {
        String json = toJson(entry);
        redisTemplate.opsForValue().set(
                entry.getMemoryId(),
                json,
                entry.getTtlSeconds(),
                TimeUnit.SECONDS
        );
    }

    /**
     * 从Redis读取
     */
    private MemoryEntry retrieveFromRedis(String memoryId) {
        String json = redisTemplate.opsForValue().get(memoryId);
        if (json == null) {
            return null;
        }
        return fromJson(json);
    }

    /**
     * 存储到MySQL（占位方法，需要MemoryRepository）
     */
    private void storeToMySQL(MemoryEntry entry) {
        // TODO: 集成MemoryRepository
        log.debug("存储到MySQL（占位）: memoryId={}", entry.getMemoryId());
    }

    /**
     * 从MySQL读取（占位方法）
     */
    private MemoryEntry retrieveFromMySQL(String memoryId) {
        // TODO: 集成MemoryRepository
        log.debug("从MySQL读取（占位）: memoryId={}", memoryId);
        return null;
    }

    /**
     * 从MySQL删除（占位方法）
     */
    private void deleteFromMySQL(String memoryId) {
        // TODO: 集成MemoryRepository
        log.debug("从MySQL删除（占位）: memoryId={}", memoryId);
    }

    /**
     * 生成记忆ID
     */
    private String generateMemoryId(MemoryLayer layer, String scopeId, String key) {
        return String.format("%s:%s:%s", layer.name(), scopeId, key);
    }

    /**
     * 序列化为JSON
     */
    private String toJson(MemoryEntry entry) {
        // 简化实现，生产环境使用Jackson
        return String.format("{\"memoryId\":\"%s\",\"value\":\"%s\",\"expiresAt\":\"%s\"}",
                entry.getMemoryId(),
                entry.getValue().replace("\"", "\\\""),
                entry.getExpiresAt());
    }

    /**
     * 从JSON反序列化
     */
    private MemoryEntry fromJson(String json) {
        // 简化实现，生产环境使用Jackson
        MemoryEntry entry = new MemoryEntry();
        // 提取value字段
        int valueStart = json.indexOf("\"value\":\"") + 9;
        int valueEnd = json.indexOf("\",\"expiresAt\"");
        if (valueStart > 9 && valueEnd > valueStart) {
            entry.setValue(json.substring(valueStart, valueEnd).replace("\\\"", "\""));
        }
        return entry;
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("workingMemoryCount", workingMemoryCache.size());
        stats.put("layers", Arrays.stream(MemoryLayer.values())
                .collect(Collectors.toMap(
                        MemoryLayer::name,
                        layer -> Map.of(
                                "displayName", layer.getDisplayName(),
                                "defaultTtl", layer.getDefaultTtlSeconds(),
                                "storage", layer.getStorageMedium()
                        )
                )));
        return stats;
    }
}

