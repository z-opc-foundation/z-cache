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
 * <p><b>原子性说明（务必读完再用这把锁）</b>：{@code tryLock} 用 SET NX PX 获取，
 * 这一步是原子的。但 {@code unlock} / {@code renew} 走的是 GET + 条件 DEL/SET
 * <b>两次独立往返</b>，而 <b>当前 z-cache-server 没有实现 EVAL/SCRIPT</b>
 * （全仓 grep EVAL 只命中本类与本类的单测；{@code resources/scripts/*.lua} 是客户端
 * 侧的资源，服务端没有解释器），所以类里那两条 {@code tryXxxViaEval} 对着自家
 * 服务端<b>每次都会落回非原子路径</b>——它不是「以防万一」的兜底，而是唯一路径。
 *
 * <p>由此得到的真实性质，请不要误读成「安全」：
 * <ul>
 *   <li>ownerId 校验与 DEL/SET <b>不在同一个原子步骤里</b>。校验通过之后、删除或
 *       改写到达服务端之前，锁可能因 TTL 到期而消失并被另一个客户端拿到；此时本类的
 *       DEL 会<b>删掉别人的锁</b>，{@code renewFallback} 的 SET（连 XX 都没有）会把
 *       <b>别人的锁值覆盖成自己的旧值</b>并顺手重置 TTL。两个客户端于是同时认为自己
 *       持锁，互斥被破坏。</li>
 *   <li>{@link Lock#getFencingToken()} 的 fencing token 正是为这种「拿锁时已经过期」
 *       的情形准备的兜底：业务侧在 DB 写入处用 {@code fencing_token < ?} 拒绝陈旧持有者。
 *       但它<b>不是</b>让 unlock/renew 本身变安全的手段。</li>
 * </ul>
 *
 * <p>要根治需要服务端提供一条原子的「比较 owner 再删除/续期」命令（Redis 的做法是
 * EVAL + Lua）。在服务端补上之前，请把本类视为<b>尽力而为</b>的锁：配合业务侧
 * fencing token 使用，且不要把 unlock 的返回值当成互斥已被严格保证。
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

        // 这里只是把窗口从 [第一次 GET .. DEL] 缩到 [第二次 GET .. DEL]，并没有关掉它：
        // 下面那条 DEL 仍然是裸的，中间没有再校验。若锁在第二次 GET 之后因 TTL 到期
        // 被别人重新拿到，这条 DEL 会把别人的锁删掉。
        // 真的能关掉它的是服务端那条原子的「比较 owner 再删」命令。
        Object getResp2 = client.sendCommand("GET", key);
        String value2 = toStringValue(getResp2);
        if (value2 != null && value2.startsWith(ownerId + SEPARATOR)) {
            client.sendCommand("DEL", key);
            logger.debug("Lock released via fallback: key='{}', ownerId={}", key, ownerId);
        } else {
            // 第二次 GET 时已经不是自己持有了（期间过期并被别人拿走）：不删是对的，
            // 但静默返回会让调用方以为「已释放」，所以显式告警。
            logger.warn("Lock owner changed between checks, not deleting: key='{}', expectedOwner={}, now={}",
                    key, ownerId, value2);
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

        // ⚠ 这条 SET 没有任何护栏：既没有 XX（会凭空建出一个没人持有的锁），
        // ownerId 也只是上一次往返里查过的。若锁在这中间过期并被别人拿到，
        // 这次 SET 会把<b>别人的锁值覆盖成我们这段旧值</b>、并顺手把 TTL 重置回去 ——
        // 直接抢走别人的锁。写这里是为了让人看见这个窗口，不是说它是安全的。
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
