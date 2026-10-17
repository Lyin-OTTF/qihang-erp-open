package cn.qihangerp.agent.memory;
import org.junit.jupiter.api.Tag;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryManager 端到端集成测试（真实 Redis + 真实 MySQL）。
 *
 * <p>验证四层记忆的存储/读取/租户隔离/敏感脱敏/TTL 行为。
 * 使用真实 127.0.0.1:6379 Redis 和 qihang-erp MySQL。</p>
 *
 * @author 回溯开发-阶段3
 * @since 2026-10-12
 */
@Tag("it")
class MemoryManagerE2ETest {

    private static final String TEST_PREFIX = "mgr-e2e-" + System.currentTimeMillis();
    private static MemoryManager memoryManager;
    private static JdbcTemplate jdbcTemplate;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setup() {
        // MySQL
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser(System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        ds.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD", "1234"));
        jdbcTemplate = new JdbcTemplate((DataSource) ds);
        MemoryRepository repository = new MemoryRepository(jdbcTemplate);

        // Redis
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        String redisPassword = System.getenv().getOrDefault("REDIS_PASSWORD", "");
        if (!redisPassword.isBlank()) {
            factory.setPassword(redisPassword);
        }
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);

        memoryManager = new MemoryManager(redisTemplate, repository);
    }

