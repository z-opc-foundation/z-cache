# z-cache 架构设计文档

## 1. 项目概述

z-cache 是一个基于 Java 实现的高性能内存键值存储系统，设计目标是提供类似 Redis 的核心功能，包括多种数据结构支持、持久化、高可用等特性。

## 2. 核心功能模块

### 2.1 基础数据结构与命令支持

#### 2.1.1 String（字符串）

- **基础操作**: `SET`, `GET`, `DEL`
- **批量操作**: `MSET`, `MGET`
- **数值操作**: `INCR`, `DECR`, `INCRBY`, `DECRBY`
- **字符串操作**: `APPEND`, `STRLEN`, `GETRANGE`, `SETRANGE`
- **过期设置**: `SETEX`, `PSETEX`, `SETNX`, `GETSET`

#### 2.1.2 List（列表）

- **压入弹出**: `LPUSH`, `RPUSH`, `LPOP`, `RPOP`
- **批量操作**: `LPUSHX`, `RPUSHX`
- **查询操作**: `LRANGE`, `LLEN`, `LINDEX`
- **修改操作**: `LSET`, `LINSERT`, `LREM`, `LTRIM`
- **阻塞操作**: `BLPOP`, `BRPOP`, `BRPOPLPUSH`
- **转移操作**: `RPOPLPUSH`

#### 2.1.3 Set（集合）

- **基础操作**: `SADD`, `SREM`, `SISMEMBER`, `SCARD`
- **查询操作**: `SMEMBERS`, `SRANDMEMBER`, `SPOP`
- **集合运算**: `SINTER`, `SINTERSTORE`, `SUNION`, `SUNIONSTORE`, `SDIFF`, `SDIFFSTORE`
- **迭代操作**: `SSCAN`, `SMOVE`

#### 2.1.4 Sorted Set（有序集合）

- **基础操作**: `ZADD`, `ZREM`, `ZCARD`, `ZSCORE`, `ZINCRBY`
- **范围查询**: `ZRANGE`, `ZREVRANGE`, `ZRANGEBYSCORE`, `ZREVRANGEBYSCORE`, `ZRANGEBYLEX`, `ZREVRANGEBYLEX`
- **排名查询**: `ZRANK`, `ZREVRANK`, `ZCOUNT`, `ZLEXCOUNT`, `ZREMRANGEBYRANK`, `ZREMRANGEBYSCORE`, `ZREMRANGEBYLEX`
- **聚合操作**: `ZUNIONSTORE`, `ZINTERSTORE`, `ZPOPMIN`, `ZPOPMAX`, `BZPOPMIN`, `BZPOPMAX`

#### 2.1.5 Hash（哈希表）

- **基础操作**: `HSET`, `HGET`, `HDEL`, `HSETNX`, `HMSET`, `HMGET`
- **查询操作**: `HGETALL`, `HKEYS`, `HVALS`, `HLEN`, `HEXISTS`, `HSTRLEN`
- **数值操作**: `HINCRBY`, `HINCRBYFLOAT`
- **扫描操作**: `HSCAN`

#### 2.1.6 通用命令

- **键管理**: `KEYS`, `EXISTS`, `DEL`, `UNLINK`, `EXPIRE`, `PEXPIRE`, `TTL`, `PTTL`, `PERSIST`, `EXPIREAT`, `PEXPIREAT`,
  `TOUCH`, `RENAME`, `RENAMENX`
- **类型操作**: `TYPE`, `OBJECT`, `ENCODING`
- **数据库操作**: `SELECT`, `SWAPDB`, `MOVE`, `FLUSHDB`, `FLUSHALL`, `DBSIZE`
- **事务操作**: `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`
- **服务器信息**: `INFO`, `CONFIG`, `CLIENT`, `SLOWLOG`, `MONITOR`, `TIME`, `ECHO`, `PING`, `QUIT`, `COMMAND`, `ACL`
- **Lua脚本**: `EVAL`, `EVALSHA`, `SCRIPT`, `FUNCTION`
- **集群命令**: `CLUSTER`, `READONLY`, `READWRITE`, `ASKING`, `MIGRATE`, `RESTORE`, `DUMP`
- **发布订阅**: `SUBSCRIBE`, `UNSUBSCRIBE`, `PUBLISH`, `PSUBSCRIBE`, `PUNSUBSCRIBE`, `PUBSUB`
- **Stream**: `XADD`, `XREAD`, `XDEL`, `XRANGE`, `XREVRANGE`, `XLEN`, `XREADGROUP`, `XACK`, `XCLAIM`, `XPENDING`,
  `XGROUP`, `XINFO`, `XTRIM`
