package com.zifang.z.cache.core.server;

import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.storage.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisServerHandler + CommandHandler 集成测试。
 * <p>
 * 直接测试 CommandHandler 的 RESP 对象输入/输出,绕过 Netty 编解码管道,
 * 确保命令处理逻辑的端到端正确性。
 * <p>
 * RESP 字节级的端到端测试已在 RespEncoderTest 和 RespDecoderTest 中覆盖。
 */
class RedisServerHandlerTest {

    private MemoryStore store;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        handler = new CommandHandler(store);
    }

    /**
     * Helper: build a RespArray of RespBulkString arguments (like a real RESP request).
     */
    private RespArray cmd(String... args) {
        Object[] bulkStrings = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            bulkStrings[i] = RespBulkString.of(args[i]);
        }
        return RespArray.of(bulkStrings);
    }

    // ==================== Connection commands ====================

    @Test
    void testPingNoArgs() {
        Object resp = handler.handle(cmd("PING"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("PONG", ((RespSimpleString) resp).getValue());
    }

    @Test
    void testPingWithMessage() {
        Object resp = handler.handle(cmd("PING", "hello"));
        assertTrue(resp instanceof RespBulkString);
        assertEquals("hello", ((RespBulkString) resp).getString());
    }

    @Test
    void testEcho() {
        Object resp = handler.handle(cmd("ECHO", "world"));
        assertTrue(resp instanceof RespBulkString);
        assertEquals("world", ((RespBulkString) resp).getString());
    }

    @Test
    void testSelectZero() {
        Object resp = handler.handle(cmd("SELECT", "0"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) resp).getValue());
    }

    @Test
    void testSelectNonZeroFails() {
        Object resp = handler.handle(cmd("SELECT", "5"));
        assertTrue(resp instanceof RespError);
    }

    @Test
    void testQuit() {
        Object resp = handler.handle(cmd("QUIT"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) resp).getValue());
    }

    // ==================== SET / GET ====================

    @Test
    void testSetGetRoundTrip() {
        Object setResp = handler.handle(cmd("SET", "key", "value"));
        assertTrue(setResp instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) setResp).getValue());

        Object getResp = handler.handle(cmd("GET", "key"));
        assertTrue(getResp instanceof RespBulkString);
        assertEquals("value", ((RespBulkString) getResp).getString());
    }

    @Test
    void testGetMissingKeyReturnsNull() {
        Object resp = handler.handle(cmd("GET", "noexist"));
        assertTrue(resp instanceof RespBulkString);
        assertTrue(((RespBulkString) resp).isNull());
    }

    @Test
    void testSetWithExSetsTtl() {
        handler.handle(cmd("SET", "k", "v", "EX", "60"));

        Object ttl = handler.handle(cmd("TTL", "k"));
        assertTrue(ttl instanceof RespInteger);
        long ttlValue = ((RespInteger) ttl).getValue();
        assertTrue(ttlValue > 0 && ttlValue <= 60, "TTL should be 1..60, got " + ttlValue);
    }

    @Test
    void testSetNxOnExistingKeyReturnsNull() {
        handler.handle(cmd("SET", "k", "original"));
        Object resp = handler.handle(cmd("SET", "k", "updated", "NX"));
        assertTrue(resp instanceof RespBulkString);
        assertTrue(((RespBulkString) resp).isNull(), "NX should not overwrite existing key");
        // Original value preserved
        assertEquals("original", store.getString("k"));
    }

    @Test
    void testSetNxOnMissingKeySucceeds() {
        Object resp = handler.handle(cmd("SET", "k", "new", "NX"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("new", store.getString("k"));
    }

    @Test
    void testSetXxOnMissingKeyReturnsNull() {
        Object resp = handler.handle(cmd("SET", "k", "v", "XX"));
        assertTrue(resp instanceof RespBulkString);
        assertTrue(((RespBulkString) resp).isNull());
        assertFalse(store.exists("k"));
    }

    @Test
    void testSetXxOnExistingKeySucceeds() {
        handler.handle(cmd("SET", "k", "old"));
        Object resp = handler.handle(cmd("SET", "k", "new", "XX"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("new", store.getString("k"));
    }

    // ==================== SETEX / PSETEX ====================

    @Test
    void testSetex() {
        Object resp = handler.handle(cmd("SETEX", "k", "30", "value"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) resp).getValue());
        assertEquals("value", store.getString("k"));
    }

    @Test
    void testPsetex() {
        Object resp = handler.handle(cmd("PSETEX", "k", "30000", "value"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("value", store.getString("k"));
    }

    // ==================== DEL / EXISTS ====================

    @Test
    void testDelCountsCorrectly() {
        handler.handle(cmd("SET", "a", "1"));
        handler.handle(cmd("SET", "b", "2"));
        handler.handle(cmd("SET", "c", "3"));

        Object resp = handler.handle(cmd("DEL", "a", "b", "missing"));
        assertEquals(2L, ((RespInteger) resp).getValue());
        assertFalse(store.exists("a"));
        assertFalse(store.exists("b"));
        assertEquals("3", store.getString("c"));
    }

    @Test
    void testExistsCountsCorrectly() {
        handler.handle(cmd("SET", "a", "1"));
        handler.handle(cmd("SET", "b", "2"));

        Object resp = handler.handle(cmd("EXISTS", "a", "b", "noexist"));
        assertEquals(2L, ((RespInteger) resp).getValue());
    }

    // ==================== EXPIRE / TTL / PERSIST ====================

    @Test
    void testExpireOnExistingKey() {
        handler.handle(cmd("SET", "k", "v"));
        Object resp = handler.handle(cmd("EXPIRE", "k", "100"));
        assertEquals(1L, ((RespInteger) resp).getValue());
    }

    @Test
    void testExpireOnMissingKey() {
        Object resp = handler.handle(cmd("EXPIRE", "missing", "100"));
        assertEquals(0L, ((RespInteger) resp).getValue());
    }

    @Test
    void testTtlMissingKey() {
        assertEquals(-2L, ((RespInteger) handler.handle(cmd("TTL", "missing"))).getValue());
    }

    @Test
    void testTtlKeyWithoutExpiration() {
        handler.handle(cmd("SET", "k", "v"));
        assertEquals(-1L, ((RespInteger) handler.handle(cmd("TTL", "k"))).getValue());
    }

    @Test
    void testPersistRemovesExpiration() {
        handler.handle(cmd("SET", "k", "v"));
        handler.handle(cmd("EXPIRE", "k", "100"));
        Object persist = handler.handle(cmd("PERSIST", "k"));
        assertEquals(1L, ((RespInteger) persist).getValue());
        assertEquals(-1L, ((RespInteger) handler.handle(cmd("TTL", "k"))).getValue());
    }

    @Test
    void testPersistKeyWithoutExpiration() {
        handler.handle(cmd("SET", "k", "v"));
        assertEquals(0L, ((RespInteger) handler.handle(cmd("PERSIST", "k"))).getValue());
    }

    // ==================== DBSIZE / FLUSHDB ====================

    @Test
    void testDbsizeAndFlushdb() {
        assertEquals(0L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());

        handler.handle(cmd("SET", "a", "1"));
        handler.handle(cmd("SET", "b", "2"));
        assertEquals(2L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());

        Object flush = handler.handle(cmd("FLUSHDB"));
        assertTrue(flush instanceof RespSimpleString);
        assertEquals(0L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }

    // ==================== KEYS ====================

    @Test
    void testKeysStar() {
        handler.handle(cmd("SET", "a", "1"));
        handler.handle(cmd("SET", "b", "2"));
        handler.handle(cmd("SET", "c", "3"));

        Object resp = handler.handle(cmd("KEYS", "*"));
        assertTrue(resp instanceof RespArray);
        assertEquals(3, ((RespArray) resp).size());
    }

    @Test
    void testKeysEmptyStore() {
        Object resp = handler.handle(cmd("KEYS", "*"));
        assertTrue(resp instanceof RespArray);
        assertEquals(0, ((RespArray) resp).size());
    }

    // ==================== Error paths ====================

    @Test
    void testNullRequest() {
        assertTrue(handler.handle(null) instanceof RespError);
    }

    @Test
    void testEmptyCommand() {
        assertTrue(handler.handle(RespArray.empty()) instanceof RespError);
    }

    @Test
    void testUnknownCommand() {
        Object resp = handler.handle(cmd("UNKNOWN"));
        assertTrue(resp instanceof RespError);
        assertTrue(((RespError) resp).getMessage().contains("UNKNOWN"));
    }

    @Test
    void testCaseInsensitiveCommands() {
        // lowercase 'ping' should work the same as 'PING'
        Object resp = handler.handle(cmd("ping"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("PONG", ((RespSimpleString) resp).getValue());
    }

    @Test
    void testTooFewArgsForSet() {
        Object resp = handler.handle(cmd("SET", "k"));
        assertTrue(resp instanceof RespError);
    }

    @Test
    void testTooFewArgsForGet() {
        assertTrue(handler.handle(cmd("GET")) instanceof RespError);
    }

    @Test
    void testTooFewArgsForDel() {
        assertTrue(handler.handle(cmd("DEL")) instanceof RespError);
    }

    @Test
    void testTooFewArgsForExpire() {
        assertTrue(handler.handle(cmd("EXPIRE", "k")) instanceof RespError);
    }

    // ==================== Multiple command sequence ====================

    @Test
    void testFullWorkflow() {
        // SET
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("SET", "counter", "10"))).getValue());

        // GET
        assertEquals("10", store.getString("counter"));

        // OVERWRITE
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("SET", "counter", "99"))).getValue());
        assertEquals("99", store.getString("counter"));

        // EXISTS
        assertEquals(1L, ((RespInteger) handler.handle(cmd("EXISTS", "counter"))).getValue());

        // SETEX + TTL
        handler.handle(cmd("SET", "temp", "val"));
        handler.handle(cmd("EXPIRE", "temp", "60"));
        long ttl = ((RespInteger) handler.handle(cmd("TTL", "temp"))).getValue();
        assertTrue(ttl > 0, "TTL should be positive after EXPIRE");

        // PERSIST
        assertEquals(1L, ((RespInteger) handler.handle(cmd("PERSIST", "temp"))).getValue());
        assertEquals(-1L, ((RespInteger) handler.handle(cmd("TTL", "temp"))).getValue());

        // DEL
        assertEquals(2L, ((RespInteger) handler.handle(cmd("DEL", "counter", "temp"))).getValue());

        // GET after DEL
        assertTrue(((RespBulkString) handler.handle(cmd("GET", "counter"))).isNull());

        // DBSIZE
        assertEquals(0L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }
}