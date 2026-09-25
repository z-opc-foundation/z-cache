package com.zifang.z.cache.core.command;

import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.storage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandHandler 基础命令测试。
 * <p>
 * 原为 JUnit 4 写法：core 只挂了 junit-jupiter 引擎，surefire 从不收集 {@code org.junit.Test}，
 * 所以这 16 个用例长期零执行（target/surefire-reports 里一直没有这个类）。
 * 迁移到 JUnit 5 后它们才真正进测试集。
 */
class CommandHandlerTest {

    private MemoryStore store;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        // 与 CommandHandlerJunit5Test 一致：重置静态共享 Store，避免用例间数据泄漏

        store = new MemoryStore();
        handler = new CommandHandler(store);
    }

    private RespArray cmd(String... args) {
        Object[] bulkStrings = Arrays.stream(args)
                .map(RespBulkString::of)
                .toArray();
        return RespArray.of(bulkStrings);
    }

    // ==================== Connection Commands ====================

    @Test
    void testPing() {
        Object result = handler.handle(cmd("PING"));
        assertEquals("PONG", ((RespSimpleString) result).getValue());

        result = handler.handle(cmd("PING", "hello"));
        assertEquals("hello", ((RespBulkString) result).getString());
    }

    @Test
    void testEcho() {
        Object result = handler.handle(cmd("ECHO", "hello world"));
        assertEquals("hello world", ((RespBulkString) result).getString());
    }

    @Test
    void testSelect() {
        assertTrue(handler.handle(cmd("SELECT", "0")) instanceof RespSimpleString);
        // 旧用例断言 SELECT 1 报错，那是只有单库时代的期望；1.0.2 起支持 0-15
        assertTrue(handler.handle(cmd("SELECT", "1")) instanceof RespSimpleString);
        assertTrue(handler.handle(cmd("SELECT", "abc")) instanceof RespError);
    }

    // ==================== String Commands ====================

    @Test
    void testSetGet() {
        handler.handle(cmd("SET", "key", "value"));
        Object result = handler.handle(cmd("GET", "key"));
        assertEquals("value", ((RespBulkString) result).getString());
    }

    @Test
    void testSetOptions() {
        handler.handle(cmd("SET", "key1", "value", "EX", "10"));
        Object ttl = handler.handle(cmd("TTL", "key1"));
        assertTrue(((RespInteger) ttl).getValue() > 0);

        handler.handle(cmd("SET", "key2", "value1"));
        Object result = handler.handle(cmd("SET", "key2", "value2", "NX"));
        assertTrue(((RespBulkString) result).isNull());
    }

    @Test
    void testDel() {
        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));

        Object result = handler.handle(cmd("DEL", "key1", "key2", "nonexistent"));
        assertEquals(2L, ((RespInteger) result).getValue());

        assertTrue(((RespBulkString) handler.handle(cmd("GET", "key1"))).isNull());
    }

    @Test
    void testExists() {
        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));

        Object result = handler.handle(cmd("EXISTS", "key1", "key2", "nonexistent"));
        assertEquals(2L, ((RespInteger) result).getValue());
    }

    @Test
    void testExpireTtl() throws InterruptedException {
        handler.handle(cmd("SET", "key", "value"));

        Object result = handler.handle(cmd("EXPIRE", "key", "1"));
        assertEquals(1L, ((RespInteger) result).getValue());

        Object ttl = handler.handle(cmd("TTL", "key"));
        assertTrue(((RespInteger) ttl).getValue() > 0);

        Thread.sleep(1100);
        ttl = handler.handle(cmd("TTL", "key"));
        assertEquals(-2L, ((RespInteger) ttl).getValue());
    }

    @Test
    void testPersist() {
        handler.handle(cmd("SET", "key", "value"));
        handler.handle(cmd("EXPIRE", "key", "10"));

        Object result = handler.handle(cmd("PERSIST", "key"));
        assertEquals(1L, ((RespInteger) result).getValue());

        Object ttl = handler.handle(cmd("TTL", "key"));
        assertEquals(-1L, ((RespInteger) ttl).getValue());
    }

    // ==================== Key Management ====================

    @Test
    void testKeys() {
        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));
        handler.handle(cmd("SET", "key3", "value3"));

        Object result = handler.handle(cmd("KEYS", "*"));
        assertTrue(result instanceof RespArray);
        assertEquals(3, ((RespArray) result).size());
    }

    @Test
    void testDbsize() {
        assertEquals(0L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());

        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));

        assertEquals(2L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }

    @Test
    void testFlushdb() {
        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));

        Object result = handler.handle(cmd("FLUSHDB"));
        assertTrue(result instanceof RespSimpleString);

        assertEquals(0L, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }

    // ==================== Error Handling ====================

    @Test
    void testNullRequest() {
        assertTrue(handler.handle(null) instanceof RespError);
    }

    @Test
    void testNonArrayRequest() {
        assertTrue(handler.handle(RespBulkString.of("GET key")) instanceof RespError);
    }

    @Test
    void testEmptyArrayRequest() {
        assertTrue(handler.handle(RespArray.empty()) instanceof RespError);
    }

    @Test
    void testUnknownCommand() {
        assertTrue(handler.handle(cmd("UNKNOWN_COMMAND")) instanceof RespError);
    }
}
