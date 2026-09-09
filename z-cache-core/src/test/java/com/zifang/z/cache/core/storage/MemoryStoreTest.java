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
}