package com.zifang.z.cache.core.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stream 数据结构（对应 Redis Stream 类型）。
 *
 * <p>内部使用 {@link CopyOnWriteArrayList} 保证读多写少场景下的线程安全。
 * 条目按 ID 严格递增排列。
 *
 * @author zifang
 * @since 1.3.0
 */
public class Stream {

    /** 条目列表（按 ID 递增排列） */
    private final CopyOnWriteArrayList<StreamEntry> entries;

    /** 消费组名称 -> 消费组 */
    private final ConcurrentHashMap<String, ConsumerGroup> groups;

    /** 自动 ID 的毫秒时间戳计数器（保证单调递增） */
    private final AtomicLong lastTimestamp;

    /** 自动 ID 的序列号（同一毫秒内递增） */
    private final AtomicLong lastSequence;

    /** 最大条目数（0 = 不限制，XTRIM 使用） */
    private volatile long maxLen;

    public Stream() {
        this.entries = new CopyOnWriteArrayList<>();
        this.groups = new ConcurrentHashMap<>();
        this.lastTimestamp = new AtomicLong(0);
        this.lastSequence = new AtomicLong(0);
        this.maxLen = 0;
    }

    // ==================== XADD ====================

    /**
     * 添加条目到 Stream。
     *
     * @param fields 条目字段
     * @param id     指定 ID，"*" 表示自动生成
     * @return 生成的条目 ID
     */
    public String addEntry(Map<String, String> fields, String id) {
        String entryId;
        if ("*".equals(id)) {
            entryId = generateId();
        } else {
            entryId = id;
            // 更新计数器以确保后续 auto-id 大于指定 id
            long[] parsed = StreamEntry.parseId(entryId);
            updateCounters(parsed[0], parsed[1]);
        }
        StreamEntry entry = new StreamEntry(entryId, fields);
        entries.add(entry);
        return entryId;
    }

    // ==================== XRANGE / XREVRANGE ====================

    /**
     * 按 ID 范围查询条目（升序）。
     */
    public List<StreamEntry> range(String start, String end, int count) {
        List<StreamEntry> result = new ArrayList<>();
        for (StreamEntry entry : entries) {
            if (StreamEntry.inRange(entry.getId(), start, end)) {
                result.add(entry);
                if (count > 0 && result.size() >= count) break;
            }
        }
        return result;
    }

    /**
     * 按 ID 范围查询条目（降序）。
     */
    public List<StreamEntry> revRange(String start, String end, int count) {
        List<StreamEntry> result = new ArrayList<>();
        List<StreamEntry> all = range(start, end, 0);
        for (int i = all.size() - 1; i >= 0 && (count <= 0 || result.size() < count); i--) {
            result.add(all.get(i));
        }
        return result;
    }

    // ==================== XLEN ====================

    public long length() {
        return entries.size();
    }

    // ==================== XDEL ====================

    /**
     * 删除指定 ID 的条目。
     *
     * @param ids 要删除的 ID 数组
     * @return 实际删除的数量
     */
    public long delete(String... ids) {
        long deleted = 0;
        for (String id : ids) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getId().equals(id)) {
                    entries.remove(i);
                    deleted++;
                    break;
                }
            }
        }
        return deleted;
    }

    // ==================== XTRIM ====================

    /**
     * 裁剪 Stream 到指定最大长度（保留最新条目）。
     *
     * @param maxLen 最大长度
     * @return 被删除的条目数
     */
    public long trim(long maxLen) {
        this.maxLen = maxLen;
        // maxLen==0 是"清空"，不是"不动"：Redis 的 XTRIM key MAXLEN 0 会删光全部条目。
        long toRemove = maxLen < 0 ? 0 : entries.size() - maxLen;
        if (toRemove <= 0) {
            return 0;
        }
        for (long i = 0; i < toRemove; i++) {
            entries.remove(0);
        }
        return toRemove;
    }

    // ==================== 消费组 ====================

    /**
     * 创建消费组。
     *
     * @param groupName  组名
     * @param startId    起始 ID（"0" 表示从头，"$" 表示从最新）
     * @return true 创建成功；false 组已存在
     */
    public boolean createGroup(String groupName, String startId) {
        if (groups.containsKey(groupName)) {
            return false;
        }
        long[] start = StreamEntry.parseId("$".equals(startId)
                ? (entries.isEmpty() ? "0-0" : entries.get(entries.size() - 1).getId())
                : startId);
        groups.put(groupName, new ConsumerGroup(groupName, start[0], start[1]));
        return true;
    }

    /**
     * 删除消费组。
     */
    public boolean destroyGroup(String groupName) {
        return groups.remove(groupName) != null;
    }

    /**
     * 获取消费组。
     */
    public ConsumerGroup getGroup(String groupName) {
        return groups.get(groupName);
    }

    /**
     * 获取所有消费组名称。
     */
    public java.util.Set<String> groupNames() {
        return Collections.unmodifiableSet(groups.keySet());
    }

    // ==================== 工具 ====================

    /**
     * 生成自增 ID：{@code <timestamp>-<seq>}。
     */
    private synchronized String generateId() {
        long now = System.currentTimeMillis();
        if (now > lastTimestamp.get()) {
            lastTimestamp.set(now);
            lastSequence.set(0);
        } else {
            lastSequence.incrementAndGet();
        }
        return lastTimestamp.get() + "-" + lastSequence.get();
    }

    /**
     * 更新计数器以确保后续 auto-id 大于给定值。
     */
    private synchronized void updateCounters(long ts, long seq) {
        if (ts > lastTimestamp.get() || (ts == lastTimestamp.get() && seq >= lastSequence.get())) {
            lastTimestamp.set(ts);
            lastSequence.set(seq);
        }
    }

    public long getMaxLen() { return maxLen; }
    public List<StreamEntry> getEntries() { return Collections.unmodifiableList(entries); }
}
