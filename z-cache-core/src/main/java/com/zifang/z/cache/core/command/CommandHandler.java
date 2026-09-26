package com.zifang.z.cache.core.command;

import com.zifang.z.cache.common.protocol.*;
import com.zifang.z.cache.core.logging.SlowLog;
import com.zifang.z.cache.core.persistence.AofPersistence;
import com.zifang.z.cache.core.persistence.RdbPersistence;
import com.zifang.z.cache.core.pubsub.PubSubManager;
import com.zifang.z.cache.core.storage.*;
import com.zifang.z.cache.core.stream.StreamStore;
import com.zifang.z.cache.core.stream.StreamEntry;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 命令处理器 — 支持完整 Redis 命令集的内存缓存服务器。
 * <p>
 * 每个 Netty 连接拥有独立的 CommandHandler 实例，per-connection 状态
 * （currentDb、transactionContext、channelContext）是线程安全的。
 * 共享组件（PubSubManager、SlowLog、子存储）通过静态字段在所有连接间共享。
 *
 * @author zifang
 * @since 1.0.0
 */
public class CommandHandler {
    private static final Logger logger = LogManager.getLogger(CommandHandler.class);

    private final MemoryStore store;
    private final String password;
    private volatile boolean authenticated;

    // per-connection 状态
    private int currentDb = 0;
    private final TransactionManager transactionManager;
    private final TransactionManager.TransactionContext transactionContext;
    /**
     * 这条连接的 channel 上下文。必须 volatile：CLIENT LIST 是"旁观者线程直接读别人的
     * CommandHandler"（见 {@code handleClient} 的 LIST 分支），没有 volatile 时旁观者
     * 按规范可以读到过期值，症状就是 sub=/psub= 与真实订阅态对不上，且只在整模块连跑时现形。
     */
    private volatile ChannelHandlerContext channelContext;
    /**
     * 这条连接真实监听到的端口。bind(0) 时配置里写的 6379 和实际端口不是一回事，
     * INFO 只能报后者；做成 per-connection 而不是静态量，是因为一个 JVM 里会同时
     * 起多个端口不同的服务器实例（测试就是这种形态），静态量会互相串。
     * 未经连接驱动（单测直接 handle()）时保持 0，INFO 就如实报 0。
     */
    private volatile int localPort;

    // 共享组件。
    //
    // pub/sub 管理器与连接登记表以前是裸静态字段，而且每条新连接的 handler 构造函数都会
    // 覆写一遍 pubSubManager —— 一个 JVM 里同时起两台服务器（整模块连跑就是这个形态）时，
    // 两条连接完全可能各自读到不同实例：SUBSCRIBE 记进 A、PSUBSCRIBE 记进 B，
    // CLIENT LIST 再读出 sub=1 psub=0 这种对不上账的数。
    //
    // 1.3.6 把同一把尺套到剩下的四样上（StreamStore / SlowLog / AOF / RDB）。它们当时仍是
    // 进程级的，于是"再起一台服务器"会改掉正在跑的那台的行为——两条都实测过：
    //   1) B 启动后 A 上 XLEN 读到 B 的流（{@code :1}，真 Redis 该是 {@code :0}）；
    //   2) 不带 dataDir 的 B 一启动就把全局 RDB 置 null，A 的 SAVE 变成
    //      {@code -ERR SAVE is not supported: no data directory configured}，
    //      而 A 还在照常收写入——静默丢盘，比第一条更贵。
    // 现在每条连接优先读"所属服务器"注入的那一份（{@link ServerScope}），静态的只当
    // "没人注入过"时的进程级默认值（嵌入式与单测走那条）。加 volatile 只解决可见性，
    // 解决不了共用，所以这里改的是作用域而不是内存序。
    private static volatile PubSubManager defaultPubSubManager;
    private static volatile SlowLog defaultSlowLog;
    private static volatile AofPersistence defaultAofPersistence;
    private static volatile RdbPersistence defaultRdbPersistence;
    private static volatile StreamStore defaultStreamStore;
    private static volatile boolean defaultLoading;

    /** 这条连接所属服务器的那一份共享状态；未绑定时为 null，读侧退回进程级默认值。 */
    private volatile ServerScope scope;

    /** 进程级默认连接登记表：没有服务器注入时（单测直接 new）用这一份。 */
    private static final ConcurrentMap<ChannelHandlerContext, CommandHandler> DEFAULT_CONNECTIONS
            = new ConcurrentHashMap<>();
    /** 进程级默认 MONITOR 集合，口径同上。 */
    private static final Set<ChannelHandlerContext> DEFAULT_MONITOR_CLIENTS
            = ConcurrentHashMap.newKeySet();

    /** 每连接客户端名称（CLIENT SETNAME / GETNAME） */
    private volatile String clientName;

    /** 建连时间与最后一次真正执行命令的时间，CLIENT LIST 的 age=/idle= 由它们算。 */
    private final long connectedAtMs = System.currentTimeMillis();
    private volatile long lastCommandMs = System.currentTimeMillis();
    private volatile String lastCommand = "NULL";

    // ======================== 构造 ========================

    public CommandHandler(MemoryStore store) {
        this(store, null);
    }

    public CommandHandler(MemoryStore store, String password) {
        this.store = store;
        this.password = password;
        this.authenticated = password == null;
        this.transactionManager = new TransactionManager();
        this.transactionContext = new TransactionManager.TransactionContext();
    }

    // ---- 共享组件 setter：进程级默认值，未绑定服务器的那条连接读这一份 ----
    public static void setPubSubManager(PubSubManager m) { defaultPubSubManager = m; }
    public static void setSlowLog(SlowLog l) { defaultSlowLog = l; }
    public static void setAofPersistence(AofPersistence a) { defaultAofPersistence = a; }
    public static void setRdbPersistence(RdbPersistence r) { defaultRdbPersistence = r; }
    public static void setStreamStore(StreamStore ss) { defaultStreamStore = ss; }

    /**
     * 把这条连接接到它所属服务器的那一份共享状态上。由
     * {@link com.zifang.z.cache.core.server.RedisServerHandler} 在建管道时调用一次。
     *
     * @param scope 这台服务器的共享状态；传 null 表示不接，读侧退回进程级默认值
     */
    public void bindScope(ServerScope scope) {
        this.scope = scope;
    }

    /** pub/sub 管理器：先用这台连接所属服务器注入的那份，没有再退回进程级默认值。 */
    private PubSubManager pubSub() {
        ServerScope s = this.scope;
        return s != null ? s.pubSubManager() : defaultPubSubManager;
    }

    /** MONITOR / CLIENT 命令"未绑定服务器"时退回的那份进程级登记表，单测直接 new 走这条。 */
    private ConcurrentMap<ChannelHandlerContext, CommandHandler> connections() {
        ServerScope s = this.scope;
        return s != null ? s.connections() : DEFAULT_CONNECTIONS;
    }

    private Set<ChannelHandlerContext> monitorClients() {
        ServerScope s = this.scope;
        return s != null ? s.monitorClients() : DEFAULT_MONITOR_CLIENTS;
    }

    private StreamStore streams() {
        ServerScope s = this.scope;
        return s != null ? s.streamStore() : defaultStreamStore;
    }

    private SlowLog slowLog() {
        ServerScope s = this.scope;
        return s != null ? s.slowLog() : defaultSlowLog;
    }

    private RdbPersistence rdb() {
        ServerScope s = this.scope;
        return s != null ? s.rdbPersistence() : defaultRdbPersistence;
    }

    private AofPersistence aof() {
        ServerScope s = this.scope;
        return s != null ? s.aofPersistence() : defaultAofPersistence;
    }

    private boolean isLoading() {
        ServerScope s = this.scope;
        return s != null ? s.loading() : defaultLoading;
    }

    public static StreamStore getStreamStore() { return defaultStreamStore; }
    public static SlowLog getSlowLog() { return defaultSlowLog; }


    /**
     * 绑定的同时把这条连接实际监听到的端口记下来：INFO 报的是它，不是配置里的默认 6379
     * （{@code bind(0)} 时两者必然不同）。EmbeddedChannel / 无连接驱动时拿不到
     * InetSocketAddress，端口保持 0，INFO 就如实报 0。
     */
    public void setChannelContext(ChannelHandlerContext ctx) {
        if (ctx != null) {
            connections().put(ctx, this);
            if (ctx.channel().localAddress() instanceof java.net.InetSocketAddress) {
                this.localPort = ((java.net.InetSocketAddress) ctx.channel().localAddress()).getPort();
            }
        } else if (channelContext != null) {
            connections().remove(channelContext);
        }
        this.channelContext = ctx;
    }

    /**
     * 对外宣告的产品版本号。与 {@code ZCacheServerMain} 共用这一把尺：读打包进 MANIFEST 的
     * Implementation-Version，裸 IDE/classes 目录运行时读不到就如实报 dev，
     * 不再在任何地方硬写一个数字——上一版 INFO 里写着 1.0.2，而 pom 已经是 1.3.x。
     */
    public static String serverVersion() {
        Package p = CommandHandler.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null || v.isEmpty() ? "dev" : v;
    }

    /**
     * 该连接是否正处在 MULTI 的"入队"阶段（EXEC 真正执行时不算）。
     * 服务器侧只有在这一阶段之外才允许把阻塞命令挪到专用线程上跑。
     */
    public boolean queuedInMulti() {
        return transactionContext.isInTransaction() && !executingTransaction;
    }

    /**
     * 客户端断开连接时的清理逻辑。
     * 清理该连接的 PubSub 订阅、事务上下文。
     */
    public void onDisconnect() {
        PubSubManager pubSub = pubSub();
        if (pubSub != null && channelContext != null) {
            pubSub.removeClient(channelContext);
        }
        if (channelContext != null) {
            monitorClients().remove(channelContext);
            connections().remove(channelContext);
        }
        if (transactionManager != null) {
            transactionManager.cleanup(transactionContext);
        }
    }

    // ======================== 核心分发 ========================

    /**
     * AOF 重放入口：把日志里的一条命令按普通命令执行。
     * <p>
     * 和客户端路径的差别只有三处：不回写 AOF（调用方已 {@link #setLoading(boolean)} 置位）、
     * 不绑连接（没有 PubSub / MONITOR 语义）、不参与鉴权（重放用的是服务内部对象）。
     * 走的是同一个 {@link #handle(Object)}，所以"重放后 GET 得到什么"和"客户端当时 GET
     * 得到什么"用的是同一份代码 —— 这正是以前缺的那一环：AOF 只写不读，落盘的功能一个字都没兑现。
     */
    public void replayCommand(String[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        Object[] parts = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            parts[i] = RespBulkString.of(args[i] == null ? "" : args[i]);
        }
        handle(RespArray.of(parts));
    }

    public Object handle(Object request) {
        if (request == null) return RespError.of("ERR", "empty request");
        if (!(request instanceof RespArray)) {
            logger.warn("Request is not an array: {}", request.getClass().getName());
            return RespError.of("ERR", "Protocol error: expected array");
        }
        RespArray array = (RespArray) request;
        String[] args = array.toStringArray();
        if (args.length == 0) return RespError.of("ERR", "empty command");
        String cmd = args[0].toUpperCase(Locale.ROOT);
        logger.debug("Processing command: {} with {} args", cmd, args.length);

        long startTime = System.nanoTime();
        // CLIENT LIST 的 cmd=/idle= 取的是"这条连接最后处理的命令"。放在 pubsub 闸门与
        // MULTI 入队之前，两条早退路径才不会把它跳过去 —— 一个常年订阅的连接，
        // idle= 不该停在它刚建立时的那一刻。
        this.lastCommand = cmd;
        this.lastCommandMs = System.currentTimeMillis();

        // Pub/Sub 模式检查
        PubSubManager pubSub = pubSub();
        if (pubSub != null && channelContext != null && pubSub.isSubscribed(channelContext)) {
            switch (cmd) {
                case "SUBSCRIBE":    return handleSubscribe(args);
                case "UNSUBSCRIBE":  return handleUnsubscribe(args);
                case "PSUBSCRIBE":   return handlePsubscribe(args);
                case "PUNSUBSCRIBE": return handlePunsubscribe(args);
                case "PING":         return RespSimpleString.of("PONG");
                case "QUIT":         return RespSimpleString.of("OK");
                default:
                    return RespError.of("ERR",
                            "Can't execute this command in subscribe mode");
            }
        }

        if ("AUTH".equals(cmd)) return handleAuth(args);
        if (!authenticated) return RespError.of("NOAUTH", "Authentication required.");

        // WATCH 不入队：Redis 把它标成 no-multi，在事务里出现是当场报错而不是排队。
        // 以前它和 SET 一样吃 +QUEUED，于是"事务里 WATCH"要到 EXEC 之后才发现根本没生效。
        if (!executingTransaction && transactionContext.isInTransaction()
                && !"MULTI".equals(cmd) && !"EXEC".equals(cmd) && !"DISCARD".equals(cmd)
                && !"WATCH".equals(cmd)) {
            transactionManager.addCommand(transactionContext, args);
            return RespSimpleString.of("QUEUED");
        }

        RespError conflict = typeConflict(args);
        if (conflict != null) return conflict;

        try {
            Object result;
            switch (cmd) {
                case "PING":     result = handlePing(args);       break;
                case "ECHO":     result = handleEcho(args);       break;
                case "QUIT":     result = RespSimpleString.of("OK"); break;
                case "SELECT":   result = handleSelect(args);     break;
                case "DBSIZE":   result = handleDbsize();         break;
                case "SET":      result = handleSet(args);        break;
                case "GET":      result = handleGet(args);        break;
                case "DEL":      result = handleDel(args);        break;
                case "EXISTS":   result = handleExists(args);     break;
                case "EXPIRE":   result = handleExpireLike(args, "EXPIRE", true);   break;
                case "PEXPIRE":  result = handleExpireLike(args, "PEXPIRE", false); break;
                case "TTL":      result = handleTtl(args);        break;
                case "PTTL":     result = handlePttl(args);       break;
                case "PERSIST":  result = handlePersist(args);    break;
                case "SETEX":    result = handleSetexLike(args, "SETEX", true);   break;
                case "PSETEX":   result = handleSetexLike(args, "PSETEX", false);  break;
                case "SETNX":    result = handleSetnx(args);      break;
                case "GETSET":   result = handleGetset(args);     break;
                case "MGET":     result = handleMget(args);       break;
                case "MSET":     result = handleMset(args);       break;
                case "APPEND":   result = handleAppend(args);     break;
                case "STRLEN":   result = handleStrlen(args);     break;
                case "GETRANGE": result = handleGetrange(args, false); break;
                case "SUBSTR":   result = handleGetrange(args, true);  break;
                case "SETRANGE": result = handleSetrange(args);   break;
                case "BITCOUNT": result = handleBitcount(args);   break;
                case "GETBIT":   result = handleGetbit(args);     break;
                case "SETBIT":   result = handleSetbit(args);     break;
                case "BITPOS":   result = handleBitpos(args);     break;
                case "BITOP":    result = handleBitop(args);      break;
                case "INCRBYFLOAT": result = handleIncrbyfloat(args); break;
                case "MSETNX":   result = handleMsetnx(args);     break;
                case "UNLINK":   result = handleUnlink(args);     break;
                case "TOUCH":    result = handleTouch(args);      break;
                case "EXPIREAT": result = handleExpireat(args, false); break;
                case "PEXPIREAT":result = handleExpireat(args, true);  break;
                case "MOVE":     result = handleMove(args);       break;
                case "INCR":     result = handleIncr(args, 1);    break;
                case "DECR":     result = handleIncr(args, -1);   break;
                case "INCRBY":   result = handleIncrby(args, 1);  break;
                case "DECRBY":   result = handleIncrby(args, -1); break;
                case "KEYS":     result = handleKeys(args);       break;
                case "TYPE":     result = handleType(args);       break;
                case "RENAME":   result = handleRename(args, false); break;
                case "RENAMENX": result = handleRename(args, true);  break;
                case "RANDOMKEY":result = handleRandomkey();      break;
                case "HSET":     result = handleHset(args);       break;
                case "HGET":     result = handleHget(args);       break;
                case "HDEL":     result = handleHdel(args);       break;
                case "HEXISTS":  result = handleHexists(args);    break;
                case "HGETALL":  result = handleHgetall(args);    break;
                case "HKEYS":    result = handleHkeys(args);      break;
                case "HVALS":    result = handleHvals(args);      break;
                case "HMGET":    result = handleHmget(args);      break;
                case "HMSET":    result = handleHmset(args);      break;
                case "HINCRBY":  result = handleHincrby(args);    break;
                case "HLEN":     result = handleHlen(args);       break;
                case "HSTRLEN":  result = handleHstrlen(args);    break;
                case "HSETNX":   result = handleHsetnx(args);     break;
                case "HSCAN":    result = handleHscan(args);      break;
                case "LPUSH":    result = handleLpush(args);      break;
                case "RPUSH":    result = handleRpush(args);      break;
                case "LPUSHX":   result = handlePushx(args, true);  break;
                case "RPUSHX":   result = handlePushx(args, false); break;
                case "LPOP":     result = handleLpop(args);       break;
                case "RPOP":     result = handleRpop(args);       break;
                case "LRANGE":   result = handleLrange(args);     break;
                case "LINDEX":   result = handleLindex(args);     break;
                case "LLEN":     result = handleLlen(args);       break;
                case "LSET":     result = handleLset(args);       break;
                case "LINSERT":  result = handleLinsert(args);    break;
                case "LREM":     result = handleLrem(args);       break;
                case "LTRIM":    result = handleLtrim(args);      break;
                case "RPOPLPUSH":result = handleRpoplpush(args);  break;
                case "BLPOP":    result = handleBpop(args, "LEFT");    break;
                case "BRPOP":    result = handleBpop(args, "RIGHT");   break;
                case "BRPOPLPUSH": result = handleBrpoplpush(args); break;
                case "HRANDFIELD": result = handleHrandfield(args); break;
                case "SSCAN":    result = handleSscan(args);      break;
                case "ZSCAN":    result = handleZscan(args);      break;
                case "SADD":     result = handleSadd(args);       break;
                case "SREM":     result = handleSrem(args);       break;
                case "SMEMBERS": result = handleSmembers(args);   break;
                case "SISMEMBER":result = handleSismember(args);  break;
                case "SCARD":    result = handleScard(args);      break;
                case "SRANDMEMBER": result = handleSrandmember(args); break;
                case "SINTER":   result = handleSinter(args);     break;
                case "SUNION":   result = handleSunion(args);     break;
                case "SDIFF":    result = handleSdiff(args);      break;
                case "SMOVE":    result = handleSmove(args);      break;
                case "ZADD":     result = handleZadd(args);       break;
                case "ZREM":     result = handleZrem(args);       break;
                case "ZSCORE":   result = handleZscore(args);     break;
                case "ZRANK":    result = handleZrank(args);      break;
                case "ZREVRANK": result = handleZrevrank(args);   break;
                case "ZCARD":    result = handleZcard(args);      break;
                case "ZCOUNT":   result = handleZcount(args);     break;
                case "ZRANGE":   result = handleZrange(args, false);  break;
                case "ZREVRANGE":result = handleZrange(args, true);   break;
                case "ZRANGEBYSCORE":    result = handleZrangebyscore(args, false); break;
                case "ZREVRANGEBYSCORE": result = handleZrangebyscore(args, true);  break;
                case "ZINCRBY":  result = handleZincrby(args);    break;
                case "HINCRBYFLOAT": result = handleHincrbyfloat(args); break;
                case "LMOVE":    result = handleLmove(args);       break;
                case "SPOP":     result = handleSpop(args);        break;
                case "SINTERSTORE": result = handleSinterstore(args); break;
                case "SUNIONSTORE": result = handleSunionstore(args); break;
                case "SDIFFSTORE":  result = handleSdiffstore(args);  break;
                case "ZUNIONSTORE": result = handleZstore(args, false); break;
                case "ZINTERSTORE": result = handleZstore(args, true);  break;
                case "ZLEXCOUNT":   result = handleZlexcount(args);   break;
                case "ZRANGEBYLEX": result = handleZrangebylex(args, false); break;
                case "ZREVRANGEBYLEX": result = handleZrangebylex(args, true); break;
                case "ZREMRANGEBYLEX": result = handleZremrangebylex(args); break;
                case "ZREMRANGEBYRANK": result = handleZremrangebyrank(args); break;
                case "ZREMRANGEBYSCORE": result = handleZremrangebyscore(args); break;
                case "ZRANDMEMBER": result = handleZrandmember(args); break;
                case "SCAN":     result = handleScan(args);        break;
                case "MULTI":    result = handleMulti();    break;
                case "EXEC":     result = handleExec();     break;
                case "DISCARD":  result = handleDiscard();  break;
                case "WATCH":    result = handleWatch(args); break;
                case "UNWATCH":  result = handleUnwatch();  break;
                case "SUBSCRIBE":    result = handleSubscribe(args);    break;
                case "UNSUBSCRIBE":  result = handleUnsubscribe(args);  break;
                case "PSUBSCRIBE":   result = handlePsubscribe(args);   break;
                case "PUNSUBSCRIBE": result = handlePunsubscribe(args); break;
                case "PUBLISH":      result = handlePublish(args);      break;
                case "PUBSUB":       result = handlePubsub(args);       break;
                case "BGSAVE":  result = handleBgsave(); break;
                case "SAVE":    result = handleSave(); break;
                case "LASTSAVE":result = handleLastsave(); break;
                case "SLOWLOG": result = handleSlowlog(args); break;
                case "FLUSHDB":  result = handleFlushdb();  break;
                case "FLUSHALL": result = handleFlushall(); break;
                case "INFO":     result = handleInfo(args);    break;
                case "CLIENT":   result = handleClient(args);  break;
                case "DEBUG":    result = handleDebug(args);   break;
                case "MONITOR":  result = handleMonitor(args);  break;
                case "RESET":    result = handleReset();        break;
                // ---- Stream 命令 ----
                case "XADD":       result = handleXadd(args);      break;
                case "XLEN":       result = handleXlen(args);      break;
                case "XRANGE":     result = handleXrange(args, false); break;
                case "XREVRANGE":  result = handleXrange(args, true);  break;
                case "XDEL":       result = handleXdel(args);      break;
                case "XTRIM":      result = handleXtrim(args);     break;
                case "XREAD":      result = handleXread(args);     break;
                case "XREADGROUP": result = handleXreadgroup(args); break;
                case "XGROUP":     result = handleXgroup(args);    break;
                case "XACK":       result = handleXack(args);      break;
                case "XPENDING":   result = handleXpending(args);  break;
                case "XINFO":      result = handleXinfo(args);     break;
                default:
                    logger.warn("Unknown command: {}", cmd);
                    // 回的是客户端打进来的那一串<b>原样</b>，不是大写化之后用于分派的那一份：
                    // 实测 {@code ZzYx e31 hello} → {@code unknown command 'ZzYx'}、
                    // {@code zaddx ...} → {@code 'zaddx'}（battery31 第 15/16 行）。分派要大小写
                    // 无关，报错却要照客户的写法 —— 两者不能共用同一个字符串。
                    result = RespError.unknownCommand(args[0]);
            }
            if (slowLog() != null) {
                long duration = System.nanoTime() - startTime;
                slowLog().log(duration, args);
            }
            // MONITOR 转发：向所有 MONITOR 客户端推送命令
            if (!monitorClients().isEmpty() && !"MONITOR".equals(cmd)) {
                forwardToMonitors(args);
            }
            // 写命令收尾：AOF 追加 + RDB 写入计数
            propagateWriteToPersistence(args, result);
            return result;
        } catch (NumberFormatException e) {
            // 整数栏的语法是集中在一处判的（{@link #longArg} / {@link #intArg}），抛出来由这里
            // 统一接：三十几个解析点里有一半本来就没有自己的 try，以前那些位置上的
            // {@code LPOP k abc} 会一路穿到下面的兜底 catch，回成 {@code internal error: For
            // input string "abc"} —— 一个语法错被报成了服务器内部错。
            // 有 try 的调用点（SELECT / MOVE / ZRANGEBYSCORE 的浮点栏）各自那句仍然优先。
            return RespError.notAnInteger();
        } catch (Exception e) {
            logger.error("Error executing command: {} - {}", cmd, e.getMessage(), e);
            return RespError.of("ERR", "internal error: " + e.getMessage());
        }
    }

