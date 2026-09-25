# Changelog

All notable changes to z-cache will be documented in this file.

## [1.3.4] - 2026-09-26

### Fixed

#### RDB 快照只装得下 DB 0
- `StoreAccessor` 的每个方法都没有库号，`writeRdbFile` 更是把 `dbCount` 写死成 1、`dbIndex`
  写死成 0：服务对外承诺 16 个库，快照里只放得下 DB 0，`SELECT 3` 之后写进去的数据
  在重启后静默消失（恢复侧还把所有键一律塞回 DB 0）。
- `getAllExpirationEntries()` 的直接返回 `new HashMap<>()`，注释写着"过期信息存储在
  ValueWrapper 中"——那份信息从来没被取出来过。于是 `SETEX k 3600 v` 落进快照后
  `expireAt` 恒为 -1，**重启后过期键变成永久键**。
- 现在 `StoreAccessor` 带 `getDbCount()` 与逐库参数，`MemoryStoreAccessor` 从
  `MemoryStore.stringStores[db]` 读真实 `expireAt`；`restoreString` 对"停机期间已经到期"的键
  直接丢弃，不再复活成永久键。集合类型键的 TTL 见文末"已知边界"。

#### SAVE / BGSAVE / LASTSAVE 三个命令一个字都没落盘
- 三条 case 此前是写死的回复：`SAVE` 和 `BGSAVE` 都回 `OK`（其中 `BGSAVE` 更早的版本还直接
  别名到 `handleSet`），`LASTSAVE` 回**当前时间**——三条命令同时给出"有快照、刚打过"的假象，
  而 `RdbPersistence` 压根没被接进来。
- 现在 `SAVE` 真调 `save()`、`BGSAVE` 在调度线程上异步打（已有后台快照在跑时如实拒绝，
  与 Redis 一致）、`LASTSAVE` 报最近一次**成功**的时刻、从未成功过为 0；
  未配 `--data-dir` 时前两条明确回 `-ERR`，不再装作成功。
- `save()` 内部两处"报了成功其实没落地"也一并修掉：`storeAccessor == null` 从
  "打条 WARNING 然后 return"改为抛异常；临时文件→目标文件的 `renameTo()` 返回值此前没人看，
  改 `Files.move(..., REPLACE_EXISTING)`，失败即抛。
- **定时快照此前从来不会发生**：`RdbPersistence.start()` 与 `onWrite()` 在主代码里都是零调用方，
  所以调度器没起过，就算起了 `writeCounter` 也恒为 0、`shouldSave()` 永远判 false。
  现在 `initPersistence()` 会 `setSaveStrategy` + `start`，并新增三项配置：
  `-Dzcache.save-seconds`（默认 300）、`-Dzcache.save-changes`（默认 1000，两者任一为 0 即关闭
  定时快照）、`-Dzcache.appendfsync`（`always`/`everysec`/`no`，默认 `everysec`，非法值不静默采纳）。

#### 快照落在 `--data-dir` 之外
- `initPersistence()` 只用路径去 `load(rdbPath)`，从不把路径交给写侧；`dbFilePath` 字段默认是
  相对路径 `"dump.rdb"`，于是 `SAVE` 与优雅停机快照全都写进**进程 cwd**，
  也就是那个没人会再去读回的地方。现在显式 `setDbFilePath(dataDir + "/dump.rdb")`。

#### AOF 只写不读：宣传的掉电恢复一次都没兑现过
- `loadAof()` 在主代码里零调用方——AOF 文件越长越勤快地写，然后没有任何人读过它。
  现在启动时按 Redis 的顺序恢复：**有 AOF 就只认 AOF，不再叠 RDB**（两份都读等于把快照里
  已经反映过的写命令再演一遍，`SET` 幂等看不出差别，`LPUSH` 会让列表原地翻倍）。
- 重放走 `CommandHandler.replayCommand()`，期间 `loading` 标志压住回写：否则每开一次机，
  日志就把自己的内容抄一遍。
- 补齐写命令表：`HINCRBYFLOAT`、`LMOVE`、`SPOP`、`SINTERSTORE`、`SUNIONSTORE`、`SDIFFSTORE`、
  `ZREMRANGEBYLEX/RANK/SCORE` 此前根本不在 `WRITE_COMMANDS` 里，连"落日志"这一步都没发生。
- 去掉 `MULTI` / `EXEC` / `DISCARD`：`EXEC` 会把队列里的命令逐条重新走一遍 `handle()`，
  每条各自落 AOF，再记一遍事务边界只会让重放多跑一次空事务。
- 当前连接不在 DB 0 时，AOF 里先补一条 `SELECT <db>`——否则重放用的是全新连接（db 恒为 0），
  5 号库的数据会全部落进 DB 0。
- `BLPOP` / `BRPOP` / `BRPOPLPUSH` 按非阻塞等价命令（`LPOP` / `RPOP` / `RPOPLPUSH`）记录，
  超时（什么都没弹出）时不记。不翻译的话"BLPOP 消费掉的那个值"在日志里毫无痕迹，
  重放后它会回到源列表里被消费第二次；直接记原命令则会在开机时真的阻塞在空列表上。
- `loadAof` 的解析从"逐行读、完全无视声明长度"改成按字节长度取：写侧记录的 `$<len>` 是
  UTF-8 字节数，而值里完全可以含 `\r\n`（`SET` 的合法取值），按行读会把这种值从第一个换行处
  切断、剩下的半截还会被当成下一条命令的开头。尾部截断（掉电时最后一条只写了一半）现在只丢
  那一条，前面的照常重放。
- `closeAof()` 改用 `shutdown()`：`stop()` 只关文件，两个调度线程池收不回，嵌入式起停一次
  就泄漏一对线程。

### Added
- `RedisServerLifecycleTest` 增加 5 条端到端回归：逐库快照 + TTL 活过重启 + 过期键不复活、
  无 dataDir 时 `SAVE`/`LASTSAVE` 如实报错、AOF 跨三代实例恢复（含库号、被 BLPOP 消费的值、
  含 CRLF 的值、重放不回写）、定时快照无需 `SAVE` 自己落盘、`appendfsync` 档位与非法值。
  全量 `mvn clean test`：96 + 320 + 133 + 2 = **551 例全绿**。

### 已知边界（本次没修，说清楚）
- **Stream（`X*`）不参与任何持久化**：既没有 RDB 段落，也不写 AOF —— `XADD` 在 `*` 形态下按
  当前时间生成条目 ID，重放会造出一批 ID 完全不同的条目，看着像存下来了其实对不上。
- 只有 String 键的 TTL 会进快照：`MemoryStore` 的过期信息只挂在 `stringStores` 上，
  集合类型键的 `EXPIRE` 本来就没有一个统一的地方读。
- 快照是"边读边写"的一致性级别：调度线程直接遍历活键空间，不做 fork，所以定时快照可能拍到
  一次写入的中途状态。`SAVE` 与优雅停机同样是这个级别（这比之前的"根本没有快照"仍是净收益）。

### 发布提醒
- **`1.3.2` 已经在 Central 上且不可撤销**：它的 pom 与 `1.3.1` 一样坏（parent 指向从未发布的
  `com.zifang:z-opc:1.0.0-SNAPSHOT`、`z-util-serialize-*` 钉在 Central 上不存在的 `1.0.9`）。
  外部消费者请直接用 `1.3.3` 及以上。

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
