package com.zifang.z.cache.core.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * List 数据结构存储引擎。
 * <p>
 * 基于 {@link ConcurrentHashMap} + {@link CopyOnWriteArrayList} 实现，
 * 支持 Redis RESP2 协议的 List 命令语义，包括阻塞弹出操作。
 * 所有写操作通过 {@code synchronized} 保证原子性。
 * </p>
 *
 * <p>内部存储结构: {@code ConcurrentHashMap<String, CopyOnWriteArrayList<byte[]>>}</p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class ListStore {

    /**
     * 存储数据: key -> list
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<byte[]>> store = new ConcurrentHashMap<>();

    /**
     * 用于 bpop 阻塞通知的等待队列。
     * key: list key, value: 等待通知的 latch 列表
     */
    private final ConcurrentHashMap<String, List<CountDownLatch>> waitQueues = new ConcurrentHashMap<>();

    // ==================== List Write Operations ====================

    /**
     * 将一个或多个值插入到列表头部（左边）。
     *
     * @param key    键
     * @param values 要插入的值
     * @return 插入后列表的长度
     */
    public long lpush(String key, byte[]... values) {
        if (key == null || values == null || values.length == 0) {
            return 0;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
            for (int i = 0; i < values.length; i++) {
                list.add(0, values[i] == null ? null : values[i].clone());
            }
            notifyWaiters(key);
            return list.size();
        }
    }

    /**
     * 将一个或多个值插入到列表尾部（右边）。
     *
     * @param key    键
     * @param values 要插入的值
     * @return 插入后列表的长度
     */
    public long rpush(String key, byte[]... values) {
        if (key == null || values == null || values.length == 0) {
            return 0;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
            for (byte[] value : values) {
                list.add(value == null ? null : value.clone());
            }
            notifyWaiters(key);
            return list.size();
        }
    }

    /**
     * 只在列表<b>已经存在</b>时从头部插入（LPUSHX）。
     * <p>
     * 与 {@link #lpush} 的差别只有一处，但那一处是可观察的：键不存在时 LPUSH 会建键，
     * LPUSHX 回 0 且不建键（250 实测 {@code LPUSHX b2:nosuch x} → 0）。空列表等同于不存在
     * —— 本实现在列表被弹空时会把键摘掉，所以这里判"在不在"直接读 {@code store.get}。
     *
     * @param key    键
     * @param values 要插入的值
     * @return 插入后的长度；键不存在返回 0
     */
    public long lpushx(String key, byte[]... values) {
        return pushx(key, true, values);
    }

    /** 只在列表已经存在时从尾部插入（RPUSHX），判据同 {@link #lpushx}。 */
    public long rpushx(String key, byte[]... values) {
        return pushx(key, false, values);
    }

    private long pushx(String key, boolean head, byte[]... values) {
        if (key == null || values == null || values.length == 0) {
            return 0;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.get(key);
            if (list == null) {
                return 0;
            }
            for (int i = 0; i < values.length; i++) {
                if (head) {
                    list.add(0, values[i] == null ? null : values[i].clone());
                } else {
                    list.add(values[i] == null ? null : values[i].clone());
                }
            }
            notifyWaiters(key);
            return list.size();
        }
    }

    /**
     * 移除并返回列表头部（左边）的元素。
     *
     * @param key 键
     * @return 弹出的元素，列表不存在或为空返回 null
     */
    public byte[] lpop(String key) {
        if (key == null) {
            return null;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.get(key);
            if (list == null || list.isEmpty()) {
                return null;
            }
            byte[] value = list.remove(0);
            if (list.isEmpty()) {
                store.remove(key);
            }
            return value;
        }
    }

    /**
     * 移除并返回列表尾部（右边）的元素。
     *
     * @param key 键
     * @return 弹出的元素，列表不存在或为空返回 null
     */
    public byte[] rpop(String key) {
        if (key == null) {
            return null;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.get(key);
            if (list == null || list.isEmpty()) {
                return null;
            }
            byte[] value = list.remove(list.size() - 1);
            if (list.isEmpty()) {
                store.remove(key);
            }
            return value;
        }
    }

    /**
     * 将源列表的尾部元素弹出，并插入到目标列表的头部。
     * <p>等效于 RPOP source + LPUSH destination。</p>
     *
     * @param source 源列表键
     * @param dest   目标列表键
     * @return 被弹出的元素，源列表不存在或为空返回 null
     */
    public byte[] rpoplpush(String source, String dest) {
        if (source == null || dest == null) {
            return null;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> srcList = store.get(source);
            if (srcList == null || srcList.isEmpty()) {
                return null;
            }
            byte[] value = srcList.remove(srcList.size() - 1);
            if (srcList.isEmpty()) {
                store.remove(source);
            }
            CopyOnWriteArrayList<byte[]> destList = store.computeIfAbsent(dest, k -> new CopyOnWriteArrayList<>());
            destList.add(0, value == null ? null : value.clone());
            notifyWaiters(dest);
            return value;
        }
    }

    /**
     * 原子地将 source 列表中的元素弹出并推入 dest 列表。
     *
     * @param source     源列表键
     * @param dest       目标列表键
     * @param srcDir     源弹出方向: "LEFT" 或 "RIGHT"
     * @param destDir    目标推入方向: "LEFT" 或 "RIGHT"
     * @return 被移动的元素，源列表不存在或为空返回 null
     */
    public byte[] lmove(String source, String dest, String srcDir, String destDir) {
        if (source == null || dest == null || srcDir == null || destDir == null) {
            return null;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> srcList = store.get(source);
            if (srcList == null || srcList.isEmpty()) {
                return null;
            }
            boolean srcFromLeft = "LEFT".equalsIgnoreCase(srcDir);
            byte[] value = srcFromLeft ? srcList.remove(0) : srcList.remove(srcList.size() - 1);
            if (srcList.isEmpty()) {
                store.remove(source);
            }
            CopyOnWriteArrayList<byte[]> destList = store.computeIfAbsent(dest, k -> new CopyOnWriteArrayList<>());
            boolean destToLeft = "LEFT".equalsIgnoreCase(destDir);
            if (destToLeft) {
                destList.add(0, value == null ? null : value.clone());
            } else {
                destList.add(value == null ? null : value.clone());
            }
            notifyWaiters(dest);
            return value;
        }
    }

    /**
     * 在列表指定位置设置值。
     *
     * @param key   键
     * @param index 索引
     * @param value 值
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    public void lset(String key, int index, byte[] value) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.get(key);
            if (list == null) {
                throw new IndexOutOfBoundsException("index out of range");
            }
            int resolvedIndex = resolveIndex(index, list.size());
            if (resolvedIndex < 0 || resolvedIndex >= list.size()) {
                throw new IndexOutOfBoundsException("index out of range");
            }
            list.set(resolvedIndex, value == null ? null : value.clone());
        }
    }

    /**
     * 在列表指定元素前或后插入值。
     *
     * @param key    键
     * @param pivot  参考元素
     * @param value  要插入的值
     * @param before true 表示在 pivot 之前插入，false 表示在 pivot 之后插入
     * @return 插入后列表的长度，pivot 不存在返回 -1
     */
    public long linsert(String key, byte[] pivot, byte[] value, boolean before) {
        if (key == null || pivot == null) {
            return -1;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.get(key);
            if (list == null) {
                return -1;
            }
            int pivotIndex = -1;
            for (int i = 0; i < list.size(); i++) {
                if (java.util.Arrays.equals(pivot, list.get(i))) {
                    pivotIndex = i;
                    break;
                }
            }
            if (pivotIndex == -1) {
                return -1;
            }
            int insertIndex = before ? pivotIndex : pivotIndex + 1;
            list.add(insertIndex, value == null ? null : value.clone());
            return list.size();
        }
    }

    /**
     * 从列表中移除等于 value 的元素。
     *
     * @param key   键
     * @param count 移除数量: 0=移除所有, 正数=从头开始移除 count 个, 负数=从尾开始移除 |count| 个
     * @param value 要移除的值
     * @return 实际移除的元素数量
     */
    public long lrem(String key, int count, byte[] value) {
        if (key == null) {
            return 0;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.get(key);
            if (list == null || list.isEmpty()) {
                return 0;
            }
            long[] removed = {0};
            if (count == 0) {
                // 移除所有匹配的元素
                list.removeIf(item -> {
                    boolean match = java.util.Arrays.equals(value, item);
                    if (match) {
                        removed[0]++;
                    }
                    return match;
                });
            } else if (count > 0) {
                // 从头部开始移除 count 个
                for (int i = 0; i < list.size() && removed[0] < count; i++) {
                    if (java.util.Arrays.equals(value, list.get(i))) {
                        list.remove(i);
                        removed[0]++;
                        i--; // 移除后索引回退
                    }
                }
            } else {
                // 从尾部开始移除 |count| 个
                int absCount = Math.abs(count);
                for (int i = 0; i < list.size() && removed[0] < absCount; i++) {
                    if (java.util.Arrays.equals(value, list.get(i))) {
                        list.remove(i);
                        removed[0]++;
                        i--; // 移除后索引回退
                    }
                }
            }
            if (list.isEmpty()) {
                store.remove(key);
            }
            return removed[0];
        }
    }

    /**
     * 裁剪列表，只保留指定区间内的元素。
     *
     * @param key   键
     * @param start 起始索引（支持负数）
     * @param stop  结束索引（支持负数）
     */
    public void ltrim(String key, int start, int stop) {
        if (key == null) {
            return;
        }
        synchronized (store) {
            CopyOnWriteArrayList<byte[]> list = store.get(key);
            if (list == null) {
                return;
            }
            int size = list.size();
            int resolvedStart = resolveIndex(start, size);
            int resolvedStop = resolveIndex(stop, size);
            if (resolvedStart < 0) resolvedStart = 0;
            if (resolvedStop >= size) resolvedStop = size - 1;
            if (resolvedStart > resolvedStop || resolvedStart >= size) {
                store.remove(key);
                return;
            }
            List<byte[]> trimmed = new ArrayList<>();
            for (int i = resolvedStart; i <= resolvedStop; i++) {
                trimmed.add(list.get(i));
            }
            store.put(key, new CopyOnWriteArrayList<>(trimmed));
        }
    }

    // ==================== List Read Operations ====================

    /**
     * 获取列表指定区间内的元素。
     * <p>
     * 支持负数索引：-1 表示最后一个元素，-2 表示倒数第二个，以此类推。
     * </p>
     *
     * @param key   键
     * @param start 起始索引
     * @param stop  结束索引（包含）
     * @return 元素列表
     */
    public List<byte[]> lrange(String key, int start, int stop) {
        List<byte[]> result = new ArrayList<>();
        if (key == null) {
            return result;
        }
        CopyOnWriteArrayList<byte[]> list = store.get(key);
        if (list == null || list.isEmpty()) {
            return result;
        }
        int size = list.size();
        int resolvedStart = resolveIndex(start, size);
        int resolvedStop = resolveIndex(stop, size);
        if (resolvedStart < 0) resolvedStart = 0;
        if (resolvedStop >= size) resolvedStop = size - 1;
        if (resolvedStart > resolvedStop || resolvedStart >= size) {
            return result;
        }
        for (int i = resolvedStart; i <= resolvedStop; i++) {
            byte[] value = list.get(i);
            result.add(value == null ? null : value.clone());
        }
        return result;
    }

    /**
     * 获取列表指定索引处的元素。
     *
     * @param key   键
     * @param index 索引（支持负数）
     * @return 元素值，不存在返回 null
     */
    public byte[] lindex(String key, int index) {
        if (key == null) {
            return null;
        }
        CopyOnWriteArrayList<byte[]> list = store.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        int resolvedIndex = resolveIndex(index, list.size());
        if (resolvedIndex < 0 || resolvedIndex >= list.size()) {
            return null;
        }
        byte[] value = list.get(resolvedIndex);
        return value == null ? null : value.clone();
    }

    /**
     * 获取列表的长度。
     *
     * @param key 键
     * @return 列表长度，不存在返回 0
     */
    public long llen(String key) {
        if (key == null) {
            return 0;
        }
        CopyOnWriteArrayList<byte[]> list = store.get(key);
        return list == null ? 0 : list.size();
    }

    // ==================== Blocking Operations ====================

    /**
     * 阻塞弹出列表头部或尾部的元素。
     * <p>
     * 当列表为空时，当前线程将阻塞等待，直到有新元素被推入或超时。
     * 使用 {@link CountDownLatch} 实现阻塞语义。
     * </p>
     *
     * @param direction     弹出方向: "LEFT"（头部）或 "RIGHT"（尾部）
     * @param keys          要监听的 key，按 Redis 语义依参数顺序尝试，命中的 key 随值一起返回
     * @param timeoutSeconds 超时时间（秒），0 表示无限等待
     * @return {@code [key, value]}，超时返回 null
     */
    public List<byte[]> bpop(String direction, List<String> keys, int timeoutSeconds) {
        if (direction == null || keys == null || keys.isEmpty()) {
            return null;
        }
        final boolean fromLeft = "LEFT".equalsIgnoreCase(direction);

        List<byte[]> immediate = tryPop(fromLeft, keys);
        if (immediate != null) {
            return immediate;
        }

        final boolean infinite = timeoutSeconds <= 0;
        final long deadline = infinite ? 0L : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);

        while (infinite || System.currentTimeMillis() < deadline) {
            CountDownLatch latch = new CountDownLatch(1);
            try {
                // 先注册再补查一次：否则 push 恰好落在"查完"和"注册"之间就永久睡过去（丢唤醒）
                registerWaiter(null, latch);
                List<byte[]> raced = tryPop(fromLeft, keys);
                if (raced != null) {
                    return raced;
                }
                if (infinite) {
                    latch.await();
                } else {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining > 0) {
                        latch.await(remaining, TimeUnit.MILLISECONDS);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                // 超时退出时必须摘掉自己的 latch，否则 waitQueues 里只增不减
                unregisterWaiter(latch);
            }
            List<byte[]> popped = tryPop(fromLeft, keys);
            if (popped != null) {
                return popped;
            }
        }
        return null;
    }

    /** 按 keys 的给定顺序找第一个非空列表并弹一头/尾；命中的 key 一起带回。 */
    private List<byte[]> tryPop(boolean fromLeft, List<String> keys) {
        synchronized (store) {
            for (String key : keys) {
                CopyOnWriteArrayList<byte[]> list = store.get(key);
                if (list != null && !list.isEmpty()) {
                    byte[] value = fromLeft ? list.remove(0) : list.remove(list.size() - 1);
                    if (list.isEmpty()) {
                        store.remove(key);
                    }
                    List<byte[]> result = new ArrayList<>(2);
                    result.add(key.getBytes(StandardCharsets.UTF_8));
                    result.add(value);
                    return result;
                }
            }
        }
        return null;
    }

    private void unregisterWaiter(CountDownLatch latch) {
        List<CountDownLatch> global = waitQueues.get("_all_");
        if (global != null) {
            global.remove(latch);
            if (global.isEmpty()) {
                waitQueues.remove("_all_", global);
            }
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
     * @return "list"
     */
    public String type() {
        return "list";
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
        CopyOnWriteArrayList<byte[]> list = store.get(key);
        return list != null && !list.isEmpty();
    }

    /**
     * 删除整个列表。
     *
     * @param key 键
     * @return 删除成功返回 true
     */
    public boolean del(String key) {
        if (key == null) {
            return false;
        }
        CopyOnWriteArrayList<byte[]> removed = store.remove(key);
        if (removed != null) {
            // 唤醒所有在此 key 上等待的线程
            notifyWaiters(key);
            return true;
        }
        return false;
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
        waitQueues.clear();
    }

    // ==================== Internal Utilities ====================

    /**
     * 将支持负数的索引解析为实际索引。
     */
    private int resolveIndex(int index, int size) {
        if (index >= 0) {
            return index;
        }
        return size + index;
    }

    /**
     * 注册一个等待者。
     */
    private void registerWaiter(String key, CountDownLatch latch) {
        waitQueues.computeIfAbsent(key != null ? key : "_all_", k -> new CopyOnWriteArrayList<>()).add(latch);
    }

    /**
     * 通知所有等待者。
     */
    private void notifyWaiters(String key) {
        List<CountDownLatch> specificWaiters = waitQueues.remove(key);
        if (specificWaiters != null) {
            for (CountDownLatch latch : specificWaiters) {
                latch.countDown();
            }
        }
        // 也通知通配等待者
        List<CountDownLatch> globalWaiters = waitQueues.remove("_all_");
        if (globalWaiters != null) {
            for (CountDownLatch latch : globalWaiters) {
                latch.countDown();
            }
        }
    }
}
