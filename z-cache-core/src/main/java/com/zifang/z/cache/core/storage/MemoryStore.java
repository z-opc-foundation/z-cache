package com.zifang.z.cache.core.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * 内存型键值存储实现
 * 支持字符串数据类型及过期时间(TTL)
 *
 * @author zifang
 * @since 1.0.0
 */
public class MemoryStore {

    /**
     * 存储数据的并发哈希表
     */
    private final Map<String, ValueWrapper> store = new ConcurrentHashMap<>();

    /**
     * 最大键数量，0表示不限制。
     */
    private final int maxEntries;

    /**
     * 因达到容量上限被淘汰的键数量。
     */
    private final AtomicLong evictions = new AtomicLong(0);
    
    /**
     * 缓存命中次数统计
     */
    private final AtomicLong hits = new AtomicLong(0);
    
    /**
     * 缓存未命中次数统计
     */
    private final AtomicLong misses = new AtomicLong(0);

    public MemoryStore() {
        this(0);
    }

    /**
     * 创建带最大键数量限制的内存存储。
     *
     * @param maxEntries 最大键数量，0表示不限制
     */
    public MemoryStore(int maxEntries) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("maxEntries cannot be negative");
        }
        this.maxEntries = maxEntries;
    }

    /**
     * 设置键值对（无过期时间）
     *
     * @param key   键
     * @param value 值（字节数组）
     * @return true表示设置成功
     */
    public boolean set(String key, byte[] value) {
        put(key, new ValueWrapper(value == null ? null : value.clone(), -1));
        return true;
    }

    // ==================== Basic Operations ====================

    /**
     * 设置键值对并指定过期时间（秒）
     *
     * @param key      键
     * @param seconds  过期时间（秒）
     * @param value    值（字节数组）
     * @return true表示设置成功
     */
    public boolean setex(String key, int seconds, byte[] value) {
        long expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        put(key, new ValueWrapper(value == null ? null : value.clone(), expireAt));
        return true;
    }

    /**
     * 设置键值对并指定过期时间（毫秒）
     *
     * @param key        键
     * @param milliseconds 过期时间（毫秒）
     * @param value      值（字节数组）
     * @return true表示设置成功
     */
    public boolean psetex(String key, long milliseconds, byte[] value) {
        long expireAt = System.currentTimeMillis() + milliseconds;
        put(key, new ValueWrapper(value == null ? null : value.clone(), expireAt));
        return true;
    }

    /**
     * 仅当键不存在或已过期时写入，保证 NX 操作的原子性。
     */
    public boolean setIfAbsent(String key, byte[] value) {
        synchronized (store) {
            ValueWrapper current = getLiveWrapper(key);
            if (current != null) {
                return false;
            }
            put(key, new ValueWrapper(copy(value), -1));
            return true;
        }
    }

    /**
     * 获取旧值并写入新值。该操作会清除旧值的过期时间。
     */
    public byte[] getAndSet(String key, byte[] value) {
        synchronized (store) {
            ValueWrapper current = getLiveWrapper(key);
            put(key, new ValueWrapper(copy(value), -1));
            return current == null ? null : copy(current.data);
        }
    }

    /**
     * 追加字符串字节并返回追加后的字节长度，保留原有 TTL。
     */
    public long append(String key, byte[] suffix) {
        synchronized (store) {
            ValueWrapper current = getLiveWrapper(key);
            byte[] prefix = current == null || current.data == null ? new byte[0] : current.data;
            byte[] value = suffix == null ? prefix.clone() : Arrays.copyOf(prefix, prefix.length + suffix.length);
            if (suffix != null) {
                System.arraycopy(suffix, 0, value, prefix.length, suffix.length);
            }
            put(key, new ValueWrapper(value, current == null ? -1 : current.expireAt));
            return value.length;
        }
    }

    /**
     * 原子递增整数值，保留原有 TTL。
     */
    public long increment(String key, long delta) {
        synchronized (store) {
            ValueWrapper current = getLiveWrapper(key);
            long value = 0;
            if (current != null && current.data != null) {
                try {
                    value = Long.parseLong(new String(current.data, StandardCharsets.UTF_8));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("value is not an integer or out of range", e);
                }
            }
            final long result;
            try {
                result = Math.addExact(value, delta);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("increment or decrement would overflow", e);
            }
            put(key, new ValueWrapper(Long.toString(result).getBytes(StandardCharsets.UTF_8),
                    current == null ? -1 : current.expireAt));
            return result;
        }
    }

    /**
     * 批量读取键值。每个键的读取都会遵循 GET 的过期和统计语义。
     */
    public List<byte[]> mget(String... keys) {
        List<byte[]> values = new ArrayList<>(keys == null ? 0 : keys.length);
        if (keys != null) {
            for (String key : keys) {
                values.add(get(key));
            }
        }
        return values;
    }

    /**
     * 返回当前有效键的快照，支持 * 和 ? 通配符。
     */
    public List<String> keys(String pattern) {
        List<String> result = new ArrayList<>();
        if (pattern == null) {
            return result;
        }
        String regex = globToRegex(pattern);
        for (Map.Entry<String, ValueWrapper> entry : store.entrySet()) {
            ValueWrapper wrapper = entry.getValue();
            if (wrapper != null && !wrapper.isExpired() && entry.getKey().matches(regex)) {
                result.add(entry.getKey());
            } else if (wrapper != null && wrapper.isExpired()) {
                store.remove(entry.getKey(), wrapper);
            }
        }
        return result;
    }

    /**
     * 获取键的剩余过期时间（毫秒）。
     */
    public long pttl(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null || wrapper.isExpired()) {
            if (wrapper != null) {
                store.remove(key, wrapper);
            }
            return -2;
        }
        if (!wrapper.hasExpiration()) {
            return -1;
        }
        return Math.max(wrapper.expireAt - System.currentTimeMillis(), 0);
    }

    private void put(String key, ValueWrapper value) {
        synchronized (store) {
            store.put(key, value);
            if (maxEntries <= 0 || store.size() <= maxEntries) {
                return;
            }
            for (String candidate : store.keySet()) {
                if (!candidate.equals(key) && store.remove(candidate) != null) {
                    evictions.incrementAndGet();
                    break;
                }
            }
        }
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public long getEvictions() {
        return evictions.get();
    }

    private ValueWrapper getLiveWrapper(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper != null && wrapper.isExpired()) {
            store.remove(key, wrapper);
            return null;
        }
        return wrapper;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    private static String globToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.append('$').toString();
    }

    /**
     * 获取指定键的值
     *
     * @param key 键
     * @return 值的字节数组，不存在返回null
     */
    public byte[] get(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null) {
            misses.incrementAndGet();
            return null;
        }
        if (wrapper.isExpired()) {
            store.remove(key, wrapper);
            misses.incrementAndGet();
            return null;
        }
        if (wrapper.data == null) {
            // Stored value was null; treat as a miss so callers can distinguish
            // "key absent" from "key present with null value" via exists()/dbsize()
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return wrapper.data.clone();
    }

    /**
     * 获取指定键的值（字符串形式，UTF-8编码）
     *
     * @param key 键
     * @return 值的字符串形式，不存在返回null
     */
    public String getString(String key) {
        byte[] data = get(key);
        if (data == null) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 删除指定键
     *
     * @param key 键
     * @return true表示删除成功
     */
    public boolean del(String key) {
        ValueWrapper wrapper = store.get(key);
        if (wrapper == null) {
            return false;
        }
        if (wrapper.isExpired()) {
            store.remove(key, wrapper);
            return false;
        }
        return store.remove(key, wrapper);
    }

    /**
     * 删除多个键
     *
     * @param keys 要删除的键数组
     * @return 实际删除的键数量
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
     * 检查键是否存在
     *
     * @param key 键
     * @return true表示存在
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
     * 设置键的过期时间（秒）
     *
     * @param key     键
     * @param seconds 过期时间（秒）
     * @return true表示设置成功
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
        put(key, new ValueWrapper(wrapper.data, expireAt));
        return true;
    }

    /**
     * 设置键的过期时间（毫秒）。
     */
    public boolean pexpire(String key, long milliseconds) {
        if (milliseconds <= 0) {
            return del(key);
        }
        synchronized (store) {
            ValueWrapper wrapper = store.get(key);
            if (wrapper == null || wrapper.isExpired()) {
                if (wrapper != null) {
                    store.remove(key, wrapper);
                }
                return false;
            }
            long expireAt = System.currentTimeMillis() + milliseconds;
            put(key, new ValueWrapper(wrapper.data, expireAt));
            return true;
        }
    }

    /**
     * 移除键的过期时间
     *
     * @param key 键
     * @return true表示移除成功
     */
    public boolean persist(String key) {
        synchronized (store) {
            ValueWrapper wrapper = store.get(key);
            if (wrapper == null || wrapper.isExpired()) {
                if (wrapper != null) {
                    store.remove(key, wrapper);
                }
                return false;
            }
            if (!wrapper.hasExpiration()) {
                return false;
            }
            put(key, new ValueWrapper(wrapper.data, -1));
            return true;
        }
    }

    /**
     * 获取键的剩余过期时间（秒）
     *
     * @param key 键
     * @return -2表示键不存在，-1表示无过期时间，否则返回剩余秒数
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
     * 获取存储的键数量
     *
     * @return 非过期键的数量
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
     * 获取缓存命中次数
     *
     * @return 命中次数
     */
    public long getHits() {
        return hits.get();
    }

    /**
     * 获取缓存未命中次数
     *
     * @return 未命中次数
     */
    public long getMisses() {
        return misses.get();
    }

    /**
     * 清空所有数据并重置统计信息
     */
    public void flush() {
        store.clear();
        hits.set(0);
        misses.set(0);
    }

    /**
     * 值包装类，包含数据和过期时间
     */
    private static class ValueWrapper {
        /**
         * 实际存储的数据
         */
        private final byte[] data;
        
        /**
         * 过期时间戳（-1表示永不过期）
         */
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
