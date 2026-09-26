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

    /** 探测用的端口窗口：只在操作系统**出站**区间之下取，见 {@link #freePort()}。 */
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final java.util.concurrent.atomic.AtomicInteger PORT_CURSOR =
            new java.util.concurrent.atomic.AtomicInteger(new java.util.Random().nextInt(PORT_SPAN));

    /**
     * 挑一枚测试用的端口：在一个固定的低位窗口里顺着一个游标找一枚真能 bind 上的号码。
     * <p>
     * 不能再写 {@code new ServerSocket(0)} —— 那样"探到再放开"的号码出自操作系统的**出站**
     * 派发区间（macOS 49152-65535、Linux 默认 32768-60999），探针一关那个号码立刻重新可派，
     * 而本机常驻代理一直在起出站连接：实测 9 遍全量里撞红 2 遍（64088 与 51981，红的用例
     * 还各不相同），{@code lsof -nP -iTCP:64088} 当场抓到它挂在一条 {@code FIN_WAIT_2} 的
     * 出站连接上 —— 也就是"这个号码在探完之后被派给了别人"，不是本仓哪个测试没关服务器。
     * 窗口之下的号码操作系统不派给出站连接，"探得到"和"bind 得上"之间就没有那道窗口了；
     * 窗口里撞上别人的常驻服务照常换下一枚。下面 {@code awaitListening} 里那句"临时端口在
     * 放开后可能被别的进程占走"防的是同一段窗口的另一头，两半都要在。
     */
    private static int freePort() throws IOException {
        for (int tries = 0; tries < PORT_SPAN; tries++) {
            int port = PORT_BASE + PORT_CURSOR.getAndIncrement() % PORT_SPAN;
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress("127.0.0.1", port));
            } catch (IOException taken) {
                continue;
            }
            return port;
        }
        throw new IOException("窗口 " + PORT_BASE + "-" + (PORT_BASE + PORT_SPAN - 1)
                + " 里找不出一枚可 bind 的端口");
    }

    private static void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            // 就绪的判据不能只是"连得上"：临时端口在放开后可能被别的进程占走（本机常有
            // 别的战役在跑 surefire），那时裸 connect 照样立刻成功，客户端读到的是别人家的
            // 响应。要对方答一句话，且答的必须是 RESP 形状。
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                probe.setSoTimeout(300);
                probe.getOutputStream().write(
                        "*1\r\n$4\r\nPING\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                probe.getOutputStream().flush();
                int first = probe.getInputStream().read();
                if (first == '+' || first == '-' || first == ':' || first == '$' || first == '*') {
                    return;
                }
                throw new IllegalStateException("port " + port + " 上答话的不是 RESP，首字节 "
                        + (first < 0 ? "是流已关闭" : "'" + (char) first + "'(" + first + ")")
                        + " —— 端口大概率在 freePort() 放开后被别的进程占走了");
            } catch (java.net.SocketTimeoutException stillWarmingUp) {
                Thread.sleep(50);
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
