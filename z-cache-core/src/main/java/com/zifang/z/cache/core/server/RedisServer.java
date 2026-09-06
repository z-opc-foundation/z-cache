package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.protocol.RespDecoder;
import com.zifang.z.cache.core.protocol.RespEncoder;
import com.zifang.z.cache.core.storage.MemoryStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * z-cache Redis-compatible server
 * Netty-based TCP server implementing Redis protocol
 */
public class RedisServer {
    // Default port - same as Redis
    public static final int DEFAULT_PORT = 6379;
    private static final Logger logger = LogManager.getLogger(RedisServer.class);
    private final int port;
    private final MemoryStore store;

    // Netty components
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    // Server state
    private volatile boolean started = false;

    public RedisServer() {
        this(DEFAULT_PORT);
    }

    public RedisServer(int port) {
        this.port = port;
        this.store = new MemoryStore();
    }

    /**
     * Start the server
     */
    public synchronized void start() throws InterruptedException {
        if (started) {
            logger.warn("Server already started on port {}", port);
            return;
        }

        logger.info("Starting z-cache server on port {}", port);

        // Create event loop groups
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();

                            // Add RESP protocol codec
                            p.addLast("decoder", new RespDecoder());
                            p.addLast("encoder", new RespEncoder());

                            // Add command handler
                            CommandHandler commandHandler = new CommandHandler(store);
                            p.addLast("handler", new RedisServerHandler(commandHandler));
                        }
                    });

            // Bind and start to accept incoming connections
            ChannelFuture f = b.bind(port).sync();
            serverChannel = f.channel();
            started = true;

            logger.info("z-cache server started successfully on port {}", port);

            // Wait until the server socket is closed
            f.channel().closeFuture().sync();

        } finally {
            // Shutdown gracefully
            shutdown();
        }
    }

    /**
     * Stop the server
     */
    public synchronized void stop() {
        if (!started) {
            logger.warn("Server is not running");
            return;
        }

        logger.info("Stopping z-cache server...");

        if (serverChannel != null) {
            serverChannel.close();
        }

        shutdown();
        started = false;
        logger.info("z-cache server stopped");
    }

    private void shutdown() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    /**
     * Check if server is running
     */
    public boolean isRunning() {
        return started;
    }

    /**
     * Get server port
     */
    public int getPort() {
        return port;
    }

    /**
     * Get memory store (for testing/debugging)
     */
    public MemoryStore getStore() {
        return store;
    }
}
