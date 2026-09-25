package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.persistence.AofPersistence;
import com.zifang.z.cache.core.persistence.MemoryStoreAccessor;
import com.zifang.z.cache.core.persistence.RdbPersistence;
import com.zifang.z.cache.core.protocol.RespDecoder;
import com.zifang.z.cache.core.protocol.RespEncoder;
import com.zifang.z.cache.core.pubsub.PubSubManager;
import com.zifang.z.cache.core.storage.MemoryStore;
import com.zifang.z.cache.core.stream.StreamStore;
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
    /**
     * 命令处理线程组。普通命令在这里跑，必须离开 I/O 线程，
     * 否则同一 EventLoop 上其它所有连接一起被冻住。
     */
    private io.netty.channel.DefaultEventLoopGroup businessGroup;
    /**
     * 阻塞命令（BLPOP/BRPOP）专用线程组。
     * <p>
     * 和 businessGroup 分开是有原因的：阻塞命令会占满它所在的线程直到真的有值。
     * 如果它们和普通命令共用一组线程，只要并发挂起的 BLPOP 数超过线程数
     * （默认实现里是 4 条），整组的普通流量就全停了 —— 十几个客户端各发一条
     * {@code BLPOP x 0} 就能让服务器对所有正常请求失去响应。
     */
    private io.netty.channel.DefaultEventLoopGroup blockingGroup;
    private Channel serverChannel;

    // Server state
    private volatile boolean started = false;

    public RedisServer() {
        this("127.0.0.1", DEFAULT_PORT, 0);
    }

    public RedisServer(int port) {
        this("127.0.0.1", port, 0);
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
     * <p>
     * 注意锁的粒度：绑定阶段持锁，随后 {@code closeFuture().sync()} 会阻塞到服务器关闭，
     * 这段时间绝不能握着监视器 —— 否则 {@link #stop()}（进程 shutdown hook 走的那条，也是
     * 唯一会落 RDB 快照的路径）会一直等锁，优雅关闭变成死锁。
     */
    public void start() throws InterruptedException {
        synchronized (this) {
            if (started) {
                logger.warn("Server already started on port {}", port);
                return;
            }

            logger.info("Starting z-cache server on {}:{}", host, port);

            // 初始化持久化
            initPersistence();

            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
            businessGroup = new io.netty.channel.DefaultEventLoopGroup(
                    threadCount("zcache.business-threads", Math.max(4, Runtime.getRuntime().availableProcessors() * 2)));
            blockingGroup = new io.netty.channel.DefaultEventLoopGroup(
                    threadCount("zcache.blocking-threads", Math.max(16, Runtime.getRuntime().availableProcessors() * 4)));

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
                                // 整条连接钉在 businessGroup 的某一条线程上：普通命令不再
                                // 挤在承载几十个连接的 I/O EventLoop 上；阻塞命令再单独挪到
                                // blockingGroup，见该字段注释。
                                p.addLast(businessGroup, "handler", new RedisServerHandler(
                                        commandHandler, pubSubManager, store, blockingGroup));
                            }
                        });

                ChannelFuture f = b.bind(host, port).sync();
                serverChannel = f.channel();
                started = true;
            } catch (Throwable t) {
                // bind 失败（端口被占最常见）时必须把线程组收干净：
                // 之前异常从 synchronized 里逃出去，调用方只看到一个没头没尾的栈。
                started = false;
                shutdown();
                throw t;
            }
        }

        logger.info("z-cache server started successfully on {}:{}", host, port);

        try {
            serverChannel.closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    /**
     * 初始化持久化组件：加载 RDB + AOF，启动 AOF 写入。
     */
    private void initPersistence() {
        // Stream 与持久化无关，且 1.3.0 的 X* 命令依赖它。放在 dataDir 早退之前：
        // 否则不带 --data-dir 的默认启动形态下，全部 Stream 命令返回 "Stream not configured"。
        if (CommandHandler.getStreamStore() == null) {
            CommandHandler.setStreamStore(new StreamStore(16));
            logger.info("Stream store initialized");
        }

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

    /**
     * 线程组规模：允许用 {@code -Dzcache.business-threads} / {@code -Dzcache.blocking-threads}
     * 覆盖，非法值不静默生效（以前是"配了也看不出来"）。
     */
    private static int threadCount(String property, int fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= 1) {
                return value;
            }
            logger.warn("{}={} is not positive, using {}", property, raw, fallback);
        } catch (NumberFormatException e) {
            logger.warn("{}={} is not an integer, using {}", property, raw, fallback);
        }
        return fallback;
    }

    private void shutdown() {
        if (blockingGroup != null) {
            blockingGroup.shutdownGracefully();
            blockingGroup = null;
        }
        if (businessGroup != null) {
            businessGroup.shutdownGracefully();
            businessGroup = null;
        }
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
