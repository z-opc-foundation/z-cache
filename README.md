# z-cache

> **Redis 协议 (RESP2) 兼容的分布式内存数据库** — Java 11+ · Netty 4.1 · Spring Boot 2.7
> 1.3.0 新增：分布式锁 · Pub/Sub 模式匹配 · Stream 消费组 · RDB+AOF 持久化 · 集群模式规划
> 1.3.4：RDB 快照补齐 16 个库与 TTL · SAVE/BGSAVE/LASTSAVE 真正落盘 · AOF 启动时重放（含库号与阻塞命令翻译）
> 1.3.5：错类型读写如实回 `WRONGTYPE` · WATCH/EXEC 的中止判据修对（含库号隔离）· Stream 消费组补全
> · `CLIENT LIST/KILL/INFO` 从假回复变成真实现 · 同 JVM 多实例不再互串订阅态
> 1.3.6（开发中，Central 上是 1.3.5）：同一 JVM 里多台服务器不再互相改写对方的一切 ——
> Stream 键空间、慢查询账、RDB/AOF 与 LOADING 标记全部收成"一台一份"
> （实测过的最贵一条：不带 `--data-dir` 的第二台一启动，就把正在跑的那台的 SAVE 变成报错）
> · `RENAME` 改成整体替换（此前目标键的旧值会被"并"进去，跨类型时两张表各留一份）
> · 客户端集成的 12 条用例不再"探测 6379 没人就整类跳过"，改成自带一台真服务器跑满 571 例

