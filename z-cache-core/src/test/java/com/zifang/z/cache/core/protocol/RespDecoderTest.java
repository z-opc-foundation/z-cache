package com.zifang.z.cache.core.protocol;

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
 * RespDecoder 单元测试 (服务端)
 */
class RespDecoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new RespDecoder());
    }

    @AfterEach
    void tearDown() {
        channel.finish();
    }

    // ==================== Simple String ====================

    @Test
    void testDecodeSimpleString() {
        writeInbound("+OK\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespSimpleString);
        assertEquals("OK", ((RespSimpleString) msg).getValue());
    }

    @Test
    void testDecodeEmptySimpleString() {
        writeInbound("+\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespSimpleString);
        assertEquals("", ((RespSimpleString) msg).getValue());
    }

    // ==================== Error ====================

    @Test
    void testDecodeError() {
        writeInbound("-ERR something bad\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespError);
        assertEquals("ERR something bad", ((RespError) msg).getMessage());
    }

    @Test
    void testDecodeWrongType() {
        writeInbound("-WRONGTYPE key\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespError);
        assertEquals("WRONGTYPE", ((RespError) msg).getErrorType());
    }

    // ==================== Integer ====================

    @Test
    void testDecodeInteger() {
        writeInbound(":42\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespInteger);
        assertEquals(42L, ((RespInteger) msg).getValue());
    }

    @Test
    void testDecodeNegativeInteger() {
        writeInbound(":-100\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespInteger);
        assertEquals(-100L, ((RespInteger) msg).getValue());
    }

    @Test
    void testDecodeZero() {
        writeInbound(":0\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespInteger);
        assertEquals(0L, ((RespInteger) msg).getValue());
    }

    @Test
    void testDecodeMaxInteger() {
        writeInbound(":" + Long.MAX_VALUE + "\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespInteger);
        assertEquals(Long.MAX_VALUE, ((RespInteger) msg).getValue());
    }

    @Test
    void testDecodeInvalidIntegerThrows() {
        // Netty's ReplayingDecoder surfaces parse failures as DecoderException
        io.netty.handler.codec.DecoderException ex = assertThrows(
                io.netty.handler.codec.DecoderException.class,
                () -> writeInbound(":not-a-number\r\n"));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    // ==================== Bulk String ====================

    @Test
    void testDecodeBulkString() {
        writeInbound("$5\r\nhello\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespBulkString);
        assertEquals("hello", ((RespBulkString) msg).getString());
    }

    @Test
    void testDecodeEmptyBulkString() {
        writeInbound("$0\r\n\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespBulkString);
        assertEquals("", ((RespBulkString) msg).getString());
    }

    @Test
    void testDecodeNullBulkString() {
        writeInbound("$-1\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespBulkString);
        assertTrue(((RespBulkString) msg).isNull());
    }

    // ==================== Array ====================

    @Test
    void testDecodeEmptyArray() {
        writeInbound("*0\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespArray);
        assertTrue(((RespArray) msg).isEmpty());
    }

    @Test
    void testDecodeNullArray() {
        writeInbound("*-1\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespArray);
        assertTrue(((RespArray) msg).isNull());
    }

    @Test
    void testDecodeSimpleArray() {
        writeInbound("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespArray);
        RespArray array = (RespArray) msg;
        List<Object> elements = array.getElements();
        assertEquals(2, elements.size());
        assertEquals("GET", ((RespBulkString) elements.get(0)).getString());
        assertEquals("key", ((RespBulkString) elements.get(1)).getString());
    }

    @Test
    void testDecodeMixedArray() {
        writeInbound("*4\r\n$5\r\nSETEX\r\n$1\r\nk\r\n:60\r\n$1\r\nv\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespArray);
        RespArray array = (RespArray) msg;
        List<Object> elements = array.getElements();
        assertEquals(4, elements.size());
        assertEquals("SETEX", ((RespBulkString) elements.get(0)).getString());
        assertEquals("k", ((RespBulkString) elements.get(1)).getString());
        assertEquals(60L, ((RespInteger) elements.get(2)).getValue());
        assertEquals("v", ((RespBulkString) elements.get(3)).getString());
    }

    @Test
    void testDecodeArrayWithNullElements() {
        writeInbound("*3\r\n$3\r\nGET\r\n$-1\r\n$3\r\nfoo\r\n");
        Object msg = channel.readInbound();
        assertTrue(msg instanceof RespArray);
        RespArray array = (RespArray) msg;
        List<Object> elements = array.getElements();
        assertEquals(3, elements.size());
        assertTrue(((RespBulkString) elements.get(1)).isNull());
    }

    @Test
    void testDecodeInvalidArrayLengthThrows() {
        assertDecodeFailsOnce("*not-a-number\r\n", "Invalid array length");
    }

    @Test
    void testDecodeNegativeArrayLength() {
        // Only -1 is a valid sentinel; -2 is rejected
        assertDecodeFailsOnce("*-2\r\n", "Invalid array length");
    }

    @Test
    void testDecodeInvalidBulkStringLength() {
        assertDecodeFailsOnce("$not-a-number\r\n", "Invalid bulk string length");
    }

    /**
     * 坏帧只许报一次错：断言异常确实冒出来（writeInbound 当场抛，或 finish() 补投，
     * 二者取其一），并且第二次不再冒同一个异常 —— 以前 ReplayingDecoder 那版游标会停在
     * 坏字节上，读一条新命令就把同一个错重投一次。
     */
    private void assertDecodeFailsOnce(String frame, String expectedMessage) {
        EmbeddedChannel fresh = new EmbeddedChannel(new RespDecoder());
        String surfaced = null;
        try {
            fresh.writeInbound(Unpooled.copiedBuffer(frame, StandardCharsets.UTF_8));
        } catch (io.netty.handler.codec.DecoderException e) {
            surfaced = String.valueOf(e.getCause());
        }
        if (surfaced == null) {
            try {
                fresh.finish();
                fail("bad frame must surface an exception, got none: " + frame);
            } catch (io.netty.handler.codec.DecoderException e) {
                surfaced = String.valueOf(e.getCause());
            }
        }
        assertTrue(surfaced.contains(expectedMessage),
                "expected \"" + expectedMessage + "\" in: " + surfaced);
        try {
            fresh.close().sync();
        } catch (Exception ignored) {
            // 同一个异常在 close 时可能再投一次，这里要断的是"报错"而不是"报几次"
        }
    }

    @Test
    void testDecodeUnknownTypeThrows() {
        // The decoder throws on first byte when type is unknown; ReplayingDecoder
        // surfaces this synchronously from writeInbound wrapped in DecoderException.
        EmbeddedChannel fresh = new EmbeddedChannel(new RespDecoder());
        ByteBuf buf = Unpooled.copiedBuffer("@unknown\r\n", StandardCharsets.UTF_8);
        io.netty.handler.codec.DecoderException ex = assertThrows(
                io.netty.handler.codec.DecoderException.class,
                () -> fresh.writeInbound(buf));
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause() instanceof IllegalArgumentException,
                "Expected IllegalArgumentException, got " + ex.getCause().getClass().getName());
        // finish() may also surface the same exception; wrap to swallow it.
        try {
            fresh.finish();
        } catch (io.netty.handler.codec.DecoderException ignored) {
            // expected — the same exception is re-raised on close
        }
    }

    // ==================== Streaming ====================

    @Test
    void testDecodePartialSimpleStringWaitsForMore() {
        // Write only half: "+OK" - no CRLF yet
        writeInbound("+OK");
        Object msg = channel.readInbound();
        assertNull(msg, "should not produce output until CRLF is seen");

        // Now complete the message
        writeInbound("\r\n");
        msg = channel.readInbound();
        assertNotNull(msg);
        assertEquals("OK", ((RespSimpleString) msg).getValue());
    }

    @Test
    void testDecodePartialBulkStringWaitsForMore() {
        EmbeddedChannel fresh = new EmbeddedChannel(new RespDecoder());
        try {
            fresh.writeInbound(Unpooled.copiedBuffer("$10\r\nhellow", StandardCharsets.UTF_8));
            assertNull(fresh.readInbound(), "body not complete yet");
            // 长度行已经说了要 10 字节，"ore" 之后的 \r\n 没到之前不能提前收尾，
            // 也不能把后面那帧的字节当 body 吃掉。
            fresh.writeInbound(Unpooled.copiedBuffer("orld", StandardCharsets.UTF_8));
            assertNull(fresh.readInbound(), "trailing CRLF missing");
            fresh.writeInbound(Unpooled.copiedBuffer("\r\n", StandardCharsets.UTF_8));
            Object msg = fresh.readInbound();
            assertTrue(msg instanceof RespBulkString);
            assertEquals("helloworld", ((RespBulkString) msg).getString());
            assertNull(fresh.readInbound(), "one frame, one value");
        } finally {
            fresh.finish();
        }
    }

    /**
     * 一条命令被 TCP 在任意字节处切开，必须还是那一条命令。
     * <p>
     * 这一条以前是漏的，而且是会咬人的那种漏：多参数命令被切开后，解码器在交出正确数组
     * 之外<b>又吐出若干散装元素</b>，服务端对每个散装元素回一条
     * {@code ERR Protocol error: expected array}，客户端的请求/响应就此错位；更糟的是
     * 被错读的元素会当成下一条命令的参数落库 —— 250 上实测 {@code ZADD z1 1 one 2 two 3
     * three} 被切一刀之后，z1 里多出一个名叫 "ZADD" 的成员。
     */
    @Test
    void testDecodeEverySplitPointOfEveryFrameShape() {
        String[] frames = {
                "*8\r\n$4\r\nZADD\r\n$2\r\nz1\r\n$1\r\n1\r\n$3\r\none\r\n$1\r\n2\r\n$3\r\ntwo\r\n$1\r\n3\r\n$5\r\nthree\r\n",
                "*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n",
                "*5\r\n$4\r\nMSET\r\n$2\r\na1\r\n$1\r\n1\r\n$2\r\na2\r\n$1\r\n2\r\n",
                "*3\r\n$3\r\nSET\r\n$2\r\nk2\r\n$8\r\na APPEND\r\n",
                "$10\r\nhelloworld\r\n",
                "*4\r\n$6\r\nSUBSTR\r\n$2\r\ns1\r\n:0\r\n:-1\r\n",
                "*2\r\n*2\r\n$1\r\na\r\n$1\r\nb\r\n*0\r\n",
                ":42\r\n",
                "*4\r\n$3\r\nSET\r\n$1\r\nk\r\n$-1\r\n$2\r\nNX\r\n",
        };
        for (String frame : frames) {
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
            // 先验夹具本身是合法 RESP：写歪一个长度，下面每个断言都在测假东西。
            EmbeddedChannel whole = new EmbeddedChannel(new RespDecoder());
            try {
                whole.writeInbound(Unpooled.wrappedBuffer(bytes));
                assertEquals(frame, encodeBack(whole.readInbound()), "fixture is not well-formed: " + frame);
                assertNull(whole.readInbound(), "fixture carries a second frame: " + frame);
            } finally {
                whole.finishAndReleaseAll();
            }
            for (int cut = 1; cut < bytes.length; cut++) {
                EmbeddedChannel fresh = new EmbeddedChannel(new RespDecoder());
                try {
                    fresh.writeInbound(Unpooled.wrappedBuffer(bytes, 0, cut));
                    assertNull(fresh.readInbound(),
                            "incomplete frame must not produce output: " + frame + " cut=" + cut);
                    fresh.writeInbound(Unpooled.wrappedBuffer(bytes, cut, bytes.length - cut));
                    Object msg = fresh.readInbound();
                    assertNotNull(msg, frame + " cut at " + cut + " produced nothing");
                    assertEquals(frame, encodeBack(msg),
                            "frame must survive a split at byte " + cut);
                    assertNull(fresh.readInbound(),
                            "exactly one value per frame, cut=" + cut + " of " + frame);
                } finally {
                    fresh.finishAndReleaseAll();
                }
            }
        }
    }

    /** 把解码结果按 RESP2 重新拼回原文，用来把"解码对不对"变成一次字符串比较。 */
    private static String encodeBack(Object value) {
        if (value instanceof RespSimpleString) {
            return "+" + ((RespSimpleString) value).getValue() + "\r\n";
        }
        if (value instanceof RespError) {
            return "-" + ((RespError) value).getMessage() + "\r\n";
        }
        if (value instanceof RespInteger) {
            return ":" + ((RespInteger) value).getValue() + "\r\n";
        }
        if (value instanceof RespBulkString) {
            RespBulkString bulk = (RespBulkString) value;
            if (bulk.isNull()) {
                return "$-1\r\n";
            }
            String s = bulk.getString();
            return "$" + s.getBytes(StandardCharsets.UTF_8).length + "\r\n" + s + "\r\n";
        }
        if (value instanceof RespArray) {
            RespArray array = (RespArray) value;
            if (array.isNull()) {
                return "*-1\r\n";
            }
            StringBuilder sb = new StringBuilder("*").append(array.size()).append("\r\n");
            for (Object element : array.getElements()) {
                sb.append(encodeBack(element));
            }
            return sb.toString();
        }
        return "<?>";
    }

    @Test
    void testDecodeMultipleMessagesOnSameChannel() {
        writeInbound("+OK\r\n");
        writeInbound(":42\r\n");
        writeInbound("-ERR bad\r\n");

        assertEquals("OK", ((RespSimpleString) channel.readInbound()).getValue());
        assertEquals(42L, ((RespInteger) channel.readInbound()).getValue());
        assertEquals("ERR bad", ((RespError) channel.readInbound()).getMessage());
    }

    // ==================== Util ====================

    private void writeInbound(String content) {
        ByteBuf buf = Unpooled.copiedBuffer(content, StandardCharsets.UTF_8);
        channel.writeInbound(buf);
    }
}