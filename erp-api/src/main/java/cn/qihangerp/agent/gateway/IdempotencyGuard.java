package cn.qihangerp.agent.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 幂等性守护。
 *
 * <p>在 Tool 执行前检查幂等键：如果相同参数的调用已经在执行或已完成，
 * 直接返回缓存结果或拒绝重复调用。</p>
 *
 * <p>状态流转：
 * <pre>
 *   checkAndMark() → "processing"（标记执行中）
 *   执行完成 → complete(resultDigest)（标记完成，缓存结果摘要）
 *   执行失败 → fail()（清除标记，允许重试）
 * </pre>
 * </p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
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
     * 检查并标记幂等键。
     *
     * @param merchantId 商户ID
     * @param userId 用户ID
     * @param toolName 工具名称
     * @param args 方法参数
     * @return IdempotencyResult：isDuplicate=true 表示重复调用，cachedResult 含上次结果摘要
     */
    public IdempotencyResult checkAndMark(Long merchantId, Long userId, String toolName, Object... args) {
        String key = keyGenerator.generateKey(merchantId, userId, toolName, args);
        String existing = redisTemplate.opsForValue().get(key);

        if (existing != null) {
            if (existing.startsWith(STATUS_COMPLETED)) {
                String cachedResult = existing.substring(STATUS_COMPLETED.length());
                log.info("幂等命中（已完成）: tool={}, key={}", toolName, key);
                return IdempotencyResult.duplicate(cachedResult);
            }
            if (STATUS_PROCESSING.equals(existing)) {
                log.info("幂等命中（执行中）: tool={}, key={}", toolName, key);
                return IdempotencyResult.processing();
            }
        }

        // 标记为执行中
        redisTemplate.opsForValue().set(key, STATUS_PROCESSING, keyGenerator.getTtlSeconds(), TimeUnit.SECONDS);
        log.debug("幂等标记（执行中）: tool={}, key={}", toolName, key);
        return IdempotencyResult.proceed(key);
    }

    /**
     * 标记执行完成，缓存结果摘要
     */
    public void complete(String idempotencyKey, String resultDigest) {
        redisTemplate.opsForValue().set(idempotencyKey, STATUS_COMPLETED + resultDigest,
                keyGenerator.getTtlSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 标记执行失败，清除幂等标记（允许重试）
     */
    public void fail(String idempotencyKey) {
        redisTemplate.delete(idempotencyKey);
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
