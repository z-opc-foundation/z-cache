package com.zifang.z.cache.core.server;

import com.zifang.z.cache.common.protocol.RespError;
import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.pubsub.PubSubManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Netty handler for Redis protocol.
 * <p>
 * 处理 RESP 请求，支持 PubSub 订阅模式和事务模式。
 * </p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class RedisServerHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger logger = LogManager.getLogger(RedisServerHandler.class);

    private final CommandHandler commandHandler;

    public RedisServerHandler(CommandHandler commandHandler, PubSubManager pubSubManager) {
        this.commandHandler = commandHandler;
        this.commandHandler.setChannelContext(null); // 会在 channelActive 中设置
        if (pubSubManager != null) {
            this.commandHandler.setPubSubManager(pubSubManager);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Received: {}", msg);
        }

        // 确保 CommandHandler 持有当前 ctx
        commandHandler.setChannelContext(ctx);

        Object response = commandHandler.handle(msg);

        if (response != null) {
            if (response instanceof Object[]) {
                // PubSub 模式下可能返回 Object[] 需要包装为 RespArray
                ctx.writeAndFlush(response);
            } else {
                ctx.writeAndFlush(response);
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client connected: {}", ctx.channel().remoteAddress());
        commandHandler.setChannelContext(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
        commandHandler.onDisconnect();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Error handling request from {}", ctx.channel().remoteAddress(), cause);

        String errorMessage = cause.getMessage();
        if (errorMessage == null || errorMessage.isEmpty()) {
            errorMessage = "internal error";
        }

        ctx.writeAndFlush(RespError.of("ERR", errorMessage));

        if (cause instanceof java.io.IOException) {
            ctx.close();
        }
    }
}
