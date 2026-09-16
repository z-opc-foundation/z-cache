package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RespSimpleString 单元测试
 */
class RespSimpleStringTest {

    @Test
    void testFactoryOf() {
        RespSimpleString value = RespSimpleString.of("OK");
        assertEquals("OK", value.getValue());
    }

    @Test
    void testOkStatic() {
        assertNotNull(RespSimpleString.ok());
        assertEquals("OK", RespSimpleString.ok().getValue());
        // Should be a stable singleton
        assertSame(RespSimpleString.ok(), RespSimpleString.ok());
    }

    @Test
    void testPongStatic() {
        assertNotNull(RespSimpleString.pong());
        assertEquals("PONG", RespSimpleString.pong().getValue());
        assertSame(RespSimpleString.pong(), RespSimpleString.pong());
    }

    @Test
    void testNullValueRejected() {
        assertThrows(NullPointerException.class, () -> RespSimpleString.of(null));
    }

    @Test
    void testCarriageReturnRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RespSimpleString.of("hello\rworld"));
    }

    @Test
    void testLineFeedRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RespSimpleString.of("hello\nworld"));
    }

    @Test
    void testEquals() {
        RespSimpleString a = RespSimpleString.of("OK");
        RespSimpleString b = RespSimpleString.of("OK");
        RespSimpleString c = RespSimpleString.of("NO");

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "OK");
    }

    @Test
    void testHashCode() {
        // Equal objects must have equal hash codes
        assertEquals(RespSimpleString.of("OK").hashCode(),
                RespSimpleString.of("OK").hashCode());
        assertEquals(RespSimpleString.of("hello").hashCode(),
                RespSimpleString.of("hello").hashCode());
    }

    @Test
    void testToString() {
        RespSimpleString value = RespSimpleString.of("OK");
        String str = value.toString();
        assertNotNull(str);
        assertTrue(str.contains("OK"));
    }

    @Test
    void testEmptyValue() {
        // Empty string is allowed
        RespSimpleString value = RespSimpleString.of("");
        assertEquals("", value.getValue());
    }

    @Test
    void testUnicodeValue() {
        RespSimpleString value = RespSimpleString.of("你好世界");
        assertEquals("你好世界", value.getValue());
    }
}