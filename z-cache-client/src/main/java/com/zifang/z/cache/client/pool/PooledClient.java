package com.zifang.z.cache.client.pool;

import com.zifang.z.cache.client.ZCacheClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A client borrowed from the connection pool
 * Automatically returns to the pool when closed
 * <p>
 * Example usage:
 * <pre>
 * try (ZCachePool pool = new ZCachePool(config, 10)) {
 *     try (PooledClient client = pool.borrowClient()) {
 *         client.set("key", "value");
 *         String value = client.get("key");
 *     } // client automatically returned to pool
 * }
 * </pre>
 */
public class PooledClient implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(PooledClient.class);

    private final ZCacheClient client;
    private final ZCachePool pool;
    private volatile boolean returned = false;
    private volatile boolean inUse = true;

    public PooledClient(ZCacheClient client, ZCachePool pool) {
        this.client = client;
        this.pool = pool;
        this.inUse = true;
    }

    /**
     * Alternative constructor for backward compatibility
     */
    public PooledClient(com.zifang.z.cache.client.ZCacheConnection connection) {
        throw new UnsupportedOperationException("Use PooledClient(ZCacheClient, ZCachePool) instead");
    }

    /**
     * Check if this client is currently in use
     */
    public boolean isInUse() {
        return inUse && !returned;
    }

    /**
     * Mark this client as in use
     */
    public void markInUse() {
        this.inUse = true;
    }

    /**
     * Mark this client as available
     */
    public void markAvailable() {
        this.inUse = false;
    }

    /**
     * Get the underlying connection (alias for getClient)
     *
     * @deprecated use getClient() instead
     */
    @Deprecated
    public com.zifang.z.cache.client.ZCacheConnection getConnection() {
        throw new UnsupportedOperationException("Use getClient() instead");
    }

    /**
     * Get the underlying client
     *
     * @return the underlying ZCacheClient
     */
    public ZCacheClient getClient() {
        return client;
    }

    /**
     * Convenience method for SET command
     */
    public String set(String key, String value) {
        return client.set(key, value);
    }

    /**
     * Convenience method for GET command
     */
    public String get(String key) {
        return client.get(key);
    }

    /**
     * Convenience method for DEL command
     */
    public Long del(String... keys) {
        return client.del(keys);
    }

    /**
     * Convenience method for PING command
     */
    public String ping() {
        return client.ping();
    }

    @Override
    public void close() {
        if (returned) {
            return;
        }
        returned = true;
        pool.returnClientPublic(this);
    }
}