    // ==================== 连接命令 ====================

    /**
     * AUTH —— 三条文案各自什么时候出现，全在 250 上量过（battery31/33/34，参考实例
     * redis-server 4.0.9，带 requirepass 与不带各跑一轮）：
     * <ul>
     *   <li>arity 排在最前：{@code AUTH}、{@code AUTH a b}、{@code AUTH a b c} 一律
     *       {@code wrong number of arguments for 'auth' command}，<b>即使这台实例根本没设密码</b>。
     *       对岸这条挂在命令表上（ arity 固定 2），轮不到处理函数里的分支，所以"没设密码"
     *       不能当先判 —— 1.3.5 之前就是先判密码，于是三条形状全回了同一句话。</li>
     *   <li>没设密码却收到 AUTH：{@code Client sent AUTH, but no password is set}。</li>
     *   <li>设了密码而密码不对：{@code invalid password}（不是 6.0 之后那句 WRONGPASS）。</li>
     * </ul>
     * 超出对岸的一条不做：Redis 6.0 的 {@code AUTH <user> <pass>} 双参形状，4.0.9 直接吃 arity 错，
     * 我们跟着拒；对岸的 maxauthtries（错十次踢连接）同样没有照搬。
     */
    private Object handleAuth(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("AUTH");
        if (password == null) return RespError.of("ERR", "Client sent AUTH, but no password is set");
        if (password.equals(args[1])) { authenticated = true; return RespSimpleString.of("OK"); }
        return RespError.of("ERR", "invalid password");
    }

    private Object handlePing(String[] args) {
        if (args.length == 1) return RespSimpleString.of("PONG");
        if (args.length == 2) return RespBulkString.of(args[1]);
        return RespError.wrongNumberOfArguments("PING");
    }

    private Object handleEcho(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("ECHO");
        return RespBulkString.of(args[1]);
    }

