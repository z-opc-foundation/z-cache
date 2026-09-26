package com.zifang.z.cache.core.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryStore 单元测试 - 全面覆盖字符串/字节操作、过期、统计、并发场景。
 */
class MemoryStoreTest {

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
    }

    // ==================== Basic Set/Get ====================

    @Test
    void testSetAndGetBytes() {
        byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.set("key", value));
        assertArrayEquals(value, store.get("key"));
    }

    @Test
    void testGetString() {
        store.set("key", "world".getBytes(StandardCharsets.UTF_8));
        assertEquals("world", store.getString("key"));
    }

    @Test
    void testGetReturnsDefensiveCopy() {
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        store.set("key", original);
        byte[] retrieved = store.get("key");

        // Modify the original; should not affect stored value
        original[0] = 'X';
        byte[] second = store.get("key");
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), second);
        assertNotSame(retrieved, second);
    }

    @Test
    void testGetMissingKey() {
        assertNull(store.get("missing"));
        assertEquals(1, store.getMisses());
    }

    @Test
    void testGetMissingKeyString() {
        assertNull(store.getString("missing"));
    }

    @Test
    void testSetOverwrite() {
        store.set("key", "v1".getBytes(StandardCharsets.UTF_8));
        store.set("key", "v2".getBytes(StandardCharsets.UTF_8));
        assertEquals("v2", store.getString("key"));
    }

    @Test
    void testSetEmptyValue() {
        store.set("empty", new byte[0]);
        assertEquals("", store.getString("empty"));
        assertArrayEquals(new byte[0], store.get("empty"));
    }

    @Test
    void testSetNullValue() {
        store.set("null-key", null);
        // Null values are stored, then get returns null on missing
        assertNull(store.get("null-key"));
    }

    // ==================== Capacity ====================

    @Test
    void testMaxEntriesEvictsWhenFull() {
        MemoryStore limited = new MemoryStore(2);
        limited.set("a", "1".getBytes(StandardCharsets.UTF_8));
        limited.set("b", "2".getBytes(StandardCharsets.UTF_8));
        limited.set("c", "3".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, limited.dbsize());
        assertEquals(1, limited.getEvictions());
    }


    @Test
    void testDelSingleKey() {
        store.set("k", "v".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.del("k"));
        assertNull(store.get("k"));
    }

    @Test
    void testDelMissingKey() {
        assertFalse(store.del("nonexistent"));
    }

    @Test
    void testDelMultipleKeys() {
        store.set("a", "1".getBytes(StandardCharsets.UTF_8));
        store.set("b", "2".getBytes(StandardCharsets.UTF_8));
        store.set("c", "3".getBytes(StandardCharsets.UTF_8));

        long deleted = store.del("a", "b", "missing");
        assertEquals(2, deleted);
        assertNull(store.get("a"));
        assertNull(store.get("b"));
        assertEquals("3", store.getString("c"));
    }

    @Test
    void testDelNoKeys() {
        long deleted = store.del();
        assertEquals(0, deleted);
    }

    // ==================== Exists ====================

    @Test
    void testExistsTrue() {
        store.set("k", "v".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.exists("k"));
    }

    @Test
    void testExistsFalse() {
        assertFalse(store.exists("nonexistent"));
    }

    @Test
    void testExistsAfterDel() {
        store.set("k", "v".getBytes(StandardCharsets.UTF_8));
        store.del("k");
        assertFalse(store.exists("k"));
    }

    // ==================== TTL / Expiration ====================

    @Test
    void testSetex() {
        store.setex("k", 60, "v".getBytes(StandardCharsets.UTF_8));
        assertEquals("v", store.getString("k"));
    }

    @Test
    void testSetexTtl() {
        store.setex("k", 60, "v".getBytes(StandardCharsets.UTF_8));
        long ttl = store.ttl("k");
        assertTrue(ttl > 0 && ttl <= 60, "TTL should be between 0 and 60, got " + ttl);
    }

    @Test
    void testPsetex() {
        store.psetex("k", 500, "v".getBytes(StandardCharsets.UTF_8));
        assertEquals("v", store.getString("k"));
    }

    @Test
    void testPsetexTtl() {
        store.psetex("k", 10000, "v".getBytes(StandardCharsets.UTF_8));
        long ttl = store.ttl("k");
        assertTrue(ttl > 0 && ttl <= 10, "TTL should be between 0 and 10, got " + ttl);
    }

    @Test
    void testExpireOnExistingKey() {
        store.set("k", "v".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.expire("k", 100));
        long ttl = store.ttl("k");
        assertTrue(ttl > 0 && ttl <= 100);
    }

    @Test
    void testExpireOnMissingKey() {
        assertFalse(store.expire("nonexistent", 100));
    }

    @Test
    void testTtlMissingKey() {
        assertEquals(-2, store.ttl("missing"));
    }

    @Test
    void testTtlKeyWithoutExpiration() {
        store.set("k", "v".getBytes(StandardCharsets.UTF_8));
        assertEquals(-1, store.ttl("k"));
    }

    @Test
    void testPersist() {
        store.set("k", "v".getBytes(StandardCharsets.UTF_8));
        store.expire("k", 100);
        assertTrue(store.persist("k"));
        assertEquals(-1, store.ttl("k"), "Key should have no expiration after PERSIST");
    }

    @Test
    void testPersistOnKeyWithoutTtl() {
        store.set("k", "v".getBytes(StandardCharsets.UTF_8));
        assertFalse(store.persist("k"), "PERSIST should return false when key has no TTL");
    }

    @Test
    void testPersistOnMissingKey() {
        assertFalse(store.persist("missing"));
    }

    // ==================== 过期时刻表（时刻不在值上，1.3.6）====================

    /**
     * 时刻表里不许有"无主"的记录：每条记录的键名，此刻还得是六种类型之一。
     * <p>
     * 过期时刻从值对象搬进"每个库一张表"之后，唯一新出来的风险形状就是这个：表里留着一行，
     * 键名在六个 store 里却已经没了。长在值上的年代不会有这种形状（键没了时刻跟着一起没）。
     * <p>
     * 今天它的代价是两样，都不算协议层的错：白占内存（键早就没了，行永远留着），以及给
     * 下一步埋雷 —— TTL 一旦不再只问 String 表，这条无主的行就会挂到"下一个用这个键名的键"
     * 身上，凭空多出一个从没人设过的过期。要说清楚的是：它现在还漏不进存档，
     * {@code RdbPersistence} 是遍历五张值表再按键名去 {@code getOrDefault} 查时刻的
     * （{@code RdbPersistence.java:463-467}），无主的行压根没人读 —— 所以这一问只能在这一层量，
     * 上面那几层看不见它。
     */
    private static void assertRecordsHaveHost(MemoryStore s, int db, String stage) {
        for (String key : s.expirationSnapshot(db).keySet()) {
            assertNotSame(MemoryStore.DataType.NONE, s.typeOfDb(db, key),
                    stage + "：时刻表里给一个已经不存在的键名留着过期记录 -> " + key);
        }
    }

    @Test
    void expiryRecordLifecycleOfTheStringWritePaths() throws Exception {
        byte[] v = "v".getBytes(StandardCharsets.UTF_8);

        // 正面一杠：挂上过期，表里就得有这一行，而且时刻是真的在未来。
        // 没有这一杠，下面那几句"表里没有记录"用一把恒空的表也能全绿。
        store.set("a", v);
        assertTrue(store.expire("a", 100));
        assertTrue(store.hasExpirationDb(0, "a"), "EXPIRE 之后时刻表里必须有 a");
        assertTrue(store.expireAtDb(0, "a") > System.currentTimeMillis());
        assertRecordsHaveHost(store, 0, "EXPIRE a 100");

        // 整键覆盖：上游 setKey（db.c:216-224）第三条就是把键变回永久，记录必须收走
        store.set("a", "w".getBytes(StandardCharsets.UTF_8));
        assertFalse(store.hasExpirationDb(0, "a"), "SET 覆盖后不许留着旧时刻");
        assertEquals(-1, store.expireAtDb(0, "a"));
        assertRecordsHaveHost(store, 0, "SET 覆盖 a");

        // SETEX 一族：写进去的是新时刻，不是叠一行
        store.psetex("a", 100000, v);
        long first = store.expireAtDb(0, "a");
        assertTrue(first > System.currentTimeMillis());
        store.pexpireDb(0, "a", 200000);
        assertTrue(store.expireAtDb(0, "a") > first, "PEXPIRE 要把时刻换成新的");
        assertRecordsHaveHost(store, 0, "PSETEX + PEXPIRE a");

        // PERSIST：回 true 就得真的摘掉（回 false 时不许顺手把别人的记录清了）
        assertTrue(store.persist("a"));
        assertFalse(store.hasExpirationDb(0, "a"), "PERSIST 之后表里不许还有 a");
        assertRecordsHaveHost(store, 0, "PERSIST a");

        // EXPIRE 0 走的是"当场删"那一路：键没了，记录也不能留
        store.set("b", v);
        assertTrue(store.expire("b", 100));
        assertTrue(store.expire("b", 0), "EXPIRE 0 回 1（battery37 第 28 行实测），因为它删掉了键");
        assertFalse(store.hasExpirationDb(0, "b"), "EXPIRE 0 删了键，记录不能留在表里");
        assertRecordsHaveHost(store, 0, "EXPIRE b 0");

        // 惰性删除：过点了，第一次读它的人负责把两半一起收走
        store.psetex("c", 1, v);
        assertTrue(store.hasExpirationDb(0, "c"));
        Thread.sleep(30);
        assertNull(store.get("c"), "已过点的键读不到");
        assertEquals(-2, store.ttl("c"));
        assertFalse(store.hasExpirationDb(0, "c"), "判过点并删掉值之后，时刻也要一并收走");
        assertRecordsHaveHost(store, 0, "c 过点后被 GET 摸到");
    }

    @Test
    void inPlaceStringWritesCarryTheExpiryRecordWithoutTouchingIt() {
        byte[] v = "v".getBytes(StandardCharsets.UTF_8);
        store.set("a", v);
        assertTrue(store.expire("a", 100));
        long before = store.expireAtDb(0, "a");

        // 原地改写的四路（APPEND / SETBIT / SETRANGE）＋ INCR：这些路以前是"新 wrapper 带着旧
        // expireAt 顶掉老 wrapper"来留 TTL 的。时刻不在值上之后，它们什么都不必做才算对 ——
        // 既不许丢，也不许借这次改写换掉。
        store.append("a", v);
        assertEquals(before, store.expireAtDb(0, "a"), "APPEND 不许动过期时刻");
        store.setbitDb(0, "a", 0, true);
        assertEquals(before, store.expireAtDb(0, "a"), "SETBIT 不许动过期时刻");
        store.setRangeDb(0, "a", 0, v);
        assertEquals(before, store.expireAtDb(0, "a"), "SETRANGE 不许动过期时刻");
        store.set("n", "5".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.expire("n", 100));
        long nBefore = store.expireAtDb(0, "n");
        assertEquals(6L, store.increment("n", 1));
        assertEquals(nBefore, store.expireAtDb(0, "n"), "INCR 不许动过期时刻");
        assertRecordsHaveHost(store, 0, "四路原地改写之后");
    }

    @Test
    void delAndFlushAndTypeCollisionTakeTheRecordWithThem() {
        byte[] v = "v".getBytes(StandardCharsets.UTF_8);
        store.set("a", v);
        assertTrue(store.expire("a", 100));

        // DEL
        assertTrue(store.del("a"));
        assertFalse(store.hasExpirationDb(0, "a"), "DEL 之后时刻表里不许还有 a");
        assertRecordsHaveHost(store, 0, "DEL a");

        // dbOverwrite：键名换成集合型，旧时刻随旧值一起没
        store.set("a", v);
        assertTrue(store.expire("a", 100));
        store.clearOtherTypes(0, "a", MemoryStore.DataType.HASH);
        assertFalse(store.hasExpirationDb(0, "a"), "换成别的类型后不许留着 String 的时刻");
        assertRecordsHaveHost(store, 0, "a 顶成 hash");

        // FLUSHDB
        store.psetex("b", 100000, v);
        assertFalse(store.expirationSnapshot(0).isEmpty());
        store.flushDb(0);
        assertTrue(store.expirationSnapshot(0).isEmpty(), "FLUSHDB 要连时刻表一起清空");
        assertRecordsHaveHost(store, 0, "FLUSHDB");
    }

    @Test
    void moveAndRenameMoveTheRecordInsteadOfLeavingItBehind() {
        byte[] v = "v".getBytes(StandardCharsets.UTF_8);
        store.set("a", v);
        assertTrue(store.expire("a", 100));
        long before = store.expireAtDb(0, "a");

        // MOVE：时刻跟着键搬库（判据在 250 refmv.tr：目标库 TTL 还在、源库回 -2）
        assertTrue(store.moveKeyToDb(0, 3, "a"));
        assertFalse(store.hasExpirationDb(0, "a"), "搬完以后源库不许还留着记录");
        assertEquals(before, store.expireAtDb(3, "a"), "搬库要带着原来的时刻");
        assertRecordsHaveHost(store, 0, "MOVE a 到 db3");
        assertRecordsHaveHost(store, 3, "MOVE a 到 db3");

        // RENAME：换的是键名，时刻一格都没多出来（旧名那一行必须收走）
        store.renameDb(3, "a", "b");
        assertFalse(store.hasExpirationDb(3, "a"), "旧键名那一行不能留在表里");
        assertEquals(before, store.expireAtDb(3, "b"), "RENAME 之后时刻不变");
        assertEquals(1, store.expirationSnapshot(3).size(), "一条键只许有一行记录");
        assertRecordsHaveHost(store, 3, "RENAME a→b");
    }

    /**
     * 淘汰掉一个带过期的键时，那行记录必须跟着走。
     * <p>
     * 挑谁当受害者是随机的（{@code evictOne} 抽样比 LRU），所以这一支不指望某一枚特定的键
     * 被淘汰，而是连写五十次：不管每次挑中谁，写完之后表里剩下的每一行都得还有主人。
     * 摘掉 {@code evictOne} 里那句 {@code clearExpireAtDb} 的话，这里会一次漏下几十行 ——
     * 不是偶发，是必红。
     */
    @Test
    void evictionReclaimsTheVictimsRecord() {
        MemoryStore tiny = new MemoryStore(1);   // 只装得下一个键，第二次写起就开始淘汰
        byte[] v = "v".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 50; i++) {
            tiny.psetex("k" + i, 100000, v);
        }
        assertTrue(tiny.getEvictions() > 0, "这一支的前提是淘汰真的发生了");
        // 淘汰发生了、记录却一行没收回来的话，这里正好数到五十行（每写一次留一行）。
        assertTrue(tiny.expirationSnapshot(0).size() < 50, "写五十次淘汰四十多次，表里不该有五十行");
        // 被淘汰掉的那个键，它的记录不能留在表里当无主的行。
        assertRecordsHaveHost(tiny, 0, "淘汰之后");
    }

    @Test
    void testExpiredKeyGetReturnsNull() throws InterruptedException {
        store.setex("k", 1, "v".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1100);
        assertNull(store.get("k"));
        assertEquals(1, store.getMisses());
    }

    @Test
    void testExpiredKeyExistsFalse() throws InterruptedException {
        store.setex("k", 1, "v".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1100);
        assertFalse(store.exists("k"));
    }

    @Test
    void testExpiredKeyRemovedFromStore() throws InterruptedException {
        store.setex("k", 1, "v".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1100);
        // Trigger removal
        store.get("k");
        // dbsize should not count expired keys
        assertEquals(0, store.dbsize());
    }

    @Test
    void testTtlAfterExpiry() throws InterruptedException {
        store.setex("k", 1, "v".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1100);
        assertEquals(-2, store.ttl("k"));
    }

    // ==================== DBSIZE ====================

    @Test
    void testDbsizeEmpty() {
        assertEquals(0, store.dbsize());
    }

    @Test
    void testDbsizeAfterInserts() {
        store.set("a", "1".getBytes(StandardCharsets.UTF_8));
        store.set("b", "2".getBytes(StandardCharsets.UTF_8));
        store.set("c", "3".getBytes(StandardCharsets.UTF_8));
        assertEquals(3, store.dbsize());
    }

    @Test
    void testDbsizeAfterDelete() {
        store.set("a", "1".getBytes(StandardCharsets.UTF_8));
        store.set("b", "2".getBytes(StandardCharsets.UTF_8));
        store.del("a");
        assertEquals(1, store.dbsize());
    }

    @Test
    void testDbsizeIgnoresExpired() throws InterruptedException {
        store.set("active", "v".getBytes(StandardCharsets.UTF_8));
        store.setex("expiring", 1, "v".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1100);
        assertEquals(1, store.dbsize(), "Only active key should be counted");
    }

    // ==================== Statistics ====================

    @Test
    void testHitsAndMisses() {
        store.set("k", "v".getBytes(StandardCharsets.UTF_8));
        store.get("k");  // hit
        store.get("k");  // hit
        store.get("missing");  // miss
        assertEquals(2, store.getHits());
        assertEquals(1, store.getMisses());
    }

    @Test
    void testFlushResetsAll() {
        store.set("a", "1".getBytes(StandardCharsets.UTF_8));
        store.set("b", "2".getBytes(StandardCharsets.UTF_8));
        store.get("a");
        store.get("missing");

        store.flush();

        assertEquals(0, store.dbsize());
        assertEquals(0, store.getHits());
        assertEquals(0, store.getMisses());
        assertNull(store.get("a"));
    }

    // ==================== Concurrency ====================

    @Test
    void testConcurrentSetAndGet() throws InterruptedException {
        int threadCount = 10;
        int iterationsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        String key = "thread" + threadId + "_key" + j;
                        String value = "v" + j;
                        store.set(key, value.getBytes(StandardCharsets.UTF_8));
                        assertEquals(value, store.getString(key));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertEquals(threadCount * iterationsPerThread, store.dbsize());
    }

    @Test
    void testConcurrentExpireAndGet() throws InterruptedException {
        // Some keys expire while threads try to read them; ensure no exceptions
        int iterations = 50;
        CountDownLatch latch = new CountDownLatch(iterations);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            store.setex("expiring" + i, 1, ("v" + i).getBytes(StandardCharsets.UTF_8));
        }

        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    // Either succeeds or returns null after expiry
                    store.getString("expiring" + idx);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        Thread.sleep(1100);
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
    }

    /**
     * 第六张表是<b>注入</b>进来的（{@code bindStreams}），不是本类自己 new 的 —— 因为
     * {@code StreamStore} 的归属是"一台服务器一份"（{@code ServerScope}），而 {@code MemoryStore}
     * 可以被拆开单用。于是"没接上"那一支在协议层永远不可达，只有拆开了才碰得到，
     * 而它恰恰是最需要钉的一支：嵌入式和单测就是直接 {@code new MemoryStore()} 的，
     * 这一支要是红在 NPE 上，整条 String 路跟着一起倒。
     */
    @Test
    void streamTableIsBlindUntilItIsBound() {
        com.zifang.z.cache.core.stream.StreamStore streams =
                new com.zifang.z.cache.core.stream.StreamStore(16);
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("a", "1");
        assertEquals("1-1", streams.xadd(0, "inj:stream", fields, "1-1", -1));
        assertEquals(1, streams.keySet(0).size(), "流表自己那一侧确实落了一枚键");

        // 没接上：判据一律"没有这个键"，而不是凭猜测给一个数，也不是炸掉
        assertEquals(MemoryStore.DataType.NONE, store.typeOfDb(0, "inj:stream"));
        assertEquals(0L, store.dbsizeDb(0));
        assertTrue(store.keysDb(0, "*").isEmpty());
        store.clearOtherTypes(0, "inj:stream", MemoryStore.DataType.STRING);
        store.flushDb(0);
        assertEquals(1, streams.keySet(0).size(), "没接上就不归本类管，清库也不该动到它");

        // 接上：TYPE / DBSIZE / KEYS 三把尺一起跟上，少任何一把都是另一处"同一键名两问两答"
        store.bindStreams(streams);
        assertEquals(MemoryStore.DataType.STREAM, store.typeOfDb(0, "inj:stream"));
        assertEquals(1L, store.dbsizeDb(0));
        assertEquals(java.util.Collections.singletonList("inj:stream"), store.keysDb(0, "*"));

        // dbOverwrite 那一问也管得到它：SET 顶掉流键时，流整个没掉而不是并存
        store.setDb(0, "inj:stream", "v".getBytes(StandardCharsets.UTF_8));
        assertEquals(MemoryStore.DataType.STRING, store.typeOfDb(0, "inj:stream"));
        assertNull(streams.getStream(0, "inj:stream"), "两半各存一份的话 DEL 只删得掉一半");
        assertEquals(1L, store.dbsizeDb(0), "一个键名只占一格");

        // FLUSHDB 与 DBSIZE 是同一把尺：接上之后它清得动第六张表
        streams.xadd(0, "inj:second", fields, "1-1", -1);
        assertEquals(2L, store.dbsizeDb(0));
        store.flushDb(0);
        assertEquals(0L, store.dbsizeDb(0));
        assertTrue(streams.keySet(0).isEmpty());

        // 拔掉（或压根没接过）就退回注入之前的形状：还是"看不见"，不是一支坏掉的尺
        store.bindStreams(null);
        streams.xadd(0, "inj:third", fields, "1-1", -1);
        assertEquals(MemoryStore.DataType.NONE, store.typeOfDb(0, "inj:third"));
        assertEquals(0L, store.dbsizeDb(0));
    }
}