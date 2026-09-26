package com.zifang.z.cache.core.stream;

import com.zifang.z.cache.common.protocol.StreamIdFormat;

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
     * 把组的"最后投递位"整段搬到给定位置 —— 上游 {@code t_stream.c:1898} 的
     * {@code cg->last_id = id} 就是这一件事，两段一起换。
     * <p>
     * 只换毫秒段会让 {@code XGROUP SETID k g 7} 这种"没写 seq"的位置停在旧 seq 上：
     * 上游的解析器在那里补的是 {@code missing_seq}（:1199），交回来已经是 {@code 7-0}。
     */
    public void setLastDelivered(long ms, long seq) {
        this.lastDeliveredId = ms;
        this.lastDeliveredSeq = seq;
    }

    /**
     * 这条 entry ID 是否比组里"最后投递"的位置更新。entry ID 是 {@code ms-seq} 二元组，
     * 必须两段一起比。
     * <p>
     * 两段一律带着位模式比，不许先拼成字符串再解析回去：这里的字段是 uint64，
     * {@code 18446744073709551615} 在 Java 只能存成 {@code -1}，拼出来就是 {@code "-1--1"}，
     * 而 {@link StreamIdFormat#parse} 按上游 {@code string2ll} 的口径把负数当越界拒收
     * （{@code "-1"} 恰好是上游"带空格才收、裸写反倒不收"的那一支），退成 {@code {0,0}}
     * 之后组顶变成了流起点 —— {@code XGROUP SETID k g +} 之后 {@code XREADGROUP … >}
     * 会把整个流重投一遍，而对岸一条都不投。
     */
    public boolean isNewerThanLastDelivered(String entryId) {
        long[] parsed = StreamEntry.parseId(entryId);
        return StreamIdFormat.compare(parsed[0], parsed[1], lastDeliveredId, lastDeliveredSeq) > 0;
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
     * <p>
     * {@code noack} 那一支对应上游 {@code STREAM_RWR_NOACK}（:1468 置位、:1615 传下去、
     * :1020 那一整块 PEL 写入被跳过）。注意它<b>只</b>跳 PEL：组的投递位置照样要推进
     * （:990-992 那一问排在 NOACK 之外），消费者照样要被建出来并被 touch
     * （:1610 用的是 {@code SLC_NONE}，而 :1745-1758 那个函数"查不到就顺手创建"）。
     * 所以 NOACK 读过的条目既不进 {@code XPENDING} 的账，也不留在消费者本地历史里，
     * 但 {@code XINFO CONSUMERS} 仍要看到这个名字。
     */
    public void markDelivered(String entryId, String consumerName, boolean noack) {
        if (!noack) pendingEntries.put(entryId, consumerName);
        // 两段一起推进：只抬毫秒段会让同一毫秒内的后几条永远投不出去
        if (isNewerThanLastDelivered(entryId)) {
            long[] parsed = StreamEntry.parseId(entryId);
            lastDeliveredId = parsed[0];
            lastDeliveredSeq = parsed[1];
        }
        Consumer consumer = getOrCreateConsumer(consumerName);
        if (!noack) consumer.incrementPendingCount();
        consumer.touch();
    }

    /**
     * 组名 / 消费者名的序。上游两张表都是一棵 rax，{@code XINFO} 的两支（:2568 CONSUMERS、
     * :2594 GROUPS）都是 {@code raxSeek("^")} + {@code raxNext} 的顺序遍历，交回来的行因此是
     * <b>按名字升序</b>而不是建组的先后；rax 比的是 SDS 的字节。
     * <p>
     * 我们的名字是 UTF-8 解码出来的 String，按 {@link String#compareTo}（UTF-16 码元序）比会在
     * 辅助平面与字节序错位：U+2B000 的头一个码元是 {@code \uD86C}，比 U+F000 小，而按字节它更大。
     * 所以这里按<b>码点</b>比 —— 对能原样往返的 UTF-8 来说码点序就是字节序。
     */
    public static final java.util.Comparator<String> NAME_ORDER = ConsumerGroup::compareNames;

    private static int compareNames(String a, String b) {
        int ia = 0;
        int ib = 0;
        while (ia < a.length() && ib < b.length()) {
            int ca = a.codePointAt(ia);
            int cb = b.codePointAt(ib);
            if (ca != cb) return Integer.compare(ca, cb);
            ia += Character.charCount(ca);
            ib += Character.charCount(cb);
        }
        // 一方是另一方的前缀：短的在前（memcmp 走到长度边界时短的那个先结束）
        return Integer.compare(a.length() - ia, b.length() - ib);
    }

    /**
     * 每个消费者手上还压着多少条没 ACK，<b>含 0 条的</b>，按名字升序。
     * <p>
     * 数字从 {@link #pendingEntries} 现算，不去信那个自增计数器——两边口径一旦漂移，
     * XPENDING 报的就是一份对不上账的数。
     * <p>
     * 列 0 条的是给 XINFO CONSUMERS 用的（上游 t_stream.c:2568 按 {@code raxSize(cg->consumers)}
     * 整份列出），而 XPENDING 的汇总<b>不</b>列 0 条的（:2086 那句 {@code continue}），那一跳留在
     * 命令层。两边今天其实互相冗余：XINFO 那一支自己遍历 {@code getConsumers()}，
     * 计数走 {@code getOrDefault(…, 0L)}，所以把种子行整段摘掉（探针 U4）或把那道兜底换成裸
     * {@code get(…)}（探针 U7）单独打都不红 —— 等价变异；<b>两支一起打才炸</b>，XINFO 的计数
     * 拿到 null。留着的理由就落在这对备份上：种子行与兜底删一侧不改行为，同时删才是缺陷，
     * 而 :2086 那一跳要有东西可跳才是它被量到的形状。
     */
    public Map<String, Long> perConsumerPending() {
        // 序按 NAME_ORDER，而不是 TreeMap 的自然序：XPENDING 那一圈交回的消费者行也要跟
        // 上游的 rax 序对得上（见上面那份注释）。
        Map<String, Long> counts = new java.util.TreeMap<>(NAME_ORDER);
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
