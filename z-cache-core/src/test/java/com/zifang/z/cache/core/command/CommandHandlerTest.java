package com.zifang.z.cache.core.command;

import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.storage.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test cases for CommandHandler
 */
public class CommandHandlerTest {

    private MemoryStore store;
    private CommandHandler handler;

    @Before
    public void setUp() {
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
    public void testPing() {
        // PING without args
        Object result = handler.handle(cmd("PING"));
        assertEquals("PONG", ((RespSimpleString) result).getValue());

        // PING with message
        result = handler.handle(cmd("PING", "hello"));
        assertEquals("hello", ((RespBulkString) result).getString());
    }

    @Test
    public void testEcho() {
        Object result = handler.handle(cmd("ECHO", "hello world"));
        assertEquals("hello world", ((RespBulkString) result).getString());
    }

    @Test
    public void testSelect() {
        // DB 0 should work
        Object result = handler.handle(cmd("SELECT", "0"));
        assertTrue(result instanceof RespSimpleString);

        // Other DBs should fail
        result = handler.handle(cmd("SELECT", "1"));
        assertTrue(result instanceof RespError);
    }

    // ==================== String Commands ====================

    @Test
    public void testSetGet() {
        // Basic SET/GET
        handler.handle(cmd("SET", "key", "value"));
        Object result = handler.handle(cmd("GET", "key"));
        assertEquals("value", ((RespBulkString) result).getString());
    }

    @Test
    public void testSetOptions() {
        // SET with EX
        handler.handle(cmd("SET", "key1", "value", "EX", "10"));
        Object ttl = handler.handle(cmd("TTL", "key1"));
        assertTrue(((RespInteger) ttl).getValue() > 0);

        // SET with NX
        handler.handle(cmd("SET", "key2", "value1"));
        Object result = handler.handle(cmd("SET", "key2", "value2", "NX"));
        assertTrue(((RespBulkString) result).isNull()); // Should not set
    }

    @Test
    public void testDel() {
        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));

        Object result = handler.handle(cmd("DEL", "key1", "key2", "nonexistent"));
        assertEquals(2, ((RespInteger) result).getValue());

        assertTrue(((RespBulkString) handler.handle(cmd("GET", "key1"))).isNull());
    }

    @Test
    public void testExists() {
        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));

        Object result = handler.handle(cmd("EXISTS", "key1", "key2", "nonexistent"));
        assertEquals(2, ((RespInteger) result).getValue());
    }

    @Test
    public void testExpireTtl() throws InterruptedException {
        handler.handle(cmd("SET", "key", "value"));

        // Set expiration
        Object result = handler.handle(cmd("EXPIRE", "key", "1"));
        assertEquals(1, ((RespInteger) result).getValue());

        // Check TTL
        Object ttl = handler.handle(cmd("TTL", "key"));
        assertTrue(((RespInteger) ttl).getValue() > 0);

        // Wait for expiration
        Thread.sleep(1100);
        ttl = handler.handle(cmd("TTL", "key"));
        assertEquals(-2, ((RespInteger) ttl).getValue()); // Key doesn't exist
    }

    @Test
    public void testPersist() {
        handler.handle(cmd("SET", "key", "value"));
        handler.handle(cmd("EXPIRE", "key", "10"));

        Object result = handler.handle(cmd("PERSIST", "key"));
        assertEquals(1, ((RespInteger) result).getValue());

        Object ttl = handler.handle(cmd("TTL", "key"));
        assertEquals(-1, ((RespInteger) ttl).getValue()); // No expiration
    }

    // ==================== Key Management ====================

    @Test
    public void testKeys() {
        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));
        handler.handle(cmd("SET", "key3", "value3"));

        Object result = handler.handle(cmd("KEYS", "*"));
        assertTrue(result instanceof RespArray);
        assertEquals(3, ((RespArray) result).size());
    }

    @Test
    public void testDbsize() {
        assertEquals(0, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());

        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));

        assertEquals(2, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }

    @Test
    public void testFlushdb() {
        handler.handle(cmd("SET", "key1", "value1"));
        handler.handle(cmd("SET", "key2", "value2"));

        Object result = handler.handle(cmd("FLUSHDB"));
        assertTrue(result instanceof RespSimpleString);

        assertEquals(0, ((RespInteger) handler.handle(cmd("DBSIZE"))).getValue());
    }

    // ==================== Error Handling ====================

    @Test
    public void testNullRequest() {
        Object result = handler.handle(null);
        assertTrue(result instanceof RespError);
    }

    @Test
    public void testNonArrayRequest() {
        Object result = handler.handle(RespBulkString.of("GET key"));
        assertTrue(result instanceof RespError);
    }

    @Test
    public void testEmptyArrayRequest() {
        Object result = handler.handle(RespArray.empty());
        assertTrue(result instanceof RespError);
    }

    @Test
    public void testUnknownCommand() {
        Object result = handler.handle(cmd("UNKNOWN_COMMAND"));
        assertTrue(result instanceof RespError);
    }
}
