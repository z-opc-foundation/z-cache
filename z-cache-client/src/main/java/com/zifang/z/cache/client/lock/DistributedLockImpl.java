package com.zifang.z.cache.client.lock;

import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.common.protocol.RespBulkString;
import com.zifang.z.cache.common.protocol.RespInteger;
import com.zifang.z.cache.common.protocol.RespSimpleString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DistributedLock 的默认实现。
 *
 * <p>基于 {@link ZCacheClient} 的 SET NX PX + GET/DEL 实现分布式锁。
 *
 * <p><b>原子性说明</b>：tryLock 使用 SET NX PX 保证原子获取。
 * unlock / renew 在 1.3.0 采用 GET + 条件 DEL/SET 两步操作
 * （非原子），依赖 ownerId 校验保证安全性。当 z-cache-server
 * 支持 EVAL/LUA 后可升级为原子 Lua 脚本（脚本已预置于 resources/scripts/）。
 *
 * @author zifang
 * @since 1.3.0
 * @see DistributedLock
 * @see Lock
 */
public class DistributedLockImpl implements DistributedLock {

    private static final Logger logger = LogManager.getLogger(DistributedLockImpl.class);

    /** value 分隔符：ownerId|fencingToken */
    private static final String SEPARATOR = "|";

    /** fencing token 全局单调递增计数器（client 端，不跨进程） */
    private static final AtomicLong FENCING_TOKEN = new AtomicLong(0);

    /** 持有锁的客户端实例 */
    private final ZCacheClient client;

    /** Watchdog 自动续约器 */
    private final LockWatchdog watchdog;

    /** 已获取的活跃锁映射（用于 unlock 时取消 watchdog） */
    private final ConcurrentHashMap<String, Lock> activeLocks;

    /**
     * 创建 DistributedLockImpl 实例。
     *
     * @param client 已连接的 ZCacheClient 实例
     */
    public DistributedLockImpl(ZCacheClient client) {
        this.client = client;
        this.watchdog = new LockWatchdog();
        this.activeLocks = new ConcurrentHashMap<>();
    }

    @Override
    public Lock tryLock(String key, long ttlMs) {
        validateArgs(key, ttlMs);
        String ownerId = UUID.randomUUID().toString();
        long fencingToken = FENCING_TOKEN.incrementAndGet();
        String value = ownerId + SEPARATOR + fencingToken;

        Object resp = client.sendCommand("SET", key, value, "NX", "PX", String.valueOf(ttlMs));
        if (isOk(resp)) {
            Lock lock = new Lock(key, ownerId, fencingToken, ttlMs);
            activeLocks.put(key, lock);
            watchdog.scheduleRenew(lock, this::doRenew);
            logger.debug("Lock acquired: key='{}', ownerId={}", key, ownerId);
            return lock;
        }
        logger.debug("Lock contention: key='{}'", key);
        return null;
    }

