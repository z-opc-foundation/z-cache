package com.zifang.z.cache.core.stream;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stream 条目（对应 Redis Stream 的一条消息）。
 *
 * <p>每个条目有一个唯一 ID，格式为 {@code <millisecondsTime>-<sequenceNumber>}。
 * ID 由 Stream 自动生成（auto-id）或由客户端指定。
 *
 * @author zifang
 * @since 1.3.0
 */
public final class StreamEntry {

    /** 条目 ID（如 "1695000000000-0"） */
    private final String id;

    /** 条目字段（key-value 对） */
    private final Map<String, String> fields;

    /** 条目生成的时间戳（毫秒），从 ID 解析 */
    private final long timestamp;

    /** 序列号，从 ID 解析 */
    private final long sequence;

    /**
     * 创建 Stream 条目。
     *
     * @param id     条目 ID
     * @param fields 字段映射
     */
    public StreamEntry(String id, Map<String, String> fields) {
        this.id = id;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        long[] parsed = parseId(id);
        this.timestamp = parsed[0];
        this.sequence = parsed[1];
    }

    public String getId() { return id; }
    public Map<String, String> getFields() { return fields; }
    public long getTimestamp() { return timestamp; }
    public long getSequence() { return sequence; }

    /**
     * 解析 ID 为 [timestamp, sequence]。
     */
    public static long[] parseId(String id) {
        if (id == null || id.isEmpty()) {
            return new long[]{0, 0};
        }
        int dash = id.indexOf('-');
        if (dash < 0) {
            return new long[]{Long.parseLong(id), 0};
        }
        long ts = Long.parseLong(id.substring(0, dash));
        long seq = Long.parseLong(id.substring(dash + 1));
        return new long[]{ts, seq};
    }

    /**
     * 比较两个 ID 的大小（用于范围查询）。
     *
     * @return 负数 = id1 < id2，0 = 相等，正数 = id1 > id2
     */
    public static int compareIds(String id1, String id2) {
        long[] p1 = parseId(id1);
        long[] p2 = parseId(id2);
        int cmp = Long.compare(p1[0], p2[0]);
        return cmp != 0 ? cmp : Long.compare(p1[1], p2[1]);
    }

    /**
     * 判断 entry ID 是否在 [start, end] 范围内（包含边界）。
     */
    public static boolean inRange(String entryId, String start, String end) {
        if ("+".equals(start)) start = "9999999999999-9999999";
        if ("-".equals(end)) end = "0-0";
        return compareIds(entryId, start) >= 0 && compareIds(entryId, end) <= 0;
    }

    @Override
    public String toString() {
        return "StreamEntry{id='" + id + "', fields=" + fields + '}';
    }
}
