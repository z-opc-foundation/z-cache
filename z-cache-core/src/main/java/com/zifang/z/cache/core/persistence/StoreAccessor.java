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
 *   <li>每个方法都带数据库编号：服务对外承诺 16 个库，快照必须覆盖全部库，
 *       否则 SELECT 3 之后写入的数据在重启后静默消失</li>
 * </ul>
 * </p>
 *
 * @author zifang
 * @since 1.0.0
 */
public interface StoreAccessor {

    /**
     * 服务配置的数据库数量。快照写侧据此逐库导出，读侧据此校验文件里的库号是否越界。
     *
     * @return 数据库数量，合法库号为 [0, getDbCount())
     */
    int getDbCount();

    // ==================== 数据导出方法 ====================

    /**
     * 获取指定库中所有字符串类型的键值对。
     *
     * @param db 数据库编号
     * @return 字符串键值对映射，key 为键名，value 为 byte[] 类型的值
     */
    Map<String, Object> getAllStringEntries(int db);

    /**
     * 获取指定库中所有 Hash 类型的键值对。
     *
     * @param db 数据库编号
     * @return Hash 键值对映射，key 为键名，value 为 Map&lt;byte[], byte[]&gt; 类型的字段-值映射
     */
    Map<String, Object> getAllHashEntries(int db);

    /**
     * 获取指定库中所有 List 类型的键值对。
     *
     * @param db 数据库编号
     * @return List 键值对映射，key 为键名，value 为 List&lt;byte[]&gt; 类型的元素列表
     */
    Map<String, Object> getAllListEntries(int db);

    /**
     * 获取指定库中所有 Set 类型的键值对。
     *
     * @param db 数据库编号
     * @return Set 键值对映射，key 为键名，value 为 Set&lt;byte[]&gt; 类型的成员集合
     */
    Map<String, Object> getAllSetEntries(int db);

    /**
     * 获取指定库中所有 Sorted Set 类型的键值对。
     *
     * @param db 数据库编号
     * @return Sorted Set 键值对映射，key 为键名，value 为 Map&lt;byte[], Double&gt; 类型的成员-分值映射
     */
    Map<String, Object> getAllSortedSetEntries(int db);

    /**
     * 获取指定库中所有键的过期时间信息。
     *
     * @param db 数据库编号
     * @return 过期时间映射，key 为键名，value 为过期时间戳（毫秒），无过期时间的键不出现在结果里
     */
    Map<String, Long> getAllExpirationEntries(int db);

    // ==================== 数据导入方法 ====================

    /**
     * 向指定库恢复字符串类型的键值对。
     *
     * @param db       数据库编号
     * @param key      键名
     * @param value    值（字节数组）
     * @param expireAt 过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreString(int db, String key, byte[] value, long expireAt);

    /**
     * 向指定库恢复 Hash 类型的键值对。
     *
     * @param db       数据库编号
     * @param key      键名
     * @param entries  字段-值映射，字段和值均为字节数组
     * @param expireAt 过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreHash(int db, String key, Map<byte[], byte[]> entries, long expireAt);

    /**
     * 向指定库恢复 List 类型的键值对。
     *
     * @param db       数据库编号
     * @param key      键名
     * @param entries  元素列表，元素为字节数组
     * @param expireAt 过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreList(int db, String key, List<byte[]> entries, long expireAt);

    /**
     * 向指定库恢复 Set 类型的键值对。
     *
     * @param db       数据库编号
     * @param key      键名
     * @param members  成员集合，成员为字节数组
     * @param expireAt 过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreSet(int db, String key, Set<byte[]> members, long expireAt);

    /**
     * 向指定库恢复 Sorted Set 类型的键值对。
     *
     * @param db            数据库编号
     * @param key           键名
     * @param memberScores  成员-分值映射，成员为字节数组，分值为 double
     * @param expireAt      过期时间戳（毫秒），-1 表示无过期时间
     */
    void restoreSortedSet(int db, String key, Map<byte[], Double> memberScores, long expireAt);
}
