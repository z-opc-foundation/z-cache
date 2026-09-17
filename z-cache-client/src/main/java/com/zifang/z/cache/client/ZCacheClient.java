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

    // ==================== Hash 命令 ====================

    /**
     * 设置 hash 字段值，返回新增字段数量。
     */
    public Long hset(String key, String field, String value) {
        return toLong(sendCommand("HSET", key, field, value));
    }

    /**
     * 获取 hash 字段值。
     */
    public String hget(String key, String field) {
        return toString(sendCommand("HGET", key, field));
    }

    /**
     * 删除 hash 一个或多个字段，返回删除数量。
     */
    public Long hdel(String key, String... fields) {
        Object[] args = new Object[fields.length + 2];
        args[0] = "HDEL"; args[1] = key;
        System.arraycopy(fields, 0, args, 2, fields.length);
        return toLong(sendCommand("HDEL", (Object[]) args));
    }

    /**
     * 检查 hash 字段是否存在。
     */
    public Long hexists(String key, String field) {
        return toLong(sendCommand("HEXISTS", key, field));
    }

    /**
     * 获取 hash 所有字段和值，返回 [field1, value1, field2, value2, ...] 列表。
     */
    public List<String> hgetall(String key) {
        Object response = sendCommand("HGETALL", key);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 获取 hash 所有字段名。
     */
    public List<String> hkeys(String key) {
        Object response = sendCommand("HKEYS", key);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 获取 hash 所有值。
     */
    public List<String> hvals(String key) {
        Object response = sendCommand("HVALS", key);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 批量获取 hash 多个字段值。
     */
    public List<String> hmget(String key, String... fields) {
        Object[] args = new Object[fields.length + 2];
        args[0] = "HMGET"; args[1] = key;
        System.arraycopy(fields, 0, args, 2, fields.length);
        Object response = sendCommand("HMGET", (Object[]) args);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 批量设置 hash 多个字段值。
     */
    public String hmset(String key, String... fieldValues) {
        Object[] args = new Object[fieldValues.length + 2];
        args[0] = "HMSET"; args[1] = key;
        System.arraycopy(fieldValues, 0, args, 2, fieldValues.length);
        return toString(sendCommand("HMSET", (Object[]) args));
    }

    /**
     * hash 字段值加整数。
     */
    public Long hincrby(String key, String field, long increment) {
        return toLong(sendCommand("HINCRBY", key, field, increment));
    }

    /**
     * 获取 hash 字段数量。
     */
    public Long hlen(String key) {
        return toLong(sendCommand("HLEN", key));
    }

    /**
     * 仅当 hash 字段不存在时设置值。
     */
    public Long hsetnx(String key, String field, String value) {
        return toLong(sendCommand("HSETNX", key, field, value));
    }

    // ==================== List 命令 ====================

    /**
     * 从头部插入一个或多个元素。
     */
    public Long lpush(String key, String... values) {
        Object[] args = new Object[values.length + 2];
        args[0] = "LPUSH"; args[1] = key;
        System.arraycopy(values, 0, args, 2, values.length);
        return toLong(sendCommand("LPUSH", (Object[]) args));
    }

    /**
     * 从尾部插入一个或多个元素。
     */
    public Long rpush(String key, String... values) {
        Object[] args = new Object[values.length + 2];
        args[0] = "RPUSH"; args[1] = key;
        System.arraycopy(values, 0, args, 2, values.length);
        return toLong(sendCommand("RPUSH", (Object[]) args));
    }

    /**
     * 从头部弹出元素。
     */
    public String lpop(String key) {
        return toString(sendCommand("LPOP", key));
    }

    /**
     * 从尾部弹出元素。
     */
    public String rpop(String key) {
        return toString(sendCommand("RPOP", key));
    }

    /**
     * 获取列表指定范围的元素。
     */
    public List<String> lrange(String key, long start, long stop) {
        Object response = sendCommand("LRANGE", key, start, stop);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 通过索引获取列表元素。
     */
    public String lindex(String key, long index) {
        return toString(sendCommand("LINDEX", key, index));
    }

    /**
     * 获取列表长度。
     */
    public Long llen(String key) {
        return toLong(sendCommand("LLEN", key));
    }

    /**
     * 设置列表指定索引的值。
     */
    public String lset(String key, long index, String value) {
        return toString(sendCommand("LSET", key, index, value));
    }

    /**
     * 在列表元素前/后插入新元素。
     */
    public Long linsert(String key, String pivot, String value, boolean before) {
        return toLong(sendCommand("LINSERT", key, before ? "BEFORE" : "AFTER", pivot, value));
    }

    /**
     * 移除列表中 count 个值为 value 的元素。
     */
    public Long lrem(String key, long count, String value) {
        return toLong(sendCommand("LREM", key, count, value));
    }

    /**
     * 裁剪列表，只保留指定区间内的元素。
     */
    public String ltrim(String key, long start, long stop) {
        return toString(sendCommand("LTRIM", key, start, stop));
    }

    /**
     * 将 source 列表尾部弹出的元素放入 destination 列表头部。
     */
    public String rpoplpush(String source, String destination) {
        return toString(sendCommand("RPOPLPUSH", source, destination));
    }

    // ==================== Set 命令 ====================

    /**
     * 向集合添加一个或多个成员，返回新增数量。
     */
    public Long sadd(String key, String... members) {
        Object[] args = new Object[members.length + 2];
        args[0] = "SADD"; args[1] = key;
        System.arraycopy(members, 0, args, 2, members.length);
        return toLong(sendCommand("SADD", (Object[]) args));
    }

    /**
     * 移除集合一个或多个成员，返回移除数量。
     */
    public Long srem(String key, String... members) {
        Object[] args = new Object[members.length + 2];
        args[0] = "SREM"; args[1] = key;
        System.arraycopy(members, 0, args, 2, members.length);
        return toLong(sendCommand("SREM", (Object[]) args));
    }

    /**
     * 获取集合所有成员。
     */
    public List<String> smembers(String key) {
        Object response = sendCommand("SMEMBERS", key);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 判断成员是否在集合中。
     */
    public Long sismember(String key, String member) {
        return toLong(sendCommand("SISMEMBER", key, member));
    }

    /**
     * 获取集合成员数量。
     */
    public Long scard(String key) {
        return toLong(sendCommand("SCARD", key));
    }

    /**
     * 随机获取集合中的成员。
     */
    public String srandmember(String key) {
        return toString(sendCommand("SRANDMEMBER", key));
    }

    /**
     * 返回多个集合的交集。
     */
    public List<String> sinter(String... keys) {
        Object[] args = new Object[keys.length + 1];
        args[0] = "SINTER";
        System.arraycopy(keys, 0, args, 1, keys.length);
        Object response = sendCommand("SINTER", (Object[]) args);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 返回多个集合的并集。
     */
    public List<String> sunion(String... keys) {
        Object[] args = new Object[keys.length + 1];
        args[0] = "SUNION";
        System.arraycopy(keys, 0, args, 1, keys.length);
        Object response = sendCommand("SUNION", (Object[]) args);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 返回第一个集合与其他集合的差集。
     */
    public List<String> sdiff(String... keys) {
        Object[] args = new Object[keys.length + 1];
        args[0] = "SDIFF";
        System.arraycopy(keys, 0, args, 1, keys.length);
        Object response = sendCommand("SDIFF", (Object[]) args);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 将成员从 source 移动到 destination。
     */
    public Long smove(String source, String destination, String member) {
        return toLong(sendCommand("SMOVE", source, destination, member));
    }

    // ==================== Sorted Set 命令 ====================

    /**
     * 向有序集合添加一个或多个成员，返回新增数量。
     */
    public Long zadd(String key, double score, String member) {
        return toLong(sendCommand("ZADD", key, score, member));
    }

    /**
     * 移除有序集合一个或多个成员，返回移除数量。
     */
    public Long zrem(String key, String... members) {
        Object[] args = new Object[members.length + 2];
        args[0] = "ZREM"; args[1] = key;
        System.arraycopy(members, 0, args, 2, members.length);
        return toLong(sendCommand("ZREM", (Object[]) args));
    }

    /**
     * 获取有序集合成员的分数。
     */
    public String zscore(String key, String member) {
        return toString(sendCommand("ZSCORE", key, member));
    }

    /**
     * 获取有序集合成员的排名（从 0 开始，分数低排前面）。
     */
    public Long zrank(String key, String member) {
        return toLong(sendCommand("ZRANK", key, member));
    }

    /**
     * 获取有序集合成员的逆排名。
     */
    public Long zrevrank(String key, String member) {
        return toLong(sendCommand("ZREVRANK", key, member));
    }

    /**
     * 获取有序集合成员数量。
     */
    public Long zcard(String key) {
        return toLong(sendCommand("ZCARD", key));
    }

    /**
     * 统计有序集合中分数在 min 和 max 之间的成员数量。
     */
    public Long zcount(String key, double min, double max) {
        return toLong(sendCommand("ZCOUNT", key, min, max));
    }

    /**
     * 获取有序集合中指定排名范围的成员。
     */
    public List<String> zrange(String key, long start, long stop) {
        Object response = sendCommand("ZRANGE", key, start, stop);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 获取有序集合中指定排名范围的成员（逆序）。
     */
    public List<String> zrevrange(String key, long start, long stop) {
        Object response = sendCommand("ZREVRANGE", key, start, stop);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 有序集合成员分数增加指定值。
     */
    public String zincrby(String key, double increment, String member) {
        return toString(sendCommand("ZINCRBY", key, increment, member));
    }

    // ==================== 新增命令 ====================

    /**
     * 随机返回 hash 中的一个字段名。
     */
    public String hrandfield(String key) {
        return toString(sendCommand("HRANDFIELD", key));
    }

    /**
     * 随机返回 hash 中的 count 个字段名。
     */
    public List<String> hrandfield(String key, int count) {
        Object response = sendCommand("HRANDFIELD", key, count);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 随机返回集合中的一个成员并移除。
     */
    public String spop(String key) {
        return toString(sendCommand("SPOP", key));
    }

    /**
     * 随机返回集合中的 count 个成员并移除。
     */
    public List<String> spop(String key, int count) {
        Object response = sendCommand("SPOP", key, count);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 阻塞弹出列表头部元素。
     */
    public List<String> blpop(String key, int timeout) {
        Object response = sendCommand("BLPOP", key, timeout);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    /**
     * 阻塞弹出列表尾部元素。
     */
    public List<String> brpop(String key, int timeout) {
        Object response = sendCommand("BRPOP", key, timeout);
        if (!(response instanceof com.zifang.z.cache.common.protocol.RespArray)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : ((com.zifang.z.cache.common.protocol.RespArray) response).getElements()) {
            result.add(toString(item));
        }
        return result;
    }

    // ==================== 事务命令 ====================

    /**
     * 开启事务。
     */
    public String multi() {
        return toString(sendCommand("MULTI"));
    }

    /**
     * 执行事务。
     */
    public Object exec() {
        return sendCommand("EXEC");
    }

    /**
     * 取消事务。
     */
    public String discard() {
        return toString(sendCommand("DISCARD"));
    }

    /**
     * 监控指定的键。
     */
    public String watch(String... keys) {
        Object[] args = new Object[keys.length + 1];
        args[0] = "WATCH";
        System.arraycopy(keys, 0, args, 1, keys.length);
        return toString(sendCommand("WATCH", (Object[]) args));
    }

    /**
     * 取消所有 WATCH。
     */
    public String unwatch() {
        return toString(sendCommand("UNWATCH"));
    }

    // ==================== Pub/Sub 命令 ====================

    /**
     * 订阅指定频道。
     */
    public void subscribe(String... channels) {
        Object[] args = new Object[channels.length + 1];
        args[0] = "SUBSCRIBE";
        System.arraycopy(channels, 0, args, 1, channels.length);
        sendCommand("SUBSCRIBE", (Object[]) args);
    }

    /**
     * 发布消息到指定频道。
     */
    public Long publish(String channel, String message) {
        return toLong(sendCommand("PUBLISH", channel, message));
    }

    // ==================== Pipeline ====================

    /**
     * 创建 Pipeline 批量命令处理器。
     * <p>
     * 使用方式：
     * <pre>
     * ZCachePipeline p = client.pipeline();
     * p.set("k1", "v1").get("k1").del("k2");
     * List&lt;Object&gt; results = p.syncAndReturnAll();
     * </pre>
     *
     * @return 新的 Pipeline 实例
     */
    public ZCachePipeline pipeline() {
        return new ZCachePipeline(this);
    }

    // ==================== 管理命令 ====================

    /**
     * 清空当前数据库。
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
