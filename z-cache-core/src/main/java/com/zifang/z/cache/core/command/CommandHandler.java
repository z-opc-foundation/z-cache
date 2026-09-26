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
    // 现在这两样优先用"这条连接所属服务器"注入的那一份（{@link #bindSharedComponents}），
    // 静态的只当"没人注入过"时的进程级默认值（嵌入式与单测走那条）。
    //
    // 剩下四个仍是进程级（SlowLog / AOF / RDB / StreamStore）：加 volatile 只解决可见性，
    // 同 JVM 多实例仍共用一份，这条边界在 CHANGELOG 里写明，不当已修。
    private static volatile PubSubManager defaultPubSubManager;
    private static volatile SlowLog slowLog;
    private static volatile AofPersistence aofPersistence;
    private static volatile RdbPersistence rdbPersistence;
    private static volatile StreamStore streamStore;

    /** 进程级默认连接登记表：没有服务器注入时（单测直接 new）用这一份。 */
    private static final java.util.concurrent.ConcurrentMap<ChannelHandlerContext, CommandHandler> DEFAULT_CONNECTIONS
            = new java.util.concurrent.ConcurrentHashMap<>();
    /** 进程级默认 MONITOR 集合，口径同上。 */
    private static final java.util.Set<ChannelHandlerContext> DEFAULT_MONITOR_CLIENTS
            = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 这条连接实际使用的 pub/sub 管理器；未注入时退回进程级默认值。 */
    private volatile PubSubManager pubSubManager;
    /**
     * 这条连接所属服务器的连接登记表：{@code ctx -> 该连接的 CommandHandler}。
     * CLIENT LIST 要列出所有连接（以前只列发起者自己那一条，等于没有列表），CLIENT KILL
     * 要能按地址/id 找到目标并真的关掉它；连接在 {@link #setChannelContext} 里入表、
     * {@link #onDisconnect} 里出表。范围是"这台服务器"，不是整个 JVM。
     */
    private volatile java.util.concurrent.ConcurrentMap<ChannelHandlerContext, CommandHandler> connections
            = DEFAULT_CONNECTIONS;

    /** MONITOR 客户端集合，作用域与 {@link #connections} 一致。 */
    private volatile java.util.Set<ChannelHandlerContext> monitorClients = DEFAULT_MONITOR_CLIENTS;

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

    // ---- 共享组件 setter：进程级默认值，未注入过的连接读这一份 ----
    public static void setPubSubManager(PubSubManager m) { defaultPubSubManager = m; }
    public static void setSlowLog(SlowLog l) { slowLog = l; }
    public static void setAofPersistence(AofPersistence a) { aofPersistence = a; }
    public static void setRdbPersistence(RdbPersistence r) { rdbPersistence = r; }
    public static void setStreamStore(StreamStore ss) { streamStore = ss; }

    /**
     * 把这条连接接到它所属服务器的那一份共享状态上。由
     * {@link com.zifang.z.cache.core.server.RedisServerHandler} 在建管道时调用一次。
     * <p>
     * 传 null 的那一项退回进程级默认值，所以单测直接 new {@code CommandHandler}、
     * 或用旧的两/三参 {@code RedisServerHandler} 构造器的路径行为不变。
     *
     * @param pubSub    这台服务器的 pub/sub 管理器
     * @param registry  这台服务器的连接登记表（CLIENT LIST / KILL 的可见范围）
     * @param monitors  这台服务器的 MONITOR 集合
     */
    public void bindSharedComponents(PubSubManager pubSub,
                                    java.util.concurrent.ConcurrentMap<ChannelHandlerContext, CommandHandler> registry,
                                    java.util.Set<ChannelHandlerContext> monitors) {
        this.pubSubManager = pubSub;
        if (registry != null) {
            this.connections = registry;
        }
        if (monitors != null) {
            this.monitorClients = monitors;
        }
    }

    /** pub/sub 管理器：先用这台连接所属服务器注入的那份，没有再退回进程级默认值。 */
    private PubSubManager pubSub() {
        PubSubManager bound = this.pubSubManager;
        return bound != null ? bound : defaultPubSubManager;
    }

    public static StreamStore getStreamStore() { return streamStore; }
    public static SlowLog getSlowLog() { return slowLog; }

    /**
     * 绑定的同时把这条连接实际监听到的端口记下来：INFO 报的是它，不是配置里的默认 6379
     * （{@code bind(0)} 时两者必然不同）。EmbeddedChannel / 无连接驱动时拿不到
     * InetSocketAddress，端口保持 0，INFO 就如实报 0。
     */
    public void setChannelContext(ChannelHandlerContext ctx) {
        if (ctx != null) {
            connections.put(ctx, this);
            if (ctx.channel().localAddress() instanceof java.net.InetSocketAddress) {
                this.localPort = ((java.net.InetSocketAddress) ctx.channel().localAddress()).getPort();
            }
        } else if (channelContext != null) {
            connections.remove(channelContext);
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
            monitorClients.remove(channelContext);
            connections.remove(channelContext);
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
        String cmd = args[0].toUpperCase();
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

        if (!executingTransaction && transactionContext.isInTransaction()
                && !"MULTI".equals(cmd) && !"EXEC".equals(cmd) && !"DISCARD".equals(cmd)) {
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
                case "EXPIRE":   result = handleExpire(args);     break;
                case "PEXPIRE":  result = handlePexpire(args);    break;
                case "TTL":      result = handleTtl(args);        break;
                case "PTTL":     result = handlePttl(args);       break;
                case "PERSIST":  result = handlePersist(args);    break;
                case "SETEX":    result = handleSetex(args);      break;
                case "PSETEX":   result = handlePsetex(args);     break;
                case "SETNX":    result = handleSetnx(args);      break;
                case "GETSET":   result = handleGetset(args);     break;
                case "MGET":     result = handleMget(args);       break;
                case "MSET":     result = handleMset(args);       break;
                case "APPEND":   result = handleAppend(args);     break;
                case "STRLEN":   result = handleStrlen(args);     break;
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
                case "HSETNX":   result = handleHsetnx(args);     break;
                case "HSCAN":    result = handleHscan(args);      break;
                case "LPUSH":    result = handleLpush(args);      break;
                case "RPUSH":    result = handleRpush(args);      break;
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
                    result = RespError.unknownCommand(cmd);
            }
            if (slowLog != null) {
                long duration = System.nanoTime() - startTime;
                slowLog.log(duration, args);
            }
            // MONITOR 转发：向所有 MONITOR 客户端推送命令
            if (!monitorClients.isEmpty() && !"MONITOR".equals(cmd)) {
                forwardToMonitors(args);
            }
            // 写命令收尾：AOF 追加 + RDB 写入计数
            propagateWriteToPersistence(args, result);
            return result;
        } catch (Exception e) {
            logger.error("Error executing command: {} - {}", cmd, e.getMessage(), e);
            return RespError.of("ERR", "internal error: " + e.getMessage());
        }
    }

    // ==================== 连接命令 ====================

    private Object handleAuth(String[] args) {
        if (password == null) return RespError.of("ERR", "AUTH called without any password configured");
        if (args.length != 2) return RespError.wrongNumberOfArguments("AUTH");
        if (password.equals(args[1])) { authenticated = true; return RespSimpleString.of("OK"); }
        return RespError.of("WRONGPASS", "invalid username-password pair or user is disabled.");
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
            int db = Integer.parseInt(args[1]);
            if (db < 0 || db > 15) return RespError.of("ERR", "DB index is out of range");
            this.currentDb = db;
            return RespSimpleString.of("OK");
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "invalid DB index");
        }
    }

    // ==================== String 命令 ====================

    private Object handleSet(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("SET");
        String key = args[1], value = args[2];
        Integer expireSeconds = null; Long expireMillis = null;
        boolean nx = false, xx = false;
        for (int i = 3; i < args.length; i++) {
            String opt = args[i].toUpperCase();
            switch (opt) {
                case "EX": if (i+1>=args.length) return RespError.syntaxError();
                    try { expireSeconds = Integer.parseInt(args[++i]); } catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); } break;
                case "PX": if (i+1>=args.length) return RespError.syntaxError();
                    try { expireMillis = Long.parseLong(args[++i]); } catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); } break;
                case "NX": nx = true; break;
                case "XX": xx = true; break;
                default: return RespError.syntaxError();
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
        else if (expireSeconds != null) store.setexDb(currentDb, key, expireSeconds, val);
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
        for (int i = 1; i < args.length; i++) {
            String k = args[i];
            // 每种类型都要清：以前五路里第一条命中就 continue，万一某个键名下真的并存着两种类型
            // （1.3.4 及之前写出来的、或手工塞进 store 的），DEL 会回 1 让调用方以为删干净了，
            // 实际另一份数据还在，DBSIZE 也还把它算成一个键。
            boolean removed = false;
            if (store.delDb(currentDb, k)) removed = true;
            if (store.getHashStore(currentDb).del(k)) removed = true;
            if (store.getListStore(currentDb).del(k)) removed = true;
            if (store.getSetStore(currentDb).del(k)) removed = true;
            if (store.getSortedSetStore(currentDb).del(k)) removed = true;
            if (removed) count++;
        }
        return RespInteger.of(count);
    }

    private Object handleExists(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("EXISTS");
        long c = 0;
        for (int i = 1; i < args.length; i++) if (keyExists(args[i])) c++;
        return RespInteger.of(c);
    }

    private Object handleExpire(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("EXPIRE");
        try { return RespInteger.of(store.expireDb(currentDb, args[1], Integer.parseInt(args[2])) ? 1 : 0); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
    }

    private Object handlePexpire(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("PEXPIRE");
        try { return RespInteger.of(store.pexpireDb(currentDb, args[1], Long.parseLong(args[2])) ? 1 : 0); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
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

    private Object handleSetex(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("SETEX");
        try { store.setexDb(currentDb, args[1], Integer.parseInt(args[2]), args[3].getBytes(StandardCharsets.UTF_8)); return RespSimpleString.of("OK"); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
    }

    private Object handlePsetex(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("PSETEX");
        try { store.psetexDb(currentDb, args[1], Long.parseLong(args[2]), args[3].getBytes(StandardCharsets.UTF_8)); return RespSimpleString.of("OK"); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
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
        if (args.length < 3 || args.length%2==0) return RespError.wrongNumberOfArguments("MSET");
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

    private Object handleIncr(String[] args, long delta) {
        if (args.length != 2) return RespError.wrongNumberOfArguments(delta>0?"INCR":"DECR");
        try { return RespInteger.of(store.incrementDb(currentDb, args[1], delta)); }
        catch (IllegalArgumentException e) { return RespError.of("ERR", e.getMessage()); }
    }

    private Object handleIncrby(String[] args, long sign) {
        if (args.length != 3) return RespError.wrongNumberOfArguments(sign>0?"INCRBY":"DECRBY");
        try { long d = Long.parseLong(args[2]); return RespInteger.of(store.incrementDb(currentDb, args[1], sign>0?d:Math.negateExact(d))); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
        catch (ArithmeticException e) { return RespError.of("ERR","increment or decrement would overflow"); }
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
        return RespSimpleString.of(store.typeOfDb(currentDb, args[1]).name().toLowerCase());
    }

    private Object handleDbsize() { return RespInteger.of(store.dbsizeDb(currentDb)); }

    // ==================== Key 命令 ====================

    private Object handleRename(String[] args, boolean nx) {
        if (args.length != 3) return RespError.wrongNumberOfArguments(nx?"RENAMENX":"RENAME");
        String src = args[1], dst = args[2];
        if (src.equals(dst)) return RespError.of("ERR","source and destination objects are the same");
        if (nx && keyExists(dst)) return RespInteger.of(0);
        if (store.existsDb(currentDb, src)) {
            byte[] val = store.getDb(currentDb, src); Long ttlMs = store.pttlDb(currentDb, src); store.delDb(currentDb, src);
            store.setDb(currentDb, dst, val); if (ttlMs > 0) store.pexpireDb(currentDb, dst, ttlMs);
            return nx ? RespInteger.of(1) : RespSimpleString.of("OK");
        }
        if (store.getHashStore(currentDb).exists(src)) { Map<String,byte[]> m = store.getHashStore(currentDb).hgetall(src); store.getHashStore(currentDb).del(src); store.getHashStore(currentDb).hmset(dst, m); return nx?RespInteger.of(1):RespSimpleString.of("OK"); }
        if (store.getListStore(currentDb).exists(src)) { List<byte[]> l = store.getListStore(currentDb).lrange(src,0,-1); store.getListStore(currentDb).del(src); store.getListStore(currentDb).rpush(dst, l.toArray(new byte[0][])); return nx?RespInteger.of(1):RespSimpleString.of("OK"); }
        if (store.getSetStore(currentDb).exists(src)) { List<byte[]> s = store.getSetStore(currentDb).smembers(src); store.getSetStore(currentDb).del(src); store.getSetStore(currentDb).sadd(dst, s.toArray(new byte[0][])); return nx?RespInteger.of(1):RespSimpleString.of("OK"); }
        if (store.getSortedSetStore(currentDb).exists(src)) {
            List<byte[]> r = store.getSortedSetStore(currentDb).zrange(src, 0, -1, true);
            store.getSortedSetStore(currentDb).del(src);
            for (int i = 0; i < r.size(); i += 2) {
                double score = Double.parseDouble(new String(r.get(i+1), StandardCharsets.UTF_8));
                store.getSortedSetStore(currentDb).zadd(dst, score, r.get(i));
            }
            return nx ? RespInteger.of(1) : RespSimpleString.of("OK");
        }
        return RespError.noSuchKey();
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
        try { return RespInteger.of(store.getHashStore(currentDb).hincrby(args[1], args[2], Long.parseLong(args[3]))); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
        catch (IllegalArgumentException e) { return RespError.of("ERR", e.getMessage()); }
    }

    private Object handleHlen(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("HLEN");
        return RespInteger.of(store.getHashStore(currentDb).hlen(args[1]));
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
            List<byte[]> l = store.getListStore(currentDb).lrange(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            Object[] r = new Object[l.size()]; for (int i=0;i<l.size();i++) r[i]=l.get(i)==null?RespBulkString.nullBulkString():RespBulkString.of(l.get(i));
            return RespArray.of(r);
        } catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
    }

    private Object handleLindex(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("LINDEX");
        try { byte[] v = store.getListStore(currentDb).lindex(args[1], Integer.parseInt(args[2])); return v==null?RespBulkString.nullBulkString():RespBulkString.of(v); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
    }

    private Object handleLlen(String[] args) {
        if (args.length != 2) return RespError.wrongNumberOfArguments("LLEN");
        return RespInteger.of(store.getListStore(currentDb).llen(args[1]));
    }

    private Object handleLset(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("LSET");
        try { store.getListStore(currentDb).lset(args[1], Integer.parseInt(args[2]), args[3].getBytes(StandardCharsets.UTF_8)); return RespSimpleString.of("OK"); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
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
        try { return RespInteger.of(store.getListStore(currentDb).lrem(args[1], Integer.parseInt(args[2]), args[3].getBytes(StandardCharsets.UTF_8))); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
    }

    private Object handleLtrim(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("LTRIM");
        try { store.getListStore(currentDb).ltrim(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3])); return RespSimpleString.of("OK"); }
        catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
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
        int count = args.length==3 ? Integer.parseInt(args[2]) : 1;
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

    private Object handleZadd(String[] args) {
        if (args.length < 4 || (args.length-2)%2!=0) return RespError.wrongNumberOfArguments("ZADD");
        long added = 0;
        for (int i = 2; i < args.length; i += 2) {
            try { added += store.getSortedSetStore(currentDb).zadd(args[1], Double.parseDouble(args[i]), args[i+1].getBytes(StandardCharsets.UTF_8)); }
            catch (NumberFormatException e) { return RespError.of("ERR","value is not a valid float"); }
        }
        return RespInteger.of(added);
    }

    private Object handleZrem(String[] args) {
        if (args.length < 3) return RespError.wrongNumberOfArguments("ZREM");
        byte[][] m = new byte[args.length-2][]; for (int i=2;i<args.length;i++) m[i-2]=args[i].getBytes(StandardCharsets.UTF_8);
        return RespInteger.of(store.getSortedSetStore(currentDb).zrem(args[1], m));
    }

    private Object handleZscore(String[] args) {
        if (args.length != 3) return RespError.wrongNumberOfArguments("ZSCORE");
        Double s = store.getSortedSetStore(currentDb).zscore(args[1], args[2].getBytes(StandardCharsets.UTF_8));
        return s==null ? RespBulkString.nullBulkString() : RespBulkString.of(formatDouble(s));
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
            List<byte[]> r = reverse ? store.getSortedSetStore(currentDb).zrevrange(args[1], Long.parseLong(args[2]), Long.parseLong(args[3]), ws)
                    : store.getSortedSetStore(currentDb).zrange(args[1], Long.parseLong(args[2]), Long.parseLong(args[3]), ws);
            return toRespArray(r);
        } catch (NumberFormatException e) { return RespError.of("ERR","value is not an integer or out of range"); }
    }

    private Object handleZrangebyscore(String[] args, boolean reverse) {
        if (args.length < 4) return RespError.wrongNumberOfArguments(reverse?"ZREVRANGEBYSCORE":"ZRANGEBYSCORE");
        try {
            double min = parseScore(args[2]), max = parseScore(args[3]);
            boolean ws=false; int offset=0, count=-1;
            for (int i=4;i<args.length;i++) { if ("WITHSCORES".equalsIgnoreCase(args[i])) ws=true; else if ("LIMIT".equalsIgnoreCase(args[i])&&i+2<args.length) { offset=Integer.parseInt(args[++i]); count=Integer.parseInt(args[++i]); } }
            List<byte[]> r = reverse ? store.getSortedSetStore(currentDb).zrevrangebyscore(args[1], max, min, ws, offset, count)
                    : store.getSortedSetStore(currentDb).zrangebyscore(args[1], min, max, ws, offset, count);
            return toRespArray(r);
        } catch (NumberFormatException e) { return RespError.of("ERR","value is not a valid float"); }
    }

    private Object handleZincrby(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("ZINCRBY");
        try {
            double ns = store.getSortedSetStore(currentDb).zincrby(args[1], Double.parseDouble(args[2]), args[3].getBytes(StandardCharsets.UTF_8));
            return RespBulkString.of(formatDouble(ns));
        } catch (NumberFormatException e) { return RespError.of("ERR","value is not a valid float"); }
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
        switch (args[1].toUpperCase()) {
            case "CHANNELS": { String p=args.length>2?args[2]:null; Set<String> c=pubSub.getChannels(p); Object[] r=new Object[c.size()]; int i=0; for (String s:c) r[i++]=RespBulkString.of(s); return RespArray.of(r); }
            case "NUMSUB": { String[] ch=args.length>2?Arrays.copyOfRange(args,2,args.length):new String[0]; Map<String,Integer> n=pubSub.getNumSub(ch); List<Object> r=new ArrayList<>(); for (Map.Entry<String,Integer> e:n.entrySet()) { r.add(RespBulkString.of(e.getKey())); r.add(RespInteger.of(e.getValue())); } return RespArray.of(r); }
            case "NUMPAT": return RespInteger.of(pubSub.getNumPat());
            default: return RespError.syntaxError();
        }
    }

    // ==================== 管理命令 ====================

    private Object handleSlowlog(String[] args) {
        if (args.length<2) return RespError.wrongNumberOfArguments("SLOWLOG");
        if (slowLog==null) return RespError.of("ERR","SlowLog not configured");
        switch (args[1].toUpperCase()) {
            case "GET": { int c=args.length>2?Integer.parseInt(args[2]):10; List<SlowLog.SlowLogEntry> e=slowLog.get(c); Object[] r=new Object[e.size()]; for(int i=0;i<e.size();i++) { SlowLog.SlowLogEntry en=e.get(i); r[i]=RespArray.of(RespInteger.of(en.getId()),RespInteger.of(en.getTimestampNanos()/1000),RespInteger.of(en.getDurationNanos()/1000),toRespArray(en.getArgs())); } return RespArray.of(r); }
            case "LEN": return RespInteger.of(slowLog.len());
            case "RESET": slowLog.reset(); return RespSimpleString.of("OK");
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
        if (rdbPersistence == null) {
            return RespError.of("ERR", "SAVE is not supported: no data directory configured");
        }
        try {
            rdbPersistence.save();
            return RespSimpleString.of("OK");
        } catch (Exception e) {
            logger.error("SAVE failed: {}", e.getMessage(), e);
            return RespError.of("ERR", "save failed: " + e.getMessage());
        }
    }

    /** BGSAVE — 受理后台快照；已有快照在跑时如实拒绝，与 Redis 行为一致。 */
    private Object handleBgsave() {
        if (rdbPersistence == null) {
            return RespError.of("ERR", "BGSAVE is not supported: no data directory configured");
        }
        if (!rdbPersistence.saveAsync()) {
            return RespError.of("ERR", "Background save already in progress. Please wait");
        }
        return RespSimpleString.of("Background saving started");
    }

    /** LASTSAVE — 最近一次成功快照的 Unix 秒；从未成功过则为 0，不再拿当前时间冒充。 */
    private Object handleLastsave() {
        return RespInteger.of(rdbPersistence == null ? 0L : rdbPersistence.getLastSaveTime());
    }

    private Object handleFlushdb() { store.flushDb(currentDb); return RespSimpleString.of("OK"); }

    private Object handleFlushall() { store.flushAll(); return RespSimpleString.of("OK"); }

    private Object handleInfo(String[] args) {
        String sec = args.length > 1 ? args[1].toUpperCase() : null;
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
        switch (args[1].toUpperCase()) {
            case "LIST": {
                // 列出所有活着的连接。以前这里只拼自己一条，等于"连接列表"里永远只有一个元素，
                // 而且 sub=/psub= 恒为 0 —— 因为订阅中的连接根本走不到 CLIENT（被 pubsub 闸门挡了），
                // 能从 socket 看到这些字段的只有别的连接。现在由旁观者来读，才真的量得到。
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<ChannelHandlerContext, CommandHandler> entry : connections.entrySet()) {
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
        sb.append(" cmd=").append(lastCommand.toLowerCase());
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
                id = Long.parseLong(args[3]);
            } catch (NumberFormatException e) {
                return null;
            }
            for (java.util.Map.Entry<ChannelHandlerContext, CommandHandler> entry : connections.entrySet()) {
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
        for (java.util.Map.Entry<ChannelHandlerContext, CommandHandler> entry : connections.entrySet()) {
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
     * DEBUG 命令：调试工具（SLEEP / OBJECT / SLOWLOG-RESET / ERROR）。
     * <p>仅实现安全子命令，不暴露 SEGFAULT 等危险操作。
     */
    private Object handleDebug(String[] args) {
        if (args.length < 2) return RespError.wrongNumberOfArguments("DEBUG");
        switch (args[1].toUpperCase()) {
            case "SLEEP": {
                if (args.length < 3) return RespError.wrongNumberOfArguments("DEBUG SLEEP");
                try {
                    long ms = Long.parseLong(args[2]);
                    Thread.sleep(ms);
                    return RespSimpleString.of("OK");
                } catch (NumberFormatException e) {
                    return RespError.of("ERR", "value is not an integer or out of range");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return RespError.of("ERR", "sleep interrupted");
                }
            }
            case "OBJECT": {
                // 以前回的是 "Value at:0x<key.hashCode()> refcount:1 ... serializedlength:0 lru:0"：
                // 地址是哈希值假扮的、refcount/lru 是常量、serializedlength 恒为 0，
                // 四个字段没有一个是量出来的。Redis 自己也已经把这条废弃掉了。
                return RespError.of("ERR", "DEBUG OBJECT is not supported: refcount / lru / serializedlength"
                        + " cannot be measured from the JVM, and reporting constants would be worse than an error");
            }
            case "SLOWLOG-RESET": {
                if (slowLog == null) return RespError.of("ERR", "SlowLog not configured");
                slowLog.reset();
                // Redis 回 +OK；回 :1 会让按 Redis 协议写的客户端把整型当成解析失败。
                return RespSimpleString.of("OK");
            }
            case "ERROR": {
                // DEBUG ERROR — 返回错误（用于测试客户端错误处理）
                return RespError.of("ERR", "DEBUG ERROR: this is a debug error");
            }
            default:
                return RespError.of("ERR", "DEBUG subcommand '" + args[1] + "' not supported. Supported: SLEEP, OBJECT, SLOWLOG-RESET, ERROR");
        }
    }

    /**
     * MONITOR 命令：开启/关闭实时命令监控。
     * <p>MONITOR 开启后，该连接进入监控模式，服务端将所有命令推送到该连接。
     * 再次执行 MONITOR 关闭监控。
     */
    private Object handleMonitor(String[] args) {
        if (channelContext == null) return RespError.of("ERR", "no connection context");
        if (monitorClients.contains(channelContext)) {
            // 已在 MONITOR 模式，再次执行则退出
            monitorClients.remove(channelContext);
            return RespSimpleString.of("OK");
        }
        monitorClients.add(channelContext);
        // Redis 兼容：MONITOR 返回 OK，然后开始推送命令
        return RespSimpleString.of("OK");
    }

    /**
     * RESET 命令：重置连接状态（退出 MONITOR 模式、清除客户端名称、切换到 db0）。
     */
    private Object handleReset() {
        monitorClients.remove(channelContext);
        this.clientName = null;
        this.currentDb = 0;
        return RespSimpleString.of("OK");
    }

    /**
     * 向所有 MONITOR 客户端转发命令。
     * <p>格式与 Redis MONITOR 兼容：timestamp.epoch [db id addr] "command" "arg1" "arg2" ...
     */
    private void forwardToMonitors(String[] args) {
        if (monitorClients.isEmpty()) return;
        long epoch = System.currentTimeMillis() / 1000;
        int db = currentDb;
        int id = channelContext.channel().hashCode() & 0x7FFFFFFF;
        String addr = String.valueOf(channelContext.channel().remoteAddress());

        StringBuilder sb = new StringBuilder();
        sb.append(epoch).append(".000000 [").append(db).append(" ").append(id).append(" ").append(addr).append("]");
        for (String arg : args) {
            sb.append(" \"").append(arg.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }

        RespBulkString msg = RespBulkString.of(sb.toString());
        java.util.Iterator<ChannelHandlerContext> it = monitorClients.iterator();
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

    // ==================== Stream 命令 ====================

    /**
     * XADD key [MAXLEN maxlen] id field value [field value ...]
     */
    private Object handleXadd(String[] args) {
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
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
                maxLen = Long.parseLong(args[i]);
            } catch (NumberFormatException e) {
                return RespError.of("ERR", "value is not an integer or out of range");
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
            String entryId = streamStore.xadd(currentDb, key, fields, id, maxLen);
            return RespBulkString.of(entryId);
        } catch (Exception e) {
            return RespError.of("ERR", e.getMessage());
        }
    }

    /**
     * XLEN key
     */
    private Object handleXlen(String[] args) {
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length != 2) return RespError.wrongNumberOfArguments("XLEN");
        return RespInteger.of((int) streamStore.xlen(currentDb, args[1]));
    }

    /**
     * XRANGE key start end [COUNT count]
     * XREVRANGE key end start [COUNT count]
     */
    private Object handleXrange(String[] args, boolean reverse) {
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments(reverse ? "XREVRANGE" : "XRANGE");

        String key = args[1];
        String start = args[2];
        String end = args[3];
        int count = -1;

        if (args.length > 4 && "COUNT".equalsIgnoreCase(args[4]) && args.length > 5) {
            count = Integer.parseInt(args[5]);
        }

        List<StreamEntry> entries = reverse
                ? streamStore.xrevrange(currentDb, key, end, start, count)
                : streamStore.xrange(currentDb, key, start, end, count);

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
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 3) return RespError.wrongNumberOfArguments("XDEL");
        String[] ids = new String[args.length - 2];
        System.arraycopy(args, 2, ids, 0, ids.length);
        return RespInteger.of((int) streamStore.xdel(currentDb, args[1], ids));
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
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments("XTRIM");
        if (!"MAXLEN".equalsIgnoreCase(args[2])) {
            return RespError.of("ERR", "unsupported XTRIM strategy '" + args[2] + "', only MAXLEN is implemented");
        }
        int idx = "~".equals(args[3]) || "=".equals(args[3]) ? 4 : 3;
        if (idx != args.length - 1) return RespError.syntaxError();
        long maxLen;
        try {
            maxLen = Long.parseLong(args[idx]);
        } catch (NumberFormatException e) {
            return RespError.of("ERR", "value is not an integer or out of range");
        }
        if (maxLen < 0) return RespError.of("ERR", "MAXLEN requires a non-negative integer");
        return RespInteger.of((int) streamStore.xtrim(currentDb, args[1], maxLen));
    }

    /**
     * XREAD [COUNT count] [BLOCK milliseconds] STREAMS key [key ...] id [id ...]
     */
    private Object handleXread(String[] args) {
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments("XREAD");

        int count = -1;
        int i = 1;

        // 解析 COUNT
        if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
            count = Integer.parseInt(args[i + 1]);
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
            List<StreamEntry> entries = streamStore.xrange(currentDb, keys[k], ids[k], "+", count);
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
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 7) return RespError.wrongNumberOfArguments("XREADGROUP");

        int i = 1;
        if (!"GROUP".equalsIgnoreCase(args[i])) return RespError.syntaxError();
        String group = args[i + 1];
        String consumer = args[i + 2];
        i += 3;

        int count = -1;
        if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
            count = Integer.parseInt(args[i + 1]);
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

        Map<String, List<StreamEntry>> result = streamStore.xreadgroup(currentDb, group, consumer, streams, count);

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
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 2) return RespError.wrongNumberOfArguments("XGROUP");
        String sub = args[1].toUpperCase();
        switch (sub) {
            case "CREATE": {
                if (args.length < 5) return RespError.wrongNumberOfArguments("XGROUP CREATE");
                boolean ok = streamStore.xgroupCreate(currentDb, args[2], args[3], args[4]);
                return ok ? RespSimpleString.of("OK") : RespError.of("ERR", "BUSYGROUP Consumer Group name already exists");
            }
            case "DESTROY": {
                if (args.length < 4) return RespError.wrongNumberOfArguments("XGROUP DESTROY");
                boolean ok = streamStore.xgroupDestroy(currentDb, args[2], args[3]);
                return RespInteger.of(ok ? 1 : 0);
            }
            case "CREATECONSUMER": {
                if (args.length < 5) return RespError.wrongNumberOfArguments("XGROUP CREATECONSUMER");
                com.zifang.z.cache.core.stream.Stream stream = streamStore.getStream(currentDb, args[2]);
                if (stream == null) return RespInteger.of(0);
                com.zifang.z.cache.core.stream.ConsumerGroup cg = stream.getGroup(args[3]);
                if (cg == null) return RespInteger.of(0);
                cg.getOrCreateConsumer(args[4]);
                return RespInteger.of(1);
            }
            case "DELCONSUMER": {
                if (args.length < 5) return RespError.wrongNumberOfArguments("XGROUP DELCONSUMER");
                com.zifang.z.cache.core.stream.Stream stream = streamStore.getStream(currentDb, args[2]);
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
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 4) return RespError.wrongNumberOfArguments("XACK");
        String[] ids = new String[args.length - 3];
        System.arraycopy(args, 3, ids, 0, ids.length);
        return RespInteger.of((int) streamStore.xack(currentDb, args[1], args[2], ids));
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
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 3) return RespError.wrongNumberOfArguments("XPENDING");
        if (args.length > 3) {
            return RespError.of("ERR", "XPENDING detail form (IDLE / start / end / count) is not supported");
        }

        Object[] summary = streamStore.xpending(currentDb, args[1], args[2]);
        if (summary == null) {
            return RespError.of("NOGROUP",
                    "No such key '" + args[1] + "' or consumer group '" + args[2] + "'");
        }

        long pendingCount = (Long) summary[0];
        String lowestId = (String) summary[1];
        String highestId = (String) summary[2];

        com.zifang.z.cache.core.stream.Stream stream = streamStore.getStream(currentDb, args[1]);
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
        if (streamStore == null) return RespError.of("ERR", "Stream not configured");
        if (args.length < 3) return RespError.wrongNumberOfArguments("XINFO");
        String sub = args[1].toUpperCase();
        switch (sub) {
            case "GROUPS": {
                com.zifang.z.cache.core.stream.Stream stream = streamStore.getStream(currentDb, args[2]);
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
                com.zifang.z.cache.core.stream.Stream stream = streamStore.getStream(currentDb, args[2]);
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
                com.zifang.z.cache.core.stream.Stream target = streamStore.getStream(currentDb, args[2]);
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
        "SET", "SETEX", "PSETEX", "SETNX", "GETSET", "MSET", "APPEND", "INCR", "DECR", "INCRBY", "DECRBY",
        "DEL", "EXPIRE", "PEXPIRE", "PERSIST", "RENAME", "RENAMENX",
        "HSET", "HDEL", "HMSET", "HINCRBY", "HINCRBYFLOAT", "HSETNX",
        "LPUSH", "RPUSH", "LPOP", "RPOP", "LSET", "LINSERT", "LREM", "LTRIM", "RPOPLPUSH", "LMOVE",
        "SADD", "SREM", "SMOVE", "SPOP", "SINTERSTORE", "SUNIONSTORE", "SDIFFSTORE",
        "ZADD", "ZREM", "ZINCRBY", "ZREMRANGEBYLEX", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE",
        "FLUSHDB", "FLUSHALL"
    ));

    /** 阻塞命令 → 非阻塞等价命令的映射；见 {@link #aofRecordFor}。 */
    private static final java.util.Set<String> BLOCKING_POP_COMMANDS =
            new java.util.HashSet<>(java.util.Arrays.asList("BLPOP", "BRPOP", "BRPOPLPUSH"));

    /**
     * AOF 重放期间为 true：此时每条命令都要照常执行，但绝不能再写回 AOF，
     * 否则开机重放一次，AOF 就把自己抄了一份，越长越离谱。
     */
    private static volatile boolean loading;

    public static void setLoading(boolean loading) { CommandHandler.loading = loading; }

    /**
     * 写命令执行完之后的两件收尾事：追加 AOF、给 RDB 调度器记一次"库变了"。
     * <p>
     * {@code RdbPersistence.onWrite()} 以前一个调用方都没有，于是 {@code writeCounter} 恒为 0、
     * {@code shouldSave()} 永远为 false —— 就算把调度器 start 起来，它也只会每 N 秒空转一次
     * 判断"没有任何写入"。定时快照要成立，这一记必须有人打。
     */
    private void propagateWriteToPersistence(String[] args, Object result) {
        if (loading || args.length == 0) {
            return;
        }
        String cmd = args[0].toUpperCase();
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
        // aofPersistence 是 null，而事务照样得能中止。
        bumpWatchedKeys(record);

        if (rdbPersistence != null) {
            rdbPersistence.onWrite();
        }
        if (aofPersistence == null) {
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
            aofPersistence.appendCommand(new String[]{"SELECT", Integer.toString(currentDb)});
        }
        aofPersistence.appendCommand(record);
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
                "INCR", "DECR", "INCRBY", "DECRBY"}) {
            typed(c, string, KeyPos.SINGLE);
        }
        typed("MGET", string, KeyPos.ALL);

        for (String c : new String[]{"HSET", "HGET", "HDEL", "HEXISTS", "HGETALL", "HKEYS", "HVALS",
                "HMGET", "HMSET", "HLEN", "HSETNX", "HSCAN", "HRANDFIELD", "HINCRBY", "HINCRBYFLOAT"}) {
            typed(c, hash, KeyPos.SINGLE);
        }

        for (String c : new String[]{"LPUSH", "RPUSH", "LPOP", "RPOP", "LLEN", "LRANGE", "LINDEX",
                "LSET", "LINSERT", "LREM", "LTRIM"}) {
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

        for (String c : new String[]{"ZADD", "ZREM", "ZSCORE", "ZRANK", "ZREVRANK", "ZCARD", "ZCOUNT",
                "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZREVRANGEBYSCORE", "ZINCRBY", "ZLEXCOUNT",
                "ZRANGEBYLEX", "ZREVRANGEBYLEX", "ZREMRANGEBYLEX", "ZREMRANGEBYRANK",
                "ZREMRANGEBYSCORE", "ZRANDMEMBER", "ZSCAN"}) {
            typed(c, zset, KeyPos.SINGLE);
        }
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
        TypeSpec spec = TYPED_COMMANDS.get(args[0].toUpperCase());
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
        String cmd = record[0].toUpperCase();
        if ("MSET".equals(cmd)) {
            for (int i = 1; i + 1 < record.length; i += 2) {
                store.bumpKeyVersion(currentDb, record[i]);
            }
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

    private static double parseScore(String s) { if ("+inf".equalsIgnoreCase(s)||"inf".equalsIgnoreCase(s)) return Double.POSITIVE_INFINITY; if ("-inf".equalsIgnoreCase(s)) return Double.NEGATIVE_INFINITY; return Double.parseDouble(s); }
    private static String formatDouble(double v) { String s=String.valueOf(v); if (s.contains(".")&&!s.contains("E")&&!s.contains("e")) { int l=s.length(); while(l>1&&s.charAt(l-1)=='0') l--; if(l>1&&s.charAt(l-1)=='.') l--; s=s.substring(0,l); } return s; }
    private static String fmtBytes(long b) { if(b<1024)return b+"B"; double k=b/1024.0; if(k<1024)return String.format(java.util.Locale.ROOT,"%.2fKB",k); double m=k/1024.0; if(m<1024)return String.format(java.util.Locale.ROOT,"%.2fMB",m); return String.format(java.util.Locale.ROOT,"%.2fGB",m/1024.0); }

    // ==================== 新增 Hash 命令 ====================

    private Object handleHincrbyfloat(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("HINCRBYFLOAT");
        try {
            double result = store.getHashStore(currentDb).hincrbyfloat(args[1], args[2], Double.parseDouble(args[3]));
            return RespBulkString.of(formatDouble(result));
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
        int count = args.length == 3 ? Integer.parseInt(args[2]) : 1;
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
            return RespInteger.of(store.getSortedSetStore(currentDb).zremrangebyrank(args[1], Long.parseLong(args[2]), Long.parseLong(args[3])));
        } catch (NumberFormatException e) { return RespError.of("ERR", "value is not an integer or out of range"); }
    }

    private Object handleZremrangebyscore(String[] args) {
        if (args.length != 4) return RespError.wrongNumberOfArguments("ZREMRANGEBYSCORE");
        try {
            return RespInteger.of(store.getSortedSetStore(currentDb).zremrangebyscore(args[1], parseScore(args[2]), parseScore(args[3])));
        } catch (NumberFormatException e) { return RespError.of("ERR", "value is not a valid float"); }
    }

    private Object handleZrandmember(String[] args) {
        if (args.length < 2 || args.length > 3) return RespError.wrongNumberOfArguments("ZRANDMEMBER");
        int count = args.length == 3 ? Integer.parseInt(args[2]) : 1;
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
        try { timeout = Integer.parseInt(args[args.length - 1]); }
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
        try { timeout = Integer.parseInt(args[3]); }
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
        int count = args.length == 3 ? Integer.parseInt(args[2]) : 1;
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
        String pattern = null;
        int count = 10;
        for (int i = 2; i < args.length; i++) {
            if ("MATCH".equalsIgnoreCase(args[i]) && i + 1 < args.length) pattern = args[++i];
            else if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) count = Integer.parseInt(args[++i]);
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