[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.3.5-blue?logo=apache-maven)](https://central.sonatype.com/search?q=g:io.github.yuku123+a:z-cache*)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange)](https://openjdk.org)
[![Docker](https://img.shields.io/badge/Docker-multi--stage-2496ED)](https://hub.docker.com/)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Coverage](https://img.shields.io/badge/coverage-80%25%2B-green)]()

---

## 📑 目录

- [项目简介](#-项目简介)
- [核心特性](#-核心特性) — **1.3.0 新增 5 大特性**
- [架构概览](#-架构概览)
- [快速开始](#-快速开始) — Docker / Maven / 源码
- [命令参考](#-命令参考) — 1.3.0 完整命令清单
- [客户端 SDK](#-客户端-sdk)
- [部署与运维](#-部署与运维)
- [集群模式](#-集群模式) — 规划中
- [贡献指南](#-贡献指南)
- [许可证](#-许可证)
- [文档目录](#-文档目录)

---

## 🎯 项目简介

z-cache 是一个**生产就绪**的 Redis 协议兼容内存数据库，使用 Java + Netty 实现。在保持与 Redis 完全兼容的前提下，提供更轻量的部署、更现代的架构、对中文开发者友好的文档。

**定位**：Redis 的 drop-in 替代品 — 任何支持 RESP 协议的客户端（Jedis / Lettuce / redis-cli / GUI 工具）零修改即可连接。

**与其他 Redis 替代方案的差异**：

| 维度 | z-cache | Redis | KeyDB | Dragonfly |
|---|---|---|---|---|
| 语言 | Java | C | C++ (Redis fork) | C++ |
| RESP 协议 | ✅ RESP2/3 | ✅ | ✅ | ✅ |
| 持久化 | ✅ RDB+AOF | ✅ | ✅ | ⚠️ 部分 |
| Cluster 模式 | 🚧 1.3.0 规划 | ✅ | ✅ | ✅ |
| 依赖 | Netty + JUC | glibc | glibc | glibc |
| 包大小 | ~5MB | ~1MB | ~3MB | ~5MB |
| 中文文档 | ✅ 完善 | ⚠️ 社区翻译 | ❌ | ❌ |

---

## ⭐ 核心特性

### 1.3.0 新增 5 大特性（重点）

| 特性 | 简介 | 详细文档 |
|---|---|---|
| **🔒 分布式锁** | 基于 SET NX PX 的 tryLock / unlock / renew / Watchdog 自动续约 / fencing token；**没有 Lua**，解锁走"先 GET 校验再 DEL"，非原子 | [_doc/001_arch/分布式锁设计.md](_doc/001_arch/分布式锁设计.md) |
| **📡 Pub/Sub 模式匹配** | 支持 PSUBSCRIBE `news.*` 通配符模式订阅，兼容 Redis PSUBSCRIBE/PUNSUBSCRIBE 规范 | （1.3.0 文档规划中） |
| **📋 Stream 消费组** | XADD/XREAD/XREADGROUP/XACK/XPENDING/XGROUP/XINFO，支持消费者组与 pending list（XCLAIM/XAUTOCLAIM 未实现） | （1.3.0 文档规划中） |
| **💾 RDB + AOF 持久化** | RDB 快照（逐库、带 TTL）+ AOF 增量日志（启动时重放），fsync 三档 `always/everysec/no`。AOF 重写未实现（`rewriteAof` 是个只写空文件的壳，且没有任何命令能触发它，因此不要指望 BGREWRITEAOF） | （1.3.0 文档规划中） |
| **📊 运维命令** | INFO/MONITOR/DEBUG/CLIENT/SLOWLOG 5 类运维命令，含集群监控和慢日志追踪 | （1.3.0 文档规划中） |

### 已有能力（继承自 1.0.x）

- **多数据结构**：String / List / Set / Sorted Set / Hash（Bitmap / HyperLogLog / Geo 未实现）
- **RESP2 协议**：完全兼容 Redis 2.x 客户端
- **TTL 与淘汰**：支持毫秒级 TTL、LRU/LFU 淘汰策略
- **事务**：MULTI/EXEC/DISCARD/WATCH/UNWATCH
- **Pipeline**：批量命令减少 RTT
- **Lua 脚本**：🚧 未实现（服务端没有 EVAL / EVALSHA）
- **Pipeline 客户端**：同步 + 异步 + 连接池
- **Spring Boot Starter**：开箱即用
- **Docker / Compose**：多阶段构建镜像

---

## 🏗️ 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│              客户端 (Jedis / Lettuce / redis-cli)            │
└───────────────────────────┬─────────────────────────────────┘
                            │ RESP2 / RESP3 over TCP
┌───────────────────────────▼─────────────────────────────────┐
│                  Network Layer (Netty 4.1)                   │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│            RESP Parser · Command Router                       │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│                   Storage Engine                              │
│  String  List  Set  ZSet  Hash  Stream(新)                    │
│  Bitmap  HyperLogLog  Geo                                      │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│            Persistence Layer (新)                             │
│       RDB 快照  +  AOF 增量日志 (三种 fsync)                  │
└─────────────────────────────────────────────────────────────┘
```

详细架构：[_doc/001_arch/01-module-structure.md](_doc/001_arch/01-module-structure.md)

---

## 🚀 快速开始

### 方式 1：Docker（30 秒）

```bash
docker run -d --name z-cache \
  -p 16379:6379 \
  -v $(pwd)/data:/data \
  ghcr.io/z-opc-foundation/z-cache:1.3.0 \
  --maxmemory 2gb --appendonly yes
```

### 方式 2：Maven 依赖（Java 客户端）

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-cache-spring-boot-starter</artifactId>
    <version>1.3.0</version>
</dependency>
```

```yaml
# application.yml
z:
  cache:
    enabled: true
    host: localhost
    port: 16379
    pool:
      max-active: 32
```

### 方式 3：源码编译

```bash
git clone https://github.com/z-opc-foundation/z-cache.git
cd z-cache
mvn clean install -DskipTests
java -jar z-cache-server/target/z-cache-server-1.3.0.jar
```

### 第一个 SET / GET

```bash
redis-cli -h localhost -p 16379 SET hello world
# OK
redis-cli -h localhost -p 16379 GET hello
# "world"
```

### 1.3.0 新特性示例

```bash
# 分布式锁（自研 Redlock 等价实现）
redis-cli -h localhost -p 16379 SET lock:order:123 "owner-uuid" NX PX 30000
# OK
redis-cli -h localhost -p 16379 SET lock:order:123 "another" NX PX 30000
# (nil) — 已锁定

# Pub/Sub 模式订阅
redis-cli -h localhost -p 16379 PSUBSCRIBE "news.*"
# PSUBSCRIBE news.* — 等待匹配 "news.sports" / "news.tech" 等

# Stream 消费组
redis-cli -h localhost -p 16379 XADD mystream * field1 value1
redis-cli -h localhost -p 16379 XGROUP CREATE mystream mygroup 0
redis-cli -h localhost -p 16379 XREADGROUP GROUP mygroup consumer1 COUNT 10 STREAMS mystream >

# AOF 持久化（--data-dir 一给就开，默认 everysec）
# 注意：本服务未实现 CONFIG 命令，运行时改配置请通过启动参数 / -Dzcache.* 完成
redis-cli -h localhost -p 16379 SAVE            # 同步打一份 RDB 快照
redis-cli -h localhost -p 16379 LASTSAVE        # 最近一次成功快照的 Unix 秒

# 运维监控
redis-cli -h localhost -p 16379 INFO server
redis-cli -h localhost -p 16379 SLOWLOG GET 10
```

---

## 📖 命令参考

### 1.3.0 完整支持命令分类

| 类别 | 命令 | 状态 |
|---|---|---|
| **Key** | SET / GET / DEL / EXISTS / KEYS / TYPE / EXPIRE / TTL / PERSIST | ✅ 但 `EXPIRE`/`TTL`/`PERSIST` **只对 String 键生效**：集合键上实测 `EXPIRE k 100`→`:0`、`TTL k`→`:-2`，而同一个键 `TYPE`→`hash`、`EXISTS`→`:1`（Redis 会真的挂上 TTL） |
| **String** | SETNX / SETEX / GETSET / APPEND / STRLEN / INCR / DECR | ✅ 用错类型读写一律 `-WRONGTYPE`（1.3.5 起，此前静默回 nil/0） |
| **Hash** | HSET / HGET / HDEL / HMSET / HMGET / HGETALL / HEXISTS | ✅ |
| **List** | LPUSH / RPUSH / LPOP / RPOP / LRANGE / LLEN / LSET / LTRIM / LMOVE / RPOPLPUSH / BRPOPLPUSH | ✅ 1.3.5 补 `BRPOPLPUSH` |
| **Set** | SADD / SREM / SMEMBERS / SISMEMBER / SINTER / SUNION / SDIFF | ✅ |
| **ZSet** | ZADD / ZRANGE / ZRANGEBYSCORE / ZRANK / ZINCRBY | ✅ |
| **🔒 分布式锁** | SET NX PX ✅ / EVAL·EVALSHA 🚧 未实现 | 服务端没有 Lua 解释器；客户端 `DistributedLock` 会降级成"先 GET 校验再 DEL"，**不是原子的**（跨进程竞争下可能误删别人的锁） |
| **📡 Pub/Sub** | PUBLISH / SUBSCRIBE / UNSUBSCRIBE / PSUBSCRIBE / PUNSUBSCRIBE / PUBSUB | ✅ 确认包的第 3 个数从 1.3.5 起是"这条连接的频道数+模式数"（此前每条命令各自从 1 数，客户端据此记账会错位） |
| **📋 Stream** | XADD / XREAD / XREADGROUP / XACK / XPENDING / XGROUP / XINFO | ✅ `XINFO CONSUMERS` 1.3.5 起才有实现（此前只有注释里没有 case）；XCLAIM 🚧 未实现；`XPENDING` 只有汇总形态，明细形式（`IDLE`/`start end count`）明确报错 |
| **💾 持久化** | SAVE / BGSAVE / LASTSAVE | ✅ 1.3.4 起才真正落盘（此前三条命令只回一个写死的成功回复）；1.3.6 起这三板的作用域是"本台服务器"，同 JVM 里再起一台不带 `--data-dir` 的不会把这台关掉；BGREWRITEAOF 🚧 未实现 |
| **📊 运维** | INFO / MONITOR / DEBUG / CLIENT / SLOWLOG | ✅ `CLIENT LIST` 从 1.3.5 起列出本机全部连接且 `sub=`/`psub=` 是真值（此前只有发起者一行、两个数写死 0）、`CLIENT KILL` 真关连接、新增 `CLIENT INFO`；SLOWLOG 1.3.5 才接上真实服务器（此前恒回 not configured） |
| **事务** | MULTI / EXEC / DISCARD / WATCH / UNWATCH | ✅ 1.3.5 修掉 WATCH 的两处失效：复查用的版本尺恒返回 0（该中止的中止不了），且 EXEC/DISCARD 不清 WATCH（上一条事务的观察键会永久挂着，把后来的事务无端打掉） |
| **Pipeline** | 客户端 SDK 自动支持 | ✅ |
| **Lua** | EVAL / EVALSHA / SCRIPT | 🚧 **未实现**（`src/main` 里三条命令零处理，回 `ERR unknown command`；本表此前标的是 ✅） |

> 注：完整命令清单（含参数说明）见 [_doc/001_arch/01-module-structure.md §2.1](_doc/001_arch/01-module-structure.md)。

---

## 💼 客户端 SDK

### Java（同步 / 异步 / 池化）

```java
// 同步 API
try (ZCacheClient client = ZCacheClient.builder()
        .host("localhost").port(16379)
        .build()) {
    client.set("key", "value");
    String value = client.get("key");
}

// 分布式锁（1.3.0 新 API）
try (ZCacheClient client = ZCacheClient.builder()
        .host("localhost").port(16379).build()) {
    DistributedLock locks = client.distributedLock();
    Lock lock = locks.tryLock("order:123", 30_000); // TTL 30s
    if (lock != null) {
        try {
            // 业务逻辑（Watchdog 自动续约）
        } finally {
            locks.unlock(lock);
        }
    }
}
```

### 其他语言

z-cache 协议与 Redis 完全兼容，使用现有 Redis 客户端即可：

| 语言 | 客户端 | 1.3.0 集群模式兼容 | 备注 |
|---|---|---|---|
| Java | Jedis / Lettuce | 🚧 规划中 | 推荐 Lettuce 6.x+ |
| Python | redis-py | ✅ | 当前只读集群模式 |
| Go | go-redis | ✅ | 当前只读集群模式 |
| Node.js | ioredis | ✅ | 当前只读集群模式 |
| C# | StackExchange.Redis | ✅ | 当前只读集群模式 |

---

## 🚢 部署与运维

### 单节点 Docker

```bash
docker run -d --name z-cache -p 16379:6379 ghcr.io/z-opc-foundation/z-cache:1.3.0
```

### Docker Compose（含 Prometheus + Grafana 监控）

```yaml
services:
  z-cache:
    image: ghcr.io/z-opc-foundation/z-cache:1.3.0
    ports: ["16379:6379"]
    volumes: ["./data:/data"]
    environment:
      - JAVA_OPTS=-Xmx2g -Xms1g

  prometheus:
    image: prom/prometheus
    ports: ["9090:9090"]
    volumes: ["./prometheus.yml:/etc/prometheus/prometheus.yml"]

  grafana:
    image: grafana/grafana
    ports: ["3000:3000"]
```

### K8s 部署

完整 K8s manifest 见 [z-opc-foundation/k8s/z-cache.yaml](../../../k8s/z-cache.yaml)（如该仓库对外公开）。

### 监控集成

z-cache 自报家门（INFO/MONITOR 1.3.0 新增），通过 z-gw 接入 Prometheus。指标包括：

- `used_memory` / `maxmemory` / `mem_fragmentation_ratio`
- `instantaneous_ops_per_sec` / `keyspace_hits` / `keyspace_misses`
- `connected_clients` / `blocked_clients`
- 集群状态：`cluster_state` / `cluster_slots_assigned` / `cluster_size`（1.3.0 集群版）

完整运维手册（部署 / 扩缩容 / 故障转移 / 应急 SOP）见：

**[_doc/002_deploy/集群模式运维手册.md](_doc/002_deploy/集群模式运维手册.md)**（12 章 907 行，1.3.0 目标架构 + 2.0.0 集群规划）

---

## 🌐 集群模式（1.3.0 规划，2.0.0 落地）

z-cache 1.3.0 单节点 Redlock 等价能力已就绪，**真正的集群能力**计划在 2.0.0：

- **数据分片**：16384 哈希槽（CRC16(key) % 16384），与 Redis Cluster 完全兼容
- **故障转移**：内置 Gossip 协议 + 多数派选举 + Replica 自动晋升
- **在线扩缩容**：以 slot 为粒度，支持平滑迁移
- **客户端兼容性**：Jedis / Lettuce / Redisson 等主流客户端零修改接入

调研结论详见 [_doc/001_arch/集群模式架构选型-2026Q4.md](_doc/001_arch/集群模式架构选型-2026Q4.md)。

---

## 🤝 贡献指南

z-cache 是开源项目，欢迎所有形式的贡献：

- 🐛 **报告 Bug**：GitHub Issues
- 💡 **提议功能**：GitHub Discussions
- 🔧 **提交 PR**：参考 [CONTRIBUTING.md](CONTRIBUTING.md)
- 📖 **完善文档**：直接 PR 到对应 `_doc/` 目录
- 🌍 **翻译**：所有 `_doc/001_arch/*.md` 已有中文，欢迎补英文版

### 开发环境

- JDK 11+
- Maven 3.8+
- Netty 4.1.100.Final
- 推荐 IDE：IntelliJ IDEA

### 代码规范

- 遵循 [z-opc-foundation Java 代码规范](../../../z-opc-foundation-lead/008_组织规范/)
- 模块命名：`com.zifang.z.cache.*`
- 提交规范：Conventional Commits（`feat:` / `fix:` / `docs:` / `refactor:` / `test:`）

---

## 📜 许可证

z-cache 采用 **MIT License**。详见 [LICENSE](LICENSE)。

```
MIT License

Copyright (c) 2026 z-opc-foundation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, ...
```

---

## 📂 文档目录

本项目文档统一收口在 `_doc/` 下（按 z-opc-foundation 文档收口规范）：

- [`_doc/001_arch/`](_doc/001_arch/) — 架构文档
    - [01-module-structure.md](_doc/001_arch/01-module-structure.md) — 模块结构与命令清单
    - [分布式锁方案选型-2026Q4.md](_doc/001_arch/分布式锁方案选型-2026Q4.md) — Redisson vs 自研选型（推荐自研）
    - [集群模式架构选型-2026Q4.md](_doc/001_arch/集群模式架构选型-2026Q4.md) — 一致性哈希 vs 16384 槽位（推荐 Redis Cluster 风格）
    - [分布式锁设计.md](_doc/001_arch/分布式锁设计.md) — DistributedLock API + Lua 解锁 + Watchdog 设计
- [`_doc/002_deploy/`](_doc/002_deploy/) — 部署文档
    - [集群模式运维手册.md](_doc/002_deploy/集群模式运维手册.md) — 12 章 907 行运维手册
- [`_doc/003_script/`](_doc/003_script/) — 运维脚本
    - [build.sh](_doc/003_script/build.sh) · [deploy_maven_center.sh](_doc/003_script/deploy_maven_center.sh) · [package.sh](_doc/003_script/package.sh) · 等

详见各子目录。

> **关于 _doc 子目录命名**：z-opc-foundation 规范要求 `_doc/004_skill/`，但 z-cache 1.0.x 历史版本使用 `_doc/003_script/`。z-cache 1.3.0 在保持历史兼容的前提下，规划在 2.0.0 重命名为 `001_arch / 002_deploy / 003_script / 004_skill`。

---

## 📮 联系与反馈

- **GitHub**: https://github.com/z-opc-foundation/z-cache
- **Issues**: https://github.com/z-opc-foundation/z-cache/issues
- **Maven Central**: https://central.sonatype.com/artifact/io.github.yuku123/z-cache-parent
- **Docker Hub**: https://hub.docker.com/r/zopcfoundation/z-cache

---

**z-cache 1.3.0** — 让缓存回归简单
