package com.zifang.z.cache.core.storage;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory key-value storage for z-cache
 * Supports String data type with TTL (Time To Live)
 */
public class MemoryStore {

    private final Map<String, ValueWrapper> store = new ConcurrentHashMap<>();
    // Statistics
    private volatile long hits = 0;
    private volatile long misses = 0;

    /**
     * Set key to hold the string value
     */
    public boolean set(String key, byte[] value) {
        store.put(key, new ValueWrapper(value, -1));
        return true;
    }

    // ==================== Basic Operations ====================

    /**
     * Set key to hold the string value with expiration in seconds
     */
    public boolean setex(String key, int seconds, byte[] value) {
        long expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        store.put(key, new ValueWrapper(value, expireAt));
        return true;
    }

    /**
     * Set key to hold the string value with expiration in milliseconds
     */
    public boolean psetex(String key, long milliseconds, byte[] value) {
        long expireAt = System.currentTimeMillis() + milliseconds;
        store.put(key, new ValueWrapper(value, expireAt));
        return true;
    }

    /**
     * Get the value of key
     */
    public byte[] get(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null) {
            misses++;
            return null;
        }
        if (wrapper.isExpired()) {
            store.remove(key);
            misses++;
            return null;
        }
        hits++;
        return wrapper.data.clone();
    }

    /**
     * Get the value of key as String (UTF-8)
     */
    public String getString(String key) {
        byte[] data = get(key);
        if (data == null) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Delete a key
     */
    public boolean del(String key) {
        return store.remove(key) != null;
    }

    /**
     * Delete multiple keys
     */
    public long del(String... keys) {
        long count = 0;
        for (String key : keys) {
            if (del(key)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Check if key exists
     */
    public boolean exists(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null) {
            return false;
        }
        if (wrapper.isExpired()) {
            store.remove(key);
            return false;
        }
        return true;
    }

    /**
     * Set expiration on a key (in seconds)
     */
    public boolean expire(String key, int seconds) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null || wrapper.isExpired()) {
            if (wrapper != null && wrapper.isExpired()) {
                store.remove(key);
            }
            return false;
        }
        long expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        store.put(key, new ValueWrapper(wrapper.data, expireAt));
        return true;
    }

    /**
     * Remove expiration from a key
     */
    public boolean persist(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null || wrapper.isExpired()) {
            if (wrapper != null && wrapper.isExpired()) {
                store.remove(key);
            }
            return false;
        }
        if (!wrapper.hasExpiration()) {
            return false;
        }
        store.put(key, new ValueWrapper(wrapper.data, -1));
        return true;
    }

    /**
     * Get TTL (Time To Live) in seconds
     */
    public long ttl(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null || wrapper.isExpired()) {
            if (wrapper != null && wrapper.isExpired()) {
                store.remove(key);
            }
            return -2; // Key does not exist
        }
        if (!wrapper.hasExpiration()) {
            return -1; // No expiration
        }
        long ttl = (wrapper.expireAt - System.currentTimeMillis()) / 1000;
        return Math.max(ttl, 0);
    }

    /**
     * Get number of keys in the store
     */
    public long dbsize() {
        long count = 0;
        for (java.util.Iterator<String> it = store.keySet().iterator(); it.hasNext(); ) {
            String key = it.next();
            ValueWrapper wrapper = store.get(key);
            if (wrapper != null && !wrapper.isExpired()) {
                count++;
            } else if (wrapper != null && wrapper.isExpired()) {
                it.remove();
            }
        }
        return count;
    }

    // ==================== Statistics ====================

    /**
     * Get cache hit count
     */
    public long getHits() {
        return hits;
    }

    /**
     * Get cache miss count
     */
    public long getMisses() {
        return misses;
    }

    /**
     * Clear all data
     */
    public void flush() {
        store.clear();
        hits = 0;
        misses = 0;
    }

    /**
     * Value wrapper containing data and expiration time
     */
    private static class ValueWrapper {
        private final byte[] data;
        private final long expireAt; // -1 means no expiration

        ValueWrapper(byte[] data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return expireAt > 0 && System.currentTimeMillis() > expireAt;
        }

        boolean hasExpiration() {
            return expireAt > 0;
        }
    }
}
