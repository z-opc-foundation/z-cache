# Changelog

All notable changes to z-cache will be documented in this file.

## [1.3.3] - 2026-09-26

### Fixed

#### 发布出去的构件外部消费者根本解析不动
- Central 上 `io.github.yuku123:z-cache:1.0.2` 与 `:1.3.1` 这两个聚合 pom 的 parent 写着
  monorepo 的 `com.zifang:z-opc:1.0.0-SNAPSHOT`——它从未（也不能）出现在 Central 上
  （`com/zifang/z-opc/` 实测 HTTP 404）。任何仓库外的项目第一次解析就得到
  `Could not find artifact com.zifang:z-opc:pom:1.0.0-SNAPSHOT (absent)` +
  `Failed to read artifact descriptor for io.github.yuku123:z-cache-core:jar:1.3.1`
  （这条是本次在空本地仓库里跑 consumer 探针实测到的输出）。
  本机 `mvn` 之所以从没报错，只是因为兄弟目录 `../pom.xml` 恰好就在磁盘上。
  `1.3.0` 是例外：它发布时聚合 pom 里没有 parent，所以只有这一版在 parent 这条线上是通的。
- `central` profile 下接入 `flatten-maven-plugin`（`flattenMode=oss`，`updatePomFile=true`）：
  发布用的 pom 去掉 parent、把继承来的依赖版本全部写成字面值，install/deploy 用它替换。
  不带 `-P central` 的本地 monorepo 构建链完全不变。

#### z-cache-core 依赖了一个 Central 上不存在的版本
- `z-util-serialize-core` / `z-util-serialize-schema` 在 `1.0.2` 与 `1.3.1` 的 pom 里都钉在
  `1.0.9`，而这个版本只存在于本机 `~/.m2`（由 monorepo 里的 z-util 现场 install 出来的），
  Central 实测只有 `1.0.10`（`.../1.0.9/z-util-serialize-core-1.0.9.jar` → HTTP 404）。
  即便修好了 parent，消费者下一步仍会卡在 `z-util-serialize-core:jar:1.0.9 (absent)`。
  抬到 `1.0.10`（实测该 jar 里 `CodecRegistry` / `ReflectCodec` / `ZSerializer` / `ZDeserializer`
  四个被实际 import 的类都在）。

## [1.3.2] - 2026-09-26

### Fixed

#### CommandHandler 写入落到了没人读的空库（数据静默丢失）
- `HINCRBYFLOAT`、`LMOVE`、`SPOP`、`ZLEXCOUNT`、`ZRANGEBYLEX`、`ZREVRANGEBYLEX`、
  `ZREMRANGEBYLEX`、`ZREMRANGEBYRANK`、`ZREMRANGEBYSCORE`、`ZRANDMEMBER` 等命令此前读写
  `CommandHandler` 的静态字段存储，而这条存储与 `MemoryStore` 的 per-DB 存储不是同一份数据：
  命令返回成功，但值并没有进入其它命令（以及 `KEYS`/`DBSIZE`/`EXISTS`/持久化）实际读取的键空间。
- 删除了这些静态字段、其懒初始化分支以及 `public static setXxxStore` 注入点；全部改为
  `store.getXxxStore(currentDb)`。

#### ZREMRANGEBYLEX 的 `[` 闭区间从未生效
- `SortedSetStore.compareLex` 只处理了开区间前缀 `(`，把 `[a` 当成字面量参与比较
  （`'a' > '['`），导致 Redis 里唯一的"含端点"写法恒不匹配。`ZRANGEBYLEX` /
  `ZREVRANGEBYLEX` / `ZLEXCOUNT` 三个命令因此长期返回空。

