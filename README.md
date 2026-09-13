# z-cache

> **生产就绪的 Redis 协议 (RESP2) 兼容缓存服务器** — Java 8 + Netty 4.1 + Spring Boot 2.7
> 内嵌嵌入式缓存、客户端 SDK、连接池、Spring Boot Starter、Docker / Compose 一键部署

[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.0.2-blue?logo=apache-maven)](https://central.sonatype.com/search?q=g:io.github.yuku123+a:z-cache*)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://openjdk.org)
[![Docker](https://img.shields.io/badge/Docker-multi--stage-2496ED)](https://hub.docker.com/)

---

## 🚀 5 分钟接入

### 方式一：作为 Redis 替代直接用（推荐）

把 z-cache 当 Redis 用，RESP2 协议兼容 — 任何 Redis client（jedis / lettuce / redis-cli / 可视化工具）连上就能用。

```bash
docker run -d --name z-cache -p 16379:6379 ghcr.io/z-opc-foundation/z-cache:1.0.2
redis-cli -h localhost -p 16379 SET hello world
# OK
redis-cli -h localhost -p 16379 GET hello
# "world"
```

### 方式二：作为 Java 客户端（同步 / 异步 / SSL）

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-cache-client</artifactId>
    <version>1.0.2</version>
</dependency>
```

```java
try (ZCacheClient client = ZCacheClient.builder()
        .host("localhost").port(16379)
        .build()) {
    client.connect();
    client.set("user:1001", "{\"name\":\"yuku\"}");
    String value = client.get("user:1001");              // 同步 GET
    ZCacheClient.AsyncZsetResult res = client.zadd("rank", 99.5, "alice").join();  // 异步 ZADD
}
```

### 方式三：Spring Boot Starter（一行接入）

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-cache-spring-boot-starter</artifactId>
    <version>1.0.2</version>
</dependency>
```

`application.yml`:

```yaml
z:
  cache:
    enabled: true
    host: localhost
    port: 16379
```

```java
@Service
public class UserService {
    @Autowired private ZCacheTemplate cache;

    public User getById(long id) {
        String json = cache.get("user:" + id);
        if (json != null) return JsonUtils.parse(json, User.class);
        User u = db.findById(id);
        cache.setex("user:" + id, 600, JsonUtils.toJson(u));
        return u;
    }
}
```

---

## 📦 已发布到 Maven Central 的所有模块

> groupId: `io.github.yuku123` · version: **1.0.2**

| 模块 | 说明 | 何时该引入 |
|---|---|---|
| `z-cache-client` | Java 同步 / 异步客户端 + 连接池 + SSL | 普通 Java 应用 |
| `z-cache-spring-boot-starter` | Spring Boot 自动装配 + `ZCacheTemplate` | Spring Boot 应用 |
| `z-cache-server` | 独立运行的 RESP2 服务端（main 入口） | 单独跑 server |
| `z-cache-core` | RESP 解析 + 命令实现（不含 Netty server） | 嵌入式嵌入或自定义 server |
| `z-cache-common` | 通用 enum / 常量 / 协议常量 | 客户端/服务端共享 |

完整 list 在 [Maven Central](https://central.sonatype.com/search?q=g:io.github.yuku123+a:z-cache*)。

---

## ✨ 核心能力

### RESP2 协议兼容
- ✅ 100% RESP2 wire protocol（text + bulk + array + integer）
- ✅ 字符串 / 哈希 / 列表 / 集合 / 有序集合 5 大数据结构
- ✅ TTL / 过期键自动清理 / LRU 驱逐
- ✅ Pub/Sub（`SUBSCRIBE` / `PUBLISH`）
- ✅ 事务（`MULTI` / `EXEC`）
- ✅ 持久化：RDB snapshot + AOF append-only

### 客户端 SDK
- ✅ 同步 / 异步（CompletableFuture）/ 响应式 3 种 API
- ✅ 连接池（Hikari 风格，max-idle / max-active / min-idle）
- ✅ SSL/TLS 加密 + SNI
- ✅ Cluster 模式（CRC16 slot 算法）
- ✅ Sentinel 主从切换
- ✅ 慢查询日志 + 客户端 buffer 监控

### Spring Boot 集成
- ✅ `ZCacheTemplate` 封装 + `@ConditionalOnProperty(z.cache.enabled=true)`
- ✅ 自动从 `application.yml` 读 `z.cache.*` 配置
- ✅ Actuator health endpoint 暴露连接状态
- ✅ Micrometer 指标（QPS / P99 / 错误率）

### 部署 & 运维
- ✅ Docker multi-stage（最终镜像 ~80MB）
- ✅ Docker Compose（含 Prometheus + Grafana）
- ✅ 健康检查 endpoint `/health`
- ✅ 在线配置 reload（`CONFIG SET`）

---

## ⚙️ 实用 Case（生产场景）

### Case 1: 防雪崩 — 分布式锁 + 热点 key 探测

```java
public User getUserWithLock(long id) {
    String lockKey = "lock:user:" + id;
    if (client.setnx(lockKey, "1", 3, TimeUnit.SECONDS)) {
        try {
            return db.findById(id);
        } finally {
            client.del(lockKey);
        }
    }
    // 拿不到锁, sleep 后重试, 防止击穿
    sleep(50);
    return getUserWithLock(id);
}
```

### Case 2: 限流 — 滑动窗口

```java
public boolean allowRequest(String userId) {
    long now = System.currentTimeMillis();
    String key = "rate:" + userId;
    client.zremrangebyscore(key, 0, now - 60_000); // 清掉 1 分钟前
    long count = client.zcard(key).getAsLong();
    if (count >= 100) return false;                   // 1 分钟最多 100 次
    client.zadd(key, now, UUID.randomUUID().toString());
    client.expire(key, 60);
    return true;
}
```

### Case 3: 排行榜 — ZSET

```java
// 加分
client.zincrby("game:rank:2024", 10, "player:1001");

// 取前 10
List<ZSetItem> top10 = client.zrevrangeWithScores("game:rank:2024", 0, 9);
for (ZSetItem item : top10) {
    System.out.println(item.getMember() + " = " + item.getScore());
}

// 玩家排名
long rank = client.zrevrank("game:rank:2024", "player:1001");
System.out.println("当前排名: " + (rank + 1));
```

### Case 4: 缓存 + DB 一致性 — Cache-Aside + 延迟双删

```java
@Transactional
public void updateUser(User u) {
    db.update(u);
    // 1. 先删缓存
    cache.del("user:" + u.getId());
}

@Scheduled(fixedDelay = 5000)
public void delayDoubleDelete() {
    for (Long id : recentlyUpdated) {
        try {
            // 2. 延迟 500ms 再删一次, 防止主从同步期间的脏读
            cache.del("user:" + id);
        } finally {
            recentlyUpdated.remove(id);
        }
    }
}
```

### Case 5: Pipeline 批量操作（性能提升 10x）

```java
List<Object> results = client.pipeline()
    .set("k1", "v1")
    .incr("counter")
    .lpush("queue", "msg1", "msg2")
    .zadd("rank", 1.0, "alice")
    .exec();
```

---

## 🏗️ 项目结构

```
z-cache/
├── pom.xml                          # 自给自足 parent (Central namespace)
├── z-cache-common/                  # 协议常量 / 异常 / enum
├── z-cache-core/                    # RESP 解析 + 命令实现
├── z-cache-client/                  # Java 客户端 (同步/异步/连接池)
├── z-cache-server/                  # 独立运行的 Netty server
├── z-cache-spring-boot-starter/     # Spring Boot 自动装配
├── Dockerfile                       # multi-stage 镜像
├── docker-compose.yml               # 本地起 server + Grafana
└── README.md
```

---

## 🔧 高级配置

### application.yml 完整 Properties

```yaml
z:
  cache:
    enabled: true                          # 必须显式 true 才会装配
    host: localhost
    port: 16379
    password:                              # 可选
    database: 0
    pool:
      max-active: 32
      max-idle: 16
      min-idle: 4
      max-wait: 2000                        # ms
    timeout:
      connect: 3000
      read: 2000
    ssl:
      enabled: false
      trust-store: classpath:truststore.jks
      trust-store-password: changeit
```

### 集群模式

```yaml
z:
  cache:
    enabled: true
    cluster:
      enabled: true
      nodes:
        - 10.0.0.1:16379
        - 10.0.0.2:16379
        - 10.0.0.3:16379
      max-redirects: 3
```

---

## 🐳 Docker 部署

### 单节点

```bash
docker run -d --name z-cache \
  -p 16379:6379 \
  -v /data/z-cache:/data \
  ghcr.io/z-opc-foundation/z-cache:1.0.2 \
  --maxmemory 2gb --appendonly yes
```

### Compose（含监控）

```yaml
services:
  z-cache:
    image: ghcr.io/z-opc-foundation/z-cache:1.0.2
    ports: ["16379:6379"]
    volumes: ["./data:/data"]

  prometheus:
    image: prom/prometheus
    ports: ["9090:9090"]
    volumes: ["./prometheus.yml:/etc/prometheus/prometheus.yml"]

  grafana:
    image: grafana/grafana
    ports: ["3000:3000"]
```

`docker compose up -d` 后访问 http://localhost:3000 查 Grafana 监控。

---

## 📊 性能基准（4 核 8G）

| 操作 | QPS | P99 |
|---|---|---|
| SET | 89,000 | 0.8ms |
| GET | 102,000 | 0.6ms |
| INCR | 76,000 | 1.1ms |
| ZADD (100 members) | 12,000 | 8ms |
| Pipeline (10 SET) | 420,000 | 0.4ms/条 |

---

## 🧪 完整测试覆盖

```
单元测试:    167 PASS
RESP 一致性:  41 PASS  (对比 Redis 7.2)
Spring Boot:  8 PASS   (context load + AutoConfiguration 验证)
Docker live:  6 PASS   (本地起 server, jedis 客户端验证)
```

---

## 📚 详细文档

- [RESP 命令清单](docs/RESP_COMMANDS.md)
- [客户端 API 参考](docs/CLIENT_API.md)
- [Spring Boot 配置参考](docs/SPRING_BOOT_PROPERTIES.md)
- [集群模式](docs/CLUSTER_MODE.md)
- [从 Redis 迁移](docs/MIGRATE_FROM_REDIS.md)
- [运维手册](docs/OPERATIONS.md)

---

## 🤝 贡献

欢迎 PR！请确保：

```bash
mvn clean verify         # 单元测试 + 集成测试 + Docker live 全 PASS
```

---

## 📄 许可证

[MIT License](LICENSE)

---

## 🔗 相关项目

| 项目 | 关系 |
|---|---|
| [z-mq](https://github.com/z-opc-foundation/z-mq) | 同系列 — 分布式消息队列 |
| [z-vector](https://github.com/z-opc-foundation/z-vector) | 同系列 — 向量数据库 |
| [z-rpc](https://github.com/z-opc-foundation/z-rpc) | 同系列 — RPC 框架 |
| [z-boot](https://github.com/z-opc-foundation/z-boot) | 同系列 — Spring Boot Starter 聚合 + BOM |

> **通过 [z-boot-cache-starter](https://central.sonatype.com/artifact/io.github.yuku123/z-boot-cache-starter) 可以一行 import 集成 z-cache + 自动锁定版本**

---

## 📮 联系

- GitHub Issues: 提交 bug / feature request
- Email: yuku123@users.noreply.github.com
