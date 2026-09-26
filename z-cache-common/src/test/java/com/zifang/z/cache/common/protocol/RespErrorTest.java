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

    /**
     * 命令名在参考实现里回的是小写原文（250 实测：{@code SUBSTR b4:s} →
     * {@code ERR wrong number of arguments for 'substr' command}、{@code SETRANGE b4:wt v} →
     * {@code ... 'setrange' ...}），而调用点一律传的大写命令名。以前这里断言
     * {@code contains("GET")}，等于把"我们发的是大写"当成期望——它既不是参考实现的形状，
     * 也正好是那次改动唯一会打断的断言。改成钉整条原文。
     */
    @Test
    void testWrongNumberOfArguments() {
        assertEquals("ERR wrong number of arguments for 'get' command",
                RespError.wrongNumberOfArguments("GET").getMessage());
        assertEquals("ERR wrong number of arguments for 'hsetnx' command",
                RespError.wrongNumberOfArguments("HSETNX").getMessage());
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