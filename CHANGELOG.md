# Changelog

All notable changes to z-cache will be documented in this file.

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
