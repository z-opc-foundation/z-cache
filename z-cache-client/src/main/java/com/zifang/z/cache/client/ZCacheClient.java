package com.zifang.z.cache.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ZCache客户端主类
 *
 * @author zifang
 * @since 1.0.0
 * 
 * <p>示例用法：
 * <pre>
 * try (ZCacheClient client = new ZCacheClient("localhost", 6379)) {
 *     client.connect();
 *     client.set("key", "value");
 *     String value = client.get("key");
 * }
 * </pre>
 */
public class ZCacheClient implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ZCacheClient.class);

    /**
     * 客户端配置
     */
    private final ZCacheClientConfig config;
    
    /**
     * 底层连接对象
     */
    private final ZCacheConnection connection;
    
    /**
     * 关闭状态标记
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造函数，默认使用配置创建客户端
     */
    public ZCacheClient() {
        this(new ZCacheClientConfig());
    }

    /**
     * 构造函数，使用指定主机和端口创建客户端
     *
     * @param host 主机地址
     * @param port 端口号
     */
    public ZCacheClient(String host, int port) {
        this(new ZCacheClientConfig(host, port));
    }

    /**
     * 构造函数，使用指定配置创建客户端
     *
     * @param config 客户端配置
     * @throws NullPointerException 如果配置为 null
     */
    public ZCacheClient(ZCacheClientConfig config) {
        if (config == null) {
            throw new NullPointerException("config cannot be null");
        }
        this.config = config;
        this.connection = new ZCacheConnection(config);
    }

    /**
     * 将响应对象转换为字符串
     *
     * @param response 响应对象
     * @return 字符串值，如果无法转换则返回 null
     */
    protected static String toString(Object response) {
        if (response == null) { return null; }

        if (response instanceof com.zifang.z.cache.common.protocol.RespBulkString) {
            return ((com.zifang.z.cache.common.protocol.RespBulkString) response).getString();
        }
        if (response instanceof com.zifang.z.cache.common.protocol.RespSimpleString) {
            return ((com.zifang.z.cache.common.protocol.RespSimpleString) response).getValue();
        }
        return response.toString();
    }

    /**
     * 将响应对象转换为 Long 类型
     *
     * @param response 响应对象
     * @return Long 值，如果无法转换则返回 null
     */
    protected static Long toLong(Object response) {
        if (response == null) { return null; }

        if (response instanceof com.zifang.z.cache.common.protocol.RespInteger) {
            return ((com.zifang.z.cache.common.protocol.RespInteger) response).getValue();
        }
        if (response instanceof Number) {
            return ((Number) response).longValue();
        }
        return Long.parseLong(response.toString());
    }

    /**
     * 建立与服务器的连接
     *
     * @throws ZCacheClientException 如果客户端已关闭
     */
    public void connect() {
        ensureNotClosed();
        connection.connect();
    }

    // String operations

    /**
     * 检查客户端是否已连接
     *
     * @return 如果已连接返回 true，否则返回 false
     */
    public boolean isConnected() {
        return connection.isConnected();
    }

    /**
     * 获取客户端配置
     *
     * @return 客户端配置
     */
    public ZCacheClientConfig getConfig() {
        return config;
    }

    /**
     * 获取字符串值
     *
     * @param key 键
     * @return 值，如果键不存在返回 null
     */
    public String get(String key) {
        Object response = sendCommand("GET", key);
        return toString(response);
    }

    /**
     * 设置字符串值
     *
     * @param key   键
     * @param value 值
     * @return OK 表示成功
     */
    public String set(String key, String value) {
        Object response = sendCommand("SET", key, value);
        return toString(response);
    }

    /**
     * 设置字符串值并设置过期时间（秒）
     *
     * @param key     键
     * @param seconds 过期时间（秒）
     * @param value   值
     * @return OK 表示成功
     */
    public String setex(String key, long seconds, String value) {
        Object response = sendCommand("SETEX", key, seconds, value);
        return toString(response);
    }

    /**
     * 仅当键不存在时设置值
     *
     * @param key   键
     * @param value 值
     * @return 1 表示设置成功，0 表示键已存在
     */
    public Long setnx(String key, String value) {
        Object response = sendCommand("SETNX", key, value);
        return toLong(response);
    }

    /**
     * 设置新值并返回旧值
     *
     * @param key   键
     * @param value 新值
     * @return 旧值，如果键不存在返回 null
     */
    public String getSet(String key, String value) {
        Object response = sendCommand("GETSET", key, value);
        return toString(response);
    }

    /**
     * 追加值到字符串
     *
     * @param key   键
     * @param value 要追加的值
     * @return 追加后的字符串长度
     */
    public Long append(String key, String value) {
        Object response = sendCommand("APPEND", key, value);
        return toLong(response);
    }

    /**
     * 获取字符串长度
     *
     * @param key 键
     * @return 字符串长度，如果键不存在返回 0
     */
    public Long strlen(String key) {
        Object response = sendCommand("STRLEN", key);
        return toLong(response);
    }

    /**
     * 将键的整数值加 1
     *
     * @param key 键
     * @return 加 1 后的值
     */
    public Long incr(String key) {
        Object response = sendCommand("INCR", key);
        return toLong(response);
    }

    /**
     * 将键的整数值增加指定增量
     *
     * @param key   键
     * @param delta 增量
     * @return 增加后的值
     */
    public Long incrBy(String key, long delta) {
        Object response = sendCommand("INCRBY", key, delta);
        return toLong(response);
    }

    /**
     * 批量获取字符串值，返回结果顺序与 keys 一致。
     */
    public List<String> mget(String... keys) {
        Object response = sendCommand("MGET", (Object[]) keys);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            values.add(toString(item));
        }
        return values;
    }

    /**
     * 批量设置键值，参数必须以 key/value 成对传入。
     */
    public String mset(String... keyValues) {
        if (keyValues == null || keyValues.length == 0 || keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must contain key/value pairs");
        }
        return toString(sendCommand("MSET", (Object[]) keyValues));
    }

    /**
     * 获取匹配模式下的键名快照。
     */
    public List<String> keys(String pattern) {
        Object response = sendCommand("KEYS", pattern);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) {
            return new ArrayList<>();
        }
        List<String> keys = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            keys.add(toString(item));
        }
        return keys;
    }

    /**
     * 获取服务端运行信息。
     */
    public String info() {
        return toString(sendCommand("INFO"));
    }


    /**
     * 将键的整数值减 1
     *
     * @param key 键
     * @return 减 1 后的值
     */
    public Long decr(String key) {
        Object response = sendCommand("DECR", key);
        return toLong(response);
    }

    /**
     * 将键的整数值减少指定增量
     *
     * @param key   键
     * @param delta 增量
     * @return 减少后的值
     */
    public Long decrBy(String key, long delta) {
        Object response = sendCommand("DECRBY", key, delta);
        return toLong(response);
    }

    /**
     * 删除一个或多个键
     *
     * @param keys 键数组
     * @return 删除的键数量
     */
    public Long del(String... keys) {
        Object response = sendCommand("DEL", (Object[]) keys);
        return toLong(response);
    }

    /**
     * 检查一个或多个键是否存在
     *
     * @param keys 键数组
     * @return 存在的键数量
     */
    public Long exists(String... keys) {
        Object response = sendCommand("EXISTS", (Object[]) keys);
        return toLong(response);
    }

    /**
     * 设置键的过期时间（秒）
     *
     * @param key     键
     * @param seconds 过期时间（秒）
     * @return 1 表示成功，0 表示键不存在
     */
    public Long expire(String key, long seconds) {
        Object response = sendCommand("EXPIRE", key, seconds);
        return toLong(response);
    }

    /**
     * 设置键的过期时间（毫秒）
     *
     * @param key         键
     * @param milliseconds 过期时间（毫秒）
     * @return 1 表示成功，0 表示键不存在
     */
    public Long pexpire(String key, long milliseconds) {
        Object response = sendCommand("PEXPIRE", key, milliseconds);
        return toLong(response);
    }

    /**
     * 获取键的剩余过期时间（秒）
     *
     * @param key 键
     * @return 剩余过期时间（秒），-1 表示无过期时间，-2 表示键不存在
     */
    public Long ttl(String key) {
        Object response = sendCommand("TTL", key);
        return toLong(response);
    }

    // Server operations

    /**
     * 获取键的剩余过期时间（毫秒）
     *
     * @param key 键
     * @return 剩余过期时间（毫秒），-1 表示无过期时间，-2 表示键不存在
     */
    public Long pttl(String key) {
        Object response = sendCommand("PTTL", key);
        return toLong(response);
    }

    /**
     * 移除键的过期时间
     *
     * @param key 键
     * @return 1 表示成功，0 表示键不存在或无过期时间
     */
    public Long persist(String key) {
        Object response = sendCommand("PERSIST", key);
        return toLong(response);
    }

    /**
     * 向服务器发送 PING 命令
     *
     * @return PONG 表示连接正常
     */
    public String ping() {
        Object response = sendCommand("PING");
        return toString(response);
    }

    /**
     * 向服务器发送带消息的 PING 命令
     *
     * @param message 要回显的消息
     * @return 返回的消息
     */
    public String ping(String message) {
        Object response = sendCommand("PING", message);
        return toString(response);
    }

    /**
     * 向服务器发送 ECHO 命令
     *
     * @param message 要回显的消息
     * @return 返回的消息
     */
    public String echo(String message) {
        Object response = sendCommand("ECHO", message);
        return toString(response);
    }

    /**
     * 清空当前数据库
     *
     * @return OK 表示成功
     */
    public String flushdb() {
        Object response = sendCommand("FLUSHDB");
        return toString(response);
    }

    /**
     * 清空所有数据库
     *
     * @return OK 表示成功
     */
    public String flushall() {
        Object response = sendCommand("FLUSHALL");
        return toString(response);
    }

    // Helper methods

    /**
     * 获取数据库中键的数量
     *
     * @return 键的数量
     */
    public Long dbsize() {
        Object response = sendCommand("DBSIZE");
        return toLong(response);
    }

    /**
     * 切换数据库
     *
     * @param database 数据库索引
     * @return OK 表示成功
     */
    public String select(int database) {
        Object response = sendCommand("SELECT", String.valueOf(database));
        return toString(response);
    }

    /**
     * 发送命令到服务器
     *
     * @param command 命令名称
     * @param args    命令参数
     * @return 服务器响应
     * @throws ZCacheClientException 如果客户端已关闭
     */
    public Object sendCommand(String command, Object... args) {
        ensureNotClosed();
        return connection.sendCommand(command, args);
    }

    /**
     * 异步发送原始命令，适合批量流水线和高并发集成场景。
     */
    public CompletableFuture<Object> sendCommandAsync(String command, Object... args) {
        ensureNotClosed();
        return connection.sendCommandAsync(command, args);
    }

    /**
     * 关闭客户端连接
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.debug("Closing ZCacheClient");
            connection.close();
        }
    }

    /**
     * 检查客户端是否已关闭
     *
     * @throws ZCacheClientException 如果客户端已关闭
     */
    private void ensureNotClosed() {
        if (closed.get()) {
            throw new ZCacheClientException("Client is closed");
        }
    }
}
