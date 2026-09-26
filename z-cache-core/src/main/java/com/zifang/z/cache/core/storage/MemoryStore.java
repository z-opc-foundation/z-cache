package com.zifang.z.cache.core.storage;

import com.zifang.z.cache.common.protocol.RedisDoubleFormat;
import com.zifang.z.cache.common.protocol.RedisIntegerFormat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多类型内存存储中心。
 * <p>
 * 统一管理 String、Hash、List、Set、Sorted Set 五种数据结构，
 * 支持多数据库（0-15）、TTL 过期、LRU 淘汰策略、SCAN 迭代器。
 * 所有写操作通过 {@code synchronized} 保证原子性。
 * </p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class MemoryStore {

    // ==================== 数据类型枚举 ====================

    /** 数据类型标识 */
    public enum DataType {
        NONE, STRING, HASH, LIST, SET, ZSET
    }

    // ==================== 多数据库支持 ====================

    /** 默认数据库数量 */
    public static final int DEFAULT_DB_COUNT = 16;

    /** 每个数据库的 String 存储 */
    private final Map<String, ValueWrapper>[] stringStores;

    /** 每个数据库的 Hash 存储 */
    private final HashStore[] hashStores;

    /** 每个数据库的 List 存储 */
    private final ListStore[] listStores;

    /** 每个数据库的 Set 存储 */
    private final SetStore[] setStores;

    /** 每个数据库的 SortedSet 存储 */
    private final SortedSetStore[] sortedSetStores;

    /** 键类型映射: key -> DataType (每个 DB 独立) */
    @SuppressWarnings("unchecked")
    private final ConcurrentHashMap<String, DataType>[] keyTypeMaps;

    /** 数据库数量 */
    private final int dbCount;

    // ==================== 统计信息 ====================

    /** 最大键数量，0 表示不限制 */
    private final int maxEntries;

    /** 淘汰的键数量 */
    private final AtomicLong evictions = new AtomicLong(0);

    /** 缓存命中次数 */
    private final AtomicLong hits = new AtomicLong(0);

    /** 缓存未命中次数 */
    private final AtomicLong misses = new AtomicLong(0);

    /** 总命令数 */
    private final AtomicLong totalCommands = new AtomicLong(0);

    /** 总连接数 */
    private final AtomicLong totalConnections = new AtomicLong(0);

    /** 启动时间 */
    private final long startTime = System.currentTimeMillis();

    private final Random random = new Random();

    // ==================== 构造函数 ====================

    public MemoryStore() {
        this(0);
    }

    public MemoryStore(int maxEntries) {
        this(maxEntries, DEFAULT_DB_COUNT);
    }

    @SuppressWarnings("unchecked")
    public MemoryStore(int maxEntries, int dbCount) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("maxEntries cannot be negative");
        }
        if (dbCount < 1 || dbCount > 16) {
            throw new IllegalArgumentException("dbCount must be between 1 and 16");
        }
        this.maxEntries = maxEntries;
        this.dbCount = dbCount;
        this.stringStores = new Map[dbCount];
        this.hashStores = new HashStore[dbCount];
        this.listStores = new ListStore[dbCount];
        this.setStores = new SetStore[dbCount];
        this.sortedSetStores = new SortedSetStore[dbCount];
        this.keyTypeMaps = new ConcurrentHashMap[dbCount];
        for (int i = 0; i < dbCount; i++) {
            stringStores[i] = new ConcurrentHashMap<>();
            hashStores[i] = new HashStore();
            listStores[i] = new ListStore();
            setStores[i] = new SetStore();
            sortedSetStores[i] = new SortedSetStore();
            keyTypeMaps[i] = new ConcurrentHashMap<>();
        }
    }

    // ==================== 多 DB 访问 ====================

    public HashStore getHashStore(int db) { return hashStores[db]; }
    public ListStore getListStore(int db) { return listStores[db]; }
    public SetStore getSetStore(int db) { return setStores[db]; }
    public SortedSetStore getSortedSetStore(int db) { return sortedSetStores[db]; }

    public Map<String, ValueWrapper> getStringStore(int db) { return stringStores[db]; }

    // ==================== 类型管理 ====================

    /**
     * 获取指定 DB 中键的数据类型。
     */
    public DataType getKeyType(String key, int db) {
        DataType type = keyTypeMaps[db].get(key);
        if (type == null) {
            // 检查旧的 string store 兼容
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper != null && !wrapper.isExpired()) {
                return DataType.STRING;
            }
            return DataType.NONE;
        }
        return type;
    }

    /**
     * 设置键的数据类型。
     */
    public void setKeyType(String key, DataType type, int db) {
        keyTypeMaps[db].put(key, type);
    }

    /**
     * 检查键是否属于给定类型，不存在则返回 NONE。
     */
    public DataType checkKeyType(String key, int db) {
        DataType type = keyTypeMaps[db].get(key);
        if (type == null) {
            return DataType.NONE;
        }
        // 惰性删除过期检查
        if (type == DataType.STRING) {
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper != null && wrapper.isExpired()) {
                stringStores[db].remove(key);
                keyTypeMaps[db].remove(key);
                return DataType.NONE;
            }
        }
        return type;
    }

    /**
     * 这个键当前真正是哪一种类型 —— 以五个 store 里有没有它为准。
     * <p>
     * 不读 {@code keyTypeMaps}：那张表只有 String 写入路径（{@code setKeyType}）维护过，
     * 集合类型从来没登记，所以它对"这是个 hash"永远说 NONE。EXISTS / TYPE / 类型闸门
     * 现在共用这一把尺，三者不可能再互相打脸。
     */
    public DataType typeOfDb(int db, String key) {
        if (key == null) {
            return DataType.NONE;
        }
        ValueWrapper wrapper = stringStores[db].get(key);
        if (wrapper != null) {
            if (wrapper.isExpired()) {
                stringStores[db].remove(key);
            } else {
                return DataType.STRING;
            }
        }
        if (hashStores[db].exists(key)) return DataType.HASH;
        if (listStores[db].exists(key)) return DataType.LIST;
        if (setStores[db].exists(key)) return DataType.SET;
        if (sortedSetStores[db].exists(key)) return DataType.ZSET;
        return DataType.NONE;
    }

    // ==================== String 操作 (保持向后兼容) ====================

    public boolean set(String key, byte[] value) {
        return setDb(0, key, value);
    }

    public boolean setDb(int db, String key, byte[] value) {
        putDb(db, key, new ValueWrapper(value == null ? null : value.clone(), -1));
        setKeyType(key, DataType.STRING, db);
        return true;
    }

    public boolean setex(String key, long seconds, byte[] value) {
        return setexDb(0, key, seconds, value);
    }

    /**
     * 秒数栏收 {@code long} 而不是 {@code int}：实测 {@code SET k v EX 4000000000} 在参考实现里
     * 回 {@code +OK} 且 {@code TTL} 就是 4000000000，用 int 接会在语法这一档就把合法输入判死
     * （battery37 第 56/57 行）。调用方负责先挡掉"乘一千会溢出"的那一段。
     */
    public boolean setexDb(int db, String key, long seconds, byte[] value) {
        long expireAt = saturatingExpireAt(TimeUnit.SECONDS.toMillis(seconds));
        putDb(db, key, new ValueWrapper(value == null ? null : value.clone(), expireAt));
        setKeyType(key, DataType.STRING, db);
        return true;
    }

    public boolean psetex(String key, long milliseconds, byte[] value) {
        return psetexDb(0, key, milliseconds, value);
    }

    public boolean psetexDb(int db, String key, long milliseconds, byte[] value) {
        long expireAt = saturatingExpireAt(milliseconds);
        putDb(db, key, new ValueWrapper(value == null ? null : value.clone(), expireAt));
        setKeyType(key, DataType.STRING, db);
        return true;
    }

    /**
     * 相对量折成绝对时刻，加不出正数就贴顶。
     * <p>
     * 绕回在参考实现里是实打实的事故：{@code SET k v EX 9223372036854776} 绕出一个<b>过去</b>
     * 的时刻，于是"设置一个远未来的过期"这条命令当场把键删了（250 实测 battery35 第 6/7 行：
     * 回 {@code +OK} 而 TTL 是 0）。命令层已经按 {@code Long.MAX_VALUE/1000} 挡掉了会乘溢出的
     * 那一档，这里挡的是"乘得出来、加不上现在"的窄缝 —— 贴顶的意思是"永不到期"，
     * 而不是把一次成功的回复变成一次删除。
     */
    private static long saturatingExpireAt(long relativeMillis) {
        long now = System.currentTimeMillis();
        return relativeMillis > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + relativeMillis;
    }

    public boolean setIfAbsent(String key, byte[] value) {
        return setIfAbsentDb(0, key, value);
    }

    public boolean setIfAbsentDb(int db, String key, byte[] value) {
        synchronized (stringStores[db]) {
            ValueWrapper current = getLiveWrapper(db, key);
            if (current != null) {
                return false;
            }
            putDb(db, key, new ValueWrapper(copy(value), -1));
            setKeyType(key, DataType.STRING, db);
            return true;
        }
    }

    public byte[] getAndSet(String key, byte[] value) {
        return getAndSetDb(0, key, value);
    }

    public byte[] getAndSetDb(int db, String key, byte[] value) {
        synchronized (stringStores[db]) {
            ValueWrapper current = getLiveWrapper(db, key);
            putDb(db, key, new ValueWrapper(copy(value), -1));
            setKeyType(key, DataType.STRING, db);
            return current == null ? null : copy(current.data);
        }
    }

    public long append(String key, byte[] suffix) {
        return appendDb(0, key, suffix);
    }

    /**
     * SETBIT 的落盘：把第 {@code bitIndex} 位（字节内<b>从高位数起</b>，与 Redis 的编号一致）
     * 写成 {@code on}，串不够长就先撑长、中间一律补零；回的是<b>改之前的那一位</b>。
     * <p>
     * 读-改-写在同一个 {@code synchronized} 块里做完：如果改成"handler 先 {@code getDb} 拿副本、
     * 改完再 {@code setDb} 写回"，两条并发 SETBIT 会互相吞掉对方置上的那位（丢更新），
     * 而且每次都要整串复制一遍。TTL 与 {@link #appendDb} 同一条路：保留原 {@code expireAt}，
     * 只有键本来不在时才新建。
     */
    public long setbitDb(int db, String key, long bitIndex, boolean on) {
        int byteIndex = (int) (bitIndex >>> 3);
        int mask = 1 << (7 - (int) (bitIndex & 7));
        synchronized (stringStores[db]) {
            ValueWrapper current = getLiveWrapper(db, key);
            byte[] prefix = current == null || current.data == null ? new byte[0] : current.data;
            long previous = byteIndex < prefix.length && (prefix[byteIndex] & mask) != 0 ? 1 : 0;
            byte[] value = byteIndex < prefix.length ? prefix.clone() : Arrays.copyOf(prefix, byteIndex + 1);
            if (on) value[byteIndex] |= (byte) mask;
            else value[byteIndex] &= (byte) ~mask;
            putDb(db, key, new ValueWrapper(value, current == null ? -1 : current.expireAt));
            setKeyType(key, DataType.STRING, db);
            return previous;
        }
    }

    /**
     * GETBIT 的读法：只碰目标位所在的那<b>一个字节</b>，不把整串复制回来 ——
     * {@link #getDb} 每次 {@code clone()}，而位偏移的合法区间大到能把串撑到 512MB，
     * 那时读一个位的代价是几亿字节。串尾右边的位按 Redis 的口径回 0（实测
     * {@code GETBIT <5 字节的串> 40} 是 0 而不是错），键不在、键已过期同样是 0。
     */
    public int getbitDb(int db, String key, long bitIndex) {
        int byteIndex = (int) (bitIndex >>> 3);
        int mask = 1 << (7 - (int) (bitIndex & 7));
        synchronized (stringStores[db]) {
            ValueWrapper wrapper = getLiveWrapper(db, key);
            if (wrapper == null || wrapper.data == null || byteIndex >= wrapper.data.length) {
                misses.incrementAndGet();
                return 0;
            }
            wrapper.touch();
            hits.incrementAndGet();
            return (wrapper.data[byteIndex] & mask) != 0 ? 1 : 0;
        }
    }

    public long appendDb(int db, String key, byte[] suffix) {
        synchronized (stringStores[db]) {
            ValueWrapper current = getLiveWrapper(db, key);
            byte[] prefix = current == null || current.data == null ? new byte[0] : current.data;
            byte[] value = suffix == null ? prefix.clone() : Arrays.copyOf(prefix, prefix.length + suffix.length);
            if (suffix != null) {
                System.arraycopy(suffix, 0, value, prefix.length, suffix.length);
            }
            putDb(db, key, new ValueWrapper(value, current == null ? -1 : current.expireAt));
            setKeyType(key, DataType.STRING, db);
            return value.length;
        }
    }

    public long increment(String key, long delta) {
        return incrementDb(0, key, delta);
    }

    public long incrementDb(int db, String key, long delta) {
        synchronized (stringStores[db]) {
            ValueWrapper current = getLiveWrapper(db, key);
            long value = 0;
            if (current != null && current.data != null) {
                // 键里存的这串也是客户端给进来的文本，判据必须与 INCRBY 的增量栏同源：
                // Long.parseLong 收 05 / +5 / -0，而参考实现 SET k 05 之后 INCR k 是拒的
                // （两处都走 string2ll）。
                Long parsed = RedisIntegerFormat.parse(new String(current.data, StandardCharsets.UTF_8));
                if (parsed == null) {
                    throw new IllegalArgumentException("value is not an integer or out of range");
                }
                value = parsed.longValue();
            }
            final long result;
            try {
                result = Math.addExact(value, delta);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("increment or decrement would overflow", e);
            }
            putDb(db, key, new ValueWrapper(Long.toString(result).getBytes(StandardCharsets.UTF_8),
                    current == null ? -1 : current.expireAt));
            setKeyType(key, DataType.STRING, db);
            return result;
        }
    }

    /**
     * 参考实现里字符串的上限就是协议里 bulk 的上限，实测边界在两侧都量到过：
     * {@code SETRANGE k 400000000 y} 成功（400000001 字节），
     * {@code SETRANGE k 600000000 x} 回 {@code string exceeds maximum allowed size (512MB)}。
     */
    public static final long MAX_STRING_LENGTH = 512L * 1024 * 1024;

    /**
     * 从 {@code offset} 起覆盖写入，越出现有长度的部分用 {@code \0} 补齐（SETRange 的补齐
     * 语义，实测 {@code SETRANGE s 5 World} 之后 {@code STRLEN} 回 10）。
     * <p>
     * TTL 与原值一起留着：这条走的是"改一个已存在的键"，不是 {@code SET} 的整键覆盖
     * （{@code appendDb} / {@code incrementDb} 同规矩）。
     *
     * @throws IllegalArgumentException 结果长度越过 {@link #MAX_STRING_LENGTH}
     */
    public long setRangeDb(int db, String key, long offset, byte[] replacement) {
        if (key == null || offset < 0) {
            throw new IllegalArgumentException("offset is out of range");
        }
        byte[] suffix = replacement == null ? new byte[0] : replacement;
        synchronized (stringStores[db]) {
            ValueWrapper current = getLiveWrapper(db, key);
            byte[] existing = current == null || current.data == null ? new byte[0] : current.data;
            if (suffix.length == 0) {
                // 空替换不改一个字节：回现长，且不给不存在的键凭空建键
                // （这一支是从 redis 4.0 setrangeCommand 的 sdslen(value)==0 分支读来的，
                //  对拍脚本传不出空值参数，未在 250 上实测）
                return existing.length;
            }
            // 减法而不是加法：offset 可能带进 Long.MAX_VALUE，offset+length 会绕回负数，
            // 那一支在参考实现里真的把长度检查绕过去了（实测 SETRANGE k 9223372036854775807 x
            // 直接让 redis-server 4.0.9 段错误退出）。
            if (offset >= MAX_STRING_LENGTH || suffix.length > MAX_STRING_LENGTH - offset) {
                throw new IllegalArgumentException("string exceeds maximum allowed size (512MB)");
            }
            int end = (int) (offset + suffix.length);
            byte[] out = Arrays.copyOf(existing, Math.max(end, existing.length));
            System.arraycopy(suffix, 0, out, (int) offset, suffix.length);
            putDb(db, key, new ValueWrapper(out, current == null ? -1 : current.expireAt));
            setKeyType(key, DataType.STRING, db);
            return out.length;
        }
    }

    /**
     * INCRBYFLOAT：文本进、文本出，键里存的就是回复的那一串。
     * <p>
     * 算术不在 double 里做 —— 参考实现这一族用的是 80 位 long double，见
     * {@link RedisDoubleFormat#plainSum}。原值缺失时从 {@code "0"} 起算（实测
     * {@code INCRBYFLOAT newkey 0.1} 回 {@code 0.1}）。
     *
     * @throws NumberFormatException   原值或增量不是合法浮点文本
     * @throws ArithmeticException     结果不是有限值
     */
    public String incrementFloatDb(int db, String key, String delta) {
        if (key == null) {
            throw new NumberFormatException("value is not a valid float");
        }
        synchronized (stringStores[db]) {
            ValueWrapper current = getLiveWrapper(db, key);
            String base = current == null || current.data == null ? "0"
                    : new String(current.data, StandardCharsets.UTF_8);
            String result = RedisDoubleFormat.plainSum(base, delta);
            putDb(db, key, new ValueWrapper(result.getBytes(StandardCharsets.UTF_8),
                    current == null ? -1 : current.expireAt));
            setKeyType(key, DataType.STRING, db);
            return result;
        }
    }

    public List<byte[]> mget(String... keys) {
        return mgetDb(0, keys);
    }

    public List<byte[]> mgetDb(int db, String... keys) {
        List<byte[]> values = new ArrayList<>(keys == null ? 0 : keys.length);
        if (keys != null) {
            for (String key : keys) {
                values.add(getDb(db, key));
            }
        }
        return values;
    }

    public byte[] get(String key) {
        return getDb(0, key);
    }

    public byte[] getDb(int db, String key) {
        ValueWrapper wrapper = stringStores[db].get(key);
        if (wrapper == null) {
            misses.incrementAndGet();
            return null;
        }
        if (wrapper.isExpired()) {
            stringStores[db].remove(key, wrapper);
            keyTypeMaps[db].remove(key);
            misses.incrementAndGet();
            return null;
        }
        if (wrapper.data == null) {
            misses.incrementAndGet();
            return null;
        }
        wrapper.touch(); // 更新访问时间用于 LRU 淘汰
        hits.incrementAndGet();
        return wrapper.data.clone();
    }

    public String getString(String key) {
        return getStringDb(0, key);
    }

    public String getStringDb(int db, String key) {
        byte[] data = getDb(db, key);
        if (data == null) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    // ==================== TTL 操作 ====================

    public boolean expire(String key, long seconds) {
        return expireDb(0, key, seconds);
    }

    /**
     * 与 {@link #pexpireDb} 同一条尺，只是量纲是秒：{@code seconds <= 0} 是"立刻过期"，
     * 实测（battery37 第 28 行）{@code EXPIRE k 0} 回 {@code :1} 而键当场不见 —— 不是回 0，
     * 也不是把过期时间写成一个非正的时刻（{@code ValueWrapper} 里 {@code expireAt <= 0}
     * 的含义是"永不过期"，写进去等于反过来把它救活）。键不在时回 0 由 {@code delDb} 自己给。
     */
    public boolean expireDb(int db, String key, long seconds) {
        if (seconds <= 0) {
            return delDb(db, key);
        }
        synchronized (stringStores[db]) {
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper == null || wrapper.isExpired()) {
                if (wrapper != null) {
                    stringStores[db].remove(key, wrapper);
                    keyTypeMaps[db].remove(key);
                }
                return false;
            }
            long expireAt = saturatingExpireAt(TimeUnit.SECONDS.toMillis(seconds));
            putDb(db, key, new ValueWrapper(wrapper.data, expireAt));
            return true;
        }
    }

    public boolean pexpire(String key, long milliseconds) {
        return pexpireDb(0, key, milliseconds);
    }

    public boolean pexpireDb(int db, String key, long milliseconds) {
        if (milliseconds <= 0) {
            return delDb(db, key);
        }
        synchronized (stringStores[db]) {
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper == null || wrapper.isExpired()) {
                if (wrapper != null) {
                    stringStores[db].remove(key, wrapper);
                    keyTypeMaps[db].remove(key);
                }
                return false;
            }
            long expireAt = saturatingExpireAt(milliseconds);
            putDb(db, key, new ValueWrapper(wrapper.data, expireAt));
            return true;
        }
    }

    public boolean persist(String key) {
        return persistDb(0, key);
    }

    public boolean persistDb(int db, String key) {
        synchronized (stringStores[db]) {
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper == null || wrapper.isExpired()) {
                if (wrapper != null) {
                    stringStores[db].remove(key, wrapper);
                    keyTypeMaps[db].remove(key);
                }
                return false;
            }
            if (!wrapper.hasExpiration()) {
                return false;
            }
            putDb(db, key, new ValueWrapper(wrapper.data, -1));
            return true;
        }
    }

    public long ttl(String key) {
        return ttlDb(0, key);
    }

    public long ttlDb(int db, String key) {
        ValueWrapper wrapper = stringStores[db].get(key);
        if (wrapper == null || wrapper.isExpired()) {
            if (wrapper != null && wrapper.isExpired()) {
                stringStores[db].remove(key);
                keyTypeMaps[db].remove(key);
            }
            return -2;
        }
        if (!wrapper.hasExpiration()) {
            return -1;
        }
        // 向上取整，与 Redis TTL 一致：EX 1 刚写入时读回 1 而不是整数除法截断成 0。
        long remaining = wrapper.expireAt - System.currentTimeMillis();
        return (remaining + 999) / 1000;
    }

    public long pttl(String key) {
        return pttlDb(0, key);
    }

    public long pttlDb(int db, String key) {
        ValueWrapper wrapper = stringStores[db].get(key);
        if (wrapper == null || wrapper.isExpired()) {
            if (wrapper != null) {
                stringStores[db].remove(key, wrapper);
                keyTypeMaps[db].remove(key);
            }
            return -2;
        }
        if (!wrapper.hasExpiration()) {
            return -1;
        }
        return Math.max(wrapper.expireAt - System.currentTimeMillis(), 0);
    }

    // ==================== 通用键操作 ====================

    public boolean del(String key) {
        return delDb(0, key);
    }

    public boolean delDb(int db, String key) {
        DataType type = keyTypeMaps[db].remove(key);
        if (type == null) {
            // 回退检查 string store
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper != null) {
                stringStores[db].remove(key, wrapper);
                return !wrapper.isExpired();
            }
            return false;
        }
        switch (type) {
            case STRING:
                ValueWrapper wrapper = stringStores[db].get(key);
                if (wrapper != null) {
                    stringStores[db].remove(key, wrapper);
                    return !wrapper.isExpired();
                }
                return false;
            case HASH:
                return hashStores[db].del(key);
            case LIST:
                return listStores[db].del(key);
            case SET:
                return setStores[db].del(key);
            case ZSET:
                return sortedSetStores[db].del(key);
            default:
                return false;
        }
    }

    public long del(String... keys) {
        return delDb(0, keys);
    }

    public long delDb(int db, String... keys) {
        long count = 0;
        for (String key : keys) {
            if (delDb(db, key)) {
                count++;
            }
        }
        return count;
    }

    public boolean exists(String key) {
        return existsDb(0, key);
    }

    public boolean existsDb(int db, String key) {
        DataType type = keyTypeMaps[db].get(key);
        if (type == null) {
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper == null) {
                return false;
            }
            if (wrapper.isExpired()) {
                stringStores[db].remove(key);
                return false;
            }
            return true;
        }
        switch (type) {
            case STRING:
                ValueWrapper wrapper = stringStores[db].get(key);
                return wrapper != null && !wrapper.isExpired();
            case HASH:
                return hashStores[db].exists(key);
            case LIST:
                return listStores[db].exists(key);
            case SET:
                return setStores[db].exists(key);
            case ZSET:
                return sortedSetStores[db].exists(key);
            default:
                return false;
        }
    }

    public List<String> keys(String pattern) {
        return keysDb(0, pattern);
    }

    public List<String> keysDb(int db, String pattern) {
        List<String> result = new ArrayList<>();
        if (pattern == null) {
            return result;
        }
        String regex = globToRegex(pattern);

        // 扫描 string store
        for (Map.Entry<String, ValueWrapper> entry : stringStores[db].entrySet()) {
            ValueWrapper wrapper = entry.getValue();
            if (wrapper != null && !wrapper.isExpired() && entry.getKey().matches(regex)) {
                result.add(entry.getKey());
            } else if (wrapper != null && wrapper.isExpired()) {
                stringStores[db].remove(entry.getKey(), wrapper);
                keyTypeMaps[db].remove(entry.getKey());
            }
        }

        // 扫描其他类型 store
        for (String hk : hashStores[db].keys()) {
            if (hk.matches(regex)) {
                result.add(hk);
            }
        }
        for (String lk : listStores[db].keys()) {
            if (lk.matches(regex)) {
                result.add(lk);
            }
        }
        for (String sk : setStores[db].keys()) {
            if (sk.matches(regex)) {
                result.add(sk);
            }
        }
        for (String zk : sortedSetStores[db].keys()) {
            if (zk.matches(regex)) {
                result.add(zk);
            }
        }
        return result;
    }

    // ==================== SCAN 迭代器 ====================

    /**
     * 游标扫描键，基于 HashSet 实现增量迭代。
     *
     * @param db      数据库编号
     * @param cursor  游标，0 表示开始扫描
     * @param pattern 匹配模式
     * @param count   预期返回数量
     * @return [nextCursor, matchedKeys]
     */
    public Object[] scan(int db, String cursor, String pattern, int count) {
        Set<String> allKeys = new HashSet<>();
        // 收集所有有效键
        for (Map.Entry<String, ValueWrapper> entry : stringStores[db].entrySet()) {
            ValueWrapper wrapper = entry.getValue();
            if (wrapper != null && !wrapper.isExpired()) {
                allKeys.add(entry.getKey());
            }
        }
        allKeys.addAll(hashStores[db].keys());
        allKeys.addAll(listStores[db].keys());
        allKeys.addAll(setStores[db].keys());
        allKeys.addAll(sortedSetStores[db].keys());

        String regex = pattern == null ? null : globToRegex(pattern);
        List<String> sorted = new ArrayList<>(allKeys);
        sorted.sort(String::compareTo);

        int startIndex = 0;
        long cursorVal = 0;
        try {
            cursorVal = Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            cursorVal = 0;
        }

        // 找到起始位置
        if (cursorVal > 0) {
            startIndex = (int) Math.min(cursorVal, sorted.size());
        }

        List<String> result = new ArrayList<>();
        int limit = count > 0 ? count : 10;
        int i = startIndex;
        while (i < sorted.size() && result.size() < limit) {
            String key = sorted.get(i);
            if (regex == null || key.matches(regex)) {
                result.add(key);
            }
            i++;
        }

        String nextCursor = i >= sorted.size() ? "0" : String.valueOf(i);
        return new Object[]{nextCursor, result};
    }

    // ==================== 删除操作 ====================

    public long dbsize() {
        return dbsizeDb(0);
    }

    public long dbsizeDb(int db) {
        long count = 0;
        // String DB
        Iterator<String> it = stringStores[db].keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper != null && !wrapper.isExpired()) {
                count++;
            } else if (wrapper != null && wrapper.isExpired()) {
                it.remove();
            }
        }
        count += hashStores[db].dbsize();
        count += listStores[db].dbsize();
        count += setStores[db].dbsize();
        count += sortedSetStores[db].dbsize();
        return count;
    }

    public void flush() {
        flushDb(0);
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        totalCommands.set(0);
    }

    public void flushDb(int db) {
        stringStores[db].clear();
        hashStores[db].flush();
        listStores[db].flush();
        setStores[db].flush();
        sortedSetStores[db].flush();
        keyTypeMaps[db].clear();
    }

    public void flushAll() {
        for (int i = 0; i < dbCount; i++) {
            flushDb(i);
        }
    }

    // ==================== RENAME 操作 ====================

    public boolean rename(String oldKey, String newKey) {
        return renameDb(0, oldKey, newKey);
    }

    public boolean renameDb(int db, String oldKey, String newKey) {
        DataType type = checkKeyType(oldKey, db);
        if (type == DataType.NONE) {
            return false;
        }
        // 复制数据到新 key，删除旧 key
        switch (type) {
            case STRING:
                ValueWrapper wrapper = stringStores[db].get(oldKey);
                if (wrapper != null) {
                    stringStores[db].put(newKey, wrapper);
                    stringStores[db].remove(oldKey);
                    keyTypeMaps[db].remove(oldKey);
                    keyTypeMaps[db].put(newKey, DataType.STRING);
                }
                break;
            case HASH:
                // Hash 不直接支持 rename，通过 del + 迁移
                hashStores[db].del(newKey);
                break;
            case LIST:
                listStores[db].del(newKey);
                break;
            case SET:
                setStores[db].del(newKey);
                break;
            case ZSET:
                sortedSetStores[db].del(newKey);
                break;
        }
        return true;
    }

    /**
     * MOVE：把当前库里的整个键搬到目标库。返回 false 表示"一下都没搬"。
     * <p>
     * 三条判据都量过（250 {@code refmv.tr}，五种类型各一组）：
     * <ol>
     *   <li>过期时间跟着键一起走 —— {@code EXPIRE mv:b 500} 之后 MOVE，目标库 {@code TTL} 回 500、
     *       源库回 -2；</li>
     *   <li>目标库已经有同名键就整个不做：回 0，两边都保持原值（不是覆盖，也不是报错）；</li>
     *   <li>五种类型都能搬，搬完源库里干干净净（DBSIZE 两侧对账 1 / 7）。</li>
     * </ol>
     * 集合类型没有 TTL 可搬，沿本实现的同一把尺（见 {@code CommandHandler} 里 EXPIRE 一族的注释）。
     *
     * @return 是否真的搬走了
     */
    public boolean moveKeyToDb(int fromDb, int toDb, String key) {
        if (key == null || fromDb == toDb) {
            return false;
        }
        DataType type = typeOfDb(fromDb, key);
        if (type == DataType.NONE || typeOfDb(toDb, key) != DataType.NONE) {
            return false;
        }
        switch (type) {
            case STRING: {
                ValueWrapper wrapper;
                synchronized (stringStores[fromDb]) {
                    wrapper = stringStores[fromDb].remove(key);
                    keyTypeMaps[fromDb].remove(key);
                    if (wrapper != null) {
                        // 目标库确认没有任何类型挂着这个键名（上面那一道判据），所以直接放，
                        // 不走 putDb —— 那会在两把库锁之间来回，跨库的锁序说不清。
                        stringStores[toDb].put(key, wrapper);
                        keyTypeMaps[toDb].put(key, DataType.STRING);
                    }
                }
                return wrapper != null;
            }
            case HASH: {
                Map<String, byte[]> fields = hashStores[fromDb].hgetall(key);
                hashStores[fromDb].del(key);
                hashStores[toDb].hmset(key, fields);
                return true;
            }
            case LIST: {
                List<byte[]> elements = listStores[fromDb].lrange(key, 0, -1);
                listStores[fromDb].del(key);
                listStores[toDb].rpush(key, elements.toArray(new byte[0][]));
                return true;
            }
            case SET: {
                List<byte[]> members = setStores[fromDb].smembers(key);
                setStores[fromDb].del(key);
                setStores[toDb].sadd(key, members.toArray(new byte[0][]));
                return true;
            }
            case ZSET: {
                // 快照成 member → score，再按分数原样落进目标库。不走 "zrange WITHSCORES 再 parse
                // 回来" 那一圈：那是让二进制值先变成文本再变回来，白白经一遍打印规则。
                Map<String, Double> scores = sortedSetStores[fromDb].memberScores(key);
                sortedSetStores[fromDb].del(key);
                for (Map.Entry<String, Double> entry : scores.entrySet()) {
                    sortedSetStores[toDb].zadd(key, entry.getValue(),
                            entry.getKey().getBytes(StandardCharsets.UTF_8));
                }
                return true;
            }
            default:
                return false;
        }
    }

    // ==================== RANDOMKEY ====================
    public String randomKey(int db) {
        // 从所有 store 中随机选择一个 key
        List<String> allKeys = new ArrayList<>();
        allKeys.addAll(stringStores[db].keySet());
        allKeys.addAll(hashStores[db].keys());
        allKeys.addAll(listStores[db].keys());
        allKeys.addAll(setStores[db].keys());
        allKeys.addAll(sortedSetStores[db].keys());
        if (allKeys.isEmpty()) {
            return null;
        }
        return allKeys.get(random.nextInt(allKeys.size()));
    }

    // ==================== 事务版本号 ====================

    private final ConcurrentHashMap<String, AtomicLong> keyVersions = new ConcurrentHashMap<>();

    /**
     * 某个库里键的事务版本号。必须带 db：版本号只在同一个库内可比，
     * 只按 key 记的话 DB 1 上写 {@code k} 会把 DB 0 上 {@code WATCH k} 的连接一起中止掉。
     */
    public long getKeyVersion(int db, String key) {
        AtomicLong version = keyVersions.get(versionKey(db, key));
        return version == null ? 0L : version.get();
    }

    public void bumpKeyVersion(int db, String key) {
        keyVersions.computeIfAbsent(versionKey(db, key), k -> new AtomicLong(0)).incrementAndGet();
    }

    /** 0x00 分隔：键名里不可能带它，而 "db:key" 形式会把 {@code "1:2"} 与 db=1/key="2" 撞在一起。 */
    private static String versionKey(int db, String key) {
        return db + "\u0000" + key;
    }

    // ==================== 统计信息 ====================

    public long getHits() { return hits.get(); }
    public long getMisses() { return misses.get(); }
    public long getEvictions() { return evictions.get(); }
    public int getMaxEntries() { return maxEntries; }
    public long getTotalCommands() { return totalCommands.get(); }
    public long getTotalConnections() { return totalConnections.get(); }
    public long getStartTime() { return startTime; }
    public int getDbCount() { return dbCount; }

    public void incrementCommands() { totalCommands.incrementAndGet(); }
    public void incrementConnections() { totalConnections.incrementAndGet(); }

    /** 当前连接数（简化：每次连接 +1，断开不减） */
    private final AtomicLong connectedClients = new AtomicLong(0);
    public long getConnectedClients() { return connectedClients.get(); }
    public void incrementConnectedClient() { connectedClients.incrementAndGet(); }
    public void decrementConnectedClient() { connectedClients.decrementAndGet(); }

    // ==================== 内部工具方法 ====================

    private void putDb(int db, String key, ValueWrapper value) {
        synchronized (stringStores[db]) {
            // 一个键只能挂一个值：写成 String 前要把同名 key 上的 hash/list/set/zset 清掉
            // （Redis 的 dbOverwrite 就是这一步）。不清的话 GET 和 HGETALL 会各自答一份，
            // DBSIZE 还会把同一个键数两遍，DEL 要按两次才删干净。
            clearOtherTypes(db, key);
            stringStores[db].put(key, value);
            // 版本号不在这里记：CommandHandler 才是"客户端写了一次"的唯一漏斗，
            // 在那儿记才能覆盖五种类型（也只有命令层知道一条命令改了哪几个键）。
            if (maxEntries <= 0 || stringStores[db].size() + getTotalEntries(db) <= maxEntries) {
                return;
            }
            evictOne(db);
        }
    }

    /**
     * 抹掉同一键上其它数据类型的残留。四个集合存储都是叶子（不回指 MemoryStore），
     * 所以这里在 string 的监视器内调用不会和它们形成反向锁序。
     */
    private void clearOtherTypes(int db, String key) {
        clearOtherTypes(db, key, DataType.STRING);
    }

    /**
     * 保留 {@code keep} 那一种，把键名上其余四种清干净。
     * <p>
     * {@code ZUNIONSTORE}/{@code ZINTERSTORE} 覆盖目标键要的就是这一把：Redis 在那里走
     * {@code dbOverwrite}，先把旧值整个换成新 zset，所以目标键原本挂着 string/hash/list/set
     * 都不报 WRONGTYPE（实测 {@code SET d x; ZUNIONSTORE d 1 k 1 => 1} 且 {@code TYPE d => zset}）。
     * 目标键同时又是源键是这条命令的合法写法，因此调用方必须先读完源再清 —— 见
     * {@code SortedSetStore.aggregateStore}。
     */
    public void clearOtherTypes(int db, String key, DataType keep) {
        if (keep != DataType.STRING) {
            synchronized (stringStores[db]) {
                stringStores[db].remove(key);
                keyTypeMaps[db].remove(key);
            }
        }
        if (keep != DataType.HASH) {
            hashStores[db].del(key);
        }
        if (keep != DataType.LIST) {
            listStores[db].del(key);
        }
        if (keep != DataType.SET) {
            setStores[db].del(key);
        }
        if (keep != DataType.ZSET) {
            sortedSetStores[db].del(key);
        }
    }

    private long getTotalEntries(int db) {
        return hashStores[db].dbsize() + listStores[db].dbsize() +
                setStores[db].dbsize() + sortedSetStores[db].dbsize();
    }

    /**
     * 淘汰一个键。支持近似 LRU 和近似 LFU 两种策略。
     * <p>
     * LRU: 随机采样 5 个键，淘汰最久未访问的。
     * LFU: 随机采样 5 个键，淘汰 Morris 计数器值最低的。
     * </p>
     */
    private void evictOne(int db) {
        String[] keys = stringStores[db].keySet().toArray(new String[0]);
        if (keys.length == 0) {
            return;
        }
        int sampleSize = Math.min(5, keys.length);
        String victim = keys[random.nextInt(keys.length)];
        long oldestAccess = Long.MAX_VALUE;
        int lowestLfu = Integer.MAX_VALUE;

        for (int i = 0; i < sampleSize; i++) {
            String candidate = keys[random.nextInt(keys.length)];
            ValueWrapper wrapper = stringStores[db].get(candidate);
            if (wrapper == null) {
                stringStores[db].remove(candidate);
                keyTypeMaps[db].remove(candidate);
                evictions.incrementAndGet();
                return;
            }
            // LFU 优先：选择 Morris 计数器最低的
            if (wrapper.lfuCounter < lowestLfu
                    || (wrapper.lfuCounter == lowestLfu && wrapper.lastAccessTime < oldestAccess)) {
                lowestLfu = wrapper.lfuCounter;
                oldestAccess = wrapper.lastAccessTime;
                victim = candidate;
            }
        }

        stringStores[db].remove(victim);
        keyTypeMaps[db].remove(victim);
        evictions.incrementAndGet();
    }

    private ValueWrapper getLiveWrapper(int db, String key) {
        ValueWrapper wrapper = stringStores[db].get(key);
        if (wrapper != null && wrapper.isExpired()) {
            stringStores[db].remove(key, wrapper);
            keyTypeMaps[db].remove(key);
            return null;
        }
        return wrapper;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    static String globToRegex(String pattern) {
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
     * 值包装类，包含数据、过期时间和访问时间（用于近似 LRU 淘汰）。
     */
    public static class ValueWrapper {
        public final byte[] data;
        public final long expireAt; // -1 means no expiration
        /** 最后访问时间（毫秒），用于近似 LRU 淘汰策略 */
        public volatile long lastAccessTime;
        /** 访问次数（用于 LFU 淘汰策略） */
        public volatile long accessCount;
        /** Morris 计数器值（对数计数，用于 LFU 近似频率） */
        public volatile int lfuCounter;

        public ValueWrapper(byte[] data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
            this.lastAccessTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return expireAt > 0 && System.currentTimeMillis() > expireAt;
        }

        public boolean hasExpiration() {
            return expireAt > 0;
        }

        /** 更新访问时间和 LFU 计数器 */
        public void touch() {
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount++;
            // Morris 计数器：对数递增，约 50% 概率递增 counter
            double p = 1.0 / (1L << lfuCounter);
            if (Math.random() < p) {
                lfuCounter++;
            }
        }
    }
}
