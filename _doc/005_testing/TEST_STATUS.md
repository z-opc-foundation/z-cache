# z-cache 全模块测试状态报告

## 总览

| 指标 | 数值 |
|------|------|
| 测试总数 | **448** |
| 通过 | **436** |
| 失败 | **0** |
| 跳过(需真实服务器) | **12** |
| 测试类总数 | **27** |
| 创建日期 | 2026-04-05 |
| 最后更新 | 2026-09-09 |

## 各模块测试统计

### z-cache-common (96 tests, 7 test classes)

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|---------|
| `RespTypeTest` | 7 | 枚举值、前缀匹配、fromPrefix |
| `RespSimpleStringTest` | 11 | 工厂方法、空值、CR/LF拒绝、equals/hashCode |
| `RespIntegerTest` | 10 | 工厂方法、边界值、intValue截断、equals/hashCode |
| `RespBulkStringTest` | 18 | 工厂方法、空值、null、equals/hashCode、防御性拷贝、Unicode |
| `RespArrayTest` | 25 | 工厂方法、null/empty单例、command工厂、防御性拷贝、equals/hashCode、toStringArray |
| `RespErrorTest` | 14 | 工厂方法、错误类型解析、单例、equals/hashCode |
| `CacheExceptionTest` | 11 | 3种构造器、继承关系、嵌套cause、堆栈跟踪、suppression |

### z-cache-core (232 tests, 8 test classes)

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|---------|
| `MemoryStoreTest` | 39 | Set/Get/GetDefensiveCopy、Delete多键、Exists、Setex/Psetex、TTL/PERSIST、DBSIZE、统计、并发读写、容量上限 + LRU 淘汰 |
| `CommandHandlerJunit5Test` | 56 | SET选项(NX/XX/EX/PX)、SETEX/PSETEX、过期逻辑、参数数量验证、大小写不敏感、AUTH/INFO 行为 |
| `RespEncoderTest` | 21 | SimpleString/Error/Integer/BulkString/Array编码、null BulkString/Array、二进制数据、多消息、错误类型 |
| `RespDecoderTest` | 24 | SimpleString/Error/Integer/BulkString/Array解码、null、错误类型、部分帧、InvalidInteger、UnknownType |
| `RedisServerHandlerTest` | 35 | 端到端命令处理(通过CommandHandler直连)、SET/GET/DEL/EXISTS/EXPIRE/TTL/PERSIST/INCR/DECR、全工作流 |
| `StringZCacheTest` | 37 | 嵌入式字符串缓存：基本操作、TTL/Expire/Persist、NX语义、Delete/Exists、Size/Clear/Flush、统计、Builder、Close生命周期、并发读写 |
| `ZCacheGenericTest` | 15 | 泛型缓存：Integer/自定义对象/ArrayList存取、TTL+泛型、NX+泛型、Builder+配置、统计、Close、快捷方法 |
| `ZCacheCodecTest` | 5 | RESP 编解码与 ZCacheCodec 序列化集成 |

### 嵌入式内存缓存 (新增)

**不依赖服务器的嵌入式缓存模块**，位于 `z-cache-core/src/main/java/com/zifang/z/cache/core/embedded/`：

| 类 | 说明 |
|-----|------|
| `ZCache<K,V>` | 泛型缓存，支持任意 Serializable 类型键值对、TTL、NX、统计、后台清理 |
| `StringZCache` | 字符串缓存特化版，更简洁的 API，包装 ZCache |

**用法示例：**
```java
// 快捷创建
StringZCache cache = ZCache.newStringCache();

// 或自定义配置
StringZCache cache = StringZCache.builder()
        .maxSize(10000)
        .cleanupIntervalSec(60)  // 0 = 禁用后台清理
        .build();

// 操作
cache.set("key", "value");
cache.set("token", "abc123", 30, TimeUnit.MINUTES);
String val = cache.get("key");          // "value"
boolean hit = cache.exists("key");       // true
long remaining = cache.ttl("token");    // 剩余秒数
cache.delete("key");

// 泛型用法
ZCache<String, User> userCache = ZCache.<String, User>newBuilder()
        .maxSize(5000)
        .build();
userCache.set("user:1", new User("张三", 25), 60, TimeUnit.SECONDS);

// 关闭
cache.close();
```

