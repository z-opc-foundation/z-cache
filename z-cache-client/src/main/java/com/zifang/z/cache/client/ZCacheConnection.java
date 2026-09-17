package com.zifang.z.cache.client;

import com.zifang.z.cache.client.protocol.ClientRespDecoder;
import com.zifang.z.cache.client.protocol.ClientRespEncoder;
import com.zifang.z.cache.common.protocol.RespArray;
import com.zifang.z.cache.common.protocol.RespSimpleString;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 低层级的 ZCache 服务器连接
 * 管理 TCP 连接和 RESP 协议通信
 *
 * @author zifang
 * @since 1.0.0
 */
public class ZCacheConnection implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ZCacheConnection.class);

    /**
     * 客户端配置
     */
    private final ZCacheClientConfig config;
    
    /**
     * 事件循环组
     */
    private final EventLoopGroup eventLoopGroup;
    
    /**
     * 连接状态引用
     */
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    
    /**
     * 通道引用
     */
    private final AtomicReference<Channel> channelRef = new AtomicReference<>();
    
    /**
     * 按请求顺序关联服务器响应，支持流水线和并发异步请求。
     */
    private final BlockingQueue<CompletableFuture<Object>> pendingResponses = new LinkedBlockingQueue<>();

    /**
     * 构造函数
     *
     * @param config 客户端配置
     * @throws ZCacheClientException 如果配置为 null
     */
    public ZCacheConnection(ZCacheClientConfig config) {
        if (config == null) {
            throw new ZCacheClientException("config cannot be null");
        }
        this.config = config;
        this.eventLoopGroup = new NioEventLoopGroup(1);
    }

    /**
     * 连接到服务器
     *
     * @throws ZCacheClientException 如果连接失败或状态不允许连接
     */
    public synchronized void connect() {
        if (state.get() == ConnectionState.CONNECTED || state.get() == ConnectionState.AUTHENTICATED) {
            logger.debug("Already connected");
            return;
        }

        if (!state.compareAndSet(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING)) {
            throw new ZCacheClientException("Cannot connect in state: " + state.get());
        }

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectTimeout().toMillis())
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            if (config.isUseSsl()) {
                                try {
                                    pipeline.addLast("ssl", SslContextBuilder.forClient().build()
                                            .newHandler(ch.alloc(), config.getHost(), config.getPort()));
                                } catch (Exception e) {
                                    throw new ZCacheClientException("Failed to initialize SSL", e);
                                }
                            }
                            pipeline.addLast(new ClientRespDecoder());
                            pipeline.addLast(new ClientRespEncoder());
                            pipeline.addLast(new ResponseHandler());
                        }
                    });

            ChannelFuture future = bootstrap.connect(config.getHost(), config.getPort()).sync();
            Channel channel = future.channel();
            channelRef.set(channel);
            
            if (channel.isActive()) {
                state.set(ConnectionState.CONNECTED);
            }
            logger.info("Connected to {}:{}", config.getHost(), config.getPort());

            // Authenticate if password is set
            if (config.getPassword() != null) {
                authenticate(config.getPassword());
            }

            // Select database if not 0
            if (config.getDatabase() != 0) {
                select(config.getDatabase());
            }

        } catch (Exception e) {
            state.set(ConnectionState.ERROR);
            throw new ZCacheClientException("Failed to connect to " + config.getHost() + ":" + config.getPort(), e);
        }
    }

    /**
     * 使用密码进行认证
     *
     * @param password 密码
     * @throws ZCacheClientException 如果认证失败
     */
    private void authenticate(String password) {
        state.set(ConnectionState.AUTHENTICATING);
        try {
            Object response = sendCommand("AUTH", password);
            if (!isOk(response)) {
                throw new ZCacheClientException("Authentication failed: " + response);
            }
            state.set(ConnectionState.AUTHENTICATED);
            logger.debug("Authentication successful");
        } catch (Exception e) {
            state.set(ConnectionState.ERROR);
            throw new ZCacheClientException("Authentication failed", e);
        }
    }

    /**
     * 选择数据库
     *
     * @param database 数据库索引
     * @throws ZCacheClientException 如果选择数据库失败
     */
    private void select(int database) {
        Object response = sendCommand("SELECT", String.valueOf(database));
        if (!isOk(response)) {
            throw new ZCacheClientException("SELECT failed: " + response);
        }
        logger.debug("Selected database {}", database);
    }

    /**
     * 发送命令并等待响应
     *
     * @param command 命令名称
     * @param args    命令参数
     * @return 服务器响应
     * @throws ZCacheClientException 如果请求超时或失败
     */
    public Object sendCommand(String command, Object... args) {
        ensureConnected();

        CompletableFuture<Object> future = sendCommandAsync(command, args);
        try {
            return future.get(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            failPendingResponses(new ZCacheClientException("Request timeout", e));
            Channel channel = channelRef.getAndSet(null);
            if (channel != null) {
                channel.close();
            }
            state.compareAndSet(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED);
            state.compareAndSet(ConnectionState.AUTHENTICATED, ConnectionState.DISCONNECTED);
            throw new ZCacheClientException("Request timeout", e);
        } catch (Exception e) {
            pendingResponses.remove(future);
            throw new ZCacheClientException("Request failed", e);
        }
    }

    /**
     * 异步发送命令并返回响应 Future。多个 Future 按请求顺序完成。
     */
    public CompletableFuture<Object> sendCommandAsync(String command, Object... args) {
        ensureConnected();
        RespArray request = RespArray.command(command, args);
        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingResponses.offer(future);
        Channel channel = channelRef.get();
        if (channel == null) {
            pendingResponses.remove(future);
            future.completeExceptionally(new ZCacheClientException("Connection channel is unavailable"));
            return future;
        }
        channel.writeAndFlush(request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess() && pendingResponses.remove(future)) {
                future.completeExceptionally(writeFuture.cause());
            }
        });
        return future;
    }


    /**
     * 写入命令到 channel 但不立即 flush（用于 Pipeline 批量写入）。
     *
     * @param command 命令名称
     * @param args    命令参数
     * @return 响应 Future
     */
    public CompletableFuture<Object> writeCommand(String command, Object... args) {
        ensureConnected();
        RespArray request = RespArray.command(command, args);
        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingResponses.offer(future);
        Channel channel = channelRef.get();
        if (channel == null) {
            pendingResponses.remove(future);
            future.completeExceptionally(new ZCacheClientException("Connection channel is unavailable"));
            return future;
        }
        channel.write(request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess() && pendingResponses.remove(future)) {
                future.completeExceptionally(writeFuture.cause());
            }
        });
        return future;
    }

    /**
     * 刷新 channel，将所有缓存的命令一次性发送出去。
     */
    public void flush() {
        Channel channel = channelRef.get();
        if (channel != null && channel.isActive()) {
            channel.flush();
        }
    }

    private void failPendingResponses(Throwable cause) {
        CompletableFuture<Object> pending;
        while ((pending = pendingResponses.poll()) != null) {
            pending.completeExceptionally(cause);
        }
    }

    private void ensureConnected() {
        if (state.get() != ConnectionState.CONNECTED && state.get() != ConnectionState.AUTHENTICATED) {
            throw new ZCacheClientException("Not connected, current state: " + state.get());
        }
    }

    private boolean isOk(Object response) {
        if (response instanceof RespSimpleString) {
            return "OK".equals(((RespSimpleString) response).getValue());
        }
        return false;
    }

    /**
     * 关闭连接
     */
    @Override
    public synchronized void close() {
        if (state.get() == ConnectionState.CLOSED) {
            return;
        }

        state.set(ConnectionState.CLOSED);
        CompletableFuture<Object> pending;
        while ((pending = pendingResponses.poll()) != null) {
            pending.completeExceptionally(new ZCacheClientException("Connection closed"));
        }

        Channel channel = channelRef.getAndSet(null);
        if (channel != null) {
            channel.close();
        }

        eventLoopGroup.shutdownGracefully();
        logger.info("Connection closed");
    }

    /**
     * 获取连接状态
     *
     * @return 连接状态
     */
    public ConnectionState getState() {
        return state.get();
    }

    /**
     * 设置连接状态
     *
     * @param state 连接状态
     */
    public void setState(ConnectionState state) {
        this.state.set(state);
    }

    /**
     * 检查是否已连接
     *
     * @return 如果已连接返回 true，否则返回 false
     */
    public boolean isConnected() {
        return state.get() == ConnectionState.CONNECTED || state.get() == ConnectionState.AUTHENTICATED;
    }

    /**
     * 检查是否已关闭
     *
     * @return 如果已关闭返回 true，否则返回 false
     */
    public boolean isClosed() {
        return state.get() == ConnectionState.CLOSED;
    }

    /**
     * 获取客户端配置
     *
     * @return 客户端配置
     */
    public ZCacheClientConfig getConfig() {
        return config;
    }

    /**
     * 获取主机地址
     *
     * @return 主机地址
     */
    public String getHost() {
        return config.getHost();
    }

    /**
     * 获取端口号
     *
     * @return 端口号
     */
    public int getPort() {
        return config.getPort();
    }

    /**
     * Netty 处理器，用于处理服务器响应
     */
    private class ResponseHandler extends ChannelInboundHandlerAdapter {
        /**
         * 处理接收到的消息
         *
         * @param ctx 通道上下文
         * @param msg 消息
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            CompletableFuture<Object> future = pendingResponses.poll();
            if (future != null) {
                future.complete(msg);
            } else {
                logger.warn("Received unexpected response: {}", msg);
            }
        }

        /**
         * 处理异常
         *
         * @param ctx   通道上下文
         * @param cause 异常原因
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            failPendingResponses(cause);
        }

        /**
         * 处理通道非活动事件
         *
         * @param ctx 通道上下文
         */
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (state.get() != ConnectionState.CLOSED) {
                state.set(ConnectionState.DISCONNECTED);
                failPendingResponses(new ZCacheClientException("Connection closed"));
                logger.warn("Channel inactive, connection lost");
            }
        }
    }
}
