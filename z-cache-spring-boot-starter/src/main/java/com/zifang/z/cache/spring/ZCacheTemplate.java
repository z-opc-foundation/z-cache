package com.zifang.z.cache.spring;

import com.zifang.z.cache.client.ZCacheClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 面向 Spring 应用的 z-cache 操作模板。
 */
public class ZCacheTemplate {
    private final ZCacheClient client;

    public ZCacheTemplate(ZCacheClient client) {
        this.client = client;
    }

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

    public CompletableFuture<Object> executeAsync(String command, Object... args) {
        return client.sendCommandAsync(command, args);
    }

    public ZCacheClient getClient() {
        return client;
    }
}
