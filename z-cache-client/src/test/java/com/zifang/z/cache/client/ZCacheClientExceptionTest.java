package com.zifang.z.cache.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZCacheClientExceptionTest {

    @Test
    void testConstructorWithMessage() {
        String message = "Test error message";
        ZCacheClientException exception = new ZCacheClientException(message);

        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testConstructorWithMessageAndCause() {
        String message = "Test error with cause";
        Throwable cause = new RuntimeException("Root cause");
        ZCacheClientException exception = new ZCacheClientException(message, cause);

        assertEquals(message, exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void testExceptionInheritance() {
        ZCacheClientException exception = new ZCacheClientException("test");

        assertTrue(exception instanceof RuntimeException);
        assertTrue(exception instanceof Exception);
        assertTrue(exception instanceof Throwable);
    }

    @Test
    void testExceptionThrowing() {
        assertThrows(ZCacheClientException.class, () -> {
            throw new ZCacheClientException("Test exception");
        });
    }

    @Test
    void testExceptionWithNestedCause() {
        Throwable rootCause = new IllegalStateException("Root cause");
        Throwable middleCause = new RuntimeException("Middle cause", rootCause);
        ZCacheClientException exception = new ZCacheClientException("Top level", middleCause);

        assertSame(middleCause, exception.getCause());
        assertSame(rootCause, exception.getCause().getCause());
    }

    @Test
    void testExceptionWithNullMessage() {
        ZCacheClientException exception = new ZCacheClientException((String) null);
        assertNull(exception.getMessage());
    }

    @Test
    void testExceptionWithEmptyMessage() {
        ZCacheClientException exception = new ZCacheClientException("");
        assertEquals("", exception.getMessage());
    }

    @Test
    void testExceptionStackTrace() {
        ZCacheClientException exception = new ZCacheClientException("Test");

        StackTraceElement[] stackTrace = exception.getStackTrace();
        assertNotNull(stackTrace);
        assertTrue(stackTrace.length > 0);

        assertEquals(ZCacheClientExceptionTest.class.getName(), stackTrace[0].getClassName());
    }

    @Test
    void testExceptionSuppression() {
        ZCacheClientException exception = new ZCacheClientException("Main");
        Throwable suppressed = new RuntimeException("Suppressed");

        exception.addSuppressed(suppressed);

        Throwable[] suppressedExceptions = exception.getSuppressed();
        assertEquals(1, suppressedExceptions.length);
        assertSame(suppressed, suppressedExceptions[0]);
    }

    @Test
    void testExceptionToString() {
        ZCacheClientException exception = new ZCacheClientException("Test message");
        String toString = exception.toString();

        assertTrue(toString.contains("ZCacheClientException"));
        assertTrue(toString.contains("Test message"));
    }
}
