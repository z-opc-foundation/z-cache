package com.zifang.z.cache.core.storage;

import com.zifang.z.cache.common.protocol.RedisDoubleFormat;
import com.zifang.z.cache.common.protocol.RedisGlob;
import com.zifang.z.cache.common.protocol.RedisIntegerFormat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
        NONE, STRING, HASH, LIST, SET, ZSET, STREAM
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

    /**
     * Stream 那一族的存储：<b>由外部注入，可以为 null</b>。
     * <p>
     * 它不是本类自己 new 出来的第六张表 —— {@code StreamStore} 是"一台服务器一份"
     * （{@code ServerScope}），而 {@code MemoryStore} 可以被拆开单用（单测、嵌入式）。
     * 没接上时下面那一整套判据对 stream 一律"看不见"，行为退回注入之前的形状，
     * 而不是退回一个更糟的猜测。
     */
    private volatile com.zifang.z.cache.core.stream.StreamStore streams;

    /** 键类型映射: key -> DataType (每个 DB 独立) */
    @SuppressWarnings("unchecked")
    private final ConcurrentHashMap<String, DataType>[] keyTypeMaps;

    /**
     * 每个数据库的过期时刻表：键名 -&gt; 绝对毫秒（{@code > 0} 才是真挂上了过期）。
     * <p>
     * 这个位置是照上游摆的：过期时刻<b>不在值对象里</b>。挂在值对象里等于把"过期时间"做成
     * String 这一型的一个属性，六种键型里就只有有一种有地方放它 —— 而上游
     * {@code expireGenericCommand}（{@code expire.c:415-451}）问的从来不是"这是什么类型"，
     * 唯一的存在性闸是 {@code :426} 的 {@code lookupKeyWrite}，时刻存在每个库自己的那张
     * {@code db->expire} 字典里。所以这里也是：键空间的属性，与值是什么类型无关。
     */
    @SuppressWarnings("unchecked")
    private final ConcurrentHashMap<String, Long>[] expirations;

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
        this.expirations = new ConcurrentHashMap[dbCount];
        for (int i = 0; i < dbCount; i++) {
            stringStores[i] = new ConcurrentHashMap<>();
            hashStores[i] = new HashStore();
            listStores[i] = new ListStore();
            setStores[i] = new SetStore();
            sortedSetStores[i] = new SortedSetStore();
            keyTypeMaps[i] = new ConcurrentHashMap<>();
            expirations[i] = new ConcurrentHashMap<>();
        }
        wireExpiryRecycling();
    }

    /**
     * 把四个集合 store 的「整键没了」接到本库的时刻表上：键消失的那一刻，它在时刻表里的那一行
     * 一起回收。
     * <p>
     * 这一接是为「集合键也能挂过期」准备的：上游删键只有一个口（{@code dbSyncDelete} 先删
     * {@code db->expires} 再删 {@code db->dict}，{@code db.c:271-281}），而我们"键空了所以不在了"
     * 这个决定长在各自的 store 里（{@code LPOP} 弹出最后一个元素、{@code SPOP} 掏空、
     * {@code ZREM} 清完、{@code LTRIM} 裁空……共 21 处），它们在构造上就看不见时刻表。
     * 不接的后果不是"多留一行垃圾"：那行是一个<b>未来</b>的时刻，同名键被重新写入之后会
     * 直接继承它 —— 上游那里 {@code TTL} 回 -1，这里会回一个看着合理的正数，谁也发现不了。
     * <p>
     * stream 不在这份名单里是有意的：上游 {@code xdelCommand}（{@code t_stream.c:2413-2436}）
     * 掏空一条流并不删键，所以 {@code StreamStore} 没有"自己把键摘掉"的路径，
     * 它的删除只从 {@link #removeAnyType} 那一个口进来。
     */
    private void wireExpiryRecycling() {
        for (int i = 0; i < dbCount; i++) {
            final int db = i;
            hashStores[db].onKeyVanished(key -> clearExpireAtDb(db, key));
            listStores[db].onKeyVanished(key -> clearExpireAtDb(db, key));
            setStores[db].onKeyVanished(key -> clearExpireAtDb(db, key));
            sortedSetStores[db].onKeyVanished(key -> clearExpireAtDb(db, key));
        }
    }

    // ==================== 多 DB 访问 ====================

    public HashStore getHashStore(int db) { return hashStores[db]; }
    public ListStore getListStore(int db) { return listStores[db]; }
    public SetStore getSetStore(int db) { return setStores[db]; }
    public SortedSetStore getSortedSetStore(int db) { return sortedSetStores[db]; }

    public Map<String, ValueWrapper> getStringStore(int db) { return stringStores[db]; }

    // ==================== 过期时刻表 ====================

    /**
     * 这条键当前挂着的过期时刻（绝对毫秒）；没挂过期回 {@code -1}。
     * <p>
     * 只回答"表里写了什么"，不做"到点没到点"的判断，也不动表 —— 与原来读
     * {@code ValueWrapper.expireAt} 那一栏一模一样。
     */
    public long expireAtDb(int db, String key) {
        Long expireAt = expirations[db].get(key);
        return expireAt == null ? -1L : expireAt;
    }

    /** 这条键是否挂过过期（时刻表里有没有它）。到点没到点不算在这一问里。 */
    public boolean hasExpirationDb(int db, String key) {
        return expirations[db].containsKey(key);
    }

    /**
     * 这条键的过期时刻是否已经过去了。
     * <p>
     * 判过点而<b>不</b>顺手抹记录：调用方删的是整条键（值连同时刻一起没了），
     * 而不是"只把时刻抹了、键留着"。原来 {@code ValueWrapper.isExpired()} 就是这个口径 ——
     * 一次判红之后 {@code expireAt} 仍然读得到，靠的正是"没人清它"。
     */
    public boolean isExpiredDb(int db, String key) {
        long expireAt = expireAtDb(db, key);
        return expireAt > 0 && System.currentTimeMillis() > expireAt;
    }

    /**
     * 该库时刻表的快照（键名 -&gt; 绝对毫秒）。返回的是副本，改它不影响存储。
     * <p>
     * 已过点却还没人碰过的键仍会出现在这里 —— 惰性删除的口径，判活要配 {@link #isExpiredDb}。
     */
    public Map<String, Long> expirationSnapshot(int db) {
        return new HashMap<>(expirations[db]);
    }

    /**
     * 挂上过期：{@code expireAt <= 0} 一律当成"取消"，时刻表里只存真时刻。
     * <p>
     * <b>要在 {@code putDb} 之前调用。</b>{@code putDb} 装到上限时会当场淘汰一个键，
     * 而被淘汰的完全可能就是刚写进去的这一枚 —— 先记后写，{@link #evictOne} 那句
     * {@code clearExpireAtDb} 才有机会把这一行一起收走；反过来就成了表里的一条无主记录。
     * 时刻长在值上的年代不会有这个形状（键没了时刻跟着没）。
     */
    private void setExpireAtDb(int db, String key, long expireAt) {
        if (expireAt > 0) {
            expirations[db].put(key, expireAt);
        } else {
            expirations[db].remove(key);
        }
    }

    /** 取消过期（或在键整个没掉之后回收它在时刻表里的记录）。 */
    private void clearExpireAtDb(int db, String key) {
        expirations[db].remove(key);
    }

    // ==================== 类型管理 ====================

    /**
     * 获取指定 DB 中键的数据类型。
     */
    public DataType getKeyType(String key, int db) {
        DataType type = keyTypeMaps[db].get(key);
        if (type == null) {
            // 检查旧的 string store 兼容
            ValueWrapper wrapper = stringStores[db].get(key);
            if (wrapper != null && !isExpiredDb(db, key)) {
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
            if (wrapper != null && isExpiredDb(db, key)) {
                stringStores[db].remove(key);
                keyTypeMaps[db].remove(key);
                clearExpireAtDb(db, key);
                return DataType.NONE;
            }
        }
        return type;
    }

    /**
     * 这个键当前真正是哪一种类型 —— 以六个 store 里有没有它为准。
     * <p>
     * 不读 {@code keyTypeMaps}：那张表只有 String 写入路径（{@code setKeyType}）维护过，
     * 集合类型从来没登记，所以它对"这是个 hash"永远说 NONE。EXISTS / TYPE / 类型闸门
     * 现在共用这一把尺，三者不可能再互相打脸。
     * <p>
     * stream 排在这一串的最后，是因为它不在本类的构造里（见 {@link #bindStreams}）：
     * 前面五张表命中时压根不该去问它。反过来说，接上之后它就成了第六种能被这把尺答出来的
     * 类型 —— 所有拿这把尺当判据的地方（TYPE / EXISTS / RENAME / MOVE / 中央类型闸门 /
     * DBSIZE）从此都看得见 stream 键，这是"stream 键是键"这一格的落点。
     */
    public DataType typeOfDb(int db, String key) {
        if (key == null) {
            return DataType.NONE;
        }
        // 惰性删除：时刻表是"按库、按键名"的一张表，六种类型共用这一把尺去问它 —— 与上游
        // expireIfNeeded 的口径一致（它也不看类型，时刻就挂在 db->expire 里，见 expire.c:415-451
        // 那一问只有 lookupKeyWrite 一道闸、没有类型分支）。
        // 六种类型的 TTL 现在都从 {@link #armExpiry} 那一条腿挂上来（上游 expireGenericCommand
        // 也没有类型分支），所以这一支对六种类型都是真问、不是 containsKey 的空问。
        if (hasExpirationDb(db, key) && isExpiredDb(db, key)) {
            removeAnyType(db, key);   // 连键连带时刻那一行一起没，不留"只抹时刻、键留着"
            return DataType.NONE;
        }
        ValueWrapper wrapper = stringStores[db].get(key);
        if (wrapper != null) {
            return DataType.STRING;
        }
        if (hashStores[db].exists(key)) return DataType.HASH;
        if (listStores[db].exists(key)) return DataType.LIST;
        if (setStores[db].exists(key)) return DataType.SET;
        if (sortedSetStores[db].exists(key)) return DataType.ZSET;
        if (hasStreams(db, key)) return DataType.STREAM;
        return DataType.NONE;
    }

    /** 接上 stream 那一族；传 null 等于不接（本类对 stream 一律回答"没有这个键"）。 */
    public void bindStreams(com.zifang.z.cache.core.stream.StreamStore streamStore) {
        this.streams = streamStore;
    }

    /** 本台那一份 stream 存储；没绑过时为 {@code null}，调用方按"这一族一个键都没有"处理。 */
    public com.zifang.z.cache.core.stream.StreamStore streamStore() {
        return streams;
    }

    private boolean hasStreams(int db, String key) {
        com.zifang.z.cache.core.stream.StreamStore s = streams;
        return s != null && s.exists(db, key);
    }

    /** stream 键的键名集合；没接 stream 存储就是空集（不是 null）。 */
    private Set<String> streamKeys(int db) {
        com.zifang.z.cache.core.stream.StreamStore s = streams;
        return s == null ? java.util.Collections.<String>emptySet() : s.keySet(db);
    }

    // ==================== String 操作 (保持向后兼容) ====================

    public boolean set(String key, byte[] value) {
        return setDb(0, key, value);
    }

    public boolean setDb(int db, String key, byte[] value) {
        putDb(db, key, new ValueWrapper(value == null ? null : value.clone()));
        setKeyType(key, DataType.STRING, db);
        // 整键覆盖要连过期一起清掉 —— 上游 setKey 的第三条就是这句："The expire time of the key
        // is reset (the key is made persistent)"（db.c:216-224，removeExpire 在 :223）。
        // 过去这件事是"新 wrapper 带着 -1 顶掉旧 wrapper"顺带做成的，现在时刻不在值上，得写明。
        clearExpireAtDb(db, key);
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
        setExpireAtDb(db, key, saturatingExpireAt(TimeUnit.SECONDS.toMillis(seconds)));
        putDb(db, key, new ValueWrapper(value == null ? null : value.clone()));
        setKeyType(key, DataType.STRING, db);
        return true;
    }

    public boolean psetex(String key, long milliseconds, byte[] value) {
        return psetexDb(0, key, milliseconds, value);
    }

    public boolean psetexDb(int db, String key, long milliseconds, byte[] value) {
        setExpireAtDb(db, key, saturatingExpireAt(milliseconds));
        putDb(db, key, new ValueWrapper(value == null ? null : value.clone()));
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
            putDb(db, key, new ValueWrapper(copy(value)));
            setKeyType(key, DataType.STRING, db);
            clearExpireAtDb(db, key);
            return true;
        }
    }

    public byte[] getAndSet(String key, byte[] value) {
        return getAndSetDb(0, key, value);
    }

    public byte[] getAndSetDb(int db, String key, byte[] value) {
        synchronized (stringStores[db]) {
            ValueWrapper current = getLiveWrapper(db, key);
            putDb(db, key, new ValueWrapper(copy(value)));
            setKeyType(key, DataType.STRING, db);
            clearExpireAtDb(db, key);
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
            putDb(db, key, new ValueWrapper(value));
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
            putDb(db, key, new ValueWrapper(value));
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
            putDb(db, key, new ValueWrapper(Long.toString(result).getBytes(StandardCharsets.UTF_8)));
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
            putDb(db, key, new ValueWrapper(out));
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
            putDb(db, key, new ValueWrapper(result.getBytes(StandardCharsets.UTF_8)));
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
        if (isExpiredDb(db, key)) {
            stringStores[db].remove(key, wrapper);
            keyTypeMaps[db].remove(key);
            clearExpireAtDb(db, key);
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
     * 与 {@link #pexpireDb} 走同一条腿（{@link #armExpiry}），只是量纲是秒：{@code seconds <= 0}
     * 是"立刻过期"，实测（battery37 第 28 行）{@code EXPIRE k 0} 回 {@code :1} 而键当场不见 ——
     * 不是回 0，也不是把过期时间写成一个非正的时刻（时刻表里 {@code expireAt <= 0} 的口径是
     * "没有过期"，写进去等于反过来把它救活）。秒乘一千会不会溢出由命令层挡
     * （{@code CommandHandler#expireMillisOrOverflow}），"乘得出来但加不上现在"由
     * {@link #saturatingExpireAt} 贴顶。
     */
    public boolean expireDb(int db, String key, long seconds) {
        return armExpiry(db, key, seconds <= 0 ? 0L : TimeUnit.SECONDS.toMillis(seconds));
    }

    public boolean pexpire(String key, long milliseconds) {
        return pexpireDb(0, key, milliseconds);
    }

    public boolean pexpireDb(int db, String key, long milliseconds) {
        return armExpiry(db, key, milliseconds);
    }

    /**
     * EXPIRE / PEXPIRE（以及从它们走过去 {@code EXPIREAT} / {@code PEXPIREAT}）的唯一一条腿。
     * <p>
     * 判存只问 {@link #typeOfDb} 那一把尺，不再先看 {@code stringStores} 里有没有一枚值对象 ——
     * 上游 {@code expireGenericCommand}（{@code expire.c:415-451}）没有类型分支，:426 那一问
     * 只有 {@code lookupKeyWrite}，所以六种类型都能挂上过期。时刻表本来就是按库、按键名的，
     * 五种集合键挂不上不是"忘了接线"，是那五支各自都先去问了一张只装 String 的表。
     * <p>
     * {@code relativeMillis <= 0} 那一支对应上游的 {@code checkAlreadyExpired}：当场删整个键并回
     * 成功（{@code dbSyncDelete}），而判活那一句先问 {@code typeOfDb} 是因为
     * {@code lookupKeyWrite} 里的 {@code expireIfNeeded} 已经把到点的键摘走了 —— 那一问回的是
     * "键不在"，于是回 0。
     */
    private boolean armExpiry(int db, String key, long relativeMillis) {
        if (typeOfDb(db, key) == DataType.NONE) {
            return false;
        }
        if (relativeMillis <= 0) {
            removeAnyType(db, key);
            return true;
        }
        setExpireAtDb(db, key, saturatingExpireAt(relativeMillis));
        return true;
    }

    public boolean persist(String key) {
        return persistDb(0, key);
    }

    /**
     * 取消过期：只动时刻表那一行。
     * <p>
     * 以前这里为了清 {@code ValueWrapper} 上的一栏，把值原样 {@code putDb} 回去了一次，
     * 而 {@code putDb} 里带着 {@code clearOtherTypes} —— 也就是说"取消一枚 String 的过期"
     * 会顺手毁掉同名的那枚 hash。时刻搬进表之后这一次写值既没必要、也是有害的。
     */
    public boolean persistDb(int db, String key) {
        if (typeOfDb(db, key) == DataType.NONE) {
            return false;
        }
        if (!hasExpirationDb(db, key)) {
            return false;
        }
        clearExpireAtDb(db, key);
        return true;
    }

    public long ttl(String key) {
        return ttlDb(0, key);
    }

    public long ttlDb(int db, String key) {
        // -2 与 -1 分别是"键不在"和"键在但没挂过期"：把这一问只交给 typeOfDb 一把尺，
        // 五种集合键才第一次能回出 -1（改之前它们连 -2 那一步都过不了 stringStores 那道闸）。
        if (typeOfDb(db, key) == DataType.NONE) {
            return -2;
        }
        if (!hasExpirationDb(db, key)) {
            return -1;
        }
        // 向上取整，与 Redis TTL 一致：EX 1 刚写入时读回 1 而不是整数除法截断成 0。
        long remaining = expireAtDb(db, key) - System.currentTimeMillis();
        return (remaining + 999) / 1000;
    }

    public long pttl(String key) {
        return pttlDb(0, key);
    }

    public long pttlDb(int db, String key) {
        if (typeOfDb(db, key) == DataType.NONE) {
            return -2;
        }
        if (!hasExpirationDb(db, key)) {
            return -1;
        }
        return Math.max(expireAtDb(db, key) - System.currentTimeMillis(), 0);
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
                boolean alive = !isExpiredDb(db, key);
                clearExpireAtDb(db, key);
                return alive;
            }
            return false;
        }
        switch (type) {
            case STRING:
                ValueWrapper wrapper = stringStores[db].get(key);
                if (wrapper != null) {
                    stringStores[db].remove(key, wrapper);
                    boolean alive = !isExpiredDb(db, key);
                    clearExpireAtDb(db, key);
                    return alive;
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

    /**
     * 六型通删：一个键名下六张表全清一遍，连它在时刻表里的那一行一起回收。
     * 回的是"本来有没有一个还活着的键"。
     * <p>
     * 这一问原先长在协议层（{@code CommandHandler.deleteEveryType}），store 只答 String 那一族，
     * 于是"摘掉过期记录"这件事在四个集合支路里**根本没有对应的动作** —— 不是漏写，是那里
     * 没有可写的地方。时刻从 {@code ValueWrapper} 搬进每个库的键空间表之后，六型通删才在
     * store 层有了落点：{@link #typeOfDb} 的惰性删除从此能对六种类型做同一件事。
     * <p>
     * 上游的对应动作是 {@code expireIfNeeded} 里的 {@code dbDelete} —— 删的是整个键，
     * 不存在"只抹时刻、键留着"。
     */
    public boolean removeAnyType(int db, String key) {
        boolean removed = delDb(db, key);
        if (hashStores[db].del(key)) removed = true;
        if (listStores[db].del(key)) removed = true;
        if (setStores[db].del(key)) removed = true;
        if (sortedSetStores[db].del(key)) removed = true;
        com.zifang.z.cache.core.stream.StreamStore s = streams;
        if (s != null && s.remove(db, key)) removed = true;
        clearExpireAtDb(db, key);
        return removed;
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
            if (isExpiredDb(db, key)) {
                stringStores[db].remove(key);
                clearExpireAtDb(db, key);
                return false;
            }
            return true;
        }
        switch (type) {
            case STRING:
                ValueWrapper wrapper = stringStores[db].get(key);
                return wrapper != null && !isExpiredDb(db, key);
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

    /**
     * 这个库里当前还活着的键名，一枚键名只出现一次 —— 判活只问 {@link #typeOfDb} 那一把尺。
     * <p>
     * 上游之所以只有一把尺：一个键名在 {@code db->dict} 里就是一条目（{@code db.c}），
     * 过期挂在同库的 {@code db->expire} 里，"能不能看见"从来不是按类型分头问的。
     * <p>
     * 两阶段是有意的：先把六张表的键名**抄进**一个集合，再逐个问尺。反过来的写法
     * （一边遍历 {@code hashStores[db].keys()} 一 {@code remove}）就要吃各 store 是否返回快照
     * 这个未知数了 —— 现在不吃。{@code typeOfDb} 会顺手做惰性删除，这是它作为唯一尺的职责。
     */
    public Set<String> liveKeys(int db) {
        Set<String> names = new java.util.LinkedHashSet<>();
        names.addAll(stringStores[db].keySet());
        names.addAll(hashStores[db].keys());
        names.addAll(listStores[db].keys());
        names.addAll(setStores[db].keys());
        names.addAll(sortedSetStores[db].keys());
        names.addAll(streamKeys(db));
        Set<String> live = new java.util.LinkedHashSet<>();
        for (String key : names) {
            if (typeOfDb(db, key) != DataType.NONE) {
                live.add(key);
            }
        }
        return live;
    }

    public List<String> keysDb(int db, String pattern) {
        List<String> result = new ArrayList<>();
        if (pattern == null) {
            return result;
        }
        // 上游 keysCommand 在匹配器之外先判一句快路（5.0.14 db.c:545 `allkeys = (pattern[0] == '*'
        // && plen == 1)`，:550 才进 stringmatchlen）。这一格不是省时间：空串键名在 `*` 底下必须收，
        // 而 stringmatchlen 的入口条件（util.c:51 `while(patternLen && stringLen)`）把它判成不匹配 ——
        // 实测参照那一台（4.0.9）`KEYS *` 回得出空串键名，就是这个快路给的。
        boolean allKeys = isMatchAllPattern(pattern);
        for (String key : liveKeys(db)) {
            if (allKeys || keyPatternMatches(pattern, key)) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * 键图案的唯一一把尺 —— {@code KEYS} 与 {@code SCAN MATCH} 都只走这里。
     * <p>
     * 上游这两家也是同一把：{@code db.c:550}（keysCommand）与 {@code db.c:748}（scanGenericCommand）
     * 打的都是 {@code stringmatchlen(pat, patlen, key, ..., 0)}，那个尾参数是 nocase=0，
     * <b>区分大小写</b>。带 {@code nocase=1} 的那一家只有 {@code CONFIG GET}（{@code config.c:1296}
     * 的 {@code stringmatch(..., 1)}），它走 {@code CommandHandler} 那一侧，不共用这个方法。
     * <p>
     * 这里以前打的是 {@code globToRegex(...)} + {@code String.matches}：那套文法对
     * {@code [..]}、{@code \}、未闭合的集、前缀式写法都不是同一个语言。13r 拿 2928 个
     * {@code (图案, 键名)} 对逐对问过一台活的参照，其中 52 格是"匹配器本身判对了、交付的那条码没接上它"。
     */
    private static boolean keyPatternMatches(String pattern, String key) {
        return RedisGlob.matches(pattern, key, false);
    }

    /** 上面那句快路：图案恰好一根 {@code *} 时不过匹配器（上游两处各写一遍，db.c:545、db.c:663）。 */
    private static boolean isMatchAllPattern(String pattern) {
        return pattern.length() == 1 && pattern.charAt(0) == '*';
    }

    // ==================== SCAN 迭代器 ====================

    /**
     * 游标扫描键：键集取自 {@link #liveKeys}（与 KEYS / DBSIZE 同一把尺），游标走的是
     * 排序之后的那一份。
     *
     * @param db      数据库编号
     * @param cursor  游标，0 表示开始扫描
     * @param pattern 匹配模式
     * @param count   预期返回数量
     * @return [nextCursor, matchedKeys]
     */
    public Object[] scan(int db, String cursor, String pattern, int count) {
        // 与 KEYS / DBSIZE 共用 {@link #liveKeys} 一把尺：以前这三家各自遍历六张表，
        // 于是 SCAN 用 HashSet 去重而 KEYS 不去重 —— 同一枚并存键名两句给出两个条数。
        Set<String> allKeys = liveKeys(db);

        // 与 KEYS 同一把尺（{@link #keyPatternMatches}），连那根单独 `*` 的快路也一起抄
        // （上游 db.c:663 `use_pattern = !(pat[0] == '*' && patlen == 1)` —— 它把"图案等于 *"
        // 直接当成"没带 MATCH"，于是空串键名照样扫得出来）。没带 MATCH 就是 pattern == null，
        // 上游那一侧对应 db.c:630 的 `use_pattern = 0`。
        boolean usePattern = pattern != null && !isMatchAllPattern(pattern);
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
            if (!usePattern || keyPatternMatches(pattern, key)) {
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
        // 一枚键名算一个键，不论它落在几张表里、不论它是六种类型中的哪一种 —— 与 KEYS / SCAN
        // 同一个口径，判活只问 liveKeys 里那把尺（以前这里是 String 支路自己判活、
        // 其余四支直接拿各 store 的 dbsize() 加总，既不去重也不过期）。
        return liveKeys(db).size();
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
        expirations[db].clear();
        // stream 表也在这一问的范围里：DBSIZE / KEYS 现在数得到 stream 键，
        // 那么 FLUSHDB 之后这两个必须归零，否则同一把尺在两句里给出两个数。
        com.zifang.z.cache.core.stream.StreamStore s = streams;
        if (s != null) {
            s.flushDb(db);
        }
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
                    // 时刻跟着键走：原来它是 wrapper 的一栏、随对象一起搬，现在得自己换个键名。
                    long expireAt = expireAtDb(db, oldKey);
                    clearExpireAtDb(db, oldKey);
                    setExpireAtDb(db, newKey, expireAt);
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
     * 过期时刻对六种类型一起搬：上游 {@code moveCommand}（{@code db.c:919}）按的是键而不是类型 ——
     * {@code getExpire} 在 :957 取一次，:965 挂到目标库，:969 才 {@code dbDelete} 源库，全程没有
     * 一个类型分支。判据 1 那一句以前只在 String 上成立，因为集合键根本挂不上过期；五种集合键的
     * TTL 接进 {@link #armExpiry} 之后，搬库必须带着那一行，否则"搬完就没过期"是一种谁也发现不
     * 了的静默行为变化。
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
        // 时刻先取下来，值搬完之后再挂到目标库。顺序不能反：三个集合腿在搬的过程中会
        // `xStores[fromDb].del(key)`，那一步的"键没了"通告（见 {@link #wireExpiryRecycling}）
        // 顺手就把源库这一行回收了 —— 先搬后读就成了"搬过去发现没挂过期"。
        // 上游同样是"先取后搬"：{@code db.c:957} 的 {@code expire = getExpire(c->db, key)}
        // 排在 :964 的 {@code dbAdd(dst, …)} 之前。
        boolean armed = hasExpirationDb(fromDb, key);
        long expireAt = armed ? expireAtDb(fromDb, key) : -1L;
        boolean moved;
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
                moved = wrapper != null;
                break;
            }
            case HASH: {
                Map<String, byte[]> fields = hashStores[fromDb].hgetall(key);
                hashStores[fromDb].del(key);
                hashStores[toDb].hmset(key, fields);
                moved = true;
                break;
            }
            case LIST: {
                List<byte[]> elements = listStores[fromDb].lrange(key, 0, -1);
                listStores[fromDb].del(key);
                listStores[toDb].rpush(key, elements.toArray(new byte[0][]));
                moved = true;
                break;
            }
            case SET: {
                List<byte[]> members = setStores[fromDb].smembers(key);
                setStores[fromDb].del(key);
                setStores[toDb].sadd(key, members.toArray(new byte[0][]));
                moved = true;
                break;
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
                moved = true;
                break;
            }
            case STREAM: {
                // 挪的是 Stream 对象本身（表顶、消费组、PEL 一起走），不是条目快照 ——
                // 上游 moveCommand 换的是 dict 里的 value 指针（{@code db.c:964} 直接把同一个
                // robj 挂进 dst），没有"照条目重建一遍"这一说。
                com.zifang.z.cache.core.stream.StreamStore s = streams;
                moved = s != null && s.moveTo(fromDb, toDb, key);
                break;
            }
            default:
                moved = false;
        }
        if (moved) {
            // 过期时刻跟着键一起搬库，六种类型共用这一个尾巴（判据见本方法注释第 1 条）：
            // 上游 {@code moveCommand} 是 :965 {@code setExpire(c, dst, …)} 再 :969
            // {@code dbDelete(src, …)}（源库那一行由 {@code dbDelete → dbSyncDelete} 顺带收走，
            // 它先删 {@code db->expires} 再删 {@code db->dict}，{@code db.c:271-281}）。
            // 这里两步的先后正好相反 —— 先从源库摘、再挂到目标库：上游要按那个顺序是因为
            // {@code dbDelete} 会 decrRefCount，值必须先被目标 dict 持有；我们的时刻表按库分栏，
            // {@code expirations[fromDb]} 与 {@code expirations[toDb]} 是两张互不相干的表，
            // 谁先谁后都不会串台，可观测结果一致。
            clearExpireAtDb(fromDb, key);
            if (armed) {
                setExpireAtDb(toDb, key, expireAt);
            }
        }
        return moved;
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
     * 保留 {@code keep} 那一种，把键名上其余五种清干净。
     * <p>
     * {@code ZUNIONSTORE}/{@code ZINTERSTORE} 覆盖目标键要的就是这一把：Redis 在那里走
     * {@code dbOverwrite}，先把旧值整个换成新 zset，所以目标键原本挂着 string/hash/list/set/stream
     * 都不报 WRONGTYPE（实测 {@code SET d x; ZUNIONSTORE d 1 k 1 => 1} 且 {@code TYPE d => zset}）。
     * 目标键同时又是源键是这条命令的合法写法，因此调用方必须先读完源再清 —— 见
     * {@code SortedSetStore.aggregateStore}。
     */
    public void clearOtherTypes(int db, String key, DataType keep) {
        if (keep != DataType.STRING) {
            synchronized (stringStores[db]) {
                stringStores[db].remove(key);
                keyTypeMaps[db].remove(key);
                clearExpireAtDb(db, key);
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
        // 上游的 dbOverwrite 换的是 dict 里那个 value 指针：旧对象不管是什么类型都整个没掉。
        // 这一条对 stream 同样是"顶掉"，不是"并存" —— 少了这一行，SET 一个 stream 键会留下
        // 一个 TYPE 报 string、XLEN 报 WRONGTYPE、DEL 只删得掉一半的键名。
        if (keep != DataType.STREAM && streams != null) {
            streams.remove(db, key);
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
                clearExpireAtDb(db, candidate);
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
        clearExpireAtDb(db, victim);
        evictions.incrementAndGet();
    }

    /**
     * String 那一族"读一条还活着的值"的唯一漏斗：判过点的键在这里就地消失，
     * 连带它挂在时刻表里的记录一起（原来那条记录长在对象上、随对象没掉）。
     * 所以 {@code APPEND}/{@code SETBIT}/{@code SETRANGE}/{@code INCR*} 这些原地改写的路
     * 不必搬运过期时刻 —— 活着的键，记录本来就在表里。
     */
    private ValueWrapper getLiveWrapper(int db, String key) {
        ValueWrapper wrapper = stringStores[db].get(key);
        if (wrapper != null && isExpiredDb(db, key)) {
            stringStores[db].remove(key, wrapper);
            keyTypeMaps[db].remove(key);
            clearExpireAtDb(db, key);
            return null;
        }
        return wrapper;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    /**
     * 值包装类：一条 String 的值，外加访问时间（近似 LRU / LFU 淘汰要用）。
     * <p>
     * 这里<b>没有</b>过期时间那一栏。它原来长在值上，于是"键能挂过期"这件事结构上就只有
     * String 一种型做得到 —— 六种键型共用一个 {@code ValueWrapper} 是不成立的，
     * 五种集合键的 TTL 因此在命令层根本没有地方落（见本类的 {@code expirations}）。
     */
    public static class ValueWrapper {
        public final byte[] data;
        /** 最后访问时间（毫秒），用于近似 LRU 淘汰策略 */
        public volatile long lastAccessTime;
        /** 访问次数（用于 LFU 淘汰策略） */
        public volatile long accessCount;
        /** Morris 计数器值（对数计数，用于 LFU 近似频率） */
        public volatile int lfuCounter;

        public ValueWrapper(byte[] data) {
            this.data = data;
            this.lastAccessTime = System.currentTimeMillis();
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
