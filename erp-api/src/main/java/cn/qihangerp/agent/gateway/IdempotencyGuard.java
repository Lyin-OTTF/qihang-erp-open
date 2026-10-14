package cn.qihangerp.agent.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 幂等性守护。
 *
 * <p>第四期加固：checkAndMark 改用 SET NX 原子操作（setIfAbsent），
 * 消除 GET-then-SET 竞态；Redis 不可用时 fail-open（放行但记录警告），
 * 避免 Redis 故障阻塞全部写操作。</p>
 */
@Slf4j
@Component
public class IdempotencyGuard {

    private final StringRedisTemplate redisTemplate;
    private final IdempotentKeyGenerator keyGenerator;

    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_COMPLETED = "completed:";

    public IdempotencyGuard(StringRedisTemplate redisTemplate, IdempotentKeyGenerator keyGenerator) {
        this.redisTemplate = redisTemplate;
        this.keyGenerator = keyGenerator;
    }

    /**
     * 原子检查并标记幂等键（SET NX）。
     *
     * @return IdempotencyResult：isDuplicate=true 表示重复调用
     */
    public IdempotencyResult checkAndMark(Long merchantId, Long userId, String toolName, Object... args) {
        String key = keyGenerator.generateKey(merchantId, userId, toolName, args);
        try {
            // 原子 SET NX：只有 key 不存在时才设置成功
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    key, STATUS_PROCESSING, keyGenerator.getTtlSeconds(), TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(acquired)) {
                // key 已存在，读取状态判断是 processing 还是 completed
                String existing = redisTemplate.opsForValue().get(key);
                if (existing != null && existing.startsWith(STATUS_COMPLETED)) {
                    String cachedResult = existing.substring(STATUS_COMPLETED.length());
                    log.info("幂等命中（已完成）: tool={}, key={}", toolName, key);
                    return IdempotencyResult.duplicate(cachedResult);
                }
                log.info("幂等命中（执行中）: tool={}, key={}", toolName, key);
                return IdempotencyResult.processing();
            }
            log.debug("幂等标记（执行中，原子SET NX）: tool={}, key={}", toolName, key);
            return IdempotencyResult.proceed(key);
        } catch (Exception e) {
            // Redis 不可用时 fail-open：放行写操作，避免 Redis 故障导致业务全阻
            log.warn("幂等检查Redis异常，fail-open放行: tool={}, error={}", toolName, e.getMessage());
            return IdempotencyResult.proceed(key);
        }
    }

    /**
     * 标记执行完成，缓存结果摘要
     */
    public void complete(String idempotencyKey, String resultDigest) {
        try {
            redisTemplate.opsForValue().set(idempotencyKey, STATUS_COMPLETED + resultDigest,
                    keyGenerator.getTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("标记幂等完成失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 标记执行失败，清除幂等标记（允许重试）
     */
    public void fail(String idempotencyKey) {
        try {
            redisTemplate.delete(idempotencyKey);
        } catch (Exception e) {
            log.warn("清除幂等标记失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 幂等检查结果
     */
    public static class IdempotencyResult {
        private final boolean duplicate;
        private final boolean processing;
        private final String idempotencyKey;
        private final String cachedResult;

        private IdempotencyResult(boolean duplicate, boolean processing, String idempotencyKey, String cachedResult) {
            this.duplicate = duplicate;
            this.processing = processing;
            this.idempotencyKey = idempotencyKey;
            this.cachedResult = cachedResult;
        }

        public static IdempotencyResult proceed(String key) {
            return new IdempotencyResult(false, false, key, null);
        }

        public static IdempotencyResult duplicate(String cachedResult) {
            return new IdempotencyResult(true, false, null, cachedResult);
        }

        public static IdempotencyResult processing() {
            return new IdempotencyResult(true, true, null, null);
        }

        public boolean isDuplicate() { return duplicate; }
        public boolean isProcessing() { return processing; }
        public boolean shouldProceed() { return !duplicate; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public String getCachedResult() { return cachedResult; }
    }
}
