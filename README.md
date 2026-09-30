# z-cache

> RESP2 兼容的分布式内存缓存 —— Java 8 · Netty 4.1 · Spring Boot 2.7；本组织 `z-boot-parent` 消费的试点仓。

z-cache 用 Java + Netty 实现了一个走 Redis 协议（RESP2）的内存数据服务：任何 RESP 客户端（Jedis / Lettuce /
redis-cli / GUI）零修改即可连。它要解决的不是"再造一个 Redis 的每个特性"，而是把**缓存 + 数据结构 + 持久化 +
分布式锁**这些一人公司基座天天要用的能力，收到一个能进 Maven Central、能被 `z-boot-parent` 统一装配、
文档说中文的轻量件里。它**不追求** Lua、Cluster 分片、HyperLogLog、Geo 这些 Redis 高级面 —— 下面「命令支持」
逐条写清了做到了哪一格。

---

## 📋 基本信息

| 字段 | 值（均来自实测） |
|------|------|
| **仓库** | `z-cache` |
| **Maven 坐标** | `io.github.yuku123:z-cache`（根聚合 POM，`packaging=pom`） |
| **当前版本** | `1.3.6`（根 POM 用直接 `<version>`，**不是** `${revision}` CI-friendly 占位） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空：parent 在 repo1，不在磁盘） |
| **Maven Central** | 实测 `z-cache` 及 5 个子件在 `1.3.6` 与 `1.3.5` 均可从 repo1 取到（`.pom` HTTP 200；不存在版本回 404）。见下方「版本链」关于 CHANGELOG 仍标 Unreleased 的账目漂移 |
| **默认端口** | `6379`（`RedisServer.DEFAULT_PORT`）；默认**只 bind `127.0.0.1`**，对外服务需显式 `--host 0.0.0.0` |
| **数据库数** | 16（`MemoryStore.DEFAULT_DB_COUNT`） |
| **运行口径** | Java 8（`maven.compiler.release=8`，JDK9+ 强制；Docker 用 `eclipse-temurin:8`）· Spring Boot 2.7.18 · Netty 4.1.138.Final |
| **最近更新** | 2026-09-30 |

---

## 🧭 版本链（z-boot-parent 试点）

本仓是全组织迁移到 `z-boot-parent` 消费入口的试点，版本链三层，逐层实测：

- **parent**：`io.github.yuku123:z-boot-parent:1.0.21`（repo1 可读，实测 200）。它负责 Java 8 的
  pluginManagement、第三方地板与兄弟仓权威表。
- **floor（地板）**：`z-boot-parent` 的父 POM 是 `io.github.yuku123:z-boot-dependencies:1.0.20`，统一第三方版本口径。
- **fleet（舰队表）**：parent import 了 `io.github.yuku123:z-boot-fleet:1.0.1`（兄弟仓对外版本权威表）。

本仓 POM **只保留与地板有意不同的一格** —— `<log4j2.version>2.17.2</log4j2.version>`：地板给的是 2.25.4，
但 `spring-boot-starter-log4j2`（随 Spring Boot 2.7.18）带的 `log4j-core/slf4j-impl/jul` 全是 2.17.2，
本仓把 `log4j-api/core/jul/slf4j-impl` 四条按坐标逐个压回 2.17.2 求"零漂移"。`netty`（4.1.138.Final）与
`z-util`（1.0.13）的属性已删除，改由 parent 的 `dependencyManagement` 供给。

一个坑（已在本 POM 注释钉死）：fleet 里 `z-cache` 那一格钉的是 repo1 上最新的 **1.3.5**，继承来的 DM 会
改写**传递依赖**，导致 `z-cache-server` 的 shade fat jar 把 1.3.5 的 `z-cache-common` 字节码打进 1.3.6 包里。
修法是在根 POM 的 `dependencyManagement` 把 5 个自家模块逐个钉 `${project.version}`。

> 账目漂移提示：`CHANGELOG.md` 顶部仍写 `## [1.3.6] - Unreleased`，而 repo1 实测已能取到 1.3.6 的 pom。
> 本 README 只陈述**实测到的 Central 状态**（1.3.6 在架），不沿用 CHANGELOG 那句过期标签。

---

## 🎯 能力清单

每条都能对应到 `src/main` 里的类或命令处理器：

