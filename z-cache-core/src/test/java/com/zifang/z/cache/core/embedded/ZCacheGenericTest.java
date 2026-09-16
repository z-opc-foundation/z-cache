package com.zifang.z.cache.core.embedded;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZCache&lt;K,V&gt; 泛型缓存单元测试。
 * 验证任意 Serializable 类型对象的存取、TTL、NX。
 */
class ZCacheGenericTest {

    // ==================== 基本泛型存取 ====================

    @Test
    void testSetAndGetIntegerKeyString() {
        ZCache<Integer, String> cache = ZCache.<Integer, String>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            cache.set(1, "一");
            cache.set(2, "二");
            assertEquals("一", cache.get(1));
            assertEquals("二", cache.get(2));
            assertNull(cache.get(3));
            assertEquals(2, cache.size());
        } finally {
            cache.close();
        }
    }

    @Test
    void testCustomObject() {
        ZCache<String, User> cache = ZCache.<String, User>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            User user = new User("张三", 25);
            cache.set("user:1", user);

            User retrieved = cache.get("user:1");
            assertNotNull(retrieved);
            assertEquals("张三", retrieved.name);
            assertEquals(25, retrieved.age);
        } finally {
            cache.close();
        }
    }

    @Test
    void testListOfStrings() {
        ZCache<String, ArrayList<String>> cache = ZCache.<String, ArrayList<String>>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            ArrayList<String> colors = new ArrayList<>(Arrays.asList("red", "green", "blue"));
            cache.set("colors", colors);
            ArrayList<String> retrieved = cache.get("colors");
            assertNotNull(retrieved);
            assertEquals(3, retrieved.size());
            assertEquals("red", retrieved.get(0));
        } finally {
            cache.close();
        }
    }

    // ==================== TTL + 泛型 ====================

    @Test
    void testTtlWithObject() throws InterruptedException {
        ZCache<String, Integer> cache = ZCache.<String, Integer>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            cache.set("count", 42, 1, TimeUnit.SECONDS);
            assertEquals(42, cache.get("count"));
            assertTrue(cache.ttl("count") > 0);

            Thread.sleep(1100);
            assertNull(cache.get("count"));
            assertEquals(-2, cache.ttl("count"));
        } finally {
            cache.close();
        }
    }

    // ==================== NX + 泛型 ====================

    @Test
    void testSetIfAbsent() {
        ZCache<String, String> cache = ZCache.<String, String>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            assertTrue(cache.setIfAbsent("key", "first"));
            assertEquals("first", cache.get("key"));

            assertFalse(cache.setIfAbsent("key", "second"));
            assertEquals("first", cache.get("key"));
        } finally {
            cache.close();
        }
    }

    // ==================== Delete / Exists ====================

    @Test
    void testDeleteWithIntegerKey() {
        ZCache<Integer, String> cache = ZCache.<Integer, String>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            cache.set(1, "a");
            cache.set(2, "b");
            assertTrue(cache.delete(1));
            assertFalse(cache.delete(1));
            assertNull(cache.get(1));
            assertEquals("b", cache.get(2));
        } finally {
            cache.close();
        }
    }

    @Test
    void testExistsWithCustomKey() {
        ZCache<String, Integer> cache = ZCache.<String, Integer>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            cache.set("x", 1);
            assertTrue(cache.exists("x"));
            assertFalse(cache.exists("y"));
        } finally {
            cache.close();
        }
    }

    // ==================== Builder + maxSize ====================

    @Test
    void testBuilderMaxSize() {
        ZCache<String, String> cache = ZCache.<String, String>newBuilder()
                .maxSize(5)
                .cleanupIntervalSec(0)
                .build();
        try {
            // 超过容量后淘汰一个非当前写入键，保证缓存不会无限增长
            for (int i = 0; i < 10; i++) {
                cache.set("k" + i, "v" + i);
            }
            assertEquals(5, cache.size());
        } finally {
            cache.close();
        }
    }

    // ==================== 统计 ====================

    @Test
    void testStats() {
        ZCache<String, String> cache = ZCache.<String, String>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            cache.set("a", "1");
            cache.get("a");      // hit
            cache.get("a");      // hit
            cache.get("miss");   // miss

            assertEquals(2, cache.getHitCount());
            assertEquals(1, cache.getMissCount());
            assertEquals(2.0 / 3.0, cache.getHitRate(), 0.001);
        } finally {
            cache.close();
        }
    }

    @Test
    void testFlushResetsStats() {
        ZCache<String, String> cache = ZCache.<String, String>newBuilder()
                .cleanupIntervalSec(0)
                .build();
        try {
            cache.set("a", "1");
            cache.get("a");
            cache.get("miss");

            cache.flush();
            assertEquals(0, cache.getHitCount());
            assertEquals(0, cache.getMissCount());
            assertEquals(0, cache.size());
        } finally {
            cache.close();
        }
    }

    // ==================== Close ====================

    @Test
    void testClose() {
        ZCache<String, String> cache = ZCache.<String, String>newBuilder().build();
        assertFalse(cache.isClosed());
        cache.close();
        assertTrue(cache.isClosed());
    }

    @Test
    void testCloseIdempotent() {
        ZCache<String, String> cache = ZCache.<String, String>newBuilder().build();
        cache.close();
        assertDoesNotThrow(cache::close);
    }

    @Test
    void testOperationsAfterCloseThrow() {
        ZCache<String, String> cache = ZCache.<String, String>newBuilder().build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.set("k", "v"));
        assertThrows(IllegalStateException.class, () -> cache.get("k"));
        assertThrows(IllegalStateException.class, () -> cache.delete("k"));
        assertThrows(IllegalStateException.class, () -> cache.exists("k"));
        assertThrows(IllegalStateException.class, () -> cache.ttl("k"));
        assertThrows(IllegalStateException.class, () -> cache.size());
        assertThrows(IllegalStateException.class, cache::flush);
    }

    // ==================== 快捷方法 ====================

    @Test
    void testNewStringCacheShortcut() {
        StringZCache cache = ZCache.newStringCache();
        assertNotNull(cache);
        cache.set("k", "v");
        assertEquals("v", cache.get("k"));
        cache.close();
    }

    @Test
    void testNewStringCacheWithParams() {
        StringZCache cache = ZCache.newStringCache(50, 30);
        assertNotNull(cache);
        cache.set("k", "v");
        assertEquals("v", cache.get("k"));
        cache.close();
    }

    // ==================== 数据类 ====================

    static class User implements Serializable {
        private static final long serialVersionUID = 1L;
        String name;
        int age;

        User(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }
}
