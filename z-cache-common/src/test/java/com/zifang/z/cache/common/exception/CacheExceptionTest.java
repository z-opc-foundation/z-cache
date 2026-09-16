package com.zifang.z.cache.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheException 单元测试
 */
class CacheExceptionTest {

    @Test
    void testConstructorWithMessage() {
        CacheException ex = new CacheException("test message");
        assertEquals("test message", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void testConstructorWithMessageAndCause() {
        Throwable cause = new IllegalStateException("root");
        CacheException ex = new CacheException("test", cause);
        assertEquals("test", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void testConstructorWithCause() {
        Throwable cause = new RuntimeException("root");
        CacheException ex = new CacheException(cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    void testIsRuntimeException() {
        CacheException ex = new CacheException("test");
        assertTrue(ex instanceof RuntimeException);
        assertTrue(ex instanceof Exception);
        assertTrue(ex instanceof Throwable);
    }

    @Test
    void testThrowAndCatch() {
        assertThrows(CacheException.class, () -> {
            throw new CacheException("test");
        });
    }

    @Test
    void testNestedCause() {
        Throwable root = new IllegalStateException("root");
        Throwable middle = new RuntimeException("middle", root);
        CacheException ex = new CacheException("top", middle);
        assertSame(middle, ex.getCause());
        assertSame(root, ex.getCause().getCause());
    }

    @Test
    void testNullMessage() {
        CacheException ex = new CacheException((String) null);
        assertNull(ex.getMessage());
    }

    @Test
    void testEmptyMessage() {
        CacheException ex = new CacheException("");
        assertEquals("", ex.getMessage());
    }

    @Test
    void testStackTrace() {
        CacheException ex = new CacheException("test");
        StackTraceElement[] trace = ex.getStackTrace();
        assertNotNull(trace);
        assertTrue(trace.length > 0);
    }

    @Test
    void testSuppression() {
        CacheException ex = new CacheException("main");
        Throwable suppressed = new RuntimeException("suppressed");
        ex.addSuppressed(suppressed);
        assertEquals(1, ex.getSuppressed().length);
        assertSame(suppressed, ex.getSuppressed()[0]);
    }

    @Test
    void testToString() {
        CacheException ex = new CacheException("msg");
        String str = ex.toString();
        assertNotNull(str);
        assertTrue(str.contains("CacheException"));
        assertTrue(str.contains("msg"));
    }
}