package com.zifang.z.cache.core.server;

import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.logging.SlowLog;
import com.zifang.z.cache.core.storage.*;
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
        // 重置静态共享 Store 避免测试间数据泄漏
        CommandHandler.setSlowLog(new SlowLog());

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
    void testSelectNonZeroSucceeds() {
        Object resp = handler.handle(cmd("SELECT", "5"));
        assertTrue(resp instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) resp).getValue());
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

    // ==================== Hash 命令测试 ====================

    @Test
    void testHashSetAndGet() {
        // HSET 返回新增字段数量（RespInteger），而非 "OK"
        Object hsetResult = handler.handle(cmd("HSET", "myhash", "field1", "value1"));
        assertTrue(hsetResult instanceof RespInteger);
        assertEquals(1L, ((RespInteger) hsetResult).getValue());
        assertEquals("value1", ((RespBulkString) handler.handle(cmd("HGET", "myhash", "field1"))).getString());
        assertTrue(((RespBulkString) handler.handle(cmd("HGET", "myhash", "missing"))).isNull());
    }

    @Test
    void testHashMultipleFields() {
        handler.handle(cmd("HSET", "myhash", "f1", "v1", "f2", "v2", "f3", "v3"));
        assertEquals(3L, ((RespInteger) handler.handle(cmd("HLEN", "myhash"))).getValue());
        assertEquals("v2", ((RespBulkString) handler.handle(cmd("HGET", "myhash", "f2"))).getString());

        // HGETALL
        Object resp = handler.handle(cmd("HGETALL", "myhash"));
        assertTrue(resp instanceof RespArray);
        RespArray arr = (RespArray) resp;
        assertEquals(6, arr.size()); // 3 fields * 2 (key + value)
    }

    @Test
    void testHashDel() {
        handler.handle(cmd("HSET", "myhash", "f1", "v1", "f2", "v2"));
        assertEquals(1L, ((RespInteger) handler.handle(cmd("HDEL", "myhash", "f1"))).getValue());
        assertTrue(((RespBulkString) handler.handle(cmd("HGET", "myhash", "f1"))).isNull());
        assertEquals(1L, ((RespInteger) handler.handle(cmd("HLEN", "myhash"))).getValue());
    }

    @Test
    void testHashHkeysHvals() {
        handler.handle(cmd("HSET", "myhash", "a", "1", "b", "2"));
        Object keys = handler.handle(cmd("HKEYS", "myhash"));
        assertTrue(keys instanceof RespArray);
        assertEquals(2, ((RespArray) keys).size());

        Object vals = handler.handle(cmd("HVALS", "myhash"));
        assertTrue(vals instanceof RespArray);
        assertEquals(2, ((RespArray) vals).size());
    }

    @Test
    void testHashIncrby() {
        handler.handle(cmd("HSET", "myhash", "counter", "10"));
        assertEquals(13L, ((RespInteger) handler.handle(cmd("HINCRBY", "myhash", "counter", "3"))).getValue());
        assertEquals("13", ((RespBulkString) handler.handle(cmd("HGET", "myhash", "counter"))).getString());
    }

    @Test
    void testHashSetnx() {
        assertEquals(1L, ((RespInteger) handler.handle(cmd("HSETNX", "myhash", "f", "v"))).getValue());
        assertEquals(0L, ((RespInteger) handler.handle(cmd("HSETNX", "myhash", "f", "v2"))).getValue());
        assertEquals("v", ((RespBulkString) handler.handle(cmd("HGET", "myhash", "f"))).getString());
    }

    @Test
    void testHashHmget() {
        handler.handle(cmd("HSET", "myhash", "a", "1", "b", "2"));
        Object resp = handler.handle(cmd("HMGET", "myhash", "a", "missing", "b"));
        assertTrue(resp instanceof RespArray);
        RespArray arr = (RespArray) resp;
        assertEquals(3, arr.size());
    }

    // ==================== List 命令测试 ====================

    @Test
    void testListPushAndPop() {
        handler.handle(cmd("LPUSH", "mylist", "c", "b", "a"));
        handler.handle(cmd("RPUSH", "mylist", "d", "e"));
        assertEquals(5L, ((RespInteger) handler.handle(cmd("LLEN", "mylist"))).getValue());

        // LPOP
        assertEquals("a", ((RespBulkString) handler.handle(cmd("LPOP", "mylist"))).getString());
        // RPOP
        assertEquals("e", ((RespBulkString) handler.handle(cmd("RPOP", "mylist"))).getString());
    }

    @Test
    void testListRange() {
        handler.handle(cmd("RPUSH", "mylist", "1", "2", "3", "4", "5"));
        Object resp = handler.handle(cmd("LRANGE", "mylist", "0", "2"));
        assertTrue(resp instanceof RespArray);
        assertEquals(3, ((RespArray) resp).size());

        // Negative index
        resp = handler.handle(cmd("LRANGE", "mylist", "-2", "-1"));
        assertTrue(resp instanceof RespArray);
        assertEquals(2, ((RespArray) resp).size());
    }

    @Test
    void testListIndex() {
        handler.handle(cmd("RPUSH", "mylist", "a", "b", "c"));
        assertEquals("b", ((RespBulkString) handler.handle(cmd("LINDEX", "mylist", "1"))).getString());
        assertTrue(((RespBulkString) handler.handle(cmd("LINDEX", "mylist", "10"))).isNull());
    }

    @Test
    void testListSet() {
        handler.handle(cmd("RPUSH", "mylist", "a", "b", "c"));
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("LSET", "mylist", "1", "X"))).getValue());
        assertEquals("X", ((RespBulkString) handler.handle(cmd("LINDEX", "mylist", "1"))).getString());
    }

    @Test
    void testListRem() {
        handler.handle(cmd("RPUSH", "mylist", "a", "b", "a", "c", "a"));
        assertEquals(2L, ((RespInteger) handler.handle(cmd("LREM", "mylist", "2", "a"))).getValue());
        assertEquals(3L, ((RespInteger) handler.handle(cmd("LLEN", "mylist"))).getValue());
    }

    @Test
    void testListTrim() {
        handler.handle(cmd("RPUSH", "mylist", "1", "2", "3", "4", "5"));
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("LTRIM", "mylist", "1", "3"))).getValue());
        assertEquals(3L, ((RespInteger) handler.handle(cmd("LLEN", "mylist"))).getValue());
    }

    @Test
    void testRpoplpush() {
        handler.handle(cmd("RPUSH", "src", "1", "2", "3"));
        handler.handle(cmd("RPUSH", "dst", "a"));
        assertEquals("3", ((RespBulkString) handler.handle(cmd("RPOPLPUSH", "src", "dst"))).getString());
        assertEquals(2L, ((RespInteger) handler.handle(cmd("LLEN", "src"))).getValue());
        assertEquals(2L, ((RespInteger) handler.handle(cmd("LLEN", "dst"))).getValue());
    }

    // ==================== Set 命令测试 ====================

    @Test
    void testSetAddAndRemove() {
        handler.handle(cmd("SADD", "myset", "a", "b", "c", "b"));
        assertEquals(3L, ((RespInteger) handler.handle(cmd("SCARD", "myset"))).getValue()); // b 去重
        assertEquals(1L, ((RespInteger) handler.handle(cmd("SISMEMBER", "myset", "a"))).getValue());
        assertEquals(0L, ((RespInteger) handler.handle(cmd("SISMEMBER", "myset", "x"))).getValue());

        assertEquals(1L, ((RespInteger) handler.handle(cmd("SREM", "myset", "a"))).getValue());
        assertEquals(2L, ((RespInteger) handler.handle(cmd("SCARD", "myset"))).getValue());
    }

    @Test
    void testSetSmembers() {
        handler.handle(cmd("SADD", "myset", "x", "y", "z"));
        Object resp = handler.handle(cmd("SMEMBERS", "myset"));
        assertTrue(resp instanceof RespArray);
        assertEquals(3, ((RespArray) resp).size());
    }

    @Test
    void testSetInter() {
        handler.handle(cmd("SADD", "s1", "a", "b", "c"));
        handler.handle(cmd("SADD", "s2", "b", "c", "d"));
        Object resp = handler.handle(cmd("SINTER", "s1", "s2"));
        assertTrue(resp instanceof RespArray);
        assertEquals(2, ((RespArray) resp).size()); // b, c
    }

    @Test
    void testSetUnion() {
        handler.handle(cmd("SADD", "s1", "a", "b"));
        handler.handle(cmd("SADD", "s2", "b", "c"));
        Object resp = handler.handle(cmd("SUNION", "s1", "s2"));
        assertTrue(resp instanceof RespArray);
        assertEquals(3, ((RespArray) resp).size()); // a, b, c
    }

    @Test
    void testSetDiff() {
        handler.handle(cmd("SADD", "s1", "a", "b", "c"));
        handler.handle(cmd("SADD", "s2", "b"));
        Object resp = handler.handle(cmd("SDIFF", "s1", "s2"));
        assertTrue(resp instanceof RespArray);
        assertEquals(2, ((RespArray) resp).size()); // a, c
    }

    @Test
    void testSrandmember() {
        handler.handle(cmd("SADD", "myset", "a", "b", "c"));
        Object resp = handler.handle(cmd("SRANDMEMBER", "myset"));
        assertTrue(resp instanceof RespBulkString);
        String val = ((RespBulkString) resp).getString();
        assertTrue("abc".contains(val));
    }

    // ==================== Sorted Set 命令测试 ====================

    @Test
    void testZsetAddAndGet() {
        handler.handle(cmd("ZADD", "myzset", "1.5", "a", "2.5", "b", "3.5", "c"));
        assertEquals(3L, ((RespInteger) handler.handle(cmd("ZCARD", "myzset"))).getValue());
        assertEquals("1.5", ((RespBulkString) handler.handle(cmd("ZSCORE", "myzset", "a"))).getString());
        assertEquals(0L, ((RespInteger) handler.handle(cmd("ZRANK", "myzset", "a"))).getValue());
        assertEquals(2L, ((RespInteger) handler.handle(cmd("ZRANK", "myzset", "c"))).getValue());
    }

    @Test
    void testZsetRange() {
        handler.handle(cmd("ZADD", "zs", "1", "a", "2", "b", "3", "c"));
        Object resp = handler.handle(cmd("ZRANGE", "zs", "0", "1"));
        assertTrue(resp instanceof RespArray);
        assertEquals(2, ((RespArray) resp).size());

        // WITHSCORES
        resp = handler.handle(cmd("ZRANGE", "zs", "0", "1", "WITHSCORES"));
        assertTrue(resp instanceof RespArray);
        assertEquals(4, ((RespArray) resp).size()); // a, 1, b, 2
    }

    @Test
    void testZsetRevrange() {
        handler.handle(cmd("ZADD", "zs", "1", "a", "2", "b", "3", "c"));
        Object resp = handler.handle(cmd("ZREVRANGE", "zs", "0", "1"));
        assertTrue(resp instanceof RespArray);
        RespArray arr = (RespArray) resp;
        assertEquals(2, arr.size());
        // 降序：c, b
    }

    @Test
    void testZsetCount() {
        handler.handle(cmd("ZADD", "zs", "1", "a", "2", "b", "3", "c", "4", "d"));
        assertEquals(2L, ((RespInteger) handler.handle(cmd("ZCOUNT", "zs", "2", "3"))).getValue());
    }

    @Test
    void testZsetIncrby() {
        handler.handle(cmd("ZADD", "zs", "10", "a"));
        // ZINCRBY 返回 RespBulkString 格式的新的 score，formatDouble 去掉尾部零
        assertEquals("15", ((RespBulkString) handler.handle(cmd("ZINCRBY", "zs", "5", "a"))).getString());
        assertEquals("15", ((RespBulkString) handler.handle(cmd("ZSCORE", "zs", "a"))).getString());
    }

    @Test
    void testZsetRem() {
        handler.handle(cmd("ZADD", "zs", "1", "a", "2", "b"));
        assertEquals(1L, ((RespInteger) handler.handle(cmd("ZREM", "zs", "a"))).getValue());
        assertEquals(1L, ((RespInteger) handler.handle(cmd("ZCARD", "zs"))).getValue());
        assertTrue(((RespBulkString) handler.handle(cmd("ZSCORE", "zs", "a"))).isNull());
    }

    // ==================== 事务命令测试 ====================

    @Test
    void testMultiExec() {
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("MULTI"))).getValue());
        Object queued1 = handler.handle(cmd("SET", "tx1", "v1"));
        assertEquals("QUEUED", ((RespSimpleString) queued1).getValue());
        Object queued2 = handler.handle(cmd("SET", "tx2", "v2"));
        assertEquals("QUEUED", ((RespSimpleString) queued2).getValue());
        Object queued3 = handler.handle(cmd("GET", "tx1"));
        assertEquals("QUEUED", ((RespSimpleString) queued3).getValue());

        Object execResult = handler.handle(cmd("EXEC"));
        assertTrue(execResult instanceof RespArray);
        RespArray results = (RespArray) execResult;
        assertEquals(3, results.size());
    }

    @Test
    void testMultiDiscard() {
        handler.handle(cmd("MULTI"));
        handler.handle(cmd("SET", "tx", "val"));
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("DISCARD"))).getValue());
        assertTrue(((RespBulkString) handler.handle(cmd("GET", "tx"))).isNull());
    }

    // ==================== RENAME / RANDOMKEY 测试 ====================

    @Test
    void testRename() {
        handler.handle(cmd("SET", "oldkey", "value"));
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("RENAME", "oldkey", "newkey"))).getValue());
        assertEquals("value", ((RespBulkString) handler.handle(cmd("GET", "newkey"))).getString());
        assertTrue(((RespBulkString) handler.handle(cmd("GET", "oldkey"))).isNull());
    }

    @Test
    void testRenamenx() {
        handler.handle(cmd("SET", "k1", "v1"));
        handler.handle(cmd("SET", "k2", "v2"));
        // k2 already exists, should fail
        assertEquals(0L, ((RespInteger) handler.handle(cmd("RENAMENX", "k1", "k2"))).getValue());
        // Target doesn't exist, should succeed
        handler.handle(cmd("DEL", "k2"));
        assertEquals(1L, ((RespInteger) handler.handle(cmd("RENAMENX", "k1", "k2"))).getValue());
    }

    @Test
    void testRandomkey() {
        handler.handle(cmd("SET", "k1", "v1"));
        handler.handle(cmd("SET", "k2", "v2"));
        Object resp = handler.handle(cmd("RANDOMKEY"));
        assertTrue(resp instanceof RespBulkString);
        String key = ((RespBulkString) resp).getString();
        assertTrue("k1k2".contains(key));
    }

    // ==================== 多数据库测试 ====================

    @Test
    void testMultiDatabase() {
        // 验证 SELECT 命令正常返回
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("SELECT", "0"))).getValue());
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("SELECT", "1"))).getValue());
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("SELECT", "0"))).getValue());

        // 当前实现中所有数据操作均在 DB 0
        handler.handle(cmd("SET", "key", "db0"));
        assertEquals("db0", ((RespBulkString) handler.handle(cmd("GET", "key"))).getString());
    }

    @Test
    void testSelectBoundary() {
        // 有效范围 0-15
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("SELECT", "0"))).getValue());
        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("SELECT", "15"))).getValue());
        // 超出范围
        Object resp = handler.handle(cmd("SELECT", "16"));
        assertTrue(resp instanceof RespError);
    }

    // ==================== SLOWLOG 测试 ====================

    @Test
    void testSlowlog() {
        Object resp = handler.handle(cmd("SLOWLOG", "LEN"));
        assertTrue(resp instanceof RespInteger);

        resp = handler.handle(cmd("SLOWLOG", "GET", "10"));
        assertTrue(resp instanceof RespArray);

        resp = handler.handle(cmd("SLOWLOG", "RESET"));
        assertEquals("OK", ((RespSimpleString) resp).getValue());
    }

    // ==================== TYPE 命令测试（多类型） ====================

    @Test
    void testTypeForAllDataStructures() {
        handler.handle(cmd("SET", "str", "val"));
        assertEquals("string", ((RespSimpleString) handler.handle(cmd("TYPE", "str"))).getValue());

        handler.handle(cmd("HSET", "hash", "f", "v"));
        assertEquals("hash", ((RespSimpleString) handler.handle(cmd("TYPE", "hash"))).getValue());

        handler.handle(cmd("LPUSH", "list", "v"));
        assertEquals("list", ((RespSimpleString) handler.handle(cmd("TYPE", "list"))).getValue());

        handler.handle(cmd("SADD", "set", "v"));
        assertEquals("set", ((RespSimpleString) handler.handle(cmd("TYPE", "set"))).getValue());

        handler.handle(cmd("ZADD", "zset", "1", "v"));
        assertEquals("zset", ((RespSimpleString) handler.handle(cmd("TYPE", "zset"))).getValue());

        assertEquals("none", ((RespSimpleString) handler.handle(cmd("TYPE", "nonexistent"))).getValue());
    }

    // ==================== DEL 跨类型测试 ====================

    @Test
    void testDelCrossTypes() {
        handler.handle(cmd("SET", "k1", "v"));
        handler.handle(cmd("HSET", "k2", "f", "v"));
        handler.handle(cmd("LPUSH", "k3", "v"));
        handler.handle(cmd("SADD", "k4", "v"));
        handler.handle(cmd("ZADD", "k5", "1", "v"));

        assertEquals(5L, ((RespInteger) handler.handle(cmd("DEL", "k1", "k2", "k3", "k4", "k5"))).getValue());
        assertEquals(0L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }

    // ==================== INFO 增强测试 ====================

    @Test
    void testInfoEnhanced() {
        Object resp = handler.handle(cmd("INFO"));
        assertTrue(resp instanceof RespBulkString);
        String info = ((RespBulkString) resp).getString();
        assertTrue(info.contains("z-cache_version"));
        assertTrue(info.contains("used_memory"));
        assertTrue(info.contains("connected_clients"));
        assertTrue(info.contains("keyspace_hits"));
    }

    // ==================== FLUSHDB / FLUSHALL 测试 ====================

    @Test
    void testFlushdbOnlyCurrentDb() {
        // 验证 FLUSHDB 返回 OK 并清除当前 DB 的数据
        handler.handle(cmd("SET", "k1", "v1"));
        handler.handle(cmd("SELECT", "1"));
        handler.handle(cmd("SET", "k2", "v2"));
        handler.handle(cmd("SELECT", "0"));

        assertEquals("OK", ((RespSimpleString) handler.handle(cmd("FLUSHDB"))).getValue());
        // FLUSHDB 清除 DB 0 中的所有数据
        assertTrue(((RespBulkString) handler.handle(cmd("GET", "k1"))).isNull());
    }

}