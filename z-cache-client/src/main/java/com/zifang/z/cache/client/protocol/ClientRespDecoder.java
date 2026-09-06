package com.zifang.z.cache.client.protocol;

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
 * RESP protocol decoder for client
 * Decodes server responses into Java objects
 */
public class ClientRespDecoder extends ReplayingDecoder<ClientRespDecoder.State> {
    private static final Logger logger = LogManager.getLogger(ClientRespDecoder.class);

    // Maximum bulk string size (512MB like Redis)
    private static final int MAX_BULK_STRING_LENGTH = 512 * 1024 * 1024;
    // Maximum array elements
    private static final int MAX_ARRAY_ELEMENTS = 1024 * 1024;
    private RespType currentType;
    private int remainingElements;
    private List<Object> arrayElements;

    public ClientRespDecoder() {
        super(State.DECODE_TYPE);
    }

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

    private void decodeArray(ByteBuf in, List<Object> out) throws Exception {
        if (remainingElements == 0 && arrayElements == null) {
            // First time, read the array length
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
                resetDecoder();
                return;
            }

            if (length < 0 || length > MAX_ARRAY_ELEMENTS) {
                throw new IllegalArgumentException("Invalid array length: " + length);
            }

            if (length == 0) {
                // Empty array
                out.add(RespArray.empty());
                resetDecoder();
                return;
            }

            remainingElements = length;
            arrayElements = new ArrayList<>(length);
        }

        // Decode elements
        while (remainingElements > 0) {
            // Save checkpoint before attempting to decode an element
            int readerIndexBefore = in.readerIndex();

            // Try to decode the next element
            checkpoint(State.DECODE_TYPE);
            decodeType(in, arrayElements);

            // Check if decodeType actually decoded something
            if (arrayElements.size() == 0 ||
                    (arrayElements.size() > 0 && in.readerIndex() == readerIndexBefore)) {
                // Nothing was decoded, we need more data
                in.readerIndex(readerIndexBefore);
                return;
            }

            remainingElements--;
        }

        // All elements decoded
        out.add(RespArray.of(arrayElements));
        resetDecoder();
    }

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

    private void resetDecoder() {
        checkpoint(State.DECODE_TYPE);
        currentType = null;
        remainingElements = 0;
        arrayElements = null;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Error decoding RESP", cause);
        ctx.fireExceptionCaught(cause);
    }

    enum State {
        DECODE_TYPE,
        DECODE_SIMPLE,
        DECODE_BULK,
        DECODE_ARRAY
    }
}
