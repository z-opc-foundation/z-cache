package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.persistence.AofPersistence;
import com.zifang.z.cache.core.persistence.MemoryStoreAccessor;
import com.zifang.z.cache.core.persistence.RdbPersistence;
import com.zifang.z.cache.core.protocol.RespDecoder;
import com.zifang.z.cache.core.protocol.RespEncoder;
import com.zifang.z.cache.core.pubsub.PubSubManager;
import com.zifang.z.cache.core.storage.MemoryStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * z-cache Redis-compatible server.
 * <p>
 * 基于 Netty 的 TCP 服务器，实现 Redis RESP2 协议。
 * 支持 String/Hash/List/Set/SortedSet 五种数据结构，
 * 以及事务、发布订阅、多数据库、持久化等功能。
 * </p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class RedisServer {

    public static final int DEFAULT_PORT = 6379;
    private static final Logger logger = LogManager.getLogger(RedisServer.class);

    private final String host;
    private final int port;
    private final String password;
    private final MemoryStore store;

    // PubSub 管理器
    private final PubSubManager pubSubManager = new PubSubManager();

    // 持久化管理器
    private RdbPersistence rdbPersistence;
    private AofPersistence aofPersistence;

    // 数据目录
    private String dataDir;

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

    public RedisServer(String host, int port, int maxEntries) {
        this(host, port, maxEntries, null);
    }

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
     * 设置数据目录，启用持久化功能。
     *
     * @param dataDir 数据文件存放目录
     */
    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * 启动服务器。
     */
    public synchronized void start() throws InterruptedException {
        if (started) {
            logger.warn("Server already started on port {}", port);
            return;
        }

        logger.info("Starting z-cache server on {}:{}", host, port);

        // 初始化持久化
        initPersistence();

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
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("decoder", new RespDecoder());
                            p.addLast("encoder", new RespEncoder());
                            CommandHandler commandHandler = new CommandHandler(store, password);
                            p.addLast("handler", new RedisServerHandler(commandHandler, pubSubManager));
                        }
                    });

            ChannelFuture f = b.bind(host, port).sync();
            serverChannel = f.channel();
            started = true;

            logger.info("z-cache server started successfully on {}:{}", host, port);
            store.incrementConnections();

            f.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    /**
     * 初始化持久化组件：加载 RDB + AOF，启动 AOF 写入。
     */
    private void initPersistence() {
        if (dataDir == null || dataDir.isEmpty()) {
            logger.info("No data directory configured, persistence disabled");
            return;
        }

        java.io.File dir = new java.io.File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 初始化 RDB 持久化
        rdbPersistence = new RdbPersistence();
        rdbPersistence.setStoreAccessor(new MemoryStoreAccessor(store));
        String rdbPath = dataDir + "/dump.rdb";
        try {
            rdbPersistence.load(rdbPath);
            logger.info("RDB data loaded from {}", rdbPath);
        } catch (Exception e) {
            logger.warn("Failed to load RDB: {}", e.getMessage());
        }

        // 初始化 AOF 持久化
        aofPersistence = new AofPersistence();
        String aofPath = dataDir + "/appendonly.aof";
        try {
            aofPersistence.start(aofPath);
            CommandHandler.setAofPersistence(aofPersistence);
            logger.info("AOF persistence started: {}", aofPath);
        } catch (Exception e) {
            logger.warn("Failed to start AOF: {}", e.getMessage());
        }
    }

    /**
     * 停止服务器。
     */
    public synchronized void stop() {
        if (!started) {
            logger.warn("Server is not running");
            return;
        }
        logger.info("Stopping z-cache server...");

        // 保存 RDB 快照
        saveRdb();

        // 关闭 AOF
        closeAof();

        if (serverChannel != null) {
            serverChannel.close();
        }
        shutdown();
        started = false;
        logger.info("z-cache server stopped");
    }

    /**
     * 保存 RDB 快照。
     */
    private void saveRdb() {
        if (rdbPersistence != null) {
            try {
                rdbPersistence.save();
                logger.info("RDB snapshot saved");
            } catch (Exception e) {
                logger.error("Failed to save RDB: {}", e.getMessage(), e);
            }
            rdbPersistence.shutdown();
        }
    }

    /**
     * 关闭 AOF 持久化。
     */
    private void closeAof() {
        if (aofPersistence != null) {
            try {
                aofPersistence.stop();
                logger.info("AOF persistence stopped");
            } catch (Exception e) {
                logger.error("Failed to stop AOF: {}", e.getMessage(), e);
            }
        }
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

    public boolean isRunning() { return started; }
    public int getPort() { return port; }
    public MemoryStore getStore() { return store; }
    public PubSubManager getPubSubManager() { return pubSubManager; }
    public RdbPersistence getRdbPersistence() { return rdbPersistence; }
    public AofPersistence getAofPersistence() { return aofPersistence; }
}
