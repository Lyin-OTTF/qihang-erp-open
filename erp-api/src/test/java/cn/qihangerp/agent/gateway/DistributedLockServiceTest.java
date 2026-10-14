package cn.qihangerp.agent.gateway;
import org.junit.jupiter.api.Tag;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redisson 分布式锁服务集成测试。
 *
 * <p>使用真实 Redis 127.0.0.1:6379 验证：加锁互斥、释放锁、可重入、
 * 带等待超时、并发竞争、TTL过期等核心语义。</p>
 *
 * @author 回溯开发-Redisson接入
 * @since 2026-10-13
 */
@Tag("it")
class DistributedLockServiceTest {

    private static RedissonClient redissonClient;
    private static DistributedLockService lockService;
    private final String testKey = "test-lock-" + UUID.randomUUID();

    @BeforeAll
    static void setup() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setDatabase(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4);
        redissonClient = Redisson.create(config);
        lockService = new DistributedLockService(redissonClient);
    }

    @AfterAll
    static void teardown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @Test
    void tryLock_succeeds_whenFree() {
        DistributedLockService.LockHandle handle = lockService.tryLock(testKey, 10);
        assertTrue(handle.isAcquired(), "空闲锁应获取成功");
        assertTrue(handle.getKey().endsWith(testKey));
        lockService.unlock(handle);
    }

    @Test
    void tryLock_fails_whenHeldByOtherThread() throws InterruptedException {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 30);
        assertTrue(h1.isAcquired());

        // 另一个线程尝试获取（Redisson可重入仅对同一线程，跨线程应失败）
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger otherAcquired = new AtomicInteger(0);
        Thread other = new Thread(() -> {
            DistributedLockService.LockHandle h2 = lockService.tryLock(testKey, 10);
            if (h2.isAcquired()) {
                otherAcquired.incrementAndGet();
                lockService.unlock(h2);
            }
            latch.countDown();
        });
        other.start();
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(0, otherAcquired.get(), "被其他线程持有的锁应获取失败");
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
    void unlock_notHeldByCurrentThread_doesNotRelease() throws InterruptedException {
        // 主线程获取锁
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 10);
        assertTrue(h1.isAcquired());

        // 另一个线程尝试释放（不应成功，因为不是它持有的）
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger otherCanAcquire = new AtomicInteger(0);
        Thread other = new Thread(() -> {
            // 构造一个acquired=true但rlock为null的handle，unlock应安全跳过
            DistributedLockService.LockHandle fake =
                    new DistributedLockService.LockHandle("agent:lock:" + testKey, "", true);
            lockService.unlock(fake);
            // 尝试获取，应仍失败（锁还被主线程持有）
            DistributedLockService.LockHandle h2 = lockService.tryLock(testKey, 10);
            if (!h2.isAcquired()) {
                otherCanAcquire.incrementAndGet();
            } else {
                lockService.unlock(h2);
            }
            latch.countDown();
        });
        other.start();
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, otherCanAcquire.get(), "非持有线程不应释放锁");
        lockService.unlock(h1);
    }

    @Test
    void tryLockWithWait_succeeds_afterRelease() throws InterruptedException {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 30);
        assertTrue(h1.isAcquired());

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

        Thread.sleep(100);
        lockService.unlock(h1);

        latch.await(5, TimeUnit.SECONDS);
        assertEquals(1, acquired.get(), "等待后应能获取到锁");
    }

    @Test
    void tryLockWithWait_timesOut_whenNeverReleased() throws InterruptedException {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 30);
        assertTrue(h1.isAcquired());

        // 另一个线程等待超时（同一线程可重入，必须跨线程才能验证超时）
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger otherAcquired = new AtomicInteger(0);
        long[] elapsed = new long[1];
        Thread other = new Thread(() -> {
            long start = System.currentTimeMillis();
            DistributedLockService.LockHandle h2 = lockService.tryLockWithWait(testKey, 10, 500);
            elapsed[0] = System.currentTimeMillis() - start;
            if (h2.isAcquired()) {
                otherAcquired.incrementAndGet();
                lockService.unlock(h2);
            }
            latch.countDown();
        });
        other.start();
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(0, otherAcquired.get(), "超时应获取失败");
        assertTrue(elapsed[0] >= 400, "应等待接近500ms, actual=" + elapsed[0]);

        lockService.unlock(h1);
    }

    @Test
    void lockExpires_afterTtl() throws InterruptedException {
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 1);
        assertTrue(h1.isAcquired());

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

    @Test
    void reentrantLock_sameThreadCanAcquireTwice() {
        // Redisson RLock 是可重入的
        DistributedLockService.LockHandle h1 = lockService.tryLock(testKey, 10);
        assertTrue(h1.isAcquired());

        // 同一线程再次获取同一把锁应成功（可重入）
        DistributedLockService.LockHandle h2 = lockService.tryLock(testKey, 10);
        assertTrue(h2.isAcquired(), "Redisson可重入锁同一线程应能再次获取");

        // 释放两次
        lockService.unlock(h2);
        lockService.unlock(h1);
    }
}
