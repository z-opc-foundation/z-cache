package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RespInteger 单元测试
 */
class RespIntegerTest {

    @Test
    void testFactoryOfLong() {
        RespInteger value = RespInteger.of(42L);
        assertEquals(42L, value.getValue());
    }

    @Test
    void testFactoryOfInt() {
        RespInteger value = RespInteger.of(42);
        assertEquals(42L, value.getValue());
    }

    @Test
    void testFactoryOfNegative() {
        RespInteger value = RespInteger.of(-100L);
        assertEquals(-100L, value.getValue());
    }

    @Test
    void testZero() {
        assertEquals(0L, RespInteger.ZERO.getValue());
        assertEquals(1L, RespInteger.ONE.getValue());
        assertEquals(-1L, RespInteger.MINUS_ONE.getValue());
    }

    @Test
    void testMaxValue() {
        RespInteger value = RespInteger.of(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, value.getValue());
    }

    @Test
    void testMinValue() {
        RespInteger value = RespInteger.of(Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, value.getValue());
    }

    @Test
    void testIntValue() {
        assertEquals(42, RespInteger.of(42L).intValue());
        // Long values truncate when cast to int
        long large = (long) Integer.MAX_VALUE + 100L;
        assertEquals(Integer.MAX_VALUE + 100, RespInteger.of(large).intValue());
    }

    @Test
    void testEquals() {
        RespInteger a = RespInteger.of(42);
        RespInteger b = RespInteger.of(42L);  // int vs long overload still produces equal value
        RespInteger c = RespInteger.of(43);

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "42");
    }

    @Test
    void testHashCode() {
        assertEquals(RespInteger.of(42).hashCode(),
                RespInteger.of(42L).hashCode());
        assertEquals(RespInteger.of(0).hashCode(), RespInteger.ZERO.hashCode());
    }

    @Test
    void testToString() {
        RespInteger value = RespInteger.of(42L);
        String str = value.toString();
        assertNotNull(str);
        assertTrue(str.contains("42"));
    }
}