package cn.qihangerp.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 手动配置。
 *
 * <p>不使用 redisson-spring-boot-starter，避免其自动配置的 RedissonConnectionFactory
 * 与 Lettuce（spring-boot-starter-data-redis）冲突。此处仅创建 RedissonClient bean，
 * 专供分布式锁使用；StringRedisTemplate 仍走 Lettuce，互不干扰。</p>
 *
 * <p>连接信息复用 spring.data.redis.* 配置，与现有 Redis 缓存共用同一实例。</p>
 *
 * @author 回溯开发-Redisson接入
 * @since 2026-10-13
 */
@Slf4j
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.timeout:10s}")
    private String timeout;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;

        var singleServer = config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);

        // 密码为空时不设置（Redisson 不接受空字符串密码）
        if (password != null && !password.isBlank()) {
            singleServer.setPassword(password);
        }

        // 解析超时时间（如 "10s" → 10000ms）
        int timeoutMs = parseTimeoutMillis(timeout);
        singleServer.setTimeout(timeoutMs);
        singleServer.setConnectTimeout(timeoutMs);

        RedissonClient client = Redisson.create(config);
        log.info("RedissonClient 初始化完成: address={}, database={}, timeout={}ms", address, database, timeoutMs);
        return client;
    }

    private int parseTimeoutMillis(String value) {
        if (value == null || value.isBlank()) {
            return 10000;
        }
        String v = value.trim().toLowerCase();
        try {
            if (v.endsWith("ms")) {
                return Integer.parseInt(v.substring(0, v.length() - 2).trim());
            } else if (v.endsWith("s")) {
                return Integer.parseInt(v.substring(0, v.length() - 1).trim()) * 1000;
            } else if (v.endsWith("m")) {
                return Integer.parseInt(v.substring(0, v.length() - 1).trim()) * 60000;
            } else {
                return Integer.parseInt(v);
            }
        } catch (NumberFormatException e) {
            log.warn("无法解析超时配置 '{}'，使用默认10000ms", value);
            return 10000;
        }
    }
}
