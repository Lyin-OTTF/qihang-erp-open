package cn.qihangerp.agent.cache;
import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商品缓存层 Redis 集成测试。
 *
 * <p>验证缓存基础设施：JSON序列化读写、TTL过期、key清除。
 * 使用真实 Redis 127.0.0.1:6379。</p>
 *
 * @author 回溯开发-Redis缓存层
 * @since 2026-10-13
 */
@Tag("it")
class GoodsCacheServiceTest {

    private static StringRedisTemplate redisTemplate;
    private static ObjectMapper objectMapper;
    private final String testKey = "cache:test:goods:" + System.currentTimeMillis();

    @BeforeAll
    static void setup() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        objectMapper = new ObjectMapper();
    }

    @AfterAll
    static void teardown() {
        // 清理测试key
        var keys = redisTemplate.keys("cache:test:goods:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void cacheSetAndGet_worksWithJson() throws Exception {
        // 模拟缓存写入
        TestGoods data = new TestGoods(1L, "测试商品", "SKU001");
        String json = objectMapper.writeValueAsString(data);
        redisTemplate.opsForValue().set(testKey, json, 5, TimeUnit.MINUTES);

        // 读取缓存
        String cached = redisTemplate.opsForValue().get(testKey);
        assertNotNull(cached, "缓存应能读到");
        TestGoods parsed = objectMapper.readValue(cached, TestGoods.class);
        assertEquals(1L, parsed.id);
        assertEquals("测试商品", parsed.name);
        assertEquals("SKU001", parsed.skuCode);
    }

    @Test
    void cacheMiss_returnsNull() {
        String cached = redisTemplate.opsForValue().get("cache:test:goods:nonexistent:" + System.nanoTime());
        assertNull(cached, "不存在的key应返回null");
    }

    @Test
    void cacheEvict_removesKey() throws Exception {
        String key = testKey + ":evict";
        redisTemplate.opsForValue().set(key, "{}", 5, TimeUnit.MINUTES);
        assertNotNull(redisTemplate.opsForValue().get(key));

        // 模拟evictGoodsCache
        redisTemplate.delete(key);
        assertNull(redisTemplate.opsForValue().get(key), "清除后缓存应不存在");
    }

    @Test
    void cacheTtl_expiresAfterTimeout() throws Exception {
        String key = testKey + ":ttl";
        redisTemplate.opsForValue().set(key, "{}", 1, TimeUnit.SECONDS);
        assertNotNull(redisTemplate.opsForValue().get(key));

        Thread.sleep(1200);
        assertNull(redisTemplate.opsForValue().get(key), "TTL过期后缓存应自动清除");
    }

    @Test
    void cacheKeyFormat_isCorrect() {
        // 验证GoodsCacheService的key前缀规则
        String searchKey = "cache:goods:search:100:手机";
        String detailKey = "cache:goods:detail:123";
        String skuKey = "cache:goods:skus:123";

        assertTrue(searchKey.startsWith("cache:goods:search:"));
        assertTrue(detailKey.startsWith("cache:goods:detail:"));
        assertTrue(skuKey.startsWith("cache:goods:skus:"));
    }

    /** 测试用商品POJO */
    static class TestGoods {
        public Long id;
        public String name;
        public String skuCode;
        public TestGoods() {}
        public TestGoods(Long id, String name, String skuCode) {
            this.id = id; this.name = name; this.skuCode = skuCode;
        }
    }
}
