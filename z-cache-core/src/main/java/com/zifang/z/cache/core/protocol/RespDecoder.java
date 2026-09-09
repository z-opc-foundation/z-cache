package com.zifang.z.cache.core.protocol;

import com.zifang.z.cache.common.protocol.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP协议解码器
 * 将RESP协议字节流解码为Java对象
 *
 * @author zifang
 * @since 1.0.0
 */
public class RespDecoder extends ReplayingDecoder<RespDecoder.State> {
    private static final Logger logger = LogManager.getLogger(RespDecoder.class);

    // 最大bulk字符串长度（512MB，类似Redis）
    private static final int MAX_BULK_STRING_LENGTH = 512 * 1024 * 1024;
    // 最大数组元素数量
    private static final int MAX_ARRAY_ELEMENTS = 1024 * 1024;
    
    /**
     * 当前解码的RESP类型
     */
    private RespType currentType;
    
    /**
     * 剩余数组元素数量
     */
    private int remainingElements;
    
    /**
     * 数组元素列表
     */
    private List<Object> arrayElements;

    /**
     * 构造函数，初始化解码器状态
     */
    public RespDecoder() {
        super(State.DECODE_TYPE);
    }

    /**
     * 解码方法，根据当前状态处理字节数据
     *
     * @param ctx ChannelHandlerContext
     * @param in  输入ByteBuf
     * @param out 输出对象列表
     * @throws Exception 解码异常
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        switch (state()) {
            case DECODE_TYPE:
                decodeType(in, out);
                break;
            case DECODE_SIMPLE:
                decodeSimple(in, out);
                break;
            case DECODE_BULK:
                decodeBulk(in, out);
                break;
            case DECODE_ARRAY:
                decodeArray(in, out);
                break;
        }
    }

    /**
     * 解码RESP类型字节
     *
     * @param in  输入ByteBuf
     * @param out 输出对象列表
     * @throws Exception 解码异常
     */
    private void decodeType(ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        byte typeByte = in.readByte();
        currentType = RespType.fromPrefix((char) typeByte);

        if (currentType == null) {
            throw new IllegalArgumentException("Unknown RESP type: " + (char) typeByte);
        }

        switch (currentType) {
            case SIMPLE_STRING:
            case ERROR:
            case INTEGER:
                checkpoint(State.DECODE_SIMPLE);
                decodeSimple(in, out);
                break;
            case BULK_STRING:
                checkpoint(State.DECODE_BULK);
                decodeBulk(in, out);
                break;
            case ARRAY:
                checkpoint(State.DECODE_ARRAY);
                decodeArray(in, out);
                break;
        }
    }

