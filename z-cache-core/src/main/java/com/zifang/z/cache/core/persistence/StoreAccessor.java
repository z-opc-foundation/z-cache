package com.zifang.z.cache.core.persistence;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据访问接口，用于持久化模块与存储引擎之间的解耦。
 * <p>
 * 该接口提供了统一的数据访问方法，使得持久化模块（RDB、AOF）能够
 * 与具体的存储实现（如 MemoryStore）进行交互，而无需直接依赖具体实现类。
 * </p>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>遵循接口隔离原则，只暴露持久化所需的操作</li>
 *   <li>支持所有 Redis 数据结构的序列化和反序列化</li>
 *   <li>所有数据以字节数组形式传递，保持二进制安全性</li>
 * </ul>
 * </p>
 *
 * @author zifang
 * @since 1.0.0
 */
public interface StoreAccessor {

    // ==================== 数据导出方法 ====================

    /**
     * 获取所有字符串类型的键值对。
     *
     * @return 字符串键值对映射，key 为键名，value 为 byte[] 类型的值
     */
    Map<String, Object> getAllStringEntries();

    /**
     * 获取所有 Hash 类型的键值对。
     *
     * @return Hash 键值对映射，key 为键名，value 为 Map&lt;byte[], byte[]&gt; 类型的字段-值映射
     */
    Map<String, Object> getAllHashEntries();

    /**
     * 获取所有 List 类型的键值对。
     *
     * @return List 键值对映射，key 为键名，value 为 List&lt;byte[]&gt; 类型的元素列表
     */
    Map<String, Object> getAllListEntries();

    /**
     * 获取所有 Set 类型的键值对。
     *
     * @return Set 键值对映射，key 为键名，value 为 Set&lt;byte[]&gt; 类型的成员集合
     */
    Map<String, Object> getAllSetEntries();

    /**
     * 获取所有 Sorted Set 类型的键值对。
     *
     * @return Sorted Set 键值对映射，key 为键名，value 为 Map&lt;byte[], Double&gt; 类型的成员-分值映射
     */
    Map<String, Object> getAllSortedSetEntries();

    /**
     * 获取所有键的过期时间信息。
     *
     * @return 过期时间映射，key 为键名，value 为过期时间戳（毫秒），-1 表示无过期时间
     */
    Map<String, Long> getAllExpirationEntries();

    // ==================== 数据导入方法 ====================

    /**
     * 恢复字符串类型的键值对。
     *
     * @param key      键名
     * @param value    值（字节数组）
     * @param expireAt 过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreString(String key, byte[] value, long expireAt);

    /**
     * 恢复 Hash 类型的键值对。
     *
     * @param key      键名
     * @param entries  字段-值映射，字段和值均为字节数组
     * @param expireAt 过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreHash(String key, Map<byte[], byte[]> entries, long expireAt);

    /**
     * 恢复 List 类型的键值对。
     *
     * @param key      键名
     * @param entries  元素列表，元素为字节数组
     * @param expireAt 过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreList(String key, List<byte[]> entries, long expireAt);

    /**
     * 恢复 Set 类型的键值对。
     *
     * @param key      键名
     * @param members  成员集合，成员为字节数组
     * @param expireAt 过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreSet(String key, Set<byte[]> members, long expireAt);

    /**
     * 恢复 Sorted Set 类型的键值对。
     *
     * @param key          键名
     * @param memberScores 成员-分值映射，成员为字节数组，分值为 double
     * @param expireAt     过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreSortedSet(String key, Map<byte[], Double> memberScores, long expireAt);
}
