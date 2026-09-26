package com.zifang.z.cache.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RespFrameReader} 的量具：一帧一个值，切在哪一刀都只交出一个值。
 * <p>
 * 夹具先过一遍 {@link #validateFrame(String)} —— 那是和解码器无关的一段纯文本走查。
 * 这一步不是洁癖：这一族用例的夹具手写过四回了，每回都有把 {@code $3} 写成 {@code $1}
 * 之类的长度错字，而"先喂给被测解码器、它肯交出东西就算夹具合法"这种验法，会把
 * 夹具错字和解码器缺陷混成同一条红色，甚至让 finally 里的 close 把真正的断言消息顶掉。
 */
class RespFrameReaderTest {

    /** 各形状的合法帧，覆盖服务端命令和客户端回复两侧。 */
    private static final String[] FRAMES = {
            // 多参数命令（250 上实测被切开后出过事的那一条）
            "*8\r\n$4\r\nZADD\r\n$2\r\nz1\r\n$1\r\n1\r\n$3\r\none\r\n$1\r\n2\r\n$3\r\ntwo\r\n$1\r\n3\r\n$5\r\nthree\r\n",
            // 成对回复（HGETALL / MGET）
            "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n",
            "*4\r\n$2\r\nk1\r\n$2\r\nv1\r\n$2\r\nk2\r\n$2\r\nv2\r\n",
            "*3\r\n$3\r\nabc\r\n$-1\r\n:7\r\n",
            "*2\r\n*2\r\n$1\r\na\r\n$1\r\nb\r\n*0\r\n",
            "$10\r\nhelloworld\r\n",
            "$0\r\n\r\n",
            "$-1\r\n",
            "+PONG\r\n",
            "+\r\n",
            "-ERR bad\r\n",
            ":42\r\n",
            ":-100\r\n",
            ":" + Long.MAX_VALUE + "\r\n",
            "*0\r\n",
            "*-1\r\n",
    };

    @Test
    void wholeFrameDecodesToExactlyOneValueAndConsumesEveryByte() {
        for (String frame : FRAMES) {
            assertExactlyOneFrame(frame);
            ByteBuf in = Unpooled.copiedBuffer(frame, StandardCharsets.UTF_8);
            try {
                Object value = RespFrameReader.read(in);
                assertNotSame(RespFrameReader.NEED_MORE, value, "frame rejected as incomplete: " + esc(frame));
                assertEquals(0, in.readableBytes(),
                        "reader left bytes unconsumed: " + esc(frame) + " left=" + esc(in.toString(StandardCharsets.UTF_8)));
                assertEquals(frame, encodeBack(value), "decoded value does not match the frame: " + esc(frame));
            } finally {
                in.release();
            }
        }
    }

    /**
     * 把每一帧在每一个字节处切一刀：前半喂进去必须什么都出不来，后半到了才交出那一个值。
     */
    @Test
    void everySplitPointYieldsOneValueAndNothingBeforeIt() {
        for (String frame : FRAMES) {
            assertExactlyOneFrame(frame);
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
            for (int cut = 1; cut < bytes.length; cut++) {                ByteBuf head = Unpooled.copiedBuffer(bytes, 0, cut);
                ByteBuf joined = Unpooled.buffer(bytes.length);
                joined.writeBytes(bytes, 0, cut);
                joined.writeBytes(bytes, cut, bytes.length - cut);
                try {
                    Object partial = RespFrameReader.read(head);
                    assertSame(RespFrameReader.NEED_MORE, partial,
                            "half a frame must not decode: " + esc(frame) + " cut=" + cut);
                    assertEquals(0, head.readerIndex(),
                            "an incomplete frame must not consume anything, cut=" + cut);

                    Object value = RespFrameReader.read(joined);
                    assertNotSame(RespFrameReader.NEED_MORE, value,
                            "completed frame still incomplete: " + esc(frame) + " cut=" + cut);
                    assertEquals(frame, encodeBack(value),
                            "frame must survive a split at byte " + cut + " of " + esc(frame));
                    assertEquals(0, joined.readableBytes(), "no spare bytes after the value, cut=" + cut);
                } finally {
                    head.release();
                    joined.release();
                }
            }
        }
    }

    /** 两条命令挤在同一个 read 里：一次 {@link RespFrameReader#read} 只吃掉第一条。 */
    @Test
    void readStopsAtTheFrameBoundaryAndLeavesTheNextOne() {
        String first = "*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n";
        String second = ":7\r\n";
        ByteBuf in = Unpooled.copiedBuffer(first + second, StandardCharsets.UTF_8);
        try {
            assertEquals(first, encodeBack(RespFrameReader.read(in)));
            assertEquals(second.length(), in.readableBytes());
            assertEquals(second, encodeBack(RespFrameReader.read(in)));
            assertEquals(0, in.readableBytes());
            assertSame(RespFrameReader.NEED_MORE, RespFrameReader.read(in));
        } finally {
            in.release();
        }
    }

    // ==================== 坏帧必须当场炸，不能静悄悄交出半条 ====================

    /**
     * 结构本身写歪的帧必须当场炸，不能静悄悄交出半条。
     * <p>
     * 注意和 {@link #incompleteFramesNeverTouchTheCursor} 的分界：长度行说好了 5 字节而只到了
     * 2 字节，那是<b>还没到</b>，要等；5 字节到了但后面不是 CRLF，才是<b>坏</b>，要炸。
     * 把前者也当成坏帧报错，等于在慢链路上自造协议错误。
     */
    @Test
    void malformedFramesThrowInsteadOfReturningHalfAValue() {
        String[] bad = {
                "@unknown\r\n",
                ":not-a-number\r\n",
                "*not-a-number\r\n",
                "$not-a-number\r\n",
                "$999999999999\r\n",            // 长度大到 int 都装不下
                "*-2\r\n",
                "$-2\r\n",
                "$3\r\nabcde\r\n",              // 声明的字节数到了，可后面不是 CRLF
                "*1\r\n@x\r\n",                 // 元素类型不认识
        };
        for (String frame : bad) {
            ByteBuf in = Unpooled.copiedBuffer(frame, StandardCharsets.UTF_8);
            try {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                        () -> RespFrameReader.read(in), "bad frame must throw: " + esc(frame));
                assertTrue(ex.getMessage() != null && !ex.getMessage().isEmpty(),
                        "throw must carry a reason: " + esc(frame));
            } finally {
                in.release();
            }
        }
    }

    /**
     * 夹具的合法性由这段独立走查来量，而这段走查自己也得有牙：把长度位写歪它必须咬。
     * 这一族夹具手写过四回，每回都有 {@code $3} 误写成 {@code $1} 这种错字，
     * 只靠"喂给被测解码器看它肯不肯交出东西"来自验，会把夹具错字和解码器缺陷混成一条红。
     */
    @Test
    void theIndependentWalkRejectsTheTyposItIsThereToCatch() {
        for (String frame : FRAMES) {
            assertExactlyOneFrame(frame);
        }
        assertThrows(AssertionError.class, () -> validateFrame("*2\r\n$1\r\nfoo\r\n$3\r\nbar\r\n", 0),
                "length typo on an element must be caught");
        assertThrows(AssertionError.class, () -> validateFrame("$5\r\nhi\r\n", 0),
                "body shorter than the declared length must be caught");
        assertThrows(AssertionError.class, () -> validateFrame("*2\r\n$3\r\nabc\r\n", 0),
                "array missing its second element must be caught");
        assertThrows(AssertionError.class, () -> validateFrame("+OK", 0), "missing CRLF must be caught");
        assertThrows(AssertionError.class, () -> assertExactlyOneFrame("*1\r\n$1\r\na\r\n:9\r\n"),
                "trailing spare bytes must be caught");
        assertThrows(AssertionError.class, () -> validateFrame("?9\r\n", 0), "unknown prefix must be caught");
    }

    /** 一帧不多不少：走查吃掉的字符数必须正好等于文本长度。 */
    private static void assertExactlyOneFrame(String text) {
        assertEquals(text.length(), validateFrame(text, 0), "not exactly one well-formed frame: " + esc(text));
    }

    /**
     * 还没到齐的帧一律 NEED_MORE，且不吃游标 —— 这条是"等下一批字节"能成立的前提。
     * <p>
     * 这里的 {@code $5\r\nhi\r\n} 在 {@link #validateFrame} 那把尺下算坏帧，在这把尺下算
     * 没到齐 —— 两边不矛盾：走查问的是"这段文本是不是完整的一帧"，解码器问的是"这一批字节
     * 够不够我交出一个值"。把后者也当成坏帧来报错，等于慢链路上自造协议错误。
     */
    @Test
    void incompleteFramesNeverTouchTheCursor() {
        String[] partial = {"$", "$5", "$5\r", "$5\r\n", "$5\r\nhel", "$5\r\nhello",
                "$5\r\nhi\r\n", "*2\r\n", "*2\r\n$3\r\nabc\r\n", "+OK", "+OK\r", ":",
                "*2\r\n$", "*2\r\n$3\r\nab", "*-", "*2\r\n$3\r\nabc\r\n:7\r"};
        for (String frame : partial) {
            ByteBuf in = Unpooled.copiedBuffer(frame, StandardCharsets.UTF_8);
            try {
                assertSame(RespFrameReader.NEED_MORE, RespFrameReader.read(in),
                        "should still be waiting: " + esc(frame));
                assertEquals(0, in.readerIndex(), "cursor must not advance on an incomplete frame: " + esc(frame));
            } finally {
                in.release();
            }
        }
    }

    // ==================== 夹具自身的合法性，由这段独立走查来量 ====================

    /**
     * 纯文本走查一遍一帧 RESP，返回它吃掉多少字符。
     *
     * @throws AssertionError 帧本身写歪了（长度不符、缺 CRLF、类型不认识、帧尾有残渣）
     */
    private static int validateFrame(String text, int pos) {
        if (pos >= text.length()) {
            throw new AssertionError("frame truncated at " + pos + " of " + esc(text));
        }
        int crlf = text.indexOf("\r\n", pos);
        if (crlf == -1) {
            throw new AssertionError("no CRLF after the header at " + pos + " of " + esc(text));
        }
        String head = text.substring(pos + 1, crlf);
        char prefix = text.charAt(pos);
        switch (prefix) {
            case '+':
            case '-':
                return crlf + 2;
            case ':':
                try {
                    Long.parseLong(head);
                } catch (NumberFormatException e) {
                    throw new AssertionError("integer header is not a number: [" + head + "] in " + esc(text));
                }
                return crlf + 2;
            case '$': {
                int length = parseSize(head, text);
                if (length < 0) {
                    return crlf + 2; // null bulk
                }
                int bodyStart = crlf + 2;
                int bodyEnd = bodyStart + length;
                if (bodyEnd + 2 > text.length()) {
                    throw new AssertionError("bulk of " + length + " bytes is not all there in " + esc(text));
                }
                if (!"\r\n".equals(text.substring(bodyEnd, bodyEnd + 2))) {
                    throw new AssertionError("bulk of " + length + " bytes is not CRLF-terminated in " + esc(text));
                }
                return bodyEnd + 2;
            }
            case '*': {
                int count = parseSize(head, text);
                if (count < 0) {
                    return crlf + 2; // null array
                }
                int at = crlf + 2;
                for (int i = 0; i < count; i++) {
                    at = validateFrame(text, at);
                }
                return at;
            }
            default:
                throw new AssertionError("unknown RESP prefix '" + prefix + "' in " + esc(text));
        }
    }

    private static int parseSize(String head, String text) {
        try {
            int value = Integer.parseInt(head);
            if (value < -1) {
                throw new AssertionError("negative size " + value + " in " + esc(text));
            }
            return value;
        } catch (NumberFormatException e) {
            throw new AssertionError("size header is not a number: [" + head + "] in " + esc(text));
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
            StringBuilder sb = new StringBuilder("*").append(array.getElements() == null ? 0 : array.size())
                    .append("\r\n");
            List<Object> elements = array.getElements();
            if (elements != null) {
                for (Object element : elements) {
                    sb.append(encodeBack(element));
                }
            }
            return sb.toString();
        }
        return "<?>";
    }

    private static String esc(String s) {
        return s == null ? "null" : s.replace("\r", "\\r").replace("\n", "\\n");
    }
}
