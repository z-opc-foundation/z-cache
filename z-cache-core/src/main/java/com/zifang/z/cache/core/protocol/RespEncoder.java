package com.zifang.z.cache.core.protocol;

import com.zifang.z.cache.common.protocol.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RESP协议编码器
 * 将Java对象编码为RESP协议字节流
 *
 * @author zifang
 * @since 1.0.0
 */
public class RespEncoder extends MessageToByteEncoder<Object> {
    private static final Logger logger = LogManager.getLogger(RespEncoder.class);

    // CRLF分隔符
    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    // 空bulk字符串表示
    private static final byte[] NULL_BULK_STRING = new byte[]{'$', '-', '1', '\r', '\n'};
    // 空数组表示
    private static final byte[] NULL_ARRAY = new byte[]{'*', '-', '1', '\r', '\n'};

    /**
     * 编码消息为RESP协议字节
     *
     * @param ctx ChannelHandlerContext
     * @param msg 待编码对象
     * @param out 输出ByteBuf
     * @throws Exception 编码异常
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        if (msg instanceof RespSimpleString) {
            encodeSimpleString((RespSimpleString) msg, out);
        } else if (msg instanceof RespError) {
            encodeError((RespError) msg, out);
        } else if (msg instanceof RespInteger) {
            encodeInteger((RespInteger) msg, out);
        } else if (msg instanceof RespBulkString) {
            encodeBulkString((RespBulkString) msg, out);
        } else if (msg instanceof RespArray) {
            encodeArray((RespArray) msg, out);
        } else if (msg instanceof String) {
            // Treat plain String as Simple String
            encodeSimpleString(RespSimpleString.of((String) msg), out);
        } else if (msg instanceof Long || msg instanceof Integer) {
            // Treat numeric types as Integer
            encodeInteger(RespInteger.of(((Number) msg).longValue()), out);
        } else if (msg instanceof byte[]) {
            // Treat byte[] as Bulk String
            encodeBulkString(RespBulkString.of((byte[]) msg), out);
        } else if (msg instanceof List) {
            // Treat List as Array (convert elements)
            List<?> list = (List<?>) msg;
            encodeArray(RespArray.of(list), out);
        } else if (msg == null) {
            // Null as null bulk string
            out.writeBytes(NULL_BULK_STRING);
        } else {
            throw new IllegalArgumentException("Cannot encode type: " + msg.getClass().getName());
        }
    }

    /**
     * 编码Simple String类型
     *
     * @param msg Simple String消息
     * @param out 输出ByteBuf
     */
    private void encodeSimpleString(RespSimpleString msg, ByteBuf out) {
        out.writeByte(RespType.SIMPLE_STRING.getPrefix());
        out.writeBytes(msg.getValue().getBytes(StandardCharsets.UTF_8));
        out.writeBytes(CRLF);
    }

    /**
     * 编码Error类型
     *
     * @param msg Error消息
     * @param out 输出ByteBuf
     */
    private void encodeError(RespError msg, ByteBuf out) {
        out.writeByte(RespType.ERROR.getPrefix());
        out.writeBytes(msg.getMessage().getBytes(StandardCharsets.UTF_8));
        out.writeBytes(CRLF);
    }

    /**
     * 编码Integer类型
     *
     * @param msg Integer消息
     * @param out 输出ByteBuf
     */
    private void encodeInteger(RespInteger msg, ByteBuf out) {
        out.writeByte(RespType.INTEGER.getPrefix());
        out.writeBytes(Long.toString(msg.getValue()).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(CRLF);
    }

    /**
     * 编码Bulk String类型
     *
     * @param msg Bulk String消息
     * @param out 输出ByteBuf
     */
    private void encodeBulkString(RespBulkString msg, ByteBuf out) {
        if (msg.isNull()) {
            out.writeBytes(NULL_BULK_STRING);
            return;
        }

        byte[] data = msg.getData();
        out.writeByte(RespType.BULK_STRING.getPrefix());
        out.writeBytes(Integer.toString(data.length).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(CRLF);
        out.writeBytes(data);
        out.writeBytes(CRLF);
    }

    /**
     * 编码Array类型
     *
     * @param msg Array消息
     * @param out 输出ByteBuf
     * @throws Exception 编码异常
     */
    private void encodeArray(RespArray msg, ByteBuf out) throws Exception {
        if (msg.isNull()) {
            out.writeBytes(NULL_ARRAY);
            return;
        }

        List<Object> elements = msg.getElements();
        out.writeByte(RespType.ARRAY.getPrefix());
        out.writeBytes(Integer.toString(elements.size()).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(CRLF);

        // Encode each element
        for (Object element : elements) {
            encode(null, element, out);
        }
    }
}
