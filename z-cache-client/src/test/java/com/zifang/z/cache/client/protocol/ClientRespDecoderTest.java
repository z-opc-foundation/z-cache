package com.zifang.z.cache.client.protocol;

import com.zifang.z.cache.common.protocol.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClientRespDecoder 测试类
 */
class ClientRespDecoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new ClientRespDecoder());
    }

    @AfterEach
    void tearDown() {
        channel.finish();
    }

    @Test
    void testDecodeSimpleString() {
        ByteBuf buf = Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) result).getValue());
    }

    @Test
    void testDecodeError() {
        ByteBuf buf = Unpooled.copiedBuffer("-ERR unknown command\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespError);
    }

    @Test
    void testDecodeInteger() {
        ByteBuf buf = Unpooled.copiedBuffer(":42\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespInteger);
        assertEquals(42L, ((RespInteger) result).getValue());
    }

    @Test
    void testDecodeNegativeInteger() {
        ByteBuf buf = Unpooled.copiedBuffer(":-100\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespInteger);
        assertEquals(-100L, ((RespInteger) result).getValue());
    }

    @Test
    void testDecodeBulkString() {
        ByteBuf buf = Unpooled.copiedBuffer("$5\r\nhello\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespBulkString);
        assertEquals("hello", ((RespBulkString) result).getString());
    }

    @Test
    void testDecodeEmptyBulkString() {
        ByteBuf buf = Unpooled.copiedBuffer("$0\r\n\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespBulkString);
        assertEquals("", ((RespBulkString) result).getString());
    }

    @Test
    void testDecodeNullBulkString() {
        ByteBuf buf = Unpooled.copiedBuffer("$-1\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespBulkString);
        assertTrue(((RespBulkString) result).isNull());
    }

    @Test
    void testDecodeArray() {
        ByteBuf buf = Unpooled.copiedBuffer("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespArray);

        RespArray array = (RespArray) result;
        List<Object> elements = array.getElements();
        assertNotNull(elements);
        assertEquals(2, elements.size());

        assertTrue(elements.get(0) instanceof RespBulkString);
        assertEquals("GET", ((RespBulkString) elements.get(0)).getString());

        assertTrue(elements.get(1) instanceof RespBulkString);
        assertEquals("key", ((RespBulkString) elements.get(1)).getString());
    }

    @Test
    void testDecodeEmptyArray() {
        ByteBuf buf = Unpooled.copiedBuffer("*0\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespArray);
        assertTrue(((RespArray) result).isEmpty());
    }

    @Test
    void testDecodeNullArray() {
        ByteBuf buf = Unpooled.copiedBuffer("*-1\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespArray);
        assertTrue(((RespArray) result).isNull());
    }

    @Test
    void testDecodeLargeInteger() {
        ByteBuf buf = Unpooled.copiedBuffer(":" + Long.MAX_VALUE + "\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespInteger);
        assertEquals(Long.MAX_VALUE, ((RespInteger) result).getValue());
    }

    @Test
    void testDecodeZero() {
        ByteBuf buf = Unpooled.copiedBuffer(":0\r\n", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertNotNull(result);
        assertTrue(result instanceof RespInteger);
        assertEquals(0L, ((RespInteger) result).getValue());
    }
}
