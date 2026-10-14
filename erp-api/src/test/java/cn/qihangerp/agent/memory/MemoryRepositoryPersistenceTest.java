package cn.qihangerp.agent.memory;
import org.junit.jupiter.api.Tag;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryRepository 真实 DB 集成测试。
 *
 * <p>验证 MySQL 持久化层：upsert（插入+更新）、findById、deleteById、deleteExpired。
 * 使用 JdbcTemplate 直连真实 qihang-erp 库的 agent_memory 表。</p>
 *
 * @author 回溯开发-阶段3
 * @since 2026-10-12
 */
@Tag("it")
class MemoryRepositoryPersistenceTest {

    private static final String TEST_PREFIX = "repo-test-" + System.currentTimeMillis();
    private static MemoryRepository repository;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setup() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/qihang-erp"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("1234");
        jdbcTemplate = new JdbcTemplate((DataSource) ds);
        repository = new MemoryRepository(jdbcTemplate);
    }

    @AfterAll
    static void cleanup() {
        jdbcTemplate.update("DELETE FROM agent_memory WHERE memory_id LIKE ?", TEST_PREFIX + "%");
    }

    private MemoryEntry buildEntry(String key, String value, Long merchantId, Long userId) {
        MemoryEntry e = new MemoryEntry();
        e.setMemoryId(TEST_PREFIX + ":" + key);
        e.setLayer(MemoryLayer.USER);
        e.setScopeType("user");
        e.setScopeId("user_" + userId);
        e.setKey(key);
        e.setValue(value);
        e.setValueType("STRING");
        e.setTtlSeconds(2592000);
        e.setExpiresAt(LocalDateTime.now().plusSeconds(2592000));
        e.setPriority(0);
        e.setAccessCount(0);
        e.setMerchantId(merchantId);
        e.setUserId(userId);
        return e;
    }

    @Test
    void save_insertsNewEntry() {
        MemoryEntry e = buildEntry("user_name", "张三", 1001L, 2001L);
        repository.save(e);

        MemoryEntry found = repository.findById(e.getMemoryId());
        assertNotNull(found, "插入后应能查到");
        assertEquals("张三", found.getValue());
        assertEquals("user_name", found.getKey());
        assertEquals(MemoryLayer.USER, found.getLayer());
        assertEquals(1001L, found.getMerchantId());
        assertEquals(2001L, found.getUserId());
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    @Test
    void save_updatesExistingEntry() {
        String memoryId = TEST_PREFIX + ":update_test";
        MemoryEntry e1 = buildEntry("update_test", "旧值", 1001L, 2001L);
        e1.setMemoryId(memoryId);
        repository.save(e1);

        // 更新为新值
        MemoryEntry e2 = buildEntry("update_test", "新值", 1001L, 2001L);
        e2.setMemoryId(memoryId);
        repository.save(e2);

        MemoryEntry found = repository.findById(memoryId);
        assertNotNull(found);
        assertEquals("新值", found.getValue(), "upsert 应更新为新值");
    }

    @Test
    void findById_returnsNullForNonExistent() {
        MemoryEntry found = repository.findById(TEST_PREFIX + ":nonexistent");
        assertNull(found, "不存在的ID应返回null");
    }

    @Test
    void deleteById_removesEntry() {
        MemoryEntry e = buildEntry("to_delete", "将被删除", 1001L, 2001L);
        repository.save(e);
        assertNotNull(repository.findById(e.getMemoryId()));

        repository.deleteById(e.getMemoryId());
        assertNull(repository.findById(e.getMemoryId()), "删除后应查不到");
    }

    @Test
    void deleteExpired_removesOnlyExpired() {
        // 插入一条已过期的记忆
        MemoryEntry expired = buildEntry("expired_key", "已过期", 1001L, 2001L);
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        expired.setTtlSeconds(0);
        repository.save(expired);

        // 插入一条未过期的记忆
        MemoryEntry valid = buildEntry("valid_key", "未过期", 1001L, 2001L);
        valid.setExpiresAt(LocalDateTime.now().plusDays(30));
        repository.save(valid);

        int deleted = repository.deleteExpired();
        assertTrue(deleted >= 1, "应至少删除1条过期记忆");

        assertNull(repository.findById(expired.getMemoryId()), "过期记忆应被清理");
        assertNotNull(repository.findById(valid.getMemoryId()), "未过期记忆应保留");
    }

    @Test
    void save_preservesTtlAndExpiry() {
        MemoryEntry e = buildEntry("ttl_test", "TTL测试", 1001L, 2001L);
        e.setTtlSeconds(3600);
        e.setExpiresAt(LocalDateTime.now().plusHours(1));
        repository.save(e);

        MemoryEntry found = repository.findById(e.getMemoryId());
        assertNotNull(found);
        assertEquals(3600, found.getTtlSeconds());
        assertNotNull(found.getExpiresAt());
        // 过期时间应在未来
        assertTrue(found.getExpiresAt().isAfter(LocalDateTime.now().minusMinutes(1)),
                "expiresAt 应在未来附近");
    }
}
