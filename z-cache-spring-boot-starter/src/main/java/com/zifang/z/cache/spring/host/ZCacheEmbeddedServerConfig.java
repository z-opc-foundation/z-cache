package com.zifang.z.cache.spring.host;

import com.zifang.z.cache.core.server.RedisServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 *
 * <p><b>【TASK-20260925-023】bind 失败不许再只有一行日志</b>:<br>
 * 原来 {@link #startServer(String, int, String)} 是**异步**的 —— 它 new 完 RedisServer 就起一个 daemon 线程
 * 调 {@code server.start()} 然后立刻返回, 于是真正的 {@code java.net.BindException: Address already in use}
 * 落在**那个 daemon 线程里**, 被一句 {@code log.error("[z-cache] RedisServer start failed", e)} 吃掉。
 * 调用方 ({@code ZCompanyMainStarter} 的 static{}) <b>根本看不到这个异常</b> ——
 * 它自己的 {@code catch (Throwable)} 只能抓到构造期异常, 而 bind 失败发生在构造之后。
 * 后果就是 2026-09-24 那次假孵化: 进程健康、页面照常打开, 只是"没有数据",
 * 当时靠 {@code lsof} 查属主才发现端口被一个几天前的旧 JVM 占着。
 * ⇒ 现在三件事同时做:
 * <ol>
 *   <li><b>bind 落定同步化</b>: {@link #startServer(String, int, String)} 在返回前<b>等</b> bind 有结论
 *       (成功 / 失败 / 超时), 失败时**抛异常**给调用方 —— 调用方才有资格决定"横幅 + 非零退出"还是"继续"。</li>
 *   <li><b>失败状态是一等公民</b>: {@link #getBindError()} / {@link #isBindFailed()} / {@link #getBoundPort()}
 *       让"内嵌实例没起来"变成**可查询的状态**, 而不是一行会被刷走的日志。</li>
 *   <li><b>监控面必须变红</b>: {@code zCacheEmbedded} 具名 health 组件 (宿主带 actuator 时注册)
 *       + 启动自检 runner 打的横幅行。</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(prefix = "z.cache", name = "enabled", havingValue = "true")
public class ZCacheEmbeddedServerConfig {

    private static final Logger log = LogManager.getLogger(ZCacheEmbeddedServerConfig.class);

    /**
     * 横幅行的统一前缀 —— 三个中间件 (cache / graph / vector) 用同一句,
     * 运维只要 grep 这一个串就能把"内嵌面没起来"从启动日志里捞出来。
     */
    public static final String SELFCHECK_TAG = "[EMBEDDED-SELFCHECK]";

    /** 启动自检失败时是否让进程以非零退出码退出。**默认关** —— 本地多实例并存是正常形态, 不能因此起不来。 */
    public static final String FAILFAST_PROPERTY = "z.embedded.selfcheck.failfast";

    /** bind 落定窗口: 内嵌 bind 在 daemon 线程里做, 调用方最多等这么久拿结论。 */
    private static final long BIND_SETTLE_TIMEOUT_MS =
            Long.getLong("z.cache.server.bind-settle-timeout-ms", 5000L);

    private static volatile RedisServer SHARED_SERVER;
    private static final AtomicBoolean SERVER_STARTED = new AtomicBoolean(false);

    /** bind 结论: 成功 / 失败 / 未知。三态, 不是两态 —— "还不知道"不许当成"成功"。 */
    private static volatile boolean bindSucceeded = false;
    private static volatile String bindError = null;
    /** 内嵌实例实际 bind 成功的端口; 没 bind 成就是 0 (调用方须据此显式失败, 不许猜端口) */
    private static volatile int boundPort = 0;
    /** 这次尝试 bind 的地址 —— 失败时横幅要指名道姓说"哪个端口没起来" */
    private static volatile String attemptedHost = null;
    private static volatile int attemptedPort = 0;
    /** bind 结论落定的信号量 (成功或失败都会 countDown) */
    private static final CountDownLatch BIND_OUTCOME = new CountDownLatch(1);

    /**
     * 启动 z-cache RedisServer (异步 bind, 但**返回前会等 bind 有结论**).
     * 由 main() static block 调用, 在 Spring 容器初始化之前完成.
     *
     * @throws IllegalStateException bind 失败时抛出 —— 调用方 (ZCompanyMainStarter) 决定怎么处置。
     */
    public static void startServer(String host, int port, String password) {
        if (!SERVER_STARTED.compareAndSet(false, true)) {
            return;
        }
        attemptedHost = host;
        attemptedPort = port;
        RedisServer server;
        try {
            server = new RedisServer(host, port, 0,
                    password == null || password.isEmpty() ? null : password);
        } catch (Exception e) {
            // 构造期失败: 原来也是被外层 catch (Exception e) 包着重新抛, 这里补上状态记录。
            recordBindFailure(e);
            throw new IllegalStateException("[z-cache] 无法构造 RedisServer (" + host + ":" + port + "): "
                    + bindError, e);
        }
        Thread t = new Thread(() -> {
            try {
                server.start();
                // 走到这里 = server 被正常停掉了, **不是** bind 成功 (start() 成功时会一直等 closeFuture)。
                if (!bindSucceeded) {
                    recordBindFailure(new IllegalStateException(
                            "RedisServer.start() 已返回但实例从未 bind 成功 (可能被 stop() 关掉了)"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordBindFailure(e);
                log.warn("[z-cache] RedisServer start interrupted: {}", e.getMessage());
            } catch (Throwable t2) {
                // ★ TASK-20260925-023 的核心一行: bind 失败原来只打一行日志, 之后谁都看不出来。
                recordBindFailure(t2);
                log.error("[z-cache] RedisServer start FAILED on {}:{} —— 内嵌实例**没有起来**。"
                                + "若该端口已被别的进程占着, 页面只会显示\"没有数据\", 看不出\"实例没起\"。"
                                + "查属主: lsof -nP -iTCP:{} -sTCP:LISTEN",
                        host, port, port, t2);
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

        awaitBindOutcome(server, host, port);
    }

    /**
     * 等 bind 有结论再返回 (最多 {@link #BIND_SETTLE_TIMEOUT_MS} 毫秒).
     *
     * <p>为什么必须等: {@code RedisServer.start()} 成功时会阻塞在 closeFuture 上永不返回,
     * 而 bind 失败是**抛异常** —— 所以"成功"只能靠 {@code server.isRunning()}
     * (那个 started 标志在 bind 成功之后才置 true) 来判定。
     * 不等这一下, 调用方拿到的就永远是"还不知道", 而"还不知道"正是这次事故的形状。
     */
    private static void awaitBindOutcome(RedisServer server, String host, int port) {
        long deadline = System.currentTimeMillis() + BIND_SETTLE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (server.isRunning()) {
                bindSucceeded = true;
                boundPort = server.getPort();
                BIND_OUTCOME.countDown();
                return;
            }
            if (BIND_OUTCOME.getCount() == 0) {
                // 失败已被记录, 走下面的失败分支
                break;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (bindSucceeded) {
            return;
        }
        if (bindError == null) {
            recordBindFailure(new IllegalStateException(
                    "bind 结论在 " + BIND_SETTLE_TIMEOUT_MS + "ms 内没有落定 (既没 bind 上, 也没报错)"));
        }
        // 把结论交给调用方: 谁调用谁决定"横幅 + 非零退出"还是"继续跑"。
        // 异常文案只说"bind 失败 + 原因" —— "端口是不是被别人占着"由调用方自己判 (它才知道上下文),
        // 两边都写一遍就会像日志那样重复两遍。
        throw new IllegalStateException("[z-cache] 内嵌 RedisServer bind 失败 (" + host + ":" + port + "): "
                + bindError);
    }

    /** 记一次 bind 失败 (只记第一次 —— 后续重试的同类错误不许覆盖首因) */
    private static void recordBindFailure(Throwable t) {
        if (bindError == null) {
            bindError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        BIND_OUTCOME.countDown();
    }

    public static boolean isServerStarted() {
        return SERVER_STARTED.get();
    }

    /**
     * 内嵌实例**真的**在 listen 吗。
     *
     * <p>与 {@link #isServerStarted()} 的区别是本卡最要紧的一件事:
     * {@code isServerStarted()} 的 {@code SERVER_STARTED} 是**进 try 之前**就 CAS 成 true 的,
     * 含义是"我们试过", 不是"我们 listen 着" —— 页面/监控拿它当"起来了"就是在骗人。
     */
    public static boolean isEmbeddedRunning() {
        RedisServer s = SHARED_SERVER;
        return s != null && s.isRunning();
    }

    /** 内嵌实例实际 bind 成功的端口; 没 bind 成就是 0 */
    public static int getBoundPort() {
        return isEmbeddedRunning() ? boundPort : 0;
    }

    /** bind 失败的原文; null = 没有失败记录 (**不代表成功**, 要看 {@link #isEmbeddedRunning()}) */
    public static String getBindError() {
        return bindError;
    }

    /** 内嵌实例是不是明确 bind 失败了 */
    public static boolean isBindFailed() {
        return bindError != null;
    }

    /** 这次尝试 bind 的端口 (失败时横幅要指名道姓) */
    public static int getAttemptedPort() {
        return attemptedPort;
    }

    /** 这次尝试 bind 的地址 */
    public static String getAttemptedHost() {
        return attemptedHost;
    }

    // ============================================================
    // 监控面: 具名 health 组件 + 启动自检 runner
    // ============================================================

    /**
     * 启动自检 runner (每个中间件 starter 各注册一个, 各自拨自己的端口)。
     *
     * <p><b>为什么是嵌套类而不是新文件</b>: 本卡对 z-cache 仓的授权是"内嵌 server 启动相关源码",
     * 而这个组件必须**贴着** {@link ZCacheEmbeddedServerConfig} 的状态才有意义 ——
     * 拆出去就变成两份状态, 那正是本卡要治的病。
     *
     * <p><b>为什么不用 {@code @ConditionalOnClass} 挡</b>: 自检 runner 只用 spring-boot core
     * (ApplicationRunner / AvailabilityChangeEvent), 宿主有没有 actuator 都必须跑 ——
     * "没有 actuator 就没人发现内嵌面没起来"正是本卡要治的病。
     * 具名 health 组件才需要 actuator, 它在 {@link EmbeddedHealthConfiguration} 里单独挡。
     */
    @Configuration
    public static class EmbeddedSelfCheckConfiguration {

        /**
         * 启动自检: boot 完成后拨自己的内嵌端口, 属主不对就在横幅上打一行。
         *
         * <p>默认只报错不退出 (开关 {@value #FAILFAST_PROPERTY} 默认关):
         * 本地多实例并存是正常形态, 不能因为别人占了端口就起不来。
         * 但**监控面必须变红** —— 所以这里同时把 readiness 打成 {@code REFUSING_TRAFFIC},
         * 带 actuator 的宿主 {@code /actuator/health} 会随之变红。
         */
        @Bean
        public org.springframework.boot.ApplicationRunner zCacheEmbeddedSelfCheckRunner(
                final ApplicationContext applicationContext,
                final org.springframework.core.env.Environment environment) {
            return new org.springframework.boot.ApplicationRunner() {
                @Override
                public void run(org.springframework.boot.ApplicationArguments args) {
                    boolean ok = isEmbeddedRunning();
                    if (ok) {
                        log.info("{} [OK] z-cache 内嵌实例已 bind {}:{} —— 内嵌面可用",
                                SELFCHECK_TAG, getAttemptedHost(), getBoundPort());
                        System.out.println(SELFCHECK_TAG + " [OK] z-cache embedded instance bound "
                                + getAttemptedHost() + ":" + getBoundPort());
                        return;
                    }
                    int p = attemptedPort > 0 ? attemptedPort : 6379;
                    String msg = SELFCHECK_TAG + " [DOWN] z-cache 内嵌实例**没有起来** (尝试 bind "
                            + attemptedHost + ":" + p + ", 失败原因: " + bindError + ")。"
                            + "该端口若可连, 应答者是**别的进程**, 不是本 JVM —— "
                            + "页面会显示\"没有数据\", 那不是数据问题, 是实例没起。"
                            + "查属主: lsof -nP -iTCP:" + p + " -sTCP:LISTEN";
                    log.error(msg);
                    System.out.println(msg);
                    AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
                    if (isFailFast(environment)) {
                        throw new IllegalStateException(msg
                                + " —— 开关 " + FAILFAST_PROPERTY + "=true ⇒ 进程以非零退出码退出。");
                    }
                }
            };
        }
    }

    /**
     * 具名 health 组件 {@code zCacheEmbedded} —— 只在宿主带 actuator 时注册。
     *
     * <p><b>为什么 actuator 不能是本 starter 的强制依赖</b>: 会把一堆只想用 client 的消费者拖下水。
     * 所以 pom 里用 {@code provided + optional} 只供编译, 运行时靠 {@code @ConditionalOnClass(name=...)}
     * 决定"宿主有没有 actuator"。
     * <p><b>为什么用 {@code name=} 形式</b>: 为了**不加载** actuator 类就能做条件判定 ——
     * 宿主没 actuator 时这个嵌套类根本不会被加载, 不会 NoClassDefFoundError。
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public static class EmbeddedHealthConfiguration {

        /**
         * 具名 DOWN 组件: {@code zCacheEmbedded}。
         *
         * <p>绑不上 ⇒ DOWN (不是 UNKNOWN): "内嵌面没起来"是一个**确定的坏**, 不是"没测"。
         * 细节里同时给出 bind 失败原文与尝试的端口 —— 监控面要能直接回答"为什么红"。
         */
        @Bean(name = "zCacheEmbedded")
        public org.springframework.boot.actuate.health.HealthIndicator zCacheEmbeddedHealthIndicator() {
            return new org.springframework.boot.actuate.health.HealthIndicator() {
                @Override
                public org.springframework.boot.actuate.health.Health health() {
                    if (isEmbeddedRunning()) {
                        return org.springframework.boot.actuate.health.Health.up()
                                .withDetail("boundPort", Integer.valueOf(getBoundPort()))
                                .withDetail("attemptedHost", String.valueOf(getAttemptedHost()))
                                .withDetail("attemptedPort", Integer.valueOf(getAttemptedPort()))
                                .build();
                    }
                    return org.springframework.boot.actuate.health.Health.down()
                            .withDetail("boundPort", Integer.valueOf(0))
                            .withDetail("attemptedHost", String.valueOf(getAttemptedHost()))
                            .withDetail("attemptedPort", Integer.valueOf(getAttemptedPort()))
                            .withDetail("bindError", String.valueOf(getBindError()))
                            .withDetail("selfCheckTag", SELFCHECK_TAG)
                            .build();
                }
            };
        }
    }

    /** fail-fast 开关: System property 优先 (static 块也能读到), 再看 Spring 环境。 */
    public static boolean isFailFast(org.springframework.core.env.Environment environment) {
        String v = System.getProperty(FAILFAST_PROPERTY);
        if (v == null && environment != null) {
            v = environment.getProperty(FAILFAST_PROPERTY);
        }
        return Boolean.parseBoolean(v);
    }
}
