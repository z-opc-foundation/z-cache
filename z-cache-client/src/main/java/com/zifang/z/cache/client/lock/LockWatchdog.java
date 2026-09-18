package com.zifang.z.cache.client.lock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁 Watchdog —— 后台线程周期性续约，防止长任务期间 TTL 意外过期。
 *
 * <p>续约周期为 ttlMs / 3，确保在 TTL 过期前有足够余量。
 *
 * @author zifang
 * @since 1.3.0
 */
public class LockWatchdog implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(LockWatchdog.class);

    /**
     * 后台调度线程池（daemon 线程，不阻止 JVM 退出）。
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 每个 lock 句柄对应的续约任务。
     */
    private final ConcurrentHashMap<Lock, ScheduledFuture<?>> renewTasks;

    /**
     * 关闭标记，防止重复 close。
     */
    private volatile boolean closed = false;

    /**
     * 创建 Watchdog，使用指定的锁客户端进行续约。
     */
    public LockWatchdog() {
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "z-cache-lock-watchdog");
            t.setDaemon(true);
            return t;
        });
        this.renewTasks = new ConcurrentHashMap<>();
    }

    /**
     * 为指定锁句柄启动自动续约。
     *
     * <p>每 ttlMs / 3 毫秒尝试 renew 一次。renew 失败时仅打日志，不中断任务。
     * 调用 {@link #cancelRenew(Lock)} 可取消续约。
     *
     * @param lock      锁句柄
     * @param renewFunc 续约函数：lock -> boolean（true = 续约成功）
     */
    public void scheduleRenew(Lock lock, java.util.function.Function<Lock, Boolean> renewFunc) {
        if (closed) {
            return;
        }
        long interval = Math.max(lock.getTtlMs() / 3, 100); // 最小 100ms
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!renewFunc.apply(lock)) {
                    logger.warn("Watchdog renew returned false for key='{}', stopping watchdog", lock.getKey());
                    cancelRenew(lock);
                }
            } catch (Exception e) {
                logger.warn("Watchdog renew failed for key='{}': {}", lock.getKey(), e.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        renewTasks.put(lock, future);
    }

    /**
     * 取消指定锁句柄的续约任务。
     *
     * @param lock 锁句柄
     */
    public void cancelRenew(Lock lock) {
        ScheduledFuture<?> future = renewTasks.remove(lock);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 关闭 Watchdog，取消所有正在进行的续约任务并停止调度线程。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        renewTasks.values().forEach(f -> f.cancel(false));
        renewTasks.clear();
        scheduler.shutdownNow();
    }
}
