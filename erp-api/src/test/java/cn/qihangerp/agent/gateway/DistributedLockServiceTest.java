package cn.qihangerp.agent.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式锁服务集成测试（搁置项攻关-任务3）。
 *
 * <p>使用真实 Redis 127.0.0.1:6379 验证：加锁互斥、释放锁、token防误删、
 * 带等待重试、并发竞争等核心语义。</p>
 *
 * @author 回溯开发-搁置项攻关
 * @since 2026-10-12
 */
class DistributedLockServiceTest {

    private static StringRedisTemplate redisTemplate;
    private static DistributedLockService lockService;
    private final String testKey = "test-lock-" + UUID.randomUUID();

    @BeforeAll
    static void setup() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        lockService = new DistributedLockService(redisTemplate);
    }

    @AfterEach
    void cleanup() {
        redisTemplate.delete("agent:lock:" + testKey);
    }

    @Test
    void tryLock_succeeds_whenFree() {
        DistributedLockService.LockHandle handle = lockService.tryLock(testKey, 10);
        assertTrue(handle.isAcquired(), "空闲锁应获取成功");
        assertNotNull(handle.getToken(), "token不应为空");
        assertTrue(handle.getKey().endsWith(testKey));
        lockService.unlock(handle);
    }

    @Test
    void tryLock_fails_whenHeld() {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 10);
        assertTrue(h1.isAcquired());

        DistributedLockService.LockHandle h2 = lockService.tryLock(testKey, 10);
        assertFalse(h2.isAcquired(), "被持有的锁应获取失败");

        lockService.unlock(h1);
    }

    @Test
    void unlock_releasesLock_forReacquisition() {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 10);
        assertTrue(h1.isAcquired());
        lockService.unlock(h1);

        DistributedLockService.LockHandle h2 = lockService.tryLock(testKey, 10);
        assertTrue(h2.isAcquired(), "释放后应能重新获取");
        lockService.unlock(h2);
    }

    @Test
    void unlock_withWrongToken_doesNotDelete() {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 10);
        assertTrue(h1.isAcquired());

        // 构造一个token不匹配的handle，模拟误删
        DistributedLockService.LockHandle fakeHandle =
                new DistributedLockService.LockHandle("agent:lock:" + testKey, "wrong-token", true);
        lockService.unlock(fakeHandle);

        // 锁仍然被h1持有
        DistributedLockService.LockHandle h2 = lockService.tryLock(testKey, 10);
        assertFalse(h2.isAcquired(), "token不匹配时不应释放锁");

        lockService.unlock(h1);
    }

    @Test
    void tryLockWithWait_succeeds_afterRelease() throws InterruptedException {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 10);
        assertTrue(h1.isAcquired());

        // 另一个线程等待获取锁
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger(0);
        Thread waiter = new Thread(() -> {
            DistributedLockService.LockHandle h2 = lockService.tryLockWithWait(testKey, 10, 3000);
            if (h2.isAcquired()) {
                acquired.incrementAndGet();
                lockService.unlock(h2);
            }
            latch.countDown();
        });
        waiter.start();

        // 100ms后释放锁
        Thread.sleep(100);
        lockService.unlock(h1);

        latch.await();
        assertEquals(1, acquired.get(), "等待后应能获取到锁");
    }

    @Test
    void tryLockWithWait_timesOut_whenNeverReleased() {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 10);
        assertTrue(h1.isAcquired());

        long start = System.currentTimeMillis();
        DistributedLockService.LockHandle h2 = lockService.tryLockWithWait(testKey, 10, 500);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(h2.isAcquired(), "超时应获取失败");
        assertTrue(elapsed >= 400, "应等待接近500ms");

        lockService.unlock(h1);
    }

    @Test
    void lockExpires_afterTtl() throws InterruptedException {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 1);
        assertTrue(h1.isAcquired());

        // 等待TTL过期
        Thread.sleep(1200);

        DistributedLockService.LockHandle h2 = lockService.tryLock(testKey, 10);
        assertTrue(h2.isAcquired(), "TTL过期后应能获取锁");
        lockService.unlock(h2);
    }

    @Test
    void unlock_nullHandle_isSafe() {
        assertDoesNotThrow(() -> lockService.unlock(null));
    }

    @Test
    void unlock_notAcquiredHandle_isSafe() {
        DistributedLockService.LockHandle notAcquired =
                new DistributedLockService.LockHandle("agent:lock:" + testKey, "token", false);
        assertDoesNotThrow(() -> lockService.unlock(notAcquired));
    }
}
