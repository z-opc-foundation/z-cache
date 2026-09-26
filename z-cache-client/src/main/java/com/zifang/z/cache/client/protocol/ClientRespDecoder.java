package com.zifang.z.cache.client.protocol;

import com.zifang.z.cache.common.protocol.RespFrameReader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * RESP protocol decoder for client
 * Decodes server responses into Java objects
 * <p>
 * 和 {@code RespDecoder}（服务端）共用 {@link RespFrameReader}：整帧到齐才前进游标。
 * 以前这里是另一份 ReplayingDecoder + 手工 rewind 的拷贝，切在一帧的中段时会把
 * <b>错值</b>交给调用方而不报任何错（实测 {@code *2 foo bar} 切在第 13 字节，
 * 解出来是 {@code ["foo","foo"]}）—— 缓存里就此静悄悄地躺着一条读歪的值。
 */
public class ClientRespDecoder extends ByteToMessageDecoder {
    private static final Logger logger = LogManager.getLogger(ClientRespDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object value = RespFrameReader.read(in);
        if (value == RespFrameReader.NEED_MORE) {
            return;
        }
        out.add(value);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Error decoding RESP", cause);
        ctx.fireExceptionCaught(cause);
    }
}
