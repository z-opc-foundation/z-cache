package com.zifang.z.cache.core.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hash 数据结构存储引擎。
 * <p>
 * 基于 {@link ConcurrentHashMap} 实现，外层 Key 映射到内层 Hash 字段映射，
 * 所有写操作通过 {@code synchronized} 保证原子性。
 * 兼容 Redis RESP2 协议的 Hash 命令语义。
 * </p>
 *
 * <p>内部存储结构: {@code ConcurrentHashMap<String, ConcurrentHashMap<String, byte[]>>}</p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class HashStore {

    /**
     * 存储数据: key -> (field -> value)
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, byte[]>> store = new ConcurrentHashMap<>();

    // ==================== Hash Write Operations ====================

    /**
     * 设置 hash 中一个或多个字段的值。
     * <p>如果 key 不存在，会创建一个新的 hash。</p>
     *
     * @param key    键
     * @param fields 字段-值映射
     * @return 实际新增（非覆盖）的字段数量
     */
    public long hmset(String key, Map<String, byte[]> fields) {
        if (key == null || fields == null || fields.isEmpty()) {
            return 0;
        }
        synchronized (store) {
            ConcurrentHashMap<String, byte[]> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            long count = 0;
            for (Map.Entry<String, byte[]> entry : fields.entrySet()) {
                if (!hash.containsKey(entry.getKey())) {
                    count++;
                }
                hash.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().clone());
            }
            return count;
        }
    }

    /**
     * 设置 hash 中一个字段的值。
     *
     * @param key   键
     * @param field 字段名
     * @param value 值
     * @return 如果字段是新创建的返回 1，如果字段已存在且值被覆盖返回 0
     */
    public long hset(String key, String field, byte[] value) {
        if (key == null || field == null) {
            return 0;
        }
        synchronized (store) {
            ConcurrentHashMap<String, byte[]> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            boolean isNew = !hash.containsKey(field);
            hash.put(field, value == null ? null : value.clone());
            return isNew ? 1 : 0;
        }
    }

    /**
     * 仅当字段不存在时设置其值（NX 语义）。
     *
     * @param key   键
     * @param field 字段名
     * @param value 值
     * @return 设置成功返回 true，字段已存在返回 false
     */
    public boolean hsetnx(String key, String field, byte[] value) {
        if (key == null || field == null) {
            return false;
        }
        synchronized (store) {
            ConcurrentHashMap<String, byte[]> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            if (hash.containsKey(field)) {
                return false;
            }
            hash.put(field, value == null ? null : value.clone());
            return true;
        }
    }

    /**
     * 对 hash 中指定字段的整数值进行递增。
     *
     * @param key   键
     * @param field 字段名
     * @param delta 增量值
     * @return 递增后的值
     * @throws IllegalArgumentException 如果字段值不是整数
     */
    public long hincrby(String key, String field, long delta) {
        if (key == null || field == null) {
            throw new IllegalArgumentException("key and field must not be null");
        }
        synchronized (store) {
            ConcurrentHashMap<String, byte[]> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            long value = 0;
            byte[] existing = hash.get(field);
            if (existing != null) {
                try {
                    value = Long.parseLong(new String(existing, StandardCharsets.UTF_8));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("hash field value is not an integer or out of range", e);
                }
            }
            long result;
            try {
                result = Math.addExact(value, delta);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("increment or decrement would overflow", e);
            }
            hash.put(field, Long.toString(result).getBytes(StandardCharsets.UTF_8));
            return result;
        }
    }

    /**
     * 对 hash 中指定字段的浮点数值进行递增。
     *
     * @param key   键
     * @param field 字段名
     * @param delta 增量值
     * @return 递增后的值
     * @throws IllegalArgumentException 如果字段值不是有效数字
     */
    public double hincrbyfloat(String key, String field, double delta) {
        if (key == null || field == null) {
            throw new IllegalArgumentException("key and field must not be null");
        }
        synchronized (store) {
            ConcurrentHashMap<String, byte[]> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            double value = 0.0;
            byte[] existing = hash.get(field);
            if (existing != null) {
                try {
                    value = Double.parseDouble(new String(existing, StandardCharsets.UTF_8));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("hash field value is not a valid float", e);
                }
            }
            double result = value + delta;
            hash.put(field, formatDouble(result).getBytes(StandardCharsets.UTF_8));
            return result;
        }
    }

    /**
     * 删除 hash 中一个或多个字段。
     *
     * @param key    键
     * @param fields 要删除的字段名
     * @return 实际删除的字段数量
     */
    public long hdel(String key, String... fields) {
        if (key == null || fields == null || fields.length == 0) {
            return 0;
        }
        synchronized (store) {
            ConcurrentHashMap<String, byte[]> hash = store.get(key);
            if (hash == null) {
                return 0;
            }
            long count = 0;
            for (String field : fields) {
                if (hash.remove(field) != null) {
                    count++;
                }
            }
            if (hash.isEmpty()) {
                store.remove(key);
            }
            return count;
        }
    }

    // ==================== Hash Read Operations ====================

    /**
     * 获取 hash 中指定字段的值。
     *
     * @param key   键
     * @param field 字段名
     * @return 字段值，不存在返回 null
     */
    public byte[] hget(String key, String field) {
        if (key == null || field == null) {
            return null;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        if (hash == null) {
            return null;
        }
        byte[] value = hash.get(field);
        return value == null ? null : value.clone();
    }

    /**
     * 获取 hash 中多个字段的值。
     *
     * @param key    键
     * @param fields 字段名列表
     * @return 字段值列表，不存在的字段返回 null
     */
    public List<byte[]> hmget(String key, String... fields) {
        List<byte[]> result = new ArrayList<>();
        if (key == null || fields == null) {
            return result;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        for (String field : fields) {
            if (hash == null) {
                result.add(null);
            } else {
                byte[] value = hash.get(field);
                result.add(value == null ? null : value.clone());
            }
        }
        return result;
    }

    /**
     * 检查 hash 中是否存在指定字段。
     *
     * @param key   键
     * @param field 字段名
     * @return 存在返回 true
     */
    public boolean hexists(String key, String field) {
        if (key == null || field == null) {
            return false;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        return hash != null && hash.containsKey(field);
    }

    /**
     * 获取 hash 中所有字段和值。
     *
     * @param key 键
     * @return 字段-值映射
     */
    public Map<String, byte[]> hgetall(String key) {
        Map<String, byte[]> result = new HashMap<>();
        if (key == null) {
            return result;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        if (hash == null) {
            return result;
        }
        for (Map.Entry<String, byte[]> entry : hash.entrySet()) {
            byte[] value = entry.getValue();
            result.put(entry.getKey(), value == null ? null : value.clone());
        }
        return result;
    }

    /**
     * 获取 hash 中所有字段名。
     *
     * @param key 键
     * @return 字段名列表
     */
    public List<String> hkeys(String key) {
        List<String> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        if (hash == null) {
            return result;
        }
        result.addAll(hash.keySet());
        return result;
    }

    /**
     * 获取 hash 中所有值。
     *
     * @param key 键
     * @return 值列表
     */
    public List<byte[]> hvals(String key) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        if (hash == null) {
            return result;
        }
        for (byte[] value : hash.values()) {
            result.add(value == null ? null : value.clone());
        }
        return result;
    }

    /**
     * 获取 hash 中字段的数量。
     *
     * @param key 键
     * @return 字段数量，key 不存在返回 0
     */
    public long hlen(String key) {
        if (key == null) {
            return 0;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        return hash == null ? 0 : hash.size();
    }

    /**
     * 随机返回 hash 中的一个或多个字段名。
     *
     * @param key     键
     * @param count   返回数量（正数=不重复，负数=允许重复）
     * @return 随机字段名列表
     */
    public List<String> hrandfield(String key, int count) {
        List<String> result = new ArrayList<>();
        if (key == null || count == 0) {
            return result;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        if (hash == null || hash.isEmpty()) {
            return result;
        }
        String[] fields = hash.keySet().toArray(new String[0]);
        if (count > 0) {
            // 不重复，最多返回全部字段数
            int n = Math.min(count, fields.length);
            // Fisher-Yates 洗牌
            for (int i = 0; i < n; i++) {
                int j = i + (int) (Math.random() * (fields.length - i));
                String temp = fields[i];
                fields[i] = fields[j];
                fields[j] = temp;
                result.add(fields[i]);
            }
        } else {
            // 负数 count，允许重复
            int n = Math.abs(count);
            java.util.Random rnd = new java.util.Random();
            for (int i = 0; i < n; i++) {
                result.add(fields[rnd.nextInt(fields.length)]);
            }
        }
        return result;
    }

    /**
     * 增量遍历 hash 中的字段和值。
     * <p>
     * 简化实现：cursor 忽略，直接返回所有匹配 pattern 的字段-值对。
     * 返回的 cursor 固定为 "0" 表示遍历完毕。
     * </p>
     *
     * @param key     键
     * @param cursor  游标（此实现中忽略）
     * @param pattern 匹配模式（支持 * 和 ?）
     * @return 包含游标和字段-值映射的数组: [cursor, Map]
     */
    public Object[] hscan(String key, String cursor, String pattern) {
        Map<String, byte[]> result = new HashMap<>();
        if (key == null) {
            return new Object[]{"0", result};
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        if (hash == null) {
            return new Object[]{"0", result};
        }
        String regex = pattern == null ? null : globToRegex(pattern);
        for (Map.Entry<String, byte[]> entry : hash.entrySet()) {
            if (regex == null || entry.getKey().matches(regex)) {
                byte[] value = entry.getValue();
                result.put(entry.getKey(), value == null ? null : value.clone());
            }
        }
        return new Object[]{"0", result};
    }

    // ==================== Generic Operations ====================

    /**
     * 返回该存储类型中所有 key。
     *
     * @return key 列表
     */
    public List<String> keys() {
        return new ArrayList<>(store.keySet());
    }

    /**
     * 返回存储类型名称。
     *
     * @return "hash"
     */
    public String type() {
        return "hash";
    }

    /**
     * 检查 key 是否存在。
     *
     * @param key 键
     * @return 存在返回 true
     */
    public boolean exists(String key) {
        if (key == null) {
            return false;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        return hash != null && !hash.isEmpty();
    }

    /**
     * 删除整个 hash。
     *
     * @param key 键
     * @return 删除成功返回 true
     */
    public boolean del(String key) {
        if (key == null) {
            return false;
        }
        return store.remove(key) != null;
    }

    /**
     * 统计存储的 key 数量。
     *
     * @return key 数量
     */
    public long dbsize() {
        return store.size();
    }

    /**
     * 清空所有数据。
     */
    public void flush() {
        store.clear();
    }

    // ==================== Internal Utilities ====================

    private static String formatDouble(double v) {
        String s = String.valueOf(v);
        if (s.contains(".") && !s.contains("E") && !s.contains("e")) {
            int l = s.length();
            while (l > 1 && s.charAt(l - 1) == '0' && s.charAt(l - 2) != '.') l--;
            s = s.substring(0, l);
        }
        return s;
    }

    /**
     * 将 glob 通配符模式转换为正则表达式。
     */
    private static String globToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.append('$').toString();
    }
}
