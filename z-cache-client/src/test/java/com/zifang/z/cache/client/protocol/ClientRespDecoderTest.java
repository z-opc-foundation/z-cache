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

    /**
     * 服务端的一条回复被 TCP 在任意字节处切开，客户端必须还是解出一条回复。
     * <p>
     * 服务端那一侧踩过这个坑（多参数命令被切开后解码器额外吐出散装元素，请求/响应就此错位），
     * 客户端这条链路一直是同一种写法，而现有用例全是一次性把整帧写进去的 —— 也就是说
     * 这一族缺陷在客户端这边从来没有被喂过。这里按每一刀都切一遍来量它。
     */
    @Test
    void testDecodeEverySplitPointOfEveryReplyShape() {
        String[] frames = {
                "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n",
                "*8\r\n$4\r\nZADD\r\n$2\r\nz1\r\n$1\r\n1\r\n$3\r\none\r\n$1\r\n2\r\n$3\r\ntwo\r\n$1\r\n3\r\n$5\r\nthree\r\n",
                "*4\r\n$2\r\nk1\r\n$2\r\nv1\r\n$2\r\nk2\r\n$2\r\nv2\r\n",
                "*3\r\n$3\r\nabc\r\n$-1\r\n:7\r\n",
                "$10\r\nhelloworld\r\n",
                "+PONG\r\n",
                ":-100\r\n",
                "*2\r\n*2\r\n$1\r\na\r\n$1\r\nb\r\n*0\r\n",
                "*-1\r\n",
        };
        for (String frame : frames) {
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
            // 先验夹具本身是合法 RESP：写歪一个长度，下面每个断言都在测假东西。
            EmbeddedChannel whole = new EmbeddedChannel(new ClientRespDecoder());
            try {
                whole.writeInbound(Unpooled.wrappedBuffer(bytes));
                assertEquals(frame, encodeBack(whole.readInbound()), "fixture is not well-formed: " + frame);
                assertNull(whole.readInbound(), "fixture carries a second frame: " + frame);
            } finally {
                whole.finishAndReleaseAll();
            }
            for (int cut = 1; cut < bytes.length; cut++) {
                EmbeddedChannel fresh = new EmbeddedChannel(new ClientRespDecoder());
                try {
                    fresh.writeInbound(Unpooled.wrappedBuffer(bytes, 0, cut));
                    assertNull(fresh.readInbound(),
                            "incomplete reply must not produce output: " + frame + " cut=" + cut);
                    fresh.writeInbound(Unpooled.wrappedBuffer(bytes, cut, bytes.length - cut));
                    Object msg = fresh.readInbound();
                    assertNotNull(msg, frame + " cut at " + cut + " produced nothing");
                    assertEquals(frame, encodeBack(msg),
                            "reply must survive a split at byte " + cut);
                    assertNull(fresh.readInbound(),
                            "exactly one value per reply, cut=" + cut + " of " + frame);
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
}
