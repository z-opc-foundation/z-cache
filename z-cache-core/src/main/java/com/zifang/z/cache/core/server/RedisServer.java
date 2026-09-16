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
    private final String host;
    private final int port;
    private final String password;
    private final MemoryStore store;

    // Netty components
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    // Server state
    private volatile boolean started = false;

    public RedisServer() {
        this("0.0.0.0", DEFAULT_PORT, 0);
    }

    public RedisServer(int port) {
        this("0.0.0.0", port, 0);
    }

    /**
     * 创建可配置监听地址和容量上限的服务器。
     *
     * @param host       监听地址
     * @param port       监听端口
     * @param maxEntries 最大键数量，0表示不限制
     */
    public RedisServer(String host, int port, int maxEntries) {
        this(host, port, maxEntries, null);
    }

    /**
     * 创建可配置监听地址、容量和密码的服务器。
     */
    public RedisServer(String host, int port, int maxEntries, String password) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.host = host;
        this.port = port;
        this.password = password == null || password.isEmpty() ? null : password;
        this.store = new MemoryStore(maxEntries);
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
                            CommandHandler commandHandler = new CommandHandler(store, password);
                            p.addLast("handler", new RedisServerHandler(commandHandler));
                        }
                    });

            // Bind and start to accept incoming connections
            ChannelFuture f = b.bind(host, port).sync();
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
