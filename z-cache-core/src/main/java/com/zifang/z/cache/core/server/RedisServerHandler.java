package com.zifang.z.cache.core.server;

import com.zifang.z.cache.common.protocol.RespArray;
import com.zifang.z.cache.common.protocol.RespError;
import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.command.ServerScope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import org.apache.logging.log4j.LogManager;
import com.zifang.z.cache.core.storage.MemoryStore;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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

    /** 会睡到"真的有值"为止的命令。它们不能占普通命令线程。 */
    private static final Set<String> BLOCKING_COMMANDS =
            new HashSet<>(Arrays.asList("BLPOP", "BRPOP", "BRPOPLPUSH"));

    private final CommandHandler commandHandler;
    private final MemoryStore store;
    private final EventExecutorGroup blockingGroup;

    /** 当前阻塞在这条连接上的工作线程，客户端断开时靠它打断等待。 */
    private volatile Thread blockedThread;
    private volatile boolean disconnected;

    /**
     * @param scope 这条连接所属服务器的那一份共享状态（pub/sub 管理器、连接登记表、MONITOR
     *              集合、StreamStore、SlowLog、RDB/AOF）。传 null 表示不属于任何服务器，
     *              读侧退回进程级默认值 —— 嵌入式与"手工搭管道"的测试走这条。
     */
    public RedisServerHandler(CommandHandler commandHandler, ServerScope scope,
                              MemoryStore store, EventExecutorGroup blockingGroup) {
        this.commandHandler = commandHandler;
        this.store = store;
        this.blockingGroup = blockingGroup;
        this.commandHandler.setChannelContext(null); // 会在 channelActive 中设置
        // 这条连接用自己服务器的那份共享状态。以前这里是逐样写 CommandHandler 的静态字段
        // （setPubSubManager / setStreamStore / setRdbPersistence ...）：每 accept 一条连接
        // 就全局覆写一次，一个 JVM 里两台服务器会互相串订阅态、串 Stream 键空间，
        // 后起的那台还会把前一台的持久化整个关掉。CLIENT LIST 也变成"列整个 JVM 的连接"。
        this.commandHandler.bindScope(scope);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Received: {}", msg);
        }

        // 确保 CommandHandler 持有当前 ctx
        commandHandler.setChannelContext(ctx);

        if (blockingGroup != null && isBlockingCommand(msg)) {
            // 挂起期间这条连接不再读字节：一是 Redis 的语义就是连接被阻塞，
            // 二是保证管道里后面的命令不会插到阻塞命令前面执行。
            ctx.channel().config().setAutoRead(false);
            final Object request = msg;
            blockingGroup.next().execute(() -> runBlocking(ctx, request));
            return;
        }

        Object response = commandHandler.handle(msg);

        if (response != null) {
            ctx.writeAndFlush(response);
        }
    }

    /**
     * 阻塞命令在专用线程组上执行完再回写。整条连接同一时刻只可能有一个在飞的命令
     * （autoRead 已关），所以 commandHandler 不会被并发使用。
     */
    private void runBlocking(ChannelHandlerContext ctx, Object request) {
        // 顺序有意义：先认领线程，再清标记，最后才看连接还在不在。
        // 反过来（先判断再认领）会留一个窗口 —— 断线发生在两者之间时，
        // channelInactive 读到 blockedThread 还是 null，没人来打断，这条任务就永久睡死。
        blockedThread = Thread.currentThread();
        // 清掉的可能只是上一轮断开时晚到的 interrupt，不该打到本轮自己的等待上。
        Thread.interrupted();
        if (disconnected) {
            blockedThread = null;
            return;
        }
        try {
            Object response = commandHandler.handle(request);
            if (response != null && ctx.channel().isActive()) {
                ctx.writeAndFlush(response);
            }
        } catch (Throwable t) {
            logger.error("Error running blocking command for {}", ctx.channel().remoteAddress(), t);
        } finally {
            blockedThread = null;
            // 打断只是叫醒这一次等待，标志位必须清掉：池化线程带着 interrupt 标记
            // 去跑下一个命令，会让它的 await 立刻抛异常。
            Thread.interrupted();
            if (!disconnected && ctx.channel().isActive()) {
                ctx.channel().config().setAutoRead(true);
            }
        }
    }

    private static boolean isBlockingCommand(Object msg) {
        if (!(msg instanceof RespArray)) {
            return false;
        }
        String[] args = ((RespArray) msg).toStringArray();
        if (args.length == 0 || args[0] == null) {
            return false;
        }
        return BLOCKING_COMMANDS.contains(args[0].toUpperCase(Locale.ROOT));
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client connected: {}", ctx.channel().remoteAddress());
        if (store != null) {
            store.incrementConnectedClient();
            store.incrementConnections();
        }
        commandHandler.setChannelContext(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
        disconnected = true;
        if (store != null) {
            store.decrementConnectedClient();
        }
        // 客户端中途跑路时，必须把还睡在 BLPOP 上的线程放回去：
        // 否则 {@code BLPOP key 0} + 断线会永久吃掉一个工作线程，十几次就让服务器瘫掉。
        Thread parked = blockedThread;
        if (parked != null) {
            parked.interrupt();
        }
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
