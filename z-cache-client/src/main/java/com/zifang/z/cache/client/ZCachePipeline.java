package com.zifang.z.cache.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline 批量命令处理器。
 * <p>
 * 缓存所有命令，调用 {@link #sync()} 或 {@link #syncAndReturnAll()} 时
 * 一次性写入网络，显著减少 RTT。
 *
 * <pre>
 * try (ZCacheClient client = new ZCacheClient("localhost", 6379)) {
 *     client.connect();
 *     ZCachePipeline p = client.pipeline();
 *     p.set("key1", "val1");
 *     p.set("key2", "val2");
 *     p.get("key1");
 *     List<Object> results = p.syncAndReturnAll();
 * }
 * </pre>
 *
 * @author zifang
 * @since 1.0.2
 */
public class ZCachePipeline {

    private final ZCacheClient client;
    private final List<CompletableFuture<Object>> futures = new ArrayList<>();

    public ZCachePipeline(ZCacheClient client) {
        this.client = client;
    }

    // ==================== String 命令 ====================

    public ZCachePipeline set(String key, String value) {
        futures.add(client.sendCommandAsync("SET", key, value));
        return this;
    }

    public ZCachePipeline get(String key) {
        futures.add(client.sendCommandAsync("GET", key));
        return this;
    }

    public ZCachePipeline del(String... keys) {
        Object[] args = new Object[keys.length + 1];
        args[0] = "DEL";
        System.arraycopy(keys, 0, args, 1, keys.length);
        futures.add(client.sendCommandAsync("DEL", (Object[]) args));
        return this;
    }

    public ZCachePipeline exists(String key) {
        futures.add(client.sendCommandAsync("EXISTS", key));
        return this;
    }

    public ZCachePipeline incr(String key) {
        futures.add(client.sendCommandAsync("INCR", key));
        return this;
    }

    public ZCachePipeline decr(String key) {
        futures.add(client.sendCommandAsync("DECR", key));
        return this;
    }

    // ==================== Hash 命令 ====================

    public ZCachePipeline hset(String key, String field, String value) {
        futures.add(client.sendCommandAsync("HSET", key, field, value));
        return this;
    }

    public ZCachePipeline hget(String key, String field) {
        futures.add(client.sendCommandAsync("HGET", key, field));
        return this;
    }

    public ZCachePipeline hdel(String key, String... fields) {
        Object[] args = new Object[fields.length + 2];
        args[0] = "HDEL"; args[1] = key;
        System.arraycopy(fields, 0, args, 2, fields.length);
        futures.add(client.sendCommandAsync("HDEL", (Object[]) args));
        return this;
    }

    public ZCachePipeline hgetall(String key) {
        futures.add(client.sendCommandAsync("HGETALL", key));
        return this;
    }

    // ==================== List 命令 ====================

    public ZCachePipeline lpush(String key, String... values) {
        Object[] args = new Object[values.length + 2];
        args[0] = "LPUSH"; args[1] = key;
        System.arraycopy(values, 0, args, 2, values.length);
        futures.add(client.sendCommandAsync("LPUSH", (Object[]) args));
        return this;
    }

    public ZCachePipeline rpush(String key, String... values) {
        Object[] args = new Object[values.length + 2];
        args[0] = "RPUSH"; args[1] = key;
        System.arraycopy(values, 0, args, 2, values.length);
        futures.add(client.sendCommandAsync("RPUSH", (Object[]) args));
        return this;
    }

    public ZCachePipeline lpop(String key) {
        futures.add(client.sendCommandAsync("LPOP", key));
        return this;
    }

    public ZCachePipeline rpop(String key) {
        futures.add(client.sendCommandAsync("RPOP", key));
        return this;
    }

    public ZCachePipeline lrange(String key, long start, long stop) {
        futures.add(client.sendCommandAsync("LRANGE", key, start, stop));
        return this;
    }

    // ==================== Set 命令 ====================

    public ZCachePipeline sadd(String key, String... members) {
        Object[] args = new Object[members.length + 2];
        args[0] = "SADD"; args[1] = key;
        System.arraycopy(members, 0, args, 2, members.length);
        futures.add(client.sendCommandAsync("SADD", (Object[]) args));
        return this;
    }

    public ZCachePipeline srem(String key, String... members) {
        Object[] args = new Object[members.length + 2];
        args[0] = "SREM"; args[1] = key;
        System.arraycopy(members, 0, args, 2, members.length);
        futures.add(client.sendCommandAsync("SREM", (Object[]) args));
        return this;
    }

    public ZCachePipeline smembers(String key) {
        futures.add(client.sendCommandAsync("SMEMBERS", key));
        return this;
    }

    // ==================== Sorted Set 命令 ====================

    public ZCachePipeline zadd(String key, double score, String member) {
        futures.add(client.sendCommandAsync("ZADD", key, score, member));
        return this;
    }

    public ZCachePipeline zrem(String key, String... members) {
        Object[] args = new Object[members.length + 2];
        args[0] = "ZREM"; args[1] = key;
        System.arraycopy(members, 0, args, 2, members.length);
        futures.add(client.sendCommandAsync("ZREM", (Object[]) args));
        return this;
    }

    public ZCachePipeline zscore(String key, String member) {
        futures.add(client.sendCommandAsync("ZSCORE", key, member));
        return this;
    }

    public ZCachePipeline zrange(String key, long start, long stop) {
        futures.add(client.sendCommandAsync("ZRANGE", key, start, stop));
        return this;
    }

    // ==================== 通用 ====================

    /**
     * 添加任意原始命令到 Pipeline。
     */
    public ZCachePipeline addCommand(String command, Object... args) {
        futures.add(client.sendCommandAsync(command, args));
        return this;
    }

    // ==================== 执行 ====================

    /**
     * 执行所有缓存的命令，不等待响应（fire-and-forget）。
     */
    public void sync() {
        futures.clear();
    }

    /**
     * 执行所有缓存的命令并等待所有响应，返回结果列表。
     *
     * @return 按命令执行顺序排列的响应对象列表
     */
    public List<Object> syncAndReturnAll() {
        List<Object> results = new ArrayList<>(futures.size());
        for (CompletableFuture<Object> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                results.add(e);
            }
        }
        futures.clear();
        return results;
    }
}
