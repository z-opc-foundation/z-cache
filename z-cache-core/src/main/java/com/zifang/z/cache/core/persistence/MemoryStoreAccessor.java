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
    public Map<String, Object> getAllStringEntries() {
        Map<String, Object> result = new HashMap<>();
        // 从 DB 0 导出 String 数据（简化：仅导出 DB0）
        Map<String, MemoryStore.ValueWrapper> stringStore = store.getStringStore(0);
        for (Map.Entry<String, MemoryStore.ValueWrapper> entry : stringStore.entrySet()) {
            MemoryStore.ValueWrapper wrapper = entry.getValue();
            if (wrapper != null && !wrapper.isExpired() && wrapper.data != null) {
                result.put(entry.getKey(), wrapper.data.clone());
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> getAllHashEntries() {
        Map<String, Object> result = new HashMap<>();
        HashStore hashStore = store.getHashStore(0);
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
    public Map<String, Object> getAllListEntries() {
        Map<String, Object> result = new HashMap<>();
        ListStore listStore = store.getListStore(0);
        for (String key : listStore.keys()) {
            List<byte[]> entries = listStore.lrange(key, 0, -1);
            result.put(key, entries);
        }
        return result;
    }

    @Override
    public Map<String, Object> getAllSetEntries() {
        Map<String, Object> result = new HashMap<>();
        SetStore setStore = store.getSetStore(0);
        for (String key : setStore.keys()) {
            List<byte[]> members = setStore.smembers(key);
            result.put(key, new LinkedHashSet<>(members));
        }
        return result;
    }

    @Override
    public Map<String, Object> getAllSortedSetEntries() {
        Map<String, Object> result = new HashMap<>();
        SortedSetStore sortedSetStore = store.getSortedSetStore(0);
        for (String key : sortedSetStore.keys()) {
            List<byte[]> range = sortedSetStore.zrange(key, 0, -1, true);
            // zrange with WITHSCORES returns [member, score, member, score, ...]
            Map<byte[], Double> memberScores = new LinkedHashMap<>();
            for (int i = 0; i < range.size(); i += 2) {
                byte[] member = range.get(i);
                double score = Double.parseDouble(new String(range.get(i + 1), StandardCharsets.UTF_8));
                memberScores.put(member, score);
            }
            result.put(key, memberScores);
        }
        return result;
    }

    @Override
    public Map<String, Long> getAllExpirationEntries() {
        // 简化实现：返回空 map，过期信息存储在 ValueWrapper 中
        return new HashMap<>();
    }

    @Override
    public void restoreString(String key, byte[] value, long expireAt) {
        if (expireAt > 0) {
            long millis = expireAt - System.currentTimeMillis();
            if (millis > 0) {
                store.psetexDb(0, key, millis, value);
            } else {
                store.setDb(0, key, value);
            }
        } else {
            store.setDb(0, key, value);
        }
    }

    @Override
    public void restoreHash(String key, Map<byte[], byte[]> entries, long expireAt) {
        HashStore hashStore = store.getHashStore(0);
        Map<String, byte[]> stringEntries = new HashMap<>();
        for (Map.Entry<byte[], byte[]> e : entries.entrySet()) {
            stringEntries.put(new String(e.getKey(), StandardCharsets.UTF_8), e.getValue());
        }
        hashStore.hmset(key, stringEntries);
    }

    @Override
    public void restoreList(String key, List<byte[]> entries, long expireAt) {
        ListStore listStore = store.getListStore(0);
        byte[][] array = entries.toArray(new byte[0][]);
        listStore.rpush(key, array);
    }

    @Override
    public void restoreSet(String key, Set<byte[]> members, long expireAt) {
        SetStore setStore = store.getSetStore(0);
        byte[][] array = members.toArray(new byte[0][]);
        setStore.sadd(key, array);
    }

    @Override
    public void restoreSortedSet(String key, Map<byte[], Double> memberScores, long expireAt) {
        SortedSetStore sortedSetStore = store.getSortedSetStore(0);
        for (Map.Entry<byte[], Double> entry : memberScores.entrySet()) {
            sortedSetStore.zadd(key, entry.getValue(), entry.getKey());
        }
    }
}
