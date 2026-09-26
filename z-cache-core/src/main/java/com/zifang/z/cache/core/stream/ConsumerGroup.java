package com.zifang.z.cache.core.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stream 消费组（对应 Redis XGROUP）。
 *
 * <p>每个消费组维护：
 * <ul>
 *   <li>lastDeliveredId — 最后投递的条目 ID</li>
 *   <li>consumers — 消费者名称 -> 消费者状态</li>
 * </ul>
 *
 * @author zifang
 * @since 1.3.0
 */
public class ConsumerGroup {

    /** 组名 */
    private final String name;

    /**
     * 最后投递的条目 ID 的毫秒段（用于 XREADGROUP 的 "&gt;" 查询）。
     * <p>与 {@link #lastDeliveredSeq} 合起来才是一个完整的 entry ID：只看毫秒段时，
     * 同一毫秒内写入的第二条永远投不出去（第一条投递后 ms 被抬到当前值，第二条就不再"更大"了）。
     */
    private volatile long lastDeliveredId;

    /** 最后投递的条目 ID 的序号段 */
    private volatile long lastDeliveredSeq;

    /** 消费者名称 -> 消费者 */
    private final ConcurrentHashMap<String, Consumer> consumers;

    /** 待确认消息（entryId -> consumerName） */
    private final ConcurrentHashMap<String, String> pendingEntries;

    public ConsumerGroup(String name, long lastDeliveredId) {
        this(name, lastDeliveredId, 0L);
    }

    public ConsumerGroup(String name, long lastDeliveredId, long lastDeliveredSeq) {
        this.name = name;
        this.lastDeliveredId = lastDeliveredId;
        this.lastDeliveredSeq = lastDeliveredSeq;
        this.consumers = new ConcurrentHashMap<>();
        this.pendingEntries = new ConcurrentHashMap<>();
    }

    public String getName() { return name; }
    public long getLastDeliveredId() { return lastDeliveredId; }
    public long getLastDeliveredSeq() { return lastDeliveredSeq; }

    /**
     * 这条 entry ID 是否比组里"最后投递"的位置更新。entry ID 是 {@code ms-seq} 二元组，
     * 必须两段一起比。
     */
    public boolean isNewerThanLastDelivered(String entryId) {
        return StreamEntry.compareIds(entryId, lastDeliveredId + "-" + lastDeliveredSeq) > 0;
    }

    /**
     * 获取或创建消费者。
     */
    public Consumer getOrCreateConsumer(String consumerName) {
        return consumers.computeIfAbsent(consumerName, Consumer::new);
    }

    /**
     * 删除消费者，交回<b>它手上还压着多少条没 ACK</b>。
     * <p>
     * 对齐上游 {@code streamDelConsumer}（t_stream.c:1765-1788）：消费者不在就是 0，
     * 在就是它那份 PEL 的大小 —— {@code XGROUP DELCONSUMER} 的答复正是这个数
     * （:1916-1917），不是"删没删掉"。以前这里回 boolean、命令层翻成 1/0，
     * 于是"压着两条没 ACK"和"一条都没有"在客户端看来是同一个答复。
     * <p>
     * 条数从 {@link #pendingEntries} 现数，不信 {@code Consumer.pendingCount} 那个自增计数器
     * （与 {@link #perConsumerPending()} 同一个理由：两边口径一旦漂移，报出去的就是一份对不上账的数）。
     */
    public long destroyConsumer(String consumerName) {
        Consumer removed = consumers.remove(consumerName);
        if (removed == null) return 0L;
        long pending = 0L;
        Iterator<Map.Entry<String, String>> it = pendingEntries.entrySet().iterator();
        while (it.hasNext()) {
            if (consumerName.equals(it.next().getValue())) {
                it.remove();
                pending++;
            }
        }
        return pending;
    }

    /**
     * 标记条目为已投递（加入 pending）。
     */
    public void markDelivered(String entryId, String consumerName) {
        pendingEntries.put(entryId, consumerName);
        // 两段一起推进：只抬毫秒段会让同一毫秒内的后几条永远投不出去
        if (isNewerThanLastDelivered(entryId)) {
            long[] parsed = StreamEntry.parseId(entryId);
            lastDeliveredId = parsed[0];
            lastDeliveredSeq = parsed[1];
        }
        Consumer consumer = getOrCreateConsumer(consumerName);
        consumer.incrementPendingCount();
        consumer.touch();
    }

    /**
     * 每个消费者手上还压着多少条没 ACK（包括 0 条的，Redis 的汇总也列出它们）。
     * 数字从 {@link #pendingEntries} 现算，不去信那个自增计数器——两边口径一旦漂移，
     * XPENDING 报的就是一份对不上账的数。
     */
    public Map<String, Long> perConsumerPending() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (String consumer : consumers.keySet()) {
            counts.put(consumer, 0L);
        }
        for (String consumer : pendingEntries.values()) {
            counts.merge(consumer, 1L, Long::sum);
        }
        return counts;
    }

    /**
     * 这个消费者手上还没 ACK 的条目 ID，按 ID 升序。
     * <p>
     * XREADGROUP 读历史读的就是这一份（上游给每个消费者单独挂了一棵 PEL 基数树，
     * {@code streamReplyWithRangeFromConsumerPEL} 按它的序遍历，t_stream.c:1083-1120），
     * 而不是流里的条目 —— 别的消费者领走的条目不该出现在这里。
     */
    public List<String> pendingIdsOf(String consumerName) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, String> pending : pendingEntries.entrySet()) {
            if (pending.getValue().equals(consumerName)) {
                ids.add(pending.getKey());
            }
        }
        Collections.sort(ids, StreamEntry::compareIds);
        return ids;
    }

    /**
     * 确认条目（XACK）。
     *
     * @param entryIds 要确认的条目 ID
     * @return 成功确认的数量
     */    public long ack(String... entryIds) {
        long count = 0;
        for (String entryId : entryIds) {
            String consumerName = pendingEntries.remove(entryId);
            if (consumerName != null) {
                Consumer consumer = consumers.get(consumerName);
                if (consumer != null) {
                    consumer.decrementPendingCount();
                }
                count++;
            }
        }
        return count;
    }

    /**
     * 获取待确认条目数。
     */
    public int pendingCount() {
        return pendingEntries.size();
    }

    /**
     * 获取 pending entries（XPENDING 使用）。
     */
    public Map<String, String> getPendingEntries() {
        return Collections.unmodifiableMap(pendingEntries);
    }

    /**
     * 获取所有消费者。
     */
    public Map<String, Consumer> getConsumers() {
        return Collections.unmodifiableMap(consumers);
    }

    /**
     * 消费者状态。
     */
    public static class Consumer {
        private final String name;
        private final AtomicLong pendingCount = new AtomicLong(0);
        /**
         * 最近一次"投递给这个消费者"或被创建的时刻。XINFO CONSUMERS 的 idle 由它算：
         * 以前这里是 {@code setIdleTimeMs} 一个调用方都没有的常量 0，等于每次都报"空闲 0 毫秒"。
         */
        private volatile long lastActivityMs = System.currentTimeMillis();

        public Consumer(String name) {
            this.name = name;
        }

        public String getName() { return name; }
        public long getPendingCount() { return pendingCount.get(); }
        public long getIdleTimeMs() { return Math.max(0L, System.currentTimeMillis() - lastActivityMs); }
        public void touch() { this.lastActivityMs = System.currentTimeMillis(); }
        public void incrementPendingCount() { pendingCount.incrementAndGet(); }
        public void decrementPendingCount() { pendingCount.decrementAndGet(); }
    }
}
