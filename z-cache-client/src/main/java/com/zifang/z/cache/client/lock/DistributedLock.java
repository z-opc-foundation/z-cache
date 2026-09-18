package com.zifang.z.cache.client.lock;

/**
 * z-cache 分布式锁高层 API。
 *
 * <p>基于 Redis SET NX PX + Lua EVAL 实现，对标 Redis 6.2+ 官方推荐的
 * "单节点 Redlock 等价实现"（见 Redis 分布式锁指南）。
 *
 * <p>典型用法：
 * <pre>{@code
 * try (ZCacheClient client = new ZCacheClient("localhost", 16379)) {
 *     client.connect();
 *     DistributedLock locks = client.distributedLock();
 *
 *     // 场景 1：单次锁（非阻塞）
 *     Lock lock = locks.tryLock("order:123", 30_000); // TTL 30s
 *     if (lock != null) {
 *         try {
 *             doBusiness();
 *         } finally {
 *             locks.unlock(lock);
 *         }
 *     }
 *
 *     // 场景 2：带等待的锁（阻塞最多 3s）
 *     Lock lock2 = locks.tryLock("resource", 10_000, 3_000);
 *     if (lock2 != null) {
 *         try {
 *             doBusiness();
 *         } finally {
 *             locks.unlock(lock2);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author zifang
 * @since 1.3.0
 */
public interface DistributedLock extends AutoCloseable {

    /**
     * 尝试获取锁，立即返回（非阻塞）。
     *
     * @param key      锁的 key（任意字符串）
     * @param ttlMs    锁的 TTL（毫秒）。超过此时间未释放或续约，锁自动失效。
     *                 建议设置为业务最长执行时间的 2-3 倍。
     * @return 成功返回 Lock 句柄；key 已存在 / TTL 非法返回 null
     * @throws IllegalArgumentException ttlMs <= 0 或 key 为 null
     */
    Lock tryLock(String key, long ttlMs);

    /**
     * 尝试获取锁，最多阻塞等待 waitMs 毫秒。
     *
     * <p>等待期间会周期性重试获取锁（每 100ms 重试一次），
     * 直到成功获取或超时。
     *
     * @param key    锁的 key
     * @param ttlMs  锁的 TTL（毫秒）
     * @param waitMs 最长等待时间（毫秒）。&lt;= 0 等价于 {@link #tryLock(String, long)}
     * @return 成功返回 Lock 句柄；超时返回 null
     * @throws IllegalArgumentException ttlMs <= 0 或 key 为 null
     */
    Lock tryLock(String key, long ttlMs, long waitMs);

    /**
     * 释放锁。仅释放当前句柄持有的锁（ownerId 匹配），不误删他人锁。
     *
     * <p>使用 Lua 脚本保证原子性：先校验 ownerId，再 DEL。
     *
     * @param lock 通过 {@link #tryLock} 获取的句柄
     * @throws IllegalArgumentException lock 为 null
     * @throws LockReleaseException    服务端异常（网络中断、Lua 校验失败等）
     */
    void unlock(Lock lock) throws LockReleaseException;

    /**
     * 续约（延长 TTL）。仅当 lock 仍归属当前 ownerId 时成功。
     *
     * @param lock  锁句柄
     * @param ttlMs 新的 TTL（毫秒）。通常传入原 TTL 或其整数倍。
     * @return true 续约成功；false 锁已被他人持有 / TTL 已过期
     */
    boolean renew(Lock lock, long ttlMs);

    /**
     * 获取当前 key 锁的 ownerId（用于诊断）。
     *
     * @param key 锁的 key
     * @return ownerId（UUID 字符串），key 未被锁时返回 null
     */
    String currentOwner(String key);

    /**
     * 关闭分布式锁客户端，释放所有内部资源（包括 watchdog 线程）。
     */
    @Override
    void close();
}
