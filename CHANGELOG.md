# Changelog

All notable changes to z-cache will be documented in this file.

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