### z-cache-client (118 tests, 10 test classes)

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|---------|
| `ConnectionStateTest` | 13 | 7个枚举值、ordinal、name、compareTo、状态转换 |
| `ZCacheClientConfigTest` | 9 | 默认构造、带参构造、Getter/Setter、Fluent API、SSL 配置 |
| `ZCacheClientExceptionTest` | 10 | 3种构造器、继承关系、嵌套cause、null/空消息、堆栈 |
| `ZCacheConnectionTest` | 13 | 构造器null校验、状态管理、isConnected/isClosed、超时、双重关闭、多连接 |
| `ZCacheClientTest` | 8 | 构造器null校验、连接状态、close幂等 |
| `ZCachePoolTest` | 22 | 构造器校验(含negative)、借用/归还、池复用、关闭、统计、并发(8线程×20操作)、null归还 |
| `PooledClientTest` | 7 | 构造器null校验、getClient、close幂等、多客户端 |
| `ClientRespEncoderTest` | 12 | SimpleString/Error/Integer/BulkString/Array编码、null/empty、边界值 |
| `ClientRespDecoderTest` | 12 | SimpleString/Error/Integer/BulkString/Array解码、null/empty、边界值 |
| `ZCacheClientIntegrationTest` | 12 | ⚠️ 跳过(需真实服务器): PING/SET/GET/DEL/EXISTS/EXPIRE/INCR/DECR/并发/FLUSHDB |

### z-cache-spring-boot-starter (2 tests, 1 test class)

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|---------|
| `ZCacheAutoConfigurationTest` | 2 | 禁用时不连接服务（`z.cache.enabled=false` 不创建 `ZCacheClient`）；属性绑定到 `ZCacheProperties` |

### z-cache-server (0 tests)

`Main.java` 仅为占位启动类,不需要单独测试。

## Bug 修复记录 (本次测试中发现并修复)

### Bug #1: ClientRespDecoder.decodeArray NPE
**文件**: `z-cache-client/.../ClientRespDecoder.java`
**根因**: decodeArray 递归调用 decodeType → decodeArray, 内部的 resetDecoder() 清空了外层的 arrayElements 字段。
**修复**: 重写 decodeArray 为独立循环, 不再递归调用 decodeType, 改为直接读取类型字节并分发。

### Bug #2: MemoryStore 未防御性拷贝
**文件**: `z-cache-core/.../MemoryStore.java`
**根因**: set/setex/psetex 方法直接存储传入的 byte[] 引用, 调用者修改数组会影响已存储的值。
**修复**: 在所有写入方法中添加 `value.clone()`。

### Bug #3: MemoryStore.get null data NPE
**文件**: `z-cache-core/.../MemoryStore.java`
**根因**: get() 无条件调用 `wrapper.data.clone()`, 当 value 为 null 时 NPE。
**修复**: 在调用 clone() 前检查 `wrapper.data == null`。

### Bug #4: ZCachePool activeCount 泄漏
**文件**: `z-cache-client/.../pool/ZCachePool.java`
**根因**: borrowClient 从池中复用 PooledClient 时返回同一实例, close 设置 `returned=true` 后再次 borrow 返回同一实例但 close 成为 no-op, 导致 activeCount 只增不减。
**修复**: borrowClient 返回新的 PooledClient 包装(复用同一底层 ZCacheClient), 确保每次 close 都有效调用 returnClient。

### Bug #5: ZCachePool activeCount 语义错误
**文件**: `z-cache-client/.../pool/ZCachePool.java`
**根因**: activeCount 仅在创建新客户端时 increment, 从池中复用时不 increment, 但 close 时总会 decrement, 导致 activeCount 与实际 in-use 数量不匹配。
**修复**: borrowClient 所有路径(复用、创建、等待后获取)都 increment, returnClient 统一 decrement。

### Bug #6: ZCachePool returnClient(null) NPE
**文件**: `z-cache-client/.../pool/ZCachePool.java`
**根因**: returnClient 未检查 client 参数, 直接调用 `client.getClient().close()`。
**修复**: 在方法入口添加 null 检查。

### Bug #7: 构造函数 null 参数校验缺失
**文件**: `ZCacheClient.java`, `ZCacheConnection.java`, `ZCachePool.java`, `PooledClient.java`
**根因**: 所有主要类的构造函数未检查 null 参数, 测试期望 NPE/ZCacheClientException。
**修复**: 在构造函数入口添加 null 检查。

## 运行测试

```bash
# 运行全部测试(排除集成测试)
mvn test -Dsurefire.failIfNoSpecifiedTests=false

# 运行单个模块
mvn test -pl z-cache-common
mvn test -pl z-cache-core
mvn test -pl z-cache-client

# 运行特定测试类
mvn test -pl z-cache-client -Dtest=ZCachePoolTest

# 运行集成测试(需先启动 z-cache 服务器)
mvn test -pl z-cache-client -Dtest=ZCacheClientIntegrationTest
```
