package com.zifang.z.cache.core.stream;

import java.util.Collections;
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

    /** 最后投递的条目 ID（用于 XREADGROUP 的 ">" 查询） */
    private volatile long lastDeliveredId;

    /** 消费者名称 -> 消费者 */
    private final ConcurrentHashMap<String, Consumer> consumers;

    /** 待确认消息（entryId -> consumerName） */
    private final ConcurrentHashMap<String, String> pendingEntries;

    public ConsumerGroup(String name, long lastDeliveredId) {
        this.name = name;
        this.lastDeliveredId = lastDeliveredId;
        this.consumers = new ConcurrentHashMap<>();
        this.pendingEntries = new ConcurrentHashMap<>();
    }

    public String getName() { return name; }
    public long getLastDeliveredId() { return lastDeliveredId; }

    /**
     * 获取或创建消费者。
     */
    public Consumer getOrCreateConsumer(String consumerName) {
        return consumers.computeIfAbsent(consumerName, Consumer::new);
    }

    /**
     * 删除消费者。
     */
    public boolean destroyConsumer(String consumerName) {
        Consumer removed = consumers.remove(consumerName);
        if (removed != null) {
            // 移除该消费者的 pending entries
            pendingEntries.values().removeIf(v -> v.equals(consumerName));
            return true;
        }
        return false;
    }

    /**
     * 标记条目为已投递（加入 pending）。
     */
    public void markDelivered(String entryId, String consumerName) {
        pendingEntries.put(entryId, consumerName);
        long[] parsed = StreamEntry.parseId(entryId);
        if (parsed[0] > lastDeliveredId) {
            lastDeliveredId = parsed[0];
        }
        Consumer consumer = getOrCreateConsumer(consumerName);
        consumer.incrementPendingCount();
    }

    /**
     * 确认条目（XACK）。
     *
     * @param entryIds 要确认的条目 ID
     * @return 成功确认的数量
     */
    public long ack(String... entryIds) {
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
        private volatile long idleTimeMs = 0;

        public Consumer(String name) {
            this.name = name;
        }

        public String getName() { return name; }
        public long getPendingCount() { return pendingCount.get(); }
        public long getIdleTimeMs() { return idleTimeMs; }
        public void setIdleTimeMs(long ms) { this.idleTimeMs = ms; }
        public void incrementPendingCount() { pendingCount.incrementAndGet(); }
        public void decrementPendingCount() { pendingCount.decrementAndGet(); }
    }
}
