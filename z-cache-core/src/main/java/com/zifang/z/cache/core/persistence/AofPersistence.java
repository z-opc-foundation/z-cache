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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
     * 自动重写的默认百分比门槛 —— 上游 {@code server.h:98}：
     * {@code #define AOF_REWRITE_PERC 100}（长到比底座大一倍才重写）。
     */
    public static final int AUTO_AOF_REWRITE_PERCENTAGE = 100;

    /**
     * 自动重写的默认体积地板 —— 上游 {@code server.h:99}：
     * {@code #define AOF_REWRITE_MIN_SIZE (64*1024*1024)}。
     */
    public static final long AUTO_AOF_REWRITE_MIN_SIZE = 64L * 1024 * 1024;

    /**
     * 自动挡多久量一次体积 —— 上游没有独立的定时器：{@code serverCron} 每轮自己回一个周期，
     * 那句是 {@code return 1000/server.hz}（{@code server.c:1374}），而 {@code hz} 默认 10
     * （{@code server.h:83} 的 {@code CONFIG_DEFAULT_HZ}），也就是<b>每 100ms 量一次</b>。
     * 我们用 {@code ScheduledExecutorService}，能对齐的只有这个周期，不是一条事件循环。
     */
    public static final long AUTO_REWRITE_TICK_MS = 1000L / 10;

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
     * {@link #writer} 底下那一支持有文件描述符的流 —— fsync 只能从这里要。
     * <p>
     * 之所以要单独留一份：{@code BufferedWriter}/{@code OutputStreamWriter} 都没有同步语义，
     * 而 {@code Writer.close()} 会把底层这同一个人关掉，所以它的生命周期必须和 {@link #writer}
     * 严格绑在一起（{@link #openAppending} 成对赋值、{@link #closeLiveWriter} 成对抹掉）。
     * </p>
     */
    private volatile FileOutputStream liveStream;

    /**
     * 累计写进日志的字节数 —— 对应上游的 {@code server.aof_current_size}（{@code server.h:1080}，
     * 注释原文 "AOF current size"）。
     * <p>
     * 上游有两个写入点，这一格两半都要同形：追加路径每写完一次加上写了多少
     * （{@code aof.c:466}、{@code :480}），而<em>换过一份日志之后按 stat 重取</em>而不是接着加
     * （{@code aofUpdateCurrentSize}，{@code aof.c:1653-1665}；调用点在载入收尾 {@code :865} 与
     * 重写收尾 {@code :1772}）。接着加会怎样：旧日志里那些"同一个键写三笔"的流水已经不在新文件里了，
     * 于是这个数比真实文件大一截 —— 而它是自动重写算增幅的分子（{@code server.c:1302-1311}），
     * 大一截就等于<em>提前重写</em>，小一截就等于<em>永不重写</em>。
     * </p>
     */
    private volatile long appendedBytes;

    /**
     * 重写算增幅时的那块底座 —— 对应上游的 {@code server.aof_rewrite_base_size}
     * （{@code server.h:1079}，注释原文 "AOF size on latest startup or rewrite"）。
     * <p>
     * 全上游只有三处会动它：初值 0（{@code server.c:1594}）、载入收尾 {@code aof.c:866}、
     * 重写收尾 {@code aof.c:1773}，两处收尾都是紧跟在 {@code aofUpdateCurrentSize()} 下一行，
     * 也就是"把底座对齐到刚接手／刚换出来的那份的长度"。写命令<em>不算它</em> —— 它是
     * {@code server.c:1308-1310} 那个分母（{@code growth = current*100/base - 100}），跟着写命令涨的话
     * 增幅永远是 0，自动挡就此失灵。
     * </p>
     */
    private volatile long rewriteBaseBytes;

    /**
     * 自动重写的百分比门槛 —— 对应上游的 {@code server.aof_rewrite_perc}（{@code server.h:1077}，
     * 注释原文 "Rewrite AOF if % growth is > M and..."）。默认 {@value #AUTO_AOF_REWRITE_PERCENTAGE}
     * （上游 {@code server.h:98} 的 {@code AOF_REWRITE_PERC}），<b>0 表示整个自动挡关掉</b> ——
     * {@code server.c:1305} 那一项就是把整数当真假用，0 直接短路。
     * <p>
     * 允许的范围是 {@code 0..INT_MAX}（{@code config.c:1160-1161} 那个
     * {@code config_set_numerical_field} 的第四、五个实参），负数在配置文件那条路上也被拒
     * （{@code config.c:501-503}，原文 "Invalid negative percentage for AOF auto rewrite"）。
     * 这一点值得注意：负数在上游是<em>过不了配置解析</em>，而不是"过得了但行为像 0"，
     * 所以下面那个判据用 {@code == 0} 翻译 {@code :1305}，不给负数留通道。
     * </p>
     */
    private volatile int autoRewritePercentage = AUTO_AOF_REWRITE_PERCENTAGE;

    /**
     * 自动重写的体积地板 —— 对应上游的 {@code server.aof_rewrite_min_size}
     * （{@code server.h:1078}，"the AOF file is at least N bytes"）。默认 64mb
     * （{@code server.h:99} 的 {@code AOF_REWRITE_MIN_SIZE}，即 {@code 64*1024*1024}）。
     * <p>
     * 它存在的理由不是"再省一点"：几十个字节的日志重写一次，收益是零、开销是把整份状态重导一遍
     * （本版重写不 fork，见 {@link #rewriteAsync()}，所以这笔开销是同步落在写侧的）。
     * {@code server.c:1306} 用的是严格大于 —— 正好等于地板时<em>不</em>重写。
     * 运行时口 {@code config.c:1262-1263} 收 {@code 0..LONG_MAX}。
     * </p>
     */
    private volatile long autoRewriteMinSize = AUTO_AOF_REWRITE_MIN_SIZE;

    /**
     * 自动挡那一拍的任务句柄。挂／撤的判据是 {@link #applyAutoRewriteScheduler()}，
     * 和 fsync 那把（{@link #fsyncFuture}）分开：<b>自动重写的触发与 fsync 档位无关</b> ——
     * 上游两件事分别住在 {@code aof.c:341-352}（flush）和 {@code server.c:1301-1315}（cron 里的增幅判断），
     * 把触发挂到 fsync 定时器上，等于让 {@code appendfsync always} 顺带关掉自动挡。
     */
    private volatile ScheduledFuture<?> autoRewriteFuture;

    /**
     * "本台正有一次后台快照在跑吗"的读口 —— 对应上游 {@code server.c:1303} 那一项
     * （{@code server.rdb_child_pid == -1}）。生产那一侧由 {@code RedisServer.initPersistence()}
     * 接到 {@link RdbPersistence#isBackgroundSaving()}；默认那一支恒 {@code false} 的含义是
     * <em>没人接线</em>，见 {@link #setRdbBusy(java.util.function.BooleanSupplier)}。
     */
    private volatile java.util.function.BooleanSupplier rdbBusy = () -> false;

    /**
     * 最后一次真 fsync 时的字节数 —— 对应上游的 {@code server.aof_fsync_offset}
     * （{@code aof.c:349} 判的就是这两个数不相等，{@code aof.c:506/:1774} 赋的就是这两个数）。
     * <p>
     * 换过一次日志（{@link #rewriteAof}）之后这两个数一起对齐到新文件长度：新 inode 的
     * 那一份已经整体同步过，不该被算成"还欠着"。
     * </p>
     */
    private volatile long fsyncedBytes;

    /**
     * 真的调用过 fsync 的次数，含 {@link #rewriteAof} 对新日志那一次。
     * <p>
     * 只在 {@code sync()} 返回之后自增：抛 {@code SyncFailedException}（系统保证不了落盘）时
     * 计数不动，所以这个数就是"操作系统确认过的同步次数"，不是"我们打算同步的次数"。
     * </p>
     */
    private final AtomicLong fsyncCount = new AtomicLong();

    /**
     * 标记是否已启动
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 后台调度执行器（用于 EVERYSEC 策略的定时 fsync）
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 定时 fsync 任务句柄。是否挂着由 {@link #applyFsyncScheduler()} 按<b>当前</b>档位决定 ——
     * 上游没有这个开关（它没有定时器：{@code CONFIG SET appendfsync} 只是改一个整数，
     * {@code config.c:493}，因为每轮事件循环都会重新读那个整数），我们的定时器必须自己跟上。
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
     * 设置 fsync 策略。启动之后也可以换档 —— 换档必须把"每秒那一次"跟着档位一起改，
     * 否则 {@code NO → EVERYSEC} 这一趟换上去的档位只有追加路径那半边是活的。
     *
     * @param policy fsync 策略（FSYNC_ALWAYS / FSYNC_EVERYSEC / FSYNC_NO）
     */
    public void setFsyncPolicy(int policy) {
        if (policy < FSYNC_ALWAYS || policy > FSYNC_NO) {
            throw new IllegalArgumentException("Invalid fsync policy: " + policy);
        }
        this.fsyncPolicy = policy;
        if (started.get()) {
            applyFsyncScheduler();
        }
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
            openAppending(file);
            appendedBytes = file.length();
            // 接手一份已有的日志，底座就从这一刻算起（上游同处：载入收尾 aof.c:865 取 stat、
            // :866 把 aof_rewrite_base_size 对齐到这个数）。
            rewriteBaseBytes = appendedBytes;
            fsyncedBytes = appendedBytes;   // 刚接手的这份不欠盘（上游同形：aof.c:285 / :718）

            // 如果是 EVERYSEC 策略，启动定时 fsync
            applyFsyncScheduler();
            // 自动挡那一拍与档位无关：上游的增幅判断住在 serverCron（server.c:1301-1315），
            // 而"有没有 AOF"是那一串条件里的第一个（:1302），不是"这台 cron 挂没挂"。
            applyAutoRewriteScheduler();
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
            // 自动挡也要撤：AOF 已经停了还留着那一拍，下一拍读到的是<em>关了句柄的</em>那份日志。
            // 上游同形 —— serverCron 一直在跑，但 :1302 那个 aof_state 判断会一直不成立。
            if (autoRewriteFuture != null) {
                autoRewriteFuture.cancel(false);
                autoRewriteFuture = null;
            }

            // 关闭文件。上游 stopAppendOnly 的顺序是 flush → fsync → close，而且<em>不看档位</em>
            // （{@code aof.c:236-238}：{@code flushAppendOnlyFile(1); redis_fsync(aof_fd); close(aof_fd);}），
            // 连 NO 档也要在收手前把那一段要回介质上 —— 只 flush 就 close，最后那一段停在操作系统里，
            // 干净停服看不出问题，整机掉电才丢。
            // 这一支要在锁里：cancel(false) 打断不了已经在跑的那一拍，让它对着正在关闭的 fd 要
            // fsync 只会换回一条 SyncFailedException 日志。
            synchronized (this) {
                closeLiveWriter(true);
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
            appendedBytes += writeRespCommand(writer, command);
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
    private static long writeRespCommand(java.io.Writer writer, String[] command) throws IOException {
        String count = String.valueOf(command.length);
        writer.write("*");
        writer.write(count);
        writer.write("\r\n");
        long bytes = 1 + count.length() + 2;
        for (String arg : command) {
            int argLen = arg.getBytes(StandardCharsets.UTF_8).length;
            String len = String.valueOf(argLen);
            writer.write("$");
            writer.write(len);
            writer.write("\r\n");
            writer.write(arg);
            writer.write("\r\n");
            bytes += 1 + len.length() + 2 + argLen + 2;
        }
        return bytes;
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
        checkRewriteArguments(aofFilePath);

        if (!rewriting.compareAndSet(false, true)) {
            // 上游这里回 "-ERR Background append only file rewriting already in progress"。
            // 原来这一支是"抢不到标志就什么都不做、还回一个正常的返回"—— 调用方分不清重写完没完。
            throw new IllegalStateException("AOF rewrite already in progress");
        }

        rewriteInternal(aofFilePath);
    }

    /**
     * 这一份日志现在<em>能不能</em>被重写 —— 只读，不抛。
     * <p>
     * 命令层需要它：上游 {@code bgrewriteaofCommand}（{@code aof.c:1629-1640}）除了"已经有人在重写"
     * （{@code :1630-1631}）之外不查任何档位 —— 整个函数里没有 {@code AOF_OFF} 判断，它只需要一个
     * 写得出去的路径。我们的路径只在带 dataDir 启动时才成立，所以"没配 dataDir"就是"没有一份日志
     * 可换"的对应物，那一支必须如实回错而不是默默回一个 {@code +OK}。
     * </p>
     */
    public boolean isRewriteSupported() {
        String path = aofFilePath;
        return started.get() && path != null && !path.isEmpty();
    }

    /**
     * 把重写排到 {@link #rewriteExecutor} 上，立刻回调用方 —— 上游 {@code BGREWRITEAOF} 的
     * 形状是"回一句 {@code +Background append only file rewriting started}，活在后头干"
     * （{@code aof.c:1635-1636} 打的是 {@code addReplyStatus}，是一个简单字符串而不是错误）。
     * <p>
     * 与上游的差别仍然是"不 fork"：上游由子进程写临时文件、父进程期间继续写旧日志；我们那一次
     * 重写会在 {@link #appendCommand} 的那把锁里堵住写侧。所以标志必须<em>在入队之前</em>抢：
     * 线程池里的任务什么时候排到不可知，标志晚了半步，期间进来的 {@code BGREWRITEAOF} 就会看到
     * "没人重写"而再排一份。
     * </p>
     *
     * @return {@code true} 表示已经排上；{@code false} 表示这一份日志不可重写或已经有人在重写
     */
    public boolean rewriteAsync() {
        String path = aofFilePath;
        if (!started.get() || path == null || path.isEmpty()) {
            return false;
        }
        if (!rewriting.compareAndSet(false, true)) {
            return false;
        }
        try {
            rewriteExecutor.execute(() -> {
                try {
                    // 标志的归还只有<em>一处</em>：{@link #rewriteInternal} 自己的 finally。
                    // 这里原先也还了一次，两处归还看着更保险，实际是让"忘了归还"这一种坏法
                    // 在任何一处都测不出来 —— 只留一处，谁漏了立刻看得见。
                    rewriteInternal(path);
                } catch (Throwable t) {
                    // 线程池里抛出去的东西没人接：不许静默。标志已经还了，但日志的形状
                    // 停在"重写之前那一份"，除了这一行日志之外没有任何一面看得见它失败过。
                    LOGGER.log(Level.WARNING, "Background AOF rewrite failed", t);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // 排不上就必须把标志还回去，否则这一份日志从此"永远有人在重写"。
            rewriting.set(false);
            return false;
        }
    }

    private void checkRewriteArguments(String aofFilePath) {
        if (aofFilePath == null || aofFilePath.isEmpty()) {
            throw new IllegalArgumentException("aofFilePath cannot be null or empty");
        }

        if (!started.get()) {
            throw new IllegalStateException("AOF not started");
        }
    }

    /** 调用方必须<em>已经</em>抢到 {@link #rewriting} 标志；这一支在结束时把它清掉。 */
    private void rewriteInternal(String aofFilePath) throws IOException {
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
            List<String[]> commands;
            synchronized (this) {
                // 快照必须和"换文件"取同一把锁，早一寸就丢一笔已确认的写：追加那一步是在这把锁里
                // 做的，所以一笔写只有两种落点 —— 排在快照之前（它的值必然已在表里，因为命令层先写
                // 内存再记日志），或排在 rename 之后（它进的是新日志）。快照取在锁外，中间那一段时间里
                // 客户端已经收到 +OK 的写就只落在旧 inode 上，rename 一盖就整份不见。
                // 上游 fork 不出这一段，所以把差记进 aofRewriteBuffer（aof.c:636-641），并在 rename
                // 之前并进新日志（:1680-1681 那句注释，调用点 :1692）；我们靠的是这把锁，代价就是
                // 类注释里已经写明的那一句：重写期间写侧被堵住。
                commands = exportMinimalCommandSet(accessor);
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
                // 换进来的是一份新日志，它自己也得算"到过盘"。上游同处按档位刷一次新 fd
                // （{@code aof.c:1767-1770}：ALWAYS 用 {@code redis_fsync(newfd)}，EVERYSEC 用
                // {@code aof_background_fsync(newfd)}，NO 两支都不进），随后无条件把
                // {@code aof_fsync_offset} 对齐到新大小（{@code aof.c:1774}）。
                // 我们不 fork，"后台那一次"就在调用 rewriteAof 的线程里同步做（差别记在 CHANGELOG）。
                appendedBytes = new File(aofFilePath).length();
                // 新的一份就是新的起点：上游在 backgroundRewriteDoneHandler 里换完 fd 之后
                // aofUpdateCurrentSize()（aof.c:1772）→ aof_rewrite_base_size = aof_current_size
                // （:1773）→ aof_fsync_offset = aof_current_size（:1774），三行连着走。
                rewriteBaseBytes = appendedBytes;
                if (fsyncPolicy != FSYNC_NO) {
                    syncFile();
                }
                fsyncedBytes = appendedBytes;
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
            // 第六家：stream 一族在上游那里是一支专门的函数
            // （{@code rewriteStreamObject}，{@code aof.c:1172-1266}），形状与前五家都不同 ——
            // 一条记录一条 {@code XADD}（带<em>显式 ID</em>，arity = 3 + 2×字段数，{@code :1186-1196}，
            // 不按 64 个一批），逐组 {@code XGROUP CREATE}（{@code :1227-1233}），最后无条件补一条
            // {@code XSETID}（{@code :1212-1217}，注释原文 "in case of XDEL lastid"）。
            for (Map.Entry<String, com.zifang.z.cache.core.stream.Stream> entry
                    : storeAccessor.getAllStreamEntries(db).entrySet()) {
                addStreamRecords(dbCommands, entry.getKey(), entry.getValue());
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

    /**
     * 一条流的导出形状，逐句照 {@code rewriteStreamObject}（{@code aof.c:1172-1266}）：
     * <ol>
     *   <li>有成员就<em>逐条</em> {@code XADD key <id> f v …}（{@code :1181-1197}）。这一家不套
     *       {@code REWRITE_ITEMS_PER_CMD} 那一批量：上游同一支函数里是一条记录一条命令，
     *       批量会把"每条记录一个显式 ID"这件事拆坏；</li>
     *   <li>成员为空也要留下这个键 —— 上游用的是 {@code XADD key MAXLEN 0 <last_id> x y}
     *       这一手（{@code :1198-1210}，注释原文 "Use the XADD MAXLEN 0 trick to generate an empty
     *       stream"）：先真加一条再当场裁到 0，于是"这条流存在、表顶在 {@code last_id}、长度为 0"
     *       三件事一起被演出来。少这一步，一个被 {@code XDEL} 空的流键会在重写之后<em>连键一起消失</em>；</li>
     *   <li>之后<em>无条件</em>补一条 {@code XSETID key <last_id>}（{@code :1212-1217}，注释原文
     *       "in case of XDEL lastid"）。表顶与"还活着的最大学 ID"是两件事：中间那条被删掉之后，
     *       光靠 {@code XADD} 演不出前者；</li>
     *   <li>最后逐组 {@code XGROUP CREATE key <组名> <组读数位置>}（{@code :1220-1233}）。</li>
     * </ol>
     * 上游在组之后还会替每个"手里有未确认条目"的消费者逐条
     * {@code XCLAIM … TIME … RETRYCOUNT … JUSTID FORCE}（{@code :1235-1260}，函数体 {@code :1150-1167}）。
     * 这一侧没有 {@code XCLAIM}（{@code src/main} 里零处理），所以<em>消费组的读数位置能过重写，
     * pending 表不能</em> —— 这是记账的未覆盖面，不是可以默默吞掉的差别。
     */
    private static void addStreamRecords(List<String[]> out, String key,
                                         com.zifang.z.cache.core.stream.Stream stream) {
        java.util.List<com.zifang.z.cache.core.stream.StreamEntry> entries = stream.getEntries();
        long[] lastId = stream.lastId();
        if (entries.isEmpty()) {
            out.add(new String[]{"XADD", key, "MAXLEN", "0", streamId(lastId), "x", "y"});
        } else {
            for (com.zifang.z.cache.core.stream.StreamEntry entry : entries) {
                Map<String, String> fields = entry.getFields();
                String[] record = new String[3 + fields.size() * 2];
                record[0] = "XADD";
                record[1] = key;
                record[2] = entry.getId();
                int at = 3;
                for (Map.Entry<String, String> field : fields.entrySet()) {
                    record[at++] = field.getKey();
                    record[at++] = field.getValue();
                }
                out.add(record);
            }
        }
        out.add(new String[]{"XSETID", key, streamId(lastId)});
        for (String groupName : stream.groupNames()) {
            com.zifang.z.cache.core.stream.ConsumerGroup group = stream.getGroup(groupName);
            if (group != null) {
                out.add(new String[]{"XGROUP", "CREATE", key, groupName,
                        streamId(group.getLastDeliveredId(), group.getLastDeliveredSeq())});
            }
        }
    }

    private static String streamId(long[] id) {
        return streamId(id[0], id[1]);
    }

    /**
     * 两段都是 uint64 的<b>位模式</b>，写法必须无符号：上游那一手是
     * {@code rioWriteBulkStreamID}（{@code aof.c:1136-1140}）里的
     * {@code sdscatfmt(sdsempty(),"%U-%U",id->ms,id->seq)}，而 {@code %U} 正是无符号那一款。
     * 有符号渲染会把 2^63 以上的表顶写成负号开头的一串，那种写法过不了 ID 文法，
     * 重放时被整条拒掉 —— 表顶就悄悄退回到"还活着的那条"，XSETID 白补了。
     */
    private static String streamId(long ms, long seq) {
        return com.zifang.z.cache.common.protocol.StreamIdFormat.format(ms, seq);
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
        closeLiveWriter(false);
    }

    /**
     * @param finalSync 收摊那一次要先 fsync 再关（上游 {@code stopAppendOnly} 的顺序就是
     *                  flush → fsync → close，{@code aof.c:236-238}）；换日志那一次不 fsync ——
     *                  这批字节接下来就被整份换掉了，要刷的是换进来那份（在 {@link #rewriteAof} 里）。
     */
    private void closeLiveWriter(boolean finalSync) throws IOException {
        try {
            if (writer != null) {
                if (finalSync) {
                    syncFile();  // syncFile 自己会先 flush，顺序不能倒（见它的文档）
                } else {
                    writer.flush();
                }
                writer.close();  // 同一只手关掉的：OutputStreamWriter.close() 会关掉底下的流
            }
        } finally {
            // 两只手一起松开：留着 liveStream 指向那个已经关掉的 fd，下一拍要 fsync 就是对着
            // 关掉的描述符要（SyncFailedException），而判空的写侧只看得到 writer。
            writer = null;
            liveStream = null;
        }
    }

    /**
     * 成对打开追加用的两只手：{@link #writer} 管编码，{@link #liveStream} 管文件描述符
     * （fsync 只能从后者要）。调用方必须已持有本对象锁，或在启动尚未对外的阶段。
     */
    private void openAppending(File file) throws IOException {
        FileOutputStream stream = new FileOutputStream(file, true);
        liveStream = stream;
        writer = new BufferedWriter(
                new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    }

    /** 换文件之后必须<em>追加</em>重开：截断会把刚换进来的那份最小命令集抹掉。 */
    private void reopenAppending(String path) throws IOException {
        openAppending(new File(path));
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
     * <p>
     * 每一拍先问"这一段有没有新字节"（{@code appendedBytes != fsyncedBytes}，上游同判据是
     * {@code aof_fsync_offset != aof_current_size}，{@code aof.c:349}）—— 没有就不动，
     * 免得空转的日志每秒白刷一次盘。反过来，写侧停了也必须让这一拍还能补上最后那一截：
     * 上游在 {@code aof.c:341-345} 专门写了这条注释（"用户在一秒内不再写了，页缓存里那段就得
     * 由这一拍刷下去"），所以判据是"欠着多少"而不是"这一拍里有没有写过来"。
     * </p>
     */
    private void startFsyncScheduler() {
        fsyncFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                synchronized (this) {
                    if (writer != null && fsyncPolicy == FSYNC_EVERYSEC && appendedBytes != fsyncedBytes) {
                        syncFile();
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error during scheduled fsync", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 执行文件同步 —— 这一支必须真的把日志要到介质上，不是"交给操作系统"。
     * <p>
     * {@code Writer.flush()} 只把字节交给内核，进程被杀不丢、整机掉电会丢那一段。上游在同一处
     * 调的是 {@code redis_fsync(server.aof_fd)}（{@code aof.c:503}，紧邻的注释写着 "redis_fsync is
     * defined as fdatasync() for Linux in order to avoid flushing metadata"，{@code aof.c:500-501}；
     * 宏定义 {@code config.h:92-96}：Linux 下 {@code fdatasync}，其余平台 {@code fsync}）。
     * </p>
     * <p>
     * JDK 没有 fdatasync 的对应物，只有 {@code FileDescriptor.sync()}：它的文档
     * （Corretto 8 的 {@code java/io/FileDescriptor.java:104-107}）写明 "This method returns after
     * all modified data <em>and attributes</em> of this FileDescriptor have been written to the
     * relevant device(s)"，声明在 {@code :131}（Corretto 25 的实现处注释直接写着
     * {@code fsync/equivalent}，{@code java.base/java/io/FileDescriptor.java:218}）。
     * 也就是说我们连元数据一起刷 —— 只比上游更保守，不会更松。
     * </p>
     * <p>
     * 同一份文档（{@code :119-123}）还钉住顺序："sync only affects buffers downstream of this
     * FileDescriptor"，应用侧缓冲区不先 flush 就管不到那一段 —— 所以这里 flush 在前、sync 在后，
     * 两句都不是可省的。
     * </p>
     */
    private void syncFile() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        FileOutputStream stream = liveStream;
        if (stream != null) {
            stream.getFD().sync();
            fsyncCount.incrementAndGet();
            fsyncedBytes = appendedBytes;   // 上游：server.aof_fsync_offset = server.aof_current_size
        }
    }

    /** 真的对这份日志的 fd 调过几次 fsync（含 {@link #rewriteAof} 对新日志那一次）。 */
    public long getFsyncCount() {
        return fsyncCount.get();
    }

    /**
     * 按<em>当前</em>档位决定"每秒那一次"挂不挂：EVERYSEC 才挂，ALWAYS / NO 都撤。
     * <p>
     * 判据不能只在 {@link #start} 里做一次：那样运行中换档就只剩追加路径半边生效，
     * 而 {@code NO → EVERYSEC} 换上去的档位永远不会有定时器。上游不需要这一支 —— 它没有定时器，
     * 每轮事件循环都重新读 {@code server.aof_fsync} 这一个整数（{@code config.c:493} 只是赋值）。
     * 幂等：已经挂着就不重复挂。
     * </p>
     */
    private void applyFsyncScheduler() {
        if (fsyncPolicy == FSYNC_EVERYSEC) {
            if (fsyncFuture == null) {
                startFsyncScheduler();
            }
        } else if (fsyncFuture != null) {
            fsyncFuture.cancel(false);
            fsyncFuture = null;
        }
    }

    /**
     * 把"每 100ms 量一次日志体积"那一拍挂上／撤掉。幂等，同 {@link #applyFsyncScheduler()}。
     * <p>
     * 挂在 {@link #rewriteExecutor} 上而不是 fsync 那把池上：这一拍唯一会做的<em>重活</em>就是
     * {@link #rewriteAsync()}，而那份活本来就在这条池上排队。单线程池意味着"重写正在进行时
     * 这一拍排在其后"，与上游用 {@code aof_child_pid == -1}（{@code server.c:1304}）挡重入同效。
     * </p>
     */
    private void applyAutoRewriteScheduler() {
        if (autoRewriteFuture == null) {
            autoRewriteFuture = rewriteExecutor.scheduleAtFixedRate(() -> {
                try {
                    checkAutoRewrite();
                } catch (Throwable t) {
                    // 这一拍抛出去的东西没人接：不记日志的话，"自动挡从不触发"和"每拍都在抛"
                    // 在盘面上是同一个样子。
                    LOGGER.log(Level.WARNING, "Scheduled AOF auto-rewrite check failed", t);
                }
            }, AUTO_REWRITE_TICK_MS, AUTO_REWRITE_TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 按上游那一串条件量一次：<b>这一份日志现在该不该自动重写</b>。
     * <p>
     * 逐条对 {@code server.c:1301-1315}：{@code aof_state == AOF_ON}（{@code :1302}）、
     * 没有后台保存在跑（{@code :1303} 的 {@code rdb_child_pid == -1}，对应 {@code rdbSaving}）、
     * 没有后台重写（{@code :1304} 的 {@code aof_child_pid == -1}，对应 {@link #rewriting} 那把标志）、
     * {@code aof_rewrite_perc} 非零（{@code :1305}）、当前体积<b>严格</b>大于地板（{@code :1306}），
     * 然后 {@code base = aof_rewrite_base_size ?: 1}（{@code :1308-1309}）、
     * {@code growth = current*100/base - 100}（{@code :1310}）、{@code growth >= perc}（{@code :1311}）。
     * </p>
     * <p>
     * {@code ?: 1} 那一步不是防御性编程，是<em>会改判据</em>的一步：底座为 0 时若按 0 去除，
     * 整数除法直接抛 {@code ArithmeticException}，那一拍从此只留一行日志。
     * </p>
     *
     * @param aofOn             这一份日志是否处于"开着"的状态
     * @param rdbSaving         本台是否正有一次后台快照在跑（上游 {@code rdb_child_pid} 那一项）
     * @param rewriteInProgress 是否已经有人在重写
     * @param current           当前日志体积（分子）
     * @param base              上次接手／换手时的体积（分母）
     * @param percentage        百分比门槛，0 表示关掉
     * @param minSize           体积地板
     * @return 该不该发起一次自动重写
     */
    static boolean shouldAutoRewrite(boolean aofOn, boolean rdbSaving, boolean rewriteInProgress,
                                     long current, long base, int percentage, long minSize) {
        if (!aofOn || rdbSaving || rewriteInProgress || percentage == 0 || current <= minSize) {
            return false;
        }
        long baseForGrowth = base != 0 ? base : 1;
        long growth = current * 100 / baseForGrowth - 100;
        return growth >= percentage;
    }

    /**
     * 自动挡的<em>唯一</em>触发点：量一次，该重写就真的排一次重写。
     *
     * @return 是否发起了这一趟重写
     */
    boolean checkAutoRewrite() {
        if (!shouldAutoRewrite(started.get(), rdbBusy.getAsBoolean(), rewriting.get(),
                appendedBytes, rewriteBaseBytes, autoRewritePercentage, autoRewriteMinSize)) {
            return false;
        }
        LOGGER.info("Starting automatic rewriting of AOF on growth over " + autoRewritePercentage + "%");
        return rewriteAsync();
    }

    /**
     * 把"本台有没有后台快照在跑"这一个读口交给自动挡，对应上游 {@code server.c:1303} 的
     * {@code rdb_child_pid == -1}。
     * <p>
     * 默认那一支恒为 {@code false}，而 {@code false} 在这里的含义是<em>没人接线</em>，不是
     * "问过了、当前空闲"：忘了接的话自动挡照样会在一趟 BGSAVE 中间去换日志，而盘面上看不出差别。
     * 所以生产那一侧的接线由 {@code RedisServerLifecycleTest} 的结构守卫钉着（读的是
     * {@code RedisServer.initPersistence()} 的字节，不看这里写了什么注释），测试要控制读数就打这一支。
     * </p>
     */
    public void setRdbBusy(java.util.function.BooleanSupplier busyReader) {
        this.rdbBusy = busyReader == null ? () -> false : busyReader;
    }

    /** 当前那个"后台快照在跑吗"的读口，只给判据回读接线用的。 */
    public java.util.function.BooleanSupplier getRdbBusy() {
        return rdbBusy;
    }

    /** 自动挡的百分比门槛（{@code server.aof_rewrite_perc}）。 */
    public int getAutoAofRewritePercentage() {
        return autoRewritePercentage;
    }

    /**
     * 设自动挡的百分比门槛。范围照 {@code config.c:1160-1161}：{@code 0..INT_MAX}，
     * 其中 0 表示关掉（{@code server.c:1305}）。
     *
     * @param percentage 新门槛
     */
    public void setAutoAofRewritePercentage(int percentage) {
        if (percentage < 0) {
            throw new IllegalArgumentException(
                    "Invalid negative percentage for AOF auto rewrite: " + percentage);
        }
        this.autoRewritePercentage = percentage;
    }

    /** 自动挡的体积地板（{@code server.aof_rewrite_min_size}）。 */
    public long getAutoAofRewriteMinSize() {
        return autoRewriteMinSize;
    }

    /**
     * 设自动挡的体积地板。范围照 {@code config.c:1262-1263}：{@code 0..LONG_MAX}。
     *
     * @param bytes 新地板
     */
    public void setAutoAofRewriteMinSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Invalid negative size for AOF auto rewrite: " + bytes);
        }
        this.autoRewriteMinSize = bytes;
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
     * 当前这份日志有多长 —— 对应上游的 {@code server.aof_current_size}，{@code INFO persistence} 里
     * 那个 {@code aof_current_size} 就是它（{@code server.c:3396} 声明字段、{@code :3403} 取值）。
     * <p>
     * 交回的是<em>记账值</em>而不是现 stat：上游也是这么办的（写完就加、只在接手与换完文件两处
     * 现 stat）。判据由 {@code RedisServerLifecycleTest} 拿真实文件长度对账。
     * </p>
     */
    public long getAofCurrentSize() {
        return appendedBytes;
    }

    /**
     * 自动重写算增幅的那块底座 —— 对应上游的 {@code server.aof_rewrite_base_size}，{@code INFO} 里的
     * {@code aof_base_size}（{@code server.c:3397} / {@code :3404}）。
     */
    public long getAofBaseSize() {
        return rewriteBaseBytes;
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
