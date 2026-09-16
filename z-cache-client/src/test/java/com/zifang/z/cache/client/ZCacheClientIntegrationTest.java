package com.zifang.z.cache.client;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZCacheClient 集成测试类
 * 注意：这些测试可能需要启动一个真实的 z-cache 服务器
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZCacheClientIntegrationTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;

    private ZCacheClientConfig config;
    private boolean serverAvailable = false;

    @BeforeAll
    void setUp() {
        config = new ZCacheClientConfig(TEST_HOST, TEST_PORT)
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));

        serverAvailable = checkServerAvailable();
    }

    private boolean checkServerAvailable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(TEST_HOST, TEST_PORT), 1000);
            return true;
        } catch (Exception e) {
            System.out.println("Server not available: " + e.getMessage());
            return false;
        }
    }

    @BeforeEach
    void checkServer() {
        Assumptions.assumeTrue(serverAvailable, "Server is not available, skipping integration test");
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
            assertTrue(deleted >= 0);

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
            assertTrue(exists >= 0);
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
            assertTrue(result >= 0);

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
            assertTrue(incrResult >= 0);

            Long decrResult = client.decr("counter");
            assertTrue(decrResult >= 0);
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
        assertTrue(successCount.get() > 0);
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
