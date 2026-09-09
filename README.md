# z-cache

> 一个生产就绪的兼容 Redis 协议（RESP2）的内存缓存服务器：Java 8 + Netty 4.1 + Spring Boot 2.7，
> 提供 Maven 多模块、Java 客户端、连接池、Spring Boot Starter、Docker / Docker Compose 部署和健康检查。

![Maven](https://img.shields.io/badge/Maven-3.6+-blue)
![Java](https://img.shields.io/badge/Java-8-orange)
![Docker](https://img.shields.io/badge/Docker-multi--stage-2496ED)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.x-6DB33F)
![License](https://img.shields.io/badge/License-MIT-green)

---

## 目录

- [项目简介](#项目简介)
- [核心能力](#核心能力)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
  - [嵌入式缓存（JVM 内）））](#嵌入式缓存jvm-内)
  - [Java 客户端（同步 / 异步 / SSL）](#java-客户端同步--异步--ssl)
  - [Spring Boot Starter](#spring-boot-starter)
- [Docker 部署](#docker-部署)
- [RESP 命令清单](#resp-命令清单)
- [构建与验证](#构建与验证)
- [目录与文件](#目录与文件)
- [开发路线](#开发路线)
- [许可证](#许可证)

---

## 项目简介

`z-cache` 是一个面向中小团队和高密度调用场景的内存缓存服务，对外完全遵循 RESP2 协议，可以被
`redis-cli`、Jedis、Lettuce、Redisson 等任意 Redis 客户端直接访问。

它同时提供：

- **嵌入式缓存 API** —— 直接在 JVM 进程里以 `ZCache<K,V>` / `StringZCache` 形式使用，零网络栈。
- **Java 客户端** —— 支持同步 / 异步 / SSL / 连接池。
- **Spring Boot Starter** —— `z.cache.enabled=true` 一行启用，引入即用 `ZCacheTemplate`。
- **Docker 镜像 + Compose** —— 多阶段构建、非 root 用户、健康检查、一键部署失败回滚。

z-cache 不替代 Redis 作为唯一持久化存储，它提供的是「轻量、可控、易集成」的本地缓存能力。

## 核心能力

- ✅ **RESP2 / Redis 客户端完全兼容**：`PING` `ECHO` `AUTH` `SELECT` `QUIT` 全部支持。
- ✅ **String / 批量 / 原子数值命令**：`SET / GET / DEL / MGET / MSET / SETNX / GETSET / APPEND / STRLEN / INCR / DECR / INCRBY / DECRBY`。
- ✅ **TTL / 毫秒 TTL**：`EXPIRE / PEXPIRE / TTL / PTTL / PERSIST / SETEX / PSETEX`，惰性过期 + 后台清理。
- ✅ **运维命令**：`INFO / TYPE / KEYS / DBSIZE / FLUSHDB / FLUSHALL`。
- ✅ **容量限制 + LRU 淘汰**：`--max-entries` 启动参数；`INFO` 输出命中率与淘汰统计。
- ✅ **可选 AUTH 认证**：直接密码 `--password` 或密码文件 `--password-file`；推荐生产使用密码文件。
- ✅ **Java 同步客户端 / 异步客户端 / 连接池 / SSL**：FIFO Future 队列，超时关闭连接。
- ✅ **Spring Boot 自动装配**：默认不连接远端，配置 `z.cache.enabled=true` 才建立连接。
- ✅ **嵌入式 API**：`ZCache` / `StringZCache` 在 JVM 内使用，无网络开销。
- ✅ **fat jar 启动**：单个 `z-cache-server-1.0.0-SNAPSHOT.jar` 即可运行。
- ✅ **Docker 多阶段构建**：Maven 编译 → Temurin JRE → 非 root 用户（uid 10001）。
- ✅ **HEALTHCHECK**：`HealthCheck --password-file /app/secrets/password` 调用原生 Socket PING。
- ✅ **部署失败回滚**：先备份旧容器为 `z-cache-old`，新容器健康检查失败自动恢复。

## 技术栈

| 维度       | 选型                                                            |
| ---------- | --------------------------------------------------------------- |
| 编程语言   | Java 8                                                          |
| 网络框架   | Netty 4.1.100.Final                                            |
| 协议       | RESP2（Redis Serialization Protocol v2）                       |
| 构建工具   | Maven 3.6+                                                      |
| 日志       | SLF4J 1.7.36 + Logback 1.2.13                                  |
| 容器基础   | Eclipse Temurin 8 JRE                                          |
| Spring 集成 | Spring Boot 2.7.x (starter)                                     |
| 序列化     | RESP 文本协议（不依赖任何二进制 JSON 库，跨语言可读）          |

## 项目结构

```
z-cache/
├── pom.xml                                 # Maven 聚合工程（parent: com.zifang:z-opc）
├── Dockerfile                              # 多阶段构建镜像
├── docker-compose.yml                      # 单节点部署编排（含 additional_contexts）
├── build.sh                                # build / deploy / logs / stop + 回滚
├── package.sh                              # mvn clean install（供他者依赖）
├── README.md
├── z-cache-common/                         # RESP 数据模型 + 公共异常
│   └── src/main/java/com/zifang/z/cache/common/protocol/
├── z-cache-core/                           # 存储、命令处理、Netty 服务端、嵌入式 API
│   ├── src/main/java/.../storage/MemoryStore.java
│   ├── src/main/java/.../command/CommandHandler.java
│   ├── src/main/java/.../server/RedisServer.java
│   └── src/main/java/.../embedded/         # JVM 内嵌 API（StringZCache / ZCache）
├── z-cache-client/                         # Java 同步 / 异步 / SSL / 连接池客户端
│   └── src/main/java/com/zifang/z/cache/client/
├── z-cache-server/                         # 可执行 fat jar + HealthCheck
│   ├── src/main/java/com/zifang/z/cache/server/Main.java
│   └── src/main/java/com/zifang/z/cache/server/HealthCheck.java
└── z-cache-spring-boot-starter/            # Spring Boot 自动配置 + ZCacheTemplate
    └── src/main/java/com/zifang/z/cache/spring/
```

## 快速开始

### 嵌入式缓存（JVM 内）

无需启动服务端，直接在 JVM 进程内使用：

```java
try (StringZCache cache = ZCache.newStringCache()) {
    cache.set("user:1", "张三", 10, TimeUnit.MINUTES);
    String value = cache.get("user:1");
    cache.del("user:1");
}
```

也可以使用泛型 `ZCache<K, V>`：

```java
ZCache<String, byte[]> binary = ZCache.newCache();
binary.set("blob", new byte[]{1, 2, 3}, 60, TimeUnit.SECONDS);
byte[] bytes = binary.get("blob");
```

### Java 客户端（同步 / 异步 / SSL）

```java
ZCacheClientConfig config = new ZCacheClientConfig()
        .host("localhost")
        .port(6379)
        .password("secret")
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(3));

try (ZCacheClient client = new ZCacheClient(config)) {
    client.connect();
    client.set("name", "z-cache");

    // 同步
    String v = client.get("name");

    // 异步
    CompletableFuture<Object> future = client.sendCommandAsync("INCR", "counter");
    Long counter = (Long) future.get(1, TimeUnit.SECONDS);

    // SSL
    ZCacheClientConfig ssl = new ZCacheClientConfig()
            .host("cache.internal")
            .port(6380)
            .useSsl(true);
}
```

连接池：

```java
ZCachePool pool = new ZCachePool(config, /* poolMaxSize */ 8);
try (PooledClient p = pool.borrow()) {
    p.client().set("k", "v");
    String v = p.client().get("k");
}
pool.close();
```

### Spring Boot Starter

引入依赖：

```xml
<dependency>
    <groupId>com.zifang</groupId>
    <artifactId>z-cache-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

配置 `application.yml`：

```yaml
z:
  cache:
    enabled: true        # 不启用就不会建立任何远程连接
    host: localhost
    port: 6379
    password: ${ZCACHE_PASSWORD:}
    pool-max-size: 8
    connect-timeout: 3s
    read-timeout: 3s
```

业务代码：

```java
@Service
public class UserService {
    @Resource
    private ZCacheTemplate zCache;

    public User load(long id) {
        User cached = zCache.get("user:" + id, User.class);
        if (cached != null) return cached;
        User fresh = repo.findById(id);
        zCache.setex("user:" + id, 300, fresh);
        return fresh;
    }
}
```

未配置 `z.cache.enabled=true` 时 starter 仅注册 `ZCacheProperties`，不会创建 `ZCacheClient`
连接，避免引入 starter 后对老应用造成启动副作用。

## Docker 部署

### 直接 docker buildx 构建

`z-cache` 是 monorepo 子模块，依赖父 POM `com.zifang:z-opc`，Dockerfile 使用
`z-opc-foundation` 作为 build context。

```bash
cd /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-cache
docker buildx build \
  --build-context maven-m2=${HOME}/.m2 \
  -f Dockerfile -t z-cache:latest ..
docker run --rm -p 6379:6379 z-cache:latest
```

> `--build-context maven-m2=$HOME/.m2` 让构建过程直接挂载宿主机的 Maven 本地仓库，
> 避免每次构建都重新下载大量内部依赖（`io.github.yuku123:z-util-*`、
> `com.zifang:z-opc` 等）。
>
> 如果宿主机没有 Maven 仓库或者想完全联网下载，可以省略该参数：
>
> ```bash
> docker buildx build -f Dockerfile -t z-cache:latest ..
> ```

### Docker Compose

```bash
cd /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-cache
HOST_M2=$HOME/.m2 docker compose up -d --build
docker compose ps
docker compose logs -f
```

`docker-compose.yml` 已经设置好：

- `additional_contexts: maven-m2=${HOST_M2:-${HOME}/.m2}`：让 Compose 自动挂载宿主机 m2。
- `HEALTHCHECK`：使用 `com.zifang.z.cache.server.HealthCheck` 调用原生 Socket PING。
- `mem_limit / stop_grace_period`：内存 512 MB，优雅停止 15 秒。
- 端口映射：默认 `6379:6379`，可通过 `ZCACHE_PORT` 覆盖。

### 一键部署脚本（含失败回滚）

```bash
./build.sh deploy
# 或带参数：
ZCACHE_PORT=6380 ZCACHE_MAX_ENTRIES=1000000 ZCACHE_PASSWORD='change-me' ./build.sh deploy

# 查看日志 / 停止
./build.sh logs
./build.sh stop
```

部署流程：

1. `check_docker_status` —— Docker daemon 不可用立即报错。
2. `stop_old_container` —— 把现有 `z-cache` 重命名为 `z-cache-old`（不删）。
3. `start_container` —— 启动新的 `z-cache`。
4. `health_check` —— 60 秒内轮询 `docker inspect .State.Health.Status`。
5. **健康成功** → `cleanup_old_container` 删除 `z-cache-old`。
6. **健康失败** → `restore_old_container` 还原旧容器。

启用 `ZCACHE_PASSWORD` 后，客户端必须在连接配置中设置 `password`，否则服务端返回 `NOAUTH`。
生产环境推荐使用 `ZCACHE_PASSWORD_FILE` 指向 secret 文件：

```bash
echo "change-me" > /run/secrets/zcache-password
ZCACHE_PASSWORD_FILE=/run/secrets/zcache-password ./build.sh deploy
```

## RESP 命令清单

| 类别       | 命令                                                                 |
| ---------- | -------------------------------------------------------------------- |
| 连接       | `PING [message]`、`ECHO message`、`AUTH password`、`SELECT db`、`QUIT` |
| 键值基础   | `SET key value [EX seconds] [PX ms] [NX \| XX]`、`GET key`、`DEL key [key ...]`、`EXISTS key [key ...]`、`TYPE key` |
| 字符串扩展 | `MGET key [key ...]`、`MSET key value [key value ...]`、`SETNX key value`、`GETSET key value`、`APPEND key value`、`STRLEN key` |
| 原子数值   | `INCR key`、`DECR key`、`INCRBY key increment`、`DECRBY key increment` |
| 过期       | `EXPIRE key seconds`、`PEXPIRE key milliseconds`、`TTL key`、`PTTL key`、`PERSIST key`、`SETEX key seconds value`、`PSETEX key milliseconds value` |
| 键空间     | `KEYS pattern`、`DBSIZE`、`FLUSHDB`、`FLUSHALL`                     |
| 运维       | `INFO [section]`、`CONFIG GET max-entries`                          |

`INFO` 输出包含：

```text
# Server
z-cache_version:1.0.0
redis_compatible:resp2

# Stats
keyspace_hits:123
keyspace_misses:7
evicted_keys:0
max_entries:1000000
hit_rate:0.946153
db0_keys:42
```

## 构建与验证

### 1. 全量 Maven 测试

```bash
cd /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-cache
mvn -q clean test
```

### 2. 安装到本地仓库

```bash
mvn -q clean install -DskipTests
```

### 3. 启动可执行 fat jar

```bash
java -jar z-cache-server/target/z-cache-server-1.0.0-SNAPSHOT.jar \
  --host 0.0.0.0 --port 6379 --max-entries 1000000 \
  --password-file /run/secrets/zcache-password
```

### 4. 客户端验证

```bash
redis-cli -p 6379
> AUTH <password>
> PING
PONG
> SET name z-cache
OK
> GET name
"z-cache"
> INFO
```

### 5. 端到端 Docker 部署验证

```bash
docker buildx build --build-context maven-m2=$HOME/.m2 -f Dockerfile -t z-cache:latest ..
docker compose up -d --build
docker ps --filter name=z-cache          # Up N seconds (healthy)
printf '*1\r\n$4\r\nPING\r\n' | nc -w 3 127.0.0.1 6379   # +PONG
docker compose down -v
```

## 目录与文件

| 路径                                      | 说明                                                                 |
| ----------------------------------------- | -------------------------------------------------------------------- |
| `pom.xml`                                | Maven 聚合工程（`com.zifang:z-cache` 5 模块）                       |
| `Dockerfile`                             | 多阶段构建 / Eclipse Temurin 8 / 非 root 用户 / `HEALTHCHECK`      |
| `docker-compose.yml`                     | 容器编排 / `additional_contexts: maven-m2` / 资源限制              |
| `build.sh`                                | `build` / `deploy` / `logs` / `stop` + 失败回滚                     |
| `package.sh`                              | `mvn clean install`，供其他 Maven 项目直接引用                     |
| `z-cache-common/...`                     | RESP 数据模型、异常、RespArray / BulkString / Integer / Error / SimpleString |
| `z-cache-core/...`                       | 存储 / 命令 / 服务端 / 嵌入式 API / `CommandHandlerJunit5Test`      |
| `z-cache-client/...`                     | `ZCacheClient` / `ZCacheConnection` / `ZCachePool` / `PooledClient` / `ClientRespDecoder` |
| `z-cache-server/...`                     | `Main` 启动器 / `HealthCheck` 健康检查                                |
| `z-cache-spring-boot-starter/...`        | `ZCacheProperties` / `ZCacheAutoConfiguration` / `ZCacheTemplate`   |

## 开发路线

- [x] v1.0 MVP —— RESP2 命令、嵌入式缓存、Java 客户端、Spring Boot Starter、Docker 部署。
- [ ] v1.1 —— List / Hash 数据类型。
- [ ] v1.2 —— RDB / AOF 持久化。
- [ ] v1.3 —— 主从复制。
- [ ] v1.4 —— 集群支持。

## 许可证

MIT License.

## 贡献

欢迎提交 Issue 和 Pull Request。

---

**z-cache** —— 轻量、可控、易集成的内存缓存服务器。