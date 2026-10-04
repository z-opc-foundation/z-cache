package com.zifang.z.cache.spring.host;

import com.zifang.z.cache.core.server.RedisServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * z-cache server 内嵌启动器 (占位 Configuration).
 * <p>
 * z-cache-spring-boot-starter 只是 client (ZCacheTemplate), 不带 server.
 * z-cache-server.jar 是 fat jar, 自身带 logback + shaded netty, 与 z-opc log4j2 + 单一 netty 冲突.
 * <p>
 * 这里在主进程内启动 RedisServer (z-cache-core 提供, 不带 logback), 用 z-cache-spring-boot-starter 的 ZCacheTemplate
 * 连回本进程 6379, 实现 "z-opc 既是业务也是中间件服务提供者".
 *
 * <p>启动时机问题: zCacheClient bean factory 方法 (ZCacheAutoConfiguration.zCacheClient) 会立刻 connect,
 * 必须保证 server 先 listening. 方案: 在 {@code ZCompanyMainStarter} 的 static block 调用
 * {@link #startServer(String, int, String)}, 比 SpringApplication.run 还早, Spring 创建 zCacheClient
 * bean 时 server 已 listening.
 *
 * <p>本类仅作为 ConditionalOnProperty 标记 + 提供静态启动方法.
 */
@Configuration
@ConditionalOnProperty(prefix = "z.cache", name = "enabled", havingValue = "true")
public class ZCacheEmbeddedServerConfig {

    private static final Logger log = LogManager.getLogger(ZCacheEmbeddedServerConfig.class);

    private static volatile RedisServer SHARED_SERVER;
    private static final AtomicBoolean SERVER_STARTED = new AtomicBoolean(false);

    /**
     * 启动 z-cache RedisServer (异步, 启动后立即返回).
     * 由 main() static block 调用, 在 Spring 容器初始化之前完成.
     */
    public static void startServer(String host, int port, String password) {
        if (!SERVER_STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            RedisServer server = new RedisServer(host, port, 0,
                    password == null || password.isEmpty() ? null : password);
            Thread t = new Thread(() -> {
                try {
                    server.start();
                    log.info("[z-cache] RedisServer started on {}:{}", host, port);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[z-cache] RedisServer start interrupted: {}", e.getMessage());
                } catch (Exception e) {
                    log.error("[z-cache] RedisServer start failed", e);
                }
            }, "z-cache-redis-server");
            t.setDaemon(true);
            t.start();
            SHARED_SERVER = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("[z-cache] shutdown hook stopping RedisServer...");
                if (SHARED_SERVER != null) {
                    SHARED_SERVER.stop();
                }
            }, "z-cache-shutdown"));
        } catch (Exception e) {
            log.error("[z-cache] failed to start RedisServer", e);
            throw new RuntimeException(e);
        }
    }

    public static boolean isServerStarted() {
        return SERVER_STARTED.get();
    }
}