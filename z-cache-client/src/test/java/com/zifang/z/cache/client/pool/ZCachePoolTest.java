package com.zifang.z.cache.client.pool;

import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.client.ZCacheClientConfig;
import com.zifang.z.cache.client.ZCacheClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZCachePool 测试类
 */
class ZCachePoolTest {

    private ZCacheClientConfig config;
    private ZCachePool pool;

    @BeforeEach
    void setUp() {
        config = new ZCacheClientConfig("localhost", 6379)
                .withConnectTimeout(Duration.ofMillis(100))
                .withReadTimeout(Duration.ofMillis(100));
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void testConstructorWithConfig() {
        pool = new ZCachePool(config);
        assertNotNull(pool);
    }

    @Test
    void testConstructorWithNullConfig() {
        assertThrows(NullPointerException.class, () -> new ZCachePool(null));
    }

    @Test
    void testBorrowAndReturnClient() throws Exception {
        pool = new ZCachePool(config, 5);

        PooledClient client = pool.borrowClient();
        assertNotNull(client);

        pool.returnClient(client);
    }

    @Test
    void testReturnNullClient() {
        pool = new ZCachePool(config);
        // 返回 null 不应该抛出异常
        assertDoesNotThrow(() -> pool.returnClient(null));
    }

    @Test
    void testPoolMaxSize() throws Exception {
        pool = new ZCachePool(config, 3, 30000);

        // 借用多个客户端
        PooledClient client1 = pool.borrowClient();
        PooledClient client2 = pool.borrowClient();
        PooledClient client3 = pool.borrowClient();

        assertNotNull(client1);
        assertNotNull(client2);
        assertNotNull(client3);

        // 归还客户端
        pool.returnClientPublic(client1);
        pool.returnClientPublic(client2);
        pool.returnClientPublic(client3);
    }

    @Test
    void testPoolClose() throws Exception {
        pool = new ZCachePool(config, 5);

        // 借用一些客户端
        PooledClient client1 = pool.borrowClient();
        PooledClient client2 = pool.borrowClient();

        // 归还一个
        pool.returnClient(client1);

        // 关闭连接池
        pool.close();

        // 关闭后不能再借用客户端
        assertThrows(ZCacheClientException.class, () -> pool.borrowClient());
    }

    @Test
    void testDoubleClose() {
        pool = new ZCachePool(config);
        pool.close();

        // 第二次关闭不应该抛出异常
        assertDoesNotThrow(() -> pool.close());
    }

    @Test
    void testGetActiveCount() throws Exception {
        pool = new ZCachePool(config, 5);

        // 初始时没有活跃的连接
        assertEquals(0, pool.getActiveCount());

        // 借用客户端
        PooledClient client = pool.borrowClient();
        assertEquals(1, pool.getActiveCount());

        // 归还后活跃数量应该减少
        pool.returnClientPublic(client);
        assertEquals(0, pool.getActiveCount());
    }

    @Test
    void testPoolStatistics() throws Exception {
        pool = new ZCachePool(config, 5);

        // 借用多个客户端并收集统计信息
        PooledClient c1 = pool.borrowClient();
        PooledClient c2 = pool.borrowClient();

        assertEquals(2, pool.getActiveCount());

        pool.returnClientPublic(c1);
        pool.returnClientPublic(c2);

        // 全部归还后活跃数量应为 0
        assertEquals(0, pool.getActiveCount());
    }

    @Test
    void testConcurrentBorrowReturn() throws Exception {
        config = new ZCacheClientConfig("localhost", 6379)
                .withConnectTimeout(Duration.ofMillis(100))
                .withReadTimeout(Duration.ofMillis(100));
        pool = new ZCachePool(config, 10, 30000);

        int threadCount = 10;
        int operationsPerThread = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        PooledClient client = pool.borrowClient();
                        assertNotNull(client);

                        // 模拟一些操作
                        Thread.sleep(1);

                        pool.returnClientPublic(client);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Threads should complete in time");
        assertNull(error.get(), "No errors should occur");
        assertEquals(threadCount * operationsPerThread, successCount.get());
    }

    @Test
    void testReturnClientNotInUse() {
        pool = new ZCachePool(config, 5);

        // 创建一个模拟的 PooledClient
        ZCacheClient client = new ZCacheClient(config);
        PooledClient pooledClient = new PooledClient(client, pool);

        // 返回一个不在使用中的客户端
        assertDoesNotThrow(() -> pool.returnClientPublic(pooledClient));
    }

    @Test
    void testBorrowAfterClose() {
        pool = new ZCachePool(config, 5);
        pool.close();

        // 关闭后借用客户端应该抛出异常
        assertThrows(ZCacheClientException.class, () -> pool.borrowClient());
    }

    @Test
    void testReturnAfterClose() throws Exception {
        pool = new ZCachePool(config, 5);

        PooledClient client = pool.borrowClient();
        pool.close();

        // 关闭后返回客户端不应该抛出异常（会关闭客户端）
        assertDoesNotThrow(() -> pool.returnClientPublic(client));
    }
}
