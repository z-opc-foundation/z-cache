package com.zifang.z.cache.client.pool;

import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.client.ZCacheClientConfig;
import com.zifang.z.cache.client.ZCacheClientException;
import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.protocol.RespDecoder;
import com.zifang.z.cache.core.protocol.RespEncoder;
import com.zifang.z.cache.core.server.RedisServerHandler;
import com.zifang.z.cache.core.storage.MemoryStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZCachePool 测试类
 * <p>
 * 单元测试使用 EmbeddedChannel / 本地嵌入式 z-cache 服务器(RedisServer)
 * 进行集成验证。@BeforeAll 启动一个嵌入式 RESP 服务器,@AfterAll 关闭它。
 */
class ZCachePoolTest {

    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private static Channel serverChannel;
    private static int testPort;

    private ZCacheClientConfig config;
    private ZCachePool pool;

    @BeforeAll
    static void startEmbeddedServer() throws InterruptedException {
        // Find a free port
        try (ServerSocket s = new ServerSocket(0)) {
            testPort = s.getLocalPort();
        } catch (Exception e) {
            testPort = 16379; // fallback
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        final MemoryStore store = new MemoryStore();
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new RespDecoder());
                        ch.pipeline().addLast(new RespEncoder());
                        CommandHandler commandHandler = new CommandHandler(store);
                        ch.pipeline().addLast(new RedisServerHandler(commandHandler, null, null, null));
                    }
                });
        ChannelFuture f = b.bind(testPort).sync();
        serverChannel = f.channel();
    }

    @AfterAll
    static void stopEmbeddedServer() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    @BeforeEach
    void setUp() {
        config = new ZCacheClientConfig("localhost", testPort)
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
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
        assertEquals(8, pool.getMaxSize()); // default maxSize = 8
        assertFalse(pool.isClosed());
    }

    @Test
    void testConstructorWithNullConfig() {
        assertThrows(NullPointerException.class, () -> new ZCachePool(null));
    }

    @Test
    void testConstructorWithMaxSize() {
        pool = new ZCachePool(config, 16);
        assertNotNull(pool);
        assertEquals(16, pool.getMaxSize());
    }

    @Test
    void testConstructorWithMaxSizeAndWait() {
        pool = new ZCachePool(config, 5, 5000);
        assertNotNull(pool);
        assertEquals(5, pool.getMaxSize());
    }

    @Test
    void testConstructorWithInvalidMaxSize() {
        assertThrows(IllegalArgumentException.class, () -> new ZCachePool(config, 0));
        assertThrows(IllegalArgumentException.class, () -> new ZCachePool(config, -1));
    }

    @Test
    void testConstructorWithNegativeWait() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZCachePool(config, 5, -1));
    }

    @Test
    void testBorrowAndReturnClient() {
        pool = new ZCachePool(config, 5);

        PooledClient client = pool.borrowClient();
        assertNotNull(client);
        assertNotNull(client.getClient());
        assertTrue(client.isInUse());

        client.close();

        // activeCount should drop back to 0 after return
        assertEquals(0, pool.getActiveCount());
    }

    @Test
    void testReturnNullClient() {
        pool = new ZCachePool(config);
        // Returning null is a no-op and must not throw
        assertDoesNotThrow(() -> pool.returnClient(null));
        assertDoesNotThrow(() -> pool.returnClientPublic(null));
    }

    @Test
    void testPoolMaxSize() {
        pool = new ZCachePool(config, 3, 1000);

        // Borrow up to max size
        PooledClient c1 = pool.borrowClient();
        PooledClient c2 = pool.borrowClient();
        PooledClient c3 = pool.borrowClient();

        assertNotNull(c1);
        assertNotNull(c2);
        assertNotNull(c3);
        assertEquals(3, pool.getActiveCount());

        // Return all
        c1.close();
        c2.close();
        c3.close();

        assertEquals(0, pool.getActiveCount());
    }

    @Test
    void testPoolReusesClients() {
        pool = new ZCachePool(config, 5);

        PooledClient c1 = pool.borrowClient();
        ZCacheClient underlying1 = c1.getClient();
        c1.close();

        PooledClient c2 = pool.borrowClient();
        // Reusing the underlying connection should reuse the same client
        assertSame(underlying1, c2.getClient(),
                "Pooled client should reuse the underlying connection when available");
        c2.close();
    }

    @Test
    void testPoolClose() {
        pool = new ZCachePool(config, 5);

        PooledClient c1 = pool.borrowClient();
        pool.returnClient(c1);

        pool.close();
        assertTrue(pool.isClosed());

        // Borrowing after close should throw
        assertThrows(ZCacheClientException.class, () -> pool.borrowClient());
    }

    @Test
    void testDoubleClose() {
        pool = new ZCachePool(config);
        pool.close();

        // Second close should be a no-op
        assertDoesNotThrow(() -> pool.close());
    }

    @Test
    void testGetActiveCount() {
        pool = new ZCachePool(config, 5);

        // Initially no active connections
        assertEquals(0, pool.getActiveCount());

        PooledClient c1 = pool.borrowClient();
        assertEquals(1, pool.getActiveCount());

        PooledClient c2 = pool.borrowClient();
        assertEquals(2, pool.getActiveCount());

        c1.close();
        assertEquals(1, pool.getActiveCount());

        c2.close();
        assertEquals(0, pool.getActiveCount());
    }

    @Test
    void testGetAvailableCount() {
        pool = new ZCachePool(config, 5);

        // Initially no available
        assertEquals(0, pool.getAvailableCount());

        PooledClient c1 = pool.borrowClient();
        assertEquals(0, pool.getAvailableCount());

        c1.close();
        assertEquals(1, pool.getAvailableCount());
    }

    @Test
    void testGetPoolSize() {
        pool = new ZCachePool(config, 5);

        // Initially zero
        assertEquals(0, pool.getPoolSize());

        PooledClient c1 = pool.borrowClient();
        // activeCount = 1, available = 0
        assertEquals(1, pool.getPoolSize());

        c1.close();
        // activeCount = 0, available = 1
        assertEquals(1, pool.getPoolSize());

        PooledClient c2 = pool.borrowClient();
        // activeCount = 1, available = 0
        assertEquals(1, pool.getPoolSize());

        c2.close();
        // activeCount = 0, available = 1
        assertEquals(1, pool.getPoolSize());
    }

    @Test
    void testPoolStatistics() {
        pool = new ZCachePool(config, 5);

        PooledClient c1 = pool.borrowClient();
        PooledClient c2 = pool.borrowClient();

        assertEquals(2, pool.getActiveCount());
        assertEquals(0, pool.getAvailableCount());
        assertEquals(2, pool.getPoolSize());

        c1.close();
        c2.close();

        assertEquals(0, pool.getActiveCount());
        assertEquals(2, pool.getAvailableCount());
        assertEquals(2, pool.getPoolSize());
    }

    @Test
    void testConcurrentBorrowReturn() throws InterruptedException {
        // Use a generous maxSize so concurrent borrow never has to block
        // waiting for an available client. We still want to exercise the
        // borrow/return cycle under contention.
        int threadCount = 8;
        int operationsPerThread = 20;
        pool = new ZCachePool(config, threadCount, 10000);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        PooledClient client = pool.borrowClient();
                        assertNotNull(client);

                        // Simulate small workload without long sleep
                        Thread.yield();

                        client.close();
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        startGate.countDown();
        assertTrue(latch.await(60, java.util.concurrent.TimeUnit.SECONDS),
                "Threads should complete in time");
        assertNull(error.get(), "No errors should occur: " + error.get());
        assertEquals(threadCount * operationsPerThread, successCount.get());
    }

    @Test
    void testBorrowAfterClose() {
        pool = new ZCachePool(config, 5);
        pool.close();

        // Borrowing after close should throw
        assertThrows(ZCacheClientException.class, () -> pool.borrowClient());
    }

    @Test
    void testReturnAfterClose() {
        pool = new ZCachePool(config, 5);

        PooledClient client = pool.borrowClient();
        assertNotNull(client);

        pool.close();

        // Returning after close must close the client and not throw
        assertDoesNotThrow(() -> pool.returnClientPublic(client));
    }

    @Test
    void testCloseWithAvailableClients() {
        // Two separate clients borrowed simultaneously so both end up
        // in the available queue after a single round of close().
        pool = new ZCachePool(config, 5);

        // Keep both borrowed before returning so the pool must create
        // two underlying connections (no reuse on the first round).
        PooledClient c1 = pool.borrowClient();
        PooledClient c2 = pool.borrowClient();
        assertEquals(2, pool.getActiveCount());
        assertEquals(0, pool.getAvailableCount());

        c1.close();
        c2.close();

        assertEquals(0, pool.getActiveCount());
        assertEquals(2, pool.getAvailableCount());

        // Close should drain the available clients without errors
        assertDoesNotThrow(() -> pool.close());
        assertTrue(pool.isClosed());
    }

    @Test
    void testReturnClientNotInUse() {
        pool = new ZCachePool(config, 5);

        // Manually create a PooledClient and return it to the pool
        ZCacheClient client = new ZCacheClient(config);
        PooledClient pooledClient = new PooledClient(client, pool);

        // Returning a client that hasn't been borrowed should not throw
        assertDoesNotThrow(() -> pool.returnClientPublic(pooledClient));
    }

    @Test
    void testBorrowUnderMaxSize() {
        // Test that borrow does not block when count <= maxSize
        pool = new ZCachePool(config, 5);

        long start = System.currentTimeMillis();
        PooledClient c = pool.borrowClient();
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(c);
        // Borrow should be near-instant when no contention
        assertTrue(elapsed < 1000, "Borrow should be fast when within max size");

        c.close();
    }
}