package com.zifang.z.cache.core.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stream 核心数据结构单元测试。
 *
 * @author zifang
 * @since 1.3.0
 */
class StreamTest {

    private Stream stream;

    @BeforeEach
    void setUp() {
        stream = new Stream();
    }

    // ==================== XADD ====================

    @Test
    void testAddEntry_AutoId() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("field1", "value1");
        String id = stream.addEntry(fields, "*");
        assertNotNull(id);
        assertTrue(id.contains("-"));
        assertEquals(1, stream.length());
    }

    @Test
    void testAddEntry_SpecificId() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "zifang");
        String id = stream.addEntry(fields, "1000-0");
        assertEquals("1000-0", id);
        assertEquals(1, stream.length());
    }

    @Test
    void testAddEntry_MultipleFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("a", "1");
        fields.put("b", "2");
        fields.put("c", "3");
        stream.addEntry(fields, "*");
        StreamEntry entry = stream.getEntries().get(0);
        assertEquals(3, entry.getFields().size());
        assertEquals("1", entry.getFields().get("a"));
    }

    // ==================== XRANGE ====================

    @Test
    void testRange_Ascending() {
        stream.addEntry(Collections.singletonMap("k", "v1"), "100-0");
        stream.addEntry(Collections.singletonMap("k", "v2"), "200-0");
        stream.addEntry(Collections.singletonMap("k", "v3"), "300-0");

        List<StreamEntry> result = stream.range("-", "+", 0);
        assertEquals(3, result.size());
        assertEquals("100-0", result.get(0).getId());
        assertEquals("300-0", result.get(2).getId());
    }

    @Test
    void testRange_WithCount() {
        for (int i = 1; i <= 10; i++) {
            stream.addEntry(Collections.singletonMap("k", "v" + i), i + "-0");
        }
        List<StreamEntry> result = stream.range("-", "+", 5);
        assertEquals(5, result.size());
    }

    @Test
    void testRevRange_Descending() {
        stream.addEntry(Collections.singletonMap("k", "v1"), "100-0");
        stream.addEntry(Collections.singletonMap("k", "v2"), "200-0");
        stream.addEntry(Collections.singletonMap("k", "v3"), "300-0");

        List<StreamEntry> result = stream.revRange("-", "+", 0);
        assertEquals(3, result.size());
        assertEquals("300-0", result.get(0).getId());
    }

    // ==================== XLEN ====================

    @Test
    void testLength() {
        assertEquals(0, stream.length());
        stream.addEntry(Collections.singletonMap("k", "v"), "*");
        assertEquals(1, stream.length());
        stream.addEntry(Collections.singletonMap("k", "v2"), "*");
        assertEquals(2, stream.length());
    }

    // ==================== XDEL ====================

    @Test
    void testDelete() {
        stream.addEntry(Collections.singletonMap("k", "v1"), "100-0");
        stream.addEntry(Collections.singletonMap("k", "v2"), "200-0");
        long deleted = stream.delete("100-0");
        assertEquals(1, deleted);
        assertEquals(1, stream.length());
    }

    // ==================== XTRIM ====================

    @Test
    void testTrim() {
        for (int i = 1; i <= 5; i++) {
            stream.addEntry(Collections.singletonMap("k", "v" + i), i + "-0");
        }
        long removed = stream.trim(3);
        assertEquals(2, removed);
        assertEquals(3, stream.length());
    }

    // ==================== 消费组 ====================

    @Test
    void testCreateGroup() {
        stream.addEntry(Collections.singletonMap("k", "v1"), "100-0");
        assertTrue(stream.createGroup("mygroup", "0"));
        assertNotNull(stream.getGroup("mygroup"));
    }

    @Test
    void testCreateGroup_Duplicate() {
        stream.createGroup("g1", "0");
        assertFalse(stream.createGroup("g1", "0"));
    }

    @Test
    void testDestroyGroup() {
        stream.createGroup("g1", "0");
        assertTrue(stream.destroyGroup("g1"));
        assertNull(stream.getGroup("g1"));
    }

    // ==================== ConsumerGroup ====================

    @Test
    void testConsumerGroup_Ack() {
        stream.addEntry(Collections.singletonMap("k", "v1"), "100-0");
        stream.createGroup("g1", "0");
        ConsumerGroup cg = stream.getGroup("g1");

        cg.markDelivered("100-0", "consumer1");
        assertEquals(1, cg.pendingCount());

        long acked = cg.ack("100-0");
        assertEquals(1, acked);
        assertEquals(0, cg.pendingCount());
    }

    @Test
    void testConsumerGroup_MultipleConsumers() {
        stream.addEntry(Collections.singletonMap("k", "v1"), "100-0");
        stream.addEntry(Collections.singletonMap("k", "v2"), "200-0");
        stream.createGroup("g1", "0");
        ConsumerGroup cg = stream.getGroup("g1");

        cg.markDelivered("100-0", "c1");
        cg.markDelivered("200-0", "c2");

        assertEquals(2, cg.pendingCount());
        assertEquals(2, cg.getConsumers().size());
    }

    // ==================== StreamStore ====================

    @Test
    void testStreamStore_Xadd() {
        StreamStore store = new StreamStore(1);
        Map<String, String> fields = Collections.singletonMap("name", "test");
        String id = store.xadd(0, "mystream", fields, "*", 0);
        assertNotNull(id);
        assertEquals(1, store.xlen(0, "mystream"));
    }

    @Test
    void testStreamStore_Xrange() {
        StreamStore store = new StreamStore(1);
        store.xadd(0, "s1", Collections.singletonMap("a", "1"), "100-0", 0);
        store.xadd(0, "s1", Collections.singletonMap("a", "2"), "200-0", 0);

        List<StreamEntry> result = store.xrange(0, "s1", "-", "+", 0);
        assertEquals(2, result.size());
    }

    @Test
    void testStreamStore_ConsumerGroup() {
        StreamStore store = new StreamStore(1);
        store.xadd(0, "s1", Collections.singletonMap("a", "1"), "100-0", 0);
        store.xadd(0, "s1", Collections.singletonMap("a", "2"), "200-0", 0);

        assertTrue(store.xgroupCreate(0, "s1", "g1", "0"));

        List<StreamEntry> delivered = store.xreadgroupNew(0, "s1", "g1", "c1", 10);
        assertEquals(2, delivered.size());
        // 投出去的东西不会再投第二遍：组的位置跟着走
        assertTrue(store.xreadgroupNew(0, "s1", "g1", "c1", 10).isEmpty());

        // 历史按消费者各自的 PEL。fromId 是闭区间起点，命令那一侧传进来的是"所要位置的下一个 ID"。
        List<String> c1History = store.xreadgroupHistory(0, "s1", "g1", "c1", "0-1", 0);
        assertEquals(java.util.Arrays.asList("100-0", "200-0"), c1History);
        // c2 一条都没领过：空历史，但不是 null（null 专指键或组不在）
        List<String> c2History = store.xreadgroupHistory(0, "s1", "g1", "c2", "0-1", 0);
        assertEquals(0, c2History.size());
        assertNull(store.xreadgroupHistory(0, "no-such-key", "g1", "c1", "0-1", 0));

        // ACK one
        long acked = store.xack(0, "s1", "g1", "100-0");
        assertEquals(1, acked);
        assertEquals(java.util.Collections.singletonList("200-0"),
                store.xreadgroupHistory(0, "s1", "g1", "c1", "0-1", 0));
    }

    @Test
    void testStreamStore_Xdel() {
        StreamStore store = new StreamStore(1);
        store.xadd(0, "s1", Collections.singletonMap("a", "1"), "100-0", 0);
        store.xadd(0, "s1", Collections.singletonMap("a", "2"), "200-0", 0);

        long deleted = store.xdel(0, "s1", "100-0");
        assertEquals(1, deleted);
        assertEquals(1, store.xlen(0, "s1"));
    }

    /**
     * 表顶（上游的 {@code s->last_id}）与"还活着的最大学 ID"（{@code streamLastValidID}）
     * 是两件事，XDEL 掉最大那条之后才分得开：前者必须留在原处（XADD 的单调性闸要它那样，
     * 否则同一个 ID 能重发一遍），后者要退回去（XREAD 拿它判"这个位置之后还有没有条目真交得出去"，
     * {@code t_stream.c:1586-1593}）。混成一个的两种错法：拿 lastId 去判 XREAD，会给客户端点名
     * 一个列表为空的键；拿 lastValidId 去判 XADD，等于允许 ID 空间倒退。
     */
    @Test
    void testTopAndLastValidIdDivergeAfterDelete() {
        assertNull(stream.lastValidId(), "空流没有存活条目");
        stream.addEntry(Collections.singletonMap("k", "v1"), "100-0");
        stream.addEntry(Collections.singletonMap("k", "v2"), "200-0");
        stream.addEntry(Collections.singletonMap("k", "v2b"), "200-5");
        stream.addEntry(Collections.singletonMap("k", "v3"), "300-0");
        assertArrayEquals(new long[]{300L, 0L}, stream.lastValidId());
        assertArrayEquals(stream.lastId(), stream.lastValidId(), "没删过的时候两者相同");

        assertEquals(1L, stream.delete("300-0"));
        assertArrayEquals(new long[]{200L, 5L}, stream.lastValidId(),
                "毫秒段相同要比序号段：200-5 才是活着的那条最大值，只比毫秒会停在 200-0");
        assertArrayEquals(new long[]{300L, 0L}, stream.lastId(), "表顶不许跟着退");

        stream.delete("100-0", "200-0");
        assertArrayEquals(new long[]{200L, 5L}, stream.lastValidId(),
                "删掉同 ms 的那条不影响最大值——取列表末位会恰好撞上，取「最大值」不会");
        stream.delete("200-5");
        assertNull(stream.lastValidId(), "全删光了就没有存活条目，不是 0-0");
        assertArrayEquals(new long[]{300L, 0L}, stream.lastId());
    }

    // ==================== StreamEntry ID 解析 ====================

    @Test
    void testParseId() {
        long[] parsed = StreamEntry.parseId("1695000000000-5");
        assertEquals(1695000000000L, parsed[0]);
        assertEquals(5, parsed[1]);
    }

    @Test
    void testCompareIds() {
        assertTrue(StreamEntry.compareIds("200-0", "100-0") > 0);
        assertTrue(StreamEntry.compareIds("100-0", "100-1") < 0);
        assertEquals(0, StreamEntry.compareIds("100-0", "100-0"));
    }
}
