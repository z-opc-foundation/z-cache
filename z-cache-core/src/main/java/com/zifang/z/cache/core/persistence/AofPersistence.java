package com.zifang.z.cache.core.persistence;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * 日志里当前的落点库号 —— 上游同名字段是 {@code server.aof_selected_db}
     * （{@code server.h:1087}，"Currently selected DB in AOF"）。
     * <p>
     * 它是<em>日志</em>的属性而不是<em>连接</em>的属性：一条连接切到 DB 3 写了东西，日志的落点
     * 就成了 3，此时另一条留在 DB 0 的连接接着写，它也必须补一条 {@code SELECT 0}，否则它的写
     * 在重放时全部落进 DB 3。上游正是这么办的：{@code feedAppendOnlyFile} 比的是
     * {@code dictid != server.aof_selected_db}（{@code aof.c:586}），命中就补 SELECT 并更新落点
     * （{@code :592}）。初值取 -1 —— {@code server.c:1602} 那句注释写着 "Make sure the first time
     * will not match"，所以启动后的第一条写会带上 {@code SELECT 0}。
     */
    private volatile int lastJournaledDb = -1;

    /**
     * 重写要导出的那份内存状态。与 {@link RdbPersistence#setStoreAccessor} 同形 ——
     * 没有它，重写就只能写出一份<em>空</em>命令集，而那份空日志接下来会盖掉真的
     * {@code appendonly.aof}（这一格改动前的实际形状）。
     */
    private volatile StoreAccessor storeAccessor;

    /**
     * 上游 {@code rewriteAppendOnlyFileRio} 一条命令最多装多少个成员（{@code server.h:100}：
     * {@code #define AOF_REWRITE_ITEMS_PER_CMD 64}）。集合类命令是变参的，全塞一条会让单条命令
     * 长度无界，所以到 64 就断成下一条。
     */
    private static final int REWRITE_ITEMS_PER_CMD = 64;

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
     * 接上要被导出的内存状态。{@link #rewriteAof(String)} 靠它算最小命令集，
     * 与 {@code RdbPersistence.setStoreAccessor} 是同一个口子（同一份
     * {@code MemoryStoreAccessor} 在 {@code RedisServer} 里两处共用）。
     */
    public void setStoreAccessor(StoreAccessor storeAccessor) {
        this.storeAccessor = storeAccessor;
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
            // 一份没人写过的日志，落点未知（上游 server.c:1602）
            lastJournaledDb = -1;

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
            // 上游 stopAppendOnly（aof.c:241）在关掉句柄时把落点抹回未知
            lastJournaledDb = -1;

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

        synchronized (this) {
            appendRawLocked(command);
        }
    }

    /**
     * 按"这条命令落在库 {@code db}"的身份追加。落点与日志当前的位置不同时才补 {@code SELECT}
     * （上游 {@code aof.c:586-592} 的同一个判据），于是同一库里的第二条写不再重复打前缀，
     * 而<em>换过一次日志</em>（{@link #rewriteAof}）之后的第一条写一定会重新打。
     * <p>
     * 判据不能是"这条连接在不在 DB 0"：日志只有一份，两条连接交替写时（一条在 DB 3、一条在
     * DB 0），留在 DB 0 的那一条补不出前缀，它的数据在重放时会全部落进 DB 3。
     * </p>
     *
     * @param db      命令作用的库号
     * @param command 命令数组，每个元素是一个参数
     * @throws IOException 如果写入失败
     */
    public void appendCommand(int db, String[] command) throws IOException {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("Command cannot be null or empty");
        }

        if (!started.get()) {
            LOGGER.warning("AOF not started, ignoring command append");
            return;
        }

        synchronized (this) {
            if (db != lastJournaledDb) {
                appendRawLocked(new String[]{"SELECT", Integer.toString(db)});
                lastJournaledDb = db;
            }
            appendRawLocked(command);
        }
    }

    /** 调用方必须已持有本对象锁。 */
    private void appendRawLocked(String[] command) throws IOException {
        if (rewriting.get()) {
            LOGGER.fine("AOF rewrite in progress, buffering command");
            // 在重写期间，命令仍然写入原 AOF 文件
        }

        if (writer != null) {
            writeRespCommand(writer, command);
            writer.flush();

            // 根据策略执行 fsync
            if (fsyncPolicy == FSYNC_ALWAYS) {
                syncFile();
            }
        }
    }

    /**
     * 一条命令的 RESP 记录形状（{@code *N} + 每个参数的 {@code $<UTF-8 字节数>}）。
     * <p>
     * 追加路径与重写路径<em>共用</em>这一支：两条路写进同一个文件，漂一寸就读不回另一寸。
     * 长度取的是字节数而不是 {@code String.length()} —— 值里可以有 {@code \r\n}，也可以有中文，
     * 读侧按声明长度取字节（见 {@link #loadAof}）。
     */
    private static void writeRespCommand(java.io.Writer writer, String[] command) throws IOException {
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
    }

    /**
     * 把当前内存状态导出成一份<em>最小命令集</em>，原子换掉日志。
     * <p>
     * 上游同处是 {@code rewriteAppendOnlyFileRio}（{@code aof.c:1299}）：逐库先补一条
     * {@code SELECT j}（{@code :1306} 那句 {@code *2\r\n$6\r\nSELECT\r\n}），空库整个跳过
     * （{@code :1308 if (dictSize(d) == 0) continue;}）；逐键按类型写 {@code SET} /
     * {@code RPUSH} / {@code SADD} / {@code HMSET} / {@code ZADD}，值之后紧跟一条
     * <b>绝对时刻</b>的 {@code PEXPIREAT}（{@code :1352-1356}）。变参命令一条最多装
     * {@code AOF_REWRITE_ITEMS_PER_CMD = 64} 个成员（{@code server.h:100}，注释 {@code :1376-1379}
     * 明说"at max … items per time"），所以 130 个成员的集合会分成三条 {@code SADD}。
     * 时刻那一栏同样不认相对时间 —— 与 13d 那一格是同一条规矩的两个落点。
     * </p>
     * <p>
     * 与上游的差别：不 fork。上游 fork 子进程写临时文件，父进程期间继续写旧日志，并把这段时间
     * 收到的写命令记进 {@code aofRewriteBuffer}，收尾时并入新日志。我们不 fork，于是整段
     * "导出 + 换文件 + 重开追加句柄"都在 {@link #appendCommand} 用的那把锁里做 —— 期间的写命令
     * 排队，不会被夹在中间丢掉。代价是重写期间写侧被堵住，换来的是不需要那份增量缓冲。
     * </p>
     * <p>
     * 换完文件必须重开：旧的 {@code FileOutputStream} 指的是那个已经被改名换掉的 inode，
     * 继续往里追加等于把命令写进一份没人会再读回来的文件。旧实现更进一步 —— 它写的是<em>空</em>
     * 命令集，然后把真日志删掉，谁调用谁抹掉整个数据集。
     * </p>
     *
     * @param aofFilePath 要被换掉的 AOF 路径（一般是 {@code <dataDir>/appendonly.aof}）
     * @throws IOException 导出、落盘或换文件失败
     */
    public void rewriteAof(String aofFilePath) throws IOException {
        if (aofFilePath == null || aofFilePath.isEmpty()) {
            throw new IllegalArgumentException("aofFilePath cannot be null or empty");
        }

        if (!started.get()) {
            throw new IllegalStateException("AOF not started");
        }

        if (!rewriting.compareAndSet(false, true)) {
            // 上游这里回 "-ERR Background append only file rewriting already in progress"。
            // 原来这一支是"抢不到标志就什么都不做、还回一个正常的返回"—— 调用方分不清重写完没完。
            throw new IllegalStateException("AOF rewrite already in progress");
        }

        try {
            LOGGER.info("Starting AOF rewrite");
            StoreAccessor accessor = storeAccessor;
            if (accessor == null) {
                // 没有状态可导，能导出的就只有"空"。而空日志接下来会盖掉真的那一份 ——
                // 这正是这一格改动前的形状，所以宁可抛，也不写一个字节。
                throw new IllegalStateException(
                        "rewriteAof 没有接上 StoreAccessor：导出不了任何东西，不许换掉现有的日志");
            }
            File tempFile = new File(aofFilePath + ".rewrite.tmp");
            List<String[]> commands = exportMinimalCommandSet(accessor);
            synchronized (this) {
                writeRecords(tempFile, commands);
                closeLiveWriter();
                boolean swapped = tempFile.renameTo(new File(aofFilePath));
                // 换没换成都要把追加句柄接回去：留着 writer == null，之后每条写命令都会在
                // appendRawLocked 的判空里静默丢掉，日志从此不再记任何东西而一个字都不报。
                // 换失败时接的还是原来那一份（它没被动过），换成功时接的是新写的那一份。
                reopenAppending(aofFilePath);
                if (!swapped) {
                    throw new IOException("AOF rewrite: cannot rename " + tempFile + " onto " + aofFilePath);
                }
                // 日志整个换过一份，"当前落点"就不再是已知的位置了。上游在 rename 收尾处做的
                // 正是这一件事：{@code server.aof_selected_db = -1;}（{@code aof.c:1771}，
                // 注释原文 "Make sure SELECT is re-issued"）。不抹的话，重写后第一条落在 DB 0
                // 的写补不出 SELECT，重放时会被演进上一段落进 DB 3 的那个位置。
                lastJournaledDb = -1;
            }
            LOGGER.info("AOF rewrite completed successfully: " + commands.size() + " commands");
        } finally {
            rewriting.set(false);
        }
    }

    /**
     * 逐库逐键导出。返回的是"重放一遍就能把当前状态演回来"的那组命令，不是历史流水。
     * <p>
     * 与上游的一处必要差别：上游（{@code aof.c:1314} 起的 {@code while((de = dictNext(di)) != NULL)}）
     * 是<em>一库里顺着哈希表的走法逐键</em>问类型，所以同一库里的键型是交错的；我们这里按类型
     * 分五趟走（字符串、hash、list、set、zset）。落进去的<em>记录集合</em>一致，只有先后不同，
     * 而 AOF 里键与键之间没有任何顺序语义 —— 所以两侧的判据都不许钉键的先后。
     */
    private List<String[]> exportMinimalCommandSet(StoreAccessor storeAccessor) {
        List<String[]> commands = new ArrayList<>();
        for (int db = 0; db < storeAccessor.getDbCount(); db++) {
            List<String[]> dbCommands = new ArrayList<>();
            Map<String, Long> expirations = storeAccessor.getAllExpirationEntries(db);

            for (Map.Entry<String, Object> entry : storeAccessor.getAllStringEntries(db).entrySet()) {
                byte[] value = (byte[]) entry.getValue();
                dbCommands.add(new String[]{"SET", entry.getKey(), new String(value, StandardCharsets.UTF_8)});
                addExpiryRecord(dbCommands, expirations, entry.getKey());
            }
            for (Map.Entry<String, Object> entry : storeAccessor.getAllHashEntries(db).entrySet()) {
                @SuppressWarnings("unchecked")
                Map<byte[], byte[]> fields = (Map<byte[], byte[]>) entry.getValue();
                List<String> pairs = new ArrayList<>(fields.size() * 2);
                for (Map.Entry<byte[], byte[]> field : fields.entrySet()) {
                    pairs.add(new String(field.getKey(), StandardCharsets.UTF_8));
                    pairs.add(new String(field.getValue(), StandardCharsets.UTF_8));
                }
                addChunked(dbCommands, "HMSET", entry.getKey(), pairs, 2);
                addExpiryRecord(dbCommands, expirations, entry.getKey());
            }
            for (Map.Entry<String, Object> entry : storeAccessor.getAllListEntries(db).entrySet()) {
                @SuppressWarnings("unchecked")
                List<byte[]> elements = (List<byte[]>) entry.getValue();
                addChunked(dbCommands, "RPUSH", entry.getKey(), texts(elements), 1);
                addExpiryRecord(dbCommands, expirations, entry.getKey());
            }
            for (Map.Entry<String, Object> entry : storeAccessor.getAllSetEntries(db).entrySet()) {
                @SuppressWarnings("unchecked")
                java.util.Collection<byte[]> members = (java.util.Collection<byte[]>) entry.getValue();
                addChunked(dbCommands, "SADD", entry.getKey(), texts(members), 1);
                addExpiryRecord(dbCommands, expirations, entry.getKey());
            }
            for (Map.Entry<String, Object> entry : storeAccessor.getAllSortedSetEntries(db).entrySet()) {
                @SuppressWarnings("unchecked")
                Map<byte[], Double> memberScores = (Map<byte[], Double>) entry.getValue();
                List<String> pairs = new ArrayList<>(memberScores.size() * 2);
                for (Map.Entry<byte[], Double> member : memberScores.entrySet()) {
                    // 上游写的是 %.17Lg（rioWriteBulkDouble）；这里用项目里唯一那把 double 文法尺，
                    // 与回复侧同源，读回的那一条 ZADD 才不会漂。
                    pairs.add(com.zifang.z.cache.common.protocol.RedisDoubleFormat.format(member.getValue()));
                    pairs.add(new String(member.getKey(), StandardCharsets.UTF_8));
                }
                addChunked(dbCommands, "ZADD", entry.getKey(), pairs, 2);
                addExpiryRecord(dbCommands, expirations, entry.getKey());
            }

            if (!dbCommands.isEmpty()) {
                commands.add(new String[]{"SELECT", Integer.toString(db)});
                commands.addAll(dbCommands);
            }
        }
        return commands;
    }

    /**
     * 一条命令装 {@code REWRITE_ITEMS_PER_CMD} 个成员（hash / zset 是"对"，其它是单个成员）。
     * 上游的批量形状：{@code rewriteListObject}（{@code aof.c:1029}）到 {@code rewriteHashObject}
     * （{@code :1107}）都是"count 到 64 就起一条新命令"。
     */
    private static void addChunked(List<String[]> out, String verb, String key, List<String> args, int argsPerItem) {
        if (args.isEmpty()) {
            return;
        }
        int itemsPerCommand = REWRITE_ITEMS_PER_CMD * argsPerItem;
        for (int from = 0; from < args.size(); from += itemsPerCommand) {
            int to = Math.min(args.size(), from + itemsPerCommand);
            List<String> chunk = args.subList(from, to);
            String[] record = new String[2 + chunk.size()];
            record[0] = verb;
            record[1] = key;
            for (int i = 0; i < chunk.size(); i++) {
                record[2 + i] = chunk.get(i);
            }
            out.add(record);
        }
    }

    /** 空集合键没有成员，也就不会生成任何命令 —— 只有时刻表里的孤行不该被导出成一条 PEXPIREAT。 */
    private static void addExpiryRecord(List<String[]> out, Map<String, Long> expirations, String key) {
        Long expireAt = expirations.get(key);
        if (expireAt != null && expireAt > 0) {
            out.add(new String[]{"PEXPIREAT", key, Long.toString(expireAt)});
        }
    }

    private static List<String> texts(java.util.Collection<byte[]> values) {
        List<String> texts = new ArrayList<>(values.size());
        for (byte[] value : values) {
            texts.add(new String(value, StandardCharsets.UTF_8));
        }
        return texts;
    }

    /** 最小命令集落成一份完整的 RESP 日志（与追加路径同一支编码器）。 */
    private static void writeRecords(File file, List<String[]> commands) throws IOException {
        try (BufferedWriter rewriteWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String[] command : commands) {
                writeRespCommand(rewriteWriter, command);
            }
            rewriteWriter.flush();
        }
    }

    private void closeLiveWriter() throws IOException {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    /** 换文件之后必须<em>追加</em>重开：截断会把刚换进来的那份最小命令集抹掉。 */
    private void reopenAppending(String path) throws IOException {
        writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(new File(path), true), StandardCharsets.UTF_8));
    }

    /**
     * 加载并重放 AOF 文件。
     * <p>
     * 读取是<b>按声明长度取字节</b>的，不是按行取的：写侧记录的 {@code $<len>} 是 UTF-8 字节数，
     * 而值里完全可以含 {@code \r\n}（SET 的合法取值）。逐行读会把这种值从第一个换行处切断，
     * 剩下半截还会被当成下一条命令的开头去解析。
     * </p>
     * <p>
     * 尾部被截断（掉电时最后一条命令只写了一半）只丢弃那一条，前面的照常重放 —— 与 Redis 一致。
     * </p>
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

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            while (true) {
                byte[] headerBytes = readRawLine(in);
                if (headerBytes == null) {
                    break;
                }
                String header = new String(headerBytes, StandardCharsets.UTF_8);
                if (header.trim().isEmpty()) {
                    continue;
                }
                if (!header.startsWith("*")) {
                    LOGGER.warning("Malformed AOF record near \"" + header + "\", stopping replay here");
                    break;
                }

                int argc;
                try {
                    argc = Integer.parseInt(header.substring(1).trim());
                } catch (NumberFormatException e) {
                    LOGGER.warning("Malformed AOF multi-bulk header \"" + header + "\", stopping replay here");
                    break;
                }

                String[] command;
                try {
                    command = readCommand(in, argc);
                } catch (EOFException truncated) {
                    LOGGER.warning("AOF tail is truncated, dropping the last incomplete command"
                            + " (replayed " + commandCount + " commands before it)");
                    break;
                }

                try {
                    commandReplayer.accept(command);
                    commandCount++;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error replaying command: " + String.join(" ", command), e);
                }
            }
        }

        LOGGER.info("AOF file loaded successfully, replayed " + commandCount + " commands");
    }

    /** 读出一条命令的 argc 个参数；任何一处提前 EOF 都抛 {@link EOFException} 交由调用方判截断。 */
    private static String[] readCommand(DataInputStream in, int argc) throws IOException {
        String[] command = new String[argc];
        for (int i = 0; i < argc; i++) {
            byte[] lengthLine = readRawLine(in);
            if (lengthLine == null) {
                throw new EOFException("Unexpected end of AOF inside a command");
            }
            String header = new String(lengthLine, StandardCharsets.UTF_8);
            if (!header.startsWith("$")) {
                throw new EOFException("Expected a bulk string, got: " + header);
            }
            int length = Integer.parseInt(header.substring(1).trim());
            if (length < 0) {
                throw new EOFException("Negative bulk length in AOF: " + length);
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            readRawLine(in);
            command[i] = new String(payload, StandardCharsets.UTF_8);
        }
        return command;
    }

    /**
     * 读到 {@code \r\n} 为止，返回<b>原始字节</b>（不含行尾），行首即撞上 EOF 时返回 null。
     * <p>
     * 按字节而不是按字符收：值里的中文在 UTF-8 下是多字节，{@code (char) b} 会把每个字节变成
     * 一个 Latin-1 字符，再编码回去就不是原来那几个字节了。也不做 trim —— AOF 里的值是二进制
     * 安全的字节串，首尾空格属于数据。
     */
    private static byte[] readRawLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next != '\n' && next != -1) {
                    throw new EOFException("Expected LF after CR in AOF");
                }
                return buf.toByteArray();
            }
            buf.write(c);
        }
        return buf.size() == 0 ? null : buf.toByteArray();
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
