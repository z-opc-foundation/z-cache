package com.zifang.z.cache.core.embedded;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StringZCache 单元测试。
 * 覆盖基本操作、TTL 过期、NX/统计、并发、Builder、close 生命周期。
 */
class StringZCacheTest {

    private StringZCache cache;

    @BeforeEach
    void setUp() {
        cache = ZCache.newStringCache();
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    // ==================== Basic Get/Set ====================

    @Test
    void testSetAndGet() {
        cache.set("key", "value");
        assertEquals("value", cache.get("key"));
    }

    @Test
    void testGetMissingKey() {
        assertNull(cache.get("missing"));
    }

    @Test
    void testSetOverwrite() {
        cache.set("key", "v1");
        cache.set("key", "v2");
        assertEquals("v2", cache.get("key"));
    }

    @Test
    void testSetWithTtlSeconds() {
        cache.set("key", "value", 60);
        assertEquals("value", cache.get("key"));
    }

    @Test
    void testSetWithTtlAndUnit() {
        cache.set("key", "value", 2, TimeUnit.SECONDS);
        assertEquals("value", cache.get("key"));
    }

    // ==================== TTL / Expire / Persist ====================

    @Test
    void testTtl永不过期() {
        cache.set("key", "value");
        assertEquals(-1, cache.ttl("key"), "key without TTL should return -1");
    }

    @Test
    void testTtlMissingKey() {
        assertEquals(-2, cache.ttl("missing"));
    }

    @Test
    void testTtlRemainingPositive() {
        cache.set("key", "value", 10, TimeUnit.SECONDS);
        long ttl = cache.ttl("key");
        assertTrue(ttl > 0 && ttl <= 10, "TTL should be 1..10, got " + ttl);
    }

    @Test
    void testExpireExistingKey() {
        cache.set("key", "value");
        assertTrue(cache.expire("key", 120));
        long ttl = cache.ttl("key");
        assertTrue(ttl > 0 && ttl <= 120);
    }

    @Test
    void testExpireMissingKey() {
        assertFalse(cache.expire("missing", 100));
    }

    @Test
    void testPersistRemovesExpiration() {
        cache.set("key", "value", 100, TimeUnit.SECONDS);
        assertTrue(cache.persist("key"));
        assertEquals(-1, cache.ttl("key"));
    }

    @Test
    void testPersistKeyWithoutTtl() {
        cache.set("key", "value");
        assertFalse(cache.persist("key"), "PERSIST should return false when key has no TTL");
    }

    @Test
    void testExpiredKeyReturnsNull() throws InterruptedException {
        cache.set("key", "value", 1, TimeUnit.SECONDS);
        Thread.sleep(1100);
        assertNull(cache.get("key"));
    }

    @Test
    void testExpiredKeyExistsFalse() throws InterruptedException {
        cache.set("key", "value", 1, TimeUnit.SECONDS);
        Thread.sleep(1100);
        assertFalse(cache.exists("key"));
    }

    @Test
    void testExpiredKeyTtlNegative2() throws InterruptedException {
        cache.set("key", "value", 1, TimeUnit.SECONDS);
        Thread.sleep(1100);
        assertEquals(-2, cache.ttl("key"));
    }

    // ==================== Delete / Exists ====================

    @Test
    void testDeleteExisting() {
        cache.set("key", "value");
        assertTrue(cache.delete("key"));
        assertNull(cache.get("key"));
    }

    @Test
    void testDeleteMissing() {
        assertFalse(cache.delete("missing"));
    }

    @Test
    void testDeleteMultiple() {
        cache.set("a", "1");
        cache.set("b", "2");
        cache.set("c", "3");
        long deleted = cache.delete("a", "c", "missing");
        assertEquals(2, deleted);
        assertNull(cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertNull(cache.get("c"));
    }

    @Test
    void testExists() {
        cache.set("key", "value");
        assertTrue(cache.exists("key"));
        assertFalse(cache.exists("missing"));
    }

    @Test
    void testExistsAfterDelete() {
        cache.set("key", "value");
        cache.delete("key");
        assertFalse(cache.exists("key"));
    }

    // ==================== NX (setIfAbsent) ====================

    @Test
    void testSetIfAbsentOnMissingKey() {
        assertTrue(cache.setIfAbsent("key", "value"));
        assertEquals("value", cache.get("key"));
    }

    @Test
    void testSetIfAbsentOnExistingKey() {
        cache.set("key", "old");
        assertFalse(cache.setIfAbsent("key", "new"));
        assertEquals("old", cache.get("key"));
    }

    @Test
    void testSetIfAbsentWithTtl() {
        assertTrue(cache.setIfAbsent("key", "value", 60, TimeUnit.SECONDS));
        assertTrue(cache.ttl("key") > 0);
    }

    // ==================== Size / Clear / Flush ====================

    @Test
    void testSize() {
        assertEquals(0, cache.size());
        cache.set("a", "1");
        cache.set("b", "2");
        assertEquals(2, cache.size());
    }

    @Test
    void testSizeIgnoresExpired() throws InterruptedException {
        cache.set("active", "1");
        cache.set("expiring", "2", 1, TimeUnit.SECONDS);
        Thread.sleep(1100);
        assertEquals(1, cache.size());
    }

    @Test
    void testClear() {
        cache.set("a", "1");
        cache.set("b", "2");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
    }

    @Test
    void testFlushResetsStats() {
        cache.set("a", "1");
        cache.get("a");
        cache.get("missing");
        assertTrue(cache.getHitCount() > 0);
        assertTrue(cache.getMissCount() > 0);

        cache.flush();
        assertEquals(0, cache.getHitCount());
        assertEquals(0, cache.getMissCount());
        assertEquals(0, cache.size());
    }

    // ==================== Statistics ====================

    @Test
    void testHitAndMissCounts() {
        cache.set("a", "1");
        cache.get("a");       // hit
        cache.get("a");       // hit
        cache.get("missing"); // miss

        assertEquals(2, cache.getHitCount());
        assertEquals(1, cache.getMissCount());
    }

    @Test
    void testHitRate() {
        cache.set("a", "1");
        cache.get("a");       // hit
        cache.get("missing"); // miss

        assertEquals(0.5, cache.getHitRate(), 0.001);
    }

    @Test
    void testHitRateEmptyCache() {
        assertEquals(0.0, cache.getHitRate());
    }

    // ==================== Builder ====================

    @Test
    void testBuilderCustomConfig() {
        StringZCache custom = StringZCache.builder()
                .maxSize(100)
                .cleanupIntervalSec(0)  // disable background cleanup
                .build();

        custom.set("k", "v");
        assertEquals("v", custom.get("k"));
        assertEquals(1, custom.size());
        custom.close();
    }

    @Test
    void testBuilderDefaultConfig() {
        StringZCache custom = StringZCache.builder().build();
        assertNotNull(custom);
        custom.set("k", "v");
        assertEquals("v", custom.get("k"));
        custom.close();
    }

    // ==================== Close / Lifecycle ====================

    @Test
    void testClose() {
        cache.close();
        assertTrue(cache.isClosed());
    }

    @Test
    void testOperationsAfterCloseThrow() {
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.set("k", "v"));
        assertThrows(IllegalStateException.class, () -> cache.get("k"));
        assertThrows(IllegalStateException.class, () -> cache.delete("k"));
        assertThrows(IllegalStateException.class, () -> cache.exists("k"));
        assertThrows(IllegalStateException.class, () -> cache.ttl("k"));
    }

    @Test
    void testDoubleClose() {
        cache.close();
        assertDoesNotThrow(() -> cache.close()); // idempotent
    }

    // ==================== Concurrency ====================

    @Test
    void testConcurrentReadWrite() throws InterruptedException {
        StringZCache concurrentCache = StringZCache.builder()
                .maxSize(1000)
                .cleanupIntervalSec(0)
                .build();

        int threads = 8;
        int opsPerThread = 200;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int tid = i;
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        String key = "t" + tid + "k" + j;
                        concurrentCache.set(key, "v" + j);
                        String val = concurrentCache.get(key);
                        assertNotNull(val, "Key " + key + " should exist");
                        concurrentCache.delete(key);
                        concurrentCache.exists(key); // after delete, should be false
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        startGate.countDown();
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        concurrentCache.close();
    }

    @Test
    void testConcurrentSetIfAbsent() throws InterruptedException {
        StringZCache concurrentCache = StringZCache.builder()
                .maxSize(1000)
                .cleanupIntervalSec(0)
                .build();

        int threads = 8;
        int opsPerThread = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        // All threads try to set the same key; only one should succeed
                        if (concurrentCache.setIfAbsent("shared", "value")) {
                            successes.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        startGate.countDown();
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(1, successes.get(), "Only one setIfAbsent should succeed on same key");
        assertEquals("value", concurrentCache.get("shared"));
        concurrentCache.close();
    }
}
