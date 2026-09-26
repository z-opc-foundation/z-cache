package com.zifang.z.cache.client;

import com.zifang.z.cache.core.server.RedisServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZCacheClient 的公开 API 打到真实服务器上的往返。
 * <p>
 * 以前这个类是"探测 6379 上有没有人，没有就整类跳过"：本仓库里没有任何东西会在 6379 上
 * 起服务，于是 12 条用例在 surefire 报告里永远是 skipped，{@link ZCacheClient} 的
 * set/get/expire/incr/flushdb 对真实服务器的往返是零覆盖（单元测试只验对象能构造出来）。
 * 反过来说，万一本机真跑着一个 Redis，这 12 条就会拿别人当被测对象，还会把 FLUSHDB
 * 打进人家的库 —— 探测式门禁两头都不安全。
 * <p>
 * 现在自己起一台、用临时端口，跑完停掉：既保证真的往返发生过，也不会碰到别人的实例。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZCacheClientIntegrationTest {

    private static final long DEADLINE_MS = 8_000L;

    private RedisServer server;
    private Thread serverThread;
    private ZCacheClientConfig config;

    @BeforeAll
    void setUp() throws Exception {
        int port = freePort();
        server = new RedisServer("127.0.0.1", port, 0);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "client-it-server");
        serverThread.setDaemon(true);
        serverThread.start();
        awaitListening(port);

        // 用 127.0.0.1 而不是 localhost：服务器只绑 IPv4，而 localhost 在有些机器上先解析到
        // ::1，客户端就会连到一个没人听的地址。
        config = new ZCacheClientConfig("127.0.0.1", port)
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(5));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.join(DEADLINE_MS);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("embedded z-cache server never listened on " + port);
    }

    @Test
    void testClientConnection() {
        ZCacheClient client = new ZCacheClient(config);
        assertNotNull(client);

        client.connect();
        assertTrue(client.isConnected());

        client.close();
    }

    @Test
    void testPingCommand() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            String result = client.ping();
            assertEquals("PONG", result);
        } finally {
            client.close();
        }
    }

    @Test
    void testPingWithMessage() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            String result = client.ping("hello");
            assertEquals("hello", result);
        } finally {
            client.close();
        }
    }

    @Test
    void testSetGetCommands() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            client.set("testkey", "testvalue");
            String value = client.get("testkey");
            assertEquals("testvalue", value);
        } finally {
            client.close();
        }
    }

    @Test
    void testDeleteCommand() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            client.set("key1", "value1");
            client.set("key2", "value2");

            Long deleted = client.del("key1", "key2", "nonexistent");
            assertEquals(2L, deleted.longValue(), "两个真实键 + 一个不存在的键：DEL 回的是删掉的个数");

            assertNull(client.get("key1"));
        } finally {
            client.close();
        }
    }

    @Test
    void testExistsCommand() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            client.set("key1", "value1");
            client.set("key2", "value2");

            Long exists = client.exists("key1", "key2", "nonexistent");
            assertEquals(2L, exists.longValue(), "EXISTS 多键回的是存在的个数");
        } finally {
            client.close();
        }
    }

    @Test
    void testExpireCommand() throws InterruptedException {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            client.set("key1", "value1");

            Long result = client.expire("key1", 1);
            assertEquals(1L, result.longValue(), "键刚 SET 过，EXPIRE 必须落在它身上并回 1");

            Thread.sleep(1100);
            assertNull(client.get("key1"));
        } finally {
            client.close();
        }
    }

    @Test
    void testTtlCommand() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            client.set("key1", "value1");
            client.expire("key1", 60);

            Long ttl = client.ttl("key1");
            assertTrue(ttl > 0);
        } finally {
            client.close();
        }
    }

    @Test
    void testIncrDecrCommands() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            client.set("counter", "0");

            Long incrResult = client.incr("counter");
            assertEquals(1L, incrResult.longValue(), "从 0 起 INCR 一次就是 1");

            Long decrResult = client.decr("counter");
            assertEquals(0L, decrResult.longValue(), "再 DECR 一次回到 0");
        } finally {
            client.close();
        }
    }

    @Test
    void testConcurrentClients() throws InterruptedException {
        int threadCount = 5;
        int operationsPerThread = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            new Thread(() -> {
                try {
                    ZCacheClient client = new ZCacheClient(config);
                    client.connect();

                    for (int j = 0; j < operationsPerThread; j++) {
                        client.set("thread" + threadIndex + "_key" + j, "value" + j);
                        String value = client.get("thread" + threadIndex + "_key" + j);
                        if ("value".concat(String.valueOf(j)).equals(value)) {
                            successCount.incrementAndGet();
                        }
                    }

                    client.close();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        // 每条连接 20 次"写完立刻读回"都应拿到自己刚写的值，所以满值是 threadCount*operationsPerThread。
        // 判据以前是 > 0 —— 100 次里只成功 1 次也算过，而少一次就意味着应答串了线或请求被丢了。
        assertEquals(threadCount * operationsPerThread, successCount.get(),
                "每个线程都要把自己写的 20 个值原样读回来");
    }

    @Test
    void testFlushDb() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            client.set("key1", "value1");
            client.set("key2", "value2");

            String result = client.flushdb();
            assertEquals("OK", result);

            assertNull(client.get("key1"));
        } finally {
            client.close();
        }
    }

    @Test
    void testEcho() {
        ZCacheClient client = new ZCacheClient(config);

        try {
            client.connect();
            String result = client.echo("Hello World");
            assertEquals("Hello World", result);
        } finally {
            client.close();
        }
    }
}