| 能力 | 落点 | 状态 |
|------|------|------|
| RESP2 服务端 / 命令路由 | `z-cache-core` `RedisServer` · `ZCacheServerMain` · `command/CommandHandler` | ✅ 就绪 |
| 数据结构 String/List/Hash/Set/ZSet/Stream | `core/storage/MemoryStore`（`DataType` 枚举六型） | ✅ |
| Bitmap 位操作（`GETBIT/SETBIT/BITCOUNT/BITPOS/BITOP`） | `CommandHandler`（建在 String 键上，与 Redis 同形） | ✅ |
| HyperLogLog（`PF*`）/ Geo（`GEO*`） | — | ❌ 未实现 |
| TTL（毫秒）+ 淘汰 + 16 库 | `MemoryStore` | ✅ |
| 事务 `MULTI/EXEC/DISCARD/WATCH/UNWATCH` | `CommandHandler` | ✅ |
| Pub/Sub 含 `PSUBSCRIBE` 通配符 | `CommandHandler` · `common/protocol/RedisGlob`（与 `KEYS`/`SCAN`/`HSCAN`/`SSCAN`/`ZSCAN` 同一条 glob） | ✅ |
| Stream 消费组 `XADD/XREAD/XREADGROUP/XACK/XPENDING/XGROUP/XINFO/XRANGE/XLEN/…` | `CommandHandler` | ✅（`XCLAIM`/`XAUTOCLAIM` ❌ 未实现；`XPENDING` 仅汇总形态） |
| RDB 快照 + AOF 持久化（fsync 三档 `always/everysec/no`） | `core/persistence/RdbPersistence` · `AofPersistence` · 命令 `SAVE/BGSAVE/LASTSAVE/BGREWRITEAOF` | ✅ 真正落盘/重放 |
| 分布式锁（客户端 API） | `z-cache-client` `lock/DistributedLock(Impl)` · `LockWatchdog` | ⚠ 基于 `SET NX PX` + **GET 后条件 DEL**，Watchdog 续约、fencing token；服务端无 Lua，**解锁非原子** |
| Lua 脚本 `EVAL/EVALSHA/SCRIPT` | — | ❌ 服务端未实现（client 里预置的 `scripts/*.lua` 因此暂不起效） |
| 运维 `INFO/CLIENT/CONFIG/SLOWLOG/MONITOR/DEBUG/TIME/ECHO/PING` | `CommandHandler` | ✅（`CONFIG` 仅 GET/SET 且只认 AOF 自动挡两条旋钮名，其余回 `Unsupported CONFIG parameter`） |
| Cluster 分片（16384 槽 / Gossip / 故障转移） | — | ❌ 无 `CLUSTER` 命令，属 2.0.0 规划 |
| Spring Boot Starter | `z-cache-spring-boot-starter` `ZCacheAutoConfiguration` · `ZCacheTemplate` · `ZCachePool` | ✅ `z.cache.enabled=true` 时装配 |

---

## 🏗️ 项目结构

```
z-cache/
├── pom.xml                     # 根聚合 POM：继承 z-boot-parent:1.0.21，版本 1.3.6，log4j2 压回 2.17.2
├── z-cache-common/             # 14 个 src/main 类：协议编解码、RedisGlob、共享类型（不引日志实现）
├── z-cache-core/               # 27 个类：存储引擎 MemoryStore、RESP 服务端 RedisServer/ZCacheServerMain、
│                               #   命令处理 CommandHandler、持久化 RdbPersistence/AofPersistence
├── z-cache-client/             # 16 个类：ZCacheClient（同步/异步）、pool/ZCachePool、lock/* 分布式锁
├── z-cache-server/             # 2 个类：Main（委托 core.ZCacheServerMain）+ HealthCheck；maven-shade 打 fat jar
├── z-cache-spring-boot-starter/# 3 个类：ZCacheProperties/ZCacheAutoConfiguration/ZCacheTemplate
├── Dockerfile                  # 多阶段 maven:temurin-8 → temurin-8-jre，EXPOSE 6379，非 root（uid 10001）
├── docker-compose.yml          # 本地构建 image z-cache:latest，端口 ${ZCACHE_PORT:-6379}:6379
├── CHANGELOG.md                # 逐版本详细变更（本 README 不复制其中的探针级叙述）
└── _doc/                       # 文档，见文末「文档目录」
```

