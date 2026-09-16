package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RespBulkString 单元测试
 */
class RespBulkStringTest {

    @Test
    void testFactoryOfString() {
        RespBulkString value = RespBulkString.of("hello");
        assertEquals("hello", value.getString());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), value.getData());
        assertFalse(value.isNull());
        assertFalse(value.isEmpty());
    }

    @Test
    void testFactoryOfBytes() {
        byte[] data = new byte[]{1, 2, 3};
        RespBulkString value = RespBulkString.of(data);
        assertArrayEquals(data, value.getData());
    }

    @Test
    void testFactoryOfNullString() {
        RespBulkString value = RespBulkString.of((String) null);
        assertTrue(value.isNull());
        assertNull(value.getString());
        assertNull(value.getData());
    }

    @Test
    void testFactoryOfNullBytes() {
        RespBulkString value = RespBulkString.of((byte[]) null);
        assertTrue(value.isNull());
        assertNull(value.getString());
    }

    @Test
    void testEmptyString() {
        RespBulkString value = RespBulkString.of("");
        assertEquals("", value.getString());
        assertEquals(0, value.length());
        assertTrue(value.isEmpty());
        assertFalse(value.isNull());
    }

    @Test
    void testEmptySingleton() {
        assertSame(RespBulkString.empty(), RespBulkString.empty());
        assertTrue(RespBulkString.empty().isEmpty());
    }

    @Test
    void testNullBulkString() {
        assertSame(RespBulkString.nullBulkString(), RespBulkString.nullBulkString());
        assertTrue(RespBulkString.nullBulkString().isNull());
    }

    @Test
    void testLength() {
        assertEquals(5, RespBulkString.of("hello").length());
        assertEquals(0, RespBulkString.of("").length());
        assertEquals(-1, RespBulkString.nullBulkString().length());
        assertEquals(-1, RespBulkString.of((String) null).length());
    }

    @Test
    void testIsNull() {
        assertTrue(RespBulkString.nullBulkString().isNull());
        assertFalse(RespBulkString.of("hello").isNull());
        assertFalse(RespBulkString.empty().isNull());
    }

    @Test
    void testIsEmpty() {
        assertTrue(RespBulkString.of("").isEmpty());
        assertTrue(RespBulkString.empty().isEmpty());
        assertFalse(RespBulkString.of("hello").isEmpty());
        // null is not "empty"; it is null
        assertFalse(RespBulkString.nullBulkString().isEmpty());
    }

    @Test
    void testDataDefensiveCopy() {
        byte[] original = new byte[]{1, 2, 3};
        RespBulkString value = RespBulkString.of(original);
        byte[] retrieved = value.getData();

        // Modifying the original should not affect the RespBulkString
        original[0] = 99;
        assertEquals(1, retrieved[0]);
    }

    @Test
    void testEquals() {
        RespBulkString a = RespBulkString.of("hello");
        RespBulkString b = RespBulkString.of("hello");
        RespBulkString c = RespBulkString.of("world");

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "hello");
    }

    @Test
    void testEqualsNulls() {
        // Both null should be equal
        assertEquals(RespBulkString.nullBulkString(), RespBulkString.nullBulkString());
        assertEquals(RespBulkString.nullBulkString().hashCode(),
                RespBulkString.nullBulkString().hashCode());
    }

    @Test
    void testHashCode() {
        assertEquals(RespBulkString.of("hello").hashCode(),
                RespBulkString.of("hello").hashCode());
    }

    @Test
    void testToString() {
        RespBulkString value = RespBulkString.of("hello");
        String str = value.toString();
        assertNotNull(str);
        assertTrue(str.contains("hello"));
    }

    @Test
    void testToStringLarge() {
        // Large content should not blow up toString
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("x");
        RespBulkString value = RespBulkString.of(sb.toString());
        String str = value.toString();
        assertNotNull(str);
        assertTrue(str.contains("bytes") || str.contains(sb.substring(0, 50)));
    }

    @Test
    void testUnicodeContent() {
        RespBulkString value = RespBulkString.of("你好");
        assertEquals("你好", value.getString());
    }

    @Test
    void testBytesEquivalentToString() {
        String text = "hello world";
        RespBulkString fromString = RespBulkString.of(text);
        RespBulkString fromBytes = RespBulkString.of(text.getBytes(StandardCharsets.UTF_8));
        assertEquals(fromString, fromBytes);
        assertEquals(fromString.hashCode(), fromBytes.hashCode());
    }
}