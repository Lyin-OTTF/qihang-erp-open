package cn.qihangerp.agent.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 幂等性守护集成测试（搁置项攻关-任务4）。
 *
 * <p>使用真实 Redis 验证：首次调用放行、重复调用拦截（processing/completed）、
 * 完成后缓存结果、失败后清除标记允许重试、相同参数生成相同key。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
class IdempotencyGuardTest {

    private static StringRedisTemplate redisTemplate;
    private static IdempotentKeyGenerator keyGenerator;
    private static IdempotencyGuard guard;

    @BeforeAll
    static void setup() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        keyGenerator = new IdempotentKeyGenerator();
        guard = new IdempotencyGuard(redisTemplate, keyGenerator);
    }

    @AfterEach
    void cleanup() {
        // 清理测试产生的幂等key（TTL 24h，测试后主动清理）
        Set<String> keys = redisTemplate.keys("agent:idem:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void firstCall_proceeds() {
        IdempotencyGuard.IdempotencyResult result =
                guard.checkAndMark(1L, 100L, "testTool", "arg1", "arg2");
        assertTrue(result.shouldProceed(), "首次调用应放行");
        assertFalse(result.isDuplicate());
        assertNotNull(result.getIdempotencyKey());
    }

    @Test
    void duplicateCall_whileProcessing_isBlocked() {
        IdempotencyGuard.IdempotencyResult r1 =
                guard.checkAndMark(1L, 100L, "testTool", "same-args");
        assertTrue(r1.shouldProceed());

        IdempotencyGuard.IdempotencyResult r2 =
                guard.checkAndMark(1L, 100L, "testTool", "same-args");
        assertTrue(r2.isDuplicate(), "执行中的重复调用应被拦截");
        assertTrue(r2.isProcessing(), "状态应为processing");
    }

    @Test
    void duplicateCall_afterCompleted_returnsCachedResult() {
        IdempotencyGuard.IdempotencyResult r1 =
                guard.checkAndMark(1L, 100L, "testTool", "complete-args");
        assertTrue(r1.shouldProceed());

        // 标记完成
        guard.complete(r1.getIdempotencyKey(), "result-digest-abc");

        IdempotencyGuard.IdempotencyResult r2 =
                guard.checkAndMark(1L, 100L, "testTool", "complete-args");
        assertTrue(r2.isDuplicate(), "已完成的重复调用应被拦截");
        assertFalse(r2.isProcessing());
        assertEquals("result-digest-abc", r2.getCachedResult(), "应返回缓存的结果摘要");
    }

    @Test
    void afterFail_callCanProceedAgain() {
        IdempotencyGuard.IdempotencyResult r1 =
                guard.checkAndMark(1L, 100L, "testTool", "fail-args");
        assertTrue(r1.shouldProceed());

        // 标记失败
        guard.fail(r1.getIdempotencyKey());

        IdempotencyGuard.IdempotencyResult r2 =
                guard.checkAndMark(1L, 100L, "testTool", "fail-args");
        assertTrue(r2.shouldProceed(), "失败后应允许重试");
    }

    @Test
    void sameArgs_generateSameKey() {
        String key1 = keyGenerator.generateKey(1L, 100L, "toolA", "x", 123);
        String key2 = keyGenerator.generateKey(1L, 100L, "toolA", "x", 123);
        assertEquals(key1, key2, "相同参数应生成相同幂等键");
    }

    @Test
    void differentArgs_generateDifferentKey() {
        String key1 = keyGenerator.generateKey(1L, 100L, "toolA", "x");
        String key2 = keyGenerator.generateKey(1L, 100L, "toolA", "y");
        assertNotEquals(key1, key2, "不同参数应生成不同幂等键");
    }

    @Test
    void differentMerchant_generateDifferentKey() {
        String key1 = keyGenerator.generateKey(1L, 100L, "toolA", "x");
        String key2 = keyGenerator.generateKey(2L, 100L, "toolA", "x");
        assertNotEquals(key1, key2, "不同商户应生成不同幂等键");
    }

    @Test
    void differentTool_generateDifferentKey() {
        String key1 = keyGenerator.generateKey(1L, 100L, "toolA", "x");
        String key2 = keyGenerator.generateKey(1L, 100L, "toolB", "x");
        assertNotEquals(key1, key2, "不同工具应生成不同幂等键");
    }
}
