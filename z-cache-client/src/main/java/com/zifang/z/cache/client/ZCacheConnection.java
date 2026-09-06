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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-level connection to z-cache server
 * Manages TCP connection and RESP protocol communication
 */
public class ZCacheConnection implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ZCacheConnection.class);

    private final ZCacheClientConfig config;
    private final EventLoopGroup eventLoopGroup;
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicReference<Channel> channelRef = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Object>> pendingResponse = new AtomicReference<>();

    public ZCacheConnection(ZCacheClientConfig config) {
        this.config = config;
        this.eventLoopGroup = new NioEventLoopGroup(1);
    }

    /**
     * Connect to the server
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
                            pipeline.addLast(new ClientRespDecoder());
                            pipeline.addLast(new ClientRespEncoder());
                            pipeline.addLast(new ResponseHandler());
                        }
                    });

            ChannelFuture future = bootstrap.connect(config.getHost(), config.getPort()).sync();
            Channel channel = future.channel();
            channelRef.set(channel);

            state.set(ConnectionState.CONNECTED);
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
     * Authenticate with password
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
     * Select database
     */
    private void select(int database) {
        Object response = sendCommand("SELECT", String.valueOf(database));
        if (!isOk(response)) {
            throw new ZCacheClientException("SELECT failed: " + response);
        }
        logger.debug("Selected database {}", database);
    }

    /**
     * Send a command and wait for response
     */
    public Object sendCommand(String command, Object... args) {
        ensureConnected();

        // Build RESP array
        RespArray request = RespArray.command(command, args);

        // Create future for response
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (!pendingResponse.compareAndSet(null, future)) {
            throw new ZCacheClientException("Another request is pending");
        }

        try {
            Channel channel = channelRef.get();
            channel.writeAndFlush(request).sync();

            // Wait for response with timeout
            return future.get(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            pendingResponse.set(null);
            throw new ZCacheClientException("Request timeout", e);
        } catch (Exception e) {
            pendingResponse.set(null);
            throw new ZCacheClientException("Request failed", e);
        }
    }

    /**
     * Send command without waiting for response (fire-and-forget)
     */
    public void sendCommandAsync(String command, Object... args) {
        ensureConnected();
        RespArray request = RespArray.command(command, args);
        Channel channel = channelRef.get();
        channel.writeAndFlush(request);
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

    @Override
    public synchronized void close() {
        if (state.get() == ConnectionState.CLOSED) {
            return;
        }

        state.set(ConnectionState.CLOSED);

        Channel channel = channelRef.getAndSet(null);
        if (channel != null) {
            channel.close();
        }

        eventLoopGroup.shutdownGracefully();
        logger.info("Connection closed");
    }

    public ConnectionState getState() {
        return state.get();
    }

    public void setState(ConnectionState state) {
        this.state.set(state);
    }

    public boolean isConnected() {
        return state.get() == ConnectionState.CONNECTED || state.get() == ConnectionState.AUTHENTICATED;
    }

    public boolean isClosed() {
        return state.get() == ConnectionState.CLOSED;
    }

    public ZCacheClientConfig getConfig() {
        return config;
    }

    public String getHost() {
        return config.getHost();
    }

    public int getPort() {
        return config.getPort();
    }

    /**
     * Netty handler for processing server responses
     */
    private class ResponseHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            CompletableFuture<Object> future = pendingResponse.getAndSet(null);
            if (future != null) {
                future.complete(msg);
            } else {
                logger.warn("Received unexpected response: {}", msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            CompletableFuture<Object> future = pendingResponse.getAndSet(null);
            if (future != null) {
                future.completeExceptionally(cause);
            } else {
                logger.error("Connection error", cause);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (state.get() != ConnectionState.CLOSED) {
                state.set(ConnectionState.DISCONNECTED);
                logger.warn("Channel inactive, connection lost");
            }
        }
    }
}