5 个模块**都在 reactor、都没有 `maven.deploy.skip`**，因此都会随 `-P central` 发布到 Maven Central
（与上面 Central 实测一致：6 个坐标 1.3.6 全在架）。`z-cache-server` 是 `maven-shade-plugin` 打出的
可执行 fat jar，`<finalName>` 固定为 `z-cache-server`，产物是 `target/z-cache-server.jar`（**不带版本号后缀**）。

---

## 🔧 技术栈

| 层级 | 技术（来自 pom 实测） |
|------|------|
| 语言 / 运行时 | Java 8（全组织口径；JDK9+ 用 `release 8` 卡死，Docker 基镜像 `eclipse-temurin:8-jre`） |
| 网络 | Netty 4.1.138.Final（版本走 `z-boot-dependencies:1.0.20` 地板，本仓不再自钉） |
| 日志 | Log4j2 **2.17.2**（本仓有意压回，理由见「版本链」；只有一个日志家族，`logback`/`slf4j` 已整批撤出） |
| 序列化 | `z-util-serialize-core` / `z-util-serialize-schema`（版本走 fleet） |
| Spring 集成 | Spring Boot 2.7.18（`spring-boot-autoconfigure`，starter 用） |
| 测试 | JUnit 5.10 · Mockito 4.11 |
| 构建 | Maven · `flatten-maven-plugin:1.5.0`（`oss` 模式剥 parent，常开）· `maven-shade-plugin:3.5.3` |

---

## 🚀 快速开始

### 编译

```bash
mvn clean install -DskipTests
```

第三方版本一律由 `z-boot-parent` → `z-boot-dependencies`（地板）+ `z-boot-fleet`（兄弟仓权威表）供给，
模块 POM 不应再出现字面版本钉；若报找不到版本，先确认本地/镜像能解析到 `io.github.yuku123:z-boot-parent:1.0.21`。

### 起一台服务端（RESP over TCP）

```bash
# 默认只监听 127.0.0.1:6379；要对外服务显式给 --host
java -jar z-cache-server/target/z-cache-server.jar --host 127.0.0.1 --port 6379
```

启动参数：`--host` / `--port` / `--data-dir` / `--max-entries` / `--password` / `--password-file` / `--help`。
同名配置也认系统属性 `-Dzcache.host` / `-Dzcache.data-dir` / `-Dzcache.password`，以及环境变量
`ZCACHE_HOST` / `ZCACHE_PORT` / `ZCACHE_DATA_DIR` / `ZCACHE_MAX_ENTRIES` / `ZCACHE_PASSWORD` / `ZCACHE_PASSWORD_FILE`。
`--data-dir` 一给即开持久化（AOF/RDB）。**未设口令时启动会 WARN**：`127.0.0.1` 之外的地址裸奔 6379 = 整库可读写。

冒烟：

```bash
redis-cli -h 127.0.0.1 -p 6379 SET hello world   # OK
redis-cli -h 127.0.0.1 -p 6379 GET hello          # "world"
```

### 作为依赖引入（Java 客户端 / Spring Boot）

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-cache-spring-boot-starter</artifactId>
    <version>1.3.6</version>
</dependency>
```

```yaml
# application.yml —— z.cache.enabled 默认 false，必须显式置 true 才装配客户端
z:
  cache:
    enabled: true
    host: 127.0.0.1
    port: 6379
    database: 0
    pool-max-size: 16          # 注意键名是 pool-max-size（旧 README 写的 pool.max-active 并不存在）
    connect-timeout: 5s
    read-timeout: 5s
    # password 建议经 ZCACHE_PASSWORD / 配置中心注入，勿写进 yml