    /**
     * 解码Simple String、Error或Integer类型
     *
     * @param in  输入ByteBuf
     * @param out 输出对象列表
     * @throws Exception 解码异常
     */
    private void decodeSimple(ByteBuf in, List<Object> out) throws Exception {
        String line = readLine(in);
        if (line == null) {
            return;
        }

        Object result;
        switch (currentType) {
            case SIMPLE_STRING:
                result = RespSimpleString.of(line);
                break;
            case ERROR:
                result = RespError.of(line);
                break;
            case INTEGER:
                try {
                    long value = Long.parseLong(line);
                    result = RespInteger.of(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid integer: " + line);
                }
                break;
            default:
                throw new IllegalStateException("Unexpected type: " + currentType);
        }

        out.add(result);
        resetDecoder();
    }

    /**
     * 解码Bulk String类型
     *
     * @param in  输入ByteBuf
     * @param out 输出对象列表
     * @throws Exception 解码异常
     */
    private void decodeBulk(ByteBuf in, List<Object> out) throws Exception {
        // Read the length line
        String line = readLine(in);
        if (line == null) {
            return;
        }

        int length;
        try {
            length = Integer.parseInt(line);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid bulk string length: " + line);
        }

        if (length == -1) {
            // Null bulk string
            out.add(RespBulkString.nullBulkString());
            resetDecoder();
            return;
        }

        if (length < 0 || length > MAX_BULK_STRING_LENGTH) {
            throw new IllegalArgumentException("Invalid bulk string length: " + length);
        }

        // Read the actual data
        if (in.readableBytes() < length + 2) {
            // Not enough data, reset reader index and wait for more
            in.readerIndex(in.readerIndex() - line.length() - 2); // Go back before the length line
            return;
        }

        byte[] data = new byte[length];
        in.readBytes(data);

        // Read CRLF
        byte cr = in.readByte();
        byte lf = in.readByte();
        if (cr != '\r' || lf != '\n') {
            throw new IllegalArgumentException("Expected CRLF after bulk string data");
        }

        out.add(RespBulkString.of(data));
        resetDecoder();
    }

    /**
     * 解码Array类型
     *
     * @param in  输入ByteBuf
     * @param out 输出对象列表
     * @throws Exception 解码异常
     */
    private void decodeArray(ByteBuf in, List<Object> out) throws Exception {
        if (arrayElements == null) {
            // First time entry for this array (or recovering after partial read)
            String line = readLine(in);
            if (line == null) {
                return;
            }

            int length;
            try {
                length = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid array length: " + line);
            }

            if (length == -1) {
                // Null array
                out.add(RespArray.nullArray());
                checkpoint(State.DECODE_TYPE);
                return;
            }

            if (length < 0 || length > MAX_ARRAY_ELEMENTS) {
                throw new IllegalArgumentException("Invalid array length: " + length);
            }

            if (length == 0) {
                // Empty array
                out.add(RespArray.empty());
                checkpoint(State.DECODE_TYPE);
                return;
            }

            remainingElements = length;
            arrayElements = new ArrayList<>(length);
            checkpoint(State.DECODE_ARRAY);
        }

        // Decode each remaining element by reading its type byte and dispatching
        // directly. We deliberately do not recurse into decodeType() here, because
        // an inner call would invoke resetDecoder() on completion and clobber the
        // outer array's state (NPE observed on testDecodeArray).
        while (remainingElements > 0) {
            int readerIndexBefore = in.readerIndex();

            byte typeByte = in.readByte();
            RespType elemType = RespType.fromPrefix((char) typeByte);
            if (elemType == null) {
                throw new IllegalArgumentException("Unknown RESP type: " + (char) typeByte);
            }

            switch (elemType) {
                case SIMPLE_STRING: {
                    String valueLine = readLine(in);
                    if (valueLine == null) {
                        in.readerIndex(readerIndexBefore);
                        return;
                    }
                    arrayElements.add(RespSimpleString.of(valueLine));
                    break;
                }
                case ERROR: {
                    String errLine = readLine(in);
                    if (errLine == null) {
                        in.readerIndex(readerIndexBefore);
                        return;
                    }
                    arrayElements.add(RespError.of(errLine));
                    break;
                }
                case INTEGER: {
                    String intLine = readLine(in);
                    if (intLine == null) {
                        in.readerIndex(readerIndexBefore);
                        return;
                    }
                    try {
                        arrayElements.add(RespInteger.of(Long.parseLong(intLine)));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid integer in array: " + intLine);
                    }
                    break;
                }
                case BULK_STRING: {
                    Boolean ok = decodeBulkIntoList(in, arrayElements, readerIndexBefore);
                    if (ok == null) {
                        return;
                    }
                    break;
                }
                case ARRAY: {
                    RespArray nested = decodeNestedArray(in, readerIndexBefore);
                    if (nested == null) {
                        return;
                    }
                    arrayElements.add(nested);
                    break;
                }
                default:
                    throw new IllegalStateException("Unexpected type in array: " + elemType);
            }

            remainingElements--;
        }

        // All elements decoded
        out.add(RespArray.of(arrayElements));
        arrayElements = null;
        remainingElements = 0;
        checkpoint(State.DECODE_TYPE);
    }

    /**
     * 辅助方法：解码单个bulk-string元素并添加到列表
     * 在decodeArray中使用
     *
     * @param in             输入ByteBuf
     * @param sink           目标列表
     * @param readerIndexBefore 读取前的reader index
     * @return Boolean.TRUE表示成功，null表示需要更多数据
     * @throws Exception 解码异常
     */
    private Boolean decodeBulkIntoList(ByteBuf in, List<Object> sink, int readerIndexBefore) throws Exception {
        String lenLine = readLine(in);
        if (lenLine == null) {
            in.readerIndex(readerIndexBefore);
            return null;
        }
        int length;
        try {
            length = Integer.parseInt(lenLine);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid bulk string length: " + lenLine);
        }
        if (length == -1) {
            sink.add(RespBulkString.nullBulkString());
            return Boolean.TRUE;
        }
        if (length < 0 || length > MAX_BULK_STRING_LENGTH) {
            throw new IllegalArgumentException("Invalid bulk string length: " + length);
        }
        if (in.readableBytes() < length + 2) {
            in.readerIndex(readerIndexBefore);
            return null;
        }
        byte[] data = new byte[length];
        in.readBytes(data);
        byte cr = in.readByte();
        byte lf = in.readByte();
        if (cr != '\r' || lf != '\n') {
            throw new IllegalArgumentException("Expected CRLF after bulk string data");
        }
        sink.add(RespBulkString.of(data));
        return Boolean.TRUE;
    }

    /**
     * 辅助方法：解码嵌套的RESP数组
     * 在decodeArray中使用
     *
     * @param in             输入ByteBuf
     * @param readerIndexBefore 读取前的reader index
     * @return 解码后的数组，null表示需要更多数据
     * @throws Exception 解码异常
     */
    private RespArray decodeNestedArray(ByteBuf in, int readerIndexBefore) throws Exception {
        String lenLine = readLine(in);
        if (lenLine == null) {
            in.readerIndex(readerIndexBefore);
            return null;
        }
        int length;
        try {
            length = Integer.parseInt(lenLine);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid nested array length: " + lenLine);
        }
        if (length == -1) {
            return RespArray.nullArray();
        }
        if (length < 0 || length > MAX_ARRAY_ELEMENTS) {
            throw new IllegalArgumentException("Invalid nested array length: " + length);
        }
        if (length == 0) {
            return RespArray.empty();
        }
        List<Object> nested = new ArrayList<>(length);
        int nestedBefore = in.readerIndex();
        for (int i = 0; i < length; i++) {
            int before = in.readerIndex();
            byte typeByte = in.readByte();
            RespType elemType = RespType.fromPrefix((char) typeByte);
            if (elemType == null) {
                throw new IllegalArgumentException("Unknown RESP type: " + (char) typeByte);
            }
            switch (elemType) {
                case SIMPLE_STRING: {
                    String line = readLine(in);
                    if (line == null) {
                        in.readerIndex(nestedBefore);
                        return null;
                    }
                    nested.add(RespSimpleString.of(line));
                    break;
                }
                case INTEGER: {
                    String line = readLine(in);
                    if (line == null) {
                        in.readerIndex(nestedBefore);
                        return null;
                    }
                    nested.add(RespInteger.of(Long.parseLong(line)));
                    break;
                }
                case BULK_STRING: {
                    Boolean ok = decodeBulkIntoList(in, nested, before);
                    if (ok == null) {
                        in.readerIndex(nestedBefore);
                        return null;
                    }
                    break;
                }
                default:
                    in.readerIndex(before);
                    throw new UnsupportedOperationException(
                            "Nested array element type not yet supported: " + elemType);
            }
        }
        return RespArray.of(nested);
    }

    /**
     * 从ByteBuf中读取一行（以CRLF结尾）
     *
     * @param in 输入ByteBuf
     * @return 读取的行内容，null表示数据不足
     */
    private String readLine(ByteBuf in) {
        int lineEnd = findLineEnd(in);
        if (lineEnd == -1) {
            return null;
        }

        int lineStart = in.readerIndex();
        int lineLength = lineEnd - lineStart;

        String line = in.toString(lineStart, lineLength, StandardCharsets.UTF_8);
        in.readerIndex(lineEnd + 2); // Skip CRLF

        return line;
    }

    /**
     * 查找CRLF（\r\n）在ByteBuf中的位置
     *
     * @param in 输入ByteBuf
     * @return CRLF起始位置，未找到返回-1
     */
    private int findLineEnd(ByteBuf in) {
        int readable = in.readableBytes();
        for (int i = 0; i < readable - 1; i++) {
            if (in.getByte(in.readerIndex() + i) == '\r'
                    && in.getByte(in.readerIndex() + i + 1) == '\n') {
                return in.readerIndex() + i;
            }
        }
        return -1;
    }

    /**
     * 重置解码器状态
     */
    private void resetDecoder() {
        checkpoint(State.DECODE_TYPE);
        currentType = null;
        remainingElements = 0;
        arrayElements = null;
    }

    /**
     * 异常处理
     *
     * @param ctx ChannelHandlerContext
     * @param cause 异常原因
     * @throws Exception 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Error decoding RESP", cause);
        ctx.fireExceptionCaught(cause);
    }

    /**
     * 解码器状态枚举
     */
    enum State {
        /**
         * 解码类型字节
         */
        DECODE_TYPE,
        
        /**
         * 解码Simple String/Error/Integer
         */
        DECODE_SIMPLE,
        
        /**
         * 解码Bulk String
         */
        DECODE_BULK,
        
        /**
         * 解码Array
         */
        DECODE_ARRAY
    }
}
