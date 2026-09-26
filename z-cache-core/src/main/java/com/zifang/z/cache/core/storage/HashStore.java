package com.zifang.z.cache.core.storage;

import com.zifang.z.cache.common.protocol.RedisDoubleFormat;
import com.zifang.z.cache.common.protocol.RedisIntegerFormat;

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

    /**
     * 参考实现把 hash 字段抄进一个定长栈缓冲才交给 {@code strtold}，抄不进去的字段一律算
     * "不是浮点"。实测的分界：255 字节的数字串加得动，256 字节起回
     * {@code hash value is not a float}（250，ref21 的 d255 / d256 两行）。
     */
    private static final int LDBL_FIELD_READ_BUF = 256;

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
                // 键里存的那串也是客户端文本，所以用同一把尺：Long.parseLong 收得下 05 / +5 / -0，
                // 而 HSET h f 05 之后 HINCRBY h f 1 在参考实现里是拒的（它走的是 string2ll）。
                Long parsed = RedisIntegerFormat.parse(new String(existing, StandardCharsets.UTF_8));
                if (parsed == null) {
                    // 文案是量出来的，不是推的：对岸这一句是 {@code ERR hash value is not an integer}
                    // （battery38 第 18/21 行，250 实测），比通用那句整数短，也不带 "or out of range"。
                    throw new IllegalArgumentException("hash value is not an integer");
                }
                value = parsed.longValue();
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
     * <p>
     * 增量和结果都是<b>文本</b>：参考实现在这一步上不做任何二进制浮点的往返（键里存的就是
     * 回复打出的那串），而它的算术是 80 位 long double。传 double 进来等于先把增量压成
     * 53 位再相加，{@code HINCRBYFLOAT h f 0.2}（f 已是 0.1）就会写成
     * {@code 0.30000000000000004}，而实测对岸回 {@code 0.3}。算术与打印一起交给
     * {@link RedisDoubleFormat#plainSum}。
     *
     * @param key   键
     * @param field 字段名
     * @param delta 增量文本（客户端原样给进来的那串）
     * @return 递增后的那串文本，也就是要回复给客户端的那串
     * @throws NumberFormatException 增量不是合法浮点文本（"value is not a valid float"）
     * @throws IllegalArgumentException 字段原值不是合法浮点文本（"hash value is not a float"）
     */
    public String hincrbyfloat(String key, String field, String delta) {
        if (key == null || field == null) {
            throw new IllegalArgumentException("key and field must not be null");
        }
        synchronized (store) {
            ConcurrentHashMap<String, byte[]> existing = store.get(key);
            byte[] currentValue = existing == null ? null : existing.get(field);
            // 字段不存在时从 0 起算，和参考实现一样。
            String base = currentValue == null ? "0" : new String(currentValue, StandardCharsets.UTF_8);
            // 增量先量、原值后量：参考实现的 hincrbyfloatCommand 是先解析 argv[3] 再回头读字段，
            // 于是"原值已是 -nan 而增量又写了 nan"这一例回的是<b>增量</b>的文案（250 实测 ref12：
            // HINCRBYFLOAT b12:a f nan → value is not a valid float，紧接着同一字段配 1 才回
            // hash value is not a float，ref13）。反过来先量原值就会把这两条文案串位。
            if (!RedisDoubleFormat.isInfinityText(delta)) {
                RedisDoubleFormat.requirePlain(delta);
            }
            if (currentValue != null && !RedisDoubleFormat.isInfinityText(base)) {
                // 读原值这一步在参考实现里要先把字段抄进一个定长栈缓冲，抄不进去就当"这字段
                // 不是浮点"（250 实测 ref21：255 位纯数字加得动，256 位起 "hash value is not a
                // float"）。这一条只在 hash 侧有：同样长度的值走字符串那一族的 INCRBYFLOAT
                // 是加得动的（实测 200 位）。而它先于真正的 strtold，所以 256 位的那串哪怕
                // 数值合法（1e4932 的精确展开就是 4933 位）也一律算坏。
                if (currentValue.length >= LDBL_FIELD_READ_BUF) {
                    throw new IllegalArgumentException("hash value is not a float");
                }
                // 坏在原值和坏在增量，参考实现回的是两条不同文案（250 实测：
                // HSET h f abc 之后 HINCRBYFLOAT h f 1 → hash value is not a float，
                // 而 HINCRBYFLOAT h f abc → value is not a valid float）。一次算完分不出是谁，
                // 所以原值要先单独量一遍。原值本身是 inf 时不算坏 —— 这一族允许无穷参与运算
                // 并把 "-inf" 写回字段（实测），只有 nan / 越界那种读不回来的才算坏。
                try {
                    RedisDoubleFormat.requirePlain(base);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("hash value is not a float", e);
                }
            }
            // 算术先做完，再落盘：以前是 computeIfAbsent 之后才算，于是"增量写歪了"这种
            // 一定失败的调用也会在库里留下一个空 hash —— TYPE 报 hash、DBSIZE 多算一个键，
            // 而参考实现报错时什么都不建（实测 HINCRBYFLOAT b13:e f 1e99999 之后 EXISTS 回 0）。
            String result = RedisDoubleFormat.plainSumAllowingNonFinite(base, delta);
            ConcurrentHashMap<String, byte[]> hash = store.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            hash.put(field, result.getBytes(StandardCharsets.UTF_8));
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
     * HSTRLEN：字段值的<b>字节</b>长度，不是字符长度。
     * <p>
     * 实测两例把这条区分得很死：{@code HSET h f Hello} 之后 {@code HSTRLEN h f} 回 5，
     * 而 {@code HSET h u 你好} 之后回的是 <b>6</b>（UTF-8 三个字节一个汉字）。
     * 键不存在、字段不存在都回 0，都不算错。
     *
     * @param key    键
     * @param field  字段名
     * @return 字段值的字节数；键或字段不存在返回 0
     */
    public long hstrlen(String key, String field) {
        if (key == null || field == null) {
            return 0;
        }
        ConcurrentHashMap<String, byte[]> hash = store.get(key);
        if (hash == null) {
            return 0;
        }
        byte[] value = hash.get(field);
        return value == null ? 0 : value.length;
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
