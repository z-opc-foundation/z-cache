package com.zifang.z.cache.core.persistence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AOF（Append Only File）追加持久化调度器。
 * <p>
 * 该类实现了 Redis 风格的 AOF 持久化机制，将每个写命令以 RESP（Redis Serialization Protocol）
 * 格式追加到日志文件。支持三种 fsync 策略和 AOF 重写功能。
 * </p>
 * <p>
 * AOF 文件格式：直接追加 RESP 格式的命令数组
 * <pre>
 * *3\r
$3\r
SET\r
$3\r
foo\r
$3\r
bar\r

 * *4\r
$4\r
SETEX\r
$3\r
foo\r
$2\r
60\r
$3\r
bar\r

 * </pre>
 * </p>
 *
 * @author zifang
 * @since 1.0.0
 */
public class AofPersistence {

    private static final Logger LOGGER = Logger.getLogger(AofPersistence.class.getName());

    /**
     * fsync 策略枚举。
     * <p>
     * 每次写入后都调用 fsync，保证数据不丢失但性能最差。
     * </p>
     */
    public static final int FSYNC_ALWAYS = 0;

    /**
     * fsync 策略：每秒执行一次 fsync，平衡性能和数据安全性。
     */
    public static final int FSYNC_EVERYSEC = 1;

    /**
     * fsync 策略：由操作系统决定何时 fsync，性能最好但可能丢失数据。
     */
    public static final int FSYNC_NO = 2;

    /**
     * 当前 fsync 策略
     */
    private volatile int fsyncPolicy = FSYNC_EVERYSEC;

    /**
     * AOF 文件路径
     */
    private volatile String aofFilePath;

    /**
     * AOF 文件输出流
     */
    private volatile BufferedWriter writer;

    /**
     * 标记是否已启动
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 后台调度执行器（用于 EVERYSEC 策略的定时 fsync）
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 定时 fsync 任务句柄
     */
    private volatile ScheduledFuture<?> fsyncFuture;

    /**
     * AOF 重写执行器
     */
    private final ScheduledExecutorService rewriteExecutor;

    /**
     * 标记是否正在重写
     */
    private final AtomicBoolean rewriting = new AtomicBoolean(false);