- **Bitmap**: `SETBIT`, `GETBIT`, `BITCOUNT`, `BITPOS`, `BITOP`, `BITFIELD`
- **HyperLogLog**: `PFADD`, `PFCOUNT`, `PFMERGE`
- **Geo**: `GEOADD`, `GEOPOS`, `GEODIST`, `GEORADIUS`, `GEORADIUSBYMEMBER`, `GEOHASH`, `GEOSEARCH`, `GEOSEARCHSTORE`

### 2.2 网络通信模块

#### 2.2.1 通信协议

- **RESP2 协议**: Redis Serialization Protocol 第二版
- **RESP3 协议**: Redis Serialization Protocol 第三版（可选高级支持）

#### 2.2.2 服务端网络架构

- **NIO 网络模型**: 基于 Java NIO 的非阻塞 IO
- **多线程 Reactor 模型**:
    - 1 个 Accept 线程处理连接建立
    - N 个 IO 线程处理网络读写（可配置）
    - M 个 Worker 线程执行业务逻辑
- **连接管理**: 最大连接数限制、空闲连接超时、连接保活

#### 2.2.3 客户端 SDK 设计

- **同步客户端**: 阻塞式 API，简单易用
- **异步客户端**: 基于 CompletableFuture 的非阻塞 API
- **连接池管理**: 连接复用、健康检查、自动重连
- **哨兵/集群支持**: 自动故障转移、读写分离

### 2.3 持久化机制

#### 2.3.1 AOF（Append Only File）

- **写入策略**:
    - `always`: 每个命令同步写入，最安全但性能最低
    - `everysec`: 每秒同步一次，平衡安全与性能（默认）
    - `no`: 由操作系统决定，性能最高但可能丢失数据
- **AOF 重写**: 后台进程压缩 AOF 文件，去除冗余命令
- **重写触发条件**: 文件大小增长比例、最小重写大小

#### 2.3.2 RDB（Redis Database）

- **快照机制**: 定期将内存数据全量保存到二进制文件
- **保存策略**:
    - 手动触发: `SAVE`（阻塞）、`BGSAVE`（后台）
    - 自动触发: 配置时间窗口内的修改次数
- **写时复制（COW）**: fork 子进程进行快照，避免阻塞主进程
- **RDB 文件格式**: 紧凑的二进制格式，快速加载

#### 2.3.3 混合持久化（可选高级功能）

- **AOF-RDB 混合**: AOF 文件前部为 RDB 格式，后部为 AOF 命令
- **加载优势**: 重启时先加载 RDB 部分快速恢复，再回放 AOF 增量

### 2.4 内存管理与数据淘汰

#### 2.4.1 内存管理

- **内存统计**: 使用内存、数据集内存、缓冲区内存、碎片率
- **内存上限**: 配置最大内存限制，触发淘汰机制
- **内存碎片整理**: 在线碎片整理（可选高级功能）

#### 2.4.2 过期键管理

- **惰性删除**: 访问时检查过期并删除
- **定期删除**: 后台定时随机抽样删除过期键
- **内存淘汰触发条件**: 内存达到上限且无法回收过期键

#### 2.4.3 淘汰策略

- `noeviction`: 不淘汰，写入报错（默认）
- `allkeys-lru`: 所有键中 LRU 淘汰
- `allkeys-lfu`: 所有键中 LFU 淘汰
- `allkeys-random`: 所有键中随机淘汰
- `volatile-lru`: 过期键中 LRU 淘汰
- `volatile-lfu`: 过期键中 LFU 淘汰
- `volatile-random`: 过期键中随机淘汰
- `volatile-ttl`: 过期键中优先淘汰 TTL 短的

### 2.5 高可用与复制

#### 2.5.1 主从复制（Replication）

- **复制架构**: 一主多从，读写分离
- **全量复制**: 初始同步 RDB 文件
- **增量复制**: 传播写命令到从节点
- **部分重同步**: 基于复制偏移量的断点续传
- **无磁盘复制**: 直接网络传输 RDB（可选）

#### 2.5.2 哨兵模式（Sentinel）

- **哨兵集群**: 多哨兵节点监控主从
- **故障检测**: 主观下线、客观下线判定
- **自动故障转移**: 选举新主节点，切换从节点
- **配置提供**: 向客户端暴露当前主节点地址

#### 2.5.3 集群模式（Cluster）

- **数据分片**: 16384 个哈希槽分配到各节点
- **节点通信**: Gossip 协议维护集群状态
- **请求路由**: 客户端直连或重定向（MOVED/ASK）
- **故障转移**: 主节点故障时从节点自动晋升
- **在线扩缩容**: 动态调整哈希槽分配和迁移

