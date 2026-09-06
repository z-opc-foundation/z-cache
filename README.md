# z-cache

一个兼容Redis协议的内存缓存服务器，使用Java + Netty实现。

## 功能特性

- ✅ 兼容Redis协议 (RESP)
- ✅ 基础命令：PING, ECHO, QUIT, SELECT
- ✅ 字符串操作：SET, GET, DEL, EXISTS
- ✅ 过期时间：EXPIRE, TTL, PERSIST
- ✅ 扩展设置：SETEX, PSETEX
- ✅ 连接管理

## 技术栈

- Java 8+
- Netty 4.1.x (网络框架)
- Maven (构建工具)
- SLF4J + Logback (日志)

## 项目结构

```
z-cache/
├── pom.xml                      # 根POM
├── z-cache-core/                # 核心模块
│   ├── pom.xml
│   └── src/
│       └── main/java/com/zifang/z/cache/core/
│           ├── protocol/        # RESP协议实现
│           │   ├── RespType.java
│           │   ├── RespDecoder.java
│           │   ├── RespEncoder.java
│           │   ├── RespArray.java
│           │   ├── RespBulkString.java
│           │   ├── RespError.java
│           │   ├── RespInteger.java
│           │   └── RespSimpleString.java
│           ├── server/        # 网络服务
│           │   ├── RedisServer.java
│           │   ├── RedisServerHandler.java
│           │   └── ZCacheServerMain.java
│           ├── storage/       # 存储引擎
│           │   └── MemoryStore.java
│           └── command/       # 命令处理
│               └── CommandHandler.java
└── doc/
    └── 架构设计.md            # 完整架构设计文档
```

## 编译运行

### 1. 编译项目

```bash
mvn clean package -DskipTests
```

### 2. 运行服务器

```bash
java -jar z-cache-core/target/z-cache-core-1.0-SNAPSHOT.jar
```

或使用默认端口 6379：

```bash
cd z-cache-core/target
java -cp "z-cache-core-1.0-SNAPSHOT.jar:lib/*" com.zifang.z.cache.core.server.ZCacheServerMain
```

### 3. 使用 redis-cli 连接

```bash
# 连接服务器
redis-cli -p 6379

# 测试命令
127.0.0.1:6379> PING
PONG

127.0.0.1:6379> SET name z-cache
OK

127.0.0.1:6379> GET name
"z-cache"

127.0.0.1:6379> DEL name
(integer) 1

127.0.0.1:6379> EXISTS name
(integer) 0

127.0.0.1:6379> SETEX temp 60 "hello"
OK

127.0.0.1:6379> TTL temp
(integer) 59
```

## 支持的命令

### 连接命令

| 命令             | 描述    |
|----------------|-------|
| PING [message] | 测试连接  |
| ECHO message   | 回显消息  |
| QUIT           | 关闭连接  |
| SELECT db      | 选择数据库 |

### 字符串命令

| 命令                                     | 描述      |
|----------------------------------------|---------|
| SET key value [EX seconds] [PX ms] [NX | XX]     | 设置键值 |
| GET key                                | 获取键值    |
| DEL key [key ...]                      | 删除键     |
| EXISTS key [key ...]                   | 检查键是否存在 |

### 过期命令

| 命令                      | 描述            |
|-------------------------|---------------|
| EXPIRE key seconds      | 设置过期时间(秒)     |
| TTL key                 | 获取剩余生存时间      |
| PERSIST key             | 移除过期时间        |
| SETEX key seconds value | 设置带过期时间的值     |
| PSETEX key ms value     | 设置带过期时间的值(毫秒) |

### 服务器命令

| 命令      | 描述      |
|---------|---------|
| DBSIZE  | 返回键数量   |
| FLUSHDB | 清空当前数据库 |

## 开发计划

- [x] MVP版本 - 基础Redis协议支持
- [ ] v0.2 - 数据类型扩展 (List, Hash)
- [ ] v0.3 - 持久化 (RDB, AOF)
- [ ] v0.4 - 主从复制
- [ ] v0.5 - 集群支持

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request!

---

**z-cache** - 轻量级、高性能的内存缓存服务器