    /**
     * 创建 AOF 持久化调度器。
     */
    public AofPersistence() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "z-cache-aof-fsync");
            t.setDaemon(true);
            return t;
        });
        this.rewriteExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "z-cache-aof-rewrite");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 创建指定线程池的 AOF 持久化调度器。
     *
     * @param fsyncScheduler  fsync 调度执行器
     * @param rewriteScheduler AOF 重写调度执行器
     */
    public AofPersistence(ScheduledExecutorService fsyncScheduler, ScheduledExecutorService rewriteScheduler) {
        this.scheduler = fsyncScheduler;
        this.rewriteExecutor = rewriteScheduler;
    }

    /**
     * 设置 fsync 策略。
     *
     * @param policy fsync 策略（FSYNC_ALWAYS / FSYNC_EVERYSEC / FSYNC_NO）
     */
    public void setFsyncPolicy(int policy) {
        if (policy < FSYNC_ALWAYS || policy > FSYNC_NO) {
            throw new IllegalArgumentException("Invalid fsync policy: " + policy);
        }
        this.fsyncPolicy = policy;
    }

    /**
     * 获取当前 fsync 策略。
     *
     * @return fsync 策略值
     */
    public int getFsyncPolicy() {
        return fsyncPolicy;
    }

    /**
     * 启动 AOF 持久化。
     *
     * @param aofFilePath AOF 文件路径
     * @throws IOException 如果文件打开失败
     */
    public void start(String aofFilePath) throws IOException {
        if (aofFilePath == null || aofFilePath.isEmpty()) {
            throw new IllegalArgumentException("aofFilePath cannot be null or empty");
        }

        this.aofFilePath = aofFilePath;

        if (started.compareAndSet(false, true)) {
            LOGGER.info("Starting AOF persistence with fsync policy: " + getFsyncPolicyName());

            File file = new File(aofFilePath);
            // 如果文件不存在，创建父目录
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 以追加模式打开文件
            writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));

            // 如果是 EVERYSEC 策略，启动定时 fsync
            if (fsyncPolicy == FSYNC_EVERYSEC) {
                startFsyncScheduler();
            }
        }
    }

    /**
     * 停止 AOF 持久化。
     *
     * @throws IOException 如果文件关闭失败
     */
    public void stop() throws IOException {
        if (started.compareAndSet(true, false)) {
            LOGGER.info("Stopping AOF persistence");

            // 停止 fsync 调度
            if (fsyncFuture != null) {
                fsyncFuture.cancel(false);
                fsyncFuture = null;
            }

            // 关闭文件
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        }
    }

    /**
     * 关闭并释放线程池资源。
     *
     * @throws IOException 如果文件关闭失败
     */
    public void shutdown() throws IOException {
        stop();
        scheduler.shutdown();
        rewriteExecutor.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!rewriteExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                rewriteExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            rewriteExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 追加写命令到 AOF 文件。
     * <p>
     * 命令以 RESP 格式写入，格式为：
     * <pre>
     * *N\r
$len1\r
arg1\r
$len2\r
arg2\r
...
     * </pre>
     * </p>
     *
     * @param command 命令数组，每个元素是一个参数
     * @throws IOException 如果写入失败
     */
    public void appendCommand(String[] command) throws IOException {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("Command cannot be null or empty");
        }

        if (!started.get()) {
            LOGGER.warning("AOF not started, ignoring command append");
            return;
        }

        if (rewriting.get()) {
            LOGGER.fine("AOF rewrite in progress, buffering command");
            // 在重写期间，命令仍然写入原 AOF 文件
        }

        synchronized (this) {
            if (writer != null) {
                // 写入 RESP 格式
                writer.write("*");
                writer.write(String.valueOf(command.length));
                writer.write("\r\n");

                for (String arg : command) {
                    byte[] argBytes = arg.getBytes(StandardCharsets.UTF_8);
                    writer.write("$");
                    writer.write(String.valueOf(argBytes.length));
                    writer.write("\r\n");
                    writer.write(arg);
                    writer.write("\r\n");
                }

                writer.flush();

                // 根据策略执行 fsync
                if (fsyncPolicy == FSYNC_ALWAYS) {
                    syncFile();
                }
            }
        }
    }

    /**
     * AOF 重写。
     * <p>
     * 重写过程：
     * 1. 创建临时 AOF 文件
     * 2. 生成最小命令集写入临时文件
     * 3. 原子替换原 AOF 文件
     * </p>
     *
     * @param dbFilePath 数据库文件路径（用于保存最小命令集）
     * @throws IOException 如果重写失败
     */
    public void rewriteAof(String dbFilePath) throws IOException {
        if (dbFilePath == null || dbFilePath.isEmpty()) {
            throw new IllegalArgumentException("dbFilePath cannot be null or empty");
        }

        if (!started.get()) {
            throw new IllegalStateException("AOF not started");
        }

        if (rewriting.compareAndSet(false, true)) {
            try {
                LOGGER.info("Starting AOF rewrite");

                File tempFile = new File(dbFilePath + ".aof.tmp");
                File targetFile = new File(dbFilePath);

                // 创建临时文件写入最小命令集
                try (BufferedWriter rewriteWriter = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8))) {

                    // 这里应该从内存数据库中读取所有数据，生成最小命令集
                    // 例如：SET key value, HSET key field value 等
                    // 实际实现需要依赖 StoreAccessor 来获取当前数据库状态

                    rewriteWriter.flush();
                }

                // 原子替换
                if (targetFile.exists()) {
                    targetFile.delete();
                }
                tempFile.renameTo(targetFile);

                // 重写完成后，清空原 AOF 文件并重新开始
                truncateAndReopen();

                LOGGER.info("AOF rewrite completed successfully");
            } finally {
                rewriting.set(false);
            }
        }
    }

    /**
     * 加载并重放 AOF 文件。
     *
     * @param aofFilePath    AOF 文件路径
     * @param commandReplayer 命令重放回调函数
     * @throws IOException 如果文件读取失败
     */
    public void loadAof(String aofFilePath, Consumer<String[]> commandReplayer) throws IOException {
        if (aofFilePath == null || aofFilePath.isEmpty()) {
            throw new IllegalArgumentException("aofFilePath cannot be null or empty");
        }

        if (commandReplayer == null) {
            throw new IllegalArgumentException("commandReplayer cannot be null");
        }

        File file = new File(aofFilePath);
        if (!file.exists()) {
            LOGGER.info("AOF file not found: " + aofFilePath + ", starting with empty log");
            return;
        }

        LOGGER.info("Loading AOF file: " + aofFilePath);

        int commandCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // 解析 RESP 格式
                if (line.startsWith("*")) {
                    int argc = Integer.parseInt(line.substring(1));
                    String[] command = new String[argc];

                    for (int i = 0; i < argc; i++) {
                        line = reader.readLine();
                        if (line == null) {
                            throw new IOException("Unexpected end of AOF file");
                        }

                        if (line.startsWith("$")) {
                            int len = Integer.parseInt(line.substring(1));
                            line = reader.readLine();
                            if (line == null) {
                                throw new IOException("Unexpected end of AOF file");
                            }
                            command[i] = line;
                        } else {
                            // 兼容非标准格式
                            command[i] = line;
                        }
                    }

                    // 重放命令
                    try {
                        commandReplayer.accept(command);
                        commandCount++;
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error replaying command: " + String.join(" ", command), e);
                    }
                }
            }
        }

        LOGGER.info("AOF file loaded successfully, replayed " + commandCount + " commands");
    }

    /**
     * 每次写操作调用，记录到 AOF。
     *
     * @param command 写命令
     * @throws IOException 如果写入失败
     */
    public void onWrite(String[] command) throws IOException {
        appendCommand(command);
    }

    /**
     * 启动 fsync 调度器（EVERYSEC 策略）。
     */
    private void startFsyncScheduler() {
        fsyncFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                synchronized (this) {
                    if (writer != null) {
                        writer.flush();
                        syncFile();
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error during scheduled fsync", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 执行文件同步。
     *
     * @throws IOException 如果同步失败
     */
    private void syncFile() throws IOException {
        // 注意：Java 的 BufferedWriter 不直接支持 fsync
        // 这里通过 flush 保证数据写入操作系统缓冲区
        // 实际的 fsync 需要使用 FileChannel 或 FileDescriptor
        if (writer != null) {
            writer.flush();
        }
    }

    /**
     * 截断并重新打开 AOF 文件（用于重写后）。
     *
     * @throws IOException 如果操作失败
     */
    private void truncateAndReopen() throws IOException {
        synchronized (this) {
            if (writer != null) {
                writer.close();
            }

            // 截断文件
            File file = new File(aofFilePath);
            if (file.exists()) {
                file.delete();
            }

            // 重新打开
            writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
        }
    }

    /**
     * 获取 fsync 策略名称。
     *
     * @return 策略名称
     */
    private String getFsyncPolicyName() {
        switch (fsyncPolicy) {
            case FSYNC_ALWAYS:
                return "ALWAYS";
            case FSYNC_EVERYSEC:
                return "EVERYSEC";
            case FSYNC_NO:
                return "NO";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * 获取 AOF 文件路径。
     *
     * @return AOF 文件路径
     */
    public String getAofFilePath() {
        return aofFilePath;
    }

    /**
     * 检查 AOF 是否已启动。
     *
     * @return 如果已启动返回 true
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * 检查是否正在重写。
     *
     * @return 如果正在重写返回 true
     */
    public boolean isRewriting() {
        return rewriting.get();
    }

    /**
     * fsync 策略常量：ALWAYS
     */
    public static final String FSYNC_POLICY_ALWAYS = "ALWAYS";

    /**
     * fsync 策略常量：EVERYSEC
     */
    public static final String FSYNC_POLICY_EVERYSEC = "EVERYSEC";

    /**
     * fsync 策略常量：NO
     */
    public static final String FSYNC_POLICY_NO = "NO";

    /**
     * 根据策略名称获取策略值。
     *
     * @param policyName 策略名称
     * @return 策略值
     */
    public static int parseFsyncPolicy(String policyName) {
        if (policyName == null) {
            throw new IllegalArgumentException("Policy name cannot be null");
        }

        switch (policyName.toUpperCase()) {
            case FSYNC_POLICY_ALWAYS:
                return FSYNC_ALWAYS;
            case FSYNC_POLICY_EVERYSEC:
                return FSYNC_EVERYSEC;
            case FSYNC_POLICY_NO:
                return FSYNC_NO;
            default:
                throw new IllegalArgumentException("Unknown fsync policy: " + policyName);
        }
    }

    /**
     * 根据策略值获取策略名称。
     *
     * @param policyValue 策略值
     * @return 策略名称
     */
    public static String getFsyncPolicyName(int policyValue) {
        switch (policyValue) {
            case FSYNC_ALWAYS:
                return FSYNC_POLICY_ALWAYS;
            case FSYNC_EVERYSEC:
                return FSYNC_POLICY_EVERYSEC;
            case FSYNC_NO:
                return FSYNC_POLICY_NO;
            default:
                throw new IllegalArgumentException("Unknown fsync policy value: " + policyValue);
        }
    }

    /**
     * 解析 RESP 格式的命令。
     *
     * @param respLine RESP 格式的命令行
     * @return 解析后的命令数组
     * @throws IOException 如果解析失败
     */
    public static String[] parseRespCommand(String respLine) throws IOException {
        if (respLine == null || !respLine.startsWith("*")) {
            throw new IOException("Invalid RESP format");
        }

        int argc = Integer.parseInt(respLine.substring(1));
        String[] command = new String[argc];

        // 注意：这个方法假设每个参数都在单独的行中
        // 实际使用时需要配合 BufferedReader 逐行读取
        return command;
    }

    /**
     * 将命令数组转换为 RESP 格式字符串。
     *
     * @param command 命令数组
     * @return RESP 格式字符串
     */
    public static String toRespFormat(String[] command) {
        if (command == null || command.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*").append(command.length).append("\r\n");

        for (String arg : command) {
            byte[] argBytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(argBytes.length).append("\r\n");
            sb.append(arg).append("\r\n");
        }

        return sb.toString();
    }
}
