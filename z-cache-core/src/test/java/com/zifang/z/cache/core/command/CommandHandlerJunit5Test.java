package com.zifang.z.cache.core.command;

import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.storage.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandHandler 单元测试(JUnit 5 版)。
 * <p>
 * 这是对已有 CommandHandlerTest 的补充,使用 JUnit 5 风格,
 * 覆盖 SET/GET 选项、NX/XX、PERSIST、SETEX/PSETEX、错误路径和并发访问。
 */
class CommandHandlerJunit5Test {

    private MemoryStore store;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        handler = new CommandHandler(store);
    }

    private RespArray cmd(String... args) {
        Object[] bulkStrings = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            bulkStrings[i] = RespBulkString.of(args[i]);
        }
        return RespArray.of(bulkStrings);
    }

    // ==================== PING / ECHO / SELECT ====================

    @Test
    void testPingNoArgs() {
        Object result = handler.handle(cmd("PING"));
        assertTrue(result instanceof RespSimpleString);
        assertEquals("PONG", ((RespSimpleString) result).getValue());
    }

    @Test
    void testPingWithMessage() {
        Object result = handler.handle(cmd("PING", "hello"));
        assertTrue(result instanceof RespBulkString);
        assertEquals("hello", ((RespBulkString) result).getString());
    }

    @Test
    void testPingTooManyArgs() {
        Object result = handler.handle(cmd("PING", "a", "b"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testEcho() {
        Object result = handler.handle(cmd("ECHO", "abc"));
        assertTrue(result instanceof RespBulkString);
        assertEquals("abc", ((RespBulkString) result).getString());
    }

    @Test
    void testEchoWrongArgs() {
        Object result = handler.handle(cmd("ECHO"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testSelectZero() {
        assertTrue(handler.handle(cmd("SELECT", "0")) instanceof RespSimpleString);
    }

    @Test
    void testSelectNonZero() {
        assertTrue(handler.handle(cmd("SELECT", "1")) instanceof RespError);
    }

    @Test
    void testSelectInvalid() {
        assertTrue(handler.handle(cmd("SELECT", "abc")) instanceof RespError);
    }

    @Test
    void testSelectWrongArgs() {
        assertTrue(handler.handle(cmd("SELECT")) instanceof RespError);
    }

    @Test
    void testQuit() {
        Object result = handler.handle(cmd("QUIT"));
        assertTrue(result instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) result).getValue());
    }

    // ==================== SET options ====================

    @Test
    void testSetEx() {
        handler.handle(cmd("SET", "k", "v", "EX", "10"));
        Object ttl = handler.handle(cmd("TTL", "k"));
        assertTrue(((RespInteger) ttl).getValue() > 0);
    }

    @Test
    void testSetPx() {
        handler.handle(cmd("SET", "k", "v", "PX", "10000"));
        Object ttl = handler.handle(cmd("TTL", "k"));
        assertTrue(((RespInteger) ttl).getValue() > 0);
    }

    @Test
    void testSetNxOnMissingKey() {
        Object result = handler.handle(cmd("SET", "newkey", "v", "NX"));
        assertTrue(result instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) result).getValue());
    }

    @Test
    void testSetNxOnExistingKey() {
        handler.handle(cmd("SET", "k", "v1"));
        Object result = handler.handle(cmd("SET", "k", "v2", "NX"));
        assertTrue(result instanceof RespBulkString);
        assertTrue(((RespBulkString) result).isNull());
        // Original value preserved
        assertEquals("v1", store.getString("k"));
    }

    @Test
    void testSetXxOnMissingKey() {
        Object result = handler.handle(cmd("SET", "k", "v", "XX"));
        assertTrue(result instanceof RespBulkString);
        assertTrue(((RespBulkString) result).isNull());
        // Key should not be created
        assertFalse(store.exists("k"));
    }

    @Test
    void testSetXxOnExistingKey() {
        handler.handle(cmd("SET", "k", "v1"));
        Object result = handler.handle(cmd("SET", "k", "v2", "XX"));
        assertTrue(result instanceof RespSimpleString);
        assertEquals("v2", store.getString("k"));
    }

    @Test
    void testSetExInvalidValue() {
        Object result = handler.handle(cmd("SET", "k", "v", "EX", "not-a-number"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testSetPxInvalidValue() {
        Object result = handler.handle(cmd("SET", "k", "v", "PX", "not-a-number"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testSetMissingExValue() {
        Object result = handler.handle(cmd("SET", "k", "v", "EX"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testSetUnknownOption() {
        Object result = handler.handle(cmd("SET", "k", "v", "BOGUS"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testSetTooFewArgs() {
        Object result = handler.handle(cmd("SET", "k"));
        assertTrue(result instanceof RespError);
    }

    // ==================== SETEX / PSETEX ====================

    @Test
    void testSetex() {
        Object result = handler.handle(cmd("SETEX", "k", "60", "v"));
        assertTrue(result instanceof RespSimpleString);
        assertEquals("v", store.getString("k"));
    }

    @Test
    void testSetexWrongArgs() {
        assertTrue(handler.handle(cmd("SETEX", "k", "60")) instanceof RespError);
    }

    @Test
    void testSetexInvalidSeconds() {
        assertTrue(handler.handle(cmd("SETEX", "k", "abc", "v")) instanceof RespError);
    }

    @Test
    void testPsetex() {
        Object result = handler.handle(cmd("PSETEX", "k", "10000", "v"));
        assertTrue(result instanceof RespSimpleString);
        assertEquals("v", store.getString("k"));
    }

    @Test
    void testPsetexWrongArgs() {
        assertTrue(handler.handle(cmd("PSETEX", "k", "10000")) instanceof RespError);
    }

    @Test
    void testPsetexInvalidMs() {
        assertTrue(handler.handle(cmd("PSETEX", "k", "abc", "v")) instanceof RespError);
    }

    // ==================== EXPIRE / TTL / PERSIST ====================

    @Test
    void testExpireOnExisting() {
        handler.handle(cmd("SET", "k", "v"));
        Object result = handler.handle(cmd("EXPIRE", "k", "100"));
        assertEquals(1L, ((RespInteger) result).getValue());
    }

    @Test
    void testExpireOnMissing() {
        Object result = handler.handle(cmd("EXPIRE", "missing", "100"));
        assertEquals(0L, ((RespInteger) result).getValue());
    }

    @Test
    void testExpireInvalidSeconds() {
        handler.handle(cmd("SET", "k", "v"));
        Object result = handler.handle(cmd("EXPIRE", "k", "abc"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testExpireWrongArgs() {
        assertTrue(handler.handle(cmd("EXPIRE", "k")) instanceof RespError);
        assertTrue(handler.handle(cmd("EXPIRE", "k", "10", "extra")) instanceof RespError);
    }

    @Test
    void testTtlMissingKey() {
        Object result = handler.handle(cmd("TTL", "missing"));
        assertEquals(-2L, ((RespInteger) result).getValue());
    }

    @Test
    void testTtlWithoutExpiration() {
        handler.handle(cmd("SET", "k", "v"));
        Object result = handler.handle(cmd("TTL", "k"));
        assertEquals(-1L, ((RespInteger) result).getValue());
    }

    @Test
    void testTtlWrongArgs() {
        assertTrue(handler.handle(cmd("TTL")) instanceof RespError);
    }

    @Test
    void testPersistRemovesTtl() {
        handler.handle(cmd("SET", "k", "v"));
        handler.handle(cmd("EXPIRE", "k", "100"));
        Object result = handler.handle(cmd("PERSIST", "k"));
        assertEquals(1L, ((RespInteger) result).getValue());
        assertEquals(-1L, ((RespInteger) handler.handle(cmd("TTL", "k"))).getValue());
    }

    @Test
    void testPersistWithoutTtl() {
        handler.handle(cmd("SET", "k", "v"));
        Object result = handler.handle(cmd("PERSIST", "k"));
        assertEquals(0L, ((RespInteger) result).getValue());
    }

    @Test
    void testPersistOnMissing() {
        Object result = handler.handle(cmd("PERSIST", "missing"));
        assertEquals(0L, ((RespInteger) result).getValue());
    }

    @Test
    void testPersistWrongArgs() {
        assertTrue(handler.handle(cmd("PERSIST")) instanceof RespError);
    }

    // ==================== GET / DEL / EXISTS ====================

    @Test
    void testGetMissingKey() {
        Object result = handler.handle(cmd("GET", "missing"));
        assertTrue(result instanceof RespBulkString);
        assertTrue(((RespBulkString) result).isNull());
    }

    @Test
    void testGetWrongArgs() {
        assertTrue(handler.handle(cmd("GET")) instanceof RespError);
        assertTrue(handler.handle(cmd("GET", "a", "b")) instanceof RespError);
    }

    @Test
    void testDelWrongArgs() {
        assertTrue(handler.handle(cmd("DEL")) instanceof RespError);
    }

    @Test
    void testExistsWrongArgs() {
        assertTrue(handler.handle(cmd("EXISTS")) instanceof RespError);
    }

    // ==================== DBSIZE / FLUSHDB ====================

    @Test
    void testDbsizeEmpty() {
        assertEquals(0L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }

    @Test
    void testFlushdb() {
        handler.handle(cmd("SET", "a", "1"));
        handler.handle(cmd("SET", "b", "2"));
        Object result = handler.handle(cmd("FLUSHDB"));
        assertTrue(result instanceof RespSimpleString);
        assertEquals(0L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }

    // ==================== KEYS ====================

    @Test
    void testKeysStar() {
        handler.handle(cmd("SET", "a", "1"));
        handler.handle(cmd("SET", "b", "2"));
        Object result = handler.handle(cmd("KEYS", "*"));
        assertTrue(result instanceof RespArray);
        RespArray array = (RespArray) result;
        List<Object> elements = array.getElements();
        assertEquals(2, elements.size());
    }

    @Test
    void testKeysPattern() {
        handler.handle(cmd("SET", "abc", "1"));
        Object result = handler.handle(cmd("KEYS", "a*"));
        assertTrue(result instanceof RespArray);
        assertEquals(1, ((RespArray) result).size());
    }

    @Test
    void testKeysWrongArgs() {
        assertTrue(handler.handle(cmd("KEYS")) instanceof RespError);
    }

    // ==================== Extended string commands ====================

    @Test
    void testBatchAndAtomicStringCommands() {
        assertEquals(1L, ((RespInteger) handler.handle(cmd("SETNX", "counter", "1"))).getValue());
        assertEquals(0L, ((RespInteger) handler.handle(cmd("SETNX", "counter", "2"))).getValue());
        assertEquals(2L, ((RespInteger) handler.handle(cmd("INCR", "counter"))).getValue());
        assertEquals(7L, ((RespInteger) handler.handle(cmd("INCRBY", "counter", "5"))).getValue());
        assertEquals(6L, ((RespInteger) handler.handle(cmd("DECR", "counter"))).getValue());

        handler.handle(cmd("MSET", "a", "A", "b", "B"));
        RespArray values = (RespArray) handler.handle(cmd("MGET", "a", "missing", "b"));
        assertEquals("A", ((RespBulkString) values.get(0)).getString());
        assertTrue(((RespBulkString) values.get(1)).isNull());
        assertEquals("B", ((RespBulkString) values.get(2)).getString());
    }

    @Test
    void testAppendGetsetAndPatternKeys() {
        handler.handle(cmd("SET", "user:1", "A"));
        assertEquals(2L, ((RespInteger) handler.handle(cmd("APPEND", "user:1", "B"))).getValue());
        assertEquals("AB", ((RespBulkString) handler.handle(cmd("GETSET", "user:1", "C"))).getString());
        RespArray keys = (RespArray) handler.handle(cmd("KEYS", "user:?") );
        assertEquals(1, keys.size());
    }

    @Test
    void testMillisecondExpirationAndInfo() {
        handler.handle(cmd("SET", "short", "value"));
        assertEquals(1L, ((RespInteger) handler.handle(cmd("PEXPIRE", "short", "10000"))).getValue());
        assertTrue(((RespInteger) handler.handle(cmd("PTTL", "short"))).getValue() > 0);
        String info = ((RespBulkString) handler.handle(cmd("INFO"))).getString();
        assertTrue(info.contains("keyspace_hits:"));
        assertEquals("string", ((RespSimpleString) handler.handle(cmd("TYPE", "short"))).getValue());
    }

    @Test
    void testAuthentication() {
        CommandHandler secure = new CommandHandler(new MemoryStore(), "secret");
        assertTrue(secure.handle(cmd("GET", "key")) instanceof RespError);
        assertTrue(secure.handle(cmd("AUTH", "wrong")) instanceof RespError);
        assertTrue(secure.handle(cmd("AUTH", "secret")) instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) secure.handle(cmd("SET", "key", "value"))).getValue());
    }

    // ==================== Error Handling ====================

    @Test
    void testNullRequestReturnsError() {
        Object result = handler.handle(null);
        assertTrue(result instanceof RespError);
    }

    @Test
    void testNonArrayRequestReturnsError() {
        Object result = handler.handle(RespBulkString.of("foo"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testEmptyArrayReturnsError() {
        Object result = handler.handle(RespArray.empty());
        assertTrue(result instanceof RespError);
    }

    @Test
    void testUnknownCommandReturnsError() {
        Object result = handler.handle(cmd("BOGUS"));
        assertTrue(result instanceof RespError);
    }

    @Test
    void testCaseInsensitiveCommands() {
        // Commands should be case-insensitive (uppercased internally)
        Object result = handler.handle(cmd("ping"));
        assertTrue(result instanceof RespSimpleString);
        assertEquals("PONG", ((RespSimpleString) result).getValue());
    }
}