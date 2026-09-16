package com.zifang.z.cache.core.protocol;

import com.zifang.z.cache.common.protocol.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RespEncoder 单元测试 (服务端)
 */
class RespEncoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new RespEncoder());
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    // ==================== Simple String ====================

    @Test
    void testEncodeSimpleString() {
        channel.writeOutbound(RespSimpleString.of("OK"));
        ByteBuf buf = channel.readOutbound();
        assertEquals("+OK\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeEmptySimpleString() {
        channel.writeOutbound(RespSimpleString.of(""));
        ByteBuf buf = channel.readOutbound();
        assertEquals("+\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeSimpleStringOkSingleton() {
        channel.writeOutbound(RespSimpleString.ok());
        ByteBuf buf = channel.readOutbound();
        assertEquals("+OK\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    // ==================== Error ====================

    @Test
    void testEncodeError() {
        channel.writeOutbound(RespError.of("ERR something"));
        ByteBuf buf = channel.readOutbound();
        assertEquals("-ERR something\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeWrongType() {
        channel.writeOutbound(RespError.wrongType("not a list"));
        ByteBuf buf = channel.readOutbound();
        assertEquals("-WRONGTYPE not a list\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    // ==================== Integer ====================

    @Test
    void testEncodeInteger() {
        channel.writeOutbound(RespInteger.of(42L));
        ByteBuf buf = channel.readOutbound();
        assertEquals(":42\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeNegativeInteger() {
        channel.writeOutbound(RespInteger.of(-1L));
        ByteBuf buf = channel.readOutbound();
        assertEquals(":-1\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeLargeInteger() {
        channel.writeOutbound(RespInteger.of(Long.MAX_VALUE));
        ByteBuf buf = channel.readOutbound();
        assertEquals(":" + Long.MAX_VALUE + "\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeZero() {
        channel.writeOutbound(RespInteger.ZERO);
        ByteBuf buf = channel.readOutbound();
        assertEquals(":0\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    // ==================== Bulk String ====================

    @Test
    void testEncodeBulkString() {
        channel.writeOutbound(RespBulkString.of("hello"));
        ByteBuf buf = channel.readOutbound();
        assertEquals("$5\r\nhello\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeEmptyBulkString() {
        channel.writeOutbound(RespBulkString.of(""));
        ByteBuf buf = channel.readOutbound();
        assertEquals("$0\r\n\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeNullBulkString() {
        channel.writeOutbound(RespBulkString.nullBulkString());
        ByteBuf buf = channel.readOutbound();
        assertEquals("$-1\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeBinaryBulkString() {
        byte[] data = new byte[]{1, 2, 3, (byte) 0xff};
        channel.writeOutbound(RespBulkString.of(data));
        ByteBuf buf = channel.readOutbound();
        byte[] expected = new byte[4 + 4 + 2];  // "$4\r\n" (4) + data (4) + "\r\n" (2)
        byte[] header = "$4\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] footer = "\r\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(header, 0, expected, 0, 4);
        System.arraycopy(data, 0, expected, 4, 4);
        System.arraycopy(footer, 0, expected, 8, 2);
        byte[] actual = readAllBytes(buf);
        assertArrayEquals(expected, actual);
    }

    // ==================== Array ====================

    @Test
    void testEncodeEmptyArray() {
        channel.writeOutbound(RespArray.empty());
        ByteBuf buf = channel.readOutbound();
        assertEquals("*0\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeNullArray() {
        channel.writeOutbound(RespArray.nullArray());
        ByteBuf buf = channel.readOutbound();
        assertEquals("*-1\r\n", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeSimpleArray() {
        RespArray array = RespArray.of(
                RespBulkString.of("GET"),
                RespBulkString.of("key"));
        channel.writeOutbound(array);
        ByteBuf buf = channel.readOutbound();
        assertEquals("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n",
                buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testEncodeMixedArray() {
        RespArray array = RespArray.of(
                RespBulkString.of("SETEX"),
                RespBulkString.of("k"),
                RespInteger.of(60L),
                RespBulkString.of("v"));
        channel.writeOutbound(array);
        ByteBuf buf = channel.readOutbound();
        String s = buf.toString(StandardCharsets.UTF_8);
        assertTrue(s.startsWith("*4\r\n"));
        assertTrue(s.contains("$5\r\nSETEX\r\n"));
        assertTrue(s.contains("$1\r\nk\r\n"));
        assertTrue(s.contains(":60\r\n"));
        assertTrue(s.contains("$1\r\nv\r\n"));
    }

    @Test
    void testEncodeCommandArray() {
        RespArray array = RespArray.command("SET", "key", "value");
        channel.writeOutbound(array);
        ByteBuf buf = channel.readOutbound();
        String s = buf.toString(StandardCharsets.UTF_8);
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n",
                s);
    }

    // ==================== Multiple writes ====================

    @Test
    void testEncodeMultipleMessages() {
        channel.writeOutbound(RespSimpleString.ok());
        channel.writeOutbound(RespInteger.of(1L));
        channel.writeOutbound(RespError.of("ERR bad"));

        assertEquals("+OK\r\n", readUtf8(channel.readOutbound()));
        assertEquals(":1\r\n", readUtf8(channel.readOutbound()));
        assertEquals("-ERR bad\r\n", readUtf8(channel.readOutbound()));
    }

    // ==================== Special inputs ====================

    @Test
    void testEncodeNullAsNullBulkString() {
        // MessageToByteEncoder skips null messages entirely, so writing null
        // should not produce any outbound buffer.
        channel.writeOutbound((Object) null);
        ByteBuf buf = channel.readOutbound();
        assertNull(buf);
    }

    @Test
    void testEncodeUnsupportedTypeThrows() {
        // Netty wraps EncoderException around the underlying cause; verify
        // the cause is the expected IllegalArgumentException.
        Object unsupported = new Object();
        try {
            channel.writeOutbound(unsupported);
            // Force the encoder to run
            channel.flushOutbound();
            ByteBuf buf = channel.readOutbound();
            if (buf != null) { buf.release(); }

            fail("Expected an exception to be thrown");
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            assertTrue(cause instanceof IllegalArgumentException,
                    "Expected IllegalArgumentException, got " + cause.getClass().getName());
        }
    }

    // ==================== Util ====================

    private static String readUtf8(ByteBuf buf) {
        String s = buf.toString(StandardCharsets.UTF_8);
        buf.release();
        return s;
    }

    private static byte[] readAllBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }
}