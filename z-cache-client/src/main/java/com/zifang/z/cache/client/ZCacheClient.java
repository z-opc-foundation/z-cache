package com.zifang.z.cache.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ZCacheClient - Main client for z-cache server
 * <p>
 * Example usage:
 * <pre>
 * try (ZCacheClient client = new ZCacheClient("localhost", 6379)) {
 *     client.connect();
 *     client.set("key", "value");
 *     String value = client.get("key");
 * }
 * </pre>
 */
public class ZCacheClient implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ZCacheClient.class);

    private final ZCacheClientConfig config;
    private final ZCacheConnection connection;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ZCacheClient() {
        this(new ZCacheClientConfig());
    }

    public ZCacheClient(String host, int port) {
        this(new ZCacheClientConfig(host, port));
    }

    public ZCacheClient(ZCacheClientConfig config) {
        this.config = config;
        this.connection = new ZCacheConnection(config);
    }

    protected static String toString(Object response) {
        if (response == null) return null;
        if (response instanceof com.zifang.z.cache.common.protocol.RespBulkString) {
            return ((com.zifang.z.cache.common.protocol.RespBulkString) response).getString();
        }
        if (response instanceof com.zifang.z.cache.common.protocol.RespSimpleString) {
            return ((com.zifang.z.cache.common.protocol.RespSimpleString) response).getValue();
        }
        return response.toString();
    }

    protected static Long toLong(Object response) {
        if (response == null) return null;
        if (response instanceof com.zifang.z.cache.common.protocol.RespInteger) {
            return ((com.zifang.z.cache.common.protocol.RespInteger) response).getValue();
        }
        if (response instanceof Number) {
            return ((Number) response).longValue();
        }
        return Long.parseLong(response.toString());
    }

    public void connect() {
        ensureNotClosed();
        connection.connect();
    }

    // String operations

    public boolean isConnected() {
        return connection.isConnected();
    }

    /**
     * Get the client configuration
     *
     * @return the configuration
     */
    public ZCacheClientConfig getConfig() {
        return config;
    }

    public String get(String key) {
        Object response = sendCommand("GET", key);
        return toString(response);
    }

    public String set(String key, String value) {
        Object response = sendCommand("SET", key, value);
        return toString(response);
    }

    public String setex(String key, long seconds, String value) {
        Object response = sendCommand("SETEX", key, seconds, value);
        return toString(response);
    }

    public Long setnx(String key, String value) {
        Object response = sendCommand("SETNX", key, value);
        return toLong(response);
    }

    public String getSet(String key, String value) {
        Object response = sendCommand("GETSET", key, value);
        return toString(response);
    }

    public Long append(String key, String value) {
        Object response = sendCommand("APPEND", key, value);
        return toLong(response);
    }

    public Long strlen(String key) {
        Object response = sendCommand("STRLEN", key);
        return toLong(response);
    }

    public Long incr(String key) {
        Object response = sendCommand("INCR", key);
        return toLong(response);
    }

    public Long incrBy(String key, long delta) {
        Object response = sendCommand("INCRBY", key, delta);
        return toLong(response);
    }

    // Key operations

    public Long decr(String key) {
        Object response = sendCommand("DECR", key);
        return toLong(response);
    }

    public Long decrBy(String key, long delta) {
        Object response = sendCommand("DECRBY", key, delta);
        return toLong(response);
    }

    public Long del(String... keys) {
        Object response = sendCommand("DEL", (Object[]) keys);
        return toLong(response);
    }

    public Long exists(String... keys) {
        Object response = sendCommand("EXISTS", (Object[]) keys);
        return toLong(response);
    }

    public Long expire(String key, long seconds) {
        Object response = sendCommand("EXPIRE", key, seconds);
        return toLong(response);
    }

    public Long pexpire(String key, long milliseconds) {
        Object response = sendCommand("PEXPIRE", key, milliseconds);
        return toLong(response);
    }

    public Long ttl(String key) {
        Object response = sendCommand("TTL", key);
        return toLong(response);
    }

    // Server operations

    public Long pttl(String key) {
        Object response = sendCommand("PTTL", key);
        return toLong(response);
    }

    public Long persist(String key) {
        Object response = sendCommand("PERSIST", key);
        return toLong(response);
    }

    public String ping() {
        Object response = sendCommand("PING");
        return toString(response);
    }

    public String ping(String message) {
        Object response = sendCommand("PING", message);
        return toString(response);
    }

    public String echo(String message) {
        Object response = sendCommand("ECHO", message);
        return toString(response);
    }

    public String flushdb() {
        Object response = sendCommand("FLUSHDB");
        return toString(response);
    }

    public String flushall() {
        Object response = sendCommand("FLUSHALL");
        return toString(response);
    }

    // Helper methods

    public Long dbsize() {
        Object response = sendCommand("DBSIZE");
        return toLong(response);
    }

    public String select(int database) {
        Object response = sendCommand("SELECT", String.valueOf(database));
        return toString(response);
    }

    protected Object sendCommand(String command, Object... args) {
        ensureNotClosed();
        return connection.sendCommand(command, args);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.debug("Closing ZCacheClient");
            connection.close();
        }
    }

    private void ensureNotClosed() {
        if (closed.get()) {
            throw new ZCacheClientException("Client is closed");
        }
    }
}
