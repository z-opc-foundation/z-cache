package com.zifang.z.cache.client.pool;

import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.client.ZCacheClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PooledClient 测试类
 */
class PooledClientTest {

    private ZCacheClientConfig config;

    @BeforeEach
    void setUp() {
        config = new ZCacheClientConfig("localhost", 6379)
                .withConnectTimeout(Duration.ofSeconds(1))
                .withReadTimeout(Duration.ofSeconds(1));
    }

    @Test
    void testConstructor() {
        ZCacheClient client = new ZCacheClient(config);
        ZCachePool pool = new ZCachePool(config, 5);
        PooledClient pooledClient = new PooledClient(client, pool);

        assertNotNull(pooledClient);
        pooledClient.close();
    }

    @Test
    void testConstructorWithNullClient() {
        ZCachePool pool = new ZCachePool(config, 5);
        assertThrows(NullPointerException.class, () -> new PooledClient(null, pool));
    }

    @Test
    void testGetClient() {
        ZCacheClient client = new ZCacheClient(config);
        ZCachePool pool = new ZCachePool(config, 5);
        PooledClient pooledClient = new PooledClient(client, pool);

        ZCacheClient retrievedClient = pooledClient.getClient();
        assertSame(client, retrievedClient);

        pooledClient.close();
    }

    @Test
    void testClose() {
        ZCacheClient client = new ZCacheClient(config);
        ZCachePool pool = new ZCachePool(config, 5);
        PooledClient pooledClient = new PooledClient(client, pool);

        // 关闭不应该抛出异常
        assertDoesNotThrow(() -> pooledClient.close());
        // 多次关闭不应该抛出异常
        assertDoesNotThrow(() -> pooledClient.close());
    }

    @Test
    void testDoubleClose() {
        ZCacheClient client = new ZCacheClient(config);
        ZCachePool pool = new ZCachePool(config, 5);
        PooledClient pooledClient = new PooledClient(client, pool);

        pooledClient.close();
        // 第二次关闭不应该抛出异常
        assertDoesNotThrow(() -> pooledClient.close());
    }

    @Test
    void testClientStateAfterReturn() {
        ZCacheClient client = new ZCacheClient(config);
        ZCachePool pool = new ZCachePool(config, 5);
        PooledClient pooledClient = new PooledClient(client, pool);

        // 验证客户端可以正常获取
        assertSame(client, pooledClient.getClient());

        pooledClient.close();
    }

    @Test
    void testMultiplePooledClientsFromSamePool() {
        ZCachePool pool = new ZCachePool(config, 10);

        // 从同一个池创建多个 PooledClient
        PooledClient pooledClient1 = null;
        PooledClient pooledClient2 = null;

        try {
            ZCacheClient client1 = new ZCacheClient(config);
            ZCacheClient client2 = new ZCacheClient(config);

            pooledClient1 = new PooledClient(client1, pool);
            pooledClient2 = new PooledClient(client2, pool);

            assertNotNull(pooledClient1);
            assertNotNull(pooledClient2);
            assertNotSame(pooledClient1, pooledClient2);
        } finally {
            if (pooledClient1 != null) pooledClient1.close();
            if (pooledClient2 != null) pooledClient2.close();
        }
    }
}