### 2.6 事务与高级功能

#### 2.6.1 事务支持

- **MULTI/EXEC**: 命令队列批量执行
- **WATCH**: 乐观锁监控键变化
- **DISCARD**: 取消事务队列
- **事务特性**: 保证命令顺序执行，但无回滚能力

#### 2.6.2 流水线（Pipeline）

- **批量发送**: 客户端一次发送多条命令
- **批量接收**: 服务端批量返回结果
- **性能提升**: 减少网络 RTT，提高吞吐量

#### 2.6.3 Lua 脚本

- **EVAL/EVALSHA**: 服务端执行 Lua 脚本
- **脚本特性**: 原子性执行，支持逻辑控制
- **脚本缓存**: SHA 摘要复用已加载脚本
- **函数库（Redis 7.0+）**: 持久化存储的函数集合

### 2.7 发布订阅与 Stream

#### 2.7.1 发布订阅（Pub/Sub）

- **订阅频道**: `SUBSCRIBE`, `UNSUBSCRIBE`
- **模式订阅**: `PSUBSCRIBE`, `PUNSUBSCRIBE`
- **消息发布**: `PUBLISH`
- **状态查询**: `PUBSUB`

#### 2.7.2 Stream（流）

- **消息追加**: `XADD`，自动生成消息 ID
- **消息读取**: `XREAD`, `XREADGROUP`
- **范围查询**: `XRANGE`, `XREVRANGE`
- **消费组**: `XGROUP` 创建管理消费者组
- **消息确认**: `XACK` 确认已处理消息
- **待处理查询**: `XPENDING` 查看未确认消息
- **消息认领**: `XCLAIM` 转移超时消息所有权
- **修剪控制**: `XTRIM`, `XDEL`, `XINFO`

### 2.8 特殊数据类型

#### 2.8.1 Bitmap（位图）

- **位操作**: `SETBIT`, `GETBIT`
- **统计操作**: `BITCOUNT`, `BITPOS`
- **位运算**: `BITOP`（AND, OR, XOR, NOT）
- **位域**: `BITFIELD` 批量操作多位

#### 2.8.2 HyperLogLog（基数统计）

- **添加元素**: `PFADD`
- **估算基数**: `PFCOUNT`
- **合并统计**: `PFMERGE`

#### 2.8.3 Geo（地理位置）

- **添加位置**: `GEOADD`
- **查询位置**: `GEOPOS`, `GEOHASH`
- **距离计算**: `GEODIST`
- **范围查询**: `GEORADIUS`, `GEORADIUSBYMEMBER`
- **通用搜索**: `GEOSEARCH`, `GEOSEARCHSTORE`

## 3. 系统架构

### 3.1 模块划分

```
z-cache/
├── z-cache-core          # 核心引擎模块
│   ├── server           # 服务端网络与协议处理
│   ├── command          # 命令解析与执行
│   ├── storage          # 内存数据存储引擎
│   ├── persistence      # 持久化机制（AOF/RDB）
│   ├── replication      # 主从复制
│   ├── cluster          # 集群分片
│   ├── sentinel         # 哨兵高可用
│   └── script           # Lua脚本引擎
│
├── z-cache-client       # 客户端SDK
│   ├── sync             # 同步客户端
│   ├── async            # 异步客户端
│   ├── pool             # 连接池
│   ├── sentinel         # 哨兵支持
│   └── cluster          # 集群支持
│
└── z-cache-common       # 公共组件（可选）
    ├── protocol         # RESP协议定义
    ├── util             # 工具类
    └── model            # 数据模型
```

### 3.2 核心组件架构

#### 3.2.1 服务端架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Client Connection                       │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│                 Network Layer (NIO/Netty)                    │
│  • Accept Thread  • IO Thread Pool  • Encoder/Decoder        │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│              RESP Protocol Parser                            │
│  • Command Parsing  • Validation  • Pipeline Support         │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│              Command Router & Executor                     │
│  • Command Mapping  • Permission Check  • Stats            │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│                 Storage Engine                               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │  String  │ │   List   │ │   Set    │ │  ZSet    │        │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │   Hash   │ │  Stream  │ │  Bitmap  │ │   Geo    │        │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│              Persistence Layer                               │
│  ┌──────────────┐      ┌──────────────┐                     │
│  │   AOF File   │      │   RDB File   │                     │
│  │  (Append)    │      │  (Snapshot)  │                     │
│  └──────────────┘      └──────────────┘                     │
└─────────────────────────────────────────────────────────────┘
```

#### 3.2.2 客户端架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Client Application                        │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────┴─────────────────────────────────┐
│                   z-cache-client SDK                           │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐               │
│  │   Sync API │  │  Async API │  │   Pool     │               │
│  └────────────┘  └────────────┘  └────────────┘               │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────┴─────────────────────────────────┐
│              Connection Management                             │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐               │
│  │ Single Node│  │  Sentinel  │  │   Cluster  │               │
│  └────────────┘  └────────────┘  └────────────┘               │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────┴─────────────────────────────────┐
│              Network Communication                           │
│  • Connection Pool  • Reconnection  • Pipeline  • Pub/Sub    │
└─────────────────────────────────────────────────────────────┘
```

