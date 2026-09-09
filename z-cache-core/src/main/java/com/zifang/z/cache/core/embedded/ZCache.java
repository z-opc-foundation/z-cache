package com.zifang.z.cache.core.embedded;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 嵌入式内存缓存，支持泛型键值对、TTL 过期、后台清理和统计。
 * <p>
 * 本类不依赖 Netty 或 RESP 协议，无需启动服务器/客户端，直接在 JVM 内使用。
 * <p>
 * 用法示例:
 * <pre>
 * // 创建缓存，最大 10000 条目，60 秒后台清理一次
 * ZCache&lt;String, User&gt; cache = ZCache.&lt;String, User&gt;builder()
 *         .maxSize(10000)
 *         .cleanupIntervalSec(60)
 *         .build();
 *
 * // 写入，60 秒后过期
 * cache.set("user:1", new User("张三"), 60, TimeUnit.SECONDS);
 *
 * // 读取
 * User user = cache.get("user:1");
 *
 * // 关闭后台线程
 * cache.close();
 * </pre>
 *
 * @param <K> 键类型，必须可序列化
 * @param <V> 值类型，必须可序列化
 */
public class ZCache<K extends Serializable, V extends Serializable> implements AutoCloseable {

    private final ConcurrentHashMap<String, Entry<V>> store;
    private final int maxSize;
    private final ScheduledExecutorService cleanupExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // 统计
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    ZCache(int maxSize, long cleanupIntervalSec) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got " + maxSize);
        }
        this.maxSize = maxSize;
        this.store = new ConcurrentHashMap<>(Math.min(maxSize, 1024));

        if (cleanupIntervalSec > 0) {
            this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "z-cache-cleanup");
                t.setDaemon(true);
                return t;
            });
            this.cleanupExecutor.scheduleWithFixedDelay(
                    this::cleanupExpired,
                    cleanupIntervalSec,
                    cleanupIntervalSec,
                    TimeUnit.SECONDS);
        } else {
            this.cleanupExecutor = null;
        }
    }

    // ==================== Public API ====================

    /**
     * 获取键对应的值，未命中或已过期返回 null。
     */
    @SuppressWarnings("unchecked")
    public V get(K key) {
        ensureNotClosed();
        Entry<V> entry = store.get(serializeKey(key));
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (entry.isExpired()) {
            store.remove(serializeKey(key));
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.value;
    }

    /**
     * 写入键值对，永不过期。
     */
    public void set(K key, V value) {
        ensureNotClosed();
        putEntry(key, value, -1);
    }

    /**
     * 写入键值对，指定过期时间。
     */
    public void set(K key, V value, long timeout, TimeUnit unit) {
        ensureNotClosed();
        long expireAt = System.currentTimeMillis() + unit.toMillis(timeout);
        putEntry(key, value, expireAt);
    }

    /**
     * 仅在键不存在时写入（NX 语义）。
     * 使用 ConcurrentHashMap.putIfAbsent 保证原子性。
     *
     * @return true 如果写入成功；false 如果键已存在（且未过期）
     */
    public boolean setIfAbsent(K key, V value) {
        ensureNotClosed();
        String sk = serializeKey(key);
        Entry<V> newEntry = new Entry<>(value, -1);
        Entry<V> existing = store.putIfAbsent(sk, newEntry);
        if (existing == null) {
            // First insert — success
            enforceMaxSize(sk);
            return true;
        }
        if (existing.isExpired()) {
            // Existing entry is expired; replace with new value
            if (store.replace(sk, existing, newEntry)) {
                enforceMaxSize(sk);
                return true;
            }
            // CAS failed — another thread inserted; retry via regular set
            store.put(sk, newEntry);
            enforceMaxSize(sk);
            return true;
        }
        // Key exists and is not expired
        return false;
    }

    /**
     * 仅在键不存在时写入，带 TTL。
     * 使用 ConcurrentHashMap.putIfAbsent 保证原子性。
     *
     * @return true 如果写入成功；false 如果键已存在（且未过期）
     */
    public boolean setIfAbsent(K key, V value, long timeout, TimeUnit unit) {
        ensureNotClosed();
        long expireAt = System.currentTimeMillis() + unit.toMillis(timeout);
        String sk = serializeKey(key);
        Entry<V> newEntry = new Entry<>(value, expireAt);
        Entry<V> existing = store.putIfAbsent(sk, newEntry);
        if (existing == null) {
            enforceMaxSize(sk);
            return true;
        }
        if (existing.isExpired()) {
            if (store.replace(sk, existing, newEntry)) {
                enforceMaxSize(sk);
                return true;
            }
            store.put(sk, newEntry);
            enforceMaxSize(sk);
            return true;
        }
        return false;
    }

    /**
     * 删除键。
     *
     * @return true 如果键存在且删除成功
     */
    public boolean delete(K key) {
        ensureNotClosed();
        return store.remove(serializeKey(key)) != null;
    }

    /**
     * 批量删除。
     *
     * @return 成功删除的数量
     */
    @SafeVarargs
    public final long delete(K... keys) {
        ensureNotClosed();
        long count = 0;
        for (K key : keys) {
            if (store.remove(serializeKey(key)) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断键是否存在（未过期）。
     */
    public boolean exists(K key) {
        ensureNotClosed();
        Entry<V> entry = store.get(serializeKey(key));
        return entry != null && !entry.isExpired();
    }

    /**
     * 获取键的剩余生存时间（秒）。
     *
     * @return -1 表示永不过期；-2 表示键不存在
     */
    public long ttl(K key) {
        ensureNotClosed();
        Entry<V> entry = store.get(serializeKey(key));
        if (entry == null || entry.isExpired()) {
            if (entry != null && entry.isExpired()) {
                store.remove(serializeKey(key));
            }
            return -2;
        }
        if (entry.expireAt < 0) {
            return -1;
        }
        long ttl = (entry.expireAt - System.currentTimeMillis()) / 1000;
        return Math.max(ttl, 0);
    }

    /**
     * 设置键的过期时间（秒）。
     *
     * @return true 如果设置成功
     */
    public boolean expire(K key, long seconds) {
        ensureNotClosed();
        String sk = serializeKey(key);
        Entry<V> entry = store.get(sk);
        if (entry == null || entry.isExpired()) {
            if (entry != null && entry.isExpired()) {
                store.remove(sk);
            }
            return false;
        }
        long expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        store.put(sk, new Entry<>(entry.value, expireAt));
        return true;
    }

    /**
     * 移除键的过期时间，使其永不过期。
     *
     * @return true 如果成功移除；false 如果键不存在或已无过期时间
     */
    public boolean persist(K key) {
        ensureNotClosed();
        String sk = serializeKey(key);
        Entry<V> entry = store.get(sk);
        if (entry == null || entry.isExpired()) {
            if (entry != null && entry.isExpired()) {
                store.remove(sk);
            }
            return false;
        }
        if (entry.expireAt < 0) {
            return false;
        }
        store.put(sk, new Entry<>(entry.value, -1));
        return true;
    }

    /**
     * 获取缓存中的有效条目数量。
     */
    public long size() {
        ensureNotClosed();
        long count = 0;
        Iterator<Map.Entry<String, Entry<V>>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry<V>> e = it.next();
            if (e.getValue().isExpired()) {
                it.remove();
            } else {
                count++;
            }
        }
        return count;
    }

    /**
     * 清空所有数据（保留统计计数器）。
     */
    public void clear() {
        ensureNotClosed();
        store.clear();
    }

    /**
     * 清空所有数据并重置统计。
     */
    public void flush() {
        ensureNotClosed();
        store.clear();
        hits.set(0);
        misses.set(0);
    }

    // ==================== 统计 ====================

    public long getHitCount() {
        return hits.get();
    }

    public long getMissCount() {
        return misses.get();
    }

    public double getHitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    // ==================== Lifecycle ====================

    /**
     * 关闭缓存，停止后台清理线程。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    // ==================== 内部方法 ====================

    private void putEntry(K key, V value, long expireAt) {
        String sk = serializeKey(key);
        store.put(sk, new Entry<>(value, expireAt));
        enforceMaxSize(sk);
    }

    private void enforceMaxSize(String insertedKey) {
        if (store.size() <= maxSize) {
            return;
        }
        for (String candidate : store.keySet()) {
            if (!candidate.equals(insertedKey) && store.remove(candidate) != null) {
                break;
            }
        }
    }

    private String serializeKey(K key) {
        if (key instanceof String) {
            return (String) key;
        }
        return key.toString();
    }

    private void cleanupExpired() {
        try {
            Iterator<Map.Entry<String, Entry<V>>> it = store.entrySet().iterator();
            int removed = 0;
            while (it.hasNext()) {
                Map.Entry<String, Entry<V>> e = it.next();
                if (e.getValue().isExpired()) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                // 静默清理
            }
        } catch (Exception e) {
            // 后台线程不允许抛出异常
        }
    }

    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("ZCache is closed");
        }
    }

    // ==================== Builder ====================

    /**
     * 创建泛型缓存的 Builder。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    public static <K extends Serializable, V extends Serializable> Builder<K, V> newBuilder() {
        return new Builder<>();
    }

    /**
     * 快捷方法：创建一个默认配置的 StringZCache。
     * <p>
     * 等价于 {@code new StringZCache(10000, 60)}。
     */
    public static StringZCache newStringCache() {
        return new StringZCache(10000, 60);
    }

    /**
     * 快捷方法：创建一个自定义配置的 StringZCache。
     */
    public static StringZCache newStringCache(int maxSize, long cleanupIntervalSec) {
        return new StringZCache(maxSize, cleanupIntervalSec);
    }

    /**
     * 缓存条目内部表示。
     */
    private static class Entry<V> {
        final V value;
        final long expireAt; // -1 = 永不过期

        Entry(V value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return expireAt > 0 && System.currentTimeMillis() > expireAt;
        }
    }

    /**
     * Builder 用于配置 ZCache。
     */
    public static class Builder<K extends Serializable, V extends Serializable> {
        private int maxSize = 10_000;
        private long cleanupIntervalSec = 60;

        public Builder<K, V> maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /**
         * 后台清理间隔（秒）。设为 0 禁用后台清理（仅在访问时惰性清理）。
         */
        public Builder<K, V> cleanupIntervalSec(long seconds) {
            this.cleanupIntervalSec = seconds;
            return this;
        }

        public ZCache<K, V> build() {
            return new ZCache<>(maxSize, cleanupIntervalSec);
        }
    }
}
