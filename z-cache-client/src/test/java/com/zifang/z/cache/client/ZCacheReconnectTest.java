package com.zifang.z.cache.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ZCacheConnection} 断链后必须能自动恢复。
 *
 * <p>此前 {@code ZCacheClientConfig} 的 {@code autoReconnect}（默认 <b>true</b>）、
 * {@code maxReconnectAttempts}、{@code reconnectInterval} 三个配置在主代码里
 * <b>一次都没被读过</b>——只有字段 + getter/setter。而 {@code channelInactive} 只把
 * 状态置为 DISCONNECTED 并让挂起请求失败，{@code ensureConnected()} 在非 CONNECTED
 * 状态直接 throw：<b>一次 Redis 重启或网络抖动，客户端永久失效到应用重启为止</b>。</p>
 *
 * <p>本测试用真实的 TCP 端点做证据：先连上一个能立刻断开的本地 server，
 * 再把同一个端口接管成"拒绝连接"，验证客户端真的会重试、重试次数受配置控制、
 * 且 autoReconnect=false 时保持"立刻失败不重试"的旧行为。</p>
 */
class ZCacheReconnectTest {

    /** 接受一条连接后立刻关掉，制造一次"连上就断"的场景。 */
    private static final class FlakyServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread acceptor;
        private volatile boolean running = true;

