package com.zifang.z.cache.core.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stream 存储管理器 — 管理所有 Stream 键及其数据。
 *
 * <p>类似 {@link com.zifang.z.cache.core.storage.HashStore}，
 * StreamStore 是 Stream 数据类型的存储后端，由 CommandHandler 调用。
 *
 * @author zifang
 * @since 1.3.0
 */
public class StreamStore {

    /** 每个数据库的 Stream 存储 */
    private final ConcurrentHashMap<String, Stream>[] stores;

    @SuppressWarnings("unchecked")
    public StreamStore(int dbCount) {
        stores = new ConcurrentHashMap[dbCount];
        for (int i = 0; i < dbCount; i++) {
            stores[i] = new ConcurrentHashMap<>();
        }
    }

    /**
     * 获取指定数据库中的 Stream，不存在则返回 null。
     */
    public Stream getStream(int db, String key) {
        return stores[db].get(key);
    }

    /**
     * 获取或创建指定数据库中的 Stream。
     */
    public Stream getOrCreate(int db, String key) {
        return stores[db].computeIfAbsent(key, k -> new Stream());
    }

    /**
     * 检查 key 是否存在。
     */
    public boolean exists(int db, String key) {
        return stores[db].containsKey(key);
    }

    /**
     * 删除 key。
     */
    public boolean remove(int db, String key) {
        return stores[db].remove(key) != null;
    }

    /**
     * 获取 Stream 数量。
     */
    public long dbsize(int db) {
        return stores[db].size();
    }

    /**
     * 清空指定数据库的所有 Stream。
     */
    public void flushDb(int db) {
        stores[db].clear();
    }

    // ==================== XADD ====================

    /**
     * XADD 实现：向 Stream 添加条目。
     *
     * @param db     数据库索引
     * @param key    Stream 键
     * @param fields 字段映射
     * @param id     条目 ID（"*" 自动生成）
     * @param maxLen 最大长度（0 = 不裁剪）
     * @return 生成的条目 ID，失败返回 null
     */
    public String xadd(int db, String key, Map<String, String> fields, String id, long maxLen) {
        Stream stream = getOrCreate(db, key);
        String entryId = stream.addEntry(fields, id);
        if (maxLen > 0) {
            stream.trim(maxLen);
        }
        return entryId;
    }

    // ==================== XRANGE / XREVRANGE ====================

    public List<StreamEntry> xrange(int db, String key, String start, String end, int count) {
        Stream stream = getStream(db, key);
        if (stream == null) return new ArrayList<>();
        return stream.range(start, end, count);
    }

    public List<StreamEntry> xrevrange(int db, String key, String start, String end, int count) {
        Stream stream = getStream(db, key);
        if (stream == null) return new ArrayList<>();
        return stream.revRange(start, end, count);
    }

    // ==================== XLEN ====================

    public long xlen(int db, String key) {
        Stream stream = getStream(db, key);
        return stream == null ? 0 : stream.length();
    }

    // ==================== XDEL ====================

    public long xdel(int db, String key, String... ids) {
        Stream stream = getStream(db, key);
        if (stream == null) return 0;
        return stream.delete(ids);
    }

    // ==================== XTRIM ====================

    public long xtrim(int db, String key, long maxLen) {
        Stream stream = getStream(db, key);
        if (stream == null) return 0;
        return stream.trim(maxLen);
    }

    // ==================== 消费组 ====================

    public boolean xgroupCreate(int db, String key, String groupName, String startId) {
        Stream stream = getOrCreate(db, key);
        return stream.createGroup(groupName, startId);
    }

    public boolean xgroupDestroy(int db, String key, String groupName) {
        Stream stream = getStream(db, key);
        if (stream == null) return false;
        return stream.destroyGroup(groupName);
    }

    // ==================== XREADGROUP ====================

    /**
     * XREADGROUP 实现：从消费组读取新条目。
     *
     * @param db         数据库索引
     * @param group      消费组名
     * @param consumer   消费者名
     * @param streams    key -> startId 映射（startId = ">" 表示新条目）
     * @param count      最大返回条数
     * @param blockMs    阻塞时间（0 = 不阻塞）
     * @return key -> 条目列表 映射
     */
    public Map<String, List<StreamEntry>> xreadgroup(int db, String group, String consumer,
                                                      Map<String, String> streams, int count) {
        Map<String, List<StreamEntry>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : streams.entrySet()) {
            String key = entry.getKey();
            String startId = entry.getValue();
            Stream stream = getStream(db, key);
            if (stream == null) continue;

            ConsumerGroup cg = stream.getGroup(group);
            if (cg == null) continue;

            ConsumerGroup.Consumer c = cg.getOrCreateConsumer(consumer);

            if (">".equals(startId)) {
                // 读取新条目（大于 lastDeliveredId）
                List<StreamEntry> allEntries = stream.getEntries();
                List<StreamEntry> newEntries = new ArrayList<>();
                for (StreamEntry e : allEntries) {
                    if (e.getTimestamp() > cg.getLastDeliveredId()) {
                        newEntries.add(e);
                        cg.markDelivered(e.getId(), consumer);
                        if (count > 0 && newEntries.size() >= count) break;
                    }
                }
                if (!newEntries.isEmpty()) {
                    result.put(key, newEntries);
                }
            } else {
                // 重新投递 pending 条目（指定 ID）
                List<StreamEntry> pending = stream.range(startId, "+", count);
                if (!pending.isEmpty()) {
                    result.put(key, pending);
                }
            }
        }
        return result;
    }

    // ==================== XACK ====================

    public long xack(int db, String key, String group, String... ids) {
        Stream stream = getStream(db, key);
        if (stream == null) return 0;
        ConsumerGroup cg = stream.getGroup(group);
        if (cg == null) return 0;
        return cg.ack(ids);
    }

    // ==================== XPENDING ====================

    /**
     * XPENDING 实现：获取消费组的待确认消息摘要。
     *
     * @return [pendingCount, lowestId, highestId, consumers[]]
     */
    public Object[] xpending(int db, String key, String group) {
        Stream stream = getStream(db, key);
        if (stream == null) return null;
        ConsumerGroup cg = stream.getGroup(group);
        if (cg == null) return null;

        Map<String, String> pending = cg.getPendingEntries();
        if (pending.isEmpty()) {
            return new Object[]{0, null, null, new String[0]};
        }

        String lowestId = null;
        String highestId = null;
        for (String id : pending.keySet()) {
            if (lowestId == null || StreamEntry.compareIds(id, lowestId) < 0) lowestId = id;
            if (highestId == null || StreamEntry.compareIds(id, highestId) > 0) highestId = id;
        }

        return new Object[]{pending.size(), lowestId, highestId, cg.getConsumers().keySet().toArray(new String[0])};
    }
}