    @AfterAll
    static void cleanup() {
        // 清理 MySQL 测试数据
        jdbcTemplate.update("DELETE FROM agent_memory WHERE memory_id LIKE ?", TEST_PREFIX + "%");
        // 清理 Redis 测试数据
        var keys = redisTemplate.keys(TEST_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void userLayer_storeAndRetrieve_persistsToMysql() {
        String scopeId = TEST_PREFIX + "_user1";
        String key = "preference";
        String value = "喜欢用顺丰快递";

        memoryManager.store(MemoryLayer.USER, scopeId, key, value, 1001L, 2001L);

        // 直接查 MySQL 验证持久化
        String memoryId = "USER:" + scopeId + ":" + key;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory WHERE memory_id = ?", Integer.class, memoryId);
        assertEquals(1, count, "USER层记忆应持久化到MySQL");

        // 读取验证
        String retrieved = memoryManager.retrieve(MemoryLayer.USER, scopeId, key, 1001L);
        assertEquals(value, retrieved, "应能读取到存储的值");
    }

    @Test
    void userLayer_tenantIsolation_merchantBCannotSeeMerchantA() {
        String scopeId = TEST_PREFIX + "_iso";
        String key = "secret";
        String value = "租户A的机密";

        memoryManager.store(MemoryLayer.USER, scopeId, key, value, 1001L, 2001L);

        // 租户B读取 → 应返回null
        String forB = memoryManager.retrieve(MemoryLayer.USER, scopeId, key, 2002L);
        assertNull(forB, "租户B不应看到租户A的记忆");

        // 租户A读取 → 应成功
        String forA = memoryManager.retrieve(MemoryLayer.USER, scopeId, key, 1001L);
        assertEquals(value, forA, "租户A应能看到自己的记忆");
    }

    @Test
    void sensitiveData_phoneNumber_isMaskedInStorage() {
        String scopeId = TEST_PREFIX + "_mask";
        String key = "contact";
        String rawValue = "我的手机号是13812345678，请联系";

        memoryManager.store(MemoryLayer.USER, scopeId, key, rawValue, 1001L, 2001L);

        // 直接查 MySQL 验证存储的值已脱敏
        String memoryId = "USER:" + scopeId + ":" + key;
        String storedValue = jdbcTemplate.queryForObject(
                "SELECT value FROM agent_memory WHERE memory_id = ?", String.class, memoryId);
        assertNotNull(storedValue);
        assertFalse(storedValue.contains("13812345678"),
                "MySQL中存储的值不应包含明文手机号，实际: " + storedValue);
        assertTrue(storedValue.contains("138****5678") || storedValue.contains("***"),
                "应包含脱敏后的手机号，实际: " + storedValue);
    }

    @Test
    void teamLayer_sharedWithinMerchant() {
        String scopeId = TEST_PREFIX + "_team";
        String key = "team_note";
        String value = "团队共享：本周盘点";

        // 商户1001的用户2001存储
        memoryManager.store(MemoryLayer.TEAM, scopeId, key, value, 1001L, 2001L);

        // 商户1001的用户2002读取 → 应能看到（TEAM层商户内共享）
        String forUser2 = memoryManager.retrieve(MemoryLayer.TEAM, scopeId, key, 1001L);
        assertEquals(value, forUser2, "同商户不同用户应共享TEAM层记忆");

        // 商户1002读取 → 应看不到
        String forOtherMerchant = memoryManager.retrieve(MemoryLayer.TEAM, scopeId, key, 1002L);
        assertNull(forOtherMerchant, "其他商户不应看到本商户TEAM记忆");
    }

    @Test
    void workingLayer_inMemoryOnly_notPersisted() {
        String scopeId = TEST_PREFIX + "_work";
        String key = "temp";
        String value = "临时工作记忆";

        memoryManager.store(MemoryLayer.WORKING, scopeId, key, value, 1001L, 2001L);

        // WORKING层不落MySQL
        String memoryId = "WORKING:" + scopeId + ":" + key;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory WHERE memory_id = ?", Integer.class, memoryId);
        assertEquals(0, count, "WORKING层不应持久化到MySQL");

        // 但内存中可读取
        String retrieved = memoryManager.retrieve(MemoryLayer.WORKING, scopeId, key, 1001L);
        assertEquals(value, retrieved, "WORKING层应能从内存读取");
    }

    @Test
    void sessionLayer_storedInRedis() {
        String scopeId = TEST_PREFIX + "_sess";
        String key = "session_data";
        String value = "会话级数据";

        memoryManager.store(MemoryLayer.SESSION, scopeId, key, value, 1001L, 2001L);

        // SESSION层不落MySQL
        String memoryId = "SESSION:" + scopeId + ":" + key;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory WHERE memory_id = ?", Integer.class, memoryId);
        assertEquals(0, count, "SESSION层不应持久化到MySQL");

        // Redis中应有数据
        String redisValue = redisTemplate.opsForValue().get(memoryId);
        assertNotNull(redisValue, "SESSION层应存储在Redis中");
        assertTrue(redisValue.contains(value), "Redis中应包含记忆值");

        // 可读取（Redis往返：序列化→存储→反序列化）
        String retrieved = memoryManager.retrieve(MemoryLayer.SESSION, scopeId, key, 1001L);
        assertEquals(value, retrieved, "应能从Redis读取SESSION记忆");
    }

    @Test
    void userLayer_cacheMiss_fallsBackToMysql() {
        String scopeId = TEST_PREFIX + "_cache";
        String key = "cold";
        String value = "冷数据回源测试";

        memoryManager.store(MemoryLayer.USER, scopeId, key, value, 1001L, 2001L);

        // 手动删除Redis缓存，模拟缓存失效
        String memoryId = "USER:" + scopeId + ":" + key;
        redisTemplate.delete(memoryId);

        // 读取应从MySQL回源
        String retrieved = memoryManager.retrieve(MemoryLayer.USER, scopeId, key, 1001L);
        assertEquals(value, retrieved, "缓存失效后应从MySQL回源");

        // 回源后应回写Redis缓存
        String redisValue = redisTemplate.opsForValue().get(memoryId);
        assertNotNull(redisValue, "回源后应回写Redis缓存");
    }

    @Test
    void delete_removesFromBothMysqlAndRedis() {
        String scopeId = TEST_PREFIX + "_del";
        String key = "to_delete";
        String value = "将被删除";

        memoryManager.store(MemoryLayer.USER, scopeId, key, value, 1001L, 2001L);
        String memoryId = "USER:" + scopeId + ":" + key;

        // 确认存在
        assertNotNull(memoryManager.retrieve(MemoryLayer.USER, scopeId, key, 1001L));

        memoryManager.delete(MemoryLayer.USER, scopeId, key, 1001L);

        // MySQL和Redis都应删除
        Integer mysqlCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory WHERE memory_id = ?", Integer.class, memoryId);
        assertEquals(0, mysqlCount, "删除后MySQL应无记录");
        assertNull(redisTemplate.opsForValue().get(memoryId), "删除后Redis应无缓存");
        assertNull(memoryManager.retrieve(MemoryLayer.USER, scopeId, key, 1001L),
                "删除后应读取不到");
    }
}
