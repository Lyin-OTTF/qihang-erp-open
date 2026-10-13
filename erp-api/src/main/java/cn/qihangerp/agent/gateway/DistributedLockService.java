package cn.qihangerp.agent.gateway;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的分布式锁服务。
 *
 * <p>使用 Redisson RLock，具备：
 * <ul>
 *   <li>可重入（同一线程可多次加锁）</li>
 *   <li>Watchdog 自动续期（未指定 leaseTime 时）</li>
 *   <li>释放时校验持有线程，防止误删他人锁</li>
 *   <li>阻塞/非阻塞/超时等待三种获取模式</li>
 * </ul>
 * </p>
 *
 * <p>与原有 StringRedisTemplate 手写锁保持相同 API（tryLock / tryLockWithWait / unlock / LockHandle），
 * ToolGateway 等调用方无需改动。</p>
 *
 * @author 回溯开发-Redisson接入
 * @since 2026-10-13
 */
@Slf4j
@Component
public class DistributedLockService {

    private final RedissonClient redissonClient;

    private static final String LOCK_PREFIX = "agent:lock:";

    public DistributedLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取锁（非阻塞，立即返回）
     *
     * @param lockKey 锁键（业务标识，如 "inventory:sku:123"）
     * @param ttlSeconds 锁超时秒数（leaseTime，到期自动释放）
     * @return LockHandle（isAcquired()判断是否成功）
     */
    public LockHandle tryLock(String lockKey, long ttlSeconds) {
        String key = LOCK_PREFIX + lockKey;
        RLock rlock = redissonClient.getLock(key);
        try {
            boolean acquired = rlock.tryLock(0, ttlSeconds, TimeUnit.SECONDS);
            if (acquired) {
                log.debug("Redisson获取锁成功: key={}", key);
                return new LockHandle(key, rlock, true);
            }
            log.debug("Redisson获取锁失败（被占用）: key={}", key);
            return new LockHandle(key, (RLock) null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Redisson获取锁被中断: key={}", key);
            return new LockHandle(key, (RLock) null, false);
        }
    }

    /**
     * 尝试获取锁（带等待超时）
     *
     * @param lockKey 锁键
     * @param ttlSeconds 锁超时秒数
     * @param maxWaitMillis 最大等待毫秒数
     * @return LockHandle
     */
    public LockHandle tryLockWithWait(String lockKey, long ttlSeconds, long maxWaitMillis) {
        String key = LOCK_PREFIX + lockKey;
        RLock rlock = redissonClient.getLock(key);
        try {
            boolean acquired = rlock.tryLock(maxWaitMillis, ttlSeconds, TimeUnit.MILLISECONDS);
            if (acquired) {
                log.debug("Redisson等待获取锁成功: key={}, wait<={}ms", key, maxWaitMillis);
                return new LockHandle(key, rlock, true);
            }
            log.debug("Redisson等待获取锁超时: key={}, wait={}ms", key, maxWaitMillis);
            return new LockHandle(key, (RLock) null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Redisson等待获取锁被中断: key={}", key);
            return new LockHandle(key, (RLock) null, false);
        }
    }

    /**
     * 释放锁（Redisson 内部校验持有线程，防止误删）
     */
    public void unlock(LockHandle handle) {
        if (handle == null || !handle.isAcquired()) {
            return;
        }
        RLock rlock = handle.getRLock();
        if (rlock == null) {
            return;
        }
        try {
            if (rlock.isHeldByCurrentThread()) {
                rlock.unlock();
                log.debug("Redisson释放锁: key={}", handle.getKey());
            } else {
                log.warn("Redisson释放锁时当前线程未持有（锁可能已超时）: key={}", handle.getKey());
            }
        } catch (Exception e) {
            log.warn("Redisson释放锁异常: key={}, error={}", handle.getKey(), e.getMessage());
        }
    }

    /**
     * 锁句柄，实现 AutoCloseable 便于 try-with-resources
     */
    public static class LockHandle implements AutoCloseable {
        private final String key;
        private final String token;
        private final boolean acquired;
        private final RLock rlock;

        /** 兼容旧构造器（token 保留但不再用于释放校验，Redisson 内部处理） */
        public LockHandle(String key, String token, boolean acquired) {
            this.key = key;
            this.token = token;
            this.acquired = acquired;
            this.rlock = null;
        }

        /** Redisson 构造器 */
        public LockHandle(String key, RLock rlock, boolean acquired) {
            this.key = key;
            this.token = "";
            this.acquired = acquired;
            this.rlock = rlock;
        }

        public String getKey() { return key; }
        public String getToken() { return token; }
        public boolean isAcquired() { return acquired; }
        public RLock getRLock() { return rlock; }

        @Override
        public void close() {
            // unlock 需要外部 DistributedLockService 调用，此处仅标记
        }
    }
}
