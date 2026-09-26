package com.zifang.z.cache.core.storage;

import com.zifang.z.cache.common.protocol.RedisDoubleFormat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Sorted Set（有序集合）数据结构存储引擎。
 * <p>
 * 每个 key 维护三个索引结构以支持高效的排序和范围查询：
 * <ul>
 *   <li>{@code memberScores}: member -> score 的映射，用于 O(1) 查询 member 的 score</li>
 *   <li>{@code scoreToMembers}: score -> members 的映射，使用 {@link ConcurrentSkipListMap} 按 score 排序</li>
 *   <li>{@code memberValues}: member -> 原始 byte[] 值的映射</li>
 * </ul>
 * </p>
 * <p>
 * 兼容 Redis RESP2 协议的 Sorted Set 命令语义。
 * 所有写操作通过 {@code synchronized} 保证原子性。
 * </p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class SortedSetStore {

    /**
     * 存储数据: key -> SortedSetData
     */
    private final ConcurrentHashMap<String, SortedSetData> store = new ConcurrentHashMap<>();

    // ==================== Internal Data Structure ====================

    /**
     * 有序集合的内部数据结构，维护三重索引。
     */
    private static class SortedSetData {
        /** member -> score */
        final Map<String, Double> memberScores = new ConcurrentHashMap<>();
        /** score -> members (按 score 排序，一个 score 可有多个 member) */
        final ConcurrentSkipListMap<Double, Set<String>> scoreToMembers = new ConcurrentSkipListMap<>();
        /** member -> 原始值 */
        final Map<String, byte[]> memberValues = new ConcurrentHashMap<>();

        /**
         * 获取所有 member 列表（按 score 升序，同 score 按字典序）。
         */
        List<String> getOrderedMembers() {
            List<String> result = new ArrayList<>();
            for (Map.Entry<Double, Set<String>> entry : scoreToMembers.entrySet()) {
                // 同 score 的 member 按字典序排序
                List<String> sortedMembers = new ArrayList<>(entry.getValue());
                sortedMembers.sort(String::compareTo);
                result.addAll(sortedMembers);
            }
            return result;
        }

        /**
         * 获取按 score 降序排列的 member 列表。
         */
        List<String> getReverseOrderedMembers() {
            List<String> result = new ArrayList<>();
            for (Map.Entry<Double, Set<String>> entry : scoreToMembers.descendingMap().entrySet()) {
                List<String> sortedMembers = new ArrayList<>(entry.getValue());
                sortedMembers.sort(String::compareTo);
                result.addAll(sortedMembers);
            }
            return result;
        }

        /**
         * 添加或更新 member 的 score。
         */
        void addMember(String member, double score, byte[] value) {
            // 如果 member 已存在，先从旧 score 的集合中移除
            Double oldScore = memberScores.get(member);
            if (oldScore != null) {
                Set<String> oldMembers = scoreToMembers.get(oldScore);
                if (oldMembers != null) {
                    oldMembers.remove(member);
                    if (oldMembers.isEmpty()) {
                        scoreToMembers.remove(oldScore);
                    }
                }
            }
            // 更新三重索引
            memberScores.put(member, score);
            scoreToMembers.computeIfAbsent(score, k -> ConcurrentHashMap.newKeySet()).add(member);
            if (value != null) {
                memberValues.put(member, value.clone());
            }
        }

        /**
         * 移除一个 member。
         */
        boolean removeMember(String member) {
            Double score = memberScores.remove(member);
            if (score == null) {
                return false;
            }
            Set<String> members = scoreToMembers.get(score);
            if (members != null) {
                members.remove(member);
                if (members.isEmpty()) {
                    scoreToMembers.remove(score);
                }
            }
            memberValues.remove(member);
            return true;
        }

        /**
         * 获取 member 的排名（从 0 开始，按 score 升序）。
         */
        int getRank(String member) {
            Double score = memberScores.get(member);
            if (score == null) {
                return -1;
            }
            int rank = 0;
            for (Map.Entry<Double, Set<String>> entry : scoreToMembers.entrySet()) {
                if (entry.getKey() < score) {
                    rank += entry.getValue().size();
                } else if (entry.getKey().equals(score)) {
                    // 同 score 的 member 按字典序排序
                    List<String> sorted = new ArrayList<>(entry.getValue());
                    sorted.sort(String::compareTo);
                    int idx = sorted.indexOf(member);
                    if (idx >= 0) {
                        return rank + idx;
                    }
                }
            }
            return -1;
        }

        /**
         * 获取 member 的逆排名（从 0 开始，按 score 降序）。
         */
        int getRevRank(String member) {
            Double score = memberScores.get(member);
            if (score == null) {
                return -1;
            }
            int totalSize = memberScores.size();
            int ascRank = getRank(member);
            return ascRank >= 0 ? totalSize - 1 - ascRank : -1;
        }

        /**
         * 获取指定 score 范围内的成员列表。
         */
        List<String> getMembersByScoreRange(double min, double max, int offset, int count) {
            List<String> result = new ArrayList<>();
            for (Map.Entry<Double, Set<String>> entry : scoreToMembers.entrySet()) {
                double s = entry.getKey();
                if (s < min || s > max) {
                    continue;
                }
                List<String> sorted = new ArrayList<>(entry.getValue());
                sorted.sort(String::compareTo);
                result.addAll(sorted);
            }
            if (offset > 0 && offset < result.size()) {
                result = new ArrayList<>(result.subList(offset, result.size()));
            } else if (offset >= result.size()) {
                return new ArrayList<>();
            }
            if (count > 0 && count < result.size()) {
                result = new ArrayList<>(result.subList(0, count));
            }
            return result;
        }

        /**
         * 统计指定 score 范围内的成员数量。
         */
        long countByScoreRange(double min, double max) {
            long count = 0;
            for (Map.Entry<Double, Set<String>> entry : scoreToMembers.entrySet()) {
                double s = entry.getKey();
                if (s >= min && s <= max) {
                    count += entry.getValue().size();
                }
            }
            return count;
        }
    }

    // ==================== Sorted Set Write Operations ====================

    /**
     * 向有序集合中添加一个或多个 member 及其 score。
     * <p>如果 member 已存在，则更新其 score。</p>
     *
     * @param key     键
     * @param score   分数
     * @param member  成员
     * @return 如果是新 member 返回 1，如果是更新已存在的 member 返回 0
     */
    public long zadd(String key, double score, byte[] member) {
        if (key == null || member == null) {
            return 0;
        }
        synchronized (store) {
            SortedSetData data = store.computeIfAbsent(key, k -> new SortedSetData());
            String memberStr = new String(member, StandardCharsets.UTF_8);
            boolean isNew = !data.memberScores.containsKey(memberStr);
            data.addMember(memberStr, score, member);
            return isNew ? 1 : 0;
        }
    }

    /**
     * 批量添加 member 及其 score。
     *
     * @param key     键
     * @param scores  分数数组
     * @param members 成员数组
     * @return 新增的 member 数量
     */
    public long zadd(String key, double[] scores, byte[][] members) {
        if (key == null || scores == null || members == null || scores.length != members.length) {
            return 0;
        }
        long count = 0;
        for (int i = 0; i < scores.length; i++) {
            count += zadd(key, scores[i], members[i]);
        }
        return count;
    }

    /**
     * 从有序集合中移除一个或多个 member。
     *
     * @param key     键
     * @param members 要移除的成员
     * @return 实际移除的 member 数量
     */
    public long zrem(String key, byte[]... members) {
        if (key == null || members == null || members.length == 0) {
            return 0;
        }
        synchronized (store) {
            SortedSetData data = store.get(key);
            if (data == null) {
                return 0;
            }
            long count = 0;
            for (byte[] member : members) {
                if (member == null) {
                    continue;
                }
                String memberStr = new String(member, StandardCharsets.UTF_8);
                if (data.removeMember(memberStr)) {
                    count++;
                }
            }
            if (data.memberScores.isEmpty()) {
                store.remove(key);
            }
            return count;
        }
    }

    /**
     * 对有序集合中指定 member 的 score 进行递增。
     *
     * @param key    键
     * @param delta  增量值
     * @param member 成员
     * @return 递增后的 score
     * @throws IllegalArgumentException 结果是 NaN（实测 {@code ZINCRBY z inf m}，m 已是 -inf
     *         → {@code resulting score is not a number (NaN)}）；拦在写盘之前，坏分数不会留在库里
     */
    public double zincrby(String key, double delta, byte[] member) {
        if (key == null || member == null) {
            throw new IllegalArgumentException("key and member must not be null");
        }
        synchronized (store) {
            SortedSetData data = store.computeIfAbsent(key, k -> new SortedSetData());
            String memberStr = new String(member, StandardCharsets.UTF_8);
            Double oldScore = data.memberScores.get(memberStr);
            double newScore = (oldScore == null ? 0.0 : oldScore) + delta;
            requireNotNan(newScore);
            data.addMember(memberStr, newScore, member);
            return newScore;
        }
    }

    /**
     * {@code ZADD} 的修饰位。互斥关系（NX/XX、GT/LT、INCR 与 GT/LT）由命令层判，
     * 这里只按位执行——存储层不认识"客户端的写法"，只认识"这一次要不要建、要不要改"。
     */
    public static final int ZADD_NX = 1;
    public static final int ZADD_XX = 2;
    public static final int ZADD_CH = 4;
    public static final int ZADD_GT = 8;
    public static final int ZADD_LT = 16;

    /**
     * 带修饰位的 ZADD。
     * <p>
     * 计数口径是实测出来的两把尺：不带 {@code CH} 只数<b>新增</b>的成员
     * （{@code ZADD k XX 3 a} 明明把 a 的分数改了，回的仍是 0），带了 {@code CH} 才把
     * "改动过的已有成员"一起算进去（{@code CH 6 a} 第一次回 1、原样再来一次回 0）。
     * 250 实测的 {@code GT}/{@code LT} 那两行回的是 {@code syntax error} —— 4.0.9 还不认识它们，
     * 本实现按 Redis 6.2 起的文法支持，而 6.2 那版的语义是<b>既不建新成员也不改劣</b>
     * （文档原话：{@code GT -- Only update elements that have a greater score. Don't add new
     * elements.}）。这一条没有对岸样本可钉（对岸压根不认这两个旗），依据只能是"既然自称支持
     * 6.2 的文法，就得照 6.2 的语义走"——把它记进 README 偏差清单，别当成实测来的。
     *
     * @param key     键
     * @param scores  分数数组
     * @param members 成员数组，与 scores 一一对应
     * @param flags   {@link #ZADD_NX} 等按位或
     * @return 要回复给客户端的计数
     */
    public long zadd(String key, double[] scores, byte[][] members, int flags) {
        if (key == null || scores == null || members == null || scores.length != members.length) {
            return 0;
        }
        long added = 0;
        long changed = 0;
        synchronized (store) {
            for (int i = 0; i < scores.length; i++) {
                byte[] member = members[i];
                if (member == null) {
                    continue;
                }
                String memberStr = new String(member, StandardCharsets.UTF_8);
                SortedSetData data = store.get(key);
                Double oldScore = data == null ? null : data.memberScores.get(memberStr);
                if (oldScore == null) {
                    if ((flags & ZADD_XX) != 0) {
                        // XX：只改已有的。一个都不改时也不该把键建出来（实测
                        // ZADD b6:z XX 1 a 2 b 回 0，而 b 不该因此多出一个空键）
                        continue;
                    }
                    if ((flags & (ZADD_GT | ZADD_LT)) != 0) {
                        // 同上：GT/LT 也不许凭空建成员，见方法上的说明
                        continue;
                    }
                    data = store.computeIfAbsent(key, k -> new SortedSetData());
                    data.addMember(memberStr, scores[i], member);
                    added++;
                    continue;
                }
                if ((flags & ZADD_NX) != 0) {
                    continue;
                }
                if ((flags & ZADD_GT) != 0 && scores[i] <= oldScore) {
                    continue;
                }
                if ((flags & ZADD_LT) != 0 && scores[i] >= oldScore) {
                    continue;
                }
                if (oldScore == scores[i]) {
                    // 分数一模一样：既不算新增也不算改动（实测 CH 那一支第二次原样写回 0），
                    // 连写都不用重写一遍
                    continue;
                }
                data.addMember(memberStr, scores[i], member);
                changed++;
            }
        }
        return (flags & ZADD_CH) != 0 ? added + changed : added;
    }

    /**
     * {@code ZADD key [NX|XX] [CH] INCR score member} —— 分数那一栏是<b>增量</b>而不是新分数。
     *
     * @return 递增后的分数；{@code null} 表示这一支被 NX/XX 挡下了（Redis 回的是 nil，
     *         实测 {@code ZADD b4:zz INCR NX 5 a} → {@code (nil)}，而不是 0 也不是报错）
     * @throws IllegalArgumentException 结果是 NaN
     */
    public Double zaddIncr(String key, double increment, byte[] member, int flags) {
        if (key == null || member == null) {
            throw new IllegalArgumentException("key and member must not be null");
        }
        synchronized (store) {
            String memberStr = new String(member, StandardCharsets.UTF_8);
            SortedSetData data = store.get(key);
            Double oldScore = data == null ? null : data.memberScores.get(memberStr);
            if (oldScore == null && (flags & ZADD_XX) != 0) {
                return null;
            }
            if (oldScore != null && (flags & ZADD_NX) != 0) {
                return null;
            }
            double newScore = (oldScore == null ? 0.0 : oldScore) + increment;
            requireNotNan(newScore);
            store.computeIfAbsent(key, k -> new SortedSetData()).addMember(memberStr, newScore, member);
            return newScore;
        }
    }

    /** 结果落在 NaN 上：Redis 在写盘之前拦下来，回的是那一句带括号的原文。 */
    private static void requireNotNan(double score) {
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("resulting score is not a number (NaN)");
        }
    }

    /**
     * member → score 的一份快照，给跨库搬迁（MOVE）用。
     * <p>
     * 不复用 {@code zrange(..., withScores=true)} 再 {@code parse} 回来：那条路径要先把自己的
     * 分数打成文本、再读回二进制，中间隔着打印规则，等于让一次纯搬运依赖一次编解码。
     *
     * @return 键不存在时是空表；返回的是拷贝，改它不影响库
     */
    public Map<String, Double> memberScores(String key) {
        SortedSetData data = key == null ? null : store.get(key);
        if (data == null) {
            return new java.util.LinkedHashMap<>();
        }
        synchronized (store) {
            return new java.util.LinkedHashMap<>(data.memberScores);
        }
    }

    // ==================== Sorted Set Read Operations ====================

    /**
     * 获取有序集合中指定 member 的 score。
     *
     * @param key    键
     * @param member 成员
     * @return score，member 不存在返回 null
     */
    public Double zscore(String key, byte[] member) {
        if (key == null || member == null) {
            return null;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return null;
        }
        String memberStr = new String(member, StandardCharsets.UTF_8);
        return data.memberScores.get(memberStr);
    }

    /**
     * 获取有序集合中指定 member 的排名（从 0 开始，按 score 升序）。
     *
     * @param key    键
     * @param member 成员
     * @return 排名，member 不存在返回 -1
     */
    public long zrank(String key, byte[] member) {
        if (key == null || member == null) {
            return -1;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return -1;
        }
        String memberStr = new String(member, StandardCharsets.UTF_8);
        return data.getRank(memberStr);
    }

    /**
     * 获取有序集合中指定 member 的逆排名（从 0 开始，按 score 降序）。
     *
     * @param key    键
     * @param member 成员
     * @return 逆排名，member 不存在返回 -1
     */
    public long zrevrank(String key, byte[] member) {
        if (key == null || member == null) {
            return -1;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return -1;
        }
        String memberStr = new String(member, StandardCharsets.UTF_8);
        return data.getRevRank(memberStr);
    }

    /**
     * 获取有序集合中 member 的数量。
     *
     * @param key 键
     * @return member 数量
     */
    public long zcard(String key) {
        if (key == null) {
            return 0;
        }
        SortedSetData data = store.get(key);
        return data == null ? 0 : data.memberScores.size();
    }

    /**
     * 统计有序集合中 score 在指定范围内的 member 数量。
     *
     * @param key 键
     * @param min 最小 score（包含）
     * @param max 最大 score（包含）
     * @return member 数量
     */
    public long zcount(String key, double min, double max) {
        if (key == null) {
            return 0;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return 0;
        }
        return data.countByScoreRange(min, max);
    }

    /**
     * 获取有序集合中指定索引范围内的 member（按 score 升序）。
     *
     * @param key        键
     * @param start      起始索引
     * @param stop       结束索引（包含）
     * @param withScores 是否返回 score
     * @return member 列表，withScores 为 true 时交替返回 member 和 score
     */
    public List<byte[]> zrange(String key, long start, long stop, boolean withScores) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return result;
        }
        List<String> members = data.getOrderedMembers();
        int size = members.size();
        int resolvedStart = (int) resolveIndex(start, size);
        int resolvedStop = (int) resolveIndex(stop, size);
        if (resolvedStart < 0) resolvedStart = 0;
        if (resolvedStop >= size) resolvedStop = size - 1;
        if (resolvedStart > resolvedStop || resolvedStart >= size) {
            return result;
        }
        for (int i = resolvedStart; i <= resolvedStop; i++) {
            String member = members.get(i);
            result.add(member.getBytes(StandardCharsets.UTF_8));
            if (withScores) {
                Double score = data.memberScores.get(member);
                result.add(RedisDoubleFormat.formatBytes(score));
            }
        }
        return result;
    }

    /**
     * 获取有序集合中指定索引范围内的 member（按 score 降序）。
     *
     * @param key        键
     * @param start      起始索引
     * @param stop       结束索引（包含）
     * @param withScores 是否返回 score
     * @return member 列表
     */
    public List<byte[]> zrevrange(String key, long start, long stop, boolean withScores) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return result;
        }
        List<String> members = data.getReverseOrderedMembers();
        int size = members.size();
        int resolvedStart = (int) resolveIndex(start, size);
        int resolvedStop = (int) resolveIndex(stop, size);
        if (resolvedStart < 0) resolvedStart = 0;
        if (resolvedStop >= size) resolvedStop = size - 1;
        if (resolvedStart > resolvedStop || resolvedStart >= size) {
            return result;
        }
        for (int i = resolvedStart; i <= resolvedStop; i++) {
            String member = members.get(i);
            result.add(member.getBytes(StandardCharsets.UTF_8));
            if (withScores) {
                Double score = data.memberScores.get(member);
                result.add(RedisDoubleFormat.formatBytes(score));
            }
        }
        return result;
    }

    /**
     * 获取有序集合中 score 在指定范围内的 member（按 score 升序）。
     *
     * @param key        键
     * @param min        最小 score
     * @param max        最大 score
     * @param withScores 是否返回 score
     * @param offset     偏移量
     * @param count      返回数量限制，-1 表示不限制
     * @return member 列表
     */
    public List<byte[]> zrangebyscore(String key, double min, double max,
                                      boolean withScores, int offset, int count) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return result;
        }
        List<String> members = data.getMembersByScoreRange(min, max, offset, count);
        for (String member : members) {
            result.add(member.getBytes(StandardCharsets.UTF_8));
            if (withScores) {
                Double score = data.memberScores.get(member);
                result.add(RedisDoubleFormat.formatBytes(score));
            }
        }
        return result;
    }

    /**
     * 获取有序集合中 score 在指定范围内的 member（按 score 降序）。
     *
     * @param key        键
     * @param max        最大 score
     * @param min        最小 score
     * @param withScores 是否返回 score
     * @param offset     偏移量
     * @param count      返回数量限制，-1 表示不限制
     * @return member 列表
     */
    public List<byte[]> zrevrangebyscore(String key, double max, double min,
                                         boolean withScores, int offset, int count) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return result;
        }
        // 降序：先获取升序，再反转
        List<String> members = data.getMembersByScoreRange(min, max, 0, -1);
        // 反转顺序
        Collections.reverse(members);
        // 应用 offset 和 count
        if (offset > 0 && offset < members.size()) {
            members = new ArrayList<>(members.subList(offset, members.size()));
        } else if (offset >= members.size()) {
            return result;
        }
        if (count > 0 && count < members.size()) {
            members = new ArrayList<>(members.subList(0, count));
        }
        for (String member : members) {
            result.add(member.getBytes(StandardCharsets.UTF_8));
            if (withScores) {
                Double score = data.memberScores.get(member);
                result.add(RedisDoubleFormat.formatBytes(score));
            }
        }
        return result;
    }

    /**
     * 统计有序集合中按字典序在 min 和 max 之间的 member 数量。
     * 所有 member 必须具有相同的 score。
     *
     * @param key 键
     * @param min 最小 member（字典序）
     * @param max 最大 member（字典序）
     * @return member 数量
     */
    public long zlexcount(String key, String min, String max) {
        if (key == null) {
            return 0;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return 0;
        }
        long count = 0;
        for (String member : data.getOrderedMembers()) {
            if (compareLex(member, min) >= 0 && compareLex(member, max) <= 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取有序集合中按字典序在 min 和 max 之间的 member。
     *
     * @param key 键
     * @param min 最小 member（字典序）
     * @param max 最大 member（字典序）
     * @return member 列表
     */
    public List<byte[]> zrangebylex(String key, String min, String max) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return result;
        }
        for (String member : data.getOrderedMembers()) {
            if (compareLex(member, min) >= 0 && compareLex(member, max) <= 0) {
                result.add(member.getBytes(StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /**
     * 按字典序逆序获取有序集合中在 max 和 min 之间的 member。
     */
    public List<byte[]> zrevrangebylex(String key, String max, String min) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return result;
        }
        List<String> reversed = data.getReverseOrderedMembers();
        for (String member : reversed) {
            if (compareLex(member, min) >= 0 && compareLex(member, max) <= 0) {
                result.add(member.getBytes(StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /**
     * 移除有序集合中按字典序在 min 和 max 之间的 member。
     */
    public long zremrangebylex(String key, String min, String max) {
        if (key == null) {
            return 0;
        }
        synchronized (store) {
            SortedSetData data = store.get(key);
            if (data == null) {
                return 0;
            }
            long count = 0;
            List<String> toRemove = new ArrayList<>();
            for (String member : data.getOrderedMembers()) {
                if (compareLex(member, min) >= 0 && compareLex(member, max) <= 0) {
                    toRemove.add(member);
                }
            }
            for (String member : toRemove) {
                if (data.removeMember(member)) {
                    count++;
                }
            }
            if (data.memberScores.isEmpty()) {
                store.remove(key);
            }
            return count;
        }
    }

    /**
     * 移除有序集合中排名在 start 和 stop 之间的 member。
     */
    public long zremrangebyrank(String key, long start, long stop) {
        if (key == null) {
            return 0;
        }
        synchronized (store) {
            SortedSetData data = store.get(key);
            if (data == null) {
                return 0;
            }
            List<String> members = data.getOrderedMembers();
            int size = members.size();
            int resolvedStart = (int) resolveIndex(start, size);
            int resolvedStop = (int) resolveIndex(stop, size);
            if (resolvedStart < 0) resolvedStart = 0;
            if (resolvedStop >= size) resolvedStop = size - 1;
            if (resolvedStart > resolvedStop || resolvedStart >= size) {
                return 0;
            }
            long count = 0;
            for (int i = resolvedStop; i >= resolvedStart; i--) {
                if (data.removeMember(members.get(i))) {
                    count++;
                }
            }
            if (data.memberScores.isEmpty()) {
                store.remove(key);
            }
            return count;
        }
    }

    /**
     * 移除有序集合中 score 在 min 和 max 之间的 member。
     */
    public long zremrangebyscore(String key, double min, double max) {
        if (key == null) {
            return 0;
        }
        synchronized (store) {
            SortedSetData data = store.get(key);
            if (data == null) {
                return 0;
            }
            long count = 0;
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<Double, Set<String>> entry : data.scoreToMembers.entrySet()) {
                double s = entry.getKey();
                if (s >= min && s <= max) {
                    toRemove.addAll(entry.getValue());
                }
            }
            for (String member : toRemove) {
                if (data.removeMember(member)) {
                    count++;
                }
            }
            if (data.memberScores.isEmpty()) {
                store.remove(key);
            }
            return count;
        }
    }

    /**
     * 随机返回有序集合中的一个或多个 member（不移除）。
     */
    public List<byte[]> zrandmember(String key, int count) {
        List<byte[]> result = new ArrayList<>();
        if (key == null || count == 0) {
            return result;
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return result;
        }
        List<String> members = data.getOrderedMembers();
        int n = Math.abs(count);
        boolean unique = count > 0;
        if (n > members.size() && unique) {
            n = members.size();
        }
        String[] arr = members.toArray(new String[0]);
        // Fisher-Yates shuffle
        int limit = unique ? Math.min(n, arr.length) : n;
        for (int i = 0; i < limit && i < arr.length; i++) {
            int j = i + (int) (Math.random() * (arr.length - i));
            String temp = arr[i];
            arr[i] = arr[j];
            arr[j] = temp;
            result.add(arr[i].getBytes(StandardCharsets.UTF_8));
        }
        if (!unique && n > arr.length) {
            // 允许重复
            java.util.Random rnd = new java.util.Random();
            while (result.size() < n) {
                result.add(arr[rnd.nextInt(arr.length)].getBytes(StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /**
     * 增量遍历有序集合。
     * 简化实现：直接返回所有 member（忽略 cursor）。
     */
    public Object[] zscan(String key, String cursor, String pattern) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return new Object[]{"0", result};
        }
        SortedSetData data = store.get(key);
        if (data == null) {
            return new Object[]{"0", result};
        }
        String regex = pattern == null ? null : globToRegex(pattern);
        for (String member : data.getOrderedMembers()) {
            if (regex == null || member.matches(regex)) {
                result.add(member.getBytes(StandardCharsets.UTF_8));
            }
        }
        return new Object[]{"0", result};
    }

    /**
     * 字典序比较辅助方法。支持 "+"、"-"、"(" 前缀。
     */
    private int compareLex(String member, String spec) {
        if ("+".equals(spec)) return -1; // + 比所有都大
        if ("-".equals(spec)) return 1;  // - 比所有都小
        if (spec.startsWith("(")) {
            return member.compareTo(spec.substring(1)) < 0 ? 1 : -1;
        }
        // "[" 是 Redis 唯一的"含端点"写法，而 ZRANGEBYLEX 的规范用例几乎都是它。
        // 以前只剥 "("，于是 [a 被当成字面量 "[a" 去比：'a' > '['，
        // 结果 ZREMRANGEBYLEX key [a [a 恒为 0 条、ZRANGEBYLEX 恒为空。
        if (spec.startsWith("[")) {
            return member.compareTo(spec.substring(1));
        }
        return member.compareTo(spec);
    }

    // ==================== Aggregate Operations ====================

    /**
     * 计算多个有序集合的并集，结果存储到目标 key。
     *
     * @param dest     目标键
     * @param keys     源键数组
     * @param weights  权重数组（可为 null，表示所有 score 乘以 1）
     * @param aggregate 聚合方式: "SUM"、"MIN"、"MAX"
     * @return 结果集中的 member 数量
     */
    public long zunionstore(String dest, String[] keys, double[] weights, String aggregate) {
        return aggregateStore(dest, keys, weights, aggregate, false);
    }

    /**
     * 计算多个有序集合的交集，结果存储到目标 key。
     *
     * @param dest     目标键
     * @param keys     源键数组
     * @param weights  权重数组（可为 null，表示所有 score 乘以 1）
     * @param aggregate 聚合方式: "SUM"、"MIN"、"MAX"
     * @return 结果集中的 member 数量
     */
    public long zinterstore(String dest, String[] keys, double[] weights, String aggregate) {
        return aggregateStore(dest, keys, weights, aggregate, true);
    }

    /**
     * 内部聚合运算实现。
     */
    private long aggregateStore(String dest, String[] keys, double[] weights,
                                String aggregate, boolean isIntersect) {
        if (dest == null || keys == null || keys.length == 0) {
            return 0;
        }
        synchronized (store) {
            // 收集所有 member 及其对应的 score 列表
            Map<String, List<Double>> memberAllScores = new HashMap<>();
            for (int i = 0; i < keys.length; i++) {
                SortedSetData data = store.get(keys[i]);
                if (data == null) {
                    if (isIntersect) {
                        // 交集中如果有一个 key 不存在，结果为空
                        store.remove(dest);
                        return 0;
                    }
                    continue;
                }
                double weight = (weights != null && i < weights.length) ? weights[i] : 1.0;
                for (Map.Entry<String, Double> entry : data.memberScores.entrySet()) {
                    String member = entry.getKey();
                    double weightedScore = entry.getValue() * weight;
                    memberAllScores.computeIfAbsent(member, k -> new ArrayList<>()).add(weightedScore);
                }
            }

            // 过滤并聚合
            SortedSetData destData = new SortedSetData();
            for (Map.Entry<String, List<Double>> entry : memberAllScores.entrySet()) {
                String member = entry.getKey();
                List<Double> scores = entry.getValue();

                // 交集：member 必须在所有 key 中都存在
                if (isIntersect && scores.size() < keys.length) {
                    continue;
                }
                // 并集：member 至少在一个 key 中存在（默认满足）

                double aggregatedScore;
                if ("MIN".equalsIgnoreCase(aggregate)) {
                    aggregatedScore = Double.MAX_VALUE;
                    for (Double s : scores) {
                        aggregatedScore = Math.min(aggregatedScore, s);
                    }
                } else if ("MAX".equalsIgnoreCase(aggregate)) {
                    aggregatedScore = -Double.MAX_VALUE;
                    for (Double s : scores) {
                        aggregatedScore = Math.max(aggregatedScore, s);
                    }
                } else {
                    // 默认 SUM
                    aggregatedScore = 0;
                    for (Double s : scores) {
                        aggregatedScore += s;
                    }
                }
                // 使用成员的原始值
                byte[] value = member.getBytes(StandardCharsets.UTF_8);
                destData.addMember(member, aggregatedScore, value);
            }

            if (destData.memberScores.isEmpty()) {
                store.remove(dest);
                return 0;
            }
            store.put(dest, destData);
            return destData.memberScores.size();
        }
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
     * @return "zset"
     */
    public String type() {
        return "zset";
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
        SortedSetData data = store.get(key);
        return data != null && !data.memberScores.isEmpty();
    }

    /**
     * 删除整个有序集合。
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

    /**
     * 将支持负数的索引解析为实际索引。
     */
    private long resolveIndex(long index, int size) {
        if (index >= 0) {
            return index;
        }
        return (long) size + index;
    }
}
