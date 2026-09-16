package com.zifang.z.cache.core.server;

import com.zifang.z.cache.common.protocol.RespError;
import com.zifang.z.cache.core.command.CommandHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Netty handler for Redis protocol
 * Processes RESP requests and sends responses
 */
public class RedisServerHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger logger = LogManager.getLogger(RedisServerHandler.class);

    private final CommandHandler commandHandler;

    public RedisServerHandler(CommandHandler commandHandler) {
        this.commandHandler = commandHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Received: {}", msg);
        }

        // Handle the request through command handler
        Object response = commandHandler.handle(msg);

        // Send response back to client
        if (response != null) {
            ctx.writeAndFlush(response);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client connected: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Error handling request from {}", ctx.channel().remoteAddress(), cause);

        // Send error response to client
        String errorMessage = cause.getMessage();
        if (errorMessage == null || errorMessage.isEmpty()) {
            errorMessage = "internal error";
        }

        ctx.writeAndFlush(RespError.of("ERR", errorMessage));

        // Don't close the connection for recoverable errors
        if (cause instanceof java.io.IOException) {
            ctx.close();
        }
    }
}
