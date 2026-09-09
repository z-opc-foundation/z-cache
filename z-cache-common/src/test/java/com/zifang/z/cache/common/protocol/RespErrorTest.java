package com.zifang.z.cache.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RespError 单元测试
 */
class RespErrorTest {

    @Test
    void testFactoryOf() {
        RespError err = RespError.of("ERR something went wrong");
        assertEquals("ERR something went wrong", err.getMessage());
    }

    @Test
    void testFactoryOfTypeAndMessage() {
        RespError err = RespError.of("WRONGTYPE", "key is not a list");
        assertEquals("WRONGTYPE key is not a list", err.getMessage());
    }

    @Test
    void testErrStatic() {
        RespError err = RespError.err("syntax error");
        assertEquals("ERR syntax error", err.getMessage());
    }

    @Test
    void testWrongNumberOfArguments() {
        RespError err = RespError.wrongNumberOfArguments("GET");
        assertTrue(err.getMessage().contains("GET"));
        assertTrue(err.getMessage().contains("wrong number of arguments"));
    }

    @Test
    void testWrongType() {
        RespError err = RespError.wrongType("key is not a list");
        assertEquals("WRONGTYPE key is not a list", err.getMessage());
    }

    @Test
    void testNoSuchKeySingleton() {
        assertNotNull(RespError.noSuchKey());
        assertSame(RespError.noSuchKey(), RespError.noSuchKey());
    }

    @Test
    void testSyntaxErrorSingleton() {
        assertNotNull(RespError.syntaxError());
        assertSame(RespError.syntaxError(), RespError.syntaxError());
    }

    @Test
    void testUnknownCommand() {
        RespError err = RespError.unknownCommand("FOOBAR");
        assertEquals("ERR unknown command 'FOOBAR'", err.getMessage());
    }

    @Test
    void testNullMessageRejected() {
        assertThrows(NullPointerException.class, () -> RespError.of(null));
    }

    @Test
    void testGetErrorType() {
        assertEquals("ERR", RespError.of("ERR something").getErrorType());
        assertEquals("WRONGTYPE", RespError.of("WRONGTYPE bad").getErrorType());
    }

    @Test
    void testGetErrorTypeWithoutSpace() {
        // When message has no space, the whole message is the error type
        assertEquals("SINGLEWORD", RespError.of("SINGLEWORD").getErrorType());
    }

    @Test
    void testEquals() {
        RespError a = RespError.of("ERR foo");
        RespError b = RespError.of("ERR foo");
        RespError c = RespError.of("ERR bar");

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "ERR foo");
    }

    @Test
    void testHashCode() {
        assertEquals(RespError.of("ERR foo").hashCode(),
                RespError.of("ERR foo").hashCode());
    }

    @Test
    void testToString() {
        RespError err = RespError.of("ERR something");
        String str = err.toString();
        assertNotNull(str);
        assertTrue(str.contains("ERR something"));
    }
}