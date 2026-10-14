package cn.qihangerp.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 记忆管理器（魔改方案 Week6-7：持久化分层记忆）
 *
 * 四层分级：
 * - WORKING 工作记忆（1小时，进程内存）
 * - SESSION 会话记忆（1天，Redis，重启可恢复）
 * - USER    用户记忆（30天，MySQL 长期 + Redis 缓存）
 * - TEAM    团队记忆（90天，MySQL 长期 + Redis 缓存，商户内共享）
 *
 * 统一保证：写入即脱敏、读取做租户隔离、TTL 自动过期。
 *
 * @author Doubao
 * @since 2026-10-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryManager {

    private final StringRedisTemplate redisTemplate;
    private final MemoryRepository repository;

    /** WORKING 层进程内缓存 */
    private final ConcurrentHashMap<String, MemoryEntry> workingMemoryCache = new ConcurrentHashMap<>();

    /**
     * 专用 Jackson：完整序列化整条记忆。
     * findAndRegisterModules 通过 SPI 自动注册 classpath 上的 jackson-datatype-jsr310（runtime 作用域），
     * 因此编译期无需直接引用 JavaTimeModule，且能正确处理 LocalDateTime。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 存储记忆
     */
    public void store(MemoryLayer layer, String scopeId, String key, String value,
                      Long merchantId, Long userId) {
        MemoryEntry entry = MemoryEntry.create(layer, scopeId, key, value, merchantId, userId);
        // 统一脱敏后再落任何介质
        entry.setValue(SensitiveDataMasker.autoMask(value));

        switch (layer) {
            case WORKING:
                workingMemoryCache.put(entry.getMemoryId(), entry);
                break;
            case SESSION:
                writeRedis(entry);
                break;
            case USER:
            case TEAM:
                repository.save(entry);
                writeRedis(entry);
                break;
        }
        log.info("记忆已存储: layer={}, scopeId={}, key={}, merchantId={}", layer, scopeId, key, merchantId);
    }

    /**
     * 读取记忆，不存在 / 已过期 / 跨租户均返回 null
     */
    public String retrieve(MemoryLayer layer, String scopeId, String key, Long merchantId) {
        String memoryId = buildMemoryId(layer, scopeId, key);
        MemoryEntry entry;

        switch (layer) {
            case WORKING:
                entry = workingMemoryCache.get(memoryId);
                break;
            case SESSION:
                entry = readRedis(memoryId);
                break;
            case USER:
            case TEAM:
                entry = readRedis(memoryId);
                if (entry == null) {
                    entry = repository.findById(memoryId);
                    if (entry != null) {
                        writeRedis(entry); // 回写缓存
                    }
                }
                break;
            default:
                entry = null;
        }

        if (entry == null || entry.isExpired()) {
            log.debug("记忆不存在或已过期: {}", memoryId);
            return null;
        }

        // 租户隔离（Objects.equals 避免 merchantId 为 null 时空指针）
        if (!Objects.equals(entry.getMerchantId(), merchantId)) {
            log.warn("租户隔离拦截: memoryId={}, expected={}, actual={}",
                    memoryId, merchantId, entry.getMerchantId());
            return null;
        }

        entry.incrementAccessCount();
        entry.setLastAccessedAt(java.time.LocalDateTime.now());
        // 回写 access_count / last_accessed_at 到 Redis（WORKING 层纯内存无需持久化）
        if (layer != MemoryLayer.WORKING) {
            writeRedis(entry);
        }
        return entry.getValue();
    }

    /**
     * 删除记忆
     */
    public void delete(MemoryLayer layer, String scopeId, String key, Long merchantId) {
        String memoryId = buildMemoryId(layer, scopeId, key);
        switch (layer) {
            case WORKING:
                workingMemoryCache.remove(memoryId);
                break;
            case SESSION:
                redisTemplate.delete(memoryId);
                break;
            case USER:
            case TEAM:
                repository.deleteById(memoryId);
                redisTemplate.delete(memoryId);
                break;
        }
        log.info("记忆已删除: {}", memoryId);
    }

    /**
     * 列出某作用域下的记忆键（用 SCAN 替代 KEYS，避免大 keyspace 阻塞 Redis）
     */
    public List<String> listKeys(MemoryLayer layer, String scopeId, Long merchantId) {
        if (layer == MemoryLayer.WORKING) {
            String prefix = layer.name() + ":" + scopeId + ":";
            return workingMemoryCache.keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .map(k -> k.substring(k.lastIndexOf(":") + 1))
                    .collect(Collectors.toList());
        }
        String pattern = layer.name() + ":" + scopeId + ":*";
        Set<String> keys = new HashSet<>();
        try (org.springframework.data.redis.core.Cursor<String> cursor = redisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern)
                        .count(100)
                        .build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("SCAN遍历记忆键失败（降级为KEYS）: pattern={}, error={}", pattern, e.getMessage());
            Set<String> fallback = redisTemplate.keys(pattern);
            if (fallback != null) keys.addAll(fallback);
        }
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        return keys.stream()
                .map(k -> k.substring(k.lastIndexOf(":") + 1))
                .collect(Collectors.toList());
    }

    /**
     * 清空某作用域全部记忆
     */
    public void clearScope(MemoryLayer layer, String scopeId, Long merchantId) {
        List<String> keys = listKeys(layer, scopeId, merchantId);
        for (String key : keys) {
            delete(layer, scopeId, key, merchantId);
        }
        log.info("作用域记忆已清空: layer={}, scopeId={}, count={}", layer, scopeId, keys.size());
    }

    /**
     * 清理过期记忆（定时任务调用）：WORKING 内存 + MySQL 长期；Redis 靠自身 TTL
     */
    public void cleanupExpiredMemories() {
        workingMemoryCache.entrySet().removeIf(en -> en.getValue().isExpired());
        repository.deleteExpired();
        log.info("过期记忆清理完成");
    }

    /**
     * 统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("workingMemoryCount", workingMemoryCache.size());
        Map<String, Object> layers = new LinkedHashMap<>();
        for (MemoryLayer layer : MemoryLayer.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("displayName", layer.getDisplayName());
            info.put("defaultTtl", layer.getDefaultTtlSeconds());
            info.put("storage", layer.getStorageMedium());
            layers.put(layer.name(), info);
        }
        stats.put("layers", layers);
        return stats;
    }

    // ==================== 内部方法 ====================

    private void writeRedis(MemoryEntry entry) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(
                    entry.getMemoryId(), json,
                    entry.getTtlSeconds() == null ? 1 : entry.getTtlSeconds(),
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("写入Redis失败: {}", entry.getMemoryId(), e);
        }
    }

    private MemoryEntry readRedis(String memoryId) {
        try {
            String json = redisTemplate.opsForValue().get(memoryId);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, MemoryEntry.class);
        } catch (Exception e) {
            log.error("读取Redis失败: {}", memoryId, e);
            return null;
        }
    }

    private static String buildMemoryId(MemoryLayer layer, String scopeId, String key) {
        return String.format("%s:%s:%s", layer.name(), scopeId, key);
    }
}
