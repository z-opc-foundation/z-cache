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
        this(config, config == null ? 8 : config.getPoolMaxSize(), 30000);
    }

    public ZCachePool(ZCacheClientConfig config, int maxSize) {
        this(config, maxSize, 30000);
    }

    public ZCachePool(ZCacheClientConfig config, int maxSize, long maxWaitMillis) {
        if (config == null) {
            throw new NullPointerException("config cannot be null");
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        if (maxWaitMillis < 0) {
            throw new IllegalArgumentException("maxWaitMillis cannot be negative");
        }
        this.config = config;
        this.maxSize = maxSize;
        this.maxWaitMillis = maxWaitMillis;
        this.availableClients = new LinkedBlockingQueue<>(maxSize);
    }

    public PooledClient borrowClient() {
        ensureNotClosed();

        PooledClient client = availableClients.poll();
        if (client != null) {
            // Wrap the borrowed underlying client in a fresh PooledClient
            // so each borrow owns an independent `returned` flag. Returning
            // the same PooledClient instance here would mean a second close()
            // becomes a no-op (returned=true), leaking the activeCount
            // increment and breaking pool capacity accounting.
            activeCount.incrementAndGet();
            logger.debug("Reusing pooled client, active={}", activeCount.get());
            return new PooledClient(client.getClient(), this);
        }

        int after = activeCount.incrementAndGet();
        if (after <= maxSize) {
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
            // Active count exceeded max size; restore and wait for an available client.
            activeCount.decrementAndGet();
            logger.debug("Pool over capacity, waiting for an available client");
        }

        try {
            client = availableClients.poll(maxWaitMillis, TimeUnit.MILLISECONDS);
            if (client != null) {
                activeCount.incrementAndGet();
                logger.debug("Got client from pool after waiting");
                return new PooledClient(client.getClient(), this);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZCacheClientException("Interrupted while waiting for client", e);
        }

        throw new ZCacheClientException("Could not get a client from the pool within " + maxWaitMillis + "ms");
    }

    void returnClient(PooledClient client) {
        if (client == null) {
            // Return null is a no-op (idempotent close path)
            return;
        }
        // Client is no longer "in use" by the caller; decrement first regardless
        // of whether the pool is closed or whether the available queue accepts it.
        activeCount.decrementAndGet();

        if (closed.get()) {
            client.getClient().close();
            return;
        }

        if (!availableClients.offer(client)) {
            client.getClient().close();
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
