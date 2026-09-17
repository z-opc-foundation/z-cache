package com.zifang.z.cache.core.logging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 慢查询日志，记录执行时间超过阈值的命令。
 * <p>
 * 使用 {@link ConcurrentLinkedDeque} 实现线程安全的环形缓冲区，
 * 超出最大容量时自动淘汰最早的条目。
 *
 * @author zifang
 * @since 1.0.2
 */
public class SlowLog {

    /**
     * 慢查询阈值（纳秒），默认 10 毫秒。
     */
    private volatile long slowLogThresholdNanos = 10_000_000L;

    /**
     * 最大条目数，默认 128。
     */
    private volatile int maxEntries = 128;

    /**
     * 自增 ID 生成器。
     */
    private final AtomicInteger idGenerator = new AtomicInteger(0);

    /**
     * 慢查询条目存储（双端队列实现环形缓冲区）。
     */
    private final ConcurrentLinkedDeque<SlowLogEntry> entries = new ConcurrentLinkedDeque<>();

    /**
     * 慢查询条目。
     */
    public static class SlowLogEntry {

        /** 条目 ID */
        private final long id;

        /** 记录时间戳（纳秒） */
        private final long timestampNanos;

        /** 命令执行耗时（纳秒） */
        private final long durationNanos;

        /** 命令参数 */
        private final String[] args;

        SlowLogEntry(long id, long timestampNanos, long durationNanos, String[] args) {
            this.id = id;
            this.timestampNanos = timestampNanos;
            this.durationNanos = durationNanos;
            this.args = args;
        }

        public long getId() {
            return id;
        }

        public long getTimestampNanos() {
            return timestampNanos;
        }

        public long getDurationNanos() {
            return durationNanos;
        }

        public String[] getArgs() {
            return args;
        }

        @Override
        public String toString() {
            return "SlowLogEntry{id=" + id
                    + ", durationNanos=" + durationNanos
                    + ", args=" + Arrays.toString(args)
                    + '}';
        }
    }

    /**
     * 记录一条慢查询。仅当执行时间超过阈值时才记录。
     *
     * @param durationNanos 命令执行耗时（纳秒）
     * @param args          命令参数数组
     */
    public void log(long durationNanos, String[] args) {
        if (durationNanos < slowLogThresholdNanos) {
            return;
        }

        SlowLogEntry entry = new SlowLogEntry(
                idGenerator.incrementAndGet(),
                System.nanoTime(),
                durationNanos,
                args
        );

        entries.addLast(entry);

        // 超出最大容量时淘汰最旧的条目
        while (entries.size() > maxEntries) {
            entries.pollFirst();
        }
    }

    /**
     * 获取最近 N 条慢查询日志。
     *
     * @param count 获取条数，-1 表示获取全部
     * @return 慢查询条目列表（从最新到最旧排列）
     */
    public List<SlowLogEntry> get(int count) {
        List<SlowLogEntry> allEntries = new ArrayList<>(entries);
        // 倒序排列（最新在前）
        java.util.Collections.reverse(allEntries);

        if (count < 0 || count >= allEntries.size()) {
            return allEntries;
        }
        return allEntries.subList(0, count);
    }

    /**
     * 获取慢查询日志的条目总数。
     *
     * @return 条目数量
     */
    public int len() {
        return entries.size();
    }

    /**
     * 清空所有慢查询日志并重置 ID。
     */
    public void reset() {
        entries.clear();
        idGenerator.set(0);
    }

    /**
     * 设置慢查询阈值（纳秒）。
     *
     * @param thresholdNanos 阈值，单位纳秒
     */
    public void setSlowLogThresholdNanos(long thresholdNanos) {
        if (thresholdNanos < 0) {
            throw new IllegalArgumentException("threshold cannot be negative");
        }
        this.slowLogThresholdNanos = thresholdNanos;
    }

    /**
     * 获取当前慢查询阈值（纳秒）。
     *
     * @return 阈值，单位纳秒
     */
    public long getSlowLogThresholdNanos() {
        return slowLogThresholdNanos;
    }

    /**
     * 设置最大条目数。超出时自动淘汰最旧的条目。
     *
     * @param maxEntries 最大条目数，必须大于 0
     */
    public void setMaxEntries(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        // 超出新上限时淘汰多余的条目
        while (entries.size() > maxEntries) {
            entries.pollFirst();
        }
    }

    /**
     * 获取当前最大条目数。
     *
     * @return 最大条目数
     */
    public int getMaxEntries() {
        return maxEntries;
    }
}
