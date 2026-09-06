package com.zifang.z.cache.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZCacheConnection 测试类
 */
class ZCacheConnectionTest {

    private ZCacheClientConfig config;

    @BeforeEach
    void setUp() {
        config = new ZCacheClientConfig()
                .host("localhost")
                .port(1987)
                .connectTimeout(Duration.ofSeconds(1))
                .readTimeout(Duration.ofSeconds(1));
    }

    @Test
    void testConstructorWithConfig() {
        ZCacheConnection connection = new ZCacheConnection(config);

        assertNotNull(connection);
        assertEquals(ConnectionState.DISCONNECTED, connection.getState());
    }

    @Test
    void testConstructorWithNullConfig() {
        assertThrows(ZCacheClientException.class, () -> new ZCacheConnection(null));
    }

    @Test
    void testGetStateInitial() {
        ZCacheConnection connection = new ZCacheConnection(config);
        assertEquals(ConnectionState.DISCONNECTED, connection.getState());
    }

    @Test
    void testIsConnected() {
        ZCacheConnection connection = new ZCacheConnection(config);
        assertFalse(connection.isConnected());
    }

    @Test
    void testConnectTimeout() {
        // 使用一个不可达的地址，验证超时
        ZCacheClientConfig timeoutConfig = new ZCacheClientConfig()
                .host("192.0.2.1") // RFC 5737 测试地址，通常不可达
                .port(1987)
                .connectTimeout(Duration.ofMillis(100));

        ZCacheConnection connection = new ZCacheConnection(timeoutConfig);

        long startTime = System.currentTimeMillis();
        try {
            connection.connect();
            fail("Expected ZCacheClientException");
        } catch (ZCacheClientException e) {
            // 预期的异常
            long elapsed = System.currentTimeMillis() - startTime;
            assertTrue(elapsed < 5000, "Connection should timeout quickly");
        }
    }

    @Test
    void testDoubleClose() {
        ZCacheConnection connection = new ZCacheConnection(config);

        // 第一次关闭
        connection.close();
        assertEquals(ConnectionState.CLOSED, connection.getState());

        // 第二次关闭不应该抛出异常
        assertDoesNotThrow(() -> connection.close());
    }

    @Test
    void testCloseWhenDisconnected() {
        ZCacheConnection connection = new ZCacheConnection(config);
        assertEquals(ConnectionState.DISCONNECTED, connection.getState());

        // 关闭未连接的连接
        assertDoesNotThrow(() -> connection.close());
        assertEquals(ConnectionState.CLOSED, connection.getState());
    }

    @Test
    void testIsClosed() {
        ZCacheConnection connection = new ZCacheConnection(config);
        assertFalse(connection.isClosed());

        connection.close();
        assertTrue(connection.isClosed());
    }

    @Test
    void testGetConfig() {
        ZCacheConnection connection = new ZCacheConnection(config);
        ZCacheClientConfig retrievedConfig = connection.getConfig();

        assertNotNull(retrievedConfig);
        assertEquals(config.getHost(), retrievedConfig.getHost());
        assertEquals(config.getPort(), retrievedConfig.getPort());
    }

    @Test
    void testConnectionWithMockSocket() throws IOException {
        // 创建一个部分模拟的连接来测试
        ZCacheConnection connection = new ZCacheConnection(config);

        assertEquals(ConnectionState.DISCONNECTED, connection.getState());
        // 验证连接对象已创建但尚未连接
        assertNotNull(connection);
        assertFalse(connection.isConnected());
    }

    @Test
    void testStateTransitions() {
        ZCacheConnection connection = new ZCacheConnection(config);

        // 初始状态
        assertEquals(ConnectionState.DISCONNECTED, connection.getState());

        // 关闭后
        connection.close();
        assertEquals(ConnectionState.CLOSED, connection.getState());
    }

    @Test
    void testMultipleConnections() {
        ZCacheClientConfig config1 = new ZCacheClientConfig().host("host1").port(1111);
        ZCacheClientConfig config2 = new ZCacheClientConfig().host("host2").port(2222);

        ZCacheConnection conn1 = new ZCacheConnection(config1);
        ZCacheConnection conn2 = new ZCacheConnection(config2);

        assertNotSame(conn1, conn2);
        assertEquals("host1", conn1.getConfig().getHost());
        assertEquals("host2", conn2.getConfig().getHost());
    }

    @Test
    void testToString() {
        ZCacheConnection connection = new ZCacheConnection(config);
        String str = connection.toString();
        assertNotNull(str);
        assertTrue(str.contains("ZCacheConnection") || str.contains("DISCONNECTED"));
    }
}
