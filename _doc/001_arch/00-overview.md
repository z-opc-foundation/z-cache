# 项目总览

## 1. 项目简介

z-cache 是一个 **RESP2 兼容的分布式内存缓存服务**：用 Java + Netty 实现走 Redis 协议（RESP2），任何 RESP 客户端（Jedis / Lettuce / redis-cli / GUI 工具）零修改即可连上。

它要解决的不是"再造一个 Redis 的每个特性"，而是把**缓存 + 数据结构 + 持久化 + 分布式锁**这几件一人公司基座天天要用的能力，收进一个能进 Maven Central、能被 `z-boot-parent` 统一装配、文档说中文的轻量件里。它**明确不追求** Lua、Cluster 分片、HyperLogLog、Geo 这些 Redis 高级面 —— 命令支持表逐条写清了做到了哪一格、哪些明确没做。

除了 RESP 服务端，仓内还有一条**嵌入式**用法：`com.zifang.z.cache.core.embedded.ZCache` 是泛型键值缓存，不依赖 Netty 也不依赖 RESP 协议，不需要起服务端就能在 JVM 内直接用（带 TTL、后台清理线程与条目统计）。

**技术栈：** Java 8（`maven.compiler.release=8`，JDK9+ 强制）· Netty 4.1.138.Final · Spring Boot 2.7.18（仅 starter 用）· Log4j2 2.17.2（本仓有意压回）· `z-util-serialize-core` / `z-util-serialize-schema` 做序列化

## 2. 模块架构

```
z-cache/
├── pom.xml                        # 根聚合 POM：z-cache:1.3.6，parent = z-boot-parent:1.1.0（relativePath 留空）
│                                  #   5 个 <module> 全部在 reactor、无 maven.deploy.skip
├── z-cache-common/                # 14 个类：RESP 协议编解码 + RedisGlob + 共享类型（不引日志实现）
│   └── com.zifang.z.cache.common.{protocol,exception}
├── z-cache-core/                  # 27 个类：存储引擎 + RESP 服务端 + 命令路由 + 持久化 + 嵌入式缓存
│   └── com.zifang.z.cache.core.{storage,server,command,persistence,protocol,stream,pubsub,serialize,embedded,logging}
├── z-cache-client/                # 16 个类：同步/异步客户端、连接池、分布式锁
│   └── com.zifang.z.cache.client{,lock,pool,protocol}
├── z-cache-server/                # 2 个类：Main（委托 core 的 ZCacheServerMain）+ HealthCheck，shade 打 fat jar
│   └── com.zifang.z.cache.server
├── z-cache-spring-boot-starter/   # 6 个类：ZCacheProperties / ZCacheAutoConfiguration / ZCacheTemplate
│   │                               #   + host/ 三个类（嵌入式托管模式：CacheHostAutoConfiguration 等）
│   └── com.zifang.z.cache.spring{,.host}
├── Dockerfile                     # 多阶段：maven:3.9.9-eclipse-temurin-8 → eclipse-temurin:8-jre
├── docker-compose.yml             # 本地构建，端口 ${ZCACHE_PORT:-6379}:6379，mem_limit 512m
├── CHANGELOG.md                   # 逐版本详细变更（探针级结论都记在这里）
├── _frontend/                     # z-cache-component（页面）+ z-cache-suit（壳）两套 Vite 工程
└── _doc/                          # 5 个桶：001_arch / 002_deploy / 003_script / 005_testing / 006_release
```

## 3. 核心功能

### 3.1 RESP2 服务端与命令路由

`z-cache-core` 的 `RedisServer` 是 Netty 服务端，`ZCacheServerMain` 是启动入口，`command/CommandHandler` 是命令路由（一个 switch 面）。协议编解码在 `z-cache-common` 的 `common/protocol`（13 个类：`RespType` / `RespFrames` / `RespFrameReader` / `RespArray` / `RespBulkString` / `RespSimpleString` / `RespError` / `RespInteger` / `RedisGlob` / `RedisIntegerFormat` / `RedisDoubleFormat` / `RedisMemoryFormat` / `StreamIdFormat`）。

覆盖的能力面：Key / String / Bitmap / Hash / List / Set / ZSet / Stream / Pub-Sub / 事务 / 持久化 / 运维。**明确没做**的：`EVAL` / `EVALSHA` / `SCRIPT`（回 `ERR unknown command`）、`CLUSTER`（2.0.0 规划）、HyperLogLog（`PF*`）、Geo（`GEO*`）、`XCLAIM` / `XAUTOCLAIM`。

### 3.2 存储引擎

`core/storage/MemoryStore` 是存储引擎，`DataType` 枚举六型（String / List / Hash / Set / ZSet / Stream），带 TTL（毫秒粒度）、淘汰与 **16 个库**（`MemoryStore.DEFAULT_DB_COUNT = 16`）。Bitmap 位操作（`GETBIT` / `SETBIT` / `BITCOUNT` / `BITPOS` / `BITOP`）建在 String 键上，与 Redis 同形。

### 3.3 持久化

