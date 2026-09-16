package com.zifang.z.cache.core.embedded;

import java.util.concurrent.TimeUnit;

/**
 * 基于字符串键值的嵌入式内存缓存，是 {@link ZCache}&lt;String, String&gt; 的特化包装。
 * <p>
 * 相比泛型版本提供了更简洁的 API（直接接受 String 参数，无需显式类型声明），
 * 适合绝大多数本地缓存场景。
 * <p>
 * 用法示例:
 * <pre>
 * StringZCache cache = ZCache.newStringCache();  // 默认 10000 条目, 60s 清理
 *
 * cache.set("user:1", "张三");
 * cache.set("token:abc", "xyz", 30, TimeUnit.MINUTES);
 *
 * String name = cache.get("user:1");          // "张三"
 * boolean hit = cache.exists("user:1");        // true
 * long remaining = cache.ttl("token:abc");    // 剩余秒数
 *
 * cache.delete("user:1");
 * cache.close();
 * </pre>
 * <p>
 * 线程安全：所有公开方法均线程安全。
 */
public class StringZCache implements AutoCloseable {

    private final ZCache<String, String> delegate;

    StringZCache(int maxSize, long cleanupIntervalSec) {
        this.delegate = new ZCache<>(maxSize, cleanupIntervalSec);
    }

    // ==================== Get / Set ====================

    /**
     * 获取键对应的值，未命中或已过期返回 null。
     */
    public String get(String key) {
        return delegate.get(key);
    }

    /**
     * 写入键值对，永不过期。
     */
    public void set(String key, String value) {
        delegate.set(key, value);
    }

    /**
     * 写入键值对，指定过期时间。
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时长
     * @param unit  时间单位
     */
    public void set(String key, String value, long ttl, TimeUnit unit) {
        delegate.set(key, value, ttl, unit);
    }

    /**
     * 写入键值对，指定过期秒数（便捷方法，等价于 set(key, value, seconds, SECONDS)）。
     */
    public void set(String key, String value, long ttlSeconds) {
        delegate.set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 仅在键不存在时写入（NX 语义）。
     *
     * @return true 如果写入成功
     */
    public boolean setIfAbsent(String key, String value) {
        return delegate.setIfAbsent(key, value);
    }

    /**
     * 仅在键不存在时写入，带 TTL。
     */
    public boolean setIfAbsent(String key, String value, long ttl, TimeUnit unit) {
        return delegate.setIfAbsent(key, value, ttl, unit);
    }

    // ==================== Delete / Exists ====================

    /**
     * 删除键。
     *
     * @return true 如果键存在且删除成功
     */
    public boolean delete(String key) {
        return delegate.delete(key);
    }

    /**
     * 批量删除。
     *
     * @return 成功删除的数量
     */
    public long delete(String... keys) {
        return delegate.delete(keys);
    }

    /**
     * 判断键是否存在（未过期）。
     */
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    // ==================== TTL / Expire / Persist ====================

    /**
     * 获取键的剩余生存时间（秒）。
     *
     * @return -1 表示永不过期；-2 表示键不存在
     */
    public long ttl(String key) {
        return delegate.ttl(key);
    }

    /**
     * 设置键的过期时间（秒）。
     *
     * @return true 如果设置成功
     */
    public boolean expire(String key, long seconds) {
        return delegate.expire(key, seconds);
    }

    /**
     * 移除键的过期时间，使其永不过期。
     *
     * @return true 如果成功移除
     */
    public boolean persist(String key) {
        return delegate.persist(key);
    }

    // ==================== Maintenance ====================

    /**
     * 获取缓存中的有效条目数量。
     */
    public long size() {
        return delegate.size();
    }

    /**
     * 清空所有数据。
     */
    public void clear() {
        delegate.clear();
    }

    /**
     * 清空所有数据并重置统计。
     */
    public void flush() {
        delegate.flush();
    }

    // ==================== 统计 ====================

    /**
     * 缓存命中次数。
     */
    public long getHitCount() {
        return delegate.getHitCount();
    }

    /**
     * 缓存未命中次数。
     */
    public long getMissCount() {
        return delegate.getMissCount();
    }

    /**
     * 缓存命中率 (0.0 ~ 1.0)。
     */
    public double getHitRate() {
        return delegate.getHitRate();
    }

    // ==================== Lifecycle ====================

    /**
     * 关闭缓存，停止后台清理线程。
     */
    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 缓存是否已关闭。
     */
    public boolean isClosed() {
        return delegate.isClosed();
    }

    /**
     * 创建自定义配置的 StringZCache Builder。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * StringZCache 的 Builder。
     */
    public static class Builder {
        private int maxSize = 10_000;
        private long cleanupIntervalSec = 60;

        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /**
         * 后台清理间隔（秒）。设为 0 禁用后台清理。
         */
        public Builder cleanupIntervalSec(long seconds) {
            this.cleanupIntervalSec = seconds;
            return this;
        }

        public StringZCache build() {
            return new StringZCache(maxSize, cleanupIntervalSec);
        }
    }
}
