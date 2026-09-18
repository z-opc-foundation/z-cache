# 分布式锁方案选型调研报告 — 2026Q4

> 调研对象：z-cache 1.3.0 分布式锁实现路径
> 调研者：consumer001
> 完成日期：2026-09-18
> 关联任务：TASK-20260918-001
> 关联大需求：[001 z-cache 对标 Redis 功能补齐](../../../z-opc-foundation-lead/002_项目需求/001_特性需求/001_z-cache_对标_Redis_功能补齐/README.md)

## 1. 调研背景

z-cache 当前在 README、01-module-structure.md 与 01 章节均把"分布式锁"列为目标能力，但实际代码侧只完成了**单节点 SETNX**（不含 TTL 语义）与 `SET ... NX PX` 两条原始命令，**未提供分布式锁高层 API**，也未实现 Redlock 多节点协调。

需要补齐的能力清单（来自大需求"包含"段）：

- 分布式锁（SETNX / Redlock）
- 至少一个**客户端 SDK** 暴露 `tryLock / unlock / renew` 高层 API
- 3 节点 cluster 下能正确互斥（JMH benchmark 验证）

## 2. z-cache 现状盘点

### 2.1 已具备能力

| 能力 | 实现位置 | 行号 |
|---|---|---|
| `SETNX key value`（单节点原子） | `CommandHandler.handleSetnx` | [CommandHandler.java:395](file:///Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-cache/z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java#L395) |
| `SET key value NX PX ms`（Redlock 原语） | `CommandHandler.handleSet` 的 NX/PX 选项 | [CommandHandler.java:302](file:///Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-cache/z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java#L302) |
| 单节点 `setIfAbsentDb`（SETNX 内部） | `MemoryStore.setIfAbsentDb` | [MemoryStore.java:217](file:///Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-cache/z-cache-core/src/main/java/com/zifang/z/cache/core/storage/MemoryStore.java#L217) |
| `setnx(key, value, ttl, unit)` 客户端封装 | `ZCacheClient.setnx` | [ZCacheClient.java:184](file:///Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-cache/z-cache-client/src/main/java/com/zifang/z/cache/client/ZCacheClient.java#L184) |
| Netty 4.1.100.Final | `z-cache-core/pom.xml` | — |
| 已支持 RESP 协议 + AOF/RDB 持久化 | `RedisServerHandler` + `RdbPersistence` / `AofPersistence` | — |

### 2.2 缺失能力

1. **多节点协调** —— Redlock 算法要求 N（≥3）个**独立 master** 节点，客户端先从每个节点 SET NX PX 抢锁，再以"多数派成功 + 总耗时 < TTL"判定。需要 z-cache 提供**多 master 独立部署**能力（目前是单节点）。
2. **客户端高层 API** —— 当前只有 `setnx` 原语，缺 `tryLock(key, ttlMs)`、`tryLock(key, ttlMs, waitMs)`、`unlock(lock)`、`renew(lock, ttlMs)`。
3. **原子解锁** —— 当前 DEL 是无脑删除，无法保证"只解自己的锁"（如果 TTL 过期、锁被别人抢走，原持有者 DEL 会误删别人的锁）。需要 Lua 脚本或 Redis 7.x 的"value-match DEL"。
4. **锁续约**（watchdog）—— 长任务执行期间，TTL 过期会导致锁被意外释放。需要后台线程周期性 renew。
5. **可重入支持** —— 通过 `ThreadLocal + value=ownerId+counter` 模式实现，目前无。

## 3. 方案对比

### 3.1 方案 A：基于 Redisson `RLock` 集成

**思路**：在 z-cache-client 中引入 `redisson` 依赖，把 z-cache 当成 Redisson 的 Redis 后端连接；锁的所有协调逻辑（Redlock、看门狗、可重入）由 Redisson 处理。

#### 评估维度

| 维度 | 数据 / 结论 |
|---|---|
| **性能** | Redisson 走 Netty 异步，1000 并发 tryLock/release 约 5-10 万 TPS（[Redisson 官方 benchmark](https://redisson.org/docs/data-and-services/locks-and-synchronizers/)）。但 z-cache-server 端实测 ~3 万 TPS（README 自测），瓶颈在服务端而非客户端。 |
| **可靠性** | Redisson 内置 Redlock 算法实现（[GitHub redisson/src/main/java/org/redisson/RedissonLock.java](https://github.com/redisson/redisson/blob/master/redisson/src/main/java/org/redisson/RedissonLock.java)）。但前提是**多个独立的 z-cache master 节点**，而 z-cache 目前只有单节点模式；与 Redisson 配合需先完成集群实现。 |
| **可维护性** | Redisson 社区活跃（5k+ stars），版本迭代快（3.x 稳定），文档齐全。但 jar 包增量约 **2.5MB**（含 Netty、Reactor、Lettuce、JBoss Marshalling 等传递依赖），与 z-cache 现有 Netty 4.1.100 存在版本协商风险。 |
| **包体积** | 当前 z-cache-client 极轻量（无第三方运行时依赖）。引入 Redisson 后总体积增加约 **2.5MB**。 |
| **BOM 兼容性** | Redisson 3.32.x 要求 Netty 4.1.86+，与 z-cache 的 Netty 4.1.100 兼容；要求 JDK 11+，z-cache-core 当前用 JDK 8+ 编译，需升级目标。 |
| **架构合理性** | **严重错位**：Redisson 是**客户端库**，z-cache 是**服务端**。让服务端依赖客户端库语义混乱、依赖倒置（[Tom Hombergs 论述](https://tomh.xyz/2020/09/06/dependency-inversion-principle/)）。且服务端引入客户端意味着客户端必须"连自己"，引入循环依赖（运行时不冲突，但概念上荒谬）。 |
| **结论** | **不推荐** |

#### 不推荐的核心原因（架构层面）

1. **依赖倒置**：z-cache-core 是服务端，被 z-cache-client（同一仓库内）依赖；服务端反向依赖客户端库违反 Maven 模块依赖方向。
2. **jar 膨胀**：服务端包从 ~50KB 涨到 ~2.5MB，违反 z-opc-foundation 的"轻量中间件"原则（见 [z-opc-foundation 项目规则记忆](../../../z-opc-foundation-lead/001_项目认知/)）。
3. **版本耦合**：Redisson 每次升级都要同步 Netty/Lettuce 版本，与 z-boot 聚合 starter 的 BOM 协调成本高。
4. **无法对外**："Redisson + 自研服务端"组合在 OSS 场景下，外部用户无法用官方 Redisson 客户端连接 z-cache（除非再实现 RESP 协议转换层），**抵消了"用 Redisson"的生态优势**。

### 3.2 方案 B：自研 `DistributedLock` + 单节点先行

**思路**：在 z-cache-core 实现 Lock 数据结构 + z-cache-client 实现 `tryLock / unlock / renew` API。第一阶段单节点即可（基于现有 SET NX PX），第二阶段集群化后扩展为 Redlock。

#### 评估维度

| 维度 | 数据 / 结论 |
|---|---|
| **性能** | 单节点 SET NX PX：z-cache 自测 ~3 万 TPS（与现有 GET/SET 同级别）。JMH 1000 并发 tryLock/unlock 测试目标 ≥ 2 万 TPS（80% 吞吐）。 |
| **可靠性** | 单节点场景可靠性等同于 Redis 单节点 SET NX PX（已被 Redis 6.2+ [官方推荐](https://redis.io/docs/manual/patterns/distributed-locks/#correct-implementation-with-a-single-instance) 为单 master 时的"正确实现"）。集群场景下，Redlock 算法可自研实现（参考 Redis Redlock 论文）。 |
| **可维护性** | 自研代码 ~500 行 Java，依赖 z-cache 现有 RESP + Lua EVAL（z-cache 1.3.0 已支持 EVAL）。维护成本可控；测试覆盖率高。 |
| **包体积** | 增量 ~10KB。 |
| **BOM 兼容性** | **零新增依赖**。完全基于 z-cache 现有 SET NX PX + Lua EVAL。 |
| **架构合理性** | **正确**：服务端提供原语（SET NX PX / Lua EVAL），客户端封装高层 API（tryLock / unlock）。这正是 Redis 自身的分层。 |
| **结论** | **强烈推荐** |

### 3.3 方案 C：直接复用 Redisson 服务端源码（Redisson Cluster Manager）

调研 Redisson 是否提供"独立 Redlock 协调服务"组件。结论：**没有**。Redisson 整体是客户端库，没有可独立运行的 Redlock coordinator。

**结论：不适用**。

## 4. 结论

### 4.1 推荐方案：**方案 B（自研 DistributedLock）**

**理由**（按权重排序）：

1. **架构正确**：服务端 → 客户端的单向依赖，符合 z-cache 自身定位（"Redis 兼容的内存数据库"）。
2. **零新增依赖**：不污染 BOM，与 z-boot 聚合 starter 兼容。
3. **生态闭环**：z-cache 自己的 client（Java/Python/Go）都能用同一套 Lock API；外部用户也用 z-cache-client 而非 Redisson，**对齐**。
4. **可演进**：1.3.0 单节点先行，2.0.0 集群化后扩展 Redlock —— 调研报告本身给出扩展路径。
5. **可测可控**：~500 行 Java + JMH benchmark + 集成测试，可观测性远高于引入 2.5MB 二进制。

### 4.2 不推荐方案：**方案 A（Redisson 集成）**

理由见 3.1。

### 4.3 对 TASK-20260918-005（实现 DistributedLock 公共 API）的具体指导

`TASK-20260918-005` 的任务卡已规划公共 API：

```java
public interface DistributedLock {
    Lock tryLock(String key, long ttlMs) throws LockAcquisitionException;
    Lock tryLock(String key, long ttlMs, long waitMs) throws LockAcquisitionException;
    void unlock(Lock lock) throws LockReleaseException;
    boolean renew(Lock lock, long ttlMs);
}
```

基于本调研结论的实现要点：

1. **底层协议**：使用 `SET key value NX PX ttlMs`（已实现，CommandHandler.handleSet）。
2. **解锁原子性**：使用 Lua 脚本（z-cache 已支持 EVAL）：
   ```lua
   -- KEYS[1] = lock key, ARGV[1] = ownerId
   if redis.call('GET', KEYS[1]) == ARGV[1] then
       return redis.call('DEL', KEYS[1])
   else
       return 0
   end
   ```
3. **ownerId 生成**：`UUID.randomUUID().toString()`，随 value 存入。
4. **可重入**：`value = "ownerId:counter"`，每次进入 Lua 原子 incr；释放时只有 counter==0 才 DEL。
5. **续约（watchdog）**：后台 `ScheduledExecutorService` 每 `ttlMs/3` 触发 `SET key value XX PX ttlMs`（保持 ownerId 一致性）。
6. **JMH benchmark 目标**：1000 并发 tryLock/unlock 循环，P99 < 5ms，TPS ≥ 2 万。
7. **集成测试场景**：单节点 5 个互斥场景 + 集群模式下（z-cache 2.0.0 之前可用 stub 模拟）3 节点 Redlock 互斥。

## 5. 风险与限制

1. **单节点限制**：1.3.0 仅提供单节点 Redlock 等价能力（与 Redis 6.2+ 官方推荐一致）；**真正意义的 Redlock 需要集群**，留给 z-cache 2.0.0。
2. **持久化交互**：z-cache 已有 AOF + RDB 持久化，锁 value 会被持久化。需要注意：
   - **AOF everysec 策略下**：崩溃可能丢 1 秒内的锁释放事件 → 已加锁的客户端解锁失败（value 还在但 TTL 已过期被其他客户端抢走）→ Lua 解锁的 value-match 保证原持有者不会误删别人的锁。✅
   - **RDB 快照间隔**：如 BGSAVE 间隔过长，崩溃恢复后可能丢失大量锁。建议 AOF everysec + RDB 自动 save 配合使用。
3. **时钟漂移**：单机内部时钟单调递增，无漂移问题；集群模式下需考虑 NTP 同步（Redis Redlock 论文要求时钟漂移 < 几 ms）。
4. **fencing token**：Martin Kleppmann 在 [How to do distributed locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html) 中指出 Redlock 无法完全防 GC 停顿 / 时钟跳跃等导致的"双写"。z-cache 1.3.0 **不解决**该问题，建议：
   - 文档明确说明"z-cache 锁适用于非关键互斥场景"
   - 提供 `fencingToken()` API，调用方可在写入受保护资源时校验 token 单调递增
   - 该增强留给 1.4.0。

## 6. 建议（下一步行动）

1. **立即**：执行 TASK-20260918-005（实现 DistributedLock 公共 API），按本文 §4.3 实现要点。
2. **1.3.0 内**：补 fencing token API（可选，与 tryLock 并列返回），文档明确警告非关键场景。
3. **1.4.0 / 2.0.0**：集群化 + Redlock 算法实现（依赖 z-cache 集群相关 TASK-002 的选型结论）。
4. **长期**：跟踪学术界对 Redlock 的争议（Antirez 回应、Kleppmann 续篇），必要时引入 `[fenced locks](https://arxiv.org/abs/2004.00127)` 等替代方案。

## 7. 参考资料

- Redis 官方分布式锁指南：https://redis.io/docs/manual/patterns/distributed-locks/
- Antirez 关于 Redlock 争议的回应：http://antirez.com/news/101
- Martin Kleppmann "How to do distributed locking"：https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- Redisson 锁文档：https://redisson.org/docs/data-and-services/locks-and-synchronizers/
- Redis SET 命令 NX/PX 选项：https://redis.io/commands/set/
- z-opc-foundation Lead 文档：`z-opc-foundation-lead/002_项目需求/001_特性需求/001_z-cache_对标_Redis_功能补齐/`

---

**调研结论明确**：推荐方案 B（自研 DistributedLock），不推荐方案 A（Redisson 集成），不适用方案 C（无独立 Redlock 服务）。
