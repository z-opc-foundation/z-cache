package com.zifang.z.cache.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ZCacheClientConfigTest {

    @Test
    void testDefaultConstructor() {
        ZCacheClientConfig config = new ZCacheClientConfig();
        assertNotNull(config);
    }

    @Test
    void testConstructorWithHostAndPort() {
        ZCacheClientConfig config = new ZCacheClientConfig("localhost", 6379);
        assertEquals("localhost", config.getHost());
        assertEquals(6379, config.getPort());
    }

    @Test
    void testHostSetterGetter() {
        ZCacheClientConfig config = new ZCacheClientConfig();
        config.setHost("192.168.1.1");
        assertEquals("192.168.1.1", config.getHost());
    }

    @Test
    void testPortSetterGetter() {
        ZCacheClientConfig config = new ZCacheClientConfig();
        config.setPort(8080);
        assertEquals(8080, config.getPort());
    }

    @Test
    void testConnectTimeoutSetterGetter() {
        ZCacheClientConfig config = new ZCacheClientConfig();
        Duration timeout = Duration.ofSeconds(10);
        config.setConnectTimeout(timeout);
        assertEquals(timeout, config.getConnectTimeout());
    }

    @Test
    void testReadTimeoutSetterGetter() {
        ZCacheClientConfig config = new ZCacheClientConfig();
        Duration timeout = Duration.ofSeconds(15);
        config.setReadTimeout(timeout);
        assertEquals(timeout, config.getReadTimeout());
    }

    @Test
    void testFluentApi() {
        ZCacheClientConfig config = new ZCacheClientConfig()
                .withHost("test-server")
                .withPort(9090)
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(8));

        assertEquals("test-server", config.getHost());
        assertEquals(9090, config.getPort());
        assertEquals(Duration.ofSeconds(3), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(8), config.getReadTimeout());
    }

    @Test
    void testNullHost() {
        ZCacheClientConfig config = new ZCacheClientConfig();
        config.setHost(null);
        assertNull(config.getHost());
    }

    @Test
    void testNegativePort() {
        ZCacheClientConfig config = new ZCacheClientConfig();
        config.setPort(-1);
        assertEquals(-1, config.getPort());
    }
}