## 4. 关键技术实现

### 4.1 内存存储引擎

#### 4.1.1 底层数据结构选型

| 数据类型        | 内部实现                       | 适用场景     |
|-------------|----------------------------|----------|
| String      | `byte[]` / `String`        | 缓存、计数器   |
| List        | `LinkedList` / `QuickList` | 消息队列、时间线 |
| Set         | `HashSet` / `IntSet`       | 去重、交集并集  |
| ZSet        | `SkipList` + `HashMap`     | 排行榜、延时队列 |
| Hash        | `HashMap` / `ZipList`      | 对象存储、购物车 |
| Stream      | `RadixTree`                | 消息流、日志   |
| Bitmap      | `BitSet` / `RoaringBitmap` | 签到、统计    |
| HyperLogLog | `HyperLogLog` 算法           | UV 统计    |
| Geo         | `GeoHash` + `Sorted Set`   | 地理位置     |

#### 4.1.2 键空间管理

```java
// 键值对存储结构示意
class RedisDB {
    Map<String, RedisObject> keySpace;  // 主键空间
    Map<String, Long> expires;          // 过期时间映射
    int id;                             // 数据库编号
}
```

### 4.2 持久化实现

#### 4.2.1 AOF 持久化流程

```
┌─────────────────────────────────────────────────────────────┐
│  Client Command                                              │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Execute Command → Update Memory                            │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Append Command to AOF Buffer                               │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Flush Buffer to Disk (everysec/always/no)                 │
└─────────────────────────────────────────────────────────────┘
```

#### 4.2.2 RDB 快照流程

```
┌─────────────────────────────────────────────────────────────┐
│  Trigger (Manual / Configured Schedule)                     │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Fork Child Process (Copy-On-Write)                         │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Child: Scan Key Space → Serialize → Write RDB File        │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Parent: Continue Serve Clients                             │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 复制机制

#### 4.3.1 主从复制流程

```
┌──────────┐                              ┌──────────┐
│  Slave   │                              │  Master  │
└────┬─────┘                              └────┬─────┘
     │  1. SYNC/PSYNC runid offset              │
     │ ───────────────────────────────────────> │
     │                                          │
     │  2. FULLRESYNC runid offset              │
     │ <─────────────────────────────────────── │
     │     或 CONTINUE（部分重同步）              │
     │                                          │
     │  3. Transfer RDB file (if full sync)       │
     │ <═══════════════════════════════════════ │
     │                                          │
     │  4. Propagate write commands               │
     │ <─────────────────────────────────────── │
     │     (持续进行增量复制)                     │
```

### 4.4 集群架构

#### 4.4.1 集群数据分片

```
┌─────────────────────────────────────────────────────────────┐
│                    Cluster Topology                           │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│   ┌──────────────┐      ┌──────────────┐      ┌──────────┐  │
│   │  Master A    │◄────►│  Master B    │◄────►│ Master C │  │
│   │  Slots 0-5460│      │  Slots 5461-10922    │ Slots 10923-16383    │
│   └──────┬───────┘      └──────┬───────┘      └────┬─────┘  │
│          │                     │                   │        │
│   ┌──────▼───────┐      ┌──────▼───────┐      ┌────▼────┐  │
│   │  Slave A1    │      │  Slave B1    │      │ Slave C1│  │
│   └──────────────┘      └──────────────┘      └─────────┘  │
│                                                               │
└─────────────────────────────────────────────────────────────┘

