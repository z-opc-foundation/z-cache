package com.zifang.z.cache.core.stream;

import java.util.ArrayList;
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
     * XREADGROUP 的 {@code ">"} 位：把组里还没投递过的条目投给这个消费者，逐条记进 PEL。
     *
     * @param db       数据库索引
     * @param key      Stream 键
     * @param group    消费组名
     * @param consumer 消费者名（不存在则顺手建出来）
     * @param count    最大条数（{@code <= 0} = 不限）
     * @return 本次投递的条目；键或组不在、或者没有新条目时为空列表
     */
    public List<StreamEntry> xreadgroupNew(int db, String key, String group, String consumer, int count) {
        List<StreamEntry> newEntries = new ArrayList<>();
        Stream stream = getStream(db, key);
        if (stream == null) return newEntries;
        ConsumerGroup cg = stream.getGroup(group);
        if (cg == null) return newEntries;

        cg.getOrCreateConsumer(consumer);
        // 条目 ID 是 ms-seq 两段，只比毫秒段会把同一毫秒内写入的第二条及以后永久卡在组外
        // （XADD 在同一毫秒里连写多条是常态）。
        for (StreamEntry e : stream.getEntries()) {
            if (!cg.isNewerThanLastDelivered(e.getId())) continue;
            newEntries.add(e);
            cg.markDelivered(e.getId(), consumer);
            if (count > 0 && newEntries.size() >= count) break;
        }
        return newEntries;
    }

    /**
     * XREADGROUP 带明确 ID 的历史位：交回这个消费者 PEL 里不小于 {@code fromId} 的条目 ID。
     * <p>
     * 只交 ID，内容要调用方回查 —— 上游也正是分两步：先按 PEL 序取 ID，再拿 ID 去流里找条目，
     * 找不到就明着交回 {@code [id, nil]}（t_stream.c:1098-1109），而不是悄悄少一条。
     *
     * @param fromId 闭区间起点；调用方传进来的已经是"所要位置的下一个 ID"
     * @return 条目 ID 升序列表；空列表 = 这个消费者手上没东西。<b>null</b> 专指键或组不在 ——
     *         命令层已经在 :1505 那一问（{@code -NOGROUP … in XREADGROUP with GROUP option}）
     *         挡过一次，所以这一支只覆盖"两次读之间被并发删掉"这种窗口，不能和空历史混成一件事
     */
    public List<String> xreadgroupHistory(int db, String key, String group, String consumer,
                                          String fromId, int count) {
        Stream stream = getStream(db, key);
        if (stream == null) return null;
        ConsumerGroup cg = stream.getGroup(group);
        if (cg == null) return null;
        // 上游的 streamLookupConsumer(SLC_NONE) 会顺手把消费者建出来（:1745-1757），
        // 所以"读一份没有的历史"也要让这个消费者出现在 XINFO CONSUMERS 里。
        cg.getOrCreateConsumer(consumer);

        List<String> ids = new ArrayList<>();
        for (String id : cg.pendingIdsOf(consumer)) {
            if (StreamEntry.compareIds(id, fromId) < 0) continue;
            ids.add(id);
            if (count > 0 && ids.size() >= count) break;
        }
        return ids;
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
     * <p>
     * 计数一律是 {@code long}：调用方按 {@code (Long)} 取值，这里塞 {@code int}
     * 会在运行时抛 ClassCastException，而这条路径单测摸不到，只有走 socket 才炸。
     *
     * @return [pendingCount(long), lowestId, highestId, consumers[]]
     */
    public Object[] xpending(int db, String key, String group) {
        Stream stream = getStream(db, key);
        if (stream == null) return null;
        ConsumerGroup cg = stream.getGroup(group);
        if (cg == null) return null;

        Map<String, String> pending = cg.getPendingEntries();
        if (pending.isEmpty()) {
            return new Object[]{0L, null, null, new String[0]};
        }

        String lowestId = null;
        String highestId = null;
        for (String id : pending.keySet()) {
            if (lowestId == null || StreamEntry.compareIds(id, lowestId) < 0) lowestId = id;
            if (highestId == null || StreamEntry.compareIds(id, highestId) > 0) highestId = id;
        }

        return new Object[]{(long) pending.size(), lowestId, highestId, cg.getConsumers().keySet().toArray(new String[0])};
    }
}
