# z-cache-client 测试模块说明

## 测试覆盖范围

本模块为 z-cache-client 提供了全量的单元测试和集成测试，共包含 **11 个测试类**，**1764 行测试代码**，覆盖 **171+ 个测试方法
**。

## 测试类列表

| 测试类                           | 说明           | 测试方法数 |
|-------------------------------|--------------|-------|
| `ZCacheClientConfigTest`      | 客户端配置测试      | 155+  |
| `ConnectionStateTest`         | 连接状态枚举测试     | 10+   |
| `ZCacheClientExceptionTest`   | 异常类测试        | 12+   |
| `ZCacheConnectionTest`        | 连接管理测试       | 12+   |
| `ZCachePoolTest`              | 连接池测试        | 15+   |
| `PooledClientTestsssss`       | 池化客户端测试      | 10+   |
| `ClientRespEncoderTest`       | RESP 协议编码器测试 | 15+   |
| `ClientRespDecoderTest`       | RESP 协议解码器测试 | 14+   |
| `ZCacheClientTest`            | 主客户端测试       | 14+   |
| `ZCacheClientIntegrationTest` | 集成测试         | 30+   |

## 运行测试

### 运行所有测试

```bash
mvn test -pl z-cache-client
```

### 运行单个测试类

```bash
mvn test -pl z-cache-client -Dtest=ZCacheClientConfigTest
```

### 跳过集成测试（需要启动服务器）

```bash
mvn test -pl z-cache-client -Dtest='!*IntegrationTest'
```

## 测试结构

```
z-cache-client/src/test/java/com/zifang/z/cache/client/
├── ZCacheClientConfigTest.java
├── ConnectionStateTest.java
├── ZCacheClientExceptionTest.java
├── ZCacheConnectionTest.java
├── ZCacheClientTest.java
├── ZCacheClientIntegrationTest.java
├── pool/
│   ├── PooledClientTest.java
│   └── ZCachePoolTest.java
└── protocol/
    ├── ClientRespEncoderTest.java
    └── ClientRespDecoderTest.java
```

## 注意事项

1. **集成测试需要服务器**: `ZCacheClientIntegrationTest` 需要启动 z-cache 服务器才能运行，默认会在 localhost:6379
   尝试连接。如果服务器不可用，这些测试会被跳过。

2. **并发测试**: `ZCachePoolTest` 中包含了并发测试，可能需要较长的运行时间。

3. **RESP 协议测试**: `ClientRespEncoderTest` 和 `ClientRespDecoderTest` 测试了 RESP 协议的编解码功能。

## 创建日期

2026-04-05
