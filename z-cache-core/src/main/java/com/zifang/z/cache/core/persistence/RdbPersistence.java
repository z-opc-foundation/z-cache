package com.zifang.z.cache.core.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RDB 快照持久化调度器。
 * <p>
 * 该类实现了 Redis RDB 风格的持久化机制，通过后台调度器定期将内存中的数据
 * 以二进制快照形式写入磁盘文件（dump.rdb）。支持基于时间间隔和写操作次数的
 * 双重触发策略。
 * </p>
 * <p>
 * RDB 文件格式：
 * <pre>
 * 魔数: "ZCHRDB" (6 bytes)
 * 版本: int (1)
 * 数据库数量: int
 * 对每个数据库:
 *   DB 编号: int
 *   键值对数量: int
 *   对每个键值对:
 *     数据类型标记: byte (0=string, 1=hash, 2=list, 3=set, 4=sorted_set)
 *     key: length-prefixed bytes (int length + byte[])
 *     过期时间: long (-1 表示无过期)
 *     值: 根据类型序列化
 * 结束标记: byte (0xFF)
 * 校验和: long (所有字节的 XOR)
 * </pre>
 * </p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class RdbPersistence {

    private static final Logger LOGGER = Logger.getLogger(RdbPersistence.class.getName());

    /**
     * RDB 文件魔数标识
     */
    private static final byte[] MAGIC_BYTES = "ZCHRDB".getBytes(StandardCharsets.UTF_8);

    /**
     * RDB 文件版本号
     */
    private static final int RDB_VERSION = 1;

    /**
     * 数据类型标记：字符串
     */
    private static final byte TYPE_STRING = 0;

    /**
     * 数据类型标记：Hash
     */
    private static final byte TYPE_HASH = 1;

    /**
     * 数据类型标记：List
     */
    private static final byte TYPE_LIST = 2;

    /**
     * 数据类型标记：Set
     */
    private static final byte TYPE_SET = 3;

    /**
     * 数据类型标记：Sorted Set
     */
    private static final byte TYPE_SORTED_SET = 4;

    /**
     * 结束标记
     */
    private static final byte END_MARKER = (byte) 0xFF;

    /**
     * 存储访问器，用于获取和恢复数据
     */
    private volatile StoreAccessor storeAccessor;

    /**
     * 后台调度执行器
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 调度任务句柄
     */
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * RDB 保存间隔（秒）
     */
    private volatile int saveSeconds = 300;

    /**
     * RDB 保存触发的最小写操作次数
     */
    private volatile int saveChanges = 1000;

    /**
     * 自上次保存以来的写操作计数器
     */
    private final AtomicLong writeCounter = new AtomicLong(0);

    /**
     * 标记是否已启动
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 默认 RDB 文件路径
     */
    private volatile String dbFilePath = "dump.rdb";

    /**
     * 创建 RDB 持久化调度器。
     */
    public RdbPersistence() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "z-cache-rdb-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 创建指定线程池的 RDB 持久化调度器。
     *
     * @param scheduler 调度执行器
     */
    public RdbPersistence(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 设置存储访问器。
     *
     * @param storeAccessor 存储访问器实例
     */
    public void setStoreAccessor(StoreAccessor storeAccessor) {
        this.storeAccessor = storeAccessor;
    }

    /**
     * 设置保存策略。
     *
     * @param seconds 时间间隔（秒），0 表示不启用时间触发
     * @param changes 最小写操作次数，0 表示不启用次数触发
     */
    public void setSaveStrategy(int seconds, int changes) {
        if (seconds < 0) {
            throw new IllegalArgumentException("saveSeconds cannot be negative");
        }
        if (changes < 0) {
            throw new IllegalArgumentException("saveChanges cannot be negative");
        }
        this.saveSeconds = seconds;
        this.saveChanges = changes;
    }

    /**
     * 获取写操作计数器的当前值。
     *
     * @return 写操作次数
     */
    public long getWriteCount() {
        return writeCounter.get();
    }

    /**
     * 重置写操作计数器。
     */
    public void resetWriteCounter() {
        writeCounter.set(0);
    }

    /**
     * 启动 RDB 持久化调度器。
     * <p>
     * 启动后台调度线程，定期检查是否需要触发 RDB 保存。
     * 如果已有定时任务在运行，该方法将忽略重复启动。
     * </p>
     *
     * @param dbFilePath RDB 文件保存路径
     */
    public void start(String dbFilePath) {
        if (dbFilePath == null || dbFilePath.isEmpty()) {
            throw new IllegalArgumentException("dbFilePath cannot be null or empty");
        }

        this.dbFilePath = dbFilePath;

        if (started.compareAndSet(false, true)) {
            LOGGER.info("Starting RDB persistence scheduler with save interval: " + saveSeconds + "s, changes threshold: " + saveChanges);

            scheduledFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (shouldSave()) {
                        save();
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error during RDB scheduled save", e);
                }
            }, saveSeconds, saveSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * 停止 RDB 持久化调度器。
     * <p>
     * 停止后台调度任务并关闭线程池。如果调度器未启动，该方法将忽略。
     * </p>
     */
    public void stop() {
        if (started.compareAndSet(true, false)) {
            LOGGER.info("Stopping RDB persistence scheduler");

            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
            }

            // 注意：不关闭 scheduler，因为它可能被外部管理
            // 如果需要完全关闭，可以调用 shutdown()
        }
    }

    /**
     * 关闭并释放线程池资源。
     * <p>
     * 该方法会停止调度器并关闭线程池，通常在应用关闭时调用。
     * </p>
     */
    public void shutdown() {
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 手动触发 RDB 保存。
     * <p>
     * 该方法会立即将内存数据快照保存到 RDB 文件。保存完成后重置写操作计数器。
     * </p>
     *
     * @throws IOException 如果文件写入失败
     */
    public void save() throws IOException {
        if (storeAccessor == null) {
            LOGGER.warning("StoreAccessor not set, skipping RDB save");
            return;
        }

        LOGGER.info("Starting RDB save to: " + dbFilePath);

        // 创建临时文件，写入成功后再替换原文件
        File tempFile = new File(dbFilePath + ".tmp");
        File targetFile = new File(dbFilePath);

        try {
            writeRdbFile(tempFile);
            // 原子替换
            if (targetFile.exists()) {
                targetFile.delete();
            }
            tempFile.renameTo(targetFile);
            writeCounter.set(0);
            LOGGER.info("RDB save completed successfully");
        } catch (IOException e) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            LOGGER.log(Level.SEVERE, "Failed to save RDB file", e);
            throw e;
        }
    }

    /**
     * 加载 RDB 文件恢复数据。
     *
     * @param dbFilePath RDB 文件路径
     * @throws IOException 如果文件读取失败
     */
    public void load(String dbFilePath) throws IOException {
        if (storeAccessor == null) {
            LOGGER.warning("StoreAccessor not set, skipping RDB load");
            return;
        }

        File file = new File(dbFilePath);
        if (!file.exists()) {
            LOGGER.info("RDB file not found: " + dbFilePath + ", starting with empty database");
            return;
        }

        LOGGER.info("Loading RDB file: " + dbFilePath);

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            readRdbFile(dis);
            LOGGER.info("RDB file loaded successfully");
        }
    }

    /**
     * 每次写操作调用，更新变更计数器。
     * <p>
     * 当计数器达到阈值时，如果同时满足时间条件，将触发 RDB 保存。
     * </p>
     */
    public void onWrite() {
        writeCounter.incrementAndGet();
    }

    /**
     * 判断是否应该触发保存。
     *
     * @return 如果满足保存条件返回 true
     */
    private boolean shouldSave() {
        if (storeAccessor == null) {
            return false;
        }

        // 检查写操作次数是否达到阈值
        if (saveChanges > 0 && writeCounter.get() >= saveChanges) {
            return true;
        }

        return false;
    }

    /**
     * 写入 RDB 文件。
     *
     * @param file 目标文件
     * @throws IOException 如果写入失败
     */
    private void writeRdbFile(File file) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            long checksum = 0;

            // 写入魔数
            dos.write(MAGIC_BYTES);
            checksum = updateChecksum(checksum, MAGIC_BYTES);

            // 写入版本号
            dos.writeInt(RDB_VERSION);
            checksum = updateChecksum(checksum, RDB_VERSION);

            // 获取所有数据
            Map<String, Object> stringEntries = storeAccessor.getAllStringEntries();
            Map<String, Object> hashEntries = storeAccessor.getAllHashEntries();
            Map<String, Object> listEntries = storeAccessor.getAllListEntries();
            Map<String, Object> setEntries = storeAccessor.getAllSetEntries();
            Map<String, Object> sortedSetEntries = storeAccessor.getAllSortedSetEntries();
            Map<String, Long> expirationEntries = storeAccessor.getAllExpirationEntries();

            // 计算数据库数量（这里简化为单数据库）
            int dbCount = 1;
            dos.writeInt(dbCount);
            checksum = updateChecksum(checksum, dbCount);

            // 写入数据库编号
            int dbIndex = 0;
            dos.writeInt(dbIndex);
            checksum = updateChecksum(checksum, dbIndex);

            // 计算总键值对数量
            int totalEntries = stringEntries.size() + hashEntries.size() + listEntries.size()
                    + setEntries.size() + sortedSetEntries.size();
            dos.writeInt(totalEntries);
            checksum = updateChecksum(checksum, totalEntries);

            // 写入字符串类型
            for (Map.Entry<String, Object> entry : stringEntries.entrySet()) {
                String key = entry.getKey();
                byte[] value = (byte[]) entry.getValue();
                Long expireAt = expirationEntries.getOrDefault(key, -1L);

                dos.writeByte(TYPE_STRING);
                checksum = updateChecksum(checksum, TYPE_STRING);

                checksum = writeString(dos, key, checksum);
                checksum = writeLong(dos, expireAt, checksum);
                checksum = writeBytes(dos, value, checksum);
            }

            // 写入 Hash 类型
            for (Map.Entry<String, Object> entry : hashEntries.entrySet()) {
                String key = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<byte[], byte[]> hashValue = (Map<byte[], byte[]>) entry.getValue();
                Long expireAt = expirationEntries.getOrDefault(key, -1L);

                dos.writeByte(TYPE_HASH);
                checksum = updateChecksum(checksum, TYPE_HASH);

                checksum = writeString(dos, key, checksum);
                checksum = writeLong(dos, expireAt, checksum);
                checksum = writeHash(dos, hashValue, checksum);
            }

            // 写入 List 类型
            for (Map.Entry<String, Object> entry : listEntries.entrySet()) {
                String key = entry.getKey();
                @SuppressWarnings("unchecked")
                List<byte[]> listValue = (List<byte[]>) entry.getValue();
                Long expireAt = expirationEntries.getOrDefault(key, -1L);

                dos.writeByte(TYPE_LIST);
                checksum = updateChecksum(checksum, TYPE_LIST);

                checksum = writeString(dos, key, checksum);
                checksum = writeLong(dos, expireAt, checksum);
                checksum = writeList(dos, listValue, checksum);
            }

            // 写入 Set 类型
            for (Map.Entry<String, Object> entry : setEntries.entrySet()) {
                String key = entry.getKey();
                @SuppressWarnings("unchecked")
                Set<byte[]> setValue = (Set<byte[]>) entry.getValue();
                Long expireAt = expirationEntries.getOrDefault(key, -1L);

                dos.writeByte(TYPE_SET);
                checksum = updateChecksum(checksum, TYPE_SET);

                checksum = writeString(dos, key, checksum);
                checksum = writeLong(dos, expireAt, checksum);
                checksum = writeSet(dos, setValue, checksum);
            }

            // 写入 Sorted Set 类型
            for (Map.Entry<String, Object> entry : sortedSetEntries.entrySet()) {
                String key = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<byte[], Double> sortedSetValue = (Map<byte[], Double>) entry.getValue();
                Long expireAt = expirationEntries.getOrDefault(key, -1L);

                dos.writeByte(TYPE_SORTED_SET);
                checksum = updateChecksum(checksum, TYPE_SORTED_SET);

                checksum = writeString(dos, key, checksum);
                checksum = writeLong(dos, expireAt, checksum);
                checksum = writeSortedSet(dos, sortedSetValue, checksum);
            }

            // 写入结束标记
            dos.writeByte(END_MARKER);
            checksum = updateChecksum(checksum, END_MARKER);

            // 写入校验和
            dos.writeLong(checksum);
        }
    }

    /**
     * 读取 RDB 文件恢复数据。
     *
     * @param dis 数据输入流
     * @throws IOException 如果读取失败
     */
    private void readRdbFile(DataInputStream dis) throws IOException {
        long checksum = 0;

        // 读取并验证魔数
        byte[] magic = new byte[MAGIC_BYTES.length];
        dis.readFully(magic);
        checksum = updateChecksum(checksum, magic);

        if (!java.util.Arrays.equals(magic, MAGIC_BYTES)) {
            throw new IOException("Invalid RDB file magic number");
        }

        // 读取版本号
        int version = dis.readInt();
        checksum = updateChecksum(checksum, version);

        if (version != RDB_VERSION) {
            throw new IOException("Unsupported RDB version: " + version);
        }

        // 读取数据库数量
        int dbCount = dis.readInt();
        checksum = updateChecksum(checksum, dbCount);

        // 读取每个数据库
        for (int db = 0; db < dbCount; db++) {
            int dbIndex = dis.readInt();
            checksum = updateChecksum(checksum, dbIndex);

            int entryCount = dis.readInt();
            checksum = updateChecksum(checksum, entryCount);

            for (int i = 0; i < entryCount; i++) {
                byte type = dis.readByte();
                checksum = updateChecksum(checksum, type);

                String key = readString(dis);
                checksum = updateChecksum(checksum, key.getBytes(StandardCharsets.UTF_8));

                long expireAt = dis.readLong();
                checksum = updateChecksum(checksum, expireAt);

                switch (type) {
                    case TYPE_STRING:
                        byte[] value = readBytes(dis);
                        checksum = updateChecksum(checksum, value);
                        storeAccessor.restoreString(key, value, expireAt);
                        break;

                    case TYPE_HASH:
                        Map<byte[], byte[]> hashEntries = readHash(dis);
                        checksum = updateChecksum(checksum, hashEntries);
                        storeAccessor.restoreHash(key, hashEntries, expireAt);
                        break;

                    case TYPE_LIST:
                        List<byte[]> listEntries = readList(dis);
                        checksum = updateChecksum(checksum, listEntries);
                        storeAccessor.restoreList(key, listEntries, expireAt);
                        break;

                    case TYPE_SET:
                        Set<byte[]> setEntries = readSet(dis);
                        checksum = updateChecksum(checksum, setEntries);
                        storeAccessor.restoreSet(key, setEntries, expireAt);
                        break;

                    case TYPE_SORTED_SET:
                        Map<byte[], Double> sortedSetEntries = readSortedSet(dis);
                        checksum = updateChecksumSortedSet(checksum, sortedSetEntries);
                        storeAccessor.restoreSortedSet(key, sortedSetEntries, expireAt);
                        break;

                    default:
                        throw new IOException("Unknown data type: " + type);
                }
            }
        }

        // 读取结束标记
        byte endMarker = dis.readByte();
        if (endMarker != END_MARKER) {
            throw new IOException("Invalid end marker: " + endMarker);
        }

        // 读取并验证校验和
        long fileChecksum = dis.readLong();
        if (fileChecksum != checksum) {
            throw new IOException("Checksum mismatch: expected " + fileChecksum + ", got " + checksum);
        }
    }

    // ==================== 辅助写入方法 ====================

    /**
     * 写入长度前缀的字符串。
     */
    private long writeString(DataOutputStream dos, String str, long checksum) throws IOException {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        checksum = updateChecksum(checksum, bytes.length);
        dos.writeInt(bytes.length);
        dos.write(bytes);
        return updateChecksum(checksum, bytes);
    }

    /**
     * 写入长度前缀的字节数组。
     */
    private long writeBytes(DataOutputStream dos, byte[] data, long checksum) throws IOException {
        int length = data == null ? 0 : data.length;
        checksum = updateChecksum(checksum, length);
        dos.writeInt(length);
        if (data != null && data.length > 0) {
            dos.write(data);
            checksum = updateChecksum(checksum, data);
        }
        return checksum;
    }

    /**
     * 写入 long 值。
     */
    private long writeLong(DataOutputStream dos, long value, long checksum) throws IOException {
        dos.writeLong(value);
        return updateChecksum(checksum, value);
    }

    /**
     * 写入 Hash 数据。
     */
    private long writeHash(DataOutputStream dos, Map<byte[], byte[]> hash, long checksum) throws IOException {
        int fieldCount = hash == null ? 0 : hash.size();
        checksum = updateChecksum(checksum, fieldCount);
        dos.writeInt(fieldCount);

        if (hash != null) {
            for (Map.Entry<byte[], byte[]> entry : hash.entrySet()) {
                checksum = writeBytes(dos, entry.getKey(), checksum);
                checksum = writeBytes(dos, entry.getValue(), checksum);
            }
        }
        return checksum;
    }

    /**
     * 写入 List 数据。
     */
    private long writeList(DataOutputStream dos, List<byte[]> list, long checksum) throws IOException {
        int itemCount = list == null ? 0 : list.size();
        checksum = updateChecksum(checksum, itemCount);
        dos.writeInt(itemCount);

        if (list != null) {
            for (byte[] item : list) {
                checksum = writeBytes(dos, item, checksum);
            }
        }
        return checksum;
    }

    /**
     * 写入 Set 数据。
     */
    private long writeSet(DataOutputStream dos, Set<byte[]> set, long checksum) throws IOException {
        int memberCount = set == null ? 0 : set.size();
        checksum = updateChecksum(checksum, memberCount);
        dos.writeInt(memberCount);

        if (set != null) {
            for (byte[] member : set) {
                checksum = writeBytes(dos, member, checksum);
            }
        }
        return checksum;
    }

    /**
     * 写入 Sorted Set 数据。
     */
    private long writeSortedSet(DataOutputStream dos, Map<byte[], Double> sortedSet, long checksum) throws IOException {
        int memberCount = sortedSet == null ? 0 : sortedSet.size();
        checksum = updateChecksum(checksum, memberCount);
        dos.writeInt(memberCount);

        if (sortedSet != null) {
            for (Map.Entry<byte[], Double> entry : sortedSet.entrySet()) {
                dos.writeDouble(entry.getValue());
                checksum = updateChecksum(checksum, entry.getValue());
                checksum = writeBytes(dos, entry.getKey(), checksum);
            }
        }
        return checksum;
    }

    // ==================== 辅助读取方法 ====================

    /**
     * 读取长度前缀的字符串。
     */
    private String readString(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 读取长度前缀的字节数组。
     */
    private byte[] readBytes(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        if (length == 0) {
            return new byte[0];
        }
        byte[] data = new byte[length];
        dis.readFully(data);
        return data;
    }

    /**
     * 读取 Hash 数据。
     */
    private Map<byte[], byte[]> readHash(DataInputStream dis) throws IOException {
        int fieldCount = dis.readInt();
        Map<byte[], byte[]> hash = new LinkedHashMap<>();
        for (int i = 0; i < fieldCount; i++) {
            byte[] field = readBytes(dis);
            byte[] value = readBytes(dis);
            hash.put(field, value);
        }
        return hash;
    }

    /**
     * 读取 List 数据。
     */
    private List<byte[]> readList(DataInputStream dis) throws IOException {
        int itemCount = dis.readInt();
        List<byte[]> list = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            list.add(readBytes(dis));
        }
        return list;
    }

    /**
     * 读取 Set 数据。
     */
    private Set<byte[]> readSet(DataInputStream dis) throws IOException {
        int memberCount = dis.readInt();
        Set<byte[]> set = new HashSet<>();
        for (int i = 0; i < memberCount; i++) {
            set.add(readBytes(dis));
        }
        return set;
    }

    /**
     * 读取 Sorted Set 数据。
     */
    private Map<byte[], Double> readSortedSet(DataInputStream dis) throws IOException {
        int memberCount = dis.readInt();
        Map<byte[], Double> sortedSet = new LinkedHashMap<>();
        for (int i = 0; i < memberCount; i++) {
            double score = dis.readDouble();
            byte[] member = readBytes(dis);
            sortedSet.put(member, score);
        }
        return sortedSet;
    }

    // ==================== 校验和方法 ====================

    /**
     * 使用所有字节的 XOR 作为简单校验。
     */
    private long updateChecksum(long checksum, byte[] data) {
        if (data == null) {
            return checksum;
        }
        for (byte b : data) {
            checksum ^= b;
        }
        return checksum;
    }

    /**
     * 更新 int 值的校验和。
     */
    private long updateChecksum(long checksum, int value) {
        checksum ^= (value & 0xFFFFFFFFL);
        return checksum;
    }

    /**
     * 更新 long 值的校验和。
     */
    private long updateChecksum(long checksum, long value) {
        checksum ^= value;
        return checksum;
    }

    /**
     * 更新 double 值的校验和。
     */
    private long updateChecksum(long checksum, double value) {
        long bits = Double.doubleToLongBits(value);
        checksum ^= bits;
        return checksum;
    }

    /**
     * 更新 Hash 数据的校验和。
     */
    private long updateChecksum(long checksum, Map<byte[], byte[]> hash) {
        for (Map.Entry<byte[], byte[]> entry : hash.entrySet()) {
            checksum = updateChecksum(checksum, entry.getKey());
            checksum = updateChecksum(checksum, entry.getValue());
        }
        return checksum;
    }

    /**
     * 更新 List 数据的校验和。
     */
    private long updateChecksum(long checksum, List<byte[]> list) {
        for (byte[] item : list) {
            checksum = updateChecksum(checksum, item);
        }
        return checksum;
    }

    /**
     * 更新 Set 数据的校验和。
     */
    private long updateChecksum(long checksum, Set<byte[]> set) {
        for (byte[] member : set) {
            checksum = updateChecksum(checksum, member);
        }
        return checksum;
    }

    /**
     * 更新 Sorted Set 数据的校验和。
     */
    private long updateChecksumSortedSet(long checksum, Map<byte[], Double> sortedSet) {
        for (Map.Entry<byte[], Double> entry : sortedSet.entrySet()) {
            checksum = updateChecksum(checksum, entry.getKey());
            checksum = updateChecksum(checksum, entry.getValue());
        }
        return checksum;
    }

    /**
     * 获取 RDB 文件路径。
     *
     * @return RDB 文件路径
     */
    public String getDbFilePath() {
        return dbFilePath;
    }

    /**
     * 设置 RDB 文件路径。
     *
     * @param dbFilePath RDB 文件路径
     */
    public void setDbFilePath(String dbFilePath) {
        this.dbFilePath = dbFilePath;
    }

    /**
     * 获取保存间隔（秒）。
     *
     * @return 保存间隔
     */
    public int getSaveSeconds() {
        return saveSeconds;
    }

    /**
     * 获取保存触发的最小写操作次数。
     *
     * @return 最小写操作次数
     */
    public int getSaveChanges() {
        return saveChanges;
    }

    /**
     * 检查调度器是否已启动。
     *
     * @return 如果已启动返回 true
     */
    public boolean isStarted() {
        return started.get();
    }
}
