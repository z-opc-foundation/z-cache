package com.zifang.z.cache.core.command;

import com.zifang.z.cache.core.logging.SlowLog;
import com.zifang.z.cache.core.persistence.AofPersistence;
import com.zifang.z.cache.core.persistence.RdbPersistence;
import com.zifang.z.cache.core.pubsub.PubSubManager;
import com.zifang.z.cache.core.stream.StreamStore;
import io.netty.channel.ChannelHandlerContext;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 一台服务器实例自己的那一份共享状态。
 *
 * <p>存在的理由是一个具体的形状：一个 JVM 里可以同时起多台 {@code RedisServer}
 * （嵌入式、以及整模块连跑的测试就是这个形态）。这些组件原先挂在 {@link CommandHandler}
 * 的静态字段上，后起的那台会把先起那台的行为改掉 —— 实测过的两条见
 * {@code RedisServerProtocolSemanticsTest} 里 {@code streamKeyspaceIsScopedToOneServerInstance}
 * 与 {@code serverWithoutDataDirDoesNotDisableOtherServersPersistence}。
 *
 * <p>{@code rdbPersistence} / {@code aofPersistence} / {@code loading} 是 volatile 而不是 final：
 * 一台服务器要等 {@code initPersistence()} 恢复完 RDB/AOF 才知道有没有这两样，
 * 而那发生在 bind 之前、连接建立之前，所以连接的 handler 一定看得到终值。
 */
public final class ServerScope {

    private final PubSubManager pubSubManager;
    private final ConcurrentMap<ChannelHandlerContext, CommandHandler> connections;
    private final Set<ChannelHandlerContext> monitorClients;
    private final StreamStore streamStore;
    private final SlowLog slowLog;

    private volatile RdbPersistence rdbPersistence;
    private volatile AofPersistence aofPersistence;
    private volatile boolean loading;

    public ServerScope(StreamStore streamStore, SlowLog slowLog) {
        this.pubSubManager = new PubSubManager();
        this.connections = new ConcurrentHashMap<>();
        this.monitorClients = ConcurrentHashMap.newKeySet();
        this.streamStore = streamStore;
        this.slowLog = slowLog;
    }

    public PubSubManager pubSubManager() {
        return pubSubManager;
    }

    /** 这台服务器的连接登记表：CLIENT LIST / KILL 的可见范围到这台为止。 */
    public ConcurrentMap<ChannelHandlerContext, CommandHandler> connections() {
        return connections;
    }

    /** 这台服务器上的 MONITOR 客户端。 */
    public Set<ChannelHandlerContext> monitorClients() {
        return monitorClients;
    }

    public StreamStore streamStore() {
        return streamStore;
    }

    public SlowLog slowLog() {
        return slowLog;
    }

    public RdbPersistence rdbPersistence() {
        return rdbPersistence;
    }

    public void setRdbPersistence(RdbPersistence rdbPersistence) {
        this.rdbPersistence = rdbPersistence;
    }

    public AofPersistence aofPersistence() {
        return aofPersistence;
    }

    public void setAofPersistence(AofPersistence aofPersistence) {
        this.aofPersistence = aofPersistence;
    }

    public boolean loading() {
        return loading;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }
}
