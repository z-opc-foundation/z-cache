package com.zifang.z.cache.common.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 ByteBuf 里读出一整个 RESP 值（RESP2），服务端解码器和客户端解码器共用。
 * <p>
 * 之所以要有这一个类：两边的解码器曾经是两份逐字雷同的 {@code ReplayingDecoder}
 * 子类，并且在元素循环里手工 {@code readerIndex(rewind)} —— 状态机的重放游标和
 * 手工rewind 互不认识，于是同一族缺陷在两边各长了一次，而且症状不同：
 * <ul>
 *   <li>服务端：{@code ZADD z1 1 one 2 two 3 three} 被 TCP 切一刀后，交出正确数组
 *       <b>之外</b>再吐出 7 个散装元素，请求/响应就此错位（250 实测）；</li>
 *   <li>客户端：{@code *2 foo bar} 切在第 13 字节，解出来是 {@code ["foo","foo"]} ——
 *       一声不响地把<b>错值</b>当回复交给调用方，日志里什么都没有。</li>
 * </ul>
 * 这里的规矩只有一条：<b>整帧到齐才前进游标</b>。字节不够就返回 {@link #NEED_MORE}
 * 并且一个字都不消耗，调用方等下一批字节再喂。
 *
 * @author zifang
 * @since 1.3.6
 */
public final class RespFrameReader {

    // 最大bulk字符串长度（512MB，类似Redis）
    private static final int MAX_BULK_STRING_LENGTH = 512 * 1024 * 1024;
    // 最大数组元素数量
    private static final int MAX_ARRAY_ELEMENTS = 1024 * 1024;

    /** 字节不够，这一帧还没读完；调用方要把游标退回帧首再等。 */
    public static final Object NEED_MORE = new Object();

    private RespFrameReader() {
    }

    /**
     * 从当前 reader 游标读出一个 RESP 值。
     * <p>
     * 成功时游标停在帧尾之后；返回 {@link #NEED_MORE} 时游标停在调用方交给它的位置上，
     * 未做任何前进。
     *
     * @param in 输入 ByteBuf
     * @return 解码后的 {@link RespArray}/{@link RespBulkString}/{@link RespSimpleString}/
     *         {@link RespError}/{@link RespInteger}，或 {@link #NEED_MORE}
     * @throws IllegalArgumentException 帧本身不合法（未知类型、长度写歪、bulk 体后缺 CRLF）
     */
    public static Object read(ByteBuf in) {
        int start = in.readerIndex();
        Object value = readValue(in);
        if (value == NEED_MORE) {
            in.readerIndex(start);
        }
        return value;
    }

    /**
     * 递归读一个 RESP 值。返回 {@link #NEED_MORE} 时游标可能已经前进，
     * 由 {@link #read(ByteBuf)} 统一退回帧首。
     */
    private static Object readValue(ByteBuf in) {
        int lineEnd = findLineEnd(in);
        if (lineEnd == -1) {
            return NEED_MORE;
        }
        char prefix = (char) in.getByte(in.readerIndex());
        String line = in.toString(in.readerIndex() + 1, lineEnd - in.readerIndex() - 1,
                StandardCharsets.UTF_8);
        RespType type = RespType.fromPrefix(prefix);
        if (type == null) {
            throw new IllegalArgumentException("Unknown RESP type: " + prefix);
        }

        switch (type) {
            case SIMPLE_STRING:
            case ERROR:
            case INTEGER: {
                in.readerIndex(lineEnd + 2);
                return decodeLine(type, line);
            }
            case BULK_STRING: {
                int length = parseLength(line, "bulk string");
                if (length == -1) {
                    in.readerIndex(lineEnd + 2);
                    return RespBulkString.nullBulkString();
                }
                if (length > MAX_BULK_STRING_LENGTH) {
                    throw new IllegalArgumentException("Invalid bulk string length: " + length);
                }
                int afterBody = lineEnd + 2 + length + 2;
                if (in.writerIndex() < afterBody) {
                    // 长度行已经说清楚了这帧要多少字节，所以只用"到没到"判断，
                    // 不必管 readableBytes() —— 那会把后面已经到达的字节也算进来。
                    return NEED_MORE;
                }
                if (in.getByte(afterBody - 2) != '\r' || in.getByte(afterBody - 1) != '\n') {
                    throw new IllegalArgumentException("Expected CRLF after bulk string data");
                }
                byte[] data = new byte[length];
                in.readerIndex(lineEnd + 2);
                in.readBytes(data);
                in.readerIndex(afterBody);
                return RespBulkString.of(data);
            }
            case ARRAY: {
                int length = parseLength(line, "array");
                if (length == -1) {
                    in.readerIndex(lineEnd + 2);
                    return RespArray.nullArray();
                }
                if (length > MAX_ARRAY_ELEMENTS) {
                    throw new IllegalArgumentException("Invalid array length: " + length);
                }
                if (length == 0) {
                    in.readerIndex(lineEnd + 2);
                    return RespArray.empty();
                }
                in.readerIndex(lineEnd + 2);
                List<Object> elements = new ArrayList<Object>(Math.min(length, 64));
                for (int i = 0; i < length; i++) {
                    Object element = readValue(in);
                    if (element == NEED_MORE) {
                        return NEED_MORE;
                    }
                    elements.add(element);
                }
                return RespArray.of(elements);
            }
            default:
                throw new IllegalStateException("Unexpected type: " + type);
        }
    }

    private static Object decodeLine(RespType type, String line) {
        switch (type) {
            case SIMPLE_STRING:
                return RespSimpleString.of(line);
            case ERROR:
                return RespError.of(line);
            case INTEGER:
                try {
                    return RespInteger.of(Long.parseLong(line));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid integer: " + line);
                }
            default:
                throw new IllegalStateException("Unexpected type: " + type);
        }
    }

    private static int parseLength(String line, String what) {
        try {
            int length = Integer.parseInt(line);
            if (length < -1) {
                throw new IllegalArgumentException("Invalid " + what + " length: " + line);
            }
            return length;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + what + " length: " + line);
        }
    }

    /**
     * 从当前游标起找行尾 CRLF。
     *
     * @return CRLF 的下标，未找到返回 -1
     */
    private static int findLineEnd(ByteBuf in) {
        int start = in.readerIndex();
        int end = in.writerIndex();
        for (int i = start; i + 1 < end; i++) {
            if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }
}
