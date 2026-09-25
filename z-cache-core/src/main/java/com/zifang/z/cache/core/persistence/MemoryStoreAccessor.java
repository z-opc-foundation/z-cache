package com.zifang.z.cache.core.persistence;

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
            if (wrapper != null && !wrapper.isExpired() && wrapper.data != null) {
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
                double score = Double.parseDouble(new String(range.get(i + 1), StandardCharsets.UTF_8));
                memberScores.put(member, score);
            }
            result.put(key, memberScores);
        }
        return result;
    }

    @Override
    public Map<String, Long> getAllExpirationEntries(int db) {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<String, MemoryStore.ValueWrapper> entry : store.getStringStore(db).entrySet()) {
            MemoryStore.ValueWrapper wrapper = entry.getValue();
            if (wrapper != null && !wrapper.isExpired() && wrapper.expireAt > 0) {
                result.put(entry.getKey(), wrapper.expireAt);
            }
        }
        return result;
    }

    @Override
    public void restoreString(int db, String key, byte[] value, long expireAt) {
        if (expireAt <= 0) {
            store.setDb(db, key, value);
            return;
        }
        long remaining = expireAt - System.currentTimeMillis();
        // 过期时间在停机期间到点的话，这个键在真实 Redis 里已经不存在了。
        // 原来这里走的是"剩余毫秒<=0 就当永久键写回去"，等于凭空复活一份再也删不掉的数据。
        if (remaining <= 0) {
            return;
        }
        store.psetexDb(db, key, remaining, value);
    }

    @Override
    public void restoreHash(int db, String key, Map<byte[], byte[]> entries, long expireAt) {
        HashStore hashStore = store.getHashStore(db);
        Map<String, byte[]> stringEntries = new HashMap<>();
        for (Map.Entry<byte[], byte[]> e : entries.entrySet()) {
            stringEntries.put(new String(e.getKey(), StandardCharsets.UTF_8), e.getValue());
        }
        hashStore.hmset(key, stringEntries);
    }

    @Override
    public void restoreList(int db, String key, List<byte[]> entries, long expireAt) {
        ListStore listStore = store.getListStore(db);
        byte[][] array = entries.toArray(new byte[0][]);
        listStore.rpush(key, array);
    }

    @Override
    public void restoreSet(int db, String key, Set<byte[]> members, long expireAt) {
        SetStore setStore = store.getSetStore(db);
        byte[][] array = members.toArray(new byte[0][]);
        setStore.sadd(key, array);
    }

    @Override
    public void restoreSortedSet(int db, String key, Map<byte[], Double> memberScores, long expireAt) {
        SortedSetStore sortedSetStore = store.getSortedSetStore(db);
        for (Map.Entry<byte[], Double> entry : memberScores.entrySet()) {
            sortedSetStore.zadd(key, entry.getValue(), entry.getKey());
        }
    }
}