```

可注入的 Bean：`ZCacheClient`（自动 `connect()`）、`ZCacheTemplate`、`ZCachePool`、`ZCacheClientConfig`，
全部 `@ConditionalOnProperty(prefix="z.cache", name="enabled", havingValue="true")`。

---

## 📖 命令支持

完整命令面（`CommandHandler` 的 switch 实测）覆盖 Key/String/Hash/List/Set/ZSet/Stream/Pub-Sub/TX/持久化/运维。
逐版本行为与"与上游 Redis 的差集"（`WRONGTYPE` 口径、确认帧形状、`QUIT` 由服务端关连接、AOF 自动挡旋钮、
`TIME`/`SLOWLOG`/`CLIENT LIST` 真实化等探针级结论）在 [`CHANGELOG.md`](CHANGELOG.md) 里逐条记录，本表只做能力级归纳：

| 类别 | 代表命令 | 状态 |
|------|----------|------|
| Key | `SET GET DEL EXISTS TYPE KEYS SCAN EXPIRE TTL PERSIST RENAME DBSIZE FLUSHALL SELECT MOVE TOUCH` | ✅ 六种类型均可挂过期 |
| String | `SETEX PSETEX SETNX GETSET APPEND STRLEN GETRANGE SETRANGE INCR(BY/FLOAT) DECR(BY) MSET MGET` | ✅ 错类型读写如实回 `WRONGTYPE` |
| Bitmap | `GETBIT SETBIT BITCOUNT BITPOS BITOP` | ✅ 建在 String 上 |
| Hash | `HSET HGET HMSET HMGET HDEL HGETALL HKEYS HVALS HLEN HEXISTS HINCRBY(HFLOAT) HRANDFIELD HSCAN` | ✅ |
| List | `LPUSH RPUSH LPOP RPOP LRANGE LLEN LSET LTRIM LINDEX LINSERT LREM LMOVE BLPOP BRPOP RPOPLPUSH BRPOPLPUSH` | ✅ |
| Set | `SADD SREM SMEMBERS SISMEMBER SCARD SINTER(SDIFF/SUNION)(STORE) SPOP SRANDMEMBER SMOVE SSCAN` | ✅ |
| ZSet | `ZADD ZRANGE(ZREVRANGE) ZRANGEBYSCORE ZRANK ZREVRANK ZINCRBY ZCOUNT ZLEXCOUNT ZSCORE ZRANDMEMBER ZSCAN` | ✅ `ZSCAN` 负载 `member,score` 成对 |
| Stream | `XADD XLEN XDEL XRANGE XREVRANGE XREAD XREADGROUP XACK XPENDING XGROUP XINFO XSETID XTRIM` | ✅ `XCLAIM/XAUTOCLAIM` ❌ |
| Pub/Sub | `PUBLISH SUBSCRIBE UNSUBSCRIBE PSUBSCRIBE PUNSUBSCRIBE PUBSUB CHANNELS NUMSUB NUMPAT` | ✅ `PSUBSCRIBE` 走 `RedisGlob` |
| 事务 | `MULTI EXEC DISCARD WATCH UNWATCH` | ✅ WATCH 中止判据已修正 |
| 持久化 | `SAVE BGSAVE LASTSAVE BGREWRITEAOF` | ✅ 真正落盘 / 启动重放 |
| 运维 | `INFO MONITOR CLIENT CONFIG SLOWLOG DEBUG TIME ECHO PING` | ✅（`CONFIG` 只认 AOF 两条旋钮名） |
| Lua | `EVAL EVALSHA SCRIPT` | ❌ 未实现，回 `ERR unknown command` |
| Cluster | `CLUSTER` | ❌ 无命令，2.0.0 规划 |

---

## 🧪 测试

```bash
mvn test
```

- `z-cache-client` 的测试由 [`_doc/003_script/run-client-tests.sh`](_doc/003_script/run-client-tests.sh) 驱动，
  自带一台真服务端跑集成用例（不再"探测 6379 没人就整类跳过"）。
- `z-cache-core` 的 RESP 行为对齐测试以"与真实 Redis 参照实例逐格对原文"为判据；若干期望表/探针脚本落在
  `~/.cache/zcache_gauges/…`（**仓外**，非源码），跑测需要参照实例与这些量具，缺失时相关用例无法自证。
- 本仓**未**在此任务里跑过完整 `mvn test`（构建耗时且非本次要求）；上述能力级状态以 POM/源码/`CHANGELOG.md` 实测归纳为准。

---

## 🐳 部署

> 本仓**没有 k8s 清单**（旧 README 指向 `../../../k8s/z-cache.yaml` 系断链，实际不存在）；对外发布走 Maven Central，
> 容器走下面的本地 `Dockerfile`。

`Dockerfile` 是多阶段构建：`maven:3.9.9-eclipse-temurin-8` 里 `mvn -N install` 后
`-pl z-cache-server -am package`，运行层是 `eclipse-temurin:8-jre`，产物 `/app/z-cache-server.jar`，
以 uid 10001 非 root 运行，`EXPOSE 6379`，`HEALTHCHECK` 调 `com.zifang.z.cache.server.HealthCheck`。
`CMD` 把 `ZCACHE_HOST/PORT/MAX_ENTRIES/PASSWORD_FILE/DATA_DIR` 翻译成 `--host/--port/…` 参数。

```bash
# 本地构建并起（compose 用 org 根做 context、z-cache/Dockerfile 做 Dockerfile）
docker compose up -d --build
# 自定义宿主端口 / 镜像名 / 内存
ZCACHE_PORT=6380 ZCACHE_MEMORY_LIMIT=1g docker compose up -d --build
```

发布脚本在 [`_doc/003_script/`](_doc/003_script/)：`build.sh`、`package.sh`、
`deploy_maven_center.sh`（一键发 Central）、`install-settings.sh`（写凭证进 `~/.m2/settings.xml`）。
集群模式（规划）的运维手册见 [`_doc/002_deploy/集群模式运维手册.md`](_doc/002_deploy/集群模式运维手册.md)。

---

## 📄 License

MIT License，全文见根 [`LICENSE`](LICENSE)（`Copyright (c) 2026 z-opc-foundation`）；根 POM `<licenses>` 亦声明 MIT。

---

## 文档目录

本项目文档统一收口在 [`_doc/`](_doc/) 下（依据
[`z-opc-foundation-lead/008_组织规范/002_项目文档收口规范.md`](../z-opc-foundation-lead/008_组织规范/002_项目文档收口规范.md)，
根路径只留本 `README.md`）。以下链接逐个对应磁盘真实文件，并按"它到底是什么"如实归类：

- [`_doc/001_arch/`](_doc/001_arch/) —— 架构与设计：
  - [`01-module-structure.md`](_doc/001_arch/01-module-structure.md) —— 模块结构与命令清单
  - [`分布式锁设计.md`](_doc/001_arch/分布式锁设计.md) —— DistributedLock API / Watchdog 设计稿（文中"Lua 解锁"是设计目标，服务端尚未实现 EVAL，实际走 GET+DEL）
  - [`分布式锁方案选型-2026Q4.md`](_doc/001_arch/分布式锁方案选型-2026Q4.md) —— Redisson vs 自研选型调研
  - [`集群模式架构选型-2026Q4.md`](_doc/001_arch/集群模式架构选型-2026Q4.md) —— 一致性哈希 vs 16384 槽调研（2.0.0 规划）
  - 测试产物（**非架构文档**，如实标注）：
    [`TEST_README.md`](_doc/005_testing/TEST_README.md)（z-cache-client 测试模块说明）、
    [`TEST_REPORT.md`](_doc/005_testing/TEST_REPORT.md)（覆盖率报告）、
    [`TEST_STATUS.md`](_doc/005_testing/TEST_STATUS.md)（全模块测试状态）
- [`_doc/002_deploy/`](_doc/002_deploy/) —— 部署：
  - [`集群模式运维手册.md`](_doc/002_deploy/集群模式运维手册.md) —— 面向 1.3.0/2.0.0 目标的运维手册
- [`_doc/003_script/`](_doc/003_script/) —— 构建 / 发布 / 测试脚本：
  - [`build.sh`](_doc/003_script/build.sh) · [`package.sh`](_doc/003_script/package.sh) ·
    [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) ·
    [`install-settings.sh`](_doc/003_script/install-settings.sh) ·
    [`run-client-tests.sh`](_doc/003_script/run-client-tests.sh)
- `_doc/004_skill/` —— AI skill / 审计：
  - [`audit-pre-release-1.3.0.md`](_doc/006_release/audit-pre-release-1.3.0.md) —— 1.3.0 发布前合规审计报告
- 命令级测试电池记录：[`_doc/005_testing/battery20.txt`](_doc/005_testing/battery20.txt) —— RESP 命令序列原文（`HSET`/`HINCRBYFLOAT` 等），是测试输入而非文档正文

> 说明：旧 README 曾称"`_doc/004_skill/` 尚不存在、2.0.0 才重命名"，实测该目录已在且含审计文件，本目录已据实更正。

_Maintained by the z-opc-foundation organization._
