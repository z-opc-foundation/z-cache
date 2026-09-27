package com.zifang.z.cache.core.persistence;

import com.zifang.z.cache.common.protocol.RedisDoubleFormat;
import com.zifang.z.cache.core.storage.HashStore;
import com.zifang.z.cache.core.storage.ListStore;
import com.zifang.z.cache.core.storage.MemoryStore;
import com.zifang.z.cache.core.storage.SetStore;
import com.zifang.z.cache.core.storage.SortedSetStore;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MemoryStore 与 StoreAccessor 之间的适配器。
 * <p>
 * 将 MemoryStore 的多 DB 存储导出为持久化模块所需的数据格式，
 * 同时支持从持久化数据恢复到 MemoryStore。
 *
 * @author zifang
 * @since 1.0.2
 */
public class MemoryStoreAccessor implements StoreAccessor {

    private final MemoryStore store;

    public MemoryStoreAccessor(MemoryStore store) {
        this.store = store;
    }

    @Override
    public int getDbCount() {
        return store.getDbCount();
    }

    @Override
    public Map<String, Object> getAllStringEntries(int db) {
        Map<String, Object> result = new HashMap<>();
        Map<String, MemoryStore.ValueWrapper> stringStore = store.getStringStore(db);
        for (Map.Entry<String, MemoryStore.ValueWrapper> entry : stringStore.entrySet()) {
            MemoryStore.ValueWrapper wrapper = entry.getValue();
            if (wrapper != null && !store.isExpiredDb(db, entry.getKey()) && wrapper.data != null) {
                result.put(entry.getKey(), wrapper.data.clone());
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> getAllHashEntries(int db) {
        Map<String, Object> result = new HashMap<>();
        HashStore hashStore = store.getHashStore(db);
        for (String key : hashStore.keys()) {
            Map<String, byte[]> entries = hashStore.hgetall(key);
            // 将 String->byte[] 转换为 byte[]->byte[]
            Map<byte[], byte[]> byteEntries = new HashMap<>();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                byteEntries.put(e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue());
            }
            result.put(key, byteEntries);
        }
        return result;
    }

    @Override
    public Map<String, Object> getAllListEntries(int db) {
        Map<String, Object> result = new HashMap<>();
        ListStore listStore = store.getListStore(db);
        for (String key : listStore.keys()) {
            List<byte[]> entries = listStore.lrange(key, 0, -1);
            result.put(key, entries);
        }
        return result;
    }

    @Override
    public Map<String, Object> getAllSetEntries(int db) {
        Map<String, Object> result = new HashMap<>();
        SetStore setStore = store.getSetStore(db);
        for (String key : setStore.keys()) {
            List<byte[]> members = setStore.smembers(key);
            result.put(key, new LinkedHashSet<>(members));
        }
        return result;
    }

    @Override
    public Map<String, Object> getAllSortedSetEntries(int db) {
        Map<String, Object> result = new HashMap<>();
        SortedSetStore sortedSetStore = store.getSortedSetStore(db);
        for (String key : sortedSetStore.keys()) {
            List<byte[]> range = sortedSetStore.zrange(key, 0, -1, true);
            // zrange with WITHSCORES returns [member, score, member, score, ...]
            Map<byte[], Double> memberScores = new LinkedHashMap<>();
            for (int i = 0; i + 1 < range.size(); i += 2) {
                byte[] member = range.get(i);
                double score = RedisDoubleFormat.parse(new String(range.get(i + 1), StandardCharsets.UTF_8));
                memberScores.put(member, score);
            }
            result.put(key, memberScores);
        }
        return result;
    }

    @Override
    public Map<String, Long> getAllExpirationEntries(int db) {
        // 整张时刻表就是答案，一道闸都不加。上游导出时也没有：rdb.c:1188 对 db->dict 里的每个键
        // 无条件 getExpire，连"停机期间已经到点"这一判都不在写侧（rdbSaveKeyValuePair 从头到尾
        // 只有 return 1 与 return -1，它自己 :1008 那句"otherwise 0 (the key was already expired)"
        // 在 5.0.14 里是过期的注释），决定排在加载那一步（rdb.c:2097）。
        // 原来这里跟着 getAllStringEntries 加了一道 isExpiredDb：四种集合的值那半是从裸表枚举的
        // （与上游的 db->dict 同形），于是"已过点但还没人碰过"的集合键被写成 expireAt = -1，
        // 重启之后**变成永久键复活** —— 两张表必须交同一把尺，一边筛一边不筛才是这里的真雷。
        return new HashMap<>(store.expirationSnapshot(db));
    }

    @Override
    public void restoreString(int db, String key, byte[] value, long expireAt) {
        if (expireAt <= 0) {
            store.setDb(db, key, value);
            return;
        }
        if (diedWhileOffline(expireAt)) {
            return;
        }
        store.psetexDb(db, key, expireAt - System.currentTimeMillis(), value);
    }

    /**
     * 四种集合键的时刻：RDB 条目里那一个 long 早就写好了（每一型导出时都取
     * {@code expirationEntries.getOrDefault(key, -1L)} 再 {@code writeLong}，读回是无条件的
     * {@code readLong}），恢复这一侧原先一个字都不读 —— 于是带 TTL 的集合键重启之后变成永久键。
     * 上游那里过期是**键**的属性、与类型无关（{@code rdb.c:1188} 每个键问一次 {@code getExpire}，
     * {@code :1015-1018} 在类型 opcode 之前写 {@code RDB_OPCODE_EXPIRETIME_MS}，{@code :2105}
     * 加载时 {@code setExpire}），停机期间已到点的那一枚则是整键不 {@code dbAdd}
     * （{@code rdb.c:2097}）。所以下面五支（含 {@link #restoreString}）共用同两道闸，
     * 而挂时刻排在写完值之后：{@code MemoryStore.armExpiry} 要先确认键真的在。
     */
    @Override
    public void restoreHash(int db, String key, Map<byte[], byte[]> entries, long expireAt) {
        if (diedWhileOffline(expireAt)) {
            return;
        }
        HashStore hashStore = store.getHashStore(db);
        Map<String, byte[]> stringEntries = new HashMap<>();
        for (Map.Entry<byte[], byte[]> e : entries.entrySet()) {
            stringEntries.put(new String(e.getKey(), StandardCharsets.UTF_8), e.getValue());
        }
        hashStore.hmset(key, stringEntries);
        armAfterRestore(db, key, expireAt);
    }

    @Override
    public void restoreList(int db, String key, List<byte[]> entries, long expireAt) {
        if (diedWhileOffline(expireAt)) {
            return;
        }
        ListStore listStore = store.getListStore(db);
        byte[][] array = entries.toArray(new byte[0][]);
        listStore.rpush(key, array);
        armAfterRestore(db, key, expireAt);
    }

    @Override
    public void restoreSet(int db, String key, Set<byte[]> members, long expireAt) {
        if (diedWhileOffline(expireAt)) {
            return;
        }
        SetStore setStore = store.getSetStore(db);
        byte[][] array = members.toArray(new byte[0][]);
        setStore.sadd(key, array);
        armAfterRestore(db, key, expireAt);
    }

    @Override
    public void restoreSortedSet(int db, String key, Map<byte[], Double> memberScores, long expireAt) {
        if (diedWhileOffline(expireAt)) {
            return;
        }
        SortedSetStore sortedSetStore = store.getSortedSetStore(db);
        for (Map.Entry<byte[], Double> entry : memberScores.entrySet()) {
            sortedSetStore.zadd(key, entry.getValue(), entry.getKey());
        }
        armAfterRestore(db, key, expireAt);
    }

    /**
     * 停机期间那一个时刻已经过去：这一枚在真实 Redis 里不存在了，整键不许复活。
     * 没有这一道闸时"剩余毫秒 &lt;= 0"会被当成永久键写回去，等于凭空复活一份再也删不掉的数据。
     */
    private boolean diedWhileOffline(long expireAt) {
        return expireAt > 0 && expireAt - System.currentTimeMillis() <= 0;
    }

    /** 没有挂过期（{@code expireAt <= 0}）就什么都不做，否则按剩余毫秒挂回去。 */
    private void armAfterRestore(int db, String key, long expireAt) {
        if (expireAt > 0) {
            store.pexpireDb(db, key, expireAt - System.currentTimeMillis());
        }
    }
}
