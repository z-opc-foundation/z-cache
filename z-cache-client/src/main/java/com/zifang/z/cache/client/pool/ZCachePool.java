package com.zifang.z.cache.client.pool;

import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.client.ZCacheClientConfig;
import com.zifang.z.cache.client.ZCacheClientException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection pool for ZCacheClient
 * Provides connection reuse for high concurrency scenarios
 */
public class ZCachePool implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ZCachePool.class);

    private final ZCacheClientConfig config;
    private final int maxSize;
    private final long maxWaitMillis;
    private final BlockingQueue<PooledClient> availableClients;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ZCachePool(ZCacheClientConfig config) {
        this(config, 8, 30000);
    }

    public ZCachePool(ZCacheClientConfig config, int maxSize) {
        this(config, maxSize, 30000);
    }

    public ZCachePool(ZCacheClientConfig config, int maxSize, long maxWaitMillis) {
        this.config = config;
        this.maxSize = maxSize;
        this.maxWaitMillis = maxWaitMillis;
        this.availableClients = new LinkedBlockingQueue<>(maxSize);
    }

    public PooledClient borrowClient() {
        ensureNotClosed();

        PooledClient client = availableClients.poll();
        if (client != null) {
            logger.debug("Reusing pooled client, active={}", activeCount.get());
            return client;
        }

        if (activeCount.incrementAndGet() <= maxSize) {
            try {
                ZCacheClient newClient = new ZCacheClient(config);
                newClient.connect();
                logger.debug("Created new pooled client, active={}", activeCount.get());
                return new PooledClient(newClient, this);
            } catch (Exception e) {
                activeCount.decrementAndGet();
                throw new ZCacheClientException("Failed to create new client for pool", e);
            }
        } else {
            activeCount.decrementAndGet();
        }

        try {
            client = availableClients.poll(maxWaitMillis, TimeUnit.MILLISECONDS);
            if (client != null) {
                logger.debug("Got client from pool after waiting");
                return client;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZCacheClientException("Interrupted while waiting for client", e);
        }

        throw new ZCacheClientException("Could not get a client from the pool within " + maxWaitMillis + "ms");
    }

    void returnClient(PooledClient client) {
        if (closed.get()) {
            client.getClient().close();
            activeCount.decrementAndGet();
            return;
        }

        if (!availableClients.offer(client)) {
            client.getClient().close();
            activeCount.decrementAndGet();
            logger.debug("Closed excess client, active={}", activeCount.get());
        } else {
            logger.debug("Returned client to pool, active={}", activeCount.get());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.info("Closing connection pool...");

        PooledClient client;
        while ((client = availableClients.poll()) != null) {
            try {
                client.getClient().close();
                activeCount.decrementAndGet();
            } catch (Exception e) {
                logger.warn("Error closing client", e);
            }
        }

        logger.info("Connection pool closed");
    }

    private void ensureNotClosed() {
        if (closed.get()) {
            throw new ZCacheClientException("Pool is closed");
        }
    }

    public int getActiveCount() {
        return activeCount.get();
    }

    public int getAvailableCount() {
        return availableClients.size();
    }

    public int getMaxSize() {
        return maxSize;
    }

    public int getPoolSize() {
        return activeCount.get() + availableClients.size();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void returnClientPublic(PooledClient client) {
        returnClient(client);
    }
}
