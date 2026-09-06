package com.zifang.z.cache.client.protocol;

import com.zifang.z.cache.common.protocol.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClientRespEncoder 测试类
 */
class ClientRespEncoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new ClientRespEncoder());
    }

    @Test
    void testEncodeSimpleString() {
        RespSimpleString resp = RespSimpleString.of("OK");
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals("+OK\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeError() {
        RespError resp = RespError.of("ERR", "unknown command");
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals("-ERR unknown command\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeInteger() {
        RespInteger resp = RespInteger.of(42L);
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals(":42\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeBulkString() {
        RespBulkString resp = RespBulkString.of("hello");
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals("$5\r\nhello\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeNullBulkString() {
        RespBulkString resp = RespBulkString.nullBulkString();
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals("$-1\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeArray() {
        RespArray resp = RespArray.of(
                RespBulkString.of("GET"),
                RespBulkString.of("key")
        );
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertTrue(encoded.startsWith("*2\r\n"));
        assertTrue(encoded.contains("$3\r\nGET\r\n"));
        assertTrue(encoded.contains("$3\r\nkey\r\n"));

        buf.release();
    }

    @Test
    void testEncodeEmptyArray() {
        RespArray resp = RespArray.empty();
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals("*0\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeNullArray() {
        RespArray resp = RespArray.nullArray();
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals("*-1\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeNegativeInteger() {
        RespInteger resp = RespInteger.of(-42L);
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals(":-42\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeLargeInteger() {
        RespInteger resp = RespInteger.of(9223372036854775807L);
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals(":9223372036854775807\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeEmptyBulkString() {
        RespBulkString resp = RespBulkString.of("");
        channel.writeOutbound(resp);

        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);

        String encoded = buf.toString(StandardCharsets.UTF_8);
        assertEquals("$0\r\n\r\n", encoded);

        buf.release();
    }

    @Test
    void testEncodeMultipleMessages() {
        // 测试连续编码多个消息
        RespSimpleString resp1 = RespSimpleString.of("OK");
        RespInteger resp2 = RespInteger.of(42L);
        RespError resp3 = RespError.of("ERR", "error");

        channel.writeOutbound(resp1, resp2, resp3);

        ByteBuf buf1 = channel.readOutbound();
        ByteBuf buf2 = channel.readOutbound();
        ByteBuf buf3 = channel.readOutbound();

        assertEquals("+OK\r\n", buf1.toString(StandardCharsets.UTF_8));
        assertEquals(":42\r\n", buf2.toString(StandardCharsets.UTF_8));
        assertEquals("-ERR error\r\n", buf3.toString(StandardCharsets.UTF_8));

        buf1.release();
        buf2.release();
        buf3.release();
    }
}
