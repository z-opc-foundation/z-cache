package com.zifang.z.cache.client.lock;

import java.util.Objects;

/**
 * 分布式锁句柄。线程不安全（每个线程应持有自己的实例）。
 *
 * <p>字段：
 * <ul>
 *   <li>key —— 锁的 key</li>
 *   <li>ownerId —— 持有者唯一标识（UUID），用于 unlock/renew 时身份校验</li>
 *   <li>fencingToken —— 单调递增令牌（Long），用于业务侧防"双写"</li>
 *   <li>acquiredAt —— 获取时间（毫秒时间戳）</li>
 *   <li>ttlMs —— 原始 TTL</li>
 * </ul>
 *
 * @author zifang
 * @since 1.3.0
 */
public final class Lock {

    private final String key;
    private final String ownerId;
    private final long fencingToken;
    private final long acquiredAt;
    private final long ttlMs;

    /**
     * 创建一个锁句柄。
     *
     * @param key          锁的 key
     * @param ownerId      持有者唯一标识（UUID）
     * @param fencingToken 单调递增令牌
     * @param ttlMs        锁的 TTL（毫秒）
     */
    public Lock(String key, String ownerId, long fencingToken, long ttlMs) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
        this.fencingToken = fencingToken;
        this.acquiredAt = System.currentTimeMillis();
        this.ttlMs = ttlMs;
    }

    /**
     * 锁的 key。
     */
    public String getKey() {
        return key;
    }

    /**
     * 持有者唯一标识（UUID）。
     */
    public String getOwnerId() {
        return ownerId;
    }

    /**
     * 单调递增令牌（client 端，不跨进程）。
     * 业务侧可在 DB UPDATE 时使用 fencing_token < ? 防双写。
     */
    public long getFencingToken() {
        return fencingToken;
    }

    /**
     * 锁获取时间戳（毫秒）。
     */
    public long getAcquiredAt() {
        return acquiredAt;
    }

    /**
     * 原始 TTL（毫秒）。
     */
    public long getTtlMs() {
        return ttlMs;
    }

    @Override
    public String toString() {
        return "Lock{key='" + key + "', ownerId='" + ownerId
                + "', fencingToken=" + fencingToken
                + ", acquiredAt=" + acquiredAt + ", ttlMs=" + ttlMs + '}';
    }
}
