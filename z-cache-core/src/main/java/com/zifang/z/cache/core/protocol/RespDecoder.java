package com.zifang.z.cache.core.protocol;

import com.zifang.z.cache.common.protocol.RespFrameReader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * RESP协议解码器
 * 将RESP协议字节流解码为Java对象
 * <p>
 * 一帧一个命令：字节不够就整帧退回等待，绝不先把已读到的部分交出去。以前它继承
 * ReplayingDecoder 又在元素循环里手工 {@code readerIndex(rewind)}，两种游标互相
 * 不认识，于是被 TCP 切开的多参数命令会在正确命令之后<b>再吐出若干散装元素</b>
 * （250 上实测：{@code ZADD z1 1 one 2 two 3 three} 回 {@code :3} 之后紧跟 7 条
 * {@code ERR Protocol error: expected array}，且 z1 里多出一个成员 "ZADD"）。
 * <p>
 * 读帧本身在 {@link RespFrameReader} 里，和客户端解码器共用一份 —— 两边曾经各有一份
 * 同样的代码，也就各自长出了同样的毛病。
 *
 * @author zifang
 * @since 1.0.0
 */
public class RespDecoder extends ByteToMessageDecoder {
    private static final Logger logger = LogManager.getLogger(RespDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object value = RespFrameReader.read(in);
        if (value == RespFrameReader.NEED_MORE) {
            return;
        }
        out.add(value);
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
}
