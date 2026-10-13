package cn.qihangerp.agent.gateway;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 幂等键生成器。
 *
 * <p>为 Tool 调用生成确定性幂等键：同一商户+同一用户+同一工具+同一参数组合
 * 始终生成相同的 key，用于防止重复提交（如重复退款申请、重复创建采购单）。</p>
 *
 * <p>算法：SHA-256(merchantId + ":" + userId + ":" + toolName + ":" + sortedArgs)</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
@Component
public class IdempotentKeyGenerator {

    private static final String IDEMPOTENCY_PREFIX = "agent:idem:";
    private static final long DEFAULT_TTL_SECONDS = 86400; // 24小时

    /**
     * 生成幂等键
     *
     * @param merchantId 商户ID
     * @param userId 用户ID
     * @param toolName 工具名称（类名.方法名）
     * @param args 方法参数
     * @return 幂等键（含前缀的完整Redis key）
     */
    public String generateKey(Long merchantId, Long userId, String toolName, Object... args) {
        String raw = merchantId + ":" + userId + ":" + toolName + ":" +
                (args == null ? "" : Arrays.stream(args)
                        .map(a -> a == null ? "null" : a.toString())
                        .collect(Collectors.joining(",")));
        String hash = sha256(raw);
        return IDEMPOTENCY_PREFIX + hash;
    }

    /**
     * 获取幂等键的TTL（秒）
     */
    public long getTtlSeconds() {
        return DEFAULT_TTL_SECONDS;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标准算法，不可能不存在
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
