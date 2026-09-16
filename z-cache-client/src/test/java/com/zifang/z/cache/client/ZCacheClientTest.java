package com.zifang.z.cache.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ZCacheClientTest {

    private ZCacheClientConfig config;

    @BeforeEach
    void setUp() {
        config = new ZCacheClientConfig("localhost", 6379)
                .withConnectTimeout(Duration.ofMillis(500))
                .withReadTimeout(Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void testDefaultConstructor() {
        ZCacheClient client = new ZCacheClient();
        assertNotNull(client);
        assertFalse(client.isConnected());
        client.close();
    }

    @Test
    void testConstructorWithHostAndPort() {
        ZCacheClient client = new ZCacheClient("myhost", 1234);
        assertNotNull(client);
        assertFalse(client.isConnected());
        client.close();
    }

    @Test
    void testConstructorWithConfig() {
        ZCacheClient client = new ZCacheClient(config);
        assertNotNull(client);
        assertFalse(client.isConnected());
        client.close();
    }

    @Test
    void testConstructorWithNullConfig() {
        assertThrows(NullPointerException.class, () -> new ZCacheClient(null));
    }

    @Test
    void testClose() {
        ZCacheClient client = new ZCacheClient(config);
        assertDoesNotThrow(() -> client.close());
        assertDoesNotThrow(() -> client.close());
    }

    @Test
    void testMultipleClose() {
        ZCacheClient client = new ZCacheClient(config);
        assertDoesNotThrow(() -> {
            client.close();
            client.close();
            client.close();
        });
    }

    @Test
    void testIsConnectedBeforeConnect() {
        ZCacheClient client = new ZCacheClient(config);
        assertFalse(client.isConnected());
        client.close();
    }

    @Test
    void testToString() {
        ZCacheClient client = new ZCacheClient(config);
        String str = client.toString();
        assertNotNull(str);
        client.close();
    }
}