`core/persistence/RdbPersistence`（RDB 快照）与 `AofPersistence`（AOF 追加）两套，对应命令 `SAVE` / `BGSAVE` / `LASTSAVE` / `BGREWRITEAOF`；AOF fsync 有 `always` / `everysec` / `no` 三档。**给了 `--data-dir` 才开持久化**，启动时按 AOF / RDB 真正落盘与重放。

### 3.4 分布式锁（客户端 API）

`z-cache-client` 的 `lock/DistributedLock` + `DistributedLockImpl` + `LockWatchdog`：基于 `SET NX PX` 拿锁，Watchdog 负责续约，带 fencing token。

⚠ **解锁不是原子的**：服务端没有 Lua，`DistributedLockImpl` 走的是「GET 后条件 DEL」（先读回值，值仍是自己才删）。设计稿 `_doc/001_arch/08-design.md` 记的就是这一版实现。

### 3.5 嵌入式缓存

`core/embedded/ZCache`（泛型）与 `core/embedded/StringZCache`（字符串键）不依赖 Netty / RESP 协议，直接在 JVM 内用：`ZCache.<String, User>builder().maxSize(10000).cleanupIntervalSec(60).build()`，支持带 TTL 写入、后台清理线程与统计，用完 `close()` 收线程。

### 3.6 Spring Boot starter

`z-cache-spring-boot-starter` 提供 `ZCacheProperties`（`@ConfigurationProperties(prefix = "z.cache")`）、`ZCacheAutoConfiguration`、`ZCacheTemplate`。`z.cache.enabled` **缺省 false**，必须显式置 `true` 才装配。`ZCacheProperties` 的缺省值：`host=localhost`、`port=6379`、`database=0`、`ssl=false`、`poolMaxSize=8`、`connectTimeout=5s`、`readTimeout=5s`。

`ZCacheAutoConfiguration` 产出的 Bean：`ZCacheClient`（自动 `connect()`）、`ZCacheTemplate`、`ZCachePool`、`ZCacheClientConfig`，全部带 `@ConditionalOnProperty(prefix="z.cache", name="enabled", havingValue="true")`。

`spring/host` 下另有三个类（`CacheHostAutoConfiguration` / `ZCacheEmbeddedServerConfig` / `CacheProxyController`）—— 在宿主进程内托管一台嵌入式 z-cache 并代理出去。

### 3.7 服务端启动参数

`z-cache-server` 的 `Main` 委托 `core` 的 `ZCacheServerMain`。启动参数：`--host` / `--port` / `--data-dir` / `--max-entries` / `--password` / `--password-file` / `--help`；同名配置也认系统属性 `-Dzcache.host` / `-Dzcache.data-dir` / `-Dzcache.password` 与环境变量 `ZCACHE_HOST` / `ZCACHE_PORT` / `ZCACHE_DATA_DIR` / `ZCACHE_MAX_ENTRIES` / `ZCACHE_PASSWORD` / `ZCACHE_PASSWORD_FILE`。

**未设口令时启动会 WARN**：`127.0.0.1` 之外的地址裸奔 6379 等于整库可读写。

## 4. 对外集成 / 上下游

**上游依赖：** 父链 `z-boot-parent:1.1.0`（`relativePath` 留空 —— parent 在 repo1 不在磁盘）；其下是第三方地板（Netty 4.1.138.Final、Spring Boot 2.7.18）与兄弟仓 fleet（`z-util-serialize-core` / `z-util-serialize-schema`）。本仓 POM 只保留一处与地板**有意不同**的钉子：`<log4j2.version>2.17.2</log4j2.version>`（地板给 2.25.4，但 `spring-boot-starter-log4j2` 带的 `log4j-core` / `slf4j-impl` / `jul` 全是 2.17.2，于是把 `log4j-api` / `log4j-core` / `log4j-jul` / `log4j-slf4j-impl` 四条按坐标逐个压回 2.17.2 求零漂移）。

**下游消费方：** 两种形态 —— ① 起一台 RESP 服务端（Jedis / Lettuce / redis-cli 直连）；② 引入 `z-cache-spring-boot-starter` 走 Spring 装配。

**对外 HTTP 边界：无。** 本仓只说 RESP over TCP（默认 **6379**，缺省只 bind `127.0.0.1`，对外服务需显式 `--host 0.0.0.0`），没有 HTTP 接口。

**对外发布：** Maven Central（根 POM 直接 `<version>1.3.6</version>`，不是 `${revision}` CI-friendly 占位）。仓库根有 `Dockerfile`（多阶段构建，运行层 `eclipse-temurin:8-jre`，`EXPOSE 6379`，以 uid 10001 非 root 运行，`HEALTHCHECK` 调 `com.zifang.z.cache.server.HealthCheck`）与 `docker-compose.yml`（`context` 指向仓根的上一级、`dockerfile: z-cache/Dockerfile`）。

**仓内没有 k8s 清单**（旧 README 指向的 `../../../k8s/z-cache.yaml` 系断链，实际不存在）。

## 5. 技术特点