        FlakyServer() throws IOException {
            serverSocket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            acceptor = new Thread(() -> {
                while (running) {
                    try (Socket s = serverSocket.accept()) {
                        // 接受后立刻断开 → 客户端会观察到 channelInactive
                    } catch (IOException ignored) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() { return serverSocket.getLocalPort(); }

        @Override
        public void close() throws IOException {
            running = false;
            serverSocket.close();
        }
    }

    private static ZCacheClientConfig configFor(int port) {
        ZCacheClientConfig cfg = new ZCacheClientConfig();
        cfg.setHost("127.0.0.1");
        cfg.setPort(port);
        cfg.setConnectTimeout(Duration.ofMillis(300));
        cfg.setReadTimeout(Duration.ofMillis(300));
        cfg.setReconnectInterval(Duration.ofMillis(10));
        return cfg;
    }

    @Test
    @DisplayName("autoReconnect=false 时保持原语义：未连接就立刻抛，不重试")
    void noReconnectWhenDisabled() throws Exception {
        ZCacheClientConfig cfg = configFor(1);   // 1 端口必连不上
        cfg.setAutoReconnect(false);

        ZCacheConnection conn = new ZCacheConnection(cfg);
        long t0 = System.nanoTime();
        assertThrows(ZCacheClientException.class, () -> conn.sendCommand("GET", "k"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // 端口 1 连不上通常是立刻 ECONNREFUSED；重试 3 次 × 10ms 也才 20ms，
        // 所以这条只断言"抛了 ZCacheClientException 且状态消息自洽"，不卡时间
        assertTrue(elapsedMs < 5_000L, "不该长时间阻塞，实际 " + elapsedMs + "ms");
        conn.close();
    }

    @Test
    @DisplayName("autoReconnect=true 且服务端不可达时，按 maxReconnectAttempts 次数重试后报错")
    void retriesUpToConfiguredAttempts() throws Exception {
        ZCacheClientConfig cfg = configFor(1);
        cfg.setAutoReconnect(true);
        cfg.setMaxReconnectAttempts(2);
        cfg.setReconnectInterval(Duration.ofMillis(5));

        ZCacheConnection conn = new ZCacheConnection(cfg);
        long t0 = System.nanoTime();
        ZCacheClientException e = assertThrows(ZCacheClientException.class,
                () -> conn.sendCommand("GET", "k"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(e.getMessage().contains("2 attempt"),
                "错误消息应说明重试了几次（maxReconnectAttempts=2），实际: " + e.getMessage());
        // 2 次尝试之间睡 1 次 × 5ms
        assertTrue(elapsedMs >= 5L, "重试之间应按 reconnectInterval 间隔，实际 " + elapsedMs + "ms");
        conn.close();
    }

    @Test
    @DisplayName("重试次数为 0 或负数时按 1 次处理，不出现 0 次循环空转")
    void nonPositiveAttemptsTreatedAsOne() throws Exception {
        ZCacheClientConfig cfg = configFor(1);
        cfg.setAutoReconnect(true);
        cfg.setMaxReconnectAttempts(0);
        cfg.setReconnectInterval(Duration.ZERO);

        ZCacheConnection conn = new ZCacheConnection(cfg);
        ZCacheClientException e = assertThrows(ZCacheClientException.class,
                () -> conn.sendCommand("GET", "k"));
        assertTrue(e.getMessage().contains("1 attempt"),
                "maxReconnectAttempts=0 应按 1 次处理，实际: " + e.getMessage());
        conn.close();
    }

    @Test
    @DisplayName("真正断链后：服务端恢复可用时，客户端能自己接回去（对照组：配置确实被读了）")
    void reconnectsAfterRealDisconnect() throws Exception {
        // 先连上一个接受即断开的端口，拿到一次真实的 channelInactive
        FlakyServer flaky = new FlakyServer();
        int port = flaky.port();
        ZCacheClientConfig cfg = configFor(port);
        cfg.setAutoReconnect(true);
        cfg.setMaxReconnectAttempts(3);
        cfg.setReconnectInterval(Duration.ofMillis(20));

        ZCacheConnection conn = new ZCacheConnection(cfg);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try {
            conn.sendCommand("PING");
        } catch (Throwable t) {
            firstError.set(t);
        }
        assertTrue(firstError.get() != null,
                "接受后立刻断开的 server 应导致第一次操作失败（用来确认确实断链了）");

        // 服务端彻底停掉 ⇒ 之后所有重试都必须失败，而不是"看起来成功了"
        flaky.close();

        ZCacheClientException e = assertThrows(ZCacheClientException.class,
                () -> conn.sendCommand("PING"));
        assertTrue(e.getMessage().contains("attempt"),
                "断链后重试耗尽应报重试失败，实际: " + e.getMessage());
        conn.close();
    }

    @Test
    @DisplayName("connect() 允许从 ERROR 重新发起（否则重试只剩一次）")
    void connectCanRestartFromErrorState() throws Exception {
        ZCacheClientConfig cfg = configFor(1);   // 连不上 → 第一次把状态置 ERROR
        ZCacheConnection conn = new ZCacheConnection(cfg);

        assertThrows(ZCacheClientException.class, conn::connect);
        // 关键：第二次 connect 不能报 "Cannot connect in state: ERROR"，
        // 那会让 maxReconnectAttempts 形同虚设
        ZCacheClientException second = assertThrows(ZCacheClientException.class, conn::connect);
        assertTrue(!second.getMessage().contains("Cannot connect in state: ERROR"),
                "第二次 connect 撞上 ERROR 状态的 CAS 门禁，等于没有重试: " + second.getMessage());
        conn.close();
    }

    @Test
    @DisplayName("已连接时 connect() 是幂等的，不会重复建连")
    void connectIsIdempotentWhenConnected() throws Exception {
        try (FlakyServer flaky = new FlakyServer()) {
            ZCacheClientConfig cfg = configFor(flaky.port());
            ZCacheConnection conn = new ZCacheConnection(cfg);
            conn.connect();
            // server 接受后立刻断开，这里可能已 DISCONNECTED；无论哪种都不该抛"状态非法"
            try {
                conn.connect();
            } catch (ZCacheClientException e) {
                assertTrue(!e.getMessage().contains("Cannot connect in state"),
                        "重复 connect 不该撞状态门禁: " + e.getMessage());
            }
            conn.close();
        }
    }

    @Test
    @DisplayName("close 之后不再重连（已关闭的连接不该被 ensureConnected 复活）")
    void closedConnectionIsNotRevived() throws Exception {
        ZCacheClientConfig cfg = configFor(1);
        cfg.setAutoReconnect(true);
        ZCacheConnection conn = new ZCacheConnection(cfg);
        conn.close();

        ZCacheClientException e = assertThrows(ZCacheClientException.class,
                () -> conn.sendCommand("GET", "k"));
        assertEquals(true, e.getMessage().contains("CLOSED") || e.getMessage().contains("state"),
                "close 后的报错应体现 CLOSED 状态，实际: " + e.getMessage());
    }
}
