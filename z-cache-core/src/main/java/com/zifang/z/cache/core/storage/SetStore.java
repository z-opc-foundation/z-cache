package com.zifang.z.cache.core.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Set 数据结构存储引擎。
 * <p>
 * 基于 {@link ConcurrentHashMap} + {@link LinkedHashSet} 实现，
 * 存储层使用 {@code String}（UTF-8）做去重，对外接口统一使用 {@code byte[]}。
 * 兼容 Redis RESP2 协议的 Set 命令语义。
 * 所有写操作通过 {@code synchronized} 保证原子性。
 * </p>
 *
 * <p>内部存储结构: {@code ConcurrentHashMap<String, LinkedHashSet<String>>}</p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class SetStore {

    /**
     * 存储数据: key -> set of members (UTF-8 strings)
     */
    private final ConcurrentHashMap<String, LinkedHashSet<String>> store = new ConcurrentHashMap<>();

    /**
     * 获取本存储中引用的另一个 SetStore（用于跨 key 的集合运算）。
     * 由外部在调用 sinter/sunion/sdiff 时传入。
     */
    private SetStore otherStore;

    /**
     * 设置关联的另一个 SetStore，用于跨 key 集合运算。
     *
     * @param other 另一个 SetStore 实例
     */
    public void setOtherStore(SetStore other) {
        this.otherStore = other;
    }

    // ==================== Set Write Operations ====================

    /**
     * 向集合中添加一个或多个元素。
     *
     * @param key     键
     * @param members 要添加的元素
     * @return 实际新增（非重复）的元素数量
     */
    public long sadd(String key, byte[]... members) {
        if (key == null || members == null || members.length == 0) {
            return 0;
        }
        synchronized (store) {
            LinkedHashSet<String> set = store.computeIfAbsent(key, k -> new LinkedHashSet<>());
            long count = 0;
            for (byte[] member : members) {
                if (member == null) {
                    continue;
                }
                String str = new String(member, StandardCharsets.UTF_8);
                if (set.add(str)) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 从集合中移除一个或多个元素。
     *
     * @param key     键
     * @param members 要移除的元素
     * @return 实际移除的元素数量
     */
    public long srem(String key, byte[]... members) {
        if (key == null || members == null || members.length == 0) {
            return 0;
        }
        synchronized (store) {
            LinkedHashSet<String> set = store.get(key);
            if (set == null) {
                return 0;
            }
            long count = 0;
            for (byte[] member : members) {
                if (member == null) {
                    continue;
                }
                String str = new String(member, StandardCharsets.UTF_8);
                if (set.remove(str)) {
                    count++;
                }
            }
            if (set.isEmpty()) {
                store.remove(key);
            }
            return count;
        }
    }

    /**
     * 将元素从源集合移动到目标集合。
     *
     * @param source 源集合键
     * @param dest   目标集合键
     * @param member 要移动的元素
     * @return 移动成功返回 true，元素不在源集合中返回 false
     */
    public boolean smove(String source, String dest, byte[] member) {
        if (source == null || dest == null || member == null) {
            return false;
        }
        synchronized (store) {
            LinkedHashSet<String> srcSet = store.get(source);
            if (srcSet == null) {
                return false;
            }
            String str = new String(member, StandardCharsets.UTF_8);
            if (!srcSet.remove(str)) {
                return false;
            }
            if (srcSet.isEmpty()) {
                store.remove(source);
            }
            LinkedHashSet<String> destSet = store.computeIfAbsent(dest, k -> new LinkedHashSet<>());
            destSet.add(str);
            return true;
        }
    }

    // ==================== Set Read Operations ====================

    /**
     * 获取集合中的所有元素。
     *
     * @param key 键
     * @return 元素列表
     */
    public List<byte[]> smembers(String key) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        LinkedHashSet<String> set = store.get(key);
        if (set == null) {
            return result;
        }
        for (String member : set) {
            result.add(member.getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    /**
     * 检查元素是否是集合的成员。
     *
     * @param key    键
     * @param member 元素
     * @return 是成员返回 true
     */
    public boolean sismember(String key, byte[] member) {
        if (key == null || member == null) {
            return false;
        }
        LinkedHashSet<String> set = store.get(key);
        if (set == null) {
            return false;
        }
        String str = new String(member, StandardCharsets.UTF_8);
        return set.contains(str);
    }

    /**
     * 获取集合中元素的数量。
     *
     * @param key 键
     * @return 元素数量
     */
    public long scard(String key) {
        if (key == null) {
            return 0;
        }
        LinkedHashSet<String> set = store.get(key);
        return set == null ? 0 : set.size();
    }

    /**
     * 随机获取集合中的一个或多个元素。
     *
     * @param key    键
     * @param count  获取数量（正数）
     * @return 随机元素列表
     */
    public List<byte[]> srandmember(String key, int count) {
        List<byte[]> result = new ArrayList<>();
        if (key == null || count <= 0) {
            return result;
        }
        LinkedHashSet<String> set = store.get(key);
        if (set == null || set.isEmpty()) {
            return result;
        }
        String[] members = set.toArray(new String[0]);
        // Fisher-Yates 洗牌取前 count 个
        int n = Math.min(count, members.length);
        for (int i = 0; i < n; i++) {
            int j = i + (int) (Math.random() * (members.length - i));
            String temp = members[i];
            members[i] = members[j];
            members[j] = temp;
            result.add(members[i].getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    /**
     * 随机移除并返回集合中的一个或多个元素。
     *
     * @param key    键
     * @param count  移除数量（可选，默认 1）
     * @return 随机移除的元素列表
     */
    public List<byte[]> spop(String key, int count) {
        List<byte[]> result = new ArrayList<>();
        if (key == null || count <= 0) {
            return result;
        }
        synchronized (store) {
            LinkedHashSet<String> set = store.get(key);
            if (set == null || set.isEmpty()) {
                return result;
            }
            String[] members = set.toArray(new String[0]);
            int n = Math.min(count, members.length);
            // Fisher-Yates 洗牌取前 n 个
            for (int i = 0; i < n; i++) {
                int j = i + (int) (Math.random() * (members.length - i));
                String temp = members[i];
                members[i] = members[j];
                members[j] = temp;
                set.remove(members[i]);
                result.add(members[i].getBytes(StandardCharsets.UTF_8));
            }
            if (set.isEmpty()) {
                store.remove(key);
            }
        }
        return result;
    }

    /**
     * 计算多个集合的交集。
     *
     * @param keys 要取交集的键列表
     * @return 交集结果
     */
    public List<byte[]> sinter(String... keys) {
        if (keys == null || keys.length == 0) {
            return new ArrayList<>();
        }
        Set<String> result = null;
        for (String key : keys) {
            LinkedHashSet<String> set = store.get(key);
            if (set == null) {
                return new ArrayList<>();
            }
            if (result == null) {
                result = new HashSet<>(set);
            } else {
                result.retainAll(set);
            }
        }
        List<byte[]> byteResult = new ArrayList<>();
        if (result != null) {
            for (String member : result) {
                byteResult.add(member.getBytes(StandardCharsets.UTF_8));
            }
        }
        return byteResult;
    }

    /**
     * 计算多个集合的并集。
     *
     * @param keys 要取并集的键列表
     * @return 并集结果
     */
    public List<byte[]> sunion(String... keys) {
        if (keys == null || keys.length == 0) {
            return new ArrayList<>();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String key : keys) {
            LinkedHashSet<String> set = store.get(key);
            if (set != null) {
                result.addAll(set);
            }
        }
        List<byte[]> byteResult = new ArrayList<>();
        for (String member : result) {
            byteResult.add(member.getBytes(StandardCharsets.UTF_8));
        }
        return byteResult;
    }

    /**
     * 计算集合的差集（第一个集合中存在但其他集合中不存在的元素）。
     *
     * @param keys 要取差集的键列表，第一个键为主集合
     * @return 差集结果
     */
    public List<byte[]> sdiff(String... keys) {
        if (keys == null || keys.length == 0) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> mainSet = store.get(keys[0]);
        if (mainSet == null) {
            return new ArrayList<>();
        }
        Set<String> result = new LinkedHashSet<>(mainSet);
        for (int i = 1; i < keys.length; i++) {
            LinkedHashSet<String> set = store.get(keys[i]);
            if (set != null) {
                result.removeAll(set);
            }
        }
        List<byte[]> byteResult = new ArrayList<>();
        for (String member : result) {
            byteResult.add(member.getBytes(StandardCharsets.UTF_8));
        }
        return byteResult;
    }

    /**
     * 增量遍历集合中的成员。
     * 简化实现：cursor 忽略，直接返回所有匹配 pattern 的成员。
     *
     * @param key     键
     * @param cursor  游标（此实现中忽略）
     * @param pattern 匹配模式
     * @return [cursor, members]
     */
    public Object[] sscan(String key, String cursor, String pattern) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return new Object[]{"0", result};
        }
        LinkedHashSet<String> set = store.get(key);
        if (set == null) {
            return new Object[]{"0", result};
        }
        String regex = pattern == null ? null : globToRegex(pattern);
        for (String member : set) {
            if (regex == null || member.matches(regex)) {
                result.add(member.getBytes(StandardCharsets.UTF_8));
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
     * @return "set"
     */
    public String type() {
        return "set";
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
        LinkedHashSet<String> set = store.get(key);
        return set != null && !set.isEmpty();
    }

    /**
     * 删除整个集合。
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

    private static String globToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') regex.append(".*");
            else if (c == '?') regex.append('.');
            else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        return regex.append('$').toString();
    }
}