    @Override
    public Lock tryLock(String key, long ttlMs, long waitMs) {
        validateArgs(key, ttlMs);
        if (waitMs <= 0) {
            return tryLock(key, ttlMs);
        }

        long deadline = System.currentTimeMillis() + waitMs;
        long retryInterval = Math.min(100, waitMs); // 每 100ms 重试，不超过 waitMs

        while (System.currentTimeMillis() < deadline) {
            Lock lock = tryLock(key, ttlMs);
            if (lock != null) {
                return lock;
            }
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Thread.sleep(Math.min(retryInterval, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Lock wait interrupted: key='{}'", key);
                break;
            }
        }
        return null;
    }

    @Override
    public void unlock(Lock lock) throws LockReleaseException {
        if (lock == null) {
            throw new IllegalArgumentException("lock must not be null");
        }
        String key = lock.getKey();
        String ownerId = lock.getOwnerId();

        // 取消 watchdog 续约
        watchdog.cancelRenew(lock);
        activeLocks.remove(key);

        // 尝试 EVAL（服务端支持时使用原子 Lua 脚本）
        long result = tryUnlockViaEval(lock);
        if (result >= 0) {
            handleUnlockResult(key, ownerId, result);
            return;
        }

        // Fallback：GET + 条件 DEL（非原子，但 ownerId 校验保证安全性）
        unlockFallback(key, ownerId);
    }

    @Override
    public boolean renew(Lock lock, long ttlMs) {
        if (lock == null) {
            throw new IllegalArgumentException("lock must not be null");
        }
        String key = lock.getKey();
        String ownerId = lock.getOwnerId();

        // 尝试 EVAL（服务端支持时使用原子 Lua 脚本）
        long evalResult = tryRenewViaEval(lock, ttlMs);
        if (evalResult >= 0) {
            return evalResult == 1;
        }

        // Fallback：GET + 条件 SET PX
        return renewFallback(key, ownerId, lock, ttlMs);
    }

    @Override
    public String currentOwner(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        Object resp = client.sendCommand("GET", key);
        String value = toStringValue(resp);
        if (value == null) {
            return null;
        }
        int sepIdx = value.indexOf(SEPARATOR);
        if (sepIdx <= 0) {
            return null;
        }
        return value.substring(0, sepIdx);
    }

    @Override
    public void close() {
        watchdog.close();
        activeLocks.clear();
        logger.debug("DistributedLockImpl closed");
    }

    // ==================== 内部方法 ====================

    /**
     * 参数校验。
     */
    private void validateArgs(String key, long ttlMs) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be > 0, got " + ttlMs);
        }
    }

    /**
     * 判断 RESP 响应是否为 OK。
     */
    private boolean isOk(Object resp) {
        if (resp == null) {
            return false;
        }
        if (resp instanceof RespSimpleString) {
            return "OK".equals(((RespSimpleString) resp).getValue());
        }
        if (resp instanceof RespBulkString) {
            return "OK".equals(((RespBulkString) resp).getString());
        }
        return "OK".equals(String.valueOf(resp));
    }

    /**
     * 将 RESP 响应转换为字符串值。
     */
    private String toStringValue(Object resp) {
        if (resp == null) {
            return null;
        }
        if (resp instanceof RespBulkString) {
            return ((RespBulkString) resp).getString();
        }
        if (resp instanceof RespSimpleString) {
            return ((RespSimpleString) resp).getValue();
        }
        return resp.toString();
    }

    /**
     * 将 RESP 响应转换为 long 值。
     */
    private long toLongValue(Object resp) throws LockReleaseException {
        if (resp == null) {
            throw new LockReleaseException("null response from server");
        }
        if (resp instanceof RespInteger) {
            return ((RespInteger) resp).getValue();
        }
        if (resp instanceof Number) {
            return ((Number) resp).longValue();
        }
        return Long.parseLong(String.valueOf(resp));
    }

    /**
     * 尝试通过 EVALSHA/EVAL 原子解锁（服务端支持时）。
     *
     * @return >= 0 表示 EVAL 成功执行，< 0 表示服务端不支持 EVAL
     */
    private long tryUnlockViaEval(Lock lock) {
        try {
            Object resp = client.sendCommand(
                    "EVAL", getUnlockScript(), "1",
                    lock.getKey(), lock.getOwnerId(), String.valueOf(lock.getFencingToken())
            );
            return toLongValue(resp);
        } catch (Exception e) {
            // EVAL 命令不支持，fallback
            logger.debug("EVAL unlock not available, using fallback: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 尝试通过 EVAL 原子续约（服务端支持时）。
     *
     * @return >= 0 表示 EVAL 成功执行，< 0 表示服务端不支持 EVAL
     */
    private long tryRenewViaEval(Lock lock, long ttlMs) {
        try {
            Object resp = client.sendCommand(
                    "EVAL", getRenewScript(), "1",
                    lock.getKey(), lock.getOwnerId(), String.valueOf(ttlMs)
            );
            return toLongValue(resp);
        } catch (Exception e) {
            logger.debug("EVAL renew not available, using fallback: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * unlock Fallback：GET + 条件 DEL。
     */
    private void unlockFallback(String key, String ownerId) throws LockReleaseException {
        Object getResp = client.sendCommand("GET", key);
        String value = toStringValue(getResp);

        if (value == null) {
            // 锁不存在（已过期或从未持有），视为正常释放
            logger.debug("Lock already expired during unlock: key='{}'", key);
            return;
        }

        int sepIdx = value.indexOf(SEPARATOR);
        if (sepIdx <= 0) {
            throw new LockReleaseException("Lock value format invalid: key='" + key + "'");
        }

        String curOwner = value.substring(0, sepIdx);
        if (!ownerId.equals(curOwner)) {
            throw new LockReleaseException(
                    "Cannot unlock: lock held by another owner. key='" + key + "'");
        }

        // 双重检查：再次 GET 确认 ownerId 仍匹配后 DEL
        Object getResp2 = client.sendCommand("GET", key);
        String value2 = toStringValue(getResp2);
        if (value2 != null && value2.startsWith(ownerId + SEPARATOR)) {
            client.sendCommand("DEL", key);
            logger.debug("Lock released via fallback: key='{}', ownerId={}", key, ownerId);
        }
    }

    /**
     * unlock 结果处理（EVAL 成功时调用）。
     */
    private void handleUnlockResult(String key, String ownerId, long result) throws LockReleaseException {
        if (result == 1) {
            logger.debug("Lock released via EVAL: key='{}', ownerId={}", key, ownerId);
            return;
        }
        String reason;
        switch ((int) result) {
            case -1:
                reason = "lock does not exist";
                break;
            case -2:
                reason = "lock value format invalid";
                break;
            case -3:
                reason = "not the lock owner";
                break;
            case -4:
                reason = "fencing token expired";
                break;
            default:
                reason = "unknown error code=" + result;
        }
        throw new LockReleaseException("unlock failed: " + reason + ", key='" + key + "'");
    }

    /**
     * renew Fallback：GET + 条件 SET PX。
     */
    private boolean renewFallback(String key, String ownerId, Lock lock, long ttlMs) {
        Object getResp = client.sendCommand("GET", key);
        String value = toStringValue(getResp);

        if (value == null) {
            return false;
        }

        int sepIdx = value.indexOf(SEPARATOR);
        if (sepIdx <= 0) {
            return false;
        }

        String curOwner = value.substring(0, sepIdx);
        if (!ownerId.equals(curOwner)) {
            return false;
        }

        // SET key value PX（保留原 value，只延长 TTL）
        Object setResp = client.sendCommand("SET", key, value, "PX", String.valueOf(ttlMs));
        if (isOk(setResp)) {
            logger.debug("Lock renewed via fallback: key='{}', ttlMs={}", key, ttlMs);
            return true;
        }
        return false;
    }

    /**
     * Watchdog 续约回调。
     */
    private boolean doRenew(Lock lock) {
        return renew(lock, lock.getTtlMs());
    }

    // ==================== Lua 脚本加载 ====================

    /** unlock.lua 脚本内容（静态加载，避免重复 IO） */
    private static volatile String unlockScript;

    /** renew.lua 脚本内容 */
    private static volatile String renewScript;

    private static String getUnlockScript() {
        if (unlockScript == null) {
            synchronized (DistributedLockImpl.class) {
                if (unlockScript == null) {
                    unlockScript = loadScript("scripts/unlock.lua");
                }
            }
        }
        return unlockScript;
    }

    private static String getRenewScript() {
        if (renewScript == null) {
            synchronized (DistributedLockImpl.class) {
                if (renewScript == null) {
                    renewScript = loadScript("scripts/renew.lua");
                }
            }
        }
        return renewScript;
    }

    /**
     * 从 classpath 加载 Lua 脚本文件。
     */
    private static String loadScript(String resourcePath) {
        try (java.io.InputStream is = DistributedLockImpl.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Lua script not found: " + resourcePath);
            }
            byte[] bytes = new byte[is.available()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = is.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load Lua script: " + resourcePath, e);
        }
    }
}