    private Object handleSelect(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("SELECT");
        try {
            int db = intArg(args[1]);
            if (db < 0 || db > 15) return RespError.of("ERR", "DB index is out of range");
            this.currentDb = db;
            return RespSimpleString.of("OK");
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "invalid DB index");
        }
    }

    // ==================== String 命令 ====================

    /**
     * SET —— 修饰位这一段照参考实现的形状实现（battery32/33/35，250 实测）。三件事按顺序：
     * <ol>
     *   <li><b>扫一遍旗标位</b>：NX/XX/EX/PX 认得，认不得的那一枚起就是尾巴。同一个旗标
     *       <b>重复出现是不拦的</b>（实测 {@code SET s33 v EX 10 EX 20} 回 {@code +OK}，
     *       battery33 第 6 行 —— 先前把它当成冲突拒掉是我加的，对岸没这一条），互斥的只有
     *       {@code NX×XX} 与 {@code EX×PX} 两对。</li>
     *   <li><b>再判冲突与尾巴</b>：{@code syntax error} 排在取值<b>之前</b> —— 实测
     *       {@code SET k v NX XX EX abc} 回 syntax error 而不是整数错（battery33 第 4 行），
     *       {@code SET s33 v EX -1 FOO} 也回 syntax error（第 3 行）而不是过期时间错。</li>
     *   <li><b>最后只解析"出现的那一枚"</b>：EX/PX 重复出现时，对岸记下的是<b>最后一次</b>的位置，
     *       解析发生在扫描结束之后 —— 所以 {@code SET s38 v EX abc EX 10} 回 {@code +OK}
     *       （battery38 第 29 行：坏文本那一枚根本没被读过），而 {@code EX 10 EX -1} 回
     *       {@code invalid expire time in set}（第 27 行：坏的是最后一枚，它才是被解析的那枚）。
     *       整数语法走 {@link RedisIntegerFormat}（{@code +10}、{@code -0} 都是
     *       {@code value is not an integer}），且必须 {@code > 0}。</li>
     * </ol>
     * 旗标大小写都认（实测 {@code SET k v EX 1 nx} 在键已存在时回 nil）。
     * <p>
     * 超出对岸的一条：EX 大到"乘一千"会溢出时，4.0.9 照样回 {@code +OK} 并把键静默删掉（实测
     * {@code EX 9223372036854776} → TTL 0、{@code EX 9223372036854775807} → 键当场不见）。
     * 我们回错，理由见 {@link #expireMillisOrOverflow}。
     */
    private Object handleSet(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("SET");
        String key = args[1], value = args[2];
        boolean nx = false, xx = false, ex = false, px = false;
        String rawExpire = null;
        boolean expireInSeconds = false;
        int i = 3;
        for (; i < args.length; i++) {
            String opt = args[i].toUpperCase(Locale.ROOT);
            if ("EX".equals(opt) || "PX".equals(opt)) {
                if (i + 1 >= args.length) return RespError.syntaxError();
                boolean seconds = "EX".equals(opt);
                if (seconds) ex = true;
                else px = true;
                // 重复出现不拦（实测 {@code EX 10 EX 20} → +OK），且只有<b>最后一枚</b>
                // 会被解析（实测 {@code EX abc EX 10} → +OK）—— 所以这里只记住位置。
                rawExpire = args[++i];
                expireInSeconds = seconds;
            } else if ("NX".equals(opt)) {
                nx = true;
            } else if ("XX".equals(opt)) {
                xx = true;
            } else {
                break;
            }
        }
        if ((nx && xx) || (ex && px) || i != args.length) return RespError.syntaxError();
        Long expireMillis = null;
        if (rawExpire != null) {
            Long parsed = RedisIntegerFormat.parse(rawExpire);
            if (parsed == null) return RespError.notAnInteger();
            expireMillis = expireMillisOrOverflow(parsed.longValue(), expireInSeconds);
            if (expireMillis == null || expireMillis.longValue() <= 0) {
                return RespError.of("ERR", "invalid expire time in set");
            }
        }
        // NX/XX 问的是"这个键在不在"，不是"string 命名空间里有没有"：同一份判据 EXISTS /
        // CLIENT 那边用的是 keyExists。以前只看 existsDb（只看 String），于是对一个
        // hash 键执行 SET k v NX 会回 OK —— 键明明存在，只是不是 string。
        boolean exists = keyExists(key);
        if (nx && exists) return RespBulkString.nullBulkString();
        if (xx && !exists) return RespBulkString.nullBulkString();
        byte[] val = value.getBytes(StandardCharsets.UTF_8);
        if (expireMillis != null) store.psetexDb(currentDb, key, expireMillis, val);
        else store.setDb(currentDb, key, val);
        return RespSimpleString.of("OK");
    }

    private Object handleGet(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("GET");
        byte[] v = store.getDb(currentDb, args[1]);
        return v == null ? RespBulkString.nullBulkString() : RespBulkString.of(v);
    }

    private Object handleDel(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("DEL");
        long count = 0;
        for (int i = 1; i < args.length; i++) if (deleteEveryType(args[i])) count++;
        return RespInteger.of(count);
    }

    /**
     * 把一个键名下五张表全清一遍，返回是否真的删掉了东西。
     * <p>
     * DEL 的"删干净"和 RENAME 的"旧值整个消失"是同一条语义，必须共用一把尺。以前五路里第一条
     * 命中就 continue：一个键名下真的并存两种类型时（1.3.4 及之前写出来的，或 {@code RENAME}
     * 造出来的）只删得掉一种，另一份既读不到也删不掉，DBSIZE 还把它多算一个。
     */
    private boolean deleteEveryType(String k) {
        boolean removed = false;
        if (store.delDb(currentDb, k)) removed = true;
        if (store.getHashStore(currentDb).del(k)) removed = true;
        if (store.getListStore(currentDb).del(k)) removed = true;
        if (store.getSetStore(currentDb).del(k)) removed = true;
        if (store.getSortedSetStore(currentDb).del(k)) removed = true;
        return removed;
    }

    private Object handleExists(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("EXISTS");
        long c = 0;
        for (int i = 1; i < args.length; i++) if (keyExists(args[i])) c++;
        return RespInteger.of(c);
    }

    /**
     * EXPIRE / PEXPIRE —— 同一个判据的两个量纲，合成一支。三条实测来的规矩
     * （battery33/37，250 对拍）：
     * <ul>
     *   <li>语法走 {@link RedisIntegerFormat}：{@code EXPIRE k +10}、{@code EXPIRE k -0} 一律
     *       {@code value is not an integer or out of range}。1.3.5 用 {@code Integer.parseInt}，
     *       一头收下了 {@code +10}，另一头把 {@code EXPIRE k 4000000000} 这种合法秒数判死。</li>
     *   <li>{@code <= 0} 是"立刻过期"：回 {@code :1} 且键当场不见（实测 {@code EXPIRE k 0} →
     *       {@code :1} 而 {@code EXISTS k} → 0）。旧实现回的是 {@code :0}，而且真的把键留下跑了。</li>
     *   <li>"键在不在"这一档由存储层的删除自己给：没删掉东西就是 0，与对岸的分界同处。</li>
     * </ul>
     * 有意不一致的一条：秒数大到乘一千会绕回时（{@code EXPIRE k 9223372036854775807}），
     * 4.0.9 回 1 并静默把键删掉（绕回成了一个"过去"的绝对时刻），我们回 {@code invalid expire time}。
     * 负数不在这条里：再负也是过去，照对岸删键回 1。
     */
    private Object handleExpireLike(String[] args, String cmd, boolean seconds) {
        if (args.length != 3) return RespError.wrongNumberOfArguments(cmd);
        Long raw = RedisIntegerFormat.parse(args[2]);
        if (raw == null) return RespError.notAnInteger();
        if (raw > 0 && expireMillisOrOverflow(raw, seconds) == null) {
            return RespError.of("ERR", "invalid expire time");
        }
        boolean done = seconds ? store.expireDb(currentDb, args[1], raw)
                : store.pexpireDb(currentDb, args[1], raw);
        return RespInteger.of(done ? 1 : 0);
    }

    /**
     * 把相对过期量（秒或毫秒）折成毫秒；折不动（秒乘一千会溢出）回 null。
     * <p>
     * 只管乘法那一道：加不上当前时刻的那一段窄缝由存储层贴顶处理（
     * {@code MemoryStore#saturatingExpireAt}），因为对岸在那里绕回成了"过去" —— 实测
     * {@code SET k v EX 9223372036854775} 回 {@code +OK} 且 TTL 就是这串秒数
     * （battery35 第 4/5 行），而 {@code EX 9223372036854776} 绕回后 TTL 成 0、键当场不见
     * （第 6/7 行）。把"永不到期"当成能表达的最远一档，才不至于让一次回复变成一次删除。
     */
    private static Long expireMillisOrOverflow(long value, boolean inSeconds) {
        if (!inSeconds) {
            return Long.valueOf(value);
        }
        if (value > Long.MAX_VALUE / 1000 || value < Long.MIN_VALUE / 1000) return null;
        return Long.valueOf(value * 1000L);
    }

    /**
     * 客户端文本 → {@code long}，用 Redis 那把尺（{@link RedisIntegerFormat}）而不是
     * {@code Long.parseLong}。
     * <p>
     * 为什么全仓几十处都要换：Java 的解析比 Redis 宽，{@code +5} / {@code -0} / {@code 05}
     * 三样它就收，Redis 三样都拒（250 实测 {@code INCRBY k +5}、{@code LINDEX k +0}、
     * {@code GETRANGE k +0 -1} 全回 {@code value is not an integer or out of range}，
     * 见 battery37）。这些位置每一个都是一个独立的入口，漏一处就对岸拒而我们照做。
     * <p>
     * 失败按 {@link NumberFormatException} 抛，是让调用点<b>原有</b>的那句 {@code catch} 去回
     * 自己该回的文案 —— 绝大多数回通用那句，但 MOVE 回 {@code index out of range}、SELECT 回
     * {@code invalid DB index}，把判据收成一处的同时不该把文案也收成一处。
     * 抛而不是返回 {@code null}：三十几处调用点的改动因此压成一次替换，语义换了而形状没换，
     * diff 才看得见。
     *
     * @throws NumberFormatException 语法不对或超出 long 的范围
     */
    private static long longArg(String text) {
        Long v = RedisIntegerFormat.parse(text);
        if (v == null) throw new NumberFormatException(text);
        return v.longValue();
    }

    /** {@link #longArg} 的 int 版：语法同一把尺，范围另有一道（实测各命令的越界也是这一句）。 */
    private static int intArg(String text) {
        Integer v = RedisIntegerFormat.parseAsInt(text);
        if (v == null) throw new NumberFormatException(text);
        return v.intValue();
    }

    private Object handleTtl(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("TTL");
        return RespInteger.of(store.ttlDb(currentDb, args[1]));
    }

    private Object handlePttl(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("PTTL");
        return RespInteger.of(store.pttlDb(currentDb, args[1]));
    }

    private Object handlePersist(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("PERSIST");
        return RespInteger.of(store.persistDb(currentDb, args[1]) ? 1 : 0);
    }

    /**
     * SETEX / PSETEX —— 与 SET 的 EX/PX 同一档判据，但<b>各自的文案不同</b>：实测
     * {@code SETEX k -1 v} 回 {@code invalid expire time in setex}、{@code PSETEX k 0 v} 回
     * {@code invalid expire time in psetex}，而 SET 那条是 {@code ... in set}。对岸这句是
     * {@code addReplyErrorFormat(c,"invalid expire time in %s",c->cmd->name)}，名字取自命令表，
     * 所以三个调用点不能共用一条常量。
     */
    private Object handleSetexLike(String[] args, String cmd, boolean seconds) {
        if (args.length != 4) return RespError.wrongNumberOfArguments(cmd);
        Long raw = RedisIntegerFormat.parse(args[2]);
        if (raw == null) return RespError.notAnInteger();
        Long ms = expireMillisOrOverflow(raw, seconds);
        if (ms == null || ms.longValue() <= 0) {
            return RespError.of("ERR", "invalid expire time in " + cmd.toLowerCase(Locale.ROOT));
        }
        // 一律按毫秒落盘：SETEX 的秒数栏在参考实现里也是先乘一千再当绝对时刻用的。
        store.psetexDb(currentDb, args[1], ms, args[3].getBytes(StandardCharsets.UTF_8));
        return RespSimpleString.of("OK");
    }

    private Object handleSetnx(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("SETNX");
        return RespInteger.of(store.setIfAbsentDb(currentDb, args[1], args[2].getBytes(StandardCharsets.UTF_8)) ? 1 : 0);
    }

    private Object handleGetset(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("GETSET");
        byte[] old = store.getAndSetDb(currentDb, args[1], args[2].getBytes(StandardCharsets.UTF_8));
        return old == null ? RespBulkString.nullBulkString() : RespBulkString.of(old);
    }

    private Object handleMget(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("MGET");
        List<byte[]> vals = store.mgetDb(currentDb, Arrays.copyOfRange(args, 1, args.length));
        Object[] r = new Object[vals.size()];
        for (int i = 0; i < vals.size(); i++) r[i] = vals.get(i)==null ? RespBulkString.nullBulkString() : RespBulkString.of(vals.get(i));
        return RespArray.of(r);
    }

    private Object handleMset(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("MSET");
        // 参数凑不成对走的是 MSET/MSETNX 共用的那段码，回的是一句写死的大写 MSET
        // （实测 MSET a 1 c → {@code wrong number of arguments for MSET}，而 MSET a 才吃通用 arity 错）。
        if (args.length % 2 == 0) return RespError.of("ERR", "wrong number of arguments for MSET");
        for (int i = 1; i < args.length; i += 2) store.setDb(currentDb, args[i], args[i+1].getBytes(StandardCharsets.UTF_8));
        return RespSimpleString.of("OK");
    }

    private Object handleAppend(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("APPEND");
        return RespInteger.of(store.appendDb(currentDb, args[1], args[2].getBytes(StandardCharsets.UTF_8)));
    }

    private Object handleStrlen(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("STRLEN");
        byte[] v = store.getDb(currentDb, args[1]);
        return RespInteger.of(v==null ? 0 : v.length);
    }

    /**
     * GETRANGE 与它的历史别名 SUBSTR —— 同一段码、同一个 arity 之外的形状，只是报错时
     * 报自己的名字（实测两者各自回 {@code 'getrange'} / {@code 'substr'}）。
     * <p>
     * 下标折叠照参考实现的 sdsrange：负数先加长度、加完还是负数就归 0，右端超出长度就截到
     * 最后一个字节，{@code start > end} 或 {@code start >= 长度} 是空串。两条边界实测撑着：
     * {@code GETRANGE b4:s -100 -100} 回 {@code "H"}（两端都归 0，不是空串），
     * {@code GETRANGE b4:s 9223372036854775807 5} 回空串（没绕成负数）。
     */
    private Object handleGetrange(String[] args, boolean substr) {
        if (args.length != 4) return RespError.wrongNumberOfArguments(substr ? "SUBSTR" : "GETRANGE");
        long start, end;
        try {
            start = longArg(args[2]);
            end = longArg(args[3]);
        } catch (NumberFormatException e) {
            return RespError.notAnInteger();
        }
        // 类型检查补在两枚下标解析之后（中央闸门跑在分发之前，会把这一档的顺序做反，
        // 见 TYPED_COMMANDS 上那段说明）：实测 {@code GETRANGE <list 键> 0 1} 才是 WRONGTYPE。
        RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.STRING, args[1]);
        if (conflict != null) return conflict;
        byte[] v = store.getDb(currentDb, args[1]);
        int len = v == null ? 0 : v.length;
        long from = foldRangeIndex(start, len), to = foldRangeIndex(end, len);
        if (from > to || from >= len) return RespBulkString.of("");
        if (to >= len) to = len - 1;
        return RespBulkString.of(Arrays.copyOfRange(v, (int) from, (int) to + 1));
    }

    /** 负下标先按"离末尾多远"折成正数；折不动（比整个串还左）就贴到 0，绝不绕回正数。 */
    private static long foldRangeIndex(long index, int len) {
        if (index >= 0) return index;
        return index < -(long) len ? 0 : index + len;
    }

    /**
     * BITCOUNT key [start end] —— 数的是<b>位</b>，而区间量的是<b>字节</b>、两端都含
     * （250 实测 battery32/33/35，值 "hello" 逐档钉住：裸回 21、{@code 0 0} 回 3、
     * {@code 1 1} 回 4、{@code -1 -1} 回 6、{@code 2 -1} 回 14、{@code -3 -2} 回 8、
     * {@code -100 100} 与 {@code 0 -1} 都回 21）。
     * <p>
     * 判据顺序也是量出来的，而且<b>"键在不在"排在所有语法之争前面</b>（250 实测 battery39
     * 第 1—15 行）：参数个数（少一个 token 就是 arity 错）→ 键不存在一律 {@code :0}
     * （{@code BITCOUNT nosuch 1}、{@code BITCOUNT nosuch abc def}、
     * {@code BITCOUNT nosuch 1 2 3 4} 三行实测都是 {@code :0}，多余的尾巴和坏下标都轮不到说话）
     * → 键存在但类型不对一律 WRONGTYPE（{@code BITCOUNT l 1}、{@code BITCOUNT l 1 2 9}、
     * {@code BITCOUNT l abc def} 三行实测都是 WRONGTYPE，同样压过 syntax error 和整数那句）
     * → 参数个数（{@code BITCOUNT k 0} 与 5 个以上都是 syntax error，battery33:86／battery38:5-8）
     * → 两个下标的整数语法（{@code abc} 与 20 位那一串都是整数那句，battery38:9）。
     * 也就是说这条命令的<b>个数检查被拆成了两段</b>：一个 token 都没有的 arity 闸在最前
     * （它由命令表把门，对岸连键名都还没拿到），而"只给一个下标 / 给多了"的 syntax 判定排在
     * 查键与类型之后 —— 与 GETRANGE（先解析下标再查键，battery39:16/17 实测）正好相反。
     * 下标折叠与 GETRANGE 共用
     * {@link #foldRangeIndex}，{@code 5 5}（起点越出串尾）、{@code 2 0}、{@code -1 -4}
     * 三档都是 0 而不是负数或错。
     * <p>
     * Redis 6.2 的 {@code BIT} / {@code BYTE} 尾栏在 4.0.9 上就是多余的 token，回
     * syntax error（实测两样都是），所以这里跟着拒 —— 支持它等于对外承诺一种对岸没有的形状。
     */
    private Object handleBitcount(String[] args) {
        // 三种个数三种答案（250 实测 battery33 第 85/86/68 行，键都在的情形）：少了是 arity
        // 错，3 个参数（只给 start 不给 end）和 5 个以上都算 syntax error —— 多余的尾巴不并入
        // arity 那一句，否则 {@code BITCOUNT k 0 1 BIT} 会被回成"参数个数不对"。
        if (args.length < 2) return RespError.wrongNumberOfArguments("BITCOUNT");
        // 查键在语法之前：不存在直接 :0，类型不对直接 WRONGTYPE（battery39:2-6、7-11）。
        if (!keyExists(args[1])) return RespInteger.of(0);
        RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.STRING, args[1]);
        if (conflict != null) return conflict;
        if (args.length == 3 || args.length > 4) return RespError.syntaxError();
        long start = 0, end = -1;
        if (args.length == 4) {
            Long s = RedisIntegerFormat.parse(args[2]);
            Long e = RedisIntegerFormat.parse(args[3]);
            if (s == null || e == null) return RespError.notAnInteger();
            start = s;
            end = e;
        }
        byte[] v = store.getDb(currentDb, args[1]);
        if (v == null || v.length == 0) return RespInteger.of(0);
        long from = foldRangeIndex(start, v.length);
        long to = foldRangeIndex(end, v.length);
        if (to >= v.length) to = v.length - 1;
        if (from > to || from >= v.length) return RespInteger.of(0);
        long bits = 0;
        for (long i = from; i <= to; i++) bits += Integer.bitCount(v[(int) i] & 0xFF);
        return RespInteger.of(bits);
    }

    /**
     * 位偏移的合法区间。Redis 那侧的闸门是"一个字符串最长 512MB"，换算到位就是
     * {@code 0 <= offset < 2^32}：实测 {@code 2^28} 收（把串撑成 33554433 字节，battery40:24），
     * {@code 2^32}、{@code 2^40}、{@code -1} 全拒（battery42 第 33/34 行、battery41 第 5 行）。
     * GETBIT 走的是同一道闸 —— {@code GETBIT h41 4294967296} 实测回错，而不是按"串尾右边算 0"
     * 回 0（battery42:35），所以越界这一档不能只挂在 SETBIT 上。
     */
    private static final long MAX_BIT_OFFSET = 1L << 32;

    private static Long parseBitOffset(String text) {
        Long v = RedisIntegerFormat.parse(text);
        if (v == null || v.longValue() < 0 || v.longValue() >= MAX_BIT_OFFSET) return null;
        return v;
    }

    /**
     * GETBIT key offset —— 判序是量出来的（battery43 第 4/7 行、battery41 第 7/24 行、
     * battery42 第 27/28/35 行）：arity → <b>偏移</b> → 类型 → 取值。坏偏移排在 WRONGTYPE 之前
     * （{@code GETBIT <list 键> abc} 实测回 "bit offset ..." 而不是 WRONGTYPE），而偏移合法的
     * {@code GETBIT <list 键> 0} 才回 WRONGTYPE；键不在、偏移越出串尾都是 0（{@code GETBIT s40 1000}
     * 对 13 字节的串实测 :0），因为 Redis 把串尾右边一律当补零。
     * <p>
     * 位的编号是<b>字节内从高位数起</b>：{@code SETBIT q43c 40 1} 之后 {@code BITPOS q43c 1}
     * 实测 :40，{@code GETBIT o41 7} 在整字节置 1 之后实测 :1，{@code GETBIT "hello" 39} 是 1
     * （末字节 'o'=0x6F 的最低位）。
     */
    private Object handleGetbit(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("GETBIT");
        Long offset = parseBitOffset(args[2]);
        if (offset == null) return RespError.bitOffsetInvalid();
        RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.STRING, args[1]);
        if (conflict != null) return conflict;
        return RespInteger.of(store.getbitDb(currentDb, args[1], offset.longValue()));
    }

    /**
     * SETBIT key offset bit —— 回<b>改之前的那一位</b>；键不在时当场建出来（实测
     * {@code SETBIT nosuch40 0 1} 之后 {@code EXISTS} 是 1，battery41 第 14/15 行），需要撑长时
     * 中间补零（{@code SETBIT q43c 40 1} 之后 STRLEN 是 6、BITCOUNT 是 1，battery43 第 27—29 行）。
     * <p>
     * 判序：arity → 偏移 → bit → 类型。第三道有实测支撑：{@code SETBIT <list 键> 0 2} 回的是
     * "bit is not an integer or out of range"，坏 bit 排在 WRONGTYPE 之前（battery43 第 3 行）。
     * bit 那一栏只收 {@code 0} 和 {@code 1} 两种字面写法：{@code -0}、{@code 01}、{@code +1}
     * 实测全拒（battery42 第 23/25/26 行）—— 这一档比 Redis 的整数语法还严，语法合不合法都不看，
     * 按字符串比。
     */
    private Object handleSetbit(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("SETBIT");
        Long offset = parseBitOffset(args[2]);
        if (offset == null) return RespError.bitOffsetInvalid();
        if (!"0".equals(args[3]) && !"1".equals(args[3])) return RespError.bitValueInvalid();
        RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.STRING, args[1]);
        if (conflict != null) return conflict;
        return RespInteger.of(store.setbitDb(currentDb, args[1], offset.longValue(), "1".equals(args[3])));
    }

    /**
     * BITPOS key bit [start [end]] —— 找第一个等于 {@code bit} 的位，编号与 GETBIT 同一套
     * （字节内从高位数起）。整条判序是量出来的，五档各有反例：
     * <ol>
     *   <li>arity 只管"少于 3 个 token"（{@code BITPOS} / {@code BITPOS k} 实测都是 arity 错，
     *       battery40 第 35/36 行）。</li>
     *   <li>{@code bit} 那一栏用<b>普通</b>整数句（{@code BITPOS k abc} → value is not an
     *       integer，battery39:21；{@code +1}、{@code 01} 同样拒，battery42 第 29/30 行），
     *       合语法但不是 0/1 时换第三句 {@code The bit argument must be 1 or 0.}
     *       （带句号，battery40:27）。这两档都排在查键与类型<b>之前</b>：
     *       {@code BITPOS <list 键> 5} 实测回的是"must be 1 or 0"而不是 WRONGTYPE
     *       （battery41:21）。</li>
     *   <li>查键：键不在时按"找 1 找不到、找 0 就在第 0 位"答 —— {@code -1} / {@code 0}
     *       （battery39:20 与 battery40:28），并且<b>先于</b>后面所有语法之争：
     *       {@code BITPOS <不存在的键> 1 2 3 4} 实测是 {@code -1}，多余的尾巴轮不到说话
     *       （battery39:22）。空串（长度为 0）另有一档：一律 {@code -1}（6394 上逐档实测）。</li>
     *   <li>类型：list 键 → WRONGTYPE（battery41:22，即使 start 是坏文本）。</li>
     *   <li>个数与下标：多于 5 个 token 是 syntax error —— 4.0.9 不认 6.2 的 {@code BYTE|BIT}
     *       尾栏（battery40 第 30/31/34 行、battery41 第 14/15/30 行），这一档又排在 start/end
     *       的整数语法之前（{@code BITPOS k 1 abc def BIT} → syntax error，battery41:14）。</li>
     * </ol>
     * 区间折叠只折一半：负数加长度、加完还负归 0，{@code end} 超出串尾截到最后一个字节，
     * 但 {@code start} <b>不</b>往回截 —— 实测 {@code BITPOS "hello" 1 99} 是 {@code -1}
     * （battery43:10），若把 start 也夹到 4 就会回 33。最后一条是对岸的"串尾右边全是零"：
     * 找 0 且<b>没有显式给 end</b> 而整段又全是 1 时，回 {@code 长度×8} 而不是 -1
     * （实测 1 字节的 {@code 0xFF}：裸回 8、给 start 回 8、显式 {@code 0 0} 回 -1，
     * battery42 第 13/14/15 行）。
     */
    private Object handleBitpos(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("BITPOS");
        Long bit = RedisIntegerFormat.parse(args[2]);
        if (bit == null) return RespError.notAnInteger();
        if (bit.longValue() != 0 && bit.longValue() != 1) return RespError.bitArgInvalid();
        boolean lookingForOne = bit.longValue() == 1;
        RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.STRING, args[1]);
        if (conflict != null) return conflict;
        byte[] v = store.getDb(currentDb, args[1]);
        if (v == null) return RespInteger.of(lookingForOne ? -1 : 0);
        if (v.length == 0) return RespInteger.of(-1);
        if (args.length > 5) return RespError.syntaxError();
        long start = 0;
        if (args.length >= 4) {
            Long s = RedisIntegerFormat.parse(args[3]);
            if (s == null) return RespError.notAnInteger();
            start = s;
        }
        boolean hasEnd = args.length == 5;
        long end = v.length - 1L;
        if (hasEnd) {
            Long e = RedisIntegerFormat.parse(args[4]);
            if (e == null) return RespError.notAnInteger();
            end = e;
            if (end < 0) end += v.length;
            if (end < 0) end = 0;
        }
        if (start < 0) start += v.length;
        if (start < 0) start = 0;
        if (end >= v.length) end = v.length - 1L;
        if (start > end) return RespInteger.of(-1);
        for (long i = start; i <= end; i++) {
            int b = v[(int) i] & 0xFF;
            int candidate = lookingForOne ? b : (~b) & 0xFF;
            if (candidate != 0) return RespInteger.of(i * 8 + Integer.numberOfLeadingZeros(candidate) - 24);
        }
        return RespInteger.of(!hasEnd && !lookingForOne ? (long) v.length * 8 : -1);
    }

    /** BITOP 的四种操作；只用得到内部编号，不参与判序。 */
    private static final int OP_AND = 0, OP_OR = 1, OP_XOR = 2, OP_NOT = 3;

    /**
     * BITOP &lt;AND|OR|XOR|NOT&gt; dest key [key ...] —— 目标键<b>无条件被覆盖</b>，
     * 长度取"最长的那一个源"，比它短的源右边按补零参与运算（battery45 第 27/31/35 行：
     * 5 字节的 hello 与 2 字节的 hi 做 AND 得 {@code "ha\0\0\0"}、STRLEN 仍是 5）。回的是
     * <b>结果的字节数</b>（＝最长源的字节数），<b>不是位数</b>：位族里 BITPOS／GETBIT／SETBIT
     * 数的是位，BITOP 这一条数的是字节 —— 5 字节的 hello AND world 实测 {@code :5}（battery45:16），
     * 而 {@code SETBIT s40 268435456 1} 撑出的那把大伞做源时实测 {@code :33554433}
     * （battery40:44，正是 2^25+1 字节）。按位数回会把它报成 :40／:268435464。
     * <p>
     * 判序四档（battery45 第 7—16 行、battery46 第 12—20 行）：arity（少于 4 个 token，
     * 操作名对不对都轮不到说话）→ 操作名语法（认大小写，{@code aNd} 收、{@code FOO}/{@code SET}
     * 回 syntax error）→ NOT 只许一个源那一句（{@code b46d b46a b46a} 里两个源都是合法 string,
     * 照样回那句；{@code nOt} 也认）→ 源的类型。
     * <p>
     * 两处"没做"是有实测支撑的：
     * <ul>
     *   <li><b>目标键不做类型检查</b>：{@code RPUSH b46z v} 之后 {@code BITOP XOR b46z b46a} 实测
     *       :5 且 {@code TYPE b46z} 变成 string（battery46 第 33—37 行）。只有<b>源</b>才是
     *       string 家族；目标是纯粹的覆盖。</li>
     *   <li><b>源里有类型错时一个字节都不写</b>：{@code SET b46t predata} 之后拿 list 键做源，
     *       实测 WRONGTYPE 而 {@code GET b46t} 还是 "predata"（battery46 第 5—8 行）。所以类型
     *       这一档必须整体先于运算，不能边读边写。</li>
     * </ul>
     * 全空是另一档：最长源为 0（源全不在，或只剩不在的源）时目标键被<b>删掉</b>并回 :0，
     * 而不是留一个空串 —— {@code SET b46n predata} 之后 {@code BITOP AND b46n b46m} 实测
     * {@code EXISTS b46n} 是 0（battery45 第 52—55 行、battery46 第 28—31 行）。
     * 结果是"长度非零但每一位都是 0"时目标键照写（{@code BITOP AND b45n b45d b45a} 实测
     * STRLEN 5 / BITCOUNT 0）。覆盖会清掉目标键原有的过期时间（{@code SET b45n x EX 100} 之后
     * 实测 TTL 是 -1，battery45 第 70—72 行）。
     */
    private Object handleBitop(String[] args) {
        if (args.length < 4) return RespError.wrongNumberOfArguments("BITOP");
        int op;
        String name = args[1].toUpperCase(Locale.ROOT);
        if ("AND".equals(name)) op = OP_AND;
        else if ("OR".equals(name)) op = OP_OR;
        else if ("XOR".equals(name)) op = OP_XOR;
        else if ("NOT".equals(name)) op = OP_NOT;
        else return RespError.syntaxError();
        if (op == OP_NOT && args.length != 4) return RespError.bitopNotSingleSource();
        int sources = args.length - 3;
        byte[][] in = new byte[sources][];
        long max = 0;
        for (int i = 0; i < sources; i++) {
            RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.STRING, args[3 + i]);
            if (conflict != null) return conflict;
            byte[] v = store.getDb(currentDb, args[3 + i]);
            in[i] = v == null ? new byte[0] : v;
            if (in[i].length > max) max = in[i].length;
        }
        String dest = args[2];
        if (max == 0) {
            deleteEveryType(dest);
            return RespInteger.of(0);
        }
        byte[] out = new byte[(int) max];
        if (op == OP_NOT) {
            for (int i = 0; i < max; i++) out[i] = (byte) ~in[0][i];
        } else {
            for (int i = 0; i < max; i++) {
                int acc = op == OP_AND ? 0xFF : 0;
                for (int k = 0; k < sources; k++) {
                    int b = i < in[k].length ? in[k][i] & 0xFF : 0;
                    if (op == OP_AND) acc &= b;
                    else if (op == OP_OR) acc |= b;
                    else acc ^= b;
                }
                out[i] = (byte) acc;
            }
        }
        store.setDb(currentDb, dest, out);
        return RespInteger.of(max);
    }

    private Object handleSetrange(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("SETRANGE");
        long offset;
        try { offset = longArg(args[2]); }
        catch (NumberFormatException e) { return RespError.notAnInteger(); }
        // 偏移的两道判据问的都是"偏移"本身，跟键在不在、是什么类型都无关 —— 这一档排在查库
        // 之前（实测 SETRANGE <list 键> -5 x → offset is out of range，而不是 WRONGTYPE）。
        if (offset < 0) return RespError.of("ERR", "offset is out of range");
        // 类型检查补在偏移之后、写入之前：实测 SETRANGE <list 键> 0 x → WRONGTYPE，
        // 而 SETRANGE <list 键> 600000000 x 也是 WRONGTYPE（参考实现的 checkType 在
        // checkStringLength 前面）。512MB 那一档留在存储层，它排在类型检查之后。
        RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.STRING, args[1]);
        if (conflict != null) return conflict;
        try {
            return RespInteger.of(store.setRangeDb(currentDb, args[1], offset,
                    args[3].getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            return RespError.of("ERR", e.getMessage());
        }
    }

    /**
     * INCRBYFLOAT：文本进、文本出，回复的那串就是键里存的那串（long double 口径，
     * 见 {@link RedisDoubleFormat#plainSum}）。
     */
    private Object handleIncrbyfloat(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("INCRBYFLOAT");
        try {
            return RespBulkString.of(store.incrementFloatDb(currentDb, args[1], args[2]));
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not a valid float");
        } catch (ArithmeticException e) {
            return RespError.of("ERR", "increment would produce NaN or Infinity");
        } catch (IllegalArgumentException e) {
            return RespError.of("ERR", e.getMessage());
        }
    }

    /**
     * MSETNX —— 要么全写，要么一个都不写。
     * <p>
     * 两条实测的边角：① 判"已存在"用的是全类型那把尺（对 hash 键 {@code MSETNX b4:hh1 a b4:other b}
     * 回 0，而 {@code EXISTS b4:other} 也是 0，说明它整条没动手）；② 参数凑不成对时，参考实现
     * 走的是 MSET/MSETNX 共用的那段码，回的是一句写死的大写
     * {@code wrong number of arguments for MSET}（不是带引号的 {@code 'msetnx'}）。
     */
    private Object handleMsetnx(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("MSETNX");
        if (args.length % 2 == 0) return RespError.of("ERR", "wrong number of arguments for MSET");
        for (int i = 1; i < args.length; i += 2) {
            if (keyExists(args[i])) return RespInteger.of(0);
        }
        for (int i = 1; i < args.length; i += 2) {
            store.setDb(currentDb, args[i], args[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return RespInteger.of(1);
    }

    /** UNLINK 就是"承诺异步回收的 DEL"：判据、计数、返回值全同，只是名字要报对自己的。 */
    private Object handleUnlink(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("UNLINK");
        return handleDel(args);
    }

    /** TOUCH 的计数形状与 EXISTS 一模一样（实测重复键重复计：{@code TOUCH a nope a b} → 3）。 */
    private Object handleTouch(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("TOUCH");
        return handleExists(args);
    }

    /**
     * EXPIREAT / PEXPIREAT —— 绝对时间版的 EXPIRE / PEXPIRE，所以直接折到那一条尺上
     * （{@code pexpireDb} 的"过期时间已过就当场删掉并回 1"这一支实测与参考实现一致：
     * {@code EXPIREAT b4:ex 946684800} → 1 而 {@code EXISTS} → 0）。
     * <p>
     * 已知偏差一条：{@code EXPIREAT k 9223372036854775807} 参考实现因自身溢出回"已过期"
     * （TTL -2），本实现把秒→毫秒饱和处理，键留在"远未来"那一侧。
     */
    private Object handleExpireat(String[] args, boolean millis) {
        if (args.length != 3) return RespError.wrongNumberOfArguments(millis ? "PEXPIREAT" : "EXPIREAT");
        long timestamp;
        try { timestamp = longArg(args[2]); }
        catch (NumberFormatException e) { return RespError.notAnInteger(); }
        long now = System.currentTimeMillis();
        // 秒→毫秒：越界就贴到上界，别让乘法绕回负数（绕回去等于把一个未来时刻说过期了）
        long targetMs = millis ? timestamp
                : (timestamp > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : timestamp * 1000L);
        return RespInteger.of(store.pexpireDb(currentDb, args[1], targetMs - now) ? 1 : 0);
    }

    /**
     * MOVE —— 整键换库。三道判据的先后是实测定的：库号（含非数字，一律 {@code index out of
     * range}）→ 同库（{@code source and destination objects are the same}，键存不存在都报）→
     * 才轮到"源库里有没有、目标库里撞不撞名"。
     */
    private Object handleMove(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("MOVE");
        int target;
        try { target = intArg(args[2]); }
        catch (NumberFormatException e) { return RespError.of("ERR", "index out of range"); }
        if (target < 0 || target >= store.getDbCount()) return RespError.of("ERR", "index out of range");
        if (target == currentDb) return RespError.of("ERR", "source and destination objects are the same");
        return RespInteger.of(store.moveKeyToDb(currentDb, target, args[1]) ? 1 : 0);
    }

    private Object handleIncr(String[] args, long delta) {
        if (args.length != 2) return RespError.wrongNumberOfArguments(delta>0?"INCR":"DECR");
        try { return RespInteger.of(store.incrementDb(currentDb, args[1], delta)); }
        catch (IllegalArgumentException e) { return RespError.of("ERR", e.getMessage()); }
    }

    private Object handleIncrby(String[] args, long sign) {
        if (args.length != 3) return RespError.wrongNumberOfArguments(sign>0?"INCRBY":"DECRBY");
        try {
            long d = longArg(args[2]);
            return RespInteger.of(store.incrementDb(currentDb, args[1], sign > 0 ? d : Math.negateExact(d)));
        } catch (NumberFormatException e) {
            return RespError.notAnInteger();
        } catch (IllegalArgumentException | ArithmeticException e) {
            // 存储层把两件事都包成 IllegalArgumentException："键里存的不是整数"与"加完会溢出"。
            // 这一支以前只 catch 了 ArithmeticException，于是溢出那句穿到 handle() 的兜底 catch，
            // 客户端收到的是 -ERR internal error: increment or decrement would overflow
            // （对岸那句没有前缀，battery37 第 10 行）。
            return RespError.of("ERR", e.getMessage());
        }
    }

    private Object handleKeys(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("KEYS");
        String pattern = args[1];
        Set<String> allKeys = new LinkedHashSet<>(store.keysDb(currentDb, pattern));
        allKeys.addAll(store.getHashStore(currentDb).keys());
        allKeys.addAll(store.getListStore(currentDb).keys());
        allKeys.addAll(store.getSetStore(currentDb).keys());
        allKeys.addAll(store.getSortedSetStore(currentDb).keys());
        String regex = globToRegex(pattern);
        List<RespBulkString> result = new ArrayList<>();
        for (String k : allKeys) if (k.matches(regex)) result.add(RespBulkString.of(k));
        return RespArray.of(result.stream().map(k->(Object)k).toArray());
    }

    private Object handleType(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("TYPE");
        // 与 EXISTS、类型闸门共用 MemoryStore.typeOfDb 这一把尺，三者不会再互相打脸
        return RespSimpleString.of(store.typeOfDb(currentDb, args[1]).name().toLowerCase(Locale.ROOT));
    }

    private Object handleDbsize() { return RespInteger.of(store.dbsizeDb(currentDb)); }

    // ==================== Key 命令 ====================

    private Object handleRename(String[] args, boolean nx) {
        if (args.length != 3) return RespError.wrongNumberOfArguments(nx?"RENAMENX":"RENAME");
        String src = args[1], dst = args[2];
        if (src.equals(dst)) return RespError.of("ERR","source and destination objects are the same");
        if (nx && keyExists(dst)) return RespInteger.of(0);
        // 源键是什么类型、在不在，用 EXISTS / TYPE / 类型闸门那同一把尺 typeOfDb 判。
        // 以前第一道判据是 store.existsDb，而它只认 String 表里的键，集合键一律"不存在"，
        // 于是下面四条集合支路只能靠各自 store.exists 兜着，五条支路五把尺。
        MemoryStore.DataType type = store.typeOfDb(currentDb, src);
        if (type == MemoryStore.DataType.NONE) return RespError.noSuchKey();
        // RENAME 是"目标键整个被顶掉"，不是"把源键并进目标键"，跟目标键原来是什么类型无关。
        // 旧实现只有 String 支路走 setDb（经 MemoryStore.putDb 的 clearOtherTypes 抹掉旧值），
        // 四条集合支路是直接往 dst 上写：同类型时 dst 的旧成员原样留着（HLEN 从 1 变 2），
        // 跨类型时两张表各存一份 —— 1.3.5 类型闸门要消灭的"同一键名并存两种类型"，
        // 而 RENAME 正是它现成的生产者。判据实测在 RedisServerProtocolSemanticsTest。
        deleteEveryType(dst);
        switch (type) {
            case STRING: {
                byte[] val = store.getDb(currentDb, src);
                long ttlMs = store.pttlDb(currentDb, src);
                deleteEveryType(src);
                store.setDb(currentDb, dst, val);
                // 源键的过期时间跟着一起搬；集合键的 TTL 这条实现本身还不支持（沿 1.3.5 的边界）。
                if (ttlMs > 0) store.pexpireDb(currentDb, dst, ttlMs);
                break;
            }
            case HASH: {
                Map<String, byte[]> m = store.getHashStore(currentDb).hgetall(src);
                deleteEveryType(src);
                store.getHashStore(currentDb).hmset(dst, m);
                break;
            }
            case LIST: {
                List<byte[]> l = store.getListStore(currentDb).lrange(src, 0, -1);
                deleteEveryType(src);
                store.getListStore(currentDb).rpush(dst, l.toArray(new byte[0][]));
                break;
            }
            case SET: {
                List<byte[]> s = store.getSetStore(currentDb).smembers(src);
                deleteEveryType(src);
                store.getSetStore(currentDb).sadd(dst, s.toArray(new byte[0][]));
                break;
            }
            case ZSET: {
                List<byte[]> r = store.getSortedSetStore(currentDb).zrange(src, 0, -1, true);
                deleteEveryType(src);
                for (int i = 0; i < r.size(); i += 2) {
                    double score = parseScore(new String(r.get(i + 1), StandardCharsets.UTF_8));
                    store.getSortedSetStore(currentDb).zadd(dst, score, r.get(i));
                }
                break;
            }
            default: return RespError.noSuchKey();
        }
        return nx ? RespInteger.of(1) : RespSimpleString.of("OK");
    }

    private Object handleRandomkey() {
        List<String> all = new ArrayList<>(store.keysDb(currentDb, "*"));
        all.addAll(store.getHashStore(currentDb).keys()); all.addAll(store.getListStore(currentDb).keys());
        all.addAll(store.getSetStore(currentDb).keys()); all.addAll(store.getSortedSetStore(currentDb).keys());
        if (all.isEmpty()) return RespBulkString.nullBulkString();
        return RespBulkString.of(all.get(ThreadLocalRandom.current().nextInt(all.size())));
    }

    // ==================== Hash 命令 ====================

    private Object handleHset(String[] args) {
        if (args.length < 4 || (args.length-2)%2!=0) return RespError.wrongNumberOfArguments("HSET");
        long added = 0;
        for (int i = 2; i < args.length; i += 2) added += store.getHashStore(currentDb).hset(args[1], args[i], args[i+1].getBytes(StandardCharsets.UTF_8));
        return RespInteger.of(added);
    }

    private Object handleHget(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("HGET");
        byte[] v = store.getHashStore(currentDb).hget(args[1], args[2]);
        return v == null ? RespBulkString.nullBulkString() : RespBulkString.of(v);
    }

    private Object handleHdel(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("HDEL");
        return RespInteger.of(store.getHashStore(currentDb).hdel(args[1], Arrays.copyOfRange(args, 2, args.length)));
    }

    private Object handleHexists(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("HEXISTS");
        return RespInteger.of(store.getHashStore(currentDb).hexists(args[1], args[2]) ? 1 : 0);
    }

    private Object handleHgetall(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("HGETALL");
        Map<String,byte[]> m = store.getHashStore(currentDb).hgetall(args[1]);
        List<Object> r = new ArrayList<>(m.size()*2);
        for (Map.Entry<String,byte[]> e : m.entrySet()) { r.add(RespBulkString.of(e.getKey())); r.add(e.getValue()==null?RespBulkString.nullBulkString():RespBulkString.of(e.getValue())); }
        return RespArray.of(r);
    }

    private Object handleHkeys(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("HKEYS");
        List<String> k = store.getHashStore(currentDb).hkeys(args[1]); Object[] r = new Object[k.size()];
        for (int i = 0; i < k.size(); i++) r[i] = RespBulkString.of(k.get(i));
        return RespArray.of(r);
    }

    private Object handleHvals(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("HVALS");
        List<byte[]> v = store.getHashStore(currentDb).hvals(args[1]); Object[] r = new Object[v.size()];
        for (int i = 0; i < v.size(); i++) r[i] = v.get(i)==null?RespBulkString.nullBulkString():RespBulkString.of(v.get(i));
        return RespArray.of(r);
    }

    private Object handleHmget(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("HMGET");
        List<byte[]> v = store.getHashStore(currentDb).hmget(args[1], Arrays.copyOfRange(args,2,args.length));
        Object[] r = new Object[v.size()];
        for (int i = 0; i < v.size(); i++) r[i] = v.get(i)==null?RespBulkString.nullBulkString():RespBulkString.of(v.get(i));
        return RespArray.of(r);
    }

    private Object handleHmset(String[] args) {
        if (args.length < 4 || (args.length-2)%2!=0) return RespError.wrongNumberOfArguments("HMSET");
        Map<String,byte[]> f = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i += 2) f.put(args[i], args[i+1].getBytes(StandardCharsets.UTF_8));
        store.getHashStore(currentDb).hmset(args[1], f);
        return RespSimpleString.of("OK");
    }

    private Object handleHincrby(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("HINCRBY");
        try { return RespInteger.of(store.getHashStore(currentDb).hincrby(args[1], args[2], longArg(args[3]))); }
        catch (NumberFormatException e) { return RespError.notAnInteger(); }
        catch (IllegalArgumentException e) { return RespError.of("ERR", e.getMessage()); }
    }

    private Object handleHlen(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("HLEN");
        return RespInteger.of(store.getHashStore(currentDb).hlen(args[1]));
    }

    private Object handleHstrlen(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("HSTRLEN");
        // 数的是字节数不是字符数：实测 HSET h f 你好; HSTRLEN h f => 6。
        return RespInteger.of(store.getHashStore(currentDb).hstrlen(args[1], args[2]));
    }

    private Object handleHsetnx(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("HSETNX");
        return RespInteger.of(store.getHashStore(currentDb).hsetnx(args[1], args[2], args[3].getBytes(StandardCharsets.UTF_8)) ? 1 : 0);
    }

    @SuppressWarnings("unchecked")
    private Object handleHscan(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("HSCAN");
        String pattern = null;
        for (int i = 3; i < args.length; i++) if ("MATCH".equalsIgnoreCase(args[i]) && i+1<args.length) pattern = args[++i];
        Object[] sr = store.getHashStore(currentDb).hscan(args[1], args[2], pattern);
        Map<String,byte[]> m = (Map<String,byte[]>)sr[1];
        List<Object> fv = new ArrayList<>();
        for (Map.Entry<String,byte[]> e : m.entrySet()) { fv.add(RespBulkString.of(e.getKey())); fv.add(e.getValue()==null?RespBulkString.nullBulkString():RespBulkString.of(e.getValue())); }
        return RespArray.of(new Object[]{ RespBulkString.of((String)sr[0]), RespArray.of(fv) });
    }

    // ==================== List 命令 ====================

    private Object handleLpush(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("LPUSH");
        byte[][] v = new byte[args.length-2][]; for (int i=2;i<args.length;i++) v[i-2]=args[i].getBytes(StandardCharsets.UTF_8);
        return RespInteger.of(store.getListStore(currentDb).lpush(args[1], v));
    }

    private Object handleRpush(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("RPUSH");
        byte[][] v = new byte[args.length-2][]; for (int i=2;i<args.length;i++) v[i-2]=args[i].getBytes(StandardCharsets.UTF_8);
        return RespInteger.of(store.getListStore(currentDb).rpush(args[1], v));
    }

    private Object handlePushx(String[] args, boolean head) {
        if (args.length < 3) return RespError.wrongNumberOfArguments(head ? "LPUSHX" : "RPUSHX");
        byte[][] v = new byte[args.length-2][]; for (int i=2;i<args.length;i++) v[i-2]=args[i].getBytes(StandardCharsets.UTF_8);
        // 键不存在时返回 0 并且**不**把键建出来：Redis 的 lpushxCommand 直接答
        // serverObjectNull，连列表对象都不创建，所以这里不能用 computeIfAbsent 那套。
        ListStore ls = store.getListStore(currentDb);
        return RespInteger.of(head ? ls.lpushx(args[1], v) : ls.rpushx(args[1], v));
    }

    private Object handleLpop(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("LPOP");
        byte[] v = store.getListStore(currentDb).lpop(args[1]); return v==null?RespBulkString.nullBulkString():RespBulkString.of(v);
    }

    private Object handleRpop(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("RPOP");
        byte[] v = store.getListStore(currentDb).rpop(args[1]); return v==null?RespBulkString.nullBulkString():RespBulkString.of(v);
    }

    private Object handleLrange(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("LRANGE");
        try {
            List<byte[]> l = store.getListStore(currentDb).lrange(args[1], intArg(args[2]), intArg(args[3]));
            Object[] r = new Object[l.size()]; for (int i=0;i<l.size();i++) r[i]=l.get(i)==null?RespBulkString.nullBulkString():RespBulkString.of(l.get(i));
            return RespArray.of(r);
        } catch (NumberFormatException e) { return RespError.notAnInteger(); }
    }

    private Object handleLindex(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("LINDEX");
        try { byte[] v = store.getListStore(currentDb).lindex(args[1], intArg(args[2])); return v==null?RespBulkString.nullBulkString():RespBulkString.of(v); }
        catch (NumberFormatException e) { return RespError.notAnInteger(); }
    }

    private Object handleLlen(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("LLEN");
        return RespInteger.of(store.getListStore(currentDb).llen(args[1]));
    }

    private Object handleLset(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("LSET");
        try { store.getListStore(currentDb).lset(args[1], intArg(args[2]), args[3].getBytes(StandardCharsets.UTF_8)); return RespSimpleString.of("OK"); }
        catch (NumberFormatException e) { return RespError.notAnInteger(); }
        catch (IndexOutOfBoundsException e) { return RespError.of("ERR","index out of range"); }
    }

    private Object handleLinsert(String[] args) {
        if (args.length != 5) return RespError.wrongNumberOfArguments("LINSERT");
        boolean before = "BEFORE".equalsIgnoreCase(args[2]);
        if (!before && !"AFTER".equalsIgnoreCase(args[2])) return RespError.of("ERR","syntax error, use BEFORE or AFTER");
        return RespInteger.of(store.getListStore(currentDb).linsert(args[1], args[3].getBytes(StandardCharsets.UTF_8), args[4].getBytes(StandardCharsets.UTF_8), before));
    }

    private Object handleLrem(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("LREM");
        try { return RespInteger.of(store.getListStore(currentDb).lrem(args[1], intArg(args[2]), args[3].getBytes(StandardCharsets.UTF_8))); }
        catch (NumberFormatException e) { return RespError.notAnInteger(); }
    }

    private Object handleLtrim(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("LTRIM");
        try { store.getListStore(currentDb).ltrim(args[1], intArg(args[2]), intArg(args[3])); return RespSimpleString.of("OK"); }
        catch (NumberFormatException e) { return RespError.notAnInteger(); }
    }

    private Object handleRpoplpush(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("RPOPLPUSH");
        byte[] v = store.getListStore(currentDb).rpoplpush(args[1], args[2]);
        return v==null?RespBulkString.nullBulkString():RespBulkString.of(v);
    }

    // ==================== Set 命令 ====================

    private Object handleSadd(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("SADD");
        byte[][] m = new byte[args.length-2][]; for (int i=2;i<args.length;i++) m[i-2]=args[i].getBytes(StandardCharsets.UTF_8);
        return RespInteger.of(store.getSetStore(currentDb).sadd(args[1], m));
    }

    private Object handleSrem(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("SREM");
        byte[][] m = new byte[args.length-2][]; for (int i=2;i<args.length;i++) m[i-2]=args[i].getBytes(StandardCharsets.UTF_8);
        return RespInteger.of(store.getSetStore(currentDb).srem(args[1], m));
    }

    private Object handleSmembers(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("SMEMBERS");
        List<byte[]> m = store.getSetStore(currentDb).smembers(args[1]); Object[] r = new Object[m.size()];
        for (int i=0;i<m.size();i++) r[i]=RespBulkString.of(m.get(i));
        return RespArray.of(r);
    }

    private Object handleSismember(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("SISMEMBER");
        return RespInteger.of(store.getSetStore(currentDb).sismember(args[1], args[2].getBytes(StandardCharsets.UTF_8)) ? 1 : 0);
    }

    private Object handleScard(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("SCARD");
        return RespInteger.of(store.getSetStore(currentDb).scard(args[1]));
    }

    private Object handleSrandmember(String[] args) {
        if (args.length<2||args.length>3) return RespError.wrongNumberOfArguments("SRANDMEMBER");
        int count = args.length==3 ? intArg(args[2]) : 1;
        List<byte[]> m = store.getSetStore(currentDb).srandmember(args[1], count);
        if (args.length==2) { if (m.isEmpty()) return RespBulkString.nullBulkString(); return RespBulkString.of(m.get(0)); }
        Object[] r = new Object[m.size()]; for (int i=0;i<m.size();i++) r[i]=RespBulkString.of(m.get(i));
        return RespArray.of(r);
    }

    private Object handleSinter(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("SINTER");
        List<byte[]> m = store.getSetStore(currentDb).sinter(Arrays.copyOfRange(args,1,args.length));
        Object[] r = new Object[m.size()]; for (int i=0;i<m.size();i++) r[i]=RespBulkString.of(m.get(i));
        return RespArray.of(r);
    }

    private Object handleSunion(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("SUNION");
        List<byte[]> m = store.getSetStore(currentDb).sunion(Arrays.copyOfRange(args,1,args.length));
        Object[] r = new Object[m.size()]; for (int i=0;i<m.size();i++) r[i]=RespBulkString.of(m.get(i));
        return RespArray.of(r);
    }

    private Object handleSdiff(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("SDIFF");
        List<byte[]> m = store.getSetStore(currentDb).sdiff(Arrays.copyOfRange(args,1,args.length));
        Object[] r = new Object[m.size()]; for (int i=0;i<m.size();i++) r[i]=RespBulkString.of(m.get(i));
        return RespArray.of(r);
    }

    private Object handleSmove(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("SMOVE");
        return RespInteger.of(store.getSetStore(currentDb).smove(args[1], args[2], args[3].getBytes(StandardCharsets.UTF_8)) ? 1 : 0);
    }

    // ==================== Sorted Set 命令 ====================

    /**
     * ZADD 的完整文法：{@code ZADD key [NX|XX] [GT|LT] [CH] [INCR] score member [score member ...]}。
     * <p>
     * 1.3.5 及之前只认裸的 {@code key score member ...}，任何修饰位都吃 arity 错。这一版按
     * 250 实测的形状实现，四条纹路各自有样本：
     * <ul>
     *   <li>修饰位只能在<b>最前面连续一段</b>出现，落在数对之后就是 {@code syntax error}
     *       （实测 {@code ZADD b6:z 1 a NX}）；大小写都认（{@code ch nx}）。</li>
     *   <li>计数默认只数新增（{@code XX 3 a} 改了分数仍回 0），带 {@code CH} 才把改动算上。</li>
     *   <li>{@code INCR} 的分数栏是增量，回的是 bulk 而不是整数；被 NX/XX 挡下回 {@code nil}；
     *       只许带一对（实测 {@code INCR 1 a 2 b} → {@code INCR option supports a single
     *       increment-element pair}），与 GT/LT 同时出现回 {@code syntax error}。</li>
     *   <li>成员名不做浮点解释（实测 {@code ZADD b4:zz 1 nan} → 1，成员就叫 "nan"），
     *       但分数栏里 {@code nan} 死在解析、{@code inf} 活着进库（{@code ZSCORE} 回 {@code inf}）。</li>
     * </ul>
     * {@code GT}/{@code LT} 本实现按 Redis 6.2 起的语义支持（既不凭空建成员，也不把分数改到
     * 不更优），而 4.0.9 回 {@code syntax error} —— 这是有意超出对岸版本的一条，见 README 偏差清单。
     * {@code NX} 与 {@code GT}/{@code LT} 同时出现是 {@code syntax error}（两条问的是不相交的
     * 两件事，合起来没有任何成员能满足）。
     */
    private Object handleZadd(String[] args) {
        if (args.length < 4) return RespError.wrongNumberOfArguments("ZADD");
        int flags = 0;
        boolean incr = false;
        int i = 2;
        while (i < args.length) {
            String token = args[i];
            if ("NX".equalsIgnoreCase(token)) flags |= SortedSetStore.ZADD_NX;
            else if ("XX".equalsIgnoreCase(token)) flags |= SortedSetStore.ZADD_XX;
            else if ("CH".equalsIgnoreCase(token)) flags |= SortedSetStore.ZADD_CH;
            else if ("GT".equalsIgnoreCase(token)) flags |= SortedSetStore.ZADD_GT;
            else if ("LT".equalsIgnoreCase(token)) flags |= SortedSetStore.ZADD_LT;
            else if ("INCR".equalsIgnoreCase(token)) incr = true;
            else break;
            i++;
        }
        int rest = args.length - i;
        if (rest == 0 || rest % 2 != 0) return RespError.syntaxError();
        // 判据先后也是量出来的：{@code ZADD k NX XX} 回的是 syntax error（数对不够那一档在前），
        // {@code ZADD k NX XX abc m} 才回互斥；而 {@code NX XX INCR 1 a 2 b} 仍回互斥，
        // 说明互斥排在"INCR 只许一对"之前。
        if ((flags & SortedSetStore.ZADD_NX) != 0 && (flags & SortedSetStore.ZADD_XX) != 0) {
            return RespError.of("ERR", "XX and NX options at the same time are not compatible");
        }
        if ((flags & SortedSetStore.ZADD_GT) != 0 && (flags & SortedSetStore.ZADD_LT) != 0) {
            return RespError.of("ERR", "GT and LT options at the same time are not compatible");
        }
        // NX 问的是"只许新增"，GT/LT 问的是"只许把分数改到更优"，两条一起出现时没有任何成员
        // 能满足，所以它俩是互斥的（实测 4.0.9 那边 {@code ZADD k NX GT 1 a} 回 syntax error，
        // 现代 Redis 回的是各自那句 —— 都拒。这里回对岸那句）。XX + GT 是合法组合，别一并挡掉。
        if ((flags & SortedSetStore.ZADD_NX) != 0
                && (flags & (SortedSetStore.ZADD_GT | SortedSetStore.ZADD_LT)) != 0) {
            return RespError.syntaxError();
        }
        if (incr && rest != 2) {
            return RespError.of("ERR", "INCR option supports a single increment-element pair");
        }
        if (incr && (flags & (SortedSetStore.ZADD_GT | SortedSetStore.ZADD_LT)) != 0) {
            return RespError.syntaxError();
        }
        try {
            if (incr) {
                double increment = parseScore(args[i]);
                RespError blocked = wrongTypeAfterParse(MemoryStore.DataType.ZSET, args[1]);
                if (blocked != null) return blocked;
                Double score = store.getSortedSetStore(currentDb).zaddIncr(args[1], increment,
                        args[i + 1].getBytes(StandardCharsets.UTF_8), flags);
                return score == null ? RespBulkString.nullBulkString()
                        : RespBulkString.of(RedisDoubleFormat.format(score));
            }
            int pairs = rest / 2;
            double[] scores = new double[pairs];
            byte[][] members = new byte[pairs][];
            for (int k = 0; k < pairs; k++) {
                scores[k] = parseScore(args[i + 2 * k]);
                members[k] = args[i + 2 * k + 1].getBytes(StandardCharsets.UTF_8);
            }
            // 整串数对先全部解析完，再判类型：实测 {@code ZADD <string 键> 1 a abc b} 回的是
            // {@code value is not a valid float}（ref17），说明对岸不会因为第一对合法就先去碰键。
            RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.ZSET, args[1]);
            if (conflict != null) return conflict;
            return RespInteger.of(store.getSortedSetStore(currentDb).zadd(args[1], scores, members, flags));
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not a valid float");
        } catch (IllegalArgumentException e) {
            return RespError.of("ERR", e.getMessage());
        }
    }

    /**
     * ZUNIONSTORE / ZINTERSTORE —— {@code STORE} 一族里唯一带"键数"的两种，所以类型闸门
     * 那张表帮不上忙（{@code args[2]} 是键数不是键名），源键的 WRONGTYPE 在这里自己判。
     * <p>
     * 实测的形状：
     * <ul>
     *   <li>键数不是整数 → {@code value is not an integer or out of range}；是 0 或负数 →
     *       {@code at least 1 input key is needed for ZUNIONSTORE/ZINTERSTORE}；
     *       而 {@code ZUNIONSTORE d abc}（token 不够）先吃 arity 错。</li>
     *   <li>{@code WEIGHTS} 少给一个 → {@code syntax error}；权重不是浮点 →
     *       {@code weight value is not a float}（注意这句是这一族独有的，不是通用的
     *       {@code value is not a valid float}）；{@code nan} 和 {@code 1e4000} 都算非法，
     *       {@code inf} 合法。</li>
     *   <li>{@code AGGREGATE} 只认 SUM/MIN/MAX，给 AVG 是 {@code syntax error}；尾巴上不认识的
     *        token（{@code WITHSCORES}）同样是 {@code syntax error}。</li>
     *   <li>目标键上原来挂的别的类型会被整个顶掉（{@code SET b4:str2 hello} 之后
     *       {@code ZUNIONSTORE b4:str2 1 b4:src1} → 2、TYPE 变 zset），但<b>不能</b>连 zset
     *       那份一起清 —— 目标键常常同时是源键（实测 {@code ZUNIONSTORE b4:src2 1 b4:src2
     *       WEIGHTS 2} 把分数翻倍，是对的）。</li>
     *   <li>算出来是空 → 目标键不留（{@code ZUNIONSTORE b4:dst7 1 nosuch} → 0 而
     *       {@code EXISTS b4:dst7} → 0），缺源的并集当空集处理。</li>
     * </ul>
     */
    private Object handleZstore(String[] args, boolean intersect) {
        String name = intersect ? "ZINTERSTORE" : "ZUNIONSTORE";
        if (args.length < 4) return RespError.wrongNumberOfArguments(name);
        int numKeys;
        try { numKeys = intArg(args[2]); }
        catch (NumberFormatException e) { return RespError.notAnInteger(); }
        if (numKeys < 1) {
            return RespError.of("ERR", "at least 1 input key is needed for ZUNIONSTORE/ZINTERSTORE");
        }
        // 键数比给出来的参数还多：参考实现是照着 argv 直接取（越界即崩），这里当语法错处理。
        if (args.length < 3 + numKeys) return RespError.syntaxError();
        for (int i = 0; i < numKeys; i++) {
            MemoryStore.DataType actual = store.typeOfDb(currentDb, args[3 + i]);
            if (actual != MemoryStore.DataType.NONE && actual != MemoryStore.DataType.ZSET) {
                return RespError.wrongType("Operation against a key holding the wrong kind of value");
            }
        }
        double[] weights = null;
        String aggregate = "SUM";
        int i = 3 + numKeys;
        while (i < args.length) {
            if ("WEIGHTS".equalsIgnoreCase(args[i])) {
                i++;
                weights = new double[numKeys];
                for (int k = 0; k < numKeys; k++) {
                    if (i >= args.length) return RespError.syntaxError();
                    try { weights[k] = parseScore(args[i++]); }
                    catch (NumberFormatException e) { return RespError.of("ERR", "weight value is not a float"); }
                }
            } else if ("AGGREGATE".equalsIgnoreCase(args[i])) {
                i++;
                if (i >= args.length) return RespError.syntaxError();
                String candidate = args[i++];
                if (!"SUM".equalsIgnoreCase(candidate) && !"MIN".equalsIgnoreCase(candidate)
                        && !"MAX".equalsIgnoreCase(candidate)) {
                    return RespError.syntaxError();
                }
                aggregate = candidate;
            } else {
                return RespError.syntaxError();
            }
        }
        String[] keys = Arrays.copyOfRange(args, 3, 3 + numKeys);
        String dest = args[1];
        // 目标键上别的类型要顶掉，zset 那一份留给存储层自己覆盖
        store.clearOtherTypes(currentDb, dest, MemoryStore.DataType.ZSET);
        SortedSetStore zset = store.getSortedSetStore(currentDb);
        long count = intersect ? zset.zinterstore(dest, keys, weights, aggregate)
                : zset.zunionstore(dest, keys, weights, aggregate);
        return RespInteger.of(count);
    }


    private Object handleZrem(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("ZREM");
        byte[][] m = new byte[args.length-2][]; for (int i=2;i<args.length;i++) m[i-2]=args[i].getBytes(StandardCharsets.UTF_8);
        return RespInteger.of(store.getSortedSetStore(currentDb).zrem(args[1], m));
    }

    private Object handleZscore(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("ZSCORE");
        Double s = store.getSortedSetStore(currentDb).zscore(args[1], args[2].getBytes(StandardCharsets.UTF_8));
        return s==null ? RespBulkString.nullBulkString() : RespBulkString.of(RedisDoubleFormat.format(s));
    }

    private Object handleZrank(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("ZRANK");
        return RespInteger.of(store.getSortedSetStore(currentDb).zrank(args[1], args[2].getBytes(StandardCharsets.UTF_8)));
    }

    private Object handleZrevrank(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("ZREVRANK");
        return RespInteger.of(store.getSortedSetStore(currentDb).zrevrank(args[1], args[2].getBytes(StandardCharsets.UTF_8)));
    }

    private Object handleZcard(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("ZCARD");
        return RespInteger.of(store.getSortedSetStore(currentDb).zcard(args[1]));
    }

    private Object handleZcount(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("ZCOUNT");
        try { return RespInteger.of(store.getSortedSetStore(currentDb).zcount(args[1], parseScore(args[2]), parseScore(args[3]))); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not a valid float"); }
    }

    private Object handleZrange(String[] args, boolean reverse) {
        if (args.length < 4) return RespError.wrongNumberOfArguments(reverse?"ZREVRANGE":"ZRANGE");
        try {
            boolean ws = args.length==5 && "WITHSCORES".equalsIgnoreCase(args[4]);
            List<byte[]> r = reverse ? store.getSortedSetStore(currentDb).zrevrange(args[1], longArg(args[2]), longArg(args[3]), ws)
                    : store.getSortedSetStore(currentDb).zrange(args[1], longArg(args[2]), longArg(args[3]), ws);
            return toRespArray(r);
        } catch (NumberFormatException e) { return RespError.notAnInteger(); }
    }

    private Object handleZrangebyscore(String[] args, boolean reverse) {
        if (args.length < 4) return RespError.wrongNumberOfArguments(reverse?"ZREVRANGEBYSCORE":"ZRANGEBYSCORE");
        try {
            double min = parseScore(args[2]), max = parseScore(args[3]);
            boolean ws=false; int offset=0, count=-1;
            for (int i=4;i<args.length;i++) {
                if ("WITHSCORES".equalsIgnoreCase(args[i])) ws=true;
                else if ("LIMIT".equalsIgnoreCase(args[i])&&i+2<args.length) {
                    // 这一支不能搭外层那个 try 的便车：它 catch 的是 NumberFormatException，
                    // 而那句回的是 "value is not a valid float" —— 分数栏确实是浮点，
                    // LIMIT 的两个下标不是（实测 ZRANGEBYSCORE k -inf +inf LIMIT abc 1 回的是
                    // 整数那句）。
                    Integer off = RedisIntegerFormat.parseAsInt(args[i+1]);
                    Integer cnt = RedisIntegerFormat.parseAsInt(args[i+2]);
                    if (off == null || cnt == null) return RespError.notAnInteger();
                    offset = off; count = cnt; i += 2;
                }
            }
            List<byte[]> r = reverse ? store.getSortedSetStore(currentDb).zrevrangebyscore(args[1], max, min, ws, offset, count)
                    : store.getSortedSetStore(currentDb).zrangebyscore(args[1], min, max, ws, offset, count);
            return toRespArray(r);
        } catch (NumberFormatException e) { return RespError.of("ERR","value is not a valid float"); }
    }

    private Object handleZincrby(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("ZINCRBY");
        try {
            double increment = parseScore(args[2]);
            // 分数先解析、类型后判：实测 ZINCRBY <string 键> nan m → value is not a valid float，
            // 而 ZINCRBY <string 键> 1 m → WRONGTYPE（ref17）。
            RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.ZSET, args[1]);
            if (conflict != null) return conflict;
            double ns = store.getSortedSetStore(currentDb).zincrby(args[1], increment,
                    args[3].getBytes(StandardCharsets.UTF_8));
            return RespBulkString.of(RedisDoubleFormat.format(ns));
        } catch (NumberFormatException e) { return RespError.of("ERR","value is not a valid float"); }
        // +inf 加到 -inf 成员上得到 NaN：Redis 在算完分数后才判 isnan(newscore) 并答
        // "resulting score is not a number (NaN)"，此时集合与成员都还没动过。
        catch (IllegalArgumentException e) { return RespError.of("ERR", e.getMessage()); }
    }

    // ==================== 事务命令 ====================

    private Object handleMulti() { try { transactionManager.multi(transactionContext); return RespSimpleString.of("OK"); } catch (IllegalStateException e) { return RespError.of("ERR",e.getMessage()); } }
    private volatile boolean executingTransaction = false;

    private Object handleExec() {
        try {
            // 设置执行标志，让 handle() 跳过入队逻辑
            executingTransaction = true;
            try {
                Object r = transactionManager.exec(transactionContext, a -> {
                    String[] s = new String[a.length];
                    for (int i = 0; i < a.length; i++) s[i] = a[i] == null ? null : a[i].toString();
                    return handle(RespArray.of(Arrays.stream(s).map(x -> (Object) RespBulkString.of(x)).toArray()));
                });
                // exec() 只在一种情况下回 null：WATCH 的键被别的连接改过。Redis 对这种中止
                // 回的是空多批量 *-1（客户端按"nil = 没提交"判断），不是 -EXECABORT ——
                // 后者专用于"入队阶段就有语法/参数错误"，而我们根本不记那种错误。
                return r == null ? RespArray.nullArray() : r;
            } finally {
                executingTransaction = false;
            }
        } catch (IllegalStateException e) {
            return RespError.of("ERR", e.getMessage());
        }
    }
    private Object handleDiscard() { try { transactionManager.discard(transactionContext); return RespSimpleString.of("OK"); } catch (IllegalStateException e) { return RespError.of("ERR",e.getMessage()); } }
    private Object handleWatch(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("WATCH");
        // 快照 WATCH 当时所在的库：Redis 的 WATCH 键属于当前库，中途 SELECT 到别的库
        // 再用同名键的当前版本比对，等于拿另一个库的写入中止这个库的事务。
        final int watchDb = currentDb;
        try { transactionManager.watch(transactionContext, Arrays.copyOfRange(args,1,args.length), k -> store.getKeyVersion(watchDb, k)); return RespSimpleString.of("OK"); } catch (IllegalStateException e) { return RespError.of("ERR",e.getMessage()); }
    }
    private Object handleUnwatch() { transactionManager.unwatch(transactionContext); return RespSimpleString.of("OK"); }

    // ==================== Pub/Sub 命令 ====================

    private Object handleSubscribe(String[] args) {
        if (args.length<2) return RespError.wrongNumberOfArguments("SUBSCRIBE");
        PubSubManager pubSub = pubSub();
        if (pubSub==null) return RespError.of("ERR","Pub/Sub not configured");
        String[] ch = Arrays.copyOfRange(args,1,args.length);
        int already = pubSub.channelCount(channelContext) + pubSub.patternCount(channelContext);
        pubSub.subscribe(channelContext, ch);
        Object[] r = new Object[ch.length]; for (int i=0;i<ch.length;i++) r[i]=RespArray.of(RespBulkString.of("subscribe"),RespBulkString.of(ch[i]),RespInteger.of(already + i + 1));
        return ch.length==1 ? r[0] : RespArray.of(r);
    }
    private Object handleUnsubscribe(String[] args) {
        PubSubManager pubSub = pubSub();
        if (pubSub==null) return RespSimpleString.of("OK");
        String[] ch = args.length<2 ? new String[0] : Arrays.copyOfRange(args,1,args.length);
        int before = pubSub.channelCount(channelContext) + pubSub.patternCount(channelContext);
        pubSub.unsubscribe(channelContext, ch);
        if (ch.length==0) return RespArray.empty();
        Object[] r = new Object[ch.length]; for (int i=0;i<ch.length;i++) r[i]=RespArray.of(RespBulkString.of("unsubscribe"),RespBulkString.of(ch[i]),RespInteger.of(Math.max(0, before - (i + 1))));
        return ch.length==1 ? r[0] : RespArray.of(r);
    }
    private Object handlePsubscribe(String[] args) {
        if (args.length<2) return RespError.wrongNumberOfArguments("PSUBSCRIBE");
        PubSubManager pubSub = pubSub();
        if (pubSub==null) return RespError.of("ERR","Pub/Sub not configured");
        String[] p = Arrays.copyOfRange(args,1,args.length);
        int already = pubSub.channelCount(channelContext) + pubSub.patternCount(channelContext);
        pubSub.psubscribe(channelContext, p);
        Object[] r = new Object[p.length]; for (int i=0;i<p.length;i++) r[i]=RespArray.of(RespBulkString.of("psubscribe"),RespBulkString.of(p[i]),RespInteger.of(already + i + 1));
        return p.length==1 ? r[0] : RespArray.of(r);
    }
    private Object handlePunsubscribe(String[] args) {
        PubSubManager pubSub = pubSub();
        if (pubSub==null) return RespSimpleString.of("OK");
        String[] p = args.length<2 ? new String[0] : Arrays.copyOfRange(args,1,args.length);
        int before = pubSub.channelCount(channelContext) + pubSub.patternCount(channelContext);
        pubSub.punsubscribe(channelContext, p);
        if (p.length==0) return RespArray.empty();
        Object[] r = new Object[p.length]; for (int i=0;i<p.length;i++) r[i]=RespArray.of(RespBulkString.of("punsubscribe"),RespBulkString.of(p[i]),RespInteger.of(Math.max(0, before - (i + 1))));
        return p.length==1 ? r[0] : RespArray.of(r);
    }
    private Object handlePublish(String[] args) {
        if (args.length!=3) return RespError.wrongNumberOfArguments("PUBLISH");
        PubSubManager pubSub = pubSub();
        return RespInteger.of(pubSub==null ? 0 : pubSub.publish(args[1], args[2]));
    }
    private Object handlePubsub(String[] args) {
        if (args.length<2) return RespError.wrongNumberOfArguments("PUBSUB");
        PubSubManager pubSub = pubSub();
        if (pubSub==null) return RespError.of("ERR","Pub/Sub not configured");
        switch (args[1].toUpperCase(Locale.ROOT)) {
            case "CHANNELS": { String p=args.length>2?args[2]:null; Set<String> c=pubSub.getChannels(p); Object[] r=new Object[c.size()]; int i=0; for (String s:c) r[i++]=RespBulkString.of(s); return RespArray.of(r); }
            case "NUMSUB": { String[] ch=args.length>2?Arrays.copyOfRange(args,2,args.length):new String[0]; Map<String,Integer> n=pubSub.getNumSub(ch); List<Object> r=new ArrayList<>(); for (Map.Entry<String,Integer> e:n.entrySet()) { r.add(RespBulkString.of(e.getKey())); r.add(RespInteger.of(e.getValue())); } return RespArray.of(r); }
            case "NUMPAT": return RespInteger.of(pubSub.getNumPat());
            default: return RespError.syntaxError();
        }
    }

    // ==================== 管理命令 ====================

    private Object handleSlowlog(String[] args) {
        if (args.length<2) return RespError.wrongNumberOfArguments("SLOWLOG");
        if (slowLog()==null) return RespError.of("ERR","SlowLog not configured");
        switch (args[1].toUpperCase(Locale.ROOT)) {
            case "GET": { int c=args.length>2?intArg(args[2]):10; List<SlowLog.SlowLogEntry> e=slowLog().get(c); Object[] r=new Object[e.size()]; for(int i=0;i<e.size();i++) { SlowLog.SlowLogEntry en=e.get(i); r[i]=RespArray.of(RespInteger.of(en.getId()),RespInteger.of(en.getTimestampNanos()/1000),RespInteger.of(en.getDurationNanos()/1000),toRespArray(en.getArgs())); } return RespArray.of(r); }
            case "LEN": return RespInteger.of(slowLog().len());
            case "RESET": slowLog().reset(); return RespSimpleString.of("OK");
            default: return RespError.syntaxError();
        }
    }

    /**
     * SAVE — 同步写一份 RDB 快照。
     * <p>
     * 1.3.3 及之前 SAVE / BGSAVE 都直接复用 handleSet 风格的假成功（返回 OK 但从不落盘），
     * 未配置 dataDir 时也照样回 OK。这里把两种情况分开：没有快照目标就如实报错。
     */
    private Object handleSave() {
        if (rdb() == null) {
            return RespError.of("ERR", "SAVE is not supported: no data directory configured");
        }
        try {
            rdb().save();
            return RespSimpleString.of("OK");
        } catch (Exception e) {
            logger.error("SAVE failed: {}", e.getMessage(), e);
            return RespError.of("ERR", "save failed: " + e.getMessage());
        }
    }

    /** BGSAVE — 受理后台快照；已有快照在跑时如实拒绝，与 Redis 行为一致。 */
    private Object handleBgsave() {
        if (rdb() == null) {
            return RespError.of("ERR", "BGSAVE is not supported: no data directory configured");
        }
        if (!rdb().saveAsync()) {
            return RespError.of("ERR", "Background save already in progress. Please wait");
        }
        return RespSimpleString.of("Background saving started");
    }

    /** LASTSAVE — 最近一次成功快照的 Unix 秒；从未成功过则为 0，不再拿当前时间冒充。 */
    private Object handleLastsave() {
        return RespInteger.of(rdb() == null ? 0L : rdb().getLastSaveTime());
    }

    private Object handleFlushdb() { store.flushDb(currentDb); return RespSimpleString.of("OK"); }

    private Object handleFlushall() { store.flushAll(); return RespSimpleString.of("OK"); }

    private Object handleInfo(String[] args) {
        String sec = args.length > 1 ? args[1].toUpperCase(Locale.ROOT) : null;
        StringBuilder sb = new StringBuilder();
        if (sec == null || "SERVER".equals(sec)) {
            sb.append("# Server\r\n");
            sb.append("z-cache_version:").append(serverVersion()).append("\r\n");
            sb.append("redis_compatible:resp2\r\n");
            sb.append("os:").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.version")).append("\r\n");
            sb.append("java_version:").append(System.getProperty("java.version")).append("\r\n");
            sb.append("uptime_in_seconds:").append((System.currentTimeMillis() - store.getStartTime()) / 1000).append("\r\n");
            sb.append("tcp_port:").append(localPort).append("\r\n");
            sb.append("\r\n");
        }
        if (sec == null || "CLIENTS".equals(sec)) {
            sb.append("# Clients\r\n");
            // 真实连接数：由 RedisServerHandler 在 channelActive/Inactive 增减，不再用 "0 就当 1" 兜底
            sb.append("connected_clients:").append(store.getConnectedClients()).append("\r\n");
            sb.append("blocked_clients:0\r\n");
            sb.append("max_clients:10000\r\n");
            sb.append("\r\n");
        }
        if (sec == null || "MEMORY".equals(sec)) {
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long max = rt.maxMemory();
            sb.append("# Memory\r\n");
            sb.append("used_memory:").append(used).append("\r\n");
            sb.append("used_memory_human:").append(fmtBytes(used)).append("\r\n");
            sb.append("max_memory:").append(max).append("\r\n");
            sb.append("max_memory_human:").append(fmtBytes(max)).append("\r\n");
            sb.append("mem_fragmentation_ratio:").append(String.format(java.util.Locale.ROOT, "%.2f", (double) used / rt.totalMemory())).append("\r\n");
            sb.append("max_entries:").append(store.getMaxEntries()).append("\r\n");
            sb.append("\r\n");
        }
        if (sec == null || "STATS".equals(sec)) {
            sb.append("# Stats\r\n");
            sb.append("total_connections_received:").append(store.getTotalConnections()).append("\r\n");
            sb.append("total_commands_processed:").append(store.getTotalCommands()).append("\r\n");
            sb.append("keyspace_hits:").append(store.getHits()).append("\r\n");
            sb.append("keyspace_misses:").append(store.getMisses()).append("\r\n");
            sb.append("hit_rate:").append(String.format(java.util.Locale.ROOT, "%.6f", hitRate())).append("\r\n");
            sb.append("evicted_keys:").append(store.getEvictions()).append("\r\n");
            sb.append("\r\n");
        }
        if (sec == null || "KEYSPACE".equals(sec)) {
            sb.append("# Keyspace\r\n");
            for (int i = 0; i < store.getDbCount(); i++) {
                long keys = store.getHashStore(i).dbsize() + store.getListStore(i).dbsize()
                        + store.getSetStore(i).dbsize() + store.getSortedSetStore(i).dbsize()
                        + store.getStringStore(i).size();
                if (keys > 0) {
                    sb.append("db").append(i).append(":keys=").append(keys).append("\r\n");
                }
            }
        }
        if (sec == null || "REPLICATION".equals(sec)) {
            sb.append("# Replication\r\n");
            sb.append("role:standalone\r\n");
            sb.append("\r\n");
        }
        return RespBulkString.of(sb.toString());
    }

    // ==================== CLIENT / DEBUG / MONITOR / RESET ====================

    /**
     * CLIENT 命令：连接管理（LIST / GETNAME / SETNAME / ID / KILL / INFO / NO-EVICT）。
     */
    private Object handleClient(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("CLIENT");
        if (channelContext == null) {
            // 没有连接就谈不上"这条连接的信息"；以前会一路走到 channelContext.channel()
            // 抛 NPE，被 handle() 兜成 -ERR internal error: null。
            return RespError.of("ERR", "CLIENT is only available on a connected session");
        }
        switch (args[1].toUpperCase(Locale.ROOT)) {
            case "LIST": {
                // 列出所有活着的连接。以前这里只拼自己一条，等于"连接列表"里永远只有一个元素，
                // 而且 sub=/psub= 恒为 0 —— 因为订阅中的连接根本走不到 CLIENT（被 pubsub 闸门挡了），
                // 能从 socket 看到这些字段的只有别的连接。现在由旁观者来读，才真的量得到。
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<ChannelHandlerContext, CommandHandler> entry : connections().entrySet()) {
                    CommandHandler peer = entry.getValue();
                    if (peer.channelContext != entry.getKey()) {
                        continue; // 已断开的陈旧条目
                    }
                    sb.append(peer.clientListLine());
                }
                return RespBulkString.of(sb.toString());
            }
            case "GETNAME": {
                return clientName != null ? RespBulkString.of(clientName) : RespBulkString.nullBulkString();
            }
            case "SETNAME": {
                if (args.length < 3) return RespError.wrongNumberOfArguments("CLIENT SETNAME");
                this.clientName = args[2];
                return RespSimpleString.of("OK");
            }
            case "ID": {
                return RespInteger.of(clientId(channelContext));
            }
            case "KILL": {
                // 以前的实现是"收下参数、回一个 +OK、什么都不做"：客户端据此认为对端连接已被切断，
                // 而那条连接好端端地活着。有了 connections 登记表之后才是真杀。
                if (args.length < 3) return RespError.wrongNumberOfArguments("CLIENT KILL");
                ChannelHandlerContext target = findClientForKill(args);
                if (target == null) return RespError.of("ERR", "No such client");
                target.close();
                return RespSimpleString.of("OK");
            }
            case "INFO": {
                return RespBulkString.of(clientListLine());
            }
            case "NO-EVICT": {
                // 参数照常校验，但语义不认：我们没有"这条连接豁免淘汰"这条通道。
                if (args.length < 3 || !("ON".equalsIgnoreCase(args[2]) || "OFF".equalsIgnoreCase(args[2]))) {
                    return RespError.syntaxError();
                }
                return RespError.of("ERR", "CLIENT NO-EVICT is not supported by z-cache");
            }
            default:
                return RespError.syntaxError();
        }
    }

    /** 一条 CLIENT LIST 记录：Redis 的字段顺序，值全部来自这条连接自己的真实状态。 */
    private String clientListLine() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(clientIdentityFields());
        sb.append(" age=").append(Math.max(0L, (now - connectedAtMs) / 1000L));
        sb.append(" idle=").append(Math.max(0L, (now - lastCommandMs) / 1000L));
        sb.append(" flags=N");
        sb.append(" multi=").append(transactionContext.isInTransaction()
                ? transactionContext.getCommands().size() : -1);
        sb.append(" cmd=").append(lastCommand.toLowerCase(Locale.ROOT));
        return sb.append("\r\n").toString();
    }

    /**
     * CLIENT LIST / CLIENT INFO 共用的那几列：订阅数取真值，不再固定写 {@code sub=0 psub=0}；
     * 连接已经没了就如实返回空串，而不是抛 NPE 被兜成 {@code internal error: null}。
     */
    private String clientIdentityFields() {
        ChannelHandlerContext ctx = this.channelContext;
        if (ctx == null) {
            return "";
        }
        PubSubManager pubSub = pubSub();
        int sub = pubSub == null ? 0 : pubSub.channelCount(ctx);
        int psub = pubSub == null ? 0 : pubSub.patternCount(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(clientId(ctx));
        sb.append(" addr=").append(ctx.channel().remoteAddress());
        sb.append(" laddr=").append(ctx.channel().localAddress());
        sb.append(" name=").append(clientName != null ? clientName : "");
        sb.append(" db=").append(currentDb);
        sb.append(" sub=").append(sub);
        sb.append(" psub=").append(psub);
        return sb.toString();
    }

    /** CLIENT ID / CLIENT KILL ID 用的连接标识。 */
    private static long clientId(ChannelHandlerContext ctx) {
        return ctx.channel().hashCode() & 0x7FFFFFFFL;
    }

    /**
     * 按 {@code CLIENT KILL} 的参数找出目标连接：支持 {@code ID <id>}、{@code LADDR ip:port}、
     * {@code ip:port}（对端地址）与 {@code <name>}（CLIENT SETNAME 起的名字）四种形式。
     *
     * @return 目标连接的 ctx；找不到返回 null，由调用方如实报错
     */
    private ChannelHandlerContext findClientForKill(String[] args) {
        if ("ID".equalsIgnoreCase(args[2])) {
            if (args.length < 4) return null;
            long id;
            try {
                id = longArg(args[3]);
            } catch (NumberFormatException e) {
                return null;
            }
            for (java.util.Map.Entry<ChannelHandlerContext, CommandHandler> entry : connections().entrySet()) {
                if (entry.getValue().channelContext == entry.getKey() && clientId(entry.getKey()) == id) {
                    return entry.getKey();
                }
            }
            return null;
        }
        final String needle;
        if ("LADDR".equalsIgnoreCase(args[2]) || "ADDR".equalsIgnoreCase(args[2])) {
            if (args.length < 4) return null;
            needle = args[3];
        } else if (args.length == 3) {
            needle = args[2];   // 旧式：ip:port 或对端地址
        } else {
            return null;
        }
        ChannelHandlerContext matched = null;
        for (java.util.Map.Entry<ChannelHandlerContext, CommandHandler> entry : connections().entrySet()) {
            CommandHandler peer = entry.getValue();
            if (peer.channelContext != entry.getKey()) {
                continue;
            }
            String name = peer.clientName;
            if (needle.equals(addressForm(entry.getKey().channel().remoteAddress()))
                    || needle.equals(addressForm(entry.getKey().channel().localAddress()))
                    || (name != null && name.equals(needle))) {
                matched = entry.getKey();
                break;
            }
        }
        return matched;
    }

    /**
     * 连接地址的两种写法都认：客户端敲的是 {@code 127.0.0.1:6379}，而
     * {@code InetSocketAddress#toString} 给的是 {@code /127.0.0.1:6379}，只比后者会杀不到人。
     */
    private static String addressForm(java.net.SocketAddress address) {
        if (address == null) {
            return "null";
        }
        if (address instanceof java.net.InetSocketAddress) {
            java.net.InetSocketAddress inet = (java.net.InetSocketAddress) address;
            return inet.getAddress() == null
                    ? inet.getHostString() + ":" + inet.getPort()
                    : inet.getAddress().getHostAddress() + ":" + inet.getPort();
        }
        return address.toString();
    }

    /**
     * DEBUG 调试子命令：只接安全的那几条（SLEEP / ERROR / SLOWLOG-RESET / OBJECT 的显式拒绝），
     * SEGFAULT、PANIC、RESTART 一类会让进程消失的支路一概不做。
     * <p>
     * 文案与判据顺序照 redis 4.0.9 的 {@code debugCommand()} 逐条量过
     * （battery31/32/33/35/36/37，250）：
     * <ul>
     *   <li>裸 {@code DEBUG} 回的是它自己那句提示，不是通用的 arity 错 —— 对岸把这句话写成了
     *       一个 {@code if (c->argc == 1) } 分支。</li>
     *   <li>子命令<b>不认识</b>和<b>arity 不对</b>共用一句
     *       {@code Unknown DEBUG subcommand or wrong number of arguments for '<原样>'}：对岸在
     *       每个子命令分支里各写了一遍 arity，落不到分支上就统一回这句，所以没有"参数太多/太少"
     *       的区分。名字部分回的是客户端敲进来的那一串，大小写照原样（实测 {@code DEBUG FoO} →
     *       {@code 'FoO'}）。</li>
     *   <li>{@code SLEEP} 的参数是<b>秒</b>且可以带小数（{@code 0.5} → 睡半秒），读不出数就当 0，
     *       对岸照样回 {@code +OK}（实测 {@code DEBUG SLEEP abc}）。旧实现按毫秒的整数解析，
     *       于是 {@code SLEEP 0.5} 报整数错、而 {@code SLEEP 1} 只睡了一毫秒。</li>
     *   <li>{@code ERROR <一段>} 把那段<b>原样</b>当错误文本回，不添 {@code ERR } 前缀
     *       （实测 {@code DEBUG ERROR hello} → {@code -hello}、{@code DEBUG ERROR -dash-first} →
     *       {@code --dash-first}）。这条存在的意义就是让客户端收到任意形状的错误回复，
     *       给它固定一句等于把它废掉。</li>
     * </ul>
     * 有意超出对岸的两条：{@code SLOWLOG-RESET}（4.0.9 不认，而我们的 SLOWLOG 需要一个重置入口）、
     * {@code DEBUG HELP} 回的是<b>本实现</b>的子命令清单而不是对岸那 21 条 —— 照抄那份清单等于
     * 对外承诺实现 segfault。{@code OBJECT} 在键不存在时与对岸同句（{@code no such key}），
     * 键存在时回拒绝而不是回四个常量假字段。
     */
    private Object handleDebug(String[] args) {
        if (args.length < 2) {
            return RespError.of("ERR You must specify a subcommand for DEBUG. Try DEBUG HELP for info.");
        }
        String sub = args[1].toUpperCase(Locale.ROOT);
        switch (sub) {
            case "HELP": {
                if (args.length != 2) return unknownDebugSubcommand(args[1]);
                Object[] lines = new Object[DEBUG_HELP.size()];
                for (int i = 0; i < lines.length; i++) lines[i] = RespBulkString.of(DEBUG_HELP.get(i));
                return RespArray.of(lines);
            }
            case "SLEEP": {
                if (args.length != 3) return unknownDebugSubcommand(args[1]);
                // 对岸走的是 strtod()：读不出数就当 0 并回 +OK（实测 DEBUG SLEEP abc）。
                // 只对整串试一次，"12abc" 这种"前缀读得出、尾巴读不出"的形状没有实测样本，
                // 这里同样当 0，不去猜 strtod 的最长前缀。
                double seconds;
                try {
                    seconds = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    seconds = 0;
                }
                if (Double.isNaN(seconds) || Double.isInfinite(seconds)) seconds = 0;
                long ms = seconds > 0 ? (long) Math.min(seconds * 1000.0, Long.MAX_VALUE) : 0;
                try {
                    if (ms > 0) Thread.sleep(ms);
                    return RespSimpleString.of("OK");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return RespError.of("ERR", "sleep interrupted");
                }
            }
            case "OBJECT": {
                if (args.length != 3) return unknownDebugSubcommand(args[1]);
                // 键在不在这一档与对岸同句（实测 DEBUG OBJECT <不存在的键> → no such key）
                if (!keyExists(args[2])) return RespError.noSuchKey();
                // 以前回的是 "Value at:0x<key.hashCode()> refcount:1 ... serializedlength:0 lru:0"：
                // 地址是哈希值假扮的、refcount/lru 是常量、serializedlength 恒为 0，
                // 四个字段没有一个是量出来的。Redis 自己也已经把这条废弃掉了。
                return RespError.of("ERR", "DEBUG OBJECT is not supported: refcount / lru / serializedlength"
                        + " cannot be measured from the JVM, and reporting constants would be worse than an error");
            }
            case "SLOWLOG-RESET": {
                if (args.length != 2) return unknownDebugSubcommand(args[1]);
                if (slowLog() == null) return RespError.of("ERR", "SlowLog not configured");
                slowLog().reset();
                // Redis 回 +OK；回 :1 会让按 Redis 协议写的客户端把整型当成解析失败。
                return RespSimpleString.of("OK");
            }
            case "ERROR": {
                if (args.length != 3) return unknownDebugSubcommand(args[1]);
                // 原样回，不添 ERR 前缀：见方法上的说明。RespError 自己会清 CR/LF，
                // 这一条与对岸一致（实测对岸把 'FOO\r\nBAR' 也压成了空格）。
                return RespError.of(args[2]);
            }
            default:
                return unknownDebugSubcommand(args[1]);
        }
    }

    /** DEBUG 那一句"要么不认识、要么参数个数不对"，名字部分照客户端写的回。 */
    private static RespError unknownDebugSubcommand(String rawSub) {
        return RespError.of("ERR", "Unknown DEBUG subcommand or wrong number of arguments for '"
                + rawSub + "'");
    }

    /** {@code DEBUG HELP} 回的内容 —— 只列真做得到的那几条。 */
    private static final List<String> DEBUG_HELP = Collections.unmodifiableList(Arrays.asList(
            "DEBUG <subcommand> arg arg ... arg. Subcommands:",
            "sleep <seconds> -- Stop the server for <seconds>. Decimals allowed.",
            "error <string> -- Return a Redis protocol error with <string> as message.",
            "slowlog-reset -- Clears the slow log. Not accepted by redis-server 4.0.9.",
            "object <key> -- Refused: refcount / lru / serializedlength cannot be measured from the JVM."));

    /**
     * MONITOR 命令：开启/关闭实时命令监控。
     * <p>MONITOR 开启后，该连接进入监控模式，服务端将所有命令推送到该连接。
     * 再次执行 MONITOR 关闭监控。
     */
    private Object handleMonitor(String[] args) {
        if (channelContext == null) return RespError.of("ERR", "no connection context");
        if (monitorClients().contains(channelContext)) {
            // 已在 MONITOR 模式，再次执行则退出
            monitorClients().remove(channelContext);
            return RespSimpleString.of("OK");
        }
        monitorClients().add(channelContext);
        // Redis 兼容：MONITOR 返回 OK，然后开始推送命令
        return RespSimpleString.of("OK");
    }

    /**
     * RESET 命令：重置连接状态（退出 MONITOR 模式、清除客户端名称、切换到 db0）。
     */
    private Object handleReset() {
        monitorClients().remove(channelContext);
        this.clientName = null;
        this.currentDb = 0;
        return RespSimpleString.of("OK");
    }

    /**
     * 向所有 MONITOR 客户端转发命令。
     * <p>形状与 Redis 一致：简单串一行，{@code <秒>.<6 位微秒> [<db> <ip:port>] "cmd" "arg" …}。
     * 三处偏离是本轮实测出来的（{@code $1790395133.000000 [0 763503151 /127.0.0.1:50047] "SET" …}）：
     * 推 bulk string（多一段长度行，按行读的客户端整体错位）、小数位写死 {@code .000000}、
     * db 与地址之间塞了一个 channel hashCode、地址用 {@code InetSocketAddress.toString()}
     * 因而带 Java 特有的前导斜杠。
     */
    private void forwardToMonitors(String[] args) {
        if (monitorClients().isEmpty()) return;
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append(now / 1000).append('.')
                // Java 的钟只有毫秒粒度，后三位恒为 000；前四位必须是真实时刻
                .append(String.format(java.util.Locale.ROOT, "%06d", (now % 1000) * 1000))
                .append(" [").append(currentDb).append(' ')
                .append(addressForm(channelContext.channel().remoteAddress())).append(']');
        for (String arg : args) {
            sb.append(" \"").append(monitorArg(arg)).append("\"");
        }

        RespSimpleString msg = RespSimpleString.of(sb.toString());
        java.util.Iterator<ChannelHandlerContext> it = monitorClients().iterator();
        while (it.hasNext()) {
            ChannelHandlerContext monitorCtx = it.next();
            try {
                monitorCtx.writeAndFlush(msg);
            } catch (Exception e) {
                logger.warn("Failed to send to monitor client, removing: {}", e.getMessage());
                it.remove();
            }
        }
    }

    /**
     * MONITOR 行里的参数转义。改成简单串之后这一步不是可选的：bulk 有长度前缀，裸换行还能被
     * 按字节读走；简单串靠 CRLF 结束，参数里带一个换行就会把一行劈成两行，后面的响应全部错位。
     */
    private static String monitorArg(String arg) {
        StringBuilder out = new StringBuilder(arg.length() + 8);
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    // ==================== Stream 命令 ====================

    /**
     * XADD key [MAXLEN maxlen] id field value [field value ...]
     */
    private Object handleXadd(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments("XADD");

        String key = args[1];
        long maxLen = 0;
        int i = 2;

        // 解析 MAXLEN [~|=] count —— 与 XTRIM 同一套形状。以前只认 "MAXLEN 5" 和 "MAXLEN ~ 5"，
        // 于是 Redis 合法的 "MAXLEN = 5" 抛出未捕获的 NumberFormatException，
        // 客户端拿到的是 "-ERR internal error: For input string: \"=\""；"MAXLEN" 少了 count
        // 更是直接越界取 args[i]。
        if ("MAXLEN".equalsIgnoreCase(args[i])) {
            if (i + 1 >= args.length) return RespError.wrongNumberOfArguments("XADD");
            i++;
            if ("~".equals(args[i]) || "=".equals(args[i])) {
                if (i + 1 >= args.length) return RespError.wrongNumberOfArguments("XADD");
                i++;
            }
            try {
                maxLen = longArg(args[i]);
            } catch (NumberFormatException e) {
                return RespError.notAnInteger();
            }
            if (maxLen < 0) return RespError.of("ERR", "MAXLEN requires a non-negative integer");
            i++;
            if (i >= args.length) return RespError.wrongNumberOfArguments("XADD");
        }

        String id = args[i++];
        if (i + 1 > args.length || (args.length - i) % 2 != 0) {
            return RespError.of("ERR", "XADD needs at least one field value pair");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        while (i + 1 < args.length) {
            fields.put(args[i], args[i + 1]);
            i += 2;
        }

        try {
            String entryId = streams().xadd(currentDb, key, fields, id, maxLen);
            return RespBulkString.of(entryId);
        } catch (Exception e) {
            return RespError.of("ERR", e.getMessage());
        }
    }

    /**
     * XLEN key
     */
    private Object handleXlen(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length != 2) return RespError.wrongNumberOfArguments("XLEN");
        return RespInteger.of((int) streams().xlen(currentDb, args[1]));
    }

    /**
     * XRANGE key start end [COUNT count]
     * XREVRANGE key end start [COUNT count]
     */
    private Object handleXrange(String[] args, boolean reverse) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments(reverse ? "XREVRANGE" : "XRANGE");

        String key = args[1];
        String start = args[2];
        String end = args[3];
        int count = -1;

        if (args.length > 4 && "COUNT".equalsIgnoreCase(args[4]) && args.length > 5) {
            count = intArg(args[5]);
        }

        List<StreamEntry> entries = reverse
                ? streams().xrevrange(currentDb, key, end, start, count)
                : streams().xrange(currentDb, key, start, end, count);

        Object[] result = new Object[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            StreamEntry e = entries.get(i);
            Object[] fields = new Object[e.getFields().size() * 2];
            int fi = 0;
            for (Map.Entry<String, String> fe : e.getFields().entrySet()) {
                fields[fi++] = RespBulkString.of(fe.getKey());
                fields[fi++] = RespBulkString.of(fe.getValue());
            }
            result[i] = RespArray.of(RespBulkString.of(e.getId()), RespArray.of(fields));
        }
        return RespArray.of(result);
    }

    /**
     * XDEL key id [id ...]
     */
    private Object handleXdel(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 3) return RespError.wrongNumberOfArguments("XDEL");
        String[] ids = new String[args.length - 2];
        System.arraycopy(args, 2, ids, 0, ids.length);
        return RespInteger.of((int) streams().xdel(currentDb, args[1], ids));
    }

    /**
     * XTRIM key MAXLEN [~ | =] count
     * <p>
     * 以前是 {@code Long.parseLong(args[3])}：标准写法 {@code XTRIM s MAXLEN ~ 3} 会去解析
     * "~"，抛出的异常被 {@code handle()} 兜成 {@code -ERR internal error}；反过来缺了 MAXLEN
     * 的 {@code XTRIM s 3} 却被接受。合法与非法正好判反对。
     * <p>
     * "~"（近似）与 "="（精确）在这里是同一件事——我们的 Stream 只有精确裁剪一种实现——
     * 但语法必须收下，不能让客户端因为写了官方形式就拿回一个 internal error。
     */
    private Object handleXtrim(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments("XTRIM");
        if (!"MAXLEN".equalsIgnoreCase(args[2])) {
            return RespError.of("ERR", "unsupported XTRIM strategy '" + args[2] + "', only MAXLEN is implemented");
        }
        int idx = "~".equals(args[3]) || "=".equals(args[3]) ? 4 : 3;
        if (idx != args.length - 1) return RespError.syntaxError();
        long maxLen;
        try {
            maxLen = longArg(args[idx]);
        } catch (NumberFormatException e) {
            return RespError.notAnInteger();
        }
        if (maxLen < 0) return RespError.of("ERR", "MAXLEN requires a non-negative integer");
        return RespInteger.of((int) streams().xtrim(currentDb, args[1], maxLen));
    }

    /**
     * XREAD [COUNT count] [BLOCK milliseconds] STREAMS key [key ...] id [id ...]
     */
    private Object handleXread(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments("XREAD");

        int count = -1;
        int i = 1;

        // 解析 COUNT
        if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
            count = intArg(args[i + 1]);
            i += 2;
        }

        // BLOCK 不能"跳过"：收下它等于对客户端谎称会阻塞，客户端于是把一个立即返回的
        // 空结果当成"没有新数据"，拿着它做轮询就成了忙等。要么真阻塞，要么明确拒绝。
        if ("BLOCK".equalsIgnoreCase(args[i])) {
            return RespError.of("ERR", "XREAD BLOCK is not supported: this server never blocks on a stream");
        }

        if (!"STREAMS".equalsIgnoreCase(args[i])) {
            return RespError.syntaxError();
        }
        i++;

        int numKeys = (args.length - i) / 2;
        String[] keys = new String[numKeys];
        String[] ids = new String[numKeys];
        for (int k = 0; k < numKeys; k++) {
            keys[k] = args[i + k];
            ids[k] = args[i + numKeys + k];
        }

        Object[] result = new Object[numKeys];
        int ri = 0;
        for (int k = 0; k < numKeys; k++) {
            List<StreamEntry> entries = streams().xrange(currentDb, keys[k], ids[k], "+", count);
            if (!entries.isEmpty()) {
                Object[] entryArr = new Object[entries.size()];
                for (int j = 0; j < entries.size(); j++) {
                    StreamEntry e = entries.get(j);
                    Object[] fields = new Object[e.getFields().size() * 2];
                    int fi = 0;
                    for (Map.Entry<String, String> fe : e.getFields().entrySet()) {
                        fields[fi++] = RespBulkString.of(fe.getKey());
                        fields[fi++] = RespBulkString.of(fe.getValue());
                    }
                    entryArr[j] = RespArray.of(RespBulkString.of(e.getId()), RespArray.of(fields));
                }
                result[ri++] = RespArray.of(RespBulkString.of(keys[k]), RespArray.of(entryArr));
            }
        }
        return ri == 0 ? RespArray.nullArray() : RespArray.of(Arrays.copyOf(result, ri));
    }

    /**
     * XREADGROUP GROUP group consumer [COUNT count] [BLOCK ms] STREAMS key [key ...] id [id ...]
     */
    private Object handleXreadgroup(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 7) return RespError.wrongNumberOfArguments("XREADGROUP");

        int i = 1;
        if (!"GROUP".equalsIgnoreCase(args[i])) return RespError.syntaxError();
        String group = args[i + 1];
        String consumer = args[i + 2];
        i += 3;

        int count = -1;
        if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
            count = intArg(args[i + 1]);
            i += 2;
        }
        if ("BLOCK".equalsIgnoreCase(args[i])) {
            return RespError.of("ERR", "XREADGROUP BLOCK is not supported: this server never blocks on a stream");
        }
        if (!"STREAMS".equalsIgnoreCase(args[i])) return RespError.syntaxError();
        i++;

        int numKeys = (args.length - i) / 2;
        Map<String, String> streams = new LinkedHashMap<>();
        for (int k = 0; k < numKeys; k++) {
            streams.put(args[i + k], args[i + numKeys + k]);
        }

        Map<String, List<StreamEntry>> result = streams().xreadgroup(currentDb, group, consumer, streams, count);

        Object[] streamResults = new Object[result.size()];
        int ri = 0;
        for (Map.Entry<String, List<StreamEntry>> entry : result.entrySet()) {
            Object[] entryArr = new Object[entry.getValue().size()];
            for (int j = 0; j < entry.getValue().size(); j++) {
                StreamEntry e = entry.getValue().get(j);
                Object[] fields = new Object[e.getFields().size() * 2];
                int fi = 0;
                for (Map.Entry<String, String> fe : e.getFields().entrySet()) {
                    fields[fi++] = RespBulkString.of(fe.getKey());
                    fields[fi++] = RespBulkString.of(fe.getValue());
                }
                entryArr[j] = RespArray.of(RespBulkString.of(e.getId()), RespArray.of(fields));
            }
            streamResults[ri++] = RespArray.of(RespBulkString.of(entry.getKey()), RespArray.of(entryArr));
        }
        return ri == 0 ? RespArray.nullArray() : RespArray.of(Arrays.copyOf(streamResults, ri));
    }

    /**
     * XGROUP [CREATE key group id] [DESTROY key group] [CREATECONSUMER key group consumer] [DELCONSUMER key group consumer]
     */
    private Object handleXgroup(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 2) return RespError.wrongNumberOfArguments("XGROUP");
        String sub = args[1].toUpperCase(Locale.ROOT);
        switch (sub) {
            case "CREATE": {
                if (args.length < 5) return RespError.wrongNumberOfArguments("XGROUP CREATE");
                boolean ok = streams().xgroupCreate(currentDb, args[2], args[3], args[4]);
                return ok ? RespSimpleString.of("OK") : RespError.of("ERR", "BUSYGROUP Consumer Group name already exists");
            }
            case "DESTROY": {
                if (args.length < 4) return RespError.wrongNumberOfArguments("XGROUP DESTROY");
                boolean ok = streams().xgroupDestroy(currentDb, args[2], args[3]);
                return RespInteger.of(ok ? 1 : 0);
            }
            case "CREATECONSUMER": {
                if (args.length < 5) return RespError.wrongNumberOfArguments("XGROUP CREATECONSUMER");
                com.zifang.z.cache.core.stream.Stream stream = streams().getStream(currentDb, args[2]);
                if (stream == null) return RespInteger.of(0);
                com.zifang.z.cache.core.stream.ConsumerGroup cg = stream.getGroup(args[3]);
                if (cg == null) return RespInteger.of(0);
                cg.getOrCreateConsumer(args[4]);
                return RespInteger.of(1);
            }
            case "DELCONSUMER": {
                if (args.length < 5) return RespError.wrongNumberOfArguments("XGROUP DELCONSUMER");
                com.zifang.z.cache.core.stream.Stream stream = streams().getStream(currentDb, args[2]);
                if (stream == null) return RespInteger.of(0);
                com.zifang.z.cache.core.stream.ConsumerGroup cg = stream.getGroup(args[3]);
                if (cg == null) return RespInteger.of(0);
                return cg.destroyConsumer(args[4]) ? RespInteger.of(1) : RespInteger.of(0);
            }
            default:
                return RespError.syntaxError();
        }
    }

    /**
     * XACK key group id [id ...]
     */
    private Object handleXack(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments("XACK");
        String[] ids = new String[args.length - 3];
        System.arraycopy(args, 3, ids, 0, ids.length);
        return RespInteger.of((int) streams().xack(currentDb, args[1], args[2], ids));
    }

    /**
     * XPENDING key group —— 只实现汇总形态。
     * <p>
     * 每个消费者手上压着几条以前是硬写的 {@code "0"}：XREADGROUP 领了三条、一条没 ACK，
     * XPENDING 仍然报每个消费者 0 条。明细形态（IDLE / start end count [consumer]）要按
     * 每条的投递时间过滤，而我们的 PEL 只记 entryId -&gt; consumer，所以现在是明确报错，
     * 不再像以前那样把多余参数丢掉、拿汇总冒充明细。
     */
    private Object handleXpending(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 3) return RespError.wrongNumberOfArguments("XPENDING");
        if (args.length > 3) {
            return RespError.of("ERR", "XPENDING detail form (IDLE / start / end / count) is not supported");
        }

        Object[] summary = streams().xpending(currentDb, args[1], args[2]);
        if (summary == null) {
            return RespError.of("NOGROUP",
                    "No such key '" + args[1] + "' or consumer group '" + args[2] + "'");
        }

        long pendingCount = (Long) summary[0];
        String lowestId = (String) summary[1];
        String highestId = (String) summary[2];

        com.zifang.z.cache.core.stream.Stream stream = streams().getStream(currentDb, args[1]);
        com.zifang.z.cache.core.stream.ConsumerGroup group = stream == null ? null : stream.getGroup(args[2]);
        Map<String, Long> perConsumer = group == null
                ? java.util.Collections.<String, Long>emptyMap() : group.perConsumerPending();
        List<Object> rows = new ArrayList<>(perConsumer.size());
        for (Map.Entry<String, Long> entry : perConsumer.entrySet()) {
            rows.add(RespArray.of(RespBulkString.of(entry.getKey()),
                    RespBulkString.of(Long.toString(entry.getValue()))));
        }
        return RespArray.of(
                RespInteger.of((int) pendingCount),
                lowestId != null ? RespBulkString.of(lowestId) : RespBulkString.nullBulkString(),
                highestId != null ? RespBulkString.of(highestId) : RespBulkString.nullBulkString(),
                RespArray.of(rows.toArray())
        );
    }

    /**
     * XINFO [GROUPS key] [STREAM key] [CONSUMERS key group]
     */
    private Object handleXinfo(String[] args) {
        if (streams() == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 3) return RespError.wrongNumberOfArguments("XINFO");
        String sub = args[1].toUpperCase(Locale.ROOT);
        switch (sub) {
            case "GROUPS": {
                com.zifang.z.cache.core.stream.Stream stream = streams().getStream(currentDb, args[2]);
                if (stream == null) return RespArray.nullArray();
                java.util.Set<String> names = stream.groupNames();
                Object[] result = new Object[names.size()];
                int i = 0;
                for (String name : names) {
                    com.zifang.z.cache.core.stream.ConsumerGroup cg = stream.getGroup(name);
                    result[i++] = RespArray.of(
                            RespBulkString.of("name"), RespBulkString.of(name),
                            RespBulkString.of("consumers"), RespInteger.of(cg != null ? cg.getConsumers().size() : 0),
                            RespBulkString.of("pending"), RespInteger.of(cg != null ? cg.pendingCount() : 0)
                    );
                }
                return RespArray.of(result);
            }
            case "STREAM": {
                com.zifang.z.cache.core.stream.Stream stream = streams().getStream(currentDb, args[2]);
                if (stream == null) return RespArray.nullArray();
                return RespArray.of(
                        RespBulkString.of("length"), RespInteger.of((int) stream.length()),
                        RespBulkString.of("groups"), RespInteger.of(stream.groupNames().size())
                );
            }
            case "CONSUMERS": {
                // 文档注释里一直写着这条，但 switch 从来没有这个 case：
                // XINFO CONSUMERS 拿回去的永远是 -ERR syntax error。
                if (args.length < 4) return RespError.wrongNumberOfArguments("XINFO CONSUMERS");
                com.zifang.z.cache.core.stream.Stream target = streams().getStream(currentDb, args[2]);
                com.zifang.z.cache.core.stream.ConsumerGroup group =
                        target == null ? null : target.getGroup(args[3]);
                if (group == null) {
                    return RespError.of("ERR", "NOGROUP No such consumer group '" + args[3]
                            + "' for key name '" + args[2] + "'");
                }
                Map<String, Long> pendingByConsumer = group.perConsumerPending();
                List<Object> rows = new ArrayList<>(group.getConsumers().size());
                for (Map.Entry<String, com.zifang.z.cache.core.stream.ConsumerGroup.Consumer> entry
                        : group.getConsumers().entrySet()) {
                    rows.add(RespArray.of(
                            RespBulkString.of("name"), RespBulkString.of(entry.getKey()),
                            RespBulkString.of("pending"),
                            RespInteger.of(pendingByConsumer.getOrDefault(entry.getKey(), 0L).intValue()),
                            RespBulkString.of("idle"), RespInteger.of((int) entry.getValue().getIdleTimeMs())));
                }
                return RespArray.of(rows.toArray());
            }
            default:
                return RespError.syntaxError();
        }
    }

    // ==================== 工具 ====================

    /**
     * 将写命令追加到 AOF 文件（仅记录写命令）。
     * <p>
     * 只列"重放这条命令就能得到同样的最终状态"的纯写命令。两点取舍：
     * <ul>
     *   <li>MULTI / EXEC / DISCARD 不列 —— EXEC 会把队列里的命令逐条重新走一遍
     *       {@code handle()}，每条各自落 AOF，再记一遍事务边界只会让重放多跑一次空事务。</li>
     *   <li>Stream（XADD / XDEL / XTRIM …）不列 —— 我们的 XADD 在 {@code *} 形态下按当前时间
     *       生成条目 ID，重放会造出一批 ID 完全不同的条目；而 Stream 也没有 RDB 那一份快照兜底。
     *       记进 AOF 看着像持久化了，实际是一堆对不上的 ID，比不记更容易误导。</li>
     * </ul>
     */
    private static final java.util.Set<String> WRITE_COMMANDS = new java.util.HashSet<>(java.util.Arrays.asList(
        "SET", "SETEX", "PSETEX", "SETNX", "GETSET", "MSET", "MSETNX", "APPEND", "INCR", "DECR", "INCRBY", "DECRBY",
        "SETRANGE", "INCRBYFLOAT", "SETBIT", "BITOP",
        "DEL", "UNLINK", "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST", "RENAME", "RENAMENX", "MOVE",
        "HSET", "HDEL", "HMSET", "HINCRBY", "HINCRBYFLOAT", "HSETNX",
        "LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LPOP", "RPOP", "LSET", "LINSERT", "LREM", "LTRIM", "RPOPLPUSH", "LMOVE",
        "SADD", "SREM", "SMOVE", "SPOP", "SINTERSTORE", "SUNIONSTORE", "SDIFFSTORE",
        "ZADD", "ZREM", "ZINCRBY", "ZREMRANGEBYLEX", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE",
        "ZUNIONSTORE", "ZINTERSTORE",
        "FLUSHDB", "FLUSHALL"
    ));

    /** 阻塞命令 → 非阻塞等价命令的映射；见 {@link #aofRecordFor}。 */
    private static final java.util.Set<String> BLOCKING_POP_COMMANDS =
            new java.util.HashSet<>(java.util.Arrays.asList("BLPOP", "BRPOP", "BRPOPLPUSH"));

    /**
     * 进程级 LOADING 标记，见字段区里的 {@code defaultLoading}：重放期间每条命令照常执行，
     * 但绝不能再写回 AOF，否则开机重放一次，AOF 就把自己抄了一份，越长越离谱。
     * 跑着的服务器走的是自己 {@link ServerScope#setLoading(boolean)} 那一份，这里只服务
     * 没有绑定服务器的路径（嵌入式手工搭 handler、单测）。
     */
    public static void setLoading(boolean value) { defaultLoading = value; }

    /**
     * 写命令执行完之后的两件收尾事：追加 AOF、给 RDB 调度器记一次"库变了"。
     * <p>
     * {@code RdbPersistence.onWrite()} 以前一个调用方都没有，于是 {@code writeCounter} 恒为 0、
     * {@code shouldSave()} 永远为 false —— 就算把调度器 start 起来，它也只会每 N 秒空转一次
     * 判断"没有任何写入"。定时快照要成立，这一记必须有人打。
     */
    private void propagateWriteToPersistence(String[] args, Object result) {
        if (isLoading() || args.length == 0) {
            return;
        }
        String cmd = args[0].toUpperCase(Locale.ROOT);
        String[] record;
        if (BLOCKING_POP_COMMANDS.contains(cmd)) {
            // 没弹出任何值 ⇒ 这条命令什么都没改，AOF 与写计数器都不记
            record = aofRecordFor(cmd, args, result);
            if (record == null) {
                return;
            }
        } else if (WRITE_COMMANDS.contains(cmd)) {
            record = args;
        } else {
            return;
        }

        // 键空间变了 —— WATCH 的复查就吃这一记。放在 AOF 分支之前：没配 --data-dir 时
        // aof() 是 null，而事务照样得能中止。
        bumpWatchedKeys(record);

        if (rdb() != null) {
            rdb().onWrite();
        }
        if (aof() == null) {
            return;
        }
        try {
            writeAofRecord(record);
        } catch (Exception e) {
            logger.warn("Failed to append to AOF: {}", e.getMessage());
        }
    }

    /**
     * 落一条 AOF 记录。当前连接不在 DB 0 时必须先写 SELECT：AOF 重放用的是一个全新
     * 连接（db 恒为 0），不带上库号的话 {@code SELECT 3} 之后写进去的数据会全部落到 DB 0。
     */
    private void writeAofRecord(String[] record) throws java.io.IOException {
        if (currentDb != 0) {
            aof().appendCommand(new String[]{"SELECT", Integer.toString(currentDb)});
        }
        aof().appendCommand(record);
    }

    /** 一条命令同时改动两个键的操作（源与目标都要让 WATCH 看见）。 */
    private static final java.util.Set<String> TWO_KEY_WRITE_COMMANDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "RENAME", "RENAMENX", "RPOPLPUSH", "BRPOPLPUSH", "LMOVE", "SMOVE"));

    /** 一条命令里"键"参数占据的下标形状。 */
    private enum KeyPos { SINGLE, ALL, ALL_BUT_LAST, FIRST_TWO, FROM_SECOND }

    private static final class TypeSpec {
        final MemoryStore.DataType family;
        final KeyPos pos;

        TypeSpec(MemoryStore.DataType family, KeyPos pos) {
            this.family = family;
            this.pos = pos;
        }
    }

    private static final java.util.Map<String, TypeSpec> TYPED_COMMANDS = new java.util.HashMap<>();

    private static void typed(String cmd, MemoryStore.DataType family, KeyPos pos) {
        TYPED_COMMANDS.put(cmd, new TypeSpec(family, pos));
    }

    static {
        MemoryStore.DataType string = MemoryStore.DataType.STRING;
        MemoryStore.DataType hash = MemoryStore.DataType.HASH;
        MemoryStore.DataType list = MemoryStore.DataType.LIST;
        MemoryStore.DataType set = MemoryStore.DataType.SET;
        MemoryStore.DataType zset = MemoryStore.DataType.ZSET;

        for (String c : new String[]{"GET", "SETNX", "GETSET", "APPEND", "STRLEN",
                "INCR", "DECR", "INCRBY", "DECRBY", "INCRBYFLOAT"}) {
            typed(c, string, KeyPos.SINGLE);
        }
        typed("MGET", string, KeyPos.ALL);
        // GETRANGE / SUBSTR / SETRANGE 都不挂中央闸门：实测对岸是"整数栏先说话"
        // （{@code GETRANGE <list 键> +0 -1} → value is not an integer，
        //  而 {@code GETRANGE <list 键> 0 1} → WRONGTYPE，battery38 第 14/15 行；
        //  {@code SETRANGE <list 键> -5 x} → offset is out of range）。参考实现把取值
        //  检查排在 lookupKeyWrite 之前，所以这道类型检查必须由各自的 handler 在解析之后补。
        // BITCOUNT 同理，但它和 GETRANGE 是<b>反着</b>的两族：实测键不存在一律 :0、类型不对
        // 一律 WRONGTYPE，两道都过了才轮到 syntax error 与整数那句（battery39 第 2—15 行），
        // 所以这一刀必须由 handleBitcount 自己在查键之后补，顺序见该方法。

        for (String c : new String[]{"HSET", "HGET", "HDEL", "HEXISTS", "HGETALL", "HKEYS", "HVALS",
                "HMGET", "HMSET", "HLEN", "HSETNX", "HSCAN", "HRANDFIELD", "HINCRBY",
                "HSTRLEN"}) {
            typed(c, hash, KeyPos.SINGLE);
        }
        // HINCRBYFLOAT 有意不在表里：它先解析增量再判类型（实测见 wrongTypeAfterParse），
        // 中央闸门跑在分发之前，会把"增量本身就写歪"那一档的文案抢答成 WRONGTYPE。

        for (String c : new String[]{"LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LPOP", "RPOP", "LLEN", "LRANGE",
                "LINDEX", "LSET", "LINSERT", "LREM", "LTRIM"}) {
            typed(c, list, KeyPos.SINGLE);
        }
        typed("RPOPLPUSH", list, KeyPos.FIRST_TWO);
        typed("BRPOPLPUSH", list, KeyPos.FIRST_TWO);
        typed("LMOVE", list, KeyPos.FIRST_TWO);
        typed("BLPOP", list, KeyPos.ALL_BUT_LAST);
        typed("BRPOP", list, KeyPos.ALL_BUT_LAST);

        for (String c : new String[]{"SADD", "SREM", "SMEMBERS", "SISMEMBER", "SCARD",
                "SRANDMEMBER", "SPOP", "SSCAN"}) {
            typed(c, set, KeyPos.SINGLE);
        }
        typed("SINTER", set, KeyPos.ALL);
        typed("SUNION", set, KeyPos.ALL);
        typed("SDIFF", set, KeyPos.ALL);
        typed("SMOVE", set, KeyPos.FIRST_TWO);
        // SINTERSTORE/SUNIONSTORE/SDIFFSTORE：目标键是被覆盖的，不检查；源键必须都是 set
        typed("SINTERSTORE", set, KeyPos.FROM_SECOND);
        typed("SUNIONSTORE", set, KeyPos.FROM_SECOND);
        typed("SDIFFSTORE", set, KeyPos.FROM_SECOND);

        for (String c : new String[]{"ZREM", "ZSCORE", "ZRANK", "ZREVRANK", "ZCARD", "ZCOUNT",
                "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZREVRANGEBYSCORE", "ZLEXCOUNT",
                "ZRANGEBYLEX", "ZREVRANGEBYLEX", "ZREMRANGEBYLEX", "ZREMRANGEBYRANK",
                "ZREMRANGEBYSCORE", "ZRANDMEMBER", "ZSCAN"}) {
            typed(c, zset, KeyPos.SINGLE);
        }
        // ZADD / ZINCRBY 同样有意不在表里：实测 ZADD <string 键> abc a → value is not a valid
        // float、ZINCRBY <string 键> nan m → 同一句（ref16 / ref17），都是"分数解析在前"，
        // 所以这道类型检查由两个 handler 自己在解析完（全部）数对之后补。
        // ZUNIONSTORE/ZINTERSTORE 有意不挂进这张表：它们的第一枚参数是 numkeys 而不是键
        // （SINTERSTORE 那三兄弟的键从 args[1] 就开始，形状不同），而且目标键是被覆盖的、
        // 不该报 WRONGTYPE。源键的类型检查由 handleZstore 自己按 numkeys 数完再做。
    }

    /**
     * "先解析参数、后判类型"那一族命令自己补的类型检查。
     * <p>
     * 中央闸门 {@link #typeConflict} 是在分发前一次性问的，而对岸这几种命令的 {@code checkType}
     * 排在参数解析<b>之后</b>，于是同一枚坏键名要按坏的到底是哪一栏给两种答案（250 实测）：
     * {@code ZADD <string 键> abc a} → {@code value is not a valid float}（ref16），
     * 而 {@code ZADD <string 键> 1 a} → {@code WRONGTYPE}；
     * {@code HINCRBYFLOAT <string 键> f abc} → 增量的错，{@code ... f 1} → WRONGTYPE（ref15）；
     * {@code SETRANGE <list 键> -5 x} → {@code offset is out of range}（ref9）。
     * 反过来 {@code ZINCRBY <string 键> nan m} 也是先 float 后 WRONGTYPE（ref17），
     * 所以这一把尺量的是"参数已经解析成功了"这个前提，谁在前谁在后由实测说了算。
     */
    private RespError wrongTypeAfterParse(MemoryStore.DataType family, String key) {
        MemoryStore.DataType actual = store.typeOfDb(currentDb, key);
        if (actual == MemoryStore.DataType.NONE || actual == family) {
            return null;
        }
        return RespError.wrongType("Operation against a key holding the wrong kind of value");
    }

    /**
     * 类型闸门：键已经属于另一种数据类型时，按 Redis 回 WRONGTYPE，而不是给一个看着像
     * "没有这个键"的答案。
     * <p>
     * 修之前整个 {@code CommandHandler} 里 {@code WRONGTYPE} 一次都没出现过（grep 计数为 0）：
     * {@code HGET} 一个 string 键回 nil（与"域不存在"分不出来），{@code LPUSH} 一个 hash 键
     * 还会成功 —— 于是同一个键名下并存着 hash 和 list 两份数据，{@code TYPE} 只报其中一种，
     * {@code DBSIZE} 把它算成两个键。
     * <p>
     * {@code SET}/{@code SETEX}/{@code PSETEX} 有意不在表里：Redis 让它们覆盖任意类型的旧值
     * （dbOverwrite），这条语义由 {@code MemoryStore.putDb} 的 {@code clearOtherTypes} 兑现。
     */
    private RespError typeConflict(String[] args) {
        TypeSpec spec = TYPED_COMMANDS.get(args[0].toUpperCase(Locale.ROOT));
        if (spec == null) {
            return null;
        }
        int from = 1;
        int to = args.length - 1;
        switch (spec.pos) {
            case SINGLE: to = 1; break;
            case FIRST_TWO: to = Math.min(2, args.length - 1); break;
            case ALL_BUT_LAST: to = args.length - 2; break;
            case FROM_SECOND: from = 2; break;
            default: break;
        }
        // 参数不够长的（ arity 错的）留给各自的 handler 报错，这里不越界取参数
        to = Math.min(to, args.length - 1);
        for (int i = from; i <= to; i++) {
            MemoryStore.DataType actual = store.typeOfDb(currentDb, args[i]);
            if (actual != MemoryStore.DataType.NONE && actual != spec.family) {
                return RespError.wrongType("Operation against a key holding the wrong kind of value");
            }
        }
        return null;
    }

    /**
     * 记一次"这些键变过了"，供 {@code WATCH} 在 EXEC 前复查。
     * <p>
     * 以 AOF 记录为准，是因为它已经把这几种情况折对了：阻塞弹空返回 null（什么都没改，
     * 不该记）、{@code BLPOP a b} 落的是真正命中的那个键、{@code BRPOPLPUSH} 落的是
     * {@code RPOPLPUSH src dst}。
     * <p>
     * 以前只有 {@code MemoryStore.putDb} 里那一记，等于"只有 String 写会让事务中止"：
     * {@code WATCH h} 之后别人 {@code HSET h f v}，EXEC 照样提交。
     * FLUSHDB / FLUSHALL 不在这里记（它们改的是整库，逐键 bump 要反过来枚举 watch 表）。
     */
    private void bumpWatchedKeys(String[] record) {
        if (record.length < 2 || record[1] == null) {
            return;
        }
        String cmd = record[0].toUpperCase(Locale.ROOT);
        if ("MSET".equals(cmd)) {
            for (int i = 1; i + 1 < record.length; i += 2) {
                store.bumpKeyVersion(currentDb, record[i]);
            }
            return;
        }
        if ("BITOP".equals(cmd)) {
            // BITOP 的键名从下标 2 起（下标 1 是 AND/OR/XOR/NOT），而被改动的只有目标那一个：
            // 源是只读的，WATCH 一个源键不会因为一次 BITOP 而中止（battery47 实测）。
            // 按通用形状走的话会把操作名 "AND" 当成键去 bump，目标键反而没记到。
            if (record.length > 2) store.bumpKeyVersion(currentDb, record[2]);
            return;
        }
        store.bumpKeyVersion(currentDb, record[1]);
        if (record.length > 2 && TWO_KEY_WRITE_COMMANDS.contains(cmd)) {
            store.bumpKeyVersion(currentDb, record[2]);
        }
    }

    /**
     * 把阻塞弹出命令翻译成 AOF 里该记的形态。
     * <p>
     * 阻塞命令超时（回复是 {@code *-1} 或 {@code $-1}）时什么都没弹出，不能记；真的弹到了
     * 才按非阻塞等价命令（LPOP / RPOP / RPOPLPUSH）记一条 —— Redis 也是这么做的。若不翻译，
     * "BLPOP 消费掉的那个值"在 AOF 里根本没有痕迹，重放后它会还躺在源列表里被消费第二次。
     * BLPOP 允许多个 key，所以要记的是回复里给出的那个真正命中的 key。
     *
     * @return 要写入 AOF 的命令；null 表示本次调用没有产生任何写入
     */
    private static String[] aofRecordFor(String cmd, String[] args, Object result) {
        if ("BRPOPLPUSH".equals(cmd)) {
            if (!(result instanceof RespBulkString) || ((RespBulkString) result).isNull()) {
                return null;
            }
            return new String[]{"RPOPLPUSH", args[1], args[2]};
        }
        if (!(result instanceof RespArray) || ((RespArray) result).isNull() || ((RespArray) result).size() < 1) {
            return null;
        }
        String poppedKey = ((RespArray) result).toStringArray()[0];
        if (poppedKey == null) {
            return null;
        }
        return new String[]{"BLPOP".equals(cmd) ? "LPOP" : "RPOP", poppedKey};
    }

    private boolean keyExists(String k) { return store.typeOfDb(currentDb, k) != MemoryStore.DataType.NONE; }
    private double hitRate() { long h=store.getHits(),m=store.getMisses(); return h+m==0?0.0:(double)h/(h+m); }

    private static RespArray toRespArray(List<byte[]> l) { Object[] r=new Object[l.size()]; for(int i=0;i<l.size();i++) r[i]=l.get(i)==null?RespBulkString.nullBulkString():RespBulkString.of(l.get(i)); return RespArray.of(r); }
    private static RespArray toRespArray(String[] a) { Object[] r=new Object[a.length]; for(int i=0;i<a.length;i++) r[i]=RespBulkString.of(a[i]); return RespArray.of(r); }

    /**
     * 分数 / 增量参数的解析。{@code +inf} / {@code inf} / {@code -inf} 是 Redis 认的写法
     * （实测 {@code ZADD z inf m} 之后 {@code ZSCORE z m} 回 {@code inf}），而
     * {@code Double.parseDouble} 只认 {@code Infinity}；{@code nan} 两侧都必须在解析阶段拒掉
     * （实测 {@code ZINCRBY z nan m} 回 {@code -ERR value is not a valid float}），否则会一路
     * 走到存储里变成第三种形状。
     */
    private static double parseScore(String s) {
        return RedisDoubleFormat.parse(s);
    }
    private static String fmtBytes(long b) { if(b<1024)return b+"B"; double k=b/1024.0; if(k<1024)return String.format(java.util.Locale.ROOT,"%.2fKB",k); double m=k/1024.0; if(m<1024)return String.format(java.util.Locale.ROOT,"%.2fMB",m); return String.format(java.util.Locale.ROOT,"%.2fGB",m/1024.0); }

    // ==================== 新增 Hash 命令 ====================

    private Object handleHincrbyfloat(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("HINCRBYFLOAT");
        // 这条命令不挂中央类型闸门，因为对岸的顺序是"解析增量 → 查键并判类型 → 读字段"：
        // 实测 HINCRBYFLOAT <string 键> f abc → value is not a valid float，而同一枚键配
        // 合法增量 f 1 → WRONGTYPE（ref15）。增量这一栏因此要先单独量一遍。
        try {
            if (!RedisDoubleFormat.isInfinityText(args[3])) RedisDoubleFormat.requirePlain(args[3]);
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not a valid float");
        }
        RespError conflict = wrongTypeAfterParse(MemoryStore.DataType.HASH, args[1]);
        if (conflict != null) return conflict;
        try {
            // 增量原样交给存储层做文本进/文本出：回复的那串和存进 hash 的那串必须是同一次
            // long double 量化的结果，中间过一遍 double 就会两边不一样（250 实测两列逐例相同）。
            String result = store.getHashStore(currentDb).hincrbyfloat(args[1], args[2], args[3]);
            return RespBulkString.of(result);
        } catch (NumberFormatException e) { return RespError.of("ERR", "value is not a valid float"); }
        catch (IllegalArgumentException e) { return RespError.of("ERR", e.getMessage()); }
    }

    // ==================== 新增 List 命令 ====================

    private Object handleLmove(String[] args) {
        if (args.length != 5) return RespError.wrongNumberOfArguments("LMOVE");
        byte[] v = store.getListStore(currentDb).lmove(args[1], args[2], args[3], args[4]);
        return v == null ? RespBulkString.nullBulkString() : RespBulkString.of(v);
    }

    // ==================== 新增 Set 命令 ====================

    private Object handleSpop(String[] args) {
        if (args.length < 2 || args.length > 3) return RespError.wrongNumberOfArguments("SPOP");
        int count = args.length == 3 ? intArg(args[2]) : 1;
        List<byte[]> m = store.getSetStore(currentDb).spop(args[1], count);
        if (args.length == 2) {
            if (m.isEmpty()) return RespBulkString.nullBulkString();
            return RespBulkString.of(m.get(0));
        }
        Object[] r = new Object[m.size()];
        for (int i = 0; i < m.size(); i++) r[i] = RespBulkString.of(m.get(i));
        return RespArray.of(r);
    }

    private Object handleSinterstore(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("SINTERSTORE");
        String dest = args[1];
        List<byte[]> result = store.getSetStore(currentDb).sinter(Arrays.copyOfRange(args, 2, args.length));
        store.getSetStore(currentDb).del(dest);
        if (!result.isEmpty()) {
            store.getSetStore(currentDb).sadd(dest, result.toArray(new byte[0][]));
        }
        return RespInteger.of(result.size());
    }

    private Object handleSunionstore(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("SUNIONSTORE");
        String dest = args[1];
        List<byte[]> result = store.getSetStore(currentDb).sunion(Arrays.copyOfRange(args, 2, args.length));
        store.getSetStore(currentDb).del(dest);
        if (!result.isEmpty()) {
            store.getSetStore(currentDb).sadd(dest, result.toArray(new byte[0][]));
        }
        return RespInteger.of(result.size());
    }

    private Object handleSdiffstore(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("SDIFFSTORE");
        String dest = args[1];
        List<byte[]> result = store.getSetStore(currentDb).sdiff(Arrays.copyOfRange(args, 2, args.length));
        store.getSetStore(currentDb).del(dest);
        if (!result.isEmpty()) {
            store.getSetStore(currentDb).sadd(dest, result.toArray(new byte[0][]));
        }
        return RespInteger.of(result.size());
    }

    // ==================== 新增 Sorted Set 命令 ====================

    private Object handleZlexcount(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("ZLEXCOUNT");
        return RespInteger.of(store.getSortedSetStore(currentDb).zlexcount(args[1], args[2], args[3]));
    }

    private Object handleZrangebylex(String[] args, boolean reverse) {
        if (args.length != 4) return RespError.wrongNumberOfArguments(reverse ? "ZREVRANGEBYLEX" : "ZRANGEBYLEX");
        List<byte[]> r = reverse ? store.getSortedSetStore(currentDb).zrevrangebylex(args[1], args[2], args[3])
                : store.getSortedSetStore(currentDb).zrangebylex(args[1], args[2], args[3]);
        return toRespArray(r);
    }

    private Object handleZremrangebylex(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("ZREMRANGEBYLEX");
        return RespInteger.of(store.getSortedSetStore(currentDb).zremrangebylex(args[1], args[2], args[3]));
    }

    private Object handleZremrangebyrank(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("ZREMRANGEBYRANK");
        try {
            return RespInteger.of(store.getSortedSetStore(currentDb).zremrangebyrank(args[1], longArg(args[2]), longArg(args[3])));
        } catch (NumberFormatException e) { return RespError.notAnInteger(); }
    }

    private Object handleZremrangebyscore(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("ZREMRANGEBYSCORE");
        try {
            return RespInteger.of(store.getSortedSetStore(currentDb).zremrangebyscore(args[1], parseScore(args[2]), parseScore(args[3])));
        } catch (NumberFormatException e) { return RespError.of("ERR", "value is not a valid float"); }
    }

    private Object handleZrandmember(String[] args) {
        if (args.length < 2 || args.length > 3) return RespError.wrongNumberOfArguments("ZRANDMEMBER");
        int count = args.length == 3 ? intArg(args[2]) : 1;
        List<byte[]> m = store.getSortedSetStore(currentDb).zrandmember(args[1], count);
        if (args.length == 2) {
            if (m.isEmpty()) return RespBulkString.nullBulkString();
            return RespBulkString.of(m.get(0));
        }
        Object[] r = new Object[m.size()];
        for (int i = 0; i < m.size(); i++) r[i] = RespBulkString.of(m.get(i));
        return RespArray.of(r);
    }

    // ==================== BLPOP/BRPOP ====================

    private Object handleBpop(String[] args, String direction) {
        if (args.length < 3) return RespError.wrongNumberOfArguments(direction.equals("LEFT") ? "BLPOP" : "BRPOP");
        int timeout;
        try { timeout = intArg(args[args.length - 1]); }
        catch (NumberFormatException e) { return RespError.of("ERR", "timeout is not an integer or out of range"); }
        if (timeout < 0) return RespError.of("ERR", "timeout is negative");

        // args[1..len-2] 才是 key 列表。以前整段丢掉，于是 BLPOP anykey 会去弹库里随便一个
        // 非空列表，并把那个 key 名一起返回给客户端。
        List<String> keys = new ArrayList<>(args.length - 2);
        for (int i = 1; i < args.length - 1; i++) keys.add(args[i]);

        List<byte[]> result = store.getListStore(currentDb).bpop(direction, keys, timeout);
        // 超时是"没有值"，Redis 回空多批量(*-1)而不是长度 0 的数组：客户端按 nil
        // 判断超时，收到 *0 会当成"取到了一个空结果"。
        if (result == null) return RespArray.nullArray();
        return RespArray.of(RespBulkString.of(result.get(0)), RespBulkString.of(result.get(1)));
    }

    /**
     * BRPOPLPUSH src dst timeout —— 尾弹出 src、头插入 dst，返回弹出的值；超时回 nil bulk。
     * <p>
     * 以前这条命令直接转给 {@link #handleRpoplpush}，而那个处理器要求 {@code args.length == 3}，
     * 带 timeout 的四参数形态必然回 {@code -ERR wrong number of arguments for 'BRPOPLPUSH'}：
     * 也就是说它从分发那一刻起就没有可用过，连"退化成非阻塞"都算不上。
     * <p>
     * 弹出与推送分两步走（而不是复用 {@code rpoplpush}）：{@code bpop} 已经把元素从 src 摘掉了，
     * 再走一次 rpoplpush 会连 dst 的写入一起做第二遍弹出。
     */
    private Object handleBrpoplpush(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("BRPOPLPUSH");
        int timeout;
        try { timeout = intArg(args[3]); }
        catch (NumberFormatException e) { return RespError.of("ERR", "timeout is not an integer or out of range"); }
        if (timeout < 0) return RespError.of("ERR", "timeout is negative");

        List<byte[]> popped = store.getListStore(currentDb)
                .bpop("RIGHT", java.util.Collections.singletonList(args[1]), timeout);
        if (popped == null) return RespBulkString.nullBulkString();
        byte[] value = popped.get(1);
        store.getListStore(currentDb).lpush(args[2], value);
        return RespBulkString.of(value);
    }

    // ==================== HRANDFIELD ====================

    private Object handleHrandfield(String[] args) {
        if (args.length < 2 || args.length > 3) return RespError.wrongNumberOfArguments("HRANDFIELD");
        int count = args.length == 3 ? intArg(args[2]) : 1;
        List<String> fields = store.getHashStore(currentDb).hrandfield(args[1], count);
        if (args.length == 2) {
            if (fields.isEmpty()) return RespBulkString.nullBulkString();
            return RespBulkString.of(fields.get(0));
        }
        Object[] r = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) r[i] = RespBulkString.of(fields.get(i));
        return RespArray.of(r);
    }

    // ==================== SSCAN ====================

    private Object handleSscan(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("SSCAN");
        String pattern = null;
        for (int i = 3; i < args.length; i++) {
            if ("MATCH".equalsIgnoreCase(args[i]) && i + 1 < args.length) pattern = args[++i];
        }
        Object[] sr = store.getSetStore(currentDb).sscan(args[1], args[2], pattern);
        List<byte[]> members = (List<byte[]>) sr[1];
        Object[] r = new Object[members.size()];
        for (int i = 0; i < members.size(); i++) r[i] = RespBulkString.of(members.get(i));
        return RespArray.of(new Object[]{RespBulkString.of((String) sr[0]), RespArray.of(r)});
    }

    // ==================== ZSCAN ====================

    private Object handleZscan(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("ZSCAN");
        String pattern = null;
        for (int i = 3; i < args.length; i++) {
            if ("MATCH".equalsIgnoreCase(args[i]) && i + 1 < args.length) pattern = args[++i];
        }
        Object[] sr = store.getSortedSetStore(currentDb).zscan(args[1], args[2], pattern);
        List<byte[]> members = (List<byte[]>) sr[1];
        Object[] r = new Object[members.size()];
        for (int i = 0; i < members.size(); i++) r[i] = RespBulkString.of(members.get(i));
        return RespArray.of(new Object[]{RespBulkString.of((String) sr[0]), RespArray.of(r)});
    }

    // ==================== SCAN 命令 ====================

    private Object handleScan(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("SCAN");
        String cursor = args[1];
        // 游标不合整数语法就是 invalid cursor（实测 battery38 第 33 行）。以前存储层把坏游标
        // 当 0 从头再扫一遍：客户端的一个拼写错变成一次全库重扫，而且和"这一轮真扫完了"
        // 的 0 号游标根本分不开。
        if (RedisIntegerFormat.parse(cursor) == null) return RespError.of("ERR", "invalid cursor");
        String pattern = null;
        int count = 10;
        for (int i = 2; i < args.length; i++) {
            if ("MATCH".equalsIgnoreCase(args[i]) && i + 1 < args.length) pattern = args[++i];
            else if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) count = intArg(args[++i]);
            // 认不得的尾巴、以及只有旗标没有值（COUNT/MATCH 落在末尾）都是 syntax error
            // （实测 {@code SCAN 0 abc} → syntax error，battery38 第 35 行；
            //  而 {@code SCAN 0 COUNT abc} 是整数那句，第 34 行 —— 两档不能合并）。
            else return RespError.syntaxError();
        }
        Object[] scanResult = store.scan(currentDb, cursor, pattern, count);
        String nextCursor = (String) scanResult[0];
        List<String> keys = (List<String>) scanResult[1];
        Object[] r = new Object[keys.size()];
        for (int i = 0; i < keys.size(); i++) r[i] = RespBulkString.of(keys.get(i));
        return RespArray.of(new Object[]{RespBulkString.of(nextCursor), RespArray.of(r)});
    }

    private static String globToRegex(String p) { StringBuilder r=new StringBuilder("^"); for(int i=0;i<p.length();i++) { char c=p.charAt(i); if(c=='*')r.append(".*"); else if(c=='?')r.append('.'); else if("\\.[]{}()+-^$|".indexOf(c)>=0)r.append('\\').append(c); else r.append(c); } return r.append('$').toString(); }
}