#### 阻塞命令的协议形状与服务隔离
- `BLPOP`/`BRPOP` 超时返回 `*0`（长度为 0 的数组），RESP2 语义应为 null 数组 `*-1`。
- 阻塞命令此前与业务命令共用同一个 Netty 线程组：少量 `BLPOP key 0` 即可占满线程，
  让所有普通流量停摆。现在阻塞命令调度到独立的线程组（`zcache.blocking-threads`，
  默认 `max(16, cores*4)`），业务线程组为 `zcache.business-threads`（默认 `max(4, cores*2)`），
  连接在阻塞期间关闭 `autoRead`，客户端断线时打断并回收 parked 线程。

#### INFO
- `keyspace` 段此前统计的不是各 DB 的真实 key 数，且只覆盖部分类型；现在按
  `store.getDbCount()` 逐库汇总 String/Hash/List/Set/SortedSet 的键数，只输出非空库。

### Added
- `RedisServerLifecycleTest`：针对真实监听端口 + 裸 RESP 的端到端回归，覆盖无 dataDir 启动、
  `connected_clients` 计数、NOAUTH 前置、跨线程 stop 不死锁、BLPOP 只弹请求的 key、
  BLPOP 超时不偷别的 key、阻塞命令不饿死普通流量、断线释放 worker 线程、
  以及集合类删除命令的效果必须出现在其它读取器看到的数据里。

## [1.3.1] - 2026-09-19

### Fixed
- Upgraded Netty from 4.1.100 to 4.1.138 to fix CVE-2023-44487 (HTTP/2 Rapid Reset)

## [1.3.0] - 2026-09-19

### Added

#### Distributed Lock (z-cache-client)
- `DistributedLock` interface with `tryLock` / `unlock` / `renew` / `currentOwner` / `close`
- `DistributedLockImpl`: SET NX PX acquisition, Lua EVAL atomic unlock, Watchdog auto-renew
- `Lock` handle POJO with ownerId, fencingToken, ttlMs
- `LockWatchdog`: background auto-renew at ttlMs/3 interval
- `ZCacheClient.distributedLock()` factory method
- unlock.lua + renew.lua scripts for future EVAL support
- 15 unit tests

#### Stream Consumer Groups (z-cache-core)
- `StreamEntry`, `Stream`, `ConsumerGroup`, `StreamStore` data structures
- 13 Stream commands: XADD, XLEN, XRANGE, XREVRANGE, XDEL, XTRIM, XREAD, XREADGROUP, XGROUP (CREATE/DESTROY/CREATECONSUMER/DELCONSUMER), XACK, XPENDING, XINFO
- Consumer group support with pending entries tracking
- 20 unit tests

#### Operations Commands (z-cache-core)
- CLIENT: LIST, GETNAME, SETNAME, ID, KILL, INFO, NO-EVICT
- DEBUG: SLEEP, OBJECT, SLOWLOG-RESET, ERROR (safe subset)
- MONITOR: toggle command monitoring with Redis-compatible output
- RESET: clear client state

#### Pub/Sub Pattern Matching (z-cache-core, existing)
- PSUBSCRIBE / PUNSUBSCRIBE with glob pattern support (*, ?)
- PUBSUB CHANNELS / NUMPAT / NUMSUB

#### Persistence (z-cache-core, existing)
- AOF: always / everysec / no strategies
- RDB: BGSAVE / SAVE snapshots

### Changed
- Version bump 1.1.0 → 1.3.0

### Documentation
- 分布式锁方案选型调研报告
- 集群模式架构选型调研报告
- 分布式锁设计文档
- 集群模式运维手册 (12 chapters)
- README 1.3.0 中文版
- 1.3.0 发布前合规审计报告

## [1.1.0] - 2026-08-15

### Added
- Pipeline support
- Transaction (MULTI/EXEC/DISCARD/WATCH)
- Pub/Sub (SUBSCRIBE/UNSUBSCRIBE/PUBLISH)
- AOF + RDB persistence
- SlowLog

## [1.0.2] - 2026-07-01

### Added
- Initial release with String/Hash/List/Set/SortedSet commands
- Netty-based RESP protocol
- Multi-database support (0-15)
- LRU eviction

---

**Full Changelog**: https://github.com/yuku123/z-cache/compare/v1.1.0...v1.3.0
