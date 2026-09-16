package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RespType 枚举单元测试
 */
class RespTypeTest {

    @Test
    void testAllTypesHaveUniquePrefix() {
        char[] prefixes = new char[RespType.values().length];
        int i = 0;
        for (RespType type : RespType.values()) {
            prefixes[i++] = type.getPrefix();
        }
        // All prefixes must be unique
        for (int j = 0; j < prefixes.length; j++) {
            for (int k = j + 1; k < prefixes.length; k++) {
                assertNotEquals(prefixes[j], prefixes[k],
                        "Prefix at index " + j + " and " + k + " collide");
            }
        }
    }

    @Test
    void testPrefixMatchesSpec() {
        assertEquals('+', RespType.SIMPLE_STRING.getPrefix());
        assertEquals('-', RespType.ERROR.getPrefix());
        assertEquals(':', RespType.INTEGER.getPrefix());
        assertEquals('$', RespType.BULK_STRING.getPrefix());
        assertEquals('*', RespType.ARRAY.getPrefix());
    }

    @Test
    void testFromPrefix() {
        assertEquals(RespType.SIMPLE_STRING, RespType.fromPrefix('+'));
        assertEquals(RespType.ERROR, RespType.fromPrefix('-'));
        assertEquals(RespType.INTEGER, RespType.fromPrefix(':'));
        assertEquals(RespType.BULK_STRING, RespType.fromPrefix('$'));
        assertEquals(RespType.ARRAY, RespType.fromPrefix('*'));
    }

    @Test
    void testFromPrefixUnknown() {
        assertNull(RespType.fromPrefix(' '));
        assertNull(RespType.fromPrefix('@'));
        assertNull(RespType.fromPrefix('Z'));
        assertNull(RespType.fromPrefix('a'));
        assertNull(RespType.fromPrefix('5'));
    }

    @Test
    void testEnumValues() {
        RespType[] types = RespType.values();
        assertEquals(5, types.length);
    }

    @Test
    void testEnumValueOf() {
        assertEquals(RespType.SIMPLE_STRING, RespType.valueOf("SIMPLE_STRING"));
        assertEquals(RespType.ERROR, RespType.valueOf("ERROR"));
        assertEquals(RespType.INTEGER, RespType.valueOf("INTEGER"));
        assertEquals(RespType.BULK_STRING, RespType.valueOf("BULK_STRING"));
        assertEquals(RespType.ARRAY, RespType.valueOf("ARRAY"));
    }

    @Test
    void testEnumValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> RespType.valueOf("UNKNOWN"));
        assertThrows(NullPointerException.class, () -> RespType.valueOf(null));
    }
}