package com.zifang.z.cache.core.stream;

import com.zifang.z.cache.common.protocol.StreamIdFormat;

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
     * 解析 ID 为 [timestamp, sequence]，两段都是 uint64 的位模式。
     *
     * <p>文法只有一处：{@link StreamIdFormat}。这里过去是裸 {@code Long.parseLong}，
     * 于是 {@code 18446744073709551615-1}（对岸收的写法）抛 {@code NumberFormatException}，
     * 而那个异常一路冒到 handler 的 {@code catch (Exception)}，客户端拿到的是
     * {@code -ERR For input string: "18446744073709551615"}——Java 的内部文本。
     *
     * <p>非法写法在这里退成 {@code {0,0}} 而不是抛出：命令这一侧已经在入口处按每个位置的
     * strict / missingSeq 判过一轮（{@code CommandHandler} 的 streamIdArg），走到这里的
     * 串都该是合法的；这一支兜底只服务内部调用，不参与对客判定。
     */
    public static long[] parseId(String id) {
        long[] parsed = StreamIdFormat.parse(id, 0L, false);
        return parsed == null ? new long[]{0L, 0L} : parsed;
    }

    /**
     * 比较两个 ID 的大小（用于范围查询）。
     *
     * @return 负数 = id1 &lt; id2，0 = 相等，正数 = id1 &gt; id2
     */
    public static int compareIds(String id1, String id2) {
        long[] p1 = parseId(id1);
        long[] p2 = parseId(id2);
        return StreamIdFormat.compare(p1[0], p1[1], p2[0], p2[1]);
    }

    /**
     * 判断 entry ID 是否在 [start, end] 范围内（包含边界）。
     */
    public static boolean inRange(String entryId, String start, String end) {
        return compareIds(entryId, start) >= 0 && compareIds(entryId, end) <= 0;
    }

    @Override
    public String toString() {
        return "StreamEntry{id='" + id + "', fields=" + fields + '}';
    }
}
