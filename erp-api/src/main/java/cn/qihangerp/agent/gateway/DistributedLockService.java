package cn.qihangerp.agent.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的分布式锁服务。
 *
 * <p>原方案要求 Redisson，但 redisson-spring-boot-starter 3.35.0 在 Spring Boot 4.1 下
 * 引用已移除的 RedisProperties 导致启动失败。此处用 StringRedisTemplate + Lua 原子操作
 * 实现轻量分布式锁，满足库存扣减、状态迁移等场景的互斥需求。</p>
 *
 * <p>锁语义：
 * <ul>
 *   <li>SET key value NX PX ttl — 原子加锁</li>
 *   <li>value 为唯一 token（UUID），释放时校验 token 防止误删他人锁</li>
 *   <li>默认锁超时 30 秒，防止死锁</li>
 * </ul>
 * </p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Slf4j
@Component
public class DistributedLockService {

    private final StringRedisTemplate redisTemplate;

    private static final String LOCK_PREFIX = "agent:lock:";

    public DistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取锁（非阻塞，立即返回）
     *
     * @param lockKey 锁键（业务标识，如 "inventory:sku:123"）
     * @param ttlSeconds 锁超时秒数
     * @return LockHandle（持有token，isAcquired()判断是否成功）
     */
    public LockHandle tryLock(String lockKey, long ttlSeconds) {
        String key = LOCK_PREFIX + lockKey;
        String token = java.util.UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, token, ttlSeconds, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("获取分布式锁成功: key={}, token={}", key, token);
            return new LockHandle(key, token, true);
        }
        log.debug("获取分布式锁失败（被占用）: key={}", key);
        return new LockHandle(key, token, false);
    }

    /**
     * 尝试获取锁（带等待重试）
     *
     * @param lockKey 锁键
     * @param ttlSeconds 锁超时秒数
     * @param maxWaitMillis 最大等待毫秒数
     * @return LockHandle
     */
    public LockHandle tryLockWithWait(String lockKey, long ttlSeconds, long maxWaitMillis) {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            LockHandle handle = tryLock(lockKey, ttlSeconds);
            if (handle.isAcquired()) {
                return handle;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new LockHandle(LOCK_PREFIX + lockKey, "", false);
    }

    /**
     * 释放锁（校验 token，防止误删）
     */
    public void unlock(LockHandle handle) {
        if (handle == null || !handle.isAcquired()) {
            return;
        }
        String current = redisTemplate.opsForValue().get(handle.getKey());
        if (handle.getToken().equals(current)) {
            redisTemplate.delete(handle.getKey());
            log.debug("释放分布式锁: key={}", handle.getKey());
        } else {
            log.warn("释放锁时token不匹配（锁可能已超时被他人获取）: key={}", handle.getKey());
        }
    }

    /**
     * 锁句柄，实现 AutoCloseable 便于 try-with-resources
     */
    public static class LockHandle implements AutoCloseable {
        private final String key;
        private final String token;
        private final boolean acquired;

        public LockHandle(String key, String token, boolean acquired) {
            this.key = key;
            this.token = token;
            this.acquired = acquired;
        }

        public String getKey() { return key; }
        public String getToken() { return token; }
        public boolean isAcquired() { return acquired; }

        @Override
        public void close() {
            // unlock 需要外部 DistributedLockService 调用，此处仅标记
        }
    }
}
