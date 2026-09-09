package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RespArray 单元测试
 */
class RespArrayTest {

    @Test
    void testFactoryOfList() {
        List<Object> elements = new ArrayList<>();
        elements.add(RespBulkString.of("GET"));
        elements.add(RespBulkString.of("key"));

        RespArray array = RespArray.of(elements);
        assertFalse(array.isNull());
        assertEquals(2, array.size());
    }

    @Test
    void testFactoryOfVarargs() {
        RespArray array = RespArray.of(
                RespBulkString.of("SET"),
                RespBulkString.of("key"),
                RespBulkString.of("value"));

        assertFalse(array.isNull());
        assertEquals(3, array.size());
    }

    @Test
    void testFactoryOfNullList() {
        RespArray array = RespArray.of((List<Object>) null);
        assertTrue(array.isNull());
        assertEquals(-1, array.size());
        assertNull(array.getElements());
    }

    @Test
    void testFactoryOfNullVarargs() {
        RespArray array = RespArray.of((Object[]) null);
        assertTrue(array.isNull());
    }

    @Test
    void testEmptySingleton() {
        assertSame(RespArray.empty(), RespArray.empty());
        assertTrue(RespArray.empty().isEmpty());
        assertFalse(RespArray.empty().isNull());
        assertEquals(0, RespArray.empty().size());
    }

    @Test
    void testNullSingleton() {
        assertSame(RespArray.nullArray(), RespArray.nullArray());
        assertTrue(RespArray.nullArray().isNull());
    }

    @Test
    void testCommandFactory() {
        RespArray array = RespArray.command("GET", "key");
        assertFalse(array.isNull());
        assertEquals(2, array.size());

        // First element should be the command as a bulk string
        Object first = array.get(0);
        assertTrue(first instanceof RespBulkString);
        assertEquals("GET", ((RespBulkString) first).getString());
        assertEquals("key", ((RespBulkString) array.get(1)).getString());
    }

    @Test
    void testCommandFactoryWithMixedArgs() {
        // Mix of String and numeric args
        RespArray array = RespArray.command("SETEX", "key", 60L, "value");
        assertEquals(4, array.size());

        // Long arg should be converted to its string representation
        Object third = array.get(2);
        assertTrue(third instanceof RespBulkString);
        assertEquals("60", ((RespBulkString) third).getString());
    }

    @Test
    void testCommandFactoryWithBytes() {
        byte[] data = new byte[]{1, 2, 3};
        RespArray array = RespArray.command("SET", "key", data);
        assertEquals(3, array.size());

        Object third = array.get(2);
        assertTrue(third instanceof RespBulkString);
        assertArrayEquals(data, ((RespBulkString) third).getData());
    }

    @Test
    void testGetElementsReturnsUnmodifiable() {
        RespArray array = RespArray.of(
                RespBulkString.of("a"),
                RespBulkString.of("b"));
        List<Object> elements = array.getElements();
        assertThrows(UnsupportedOperationException.class,
                () -> elements.add(RespBulkString.of("c")));
    }

    @Test
    void testGetOnNullArrayThrows() {
        RespArray array = RespArray.nullArray();
        assertThrows(IllegalStateException.class, () -> array.get(0));
    }

    @Test
    void testIsEmpty() {
        assertTrue(RespArray.empty().isEmpty());
        assertFalse(RespArray.nullArray().isEmpty());
        assertFalse(RespArray.of(RespBulkString.of("x")).isEmpty());
    }

    @Test
    void testToStringArray() {
        RespArray array = RespArray.command("GET", "mykey");
        String[] strs = array.toStringArray();
        assertEquals(2, strs.length);
        assertEquals("GET", strs[0]);
        assertEquals("mykey", strs[1]);
    }

    @Test
    void testToStringArrayEmpty() {
        String[] strs = RespArray.empty().toStringArray();
        assertNotNull(strs);
        assertEquals(0, strs.length);
    }

    @Test
    void testToStringArrayNull() {
        String[] strs = RespArray.nullArray().toStringArray();
        assertNotNull(strs);
        assertEquals(0, strs.length);
    }

    @Test
    void testToStringArrayWithNullElement() {
        RespArray array = RespArray.of(
                RespBulkString.of("a"),
                null,
                RespBulkString.of("c"));

        String[] strs = array.toStringArray();
        assertEquals(3, strs.length);
        assertEquals("a", strs[0]);
        assertNull(strs[1]);
        assertEquals("c", strs[2]);
    }

    @Test
    void testEquals() {
        RespArray a = RespArray.of(RespBulkString.of("x"), RespBulkString.of("y"));
        RespArray b = RespArray.of(RespBulkString.of("x"), RespBulkString.of("y"));
        RespArray c = RespArray.of(RespBulkString.of("x"), RespBulkString.of("z"));

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "x");
    }

    @Test
    void testEqualsNullArrays() {
        assertEquals(RespArray.nullArray(), RespArray.nullArray());
        assertEquals(0, RespArray.nullArray().hashCode());
    }

    @Test
    void testHashCodeConsistent() {
        RespArray a = RespArray.of(RespBulkString.of("x"));
        RespArray b = RespArray.of(RespBulkString.of("x"));
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void testToString() {
        RespArray array = RespArray.of(RespBulkString.of("a"), RespBulkString.of("b"));
        String str = array.toString();
        assertNotNull(str);
        assertTrue(str.contains("RespArray"));
        assertTrue(str.contains("size=2"));
    }

    @Test
    void testToStringLarge() {
        // For larger arrays, full element list should not be shown
        RespArray array = RespArray.of(
                RespBulkString.of("a"), RespBulkString.of("b"),
                RespBulkString.of("c"), RespBulkString.of("d"),
                RespBulkString.of("e"), RespBulkString.of("f"));
        String str = array.toString();
        assertNotNull(str);
        assertTrue(str.contains("size=6"));
    }

    @Test
    void testToStringNull() {
        String str = RespArray.nullArray().toString();
        assertNotNull(str);
        assertTrue(str.contains("null"));
    }

    @Test
    void testOfListDefensiveCopy() {
        List<Object> source = new ArrayList<>();
        source.add(RespBulkString.of("original"));
        RespArray array = RespArray.of(source);

        // Modify the source list
        source.add(RespBulkString.of("added"));
        source.set(0, RespBulkString.of("changed"));

        // The array should not be affected
        assertEquals(1, array.size());
        assertEquals("original", ((RespBulkString) array.get(0)).getString());
    }

    @Test
    void testOfEmptyVarargs() {
        RespArray array = RespArray.of();
        assertEquals(0, array.size());
        assertFalse(array.isNull());
    }

    @Test
    void testEqualsAcrossNullAndEmpty() {
        // null array and empty array are different
        assertNotEquals(RespArray.nullArray(), RespArray.empty());
    }
}