package com.zifang.z.cache.spring;

import com.zifang.z.cache.client.ZCacheClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 面向 Spring 应用的 z-cache 操作模板。
 * <p>
 * 封装 ZCacheClient，提供 String/Hash/List/Set/ZSet 五种数据结构的
 * Spring-friendly API。
 *
 * @author zifang
 * @since 1.0.2
 */
public class ZCacheTemplate {
    private final ZCacheClient client;

    public ZCacheTemplate(ZCacheClient client) {
        this.client = client;
    }

    // ==================== String 命令 ====================

    public String get(String key) {
        return client.get(key);
    }

    public String set(String key, String value) {
        return client.set(key, value);
    }

    public String setex(String key, long seconds, String value) {
        return client.setex(key, seconds, value);
    }

    public Long delete(String... keys) {
        return client.del(keys);
    }

    public Long exists(String... keys) {
        return client.exists(keys);
    }

    public Long expire(String key, long seconds) {
        return client.expire(key, seconds);
    }

    public Long ttl(String key) {
        return client.ttl(key);
    }

    public List<String> mget(String... keys) {
        return client.mget(keys);
    }

    public String mset(String... keyValues) {
        return client.mset(keyValues);
    }

    public List<String> keys(String pattern) {
        return client.keys(pattern);
    }

    public String info() {
        return client.info();
    }

    // ==================== Hash 命令 ====================

    public Long hset(String key, String field, String value) {
        return client.hset(key, field, value);
    }

    public String hget(String key, String field) {
        return client.hget(key, field);
    }

    public Long hdel(String key, String... fields) {
        return client.hdel(key, fields);
    }

    public Long hexists(String key, String field) {
        return client.hexists(key, field);
    }

    public List<String> hgetall(String key) {
        return client.hgetall(key);
    }

    public List<String> hkeys(String key) {
        return client.hkeys(key);
    }

    public List<String> hvals(String key) {
        return client.hvals(key);
    }

    public List<String> hmget(String key, String... fields) {
        return client.hmget(key, fields);
    }

    public String hmset(String key, String... fieldValues) {
        return client.hmset(key, fieldValues);
    }

    public Long hincrby(String key, String field, long increment) {
        return client.hincrby(key, field, increment);
    }

    public Long hlen(String key) {
        return client.hlen(key);
    }

    public Long hsetnx(String key, String field, String value) {
        return client.hsetnx(key, field, value);
    }

    // ==================== List 命令 ====================

    public Long lpush(String key, String... values) {
        return client.lpush(key, values);
    }

    public Long rpush(String key, String... values) {
        return client.rpush(key, values);
    }

    public String lpop(String key) {
        return client.lpop(key);
    }

    public String rpop(String key) {
        return client.rpop(key);
    }

    public List<String> lrange(String key, long start, long stop) {
        return client.lrange(key, start, stop);
    }

    public String lindex(String key, long index) {
        return client.lindex(key, index);
    }

    public Long llen(String key) {
        return client.llen(key);
    }

    public String lset(String key, long index, String value) {
        return client.lset(key, index, value);
    }

    public Long lrem(String key, long count, String value) {
        return client.lrem(key, count, value);
    }

    public String ltrim(String key, long start, long stop) {
        return client.ltrim(key, start, stop);
    }

    public String rpoplpush(String source, String destination) {
        return client.rpoplpush(source, destination);
    }

    // ==================== Set 命令 ====================

    public Long sadd(String key, String... members) {
        return client.sadd(key, members);
    }

    public Long srem(String key, String... members) {
        return client.srem(key, members);
    }

    public List<String> smembers(String key) {
        return client.smembers(key);
    }

    public Long sismember(String key, String member) {
        return client.sismember(key, member);
    }

    public Long scard(String key) {
        return client.scard(key);
    }

    public String srandmember(String key) {
        return client.srandmember(key);
    }

    public List<String> sinter(String... keys) {
        return client.sinter(keys);
    }

    public List<String> sunion(String... keys) {
        return client.sunion(keys);
    }

    public List<String> sdiff(String... keys) {
        return client.sdiff(keys);
    }

    public Long smove(String source, String destination, String member) {
        return client.smove(source, destination, member);
    }

    // ==================== Sorted Set 命令 ====================

    public Long zadd(String key, double score, String member) {
        return client.zadd(key, score, member);
    }

    public Long zrem(String key, String... members) {
        return client.zrem(key, members);
    }

    public String zscore(String key, String member) {
        return client.zscore(key, member);
    }

    public Long zrank(String key, String member) {
        return client.zrank(key, member);
    }

    public Long zrevrank(String key, String member) {
        return client.zrevrank(key, member);
    }

    public Long zcard(String key) {
        return client.zcard(key);
    }

    public Long zcount(String key, double min, double max) {
        return client.zcount(key, min, max);
    }

    public List<String> zrange(String key, long start, long stop) {
        return client.zrange(key, start, stop);
    }

    public List<String> zrevrange(String key, long start, long stop) {
        return client.zrevrange(key, start, stop);
    }

    public String zincrby(String key, double increment, String member) {
        return client.zincrby(key, increment, member);
    }

    // ==================== 新增命令 ====================

    public String hrandfield(String key) {
        return client.hrandfield(key);
    }

    public List<String> hrandfield(String key, int count) {
        return client.hrandfield(key, count);
    }

    public String spop(String key) {
        return client.spop(key);
    }

    public List<String> spop(String key, int count) {
        return client.spop(key, count);
    }

    // ==================== 事务命令 ====================

    public String multi() {
        return client.multi();
    }

    public Object exec() {
        return client.exec();
    }

    public String discard() {
        return client.discard();
    }

    public String watch(String... keys) {
        return client.watch(keys);
    }

    public String unwatch() {
        return client.unwatch();
    }

    // ==================== Pub/Sub ====================

    public void subscribe(String... channels) {
        client.subscribe(channels);
    }

    public Long publish(String channel, String message) {
        return client.publish(channel, message);
    }

    // ==================== 管理命令 ====================

    public String select(int database) {
        return client.select(database);
    }

    public String flushdb() {
        return client.flushdb();
    }

    public String flushall() {
        return client.flushall();
    }

    public Long dbsize() {
        return client.dbsize();
    }

    public CompletableFuture<Object> executeAsync(String command, Object... args) {
        return client.sendCommandAsync(command, args);
    }

    public ZCacheClient getClient() {
        return client;
    }
}