1. **协议优先而不是功能优先**：目标是"任何 RESP 客户端零修改可连"，所以 `z-cache-common` 把协议编解码与 `RedisGlob` 单独拆一个模块（13 个类），`KEYS` / `SCAN` / `HSCAN` / `SSCAN` / `ZSCAN` / `PSUBSCRIBE` 走同一条 glob 实现。
2. **能力表是"做到哪一格"级别的**：`CHANGELOG.md` 记录的是探针级结论（`WRONGTYPE` 口径、确认帧形状、`QUIT` 由服务端关连接、`TIME` / `SLOWLOG` / `CLIENT LIST` 真实化、`CONFIG` 只认 AOF 两条旋钮名其余回 `Unsupported CONFIG parameter`），而不是功能有无。
3. **分布式锁明确承认不原子**：服务端没有 Lua，解锁走「GET 后条件 DEL」。README 与设计稿都把这句写在显眼处，不假装有原子性。
4. **嵌入式与服务端共用一份存储引擎**：`core/embedded/ZCache` 不引 Netty 也不引 RESP，与 RESP 服务端并存，给"只想用个内存缓存"的场景一条零依赖路径。
5. **log4j2 压回 2.17.2 是"零漂移"而非降级**：本仓只保留这一个与地板不同的钉子，理由是 Spring Boot 2.7.18 带的 log4j 家族本来就是 2.17.2，逐坐标压回才能与 starter 自带的一致。
6. **依赖管理踩过"传递依赖改写"的坑**：fleet 里 `z-cache` 那一格钉的是 1.3.5，继承来的 DM 会把传递依赖改写成 1.3.5 的 `z-cache-common` 字节码打进 1.3.6 的 shade 包 —— 修法是在根 POM 的 `dependencyManagement` 里把 5 个自家模块逐个钉 `${project.version}`。
7. **集群模式是"规划"而非半成品**：仓内既无 `CLUSTER` 命令也无分片实现，集群运维手册放在 `_doc/002_deploy/01-cluster-mode-ops.md` 里标明是规划口径。
8. **测试依赖真服务端**：`z-cache-client` 的集成测试由 `_doc/003_script/run-client-tests.sh` 驱动并自带一台真服务端跑（不再"探测 6379 没人就整类跳过"）；`z-cache-core` 的 RESP 行为对齐测试以"与真实 Redis 参照实例逐格对原文"为判据，量具放在仓外 `~/.cache/zcache_gauges/…`。

## 6. 目录结构

源码包树共 5 段（`com.zifang.z.cache.*`），主源码 **65 个 `.java`**（common 14 + core 27 + client 16 + server 2 + starter 6），测试 52 个（common 14 + core 23 + client 14 + starter 1）：

```
z-cache/
├── z-cache-common/src/main/java/com/zifang/z/cache/common/
│   ├── protocol/    (13)  # RESP 帧类型、RedisGlob、三种数字格式、StreamIdFormat
│   └── exception/   (1)
├── z-cache-core/src/main/java/com/zifang/z/cache/core/
│   ├── storage/     (5)   # MemoryStore 存储引擎、DataType 六型
│   ├── server/      (3)   # RedisServer（DEFAULT_PORT=6379）、ZCacheServerMain
│   ├── command/     (3)   # CommandHandler 命令路由
│   ├── persistence/ (5)   # RdbPersistence / AofPersistence
│   ├── stream/      (4)   # Stream 消费组相关
│   ├── pubsub/      (1)
│   ├── protocol/    (2)
│   ├── serialize/   (1)
│   ├── embedded/    (2)   # ZCache / StringZCache（不依赖 Netty / RESP）
│   └── logging/     (1)
├── z-cache-client/src/main/java/com/zifang/z/cache/client/
│   ├── ZCacheClient.java / ZCacheClientConfig.java / ZCacheConnection.java / ZCachePipeline.java
│   ├── ConnectionState.java / PooledClient.java / ZCachePool.java / ZCacheClientException.java
│   ├── lock/        (6)   # DistributedLock(Impl) / Lock / LockWatchdog / 两个异常
│   └── protocol/    (2)   # ClientRespEncoder / ClientRespDecoder
├── z-cache-server/src/main/java/com/zifang/z/cache/server/
│   ├── Main.java          # 委托 core 的 ZCacheServerMain
│   └── HealthCheck.java   # Docker HEALTHCHECK 调用
└── z-cache-spring-boot-starter/src/main/java/com/zifang/z/cache/spring/
    ├── ZCacheProperties.java      # z.cache.* 配置项
    ├── ZCacheAutoConfiguration.java
    ├── ZCacheTemplate.java
    └── host/                       # CacheHostAutoConfiguration / ZCacheEmbeddedServerConfig / CacheProxyController
```

`_doc/` 侧 5 个桶：`001_arch/`（本文 + `01-module-structure.md` / `08-design.md` / `09-selection.md` / `10-selection-cluster.md`）、`002_deploy/`（`01-cluster-mode-ops.md`）、`003_script/`（6 个构建与发布脚本）、`005_testing/`（3 份测试文档 + `battery20.txt`）、`006_release/`（`01-audit-pre-release-1.3.0.md`）。
