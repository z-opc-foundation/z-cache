package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.logging.SlowLog;
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

    /**
     * 这台服务器自己的一份共享状态：pub/sub 管理器、连接登记表、MONITOR 集合、StreamStore、
     * SlowLog，以及启动后才挂上来的 RDB/AOF。
     * <p>它们曾经都是 {@code CommandHandler} 的静态字段，于是"这台服务器上的状态"其实是
     * "这个 JVM 里最后写过一次的那个值"：CLIENT LIST 列出别台服务器的客户端、CLIENT KILL
     * 踢掉别人的连接、A 写的 Stream 在 B 上读得到，而不带 dataDir 的 B 一启动就把 A 的
     * 持久化整个置空（A 继续收写入但 SAVE 已经不能用了）。两台服务器的对照实测在
     * {@code RedisServerProtocolSemanticsTest}。
     */
    private final com.zifang.z.cache.core.command.ServerScope scope;

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
        // SlowLog 与 StreamStore 是一台服务器一份，不是进程一份：
        // SLOWLOG RESET 只能清自己这台账，XADD 写的流也不能被别台服务器读到。
        SlowLog scopeSlowLog = new SlowLog();
        int scopeSlowlogMs = intProperty("zcache.slowlog-log-slower-than", 10, 0);
        scopeSlowLog.setSlowLogThresholdNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(scopeSlowlogMs));
        this.scope = new com.zifang.z.cache.core.command.ServerScope(
                new StreamStore(16), scopeSlowLog);
        logger.info("Slow log initialized (threshold: {} ms)", scopeSlowlogMs);
    }

    /** 这台服务器的共享状态；连接、Stream、慢查询账都到这一台为止。 */
    public com.zifang.z.cache.core.command.ServerScope serverScope() {
        return scope;
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
                    intProperty("zcache.business-threads", Math.max(4, Runtime.getRuntime().availableProcessors() * 2), 1));
            blockingGroup = new io.netty.channel.DefaultEventLoopGroup(
                    intProperty("zcache.blocking-threads", Math.max(16, Runtime.getRuntime().availableProcessors() * 4), 1));

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
                                        commandHandler, scope, store, blockingGroup));
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
     * <p>挂在 {@link #scope} 上而不是 {@code CommandHandler} 的静态字段上：这条方法在
     * {@code bind()} 之前跑完，所以每条连接的 handler 建出来时看到的就是终值，
     * 而另一台服务器的 {@code initPersistence()} 碰不到这一台的。
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
        // load() 只吃路径参数、不记住它；不显式 setDbFilePath 的话 save() 会写到
        // 默认相对路径 ./dump.rdb（进程 cwd），也就是 --data-dir 之外的另一个文件，
        // 于是 SAVE 与停机快照全都落在一个没人再读回的地方。
        rdbPersistence.setDbFilePath(rdbPath);
        scope.setRdbPersistence(rdbPersistence);

        String aofPath = dataDir + "/appendonly.aof";
        aofPersistence = new AofPersistence();
        // fsync 策略必须在 start() 之前定：start() 按当时的策略决定要不要起每秒 fsync 的调度。
        // 配错不静默采纳（原来是"写什么都是 EVERYSEC"）。
        String fsync = System.getProperty("zcache.appendfsync");
        if (fsync != null && !fsync.trim().isEmpty()) {
            try {
                aofPersistence.setFsyncPolicy(AofPersistence.parseFsyncPolicy(fsync.trim()));
            } catch (IllegalArgumentException e) {
                logger.warn("zcache.appendfsync={} is not one of always/everysec/no, keeping {}",
                        fsync, AofPersistence.getFsyncPolicyName(aofPersistence.getFsyncPolicy()));
            }
        }
        boolean aofPresent = new java.io.File(aofPath).length() > 0;

        // 恢复顺序与 Redis 一致：有 AOF 就只认 AOF，不再叠 RDB。两份都读等于把快照里
        // 已经反映过的写命令再演一遍 —— SET 幂等看不出差别，LPUSH 会让列表原地翻倍。
        // 重放失败也不回头读 RDB：那时内存里已经落了一半重放结果，再盖一份只会更乱。
        if (aofPresent) {
            CommandHandler replayer = new CommandHandler(store, null);
            replayer.bindScope(scope);
            scope.setLoading(true);
            try {
                aofPersistence.loadAof(aofPath, replayer::replayCommand);
                logger.info("AOF data replayed from {}", aofPath);
            } catch (Exception e) {
                logger.error("Failed to replay AOF {}: {}", aofPath, e.getMessage(), e);
            } finally {
                scope.setLoading(false);
            }
        } else {
            try {
                rdbPersistence.load(rdbPath);
                logger.info("RDB data loaded from {}", rdbPath);
            } catch (Exception e) {
                logger.warn("Failed to load RDB: {}", e.getMessage());
            }
        }

        // 启动 AOF 写入（追加模式，上面重放过的内容原样保留）
        try {
            aofPersistence.start(aofPath);
            scope.setAofPersistence(aofPersistence);
            logger.info("AOF persistence started: {}", aofPath);
        } catch (Exception e) {
            logger.warn("Failed to start AOF: {}", e.getMessage());
        }

        // 定时快照。RdbPersistence 的调度器以前一个调用方都没有，所以"每隔 N 秒自动落一份
        // RDB"只存在于类注释里：除了显式 SAVE 和优雅停机，磁盘上永远等不到第二份快照，
        // 进程被 kill -9 时 AOF 之外没有任何兜底。
        try {
            int saveSeconds = intProperty("zcache.save-seconds", 300, 0);
            int saveChanges = intProperty("zcache.save-changes", 1000, 0);
            rdbPersistence.setSaveStrategy(saveSeconds, saveChanges);
            if (saveSeconds > 0 && saveChanges > 0) {
                rdbPersistence.start(rdbPath);
            } else {
                // 0 是"按 setSaveStrategy 的语义关掉这一路"，不是"随手配错了就当没配"。
                logger.info("Periodic RDB snapshot disabled (zcache.save-seconds={}, zcache.save-changes={})",
                        saveSeconds, saveChanges);
            }
        } catch (Exception e) {
            logger.warn("Failed to start the RDB scheduler: {}", e.getMessage());
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
        // 摘掉本台的写盘入口：已经关掉的 AOF writer 不能再被这条连接上
        // 迟到的命令 append（作用域已经是每台一份，不会再牵连别台）。
        scope.setAofPersistence(null);
        scope.setRdbPersistence(null);
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
                // stop() 只关文件；两个调度线程池要 shutdown() 才收得回来，
                // 否则嵌入式起停一次就泄漏一对线程。
                aofPersistence.shutdown();
                logger.info("AOF persistence stopped");
            } catch (Exception e) {
                logger.error("Failed to stop AOF: {}", e.getMessage(), e);
            }
            aofPersistence = null;
        }
    }

    /**
     * 整数配置项：允许用 {@code -Dzcache.business-threads} / {@code -Dzcache.blocking-threads} /
     * {@code -Dzcache.save-seconds} 等覆盖，非法值不静默生效（以前是"配了也看不出来"）。
     *
     * @param min 允许的最小值；线程组要 ≥1，而 {@code save-*} 允许 0 表示"这一路关掉"
     */
    private static int intProperty(String property, int fallback, int min) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min) {
                return value;
            }
            logger.warn("{}={} is below {}, using {}", property, raw, min, fallback);
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
    public PubSubManager getPubSubManager() { return scope.pubSubManager(); }
    public RdbPersistence getRdbPersistence() { return rdbPersistence; }
    public AofPersistence getAofPersistence() { return aofPersistence; }
}