Slot 分配示例:
┌─────────────────────────────────────────────────────────────┐
│  Slot 0    Slot 1    ...    Slot 5460    ...    Slot 16383  │
│    │         │               │                          │   │
│    ▼         ▼               ▼                          ▼   │
│  [Key1]   [Key2]           [KeyN]                    [KeyM] │
│                                                           │
│  CRC16(key) % 16384 → Slot 编号                           │
└─────────────────────────────────────────────────────────────┘
```

#### 4.4.2 请求路由流程

```
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│   Client     │         │   Cluster    │         │   Correct    │
│              │         │   Node       │         │   Node       │
└──────┬───────┘         └──────┬───────┘         └──────┬───────┘
       │                        │                        │
       │  1. GET key           │                        │
       │ ─────────────────────>│                        │
       │                        │  2. 计算 slot = CRC16(key) % 16384
       │                        │                        │
       │                        │  3. 发现 key 不在本节点   │
       │                        │                        │
       │  4. MOVED slot node_id│                        │
       │ <──────────────────────│                        │
       │                        │                        │
       │  5. 缓存槽位映射,重定向请求 │                        │
       │ ───────────────────────────────────────────────>│
       │                        │                        │
       │                        │                        │ 6. 执行 GET
       │                        │                        │
       │  7. 返回结果           │                        │
       │ <───────────────────────────────────────────────│
```

### 4.5 性能优化策略

#### 4.5.1 数据结构优化

- **编码优化**: 小整数、短字符串使用紧凑编码
- **渐进式 rehash**: Hash 表扩容时渐进迁移，避免阻塞
- ** quicklist**: List 由多个 ziplist 组成，平衡插入和遍历性能
- **intset**: 小整数集合使用紧凑数组存储

#### 4.5.2 网络优化

- **Pipeline**: 批量发送命令减少 RTT
- **连接池**: 复用连接减少建立开销
- **响应压缩**: 大值传输时启用压缩
- **零拷贝**: 文件传输使用 sendfile

#### 4.5.3 内存优化

- **共享对象**: 常用值共享引用
- **内存预分配**: 减少频繁扩容
- **压缩存储**: 大对象使用压缩编码
- **智能淘汰**: 根据访问模式选择淘汰策略

## 5. 技术选型

### 5.1 核心技术栈

| 组件   | 技术选型             | 说明         |
|------|------------------|------------|
| 编程语言 | Java 8+          | 广泛兼容，生态丰富  |
| 构建工具 | Maven            | 标准构建工具     |
| 网络通信 | Java NIO / Netty | 高性能网络框架    |
| 序列化  | 自定义 RESP 实现      | Redis 协议兼容 |
| 数据结构 | Java 集合 + 自定义    | 根据需求定制     |
| 持久化  | 文件 IO / NIO      | 高性能文件操作    |
| 并发控制 | JUC 包            | Java 并发工具  |
| 日志   | SLF4J + Logback  | 标准日志方案     |

### 5.2 外部依赖

```xml
<!-- 核心依赖 -->
<dependencies>
    <!-- 高性能网络 -->
    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
    </dependency>

    <!-- 序列化 -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- 日志 -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
    </dependency>

    <!-- 测试 -->
    <dependency>
        <groupId>junit</groupId>
        <artifactId>junit</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## 6. 演进路线

### 6.1 第一阶段：基础功能（MVP）

- [ ] 项目基础架构搭建
- [ ] RESP 协议解析实现
- [ ] String 数据结构及命令
- [ ] 基础网络服务（NIO）
- [ ] 简单客户端 SDK

### 6.2 第二阶段：核心功能完善

- [ ] List、Set、Hash、ZSet 数据结构
- [ ] AOF 持久化
- [ ] 过期键管理
- [ ] 事务支持（MULTI/EXEC）
- [ ] Pipeline 支持

### 6.3 第三阶段：高级特性

- [ ] RDB 持久化
- [ ] 主从复制
- [ ] 哨兵高可用
- [ ] 内存淘汰策略
- [ ] Lua 脚本支持

### 6.4 第四阶段：集群与扩展

- [ ] Cluster 集群模式
- [ ] 数据自动分片
- [ ] 在线扩缩容
- [ ] Stream 数据结构
- [ ] 高级客户端（哨兵/集群感知）

### 6.5 第五阶段：性能优化

- [ ] 多线程 IO 优化
- [ ] 内存碎片整理
- [ ] 性能监控与统计
- [ ] 配置热更新
- [ ] 生产环境完善

## 7. 设计原则

1. **兼容性优先**: 协议和命令与 Redis 保持兼容，降低学习和迁移成本
2. **渐进式实现**: 按优先级分阶段实现，确保核心功能稳定后再扩展
3. **Java 生态融合**: 遵循 Java 编码规范，与 Spring 等框架良好集成
4. **可观测性**: 内置监控指标，便于运维和调优
5. **可扩展性**: 模块化解耦，便于后续功能扩展和维护

---

**文档版本**: v1.0
**最后更新**: 2026-03-21
