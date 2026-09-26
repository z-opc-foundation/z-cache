package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.logging.SlowLog;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.3.5 修掉的那批" advertised 但没接线 / 参数形状不合 Redis "的命令，走真实 Netty bind + RESP 往返。
 * <p>
 * 这一类缺陷单测看不见：SlowLog 的静态字段以前只有测试赋过值，服务器里恒为 null，
 * 而 {@code CommandHandler} 分发末尾读它的那几行也就恒不执行 —— 直接 new CommandHandler
 * 的单测反倒全绿。所以判据一律从 socket 这一侧拿。
 */
class RedisServerProtocolSemanticsTest {

    private static final long DEADLINE_MS = 8_000L;

    /**
     * SLOWLOG 必须真在跑着的服务器上可用，且阈值是真的在筛。
     * <p>
     * 旧行为：{@code SLOWLOG GET} 恒为 {@code -ERR SlowLog not configured}。
     * 阈值刻意留到 100ms（只有 {@code DEBUG SLEEP} 越线），并在测量前先热两条命令再清账 ——
     * 否则首条命令的类加载开销都可能被记进去，判定就随机器负载漂了。
     */
    @Test
    void slowLogIsWiredIntoTheRunningServer() throws Exception {
        SlowLog previous = CommandHandler.getSlowLog();
        CommandHandler.setSlowLog(null);
        System.setProperty("zcache.slowlog-log-slower-than", "100");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "SLOWLOG", "GET");
            assertFalse(readReply(in).startsWith("-ERR"), "SLOWLOG GET 必须可用，不再是未配置");

            send(socket, "PING");
            assertEquals("+PONG", readReply(in));
            send(socket, "SLOWLOG", "RESET");
            assertEquals("+OK", readReply(in));
            send(socket, "SLOWLOG", "LEN");
            assertEquals(":0", readReply(in), "没跑过慢命令时账上应该是空的");

            // 单位是<b>秒</b>（250 实测 DEBUG SLEEP 0.5 睡半秒、DEBUG SLEEP 1e3 把对岸挂了一千秒）。
            // 这一支以前写的是 "400"，在"毫秒"的错读法下刚好睡 400ms 越过 100ms 阈值而全绿 ——
            // 改成秒以后它要睡 400 秒，于是这条测试把那个错读法钉在了红线上。
            send(socket, "DEBUG", "SLEEP", "0.4");
            assertEquals("+OK", readReply(in));

            send(socket, "SLOWLOG", "LEN");
            assertEquals(":1", readReply(in), "超过阈值的 DEBUG SLEEP 必须被记账");

            send(socket, "SLOWLOG", "GET");
            String entry = readReplyDeep(in);
            assertTrue(entry.contains("DEBUG, SLEEP"), "记录的应是那条慢命令本身: " + entry);

            send(socket, "DEBUG", "SLOWLOG-RESET");
            assertEquals("+OK", readReply(in), "DEBUG SLOWLOG-RESET 回 +OK，不再回 :1");
            send(socket, "SLOWLOG", "LEN");
            assertEquals(":0", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
            System.clearProperty("zcache.slowlog-log-slower-than");
            CommandHandler.setSlowLog(previous);
        }
    }

    /**
     * INFO 报的端口必须是这条连接真实连上的端口，版本必须与启动日志同一把尺。
     * <p>
     * {@code RedisServer(port)} 单参构造器写死 6379，{@code ZCacheServerMain} 又自己算一次版本，
     * 于是 {@code bind(0)} 与多实例下 INFO 说的端口根本没人监听。
     */
    @Test
    void infoReportsThePortAndVersionActuallyInUse() throws Exception {
        int port = freePort();
        assertNotEquals(6379, port, "端口必须与写死的默认值不同，否则这条断言是空跑");
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "INFO", "server");
            String info = readReply(in);
            assertTrue(info.contains("tcp_port:" + port + "\r"),
                    "INFO 的 tcp_port 必须是真实监听端口 " + port + "，实际: " + info);
            assertTrue(info.contains("z-cache_version:" + CommandHandler.serverVersion() + "\r"),
                    "INFO 的版本必须与启动日志同一把尺 (" + CommandHandler.serverVersion() + ")，实际: " + info);
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * BRPOPLPUSH 的三参数形状：取源尾、推目标头、把值回给客户端；空则阻塞，超时回 nil。
     * <p>
     * 旧实现把它转成 3 参数的 RPOPLPUSH，于是永远回 {@code -ERR wrong number of arguments}。
     */
    @Test
    void brpoplpushMovesElementsAndTimesOutLikeRedis() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port); Socket peer = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "RPUSH", "sem:bp:src", "a");
            assertEquals(":1", readReply(in));
            send(socket, "BRPOPLPUSH", "sem:bp:src", "sem:bp:dst", "1");
            assertEquals("a", readReply(in), "BRPOPLPUSH 要把搬走的值回给客户端");
            send(socket, "LRANGE", "sem:bp:dst", "0", "-1");
            assertEquals("[a]", readReplyDeep(in));
            send(socket, "LLEN", "sem:bp:src");
            assertEquals(":0", readReply(in));

            // 阻塞路径：源空着发起，等另一条连接补上
            send(socket, "BRPOPLPUSH", "sem:bp:src", "sem:bp:dst", "4");
            sendLater(peer, 150L, "LPUSH", "sem:bp:src", "z");
            assertEquals("z", readReply(in), "源为空时必须睡到有人推入");
            send(socket, "LLEN", "sem:bp:src");
            assertEquals(":0", readReply(in));
            send(socket, "LRANGE", "sem:bp:dst", "0", "-1");
            assertEquals("[z, a]", readReplyDeep(in), "两次都要推到目标头部");

            // 超时：Redis 语义是 nil bulk，不是错误，也不是 *-1
            send(socket, "BRPOPLPUSH", "sem:bp:empty", "sem:bp:dst", "1");
            assertEquals("$-1", readReply(in));

            send(socket, "BRPOPLPUSH", "sem:bp:src", "sem:bp:dst");
            assertTrue(readReply(in).startsWith("-ERR wrong number"), "参数不足必须报 arity");
            send(socket, "BRPOPLPUSH", "sem:bp:src", "sem:bp:dst", "abc");
            assertEquals("-ERR timeout is not an integer or out of range", readReply(in));
            send(socket, "BRPOPLPUSH", "sem:bp:src", "sem:bp:dst", "-1");
            assertEquals("-ERR timeout is negative", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 同名键下集合类型必须真的被 String 覆盖掉（Redis 的 dbOverwrite），且 SET 的 NX/XX
     * 问的是"这个键在不在"，不是"string 命名空间里有没有"。
     */
    @Test
    void writingAStringOverwritesTheCollectionUnderTheSameKey() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "HSET", "sem:overwrite", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "EXISTS", "sem:overwrite");
            assertEquals(":1", readReply(in), "hash 键也算存在");
            send(socket, "TYPE", "sem:overwrite");
            assertEquals("+hash", readReply(in));

            send(socket, "SET", "sem:overwrite", "now-a-string", "NX");
            assertEquals("$-1", readReply(in), "键存在（只是类型不同），NX 不该成功");
            send(socket, "TYPE", "sem:overwrite");
            assertEquals("+hash", readReply(in), "NX 失败不能把原值删了");

            send(socket, "SET", "sem:overwrite", "now-a-string", "XX");
            assertEquals("+OK", readReply(in));
            send(socket, "TYPE", "sem:overwrite");
            assertEquals("+string", readReply(in), "SET 之后旧 hash 必须整体消失，否则同一键两种类型并存");
            send(socket, "HGET", "sem:overwrite", "f");
            assertTrue(readReply(in).startsWith("-WRONGTYPE"), "覆盖后 hash 域不该还读得到");
            send(socket, "DBSIZE");
            assertEquals(":1", readReply(in), "覆盖后的键只能算一个");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * WATCH 的乐观锁：五种数据类型的写入都得能让事务中止；不相干的写入不能中止；没改动则提交。
     * <p>
     * 旧实现两个洞叠在一起：{@code TransactionManager.getCurrentVersion} 写死返回 0，
     * 服务器里又从没有子类覆盖它；版本号只在 {@code MemoryStore.putDb} 记，也就是只有 String 写会 bump。
     * 合起来的净效果与"乐观锁"正好相反：{@code WATCH h} + 别人 {@code HSET h f v} 之后
     * EXEC 照样提交，而一个从没被 WATCH 过的键被 String 写过之后 EXEC 反倒中止。
     */
    @Test
    void watchSeesWritesFromEveryDataType() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket mine = connect(port); Socket other = connect(port)) {
            DataInputStream in = new DataInputStream(mine.getInputStream());
            DataInputStream oin = new DataInputStream(other.getInputStream());

            // 阴性对照一：不相干的键被写，事务照常提交（否则"中止"这个结论是白来的）
            assertTrue(startWatchedTransaction(mine, in, "sem:watch:string"), "MULTI/SET 入队失败");
            send(other, "SET", "sem:watch:unrelated", "1");
            assertEquals("+OK", readReply(oin), "对照写入本身要成功");
            send(mine, "EXEC");
            assertEquals("[+OK]", readReplyDeep(in), "不相干键的写入不该中止事务");

            // 阳性对照：hash / list / set / zset / string 五种写入各自都要中止。
            // 每族用不同的键 —— 这个实现目前没有跨集合类型的 WRONGTYPE，同键先 hash 再 LPUSH
            // 会两型并存，判据就不干净了。
            String[][] foreignWrites = {
                    {"HSET", "sem:watch:hash", "f", "v"},
                    {"LPUSH", "sem:watch:list", "v"},
                    {"SADD", "sem:watch:set", "v"},
                    {"ZADD", "sem:watch:zset", "1", "v"},
                    {"SET", "sem:watch:string", "plain-string"},
            };
            for (String[] write : foreignWrites) {
                assertTrue(startWatchedTransaction(mine, in, write[1]), "MULTI/SET 入队失败");
                send(other, write);
                assertFalse(readReply(oin).startsWith("-"), "对照写入本身要成功: " + java.util.Arrays.toString(write));
                send(mine, "EXEC");
                assertEquals("*-1", readReply(in),
                        "WATCH 之后被 " + java.util.Arrays.toString(write) + " 改动，EXEC 必须中止");
            }

            // 阴性对照二：什么都没动过则提交，且结果照常返回数组
            assertTrue(startWatchedTransaction(mine, in, "sem:watch:untouched"), "MULTI/SET 入队失败");
            send(mine, "EXEC");
            assertEquals("[+OK]", readReplyDeep(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 版本号的命名空间必须含库号：旧代码里 WATCH 落在 db 3、别人在 db 0 写同名键就能把事务打掉。
     */
    @Test
    void watchIsScopedToTheDatabaseItWasSetOn() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket mine = connect(port); Socket other = connect(port)) {
            DataInputStream in = new DataInputStream(mine.getInputStream());
            DataInputStream oin = new DataInputStream(other.getInputStream());

            send(mine, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(other, "SELECT", "0");
            assertEquals("+OK", readReply(oin));

            assertTrue(startWatchedTransaction(mine, in, "sem:watch:cross-db"), "MULTI/SET 入队失败");
            send(other, "SET", "sem:watch:cross-db", "in-db-0");
            assertEquals("+OK", readReply(oin));
            send(mine, "EXEC");
            assertEquals("[+OK]", readReplyDeep(in), "另一个库的同名键不该中止这个库的事务");

            // 反向对照：同一个库里写同名键，必须中止
            send(other, "SELECT", "3");
            assertEquals("+OK", readReply(oin));
            assertTrue(startWatchedTransaction(mine, in, "sem:watch:cross-db"), "MULTI/SET 入队失败");
            send(other, "SET", "sem:watch:cross-db", "in-db-3");
            assertEquals("+OK", readReply(oin));
            send(mine, "EXEC");
            assertEquals("*-1", readReply(in), "同库的写入必须中止事务");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * XTRIM 只认 {@code MAXLEN [~|=] count}；其余形状一律如实报错。
     * <p>
     * 旧实现把"第 3 个参数"当成 count，于是文档里写的 {@code XTRIM key MAXLEN ~ 1000}
     * 报 value is not an integer，而 Redis 拒绝的裸 {@code XTRIM key 5} 反倒被接受。
     */
    @Test
    void xtrimTakesTheRedisArgumentShape() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            for (int i = 0; i < 5; i++) {
                send(socket, "XADD", "sem:trim", "1-" + (i + 1), "f", "v" + i);
                assertFalse(readReply(in).startsWith("-"), "XADD 失败");
            }
            send(socket, "XLEN", "sem:trim");
            assertEquals(":5", readReply(in));

            send(socket, "XTRIM", "sem:trim", "MAXLEN", "~", "3");
            assertEquals(":2", readReply(in), "XTRIM key MAXLEN ~ 3 必须裁到 3");
            send(socket, "XLEN", "sem:trim");
            assertEquals(":3", readReply(in));

            send(socket, "XTRIM", "sem:trim", "MAXLEN", "=", "1");
            assertEquals(":2", readReply(in));
            send(socket, "XLEN", "sem:trim");
            assertEquals(":1", readReply(in));

            send(socket, "XTRIM", "sem:trim", "MAXLEN", "0");
            assertEquals(":1", readReply(in), "MAXLEN 0 在 Redis 里是清空，不是不动");
            send(socket, "XLEN", "sem:trim");
            assertEquals(":0", readReply(in));

            send(socket, "XTRIM", "sem:trim", "3");
            assertTrue(readReply(in).startsWith("-"), "裸计数不是 Redis 的语法，不能裁成功");
            send(socket, "XTRIM", "sem:trim", "MAXLEN");
            assertTrue(readReply(in).startsWith("-ERR wrong number"), "缺 count 要报 arity");
            send(socket, "XTRIM", "sem:trim", "MAXLEN", "3", "4");
            assertEquals("-ERR syntax error", readReply(in));
            send(socket, "XTRIM", "sem:trim", "MAXLEN", "-1");
            assertEquals("-ERR MAXLEN requires a non-negative integer", readReply(in));
            send(socket, "XTRIM", "sem:trim", "MINID", "3");
            assertTrue(readReply(in).startsWith("-ERR unsupported XTRIM strategy"),
                    "没实现的策略不能当成 MAXLEN 蒙过去");

            // XADD 的 MAXLEN 是同一套语法。以前只认 "MAXLEN 5" / "MAXLEN ~ 5"，
            // Redis 合法的 "MAXLEN = 3" 抛出未捕获的 NumberFormatException，
            // 客户端收到的是 "-ERR internal error: For input string: \"=\""。
            send(socket, "XADD", "sem:trim", "2-1", "f", "w0");
            assertEquals("2-1", readReply(in));
            send(socket, "XADD", "sem:trim", "MAXLEN", "=", "1", "2-2", "f", "w1");
            assertEquals("2-2", readReply(in));
            send(socket, "XLEN", "sem:trim");
            assertEquals(":1", readReply(in), "XADD MAXLEN = 1 应把流裁到 1");
            send(socket, "XADD", "sem:trim", "MAXLEN", "~", "5", "2-3", "f", "w2");
            assertEquals("2-3", readReply(in));
            send(socket, "XADD", "sem:trim", "MAXLEN", "not-a-number", "2-4", "f", "w3");
            assertEquals("-ERR value is not an integer or out of range", readReply(in));
            send(socket, "XADD", "sem:trim", "MAXLEN");
            assertTrue(readReply(in).startsWith("-ERR wrong number"), "MAXLEN 缺 count 不能越界取参数");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * XPENDING 的汇总形式要给出每个消费者的真实待确认数。
     * <p>
     * 旧实现填的是 {@code consumer.getPendingCount()} —— 那个字段从没自增过，恒为 0。
     * 现在按 pending 表算，账上为 0 的消费者也不再被抹掉。
     */
    @Test
    void xpendingReportsRealPerConsumerCounts() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XGROUP", "CREATE", "sem:pending", "workers", "$", "MKSTREAM");
            assertEquals("+OK", readReply(in));

            send(socket, "XADD", "sem:pending", "1-1", "item", "a");
            String first = readReply(in);
            send(socket, "XADD", "sem:pending", "1-2", "item", "b");
            assertFalse(readReply(in).startsWith("-"));

            send(socket, "XREADGROUP", "GROUP", "workers", "c1", "STREAMS", "sem:pending", ">");
            String delivered = readReplyDeep(in);
            assertTrue(delivered.contains("[item, a]") && delivered.contains("[item, b]"),
                    "两条都应投递给 c1: " + delivered);

            send(socket, "XPENDING", "sem:pending", "workers");
            String summary = readReplyDeep(in);
            assertTrue(summary.contains("[c1, 2]"), "两个待确认都应记在 c1 名下: " + summary);

            send(socket, "XACK", "sem:pending", "workers", first);
            assertEquals(":1", readReply(in));
            send(socket, "XPENDING", "sem:pending", "workers");
            assertTrue(readReplyDeep(in).contains("[c1, 1]"), "XACK 之后计数要回落");

            // 详情形式（IDLE / start / end / count）没实现，必须如实报错而不是静默给汇总
            send(socket, "XPENDING", "sem:pending", "workers", "-", "+", "10");
            assertTrue(readReply(in).startsWith("-ERR"), "未实现的详情形式要报错");

            // 组不存在要回 -NOGROUP：回空数组的话客户端把"查不到"读成"0 条待确认"
            send(socket, "XPENDING", "sem:pending", "nosuchgroup");
            String missing = readReply(in);
            assertTrue(missing.startsWith("-NOGROUP"), "组不存在要报 NOGROUP: " + missing);
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * XINFO CONSUMERS 以前在文档注释里有、switch 里没有这个 case，
     * 所以 {@code XINFO CONSUMERS key group} 永远回 {@code -ERR syntax error}。
     */
    @Test
    void xinfoConsumersRepliesWithConsumerRows() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XGROUP", "CREATE", "sem:consumers", "pool", "$", "MKSTREAM");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "sem:consumers", "1-1", "task", "x");
            readReply(in);
            send(socket, "XREADGROUP", "GROUP", "pool", "w1", "STREAMS", "sem:consumers", ">");
            readReplyDeep(in);

            send(socket, "XINFO", "CONSUMERS", "sem:consumers", "pool");
            String rows = readReplyDeep(in);
            assertTrue(rows.contains("name, w1"), "要列出消费者: " + rows);
            assertTrue(rows.contains("pending, :1"), "待确认数要取真值: " + rows);
            assertTrue(rows.contains("idle, "), "idle 字段要在: " + rows);

            send(socket, "XINFO", "CONSUMERS", "sem:no-such-stream", "pool");
            assertTrue(readReply(in).startsWith("-ERR NOGROUP"), "不存在的流要如实报 NOGROUP");
            send(socket, "XINFO", "CONSUMERS", "sem:consumers");
            assertTrue(readReply(in).startsWith("-ERR wrong number"), "缺组名要报 arity");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 广告过但没有实现的命令不能"收下参数、回个 +OK、什么都不做"。
     * <p>
     * {@code CLIENT KILL} 尤其危险：客户端据此以为对端连接已被切断，而它好端端活着。
     * {@code DEBUG OBJECT} 回的是拿哈希值扮地址、refcount/lru 全是常量的假遥测。
     */
    @Test
    void unimplementedCommandsRefuseInsteadOfPretending() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "SET", "sem:victim", "1");
            assertEquals("+OK", readReply(in));

            send(socket, "CLIENT", "KILL", "127.0.0.1:1234");
            assertTrue(readReply(in).startsWith("-ERR"), "杀不到人要说 No such client，不能回 +OK");
            send(socket, "GET", "sem:victim");
            assertEquals("1", readReply(in), "报完错之后这条连接还得能用");

            send(socket, "CLIENT", "NO-EVICT", "ON");
            assertTrue(readReply(in).startsWith("-ERR"), "没有淘汰豁免通道就不能装作打开了它");
            send(socket, "CLIENT", "NO-EVICT", "MAYBE");
            assertEquals("-ERR syntax error", readReply(in), "参数照样要校验");

            send(socket, "DEBUG", "OBJECT", "sem:victim");
            assertTrue(readReply(in).startsWith("-ERR"),
                    "DEBUG OBJECT 的 refcount/lru 在 JVM 里量不出来，不能报常量");
            send(socket, "DEBUG", "SEGFAULT");
            assertTrue(readReply(in).startsWith("-ERR"), "危险的调试子命令不能存在");

            send(socket, "XREAD", "BLOCK", "10", "STREAMS", "sem:nope", "$");
            assertTrue(readReply(in).startsWith("-ERR"), "XREAD 的 BLOCK 没接线，不能静默不阻塞");
            send(socket, "XREADGROUP", "GROUP", "g", "c", "BLOCK", "10", "STREAMS", "sem:nope", ">");
            assertTrue(readReply(in).startsWith("-ERR"), "XREADGROUP 同理");

            send(socket, "CLIENT");
            assertTrue(readReply(in).startsWith("-ERR wrong number"), "裸 CLIENT 要报 arity");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /** CLIENT INFO 的 multi= 要反映事务队列的真实长度。 */
    @Test
    void clientInfoReportsQueuedTransactionLength() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "CLIENT", "INFO");
            String idle = readReply(in);
            assertTrue(idle.contains("multi=-1"), "不在事务里时 multi=-1: " + idle);

            send(socket, "MULTI");
            assertEquals("+OK", readReply(in));
            send(socket, "SET", "sem:q1", "1");
            assertEquals("+QUEUED", readReply(in));
            send(socket, "CLIENT", "INFO");
            assertEquals("+QUEUED", readReply(in), "事务里的命令要入队，不能立刻执行");
            send(socket, "EXEC");
            String results = readReplyDeep(in);
            assertTrue(results.contains("multi=2"), "multi= 应是排队中的命令数，实际: " + results);
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 浮点回复的形状与事务错误的文案，逐条按 250 上一次性 redis-server 4.0.9 参考实例量到的原文钉住。
     * <p>
     * 形状这一族以前有四份各自 trimming 的副本，同一个值能给出三种答案：
     * {@code ZRANGE … WITHSCORES} 把整数分数回成 {@code 1.0}（参考实现回 {@code 1}）、
     * {@code +inf} 成员回成 {@code "+inf"}（参考实现回 {@code inf}）、
     * {@code HINCRBYFLOAT} 存进 hash 的那串又是第三种写法。
     * 事务这一族则是每条消息带两个 {@code ERR}：实测到的原文是
     * {@code -ERR ERR no transaction in progress}，而参考实现是 {@code -ERR EXEC without MULTI}。
     */
    @Test
    void doubleRepliesAndTransactionErrorsMatchTheReference() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 分数一族（d2string：%.17g + 削尾零）
            send(socket, "ZADD", "sem:dbl", "1", "int");
            assertEquals(":1", readReply(in));
            send(socket, "ZSCORE", "sem:dbl", "int");
            assertEquals("1", readReply(in), "整数分数回 1，不是 1.0");
            send(socket, "ZRANGE", "sem:dbl", "0", "-1", "WITHSCORES");
            assertEquals("[int, 1]", readReplyDeep(in));
            send(socket, "ZADD", "sem:dbl", "0.1", "tenth");
            assertEquals(":1", readReply(in));
            send(socket, "ZSCORE", "sem:dbl", "tenth");
            assertEquals("0.10000000000000001", readReply(in), "double 的 %.17g 就是这么打的");
            send(socket, "ZADD", "sem:dbl", "inf", "hi");
            assertEquals(":1", readReply(in));
            send(socket, "ZSCORE", "sem:dbl", "hi");
            assertEquals("inf", readReply(in), "参考实现回 inf，不是 +inf");
            send(socket, "ZADD", "sem:dbl", "-inf", "lo");
            assertEquals(":1", readReply(in));
            send(socket, "ZSCORE", "sem:dbl", "lo");
            assertEquals("-inf", readReply(in));

            // humanReadable 一族：回复与存进 hash 的字节在参考实现里逐例相同
            send(socket, "HINCRBYFLOAT", "sem:hdbl", "f", "0.1");
            assertEquals("0.1", readReply(in));
            send(socket, "HGET", "sem:hdbl", "f");
            assertEquals("0.1", readReply(in), "存进去的那串必须与回复同一形状");
            send(socket, "HINCRBYFLOAT", "sem:hdbl", "f", "1.0");
            assertEquals("1.1", readReply(in));

            // 事务错误文案：每条只加一次 ERR
            send(socket, "EXEC");
            assertEquals("-ERR EXEC without MULTI", readReply(in));
            send(socket, "DISCARD");
            assertEquals("-ERR DISCARD without MULTI", readReply(in));
            send(socket, "MULTI");
            assertEquals("+OK", readReply(in));
            send(socket, "MULTI");
            assertEquals("-ERR MULTI calls can not be nested", readReply(in));
            send(socket, "WATCH", "sem:dbl");
            assertEquals("-ERR WATCH inside MULTI is not allowed", readReply(in));
            send(socket, "DISCARD");
            assertEquals("+OK", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * EXEC 与 DISCARD 都要 flush 掉 WATCH 记录（Redis 就是这样的）。
     * <p>
     * {@code TransactionManager.resetContext} 以前刻意"保留 WATCH 信息"，于是上一条事务里
     * WATCH 过的键永久挂在这条连接上：只要它后来被任何人写过一次，这条连接之后每个 EXEC
     * 都比出不一致而中止 —— 看起来像随机失败，根子却在一条早就结束的事务上。
     */
    @Test
    void execAndDiscardFlushTheWatches() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket mine = connect(port); Socket other = connect(port)) {
            DataInputStream in = new DataInputStream(mine.getInputStream());
            DataInputStream oin = new DataInputStream(other.getInputStream());

            // 事务一：WATCH 后被改动，中止
            assertTrue(startWatchedTransaction(mine, in, "sem:flush:a"), "MULTI/SET 入队失败");
            send(other, "SET", "sem:flush:a", "by-other");
            assertEquals("+OK", readReply(oin));
            send(mine, "EXEC");
            assertEquals("*-1", readReply(in), "被改动过就该中止");

            // 事务二：只 WATCH 新键。若上一条的 a 还挂在账上，EXEC 会被"a 早就变过"打回
            assertTrue(startWatchedTransaction(mine, in, "sem:flush:b"), "MULTI/SET 入队失败");
            send(mine, "EXEC");
            assertEquals("[+OK]", readReplyDeep(in), "EXEC 之后旧 WATCH 必须已经作废");

            // DISCARD 同理
            assertTrue(startWatchedTransaction(mine, in, "sem:flush:c"), "MULTI/SET 入队失败");
            send(other, "SET", "sem:flush:c", "by-other");
            assertEquals("+OK", readReply(oin));
            send(mine, "DISCARD");
            assertEquals("+OK", readReply(in));
            assertTrue(startWatchedTransaction(mine, in, "sem:flush:d"), "MULTI/SET 入队失败");
            send(mine, "EXEC");
            assertEquals("[+OK]", readReplyDeep(in), "DISCARD 之后旧 WATCH 也必须作废");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * CLIENT LIST 要列出所有活着的连接，并且订阅数是这条连接的真值。
     * <p>
     * 旧实现只拼发起者自己那一条，而且写死 {@code sub=0 psub=0} —— 那不是"没量"，
     * 而是永远量不到：订阅中的连接被 pubsub 闸门挡住，根本执行不了 CLIENT，
     * 所以能从 socket 上看到 sub 的只有旁观者。
     */
    @Test
    void clientListShowsEveryLiveConnectionWithRealSubscriptionCounts() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket subscriber = connect(port); Socket observer = connect(port)) {
            DataInputStream sin = new DataInputStream(subscriber.getInputStream());
            DataInputStream oin = new DataInputStream(observer.getInputStream());

            send(subscriber, "CLIENT", "SETNAME", "sem-subscriber");
            assertEquals("+OK", readReply(sin));
            send(subscriber, "SUBSCRIBE", "sem:news");
            // 确认包的第 3 个数是"这条连接目前共有几个频道+模式"，不是"本条命令里的第几个"
            assertEquals("[subscribe, sem:news, :1]", readReplyDeep(sin));
            send(subscriber, "PSUBSCRIBE", "sem:news:*");
            assertEquals("[psubscribe, sem:news:*, :2]", readReplyDeep(sin));

            send(observer, "CLIENT", "LIST");
            String list = readReply(oin);
            String subscriberLine = lineFor(list, subscriber.getLocalPort());
            assertNotNull(subscriberLine, "旁观者的 CLIENT LIST 里必须能看到那条订阅连接:\n" + list);
            assertTrue(subscriberLine.contains("sub=1"), "订阅数要量出来，不再是写死的 0: " + subscriberLine);
            assertTrue(subscriberLine.contains("psub=1"), "模式订阅数同上: " + subscriberLine);
            assertTrue(subscriberLine.contains("name=sem-subscriber"), "SETNAME 起的名字要在: " + subscriberLine);
            assertTrue(subscriberLine.contains("cmd=psubscribe"), "cmd= 要反映最后处理的命令: " + subscriberLine);

            String observerLine = lineFor(list, observer.getLocalPort());
            assertNotNull(observerLine, "自己那条也要在列表里:\n" + list);
            assertTrue(observerLine.contains("sub=0") && observerLine.contains("psub=0"),
                    "没订阅的连接不能沾上别人的计数: " + observerLine);
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * CLIENT KILL 必须真的切断目标连接。
     * <p>
     * 旧实现是"收下参数、回 +OK、什么都不做"：调用方据此认为对端已被踢掉，而对端好端端活着。
     */
    @Test
    void clientKillClosesTheTargetConnection() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket killer = connect(port); Socket victim = connect(port); Socket byId = connect(port)) {
            DataInputStream kin = new DataInputStream(killer.getInputStream());
            DataInputStream vin = new DataInputStream(victim.getInputStream());
            DataInputStream bin = new DataInputStream(byId.getInputStream());

            send(victim, "SET", "sem:kill:1", "v");
            assertEquals("+OK", readReply(vin));
            send(killer, "CLIENT", "KILL", "127.0.0.1:" + victim.getLocalPort());
            assertEquals("+OK", readReply(kin), "按 ip:port 形式要能杀到人");
            assertEquals(-1, vin.read(), "被杀的那条连接必须真的收到 EOF");

            send(byId, "CLIENT", "ID");
            long id = Long.parseLong(readReply(bin).substring(1));
            send(killer, "CLIENT", "KILL", "ID", String.valueOf(id));
            assertEquals("+OK", readReply(kin), "按 ID 形式也要能杀到人");
            assertEquals(-1, bin.read(), "同样要真的断开");

            // 杀手自己还得活着
            send(killer, "PING");
            assertEquals("+PONG", readReply(kin));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 一个键名下任何时刻只有一种类型：类型不对一律 WRONGTYPE。
     * <p>
     * 修之前整个 {@code CommandHandler} 里 {@code WRONGTYPE} 一次都没出现过（grep 计数 0）：
     * {@code HGET} 一个 string 键回 nil（与"域不存在"分不出来），{@code LPUSH} 一个 hash 键
     * 还成功 —— 于是同一个键名下并存 hash 与 list 两份数据，{@code TYPE} 只报一种、
     * {@code DBSIZE} 把它算成两个键、{@code DEL} 只删得掉一种。
     */
    @Test
    void aKeyHoldsExactlyOneTypeAtATime() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "HSET", "sem:one-type", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "LPUSH", "sem:one-type", "x");
            assertTrue(readReply(in).startsWith("-WRONGTYPE"), "hash 键不能被 list 命令写");
            send(socket, "SADD", "sem:one-type", "x");
            assertTrue(readReply(in).startsWith("-WRONGTYPE"), "hash 键不能被 set 命令写");
            send(socket, "LLEN", "sem:one-type");
            assertTrue(readReply(in).startsWith("-WRONGTYPE"), "读也一样要拦，否则回 0 看着像空列表");
            send(socket, "TYPE", "sem:one-type");
            assertEquals("+hash", readReply(in));
            send(socket, "DBSIZE");
            assertEquals(":1", readReply(in), "这个键名只能占一个位置");

            // 与类型无关的键空间命令不该被闸门误伤
            send(socket, "EXISTS", "sem:one-type");
            assertEquals(":1", readReply(in));
            send(socket, "DEL", "sem:one-type");
            assertEquals(":1", readReply(in));
            send(socket, "DBSIZE");
            assertEquals(":0", readReply(in), "DEL 之后一种类型都不该留下");
            send(socket, "HGET", "sem:one-type", "f");
            assertEquals("$-1", readReply(in), "键已经不存在，这次该回 nil 而不是 WRONGTYPE");
            send(socket, "TYPE", "sem:one-type");
            assertEquals("+none", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * {@code RENAME} 是"目标键整个被源键顶掉"，不是"把源键并进目标键"。
     * <p>
     * {@code handleRename} 的五条类型分支里，只有 String 那一条走 {@code setDb}（会经
     * {@code MemoryStore.putDb} 的 {@code clearOtherTypes} 抹掉旧值）。四条集合分支是
     * {@code hgetall(src) → del(src) → hmset(dst, m)}：dst 原本有内容时被原样保留。
     * 于是 {@code HSET d old 1; HSET s f 1; RENAME s d} 之后 dst 有两个域，Redis 只有 src 那一个；
     * 跨类型更糟 —— dst 是 hash、src 是 list 时 list 写进 listStore 而 hashStore 里的旧 hash 还在，
     * {@code TYPE} 报一种、另一张表里的数据读不出来也删不掉，正是 1.3.5 类型闸门想消灭的
     * "同一键名并存两种类型"，而 RENAME 是它现成的生产者。
     */
    @Test
    void renameReplacesTheDestinationInsteadOfMergingIntoIt() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 阳性对照：dst 本来不存在时 rename 一直是对的。这条不红，上面那些红才说明问题在 dst。
            send(socket, "HSET", "rn:ctrl:src", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "RENAME", "rn:ctrl:src", "rn:ctrl:dst");
            assertEquals("+OK", readReply(in));
            send(socket, "HGET", "rn:ctrl:dst", "f");
            assertEquals("v", readReply(in));
            send(socket, "EXISTS", "rn:ctrl:src");
            assertEquals(":0", readReply(in), "rename 之后源键必须消失");

            // 同类型：dst 上原有的成员必须跟着旧值一起消失
            send(socket, "HSET", "rn:hash:dst", "old", "1");
            assertEquals(":1", readReply(in));
            send(socket, "HSET", "rn:hash:src", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "RENAME", "rn:hash:src", "rn:hash:dst");
            assertEquals("+OK", readReply(in));
            send(socket, "HLEN", "rn:hash:dst");
            assertEquals(":1", readReply(in), "dst 该只剩 src 带过来的那一个域");
            send(socket, "HEXISTS", "rn:hash:dst", "old");
            assertEquals(":0", readReply(in), "旧域不该还在");

            send(socket, "RPUSH", "rn:list:dst", "old");
            assertEquals(":1", readReply(in));
            send(socket, "RPUSH", "rn:list:src", "a", "b");
            assertEquals(":2", readReply(in));
            send(socket, "RENAME", "rn:list:src", "rn:list:dst");
            assertEquals("+OK", readReply(in));
            send(socket, "LRANGE", "rn:list:dst", "0", "-1");
            assertEquals("[a, b]", readReplyDeep(in), "旧元素不能排在前面（Redis 是整体替换）");

            send(socket, "SADD", "rn:set:dst", "old");
            assertEquals(":1", readReply(in));
            send(socket, "SADD", "rn:set:src", "m");
            assertEquals(":1", readReply(in));
            send(socket, "RENAME", "rn:set:src", "rn:set:dst");
            assertEquals("+OK", readReply(in));
            send(socket, "SMEMBERS", "rn:set:dst");
            assertEquals("[m]", readReplyDeep(in));

            send(socket, "ZADD", "rn:zset:dst", "1", "old");
            assertEquals(":1", readReply(in));
            send(socket, "ZADD", "rn:zset:src", "5", "z");
            assertEquals(":1", readReply(in));
            send(socket, "RENAME", "rn:zset:src", "rn:zset:dst");
            assertEquals("+OK", readReply(in));
            send(socket, "ZCARD", "rn:zset:dst");
            assertEquals(":1", readReply(in));
            send(socket, "ZSCORE", "rn:zset:dst", "old");
            assertEquals("$-1", readReply(in), "旧成员的分数不该跟着一起活下来");

            // 跨类型：dst 是 hash、src 是 list ⇒ 只能剩 list，旧 hash 整份消失
            send(socket, "HSET", "rn:cross:dst", "old", "1");
            assertEquals(":1", readReply(in));
            send(socket, "LPUSH", "rn:cross:src", "a");
            assertEquals(":1", readReply(in));
            send(socket, "RENAME", "rn:cross:src", "rn:cross:dst");
            assertEquals("+OK", readReply(in));
            send(socket, "TYPE", "rn:cross:dst");
            assertEquals("+list", readReply(in));
            send(socket, "HLEN", "rn:cross:dst");
            assertTrue(readReply(in).startsWith("-WRONGTYPE"), "旧 hash 若还留在另一张表里，这条会回 :1");
            send(socket, "LRANGE", "rn:cross:dst", "0", "-1");
            assertEquals("[a]", readReplyDeep(in));
            send(socket, "DBSIZE");
            assertEquals(":6", readReply(in), "到这里共 6 个键名，跨类型的键不能被算成两个");
            send(socket, "DEL", "rn:cross:dst");
            assertEquals(":1", readReply(in));
            send(socket, "EXISTS", "rn:cross:dst");
            assertEquals(":0", readReply(in), "DEL 之后两种类型都不该留下");

            // RENAMENX 的判据也要看得到集合键：dst 是 hash 时同样得回 0 且不动 src
            send(socket, "HSET", "rn:nx:dst", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "SET", "rn:nx:src", "keep");
            assertEquals("+OK", readReply(in));
            send(socket, "RENAMENX", "rn:nx:src", "rn:nx:dst");
            assertEquals(":0", readReply(in), "dst 存在（只是不是 string），RENAMENX 不该动手");
            send(socket, "GET", "rn:nx:src");
            assertEquals("keep", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * {@code RENAME} 把源键的 TTL 一起搬过去，目标键原来带没带过期都要以源键为准。
     * <p>
     * String 分支本来就抄了 {@code pttlDb} → {@code pexpireDb}，这一条钉住它别在改替换语义时被顺手丢掉；
     * 顺带确认覆盖集合键那一支（走 {@code setDb}）不会把旧 hash 留在另一张表里。
     */
    @Test
    void renameMovesTheSourceTtlToTheDestination() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 阳性对照：没设过期的键 TTL 是 -1，"设了过期才 >0"这一判据才有意义
            send(socket, "SET", "rn:ttl:plain", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "TTL", "rn:ttl:plain");
            assertEquals(":-1", readReply(in));

            send(socket, "SET", "rn:ttl:src", "v", "EX", "100");
            assertEquals("+OK", readReply(in));
            send(socket, "HSET", "rn:ttl:dst", "old", "1");
            assertEquals(":1", readReply(in));
            send(socket, "RENAME", "rn:ttl:src", "rn:ttl:dst");
            assertEquals("+OK", readReply(in));
            send(socket, "TYPE", "rn:ttl:dst");
            assertEquals("+string", readReply(in));
            send(socket, "TTL", "rn:ttl:dst");
            long ttl = Long.parseLong(readReply(in).substring(1));
            assertTrue(ttl > 0 && ttl <= 100, "源键的 TTL 要跟着搬过来，实得 " + ttl);
            send(socket, "HGET", "rn:ttl:dst", "old");
            assertTrue(readReply(in).startsWith("-WRONGTYPE"), "被顶掉的旧 hash 不该还能读");
            send(socket, "EXISTS", "rn:ttl:src");
            assertEquals(":0", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * MONITOR 每一行都要能按 Redis 的形状被机器读：RESP 简单串（'+'），
     * 形如 {@code <秒>.<6 位微秒> [<db> <ip:port>] "CMD" "arg" …}。
     * <p>
     * 注册与转发这条链本身是通的（本轮实测拿得到行），形状有四处不对：推的是 bulk string
     * （按行读的客户端会把长度行当内容、随后错位）、小数位写死 {@code .000000}、
     * db 与地址之间多塞了一个 channel hashCode、地址用的是 {@code InetSocketAddress.toString()}
     * 因而带一个 Java 特有的前导斜杠。改成简单串之后还多一道必须一起补的：参数里的裸换行
     * 会把这一行劈成两行（bulk 有长度前缀所以原来不炸），Redis 的做法是转义。
     */
    @Test
    void monitorLinesUseTheRedisWireFormat() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket mon = connect(port); Socket peer = connect(port)) {
            DataInputStream min = new DataInputStream(mon.getInputStream());
            DataInputStream pin = new DataInputStream(peer.getInputStream());

            send(mon, "MONITOR");
            assertEquals("+OK", readReply(min));

            long before = System.currentTimeMillis();
            send(peer, "SET", "mon:key", "v1");
            assertEquals("+OK", readReply(pin));
            long after = System.currentTimeMillis();

            String raw = readWireReply(min);
            // 阳性对照：别人那条命令确实推到了这条连接上（形状的问题留给后面几条判据）
            String line = raw.startsWith("+") ? raw.substring(1) : raw;
            assertTrue(line.contains("\"SET\" \"mon:key\" \"v1\""),
                    "MONITOR 该收到别的连接这条命令与参数，实得 " + raw);

            assertTrue(raw.startsWith("+"), "MONITOR 行是简单串而不是 bulk，实得 " + raw);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d+)\\.(\\d{6}) \\[(\\d+) ([^\\]]*)\\] ").matcher(line);
            assertTrue(m.find(), "行首要形如 <秒>.<微秒> [<库号> <ip:port>]，实得 " + line);
            assertEquals("0", m.group(3), "这条连接默认在 db 0");
            String addr = m.group(4);
            assertFalse(addr.startsWith("/"), "地址不能带 Java toString 的前导斜杠，实得 " + addr);
            assertTrue(addr.matches("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+"), "应是 ip:port，实得 " + addr);

            long stamped = Long.parseLong(m.group(1)) * 1000 + Long.parseLong(m.group(2)) / 1000;
            assertTrue(stamped >= before - 1 && stamped <= after + 1,
                    "时间戳要是命令真正发生的时刻（小数位不是写死的 000000）："
                            + "实得 " + stamped + "，窗口 [" + (before - 1) + ", " + (after + 1) + "]");

            // 参数里有裸换行时不能把这一行劈成两行：下一行还得对得上
            send(peer, "SET", "mon:nl", "a\nb");
            assertEquals("+OK", readReply(pin));
            String withNewline = readWireReply(min);
            assertTrue(withNewline.startsWith("+"), "带换行的参数仍要是一整行简单串，实得 " + withNewline);
            // 这一条才是"转义"的判据：测试里的 readLine 对裸换行是宽容的（读到 \r 才停），
            // 只判 startsWith("+") 的话不转义也照样绿，而真客户端在这里就已经把一行读成两行。
            assertFalse(withNewline.contains("\n") || withNewline.contains("\r"),
                    "参数里的换行必须转义成 \\\\n，不能原样进这一行（简单串靠 CRLF 结束），实得 " + withNewline);
            assertTrue(withNewline.contains("\"mon:nl\""), "命令与键名要还在行里，实得 " + withNewline);
            send(peer, "SET", "mon:after", "x");
            assertEquals("+OK", readReply(pin));
            String after2 = readWireReply(min);
            assertTrue(after2.contains("\"SET\" \"mon:after\" \"x\""),
                    "上一行若把帧读错位，这条就对不上，实得 " + after2);

            // RESET 退出 MONITOR 之后不再收到推送（这里用"下一条命令读不到行"来判，
            // 所以必须在上面几条判据之后才做，否则会污染后面的读取）
            send(mon, "RESET");
            assertEquals("+OK", readReply(min));
            send(peer, "SET", "mon:quiet", "1");
            assertEquals("+OK", readReply(pin));
            mon.setSoTimeout(600);
            String late;
            try {
                late = readWireReply(min);
            } catch (java.net.SocketTimeoutException nothing) {
                late = null;
            }
            assertNull(late, "RESET 之后这条连接不该再收推送，实得 " + late);
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 错误回复不能把这条连接上的帧劈开。
     * <p>
     * 一个 bulk 参数里可以放任意字节，CR/LF 也算，而报错文本会把客户端给的东西原样抄进去：
     * 未实现命令回 {@code -ERR unknown command '<名字>'}，{@code XTRIM} 的策略位、
     * {@code DEBUG} 的子命令名、以及若干 {@code e.getMessage()}（NumberFormatException 会带上
     * 出问题的那串输入）同理。名字里带一组 CRLF，服务器吐出的就是三行——第二行是一条
     * 客户端从没请求过的响应。Redis 的 {@code addReplyErrorLength} 专门把 CR/LF 换成空格，
     * 就是为这件事。判据不放在"读到的第一行"上（读到这里正好停在被注入的那个 CR 上，
     * 看着一切正常），放在紧接着的下一条命令：劈了帧的话它读到的就是伪造行。
     */
    @Test
    void errorRepliesCannotForgeAnExtraLine() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 阳性对照：普通报错形状正常，文本也照原样带得出来
            send(socket, "NOSUCHCMD", "a");
            assertEquals("-ERR unknown command 'NOSUCHCMD'", readReply(in));

            send(socket, "PING\r\n+FORGED\r\n", "x");
            String first = readReply(in);
            assertTrue(first.startsWith("-"), "未实现命令仍是一条错误回复，实得 " + first);
            send(socket, "PING");
            assertEquals("+PONG", readReply(in),
                    "上一条若劈开了帧，这里读到的会是被伪造的那一行；上一条实得 " + first);
            // 走到这里说明整行是一次读干净的：文本还在同一行里，只是 CRLF 被换成了空格
            assertTrue(first.contains("+FORGED"), "只换掉 CRLF，不截断文本，实得 " + first);

            // 另一个载体：XTRIM 的策略位也在报错文本里
            send(socket, "XTRIM", "err:trim", "MAXLENX\r\n+FORGED2\r\n", "3");
            String second = readReply(in);
            assertTrue(second.startsWith("-"), "不认识的裁剪策略要报错，实得 " + second);
            send(socket, "PING");
            assertEquals("+PONG", readReply(in), "XTRIM 那条报错若劈开了帧，这里对不上");
            assertTrue(second.contains("+FORGED2"), "文本保留、只洗 CRLF，实得 " + second);

            // 数字解析的报错走的是 e.getMessage()，那串文本里带着客户端的输入。
            // 但反过来：bulk 是二进制安全的，member 里带换行完全合法，清洗只能做在
            // 简单串/错误这两类"靠 CRLF 结束"的形状上，不能顺手把键名也改了。
            send(socket, "ZADD", "err:zset", "1", "a\r\n+FORGED3\r\n");
            assertEquals(":1", readReply(in), "带换行的 member 照样写得进去");
            send(socket, "ZSCORE", "err:zset", "a\r\n+FORGED3\r\n");
            assertEquals("1", readReply(in), "读回来的分数不受影响（bulk 按长度取，不看换行）");
            send(socket, "PING");
            assertEquals("+PONG", readReply(in), "上面两条若把帧劈开了，这里对不上");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 一台服务器的连接表与订阅态不能漏到另一台。
     * <p>
     * pub/sub 管理器和连接登记表以前是 {@code CommandHandler} 上的静态字段，每条新连接还会
     * 覆写一次：同一 JVM 里两台服务器（跑整模块测试就是这个形态）会出现"A 的 SUBSCRIBE 记进
     * 一个管理器、B 的 CLIENT LIST 读另一个"，量出来的 sub=/psub= 与真值不符；CLIENT KILL
     * 能踢掉别人服务器的客户端，MONITOR 也收得到别台的命令。
     * <p>
     * 判据两头都要量：B 上 PUBLISH 必须是 0 个订阅者（不漏），A 上必须是 1 个（不假绿——
     * 订阅根本没生效时两边都会是 0）。
     */
    @Test
    void pubSubStateAndClientListAreScopedToOneServerInstance() throws Exception {
        int portA = freePort();
        int portB = freePort();
        RedisServer serverA = new RedisServer("127.0.0.1", portA, 0);
        RedisServer serverB = new RedisServer("127.0.0.1", portB, 0);
        Thread threadA = startAndWait(serverA, portA);
        Thread threadB = startAndWait(serverB, portB);
        try (Socket subscriberA = connect(portA); Socket peerA = connect(portA); Socket peerB = connect(portB)) {
            DataInputStream sin = new DataInputStream(subscriberA.getInputStream());
            DataInputStream ain = new DataInputStream(peerA.getInputStream());
            DataInputStream bin = new DataInputStream(peerB.getInputStream());

            send(subscriberA, "SUBSCRIBE", "bleed:channel");
            assertEquals("[subscribe, bleed:channel, :1]", readReplyDeep(sin));

            send(peerB, "PUBLISH", "bleed:channel", "from-b");
            assertEquals(":0", readReply(bin), "另一台服务器上的订阅者不能算进来");
            send(peerB, "CLIENT", "LIST");
            String listB = readReply(bin);
            assertFalse(hasAddrOnPort(listB, subscriberA.getLocalPort()),
                    "B 的连接列表里不该出现 A 的客户端:\n" + listB);

            send(peerA, "PUBLISH", "bleed:channel", "from-a");
            assertEquals(":1", readReply(ain), "阳性对照：A 自己那条订阅确实生效了");
            send(peerA, "CLIENT", "LIST");
            assertTrue(hasAddrOnPort(readReply(ain), subscriberA.getLocalPort()),
                    "阳性对照：A 的列表里确实列得出自己的客户端");

            assertEquals("[message, bleed:channel, from-a]", readReplyDeep(sin));
        } finally {
            serverA.stop();
            serverB.stop();
            threadA.join(DEADLINE_MS);
            threadB.join(DEADLINE_MS);
        }
    }

    /** CLIENT LIST 文本里是否存在对端端口为该值的那条连接（只看 {@code addr=} 字段）。 */
    private static boolean hasAddrOnPort(String clientList, int port) {
        String needle = ":" + port;
        for (String line : clientList.split("\r\n")) {
            int at = line.indexOf("addr=");
            if (at < 0) {
                continue;
            }
            if (line.substring(at + "addr=".length()).split(" ")[0].endsWith(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 后起的那台服务器不能把前一台的 Stream 键空间换掉，两台也不该共用同一份。
     * <p>
     * 判据两头都量：B 读不到 A 的流（不漏），A 也读得到自己的流（不假绿）。
     */
    @Test
    void streamKeyspaceIsScopedToOneServerInstance() throws Exception {
        int portA = freePort();
        int portB = freePort();
        RedisServer serverA = new RedisServer("127.0.0.1", portA, 0);
        RedisServer serverB = new RedisServer("127.0.0.1", portB, 0);
        Thread threadA = startAndWait(serverA, portA);
        Thread threadB = startAndWait(serverB, portB);
        try (Socket a = connect(portA); Socket b = connect(portB)) {
            DataInputStream ain = new DataInputStream(a.getInputStream());
            DataInputStream bin = new DataInputStream(b.getInputStream());

            send(a, "XADD", "bleed:stream", "*", "f", "v");
            assertTrue(readReply(ain).matches("\\d+-\\d+"), "A 上 XADD 要回条目 id");
            send(a, "XLEN", "bleed:stream");
            assertEquals(":1", readReply(ain), "阳性对照：A 自己写进去的流读得回来");

            send(b, "XLEN", "bleed:stream");
            assertEquals(":0", readReply(bin), "另一台服务器上没有这条流");

            send(b, "XADD", "own:stream", "*", "f", "v");
            readReply(bin);
            send(a, "XLEN", "own:stream");
            assertEquals(":0", readReply(ain), "B 写的流不能出现在 A 上");
        } finally {
            serverA.stop();
            serverB.stop();
            threadA.join(DEADLINE_MS);
            threadB.join(DEADLINE_MS);
        }
    }

    /**
     * 一台不带 dataDir 的服务器起来，不能顺手把另一台正在跑的服务器的持久化关掉。
     */
    @Test
    void serverWithoutDataDirDoesNotDisableOtherServersPersistence() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-scope-rdb");
        int portA = freePort();
        int portB = freePort();
        RedisServer serverA = new RedisServer("127.0.0.1", portA, 0);
        serverA.setDataDir(dir.toString());
        RedisServer serverB = new RedisServer("127.0.0.1", portB, 0);
        Thread threadA = startAndWait(serverA, portA);
        try (Socket a = connect(portA)) {
            DataInputStream ain = new DataInputStream(a.getInputStream());

            send(a, "SET", "scope:key", "v");
            assertEquals("+OK", readReply(ain));
            send(a, "SAVE");
            assertEquals("+OK", readReply(ain), "阳性对照：带 dataDir 的这台本来就能存盘");
            assertTrue(java.nio.file.Files.exists(dir.resolve("dump.rdb")), "SAVE 之后快照文件必须在");

            Thread threadB = startAndWait(serverB, portB);
            try {
                send(a, "SET", "scope:key2", "v");
                assertEquals("+OK", readReply(ain));
                send(a, "SAVE");
                assertEquals("+OK", readReply(ain),
                        "另一台服务器启动不该把本台的持久化一起关掉");
            } finally {
                serverB.stop();
                threadB.join(DEADLINE_MS);
            }
        } finally {
            serverA.stop();
            threadA.join(DEADLINE_MS);
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(java.nio.file.Path root) throws IOException {
        if (!java.nio.file.Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk.sorted(
                    java.util.Comparator.<java.nio.file.Path>reverseOrder()::compare)::iterator) {
                java.nio.file.Files.deleteIfExists(p);
            }
        }
    }

    /**
     * GETRANGE / SUBSTR / SETRANGE 三兄弟：越界怎么钳、错先报哪一个，逐条钉 250 上一次性
     * redis-server 4.0.9 抄下的原文（ref.tr、ref2-6.tr、ref9-11.tr、ref15-17.tr）。
     * <p>
     * 两处最容易自己发明：
     * <ul>
     *   <li>取不到内容的 GETRANGE 回<b>空 bulk</b>而不是 nil：{@code 5 5}、{@code 6 9}、
     *       {@code 10 20}、{@code 2 1}（end 排在 start 之前）、键不存在，五例全回空串。</li>
     *   <li>SETRANGE 的偏移判据<b>排在类型闸门之前</b>：同一枚 list 键，{@code -5 x} 回
     *       {@code offset is out of range}、{@code abc x} 回
     *       {@code value is not an integer or out of range}、{@code 0 x} 才轮到 WRONGTYPE。
     *       中央类型闸门跑在分发之前，答不出这个先后，所以这条命令由 {@code handleSetrange}
     *       自己在偏移之后补类型检查。</li>
     * </ul>
     */
    @Test
    void stringRangeFamilyFollowsTheMeasuredClampsAndPrecedence() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "SET", "sr:hello", "Hello");
            assertEquals("+OK", readReply(in));
            send(socket, "GETRANGE", "sr:hello", "0", "1");
            assertEquals("He", readReply(in));
            send(socket, "GETRANGE", "sr:hello", "0", "99999999999999999999");
            assertEquals("-ERR value is not an integer or out of range", readReply(in),
                    "end 装不进 int64 时先吃解析错，轮不到钳位");
            send(socket, "GETRANGE", "sr:hello", "abc", "2");
            assertEquals("-ERR value is not an integer or out of range", readReply(in));
            send(socket, "GETRANGE", "sr:hello", "5", "5");
            assertEquals("", readReply(in), "start 落在串尾之外是空 bulk，不是 nil");
            send(socket, "GETRANGE", "sr:hello", "6", "9");
            assertEquals("", readReply(in));
            send(socket, "GETRANGE", "sr:hello", "10", "20");
            assertEquals("", readReply(in));
            send(socket, "GETRANGE", "sr:hello", "2", "1");
            assertEquals("", readReply(in), "end 在 start 之前也是空串");
            send(socket, "GETRANGE", "sr:hello", "0", "-100");
            assertEquals("H", readReply(in), "负的 end 从串尾倒着换算");
            send(socket, "GETRANGE", "sr:hello", "-100", "-100");
            assertEquals("H", readReply(in));
            send(socket, "GETRANGE", "sr:hello", "-100", "100");
            assertEquals("Hello", readReply(in), "两头都越界就是整串");
            send(socket, "GETRANGE", "sr:hello", "-3", "-1");
            assertEquals("llo", readReply(in));
            send(socket, "GETRANGE", "sr:hello", "9223372036854775807", "5");
            assertEquals("", readReply(in), "int64 上界本身要能进钳位");
            send(socket, "GETRANGE", "sr:hello", "-9223372036854775808", "5");
            assertEquals("Hello", readReply(in), "int64 下界换算后落在串头之前，钳到 0");
            send(socket, "GETRANGE", "sr:missing", "0", "5");
            assertEquals("", readReply(in), "键不存在回空串");
            send(socket, "GETRANGE", "sr:missing", "0", "-100");
            assertEquals("", readReply(in));
            send(socket, "GETRANGE", "sr:hello", "1");
            assertEquals("-ERR wrong number of arguments for 'getrange' command", readReply(in));

            // SUBSTR 与 GETRANGE 同一把尺（实测两族逐例一致，含 arity 文案里的名字）
            send(socket, "SUBSTR", "sr:hello", "0", "3");
            assertEquals("Hell", readReply(in));
            send(socket, "SUBSTR", "sr:hello", "1", "-2");
            assertEquals("ell", readReply(in));
            send(socket, "SUBSTR", "sr:hello", "0", "-100");
            assertEquals("H", readReply(in));
            send(socket, "SUBSTR", "sr:hello", "3", "0");
            assertEquals("", readReply(in));
            send(socket, "SUBSTR", "sr:hello", "-3");
            assertEquals("-ERR wrong number of arguments for 'substr' command", readReply(in));

            // 非 string 键：这两条的闸门在参数之后吗？实测在<b>之前</b>（参数合法就 WRONGTYPE）
            send(socket, "HSET", "sr:h", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "GETRANGE", "sr:h", "0", "1");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
            send(socket, "SUBSTR", "sr:h", "0", "1");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
            send(socket, "HSET", "sr:h", "big", "你好");
            assertEquals(":1", readReply(in));
            send(socket, "HSTRLEN", "sr:h", "big");
            assertEquals(":6", readReply(in), "数的是字节数不是字符数");
            send(socket, "HSTRLEN", "sr:h", "nosuch");
            assertEquals(":0", readReply(in), "缺字段回 0，不是 nil");
            send(socket, "HSTRLEN", "sr:nokey", "f");
            assertEquals(":0", readReply(in), "缺键同样回 0");
            send(socket, "HSTRLEN", "sr:h");
            assertEquals("-ERR wrong number of arguments for 'hstrlen' command", readReply(in));
            send(socket, "HSTRLEN", "sr:hello", "f");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));

            // SETRANGE：偏移 → 类型 → 长度，三档各回一句
            send(socket, "LPUSH", "sr:l", "a");
            assertEquals(":1", readReply(in));
            send(socket, "SETRANGE", "sr:l", "-5", "x");
            assertEquals("-ERR offset is out of range", readReply(in), "负的偏移先于类型闸门");
            send(socket, "SETRANGE", "sr:l", "-9223372036854775808", "x");
            assertEquals("-ERR offset is out of range", readReply(in));
            send(socket, "SETRANGE", "sr:l", "abc", "x");
            assertEquals("-ERR value is not an integer or out of range", readReply(in));
            send(socket, "SETRANGE", "sr:l", "0", "x");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
            send(socket, "LLEN", "sr:l");
            assertEquals(":1", readReply(in), "四类失败都不许动到列表本体");

            send(socket, "SETRANGE", "sr:fresh", "3", "x");
            assertEquals(":4", readReply(in), "缺键时按零补齐到偏移");
            send(socket, "EXISTS", "sr:fresh");
            assertEquals(":1", readReply(in));
            send(socket, "GETRANGE", "sr:fresh", "0", "-1");
            assertEquals("\u0000\u0000\u0000x", readReply(in), "补齐的那三段是 0x00");
            send(socket, "SETRANGE", "sr:hello", "5", "World");
            assertEquals(":10", readReply(in));
            send(socket, "GET", "sr:hello");
            assertEquals("HelloWorld", readReply(in));
            send(socket, "SETRANGE", "sr:hello", "1", "ey");
            assertEquals(":10", readReply(in), "原地替换不改长度");
            send(socket, "GET", "sr:hello");
            assertEquals("HeyloWorld", readReply(in));
            send(socket, "SETRANGE", "sr:hello", "5", "abc");
            assertEquals(":10", readReply(in));

            // 512MB 那一档在分配之前拒：这四条必须一条堆都不吃
            send(socket, "SETRANGE", "sr:hello", "600000000", "x");
            assertEquals("-ERR string exceeds maximum allowed size (512MB)", readReply(in));
            send(socket, "SETRANGE", "sr:hello", "2000000000", "x");
            assertEquals("-ERR string exceeds maximum allowed size (512MB)", readReply(in));
            send(socket, "SETRANGE", "sr:hello", "2147483647", "x");
            assertEquals("-ERR string exceeds maximum allowed size (512MB)", readReply(in));
            // 这一条在参考实现里直接把 4.0.9 打崩（长度检查用加法，绕回负数后放行）。
            // 本实现用减法问"还剩多少地方"，所以回的是同一句错而不是把连接弄断。
            send(socket, "SETRANGE", "sr:hello", "9223372036854775807", "x");
            assertEquals("-ERR string exceeds maximum allowed size (512MB)", readReply(in),
                    "int64 上界的偏移不许把长度检查绕过去");
            send(socket, "PING");
            assertEquals("+PONG", readReply(in), "越界的偏移不能把服务器打死");
            send(socket, "STRLEN", "sr:hello");
            assertEquals(":10", readReply(in), "被拒的大偏移不许留下任何补齐");

            // SETRANGE 不动 TTL（实测 200 → 200）
            send(socket, "SETEX", "sr:ttl", "200", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "SETRANGE", "sr:ttl", "0", "x");
            assertEquals(":1", readReply(in));
            send(socket, "TTL", "sr:ttl");
            String ttl = readReply(in);
            assertTrue(ttl.startsWith(":") && Long.parseLong(ttl.substring(1)) > 150,
                    "SETRANGE 之后 TTL 要还在: " + ttl);
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 两族浮点：同一台机器上的两套算术，加同一个数给出两个答案。
     * <p>
     * {@code INCRBYFLOAT} / {@code HINCRBYFLOAT} 这一族在 x86-64 上算的是 80 位 long double，
     * {@code 0.1 + 0.2} 回 {@code 0.3}；{@code ZADD} / {@code ZINCRBY} 那一族算的是 64 位 double，
     * 同一道加法回 {@code 0.30000000000000004}（ref19 实测，两行都在同一个实例上量到）。
     * 十进制溢出的口子也只开在一族上：分数一族 {@code 1e4000} 直接
     * {@code value is not a valid float}，长双数一族却把 4000 位整数字符串打回来
     * （{@code %.17Lf} 打整数位，实测长度正好 4000）。
     * <p>
     * 更细的一条是<b>非有限值的政策</b>：{@code incrbyfloatCommand} 在算完之后显式挡
     * {@code NaN}/{@code Infinity}，而 {@code hincrbyfloatCommand} 没有那一步 —— 于是
     * {@code HINCRBYFLOAT h f -inf} 把 "-inf" 原样写进字段，{@code -inf} 再加 {@code inf}
     * 写出 "-nan"，下一次读它时以 {@code hash value is not a float} 拒绝（ref12 / ref13）。
     */
    @Test
    void theTwoFloatFamiliesShareArithmeticButNotTheNonFinitePolicy() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- 长双数一族：INCRBYFLOAT
            send(socket, "INCRBYFLOAT", "ibf:f", "0.1");
            assertEquals("0.1", readReply(in), "缺键时从 0 起算，回复的就是增量");
            send(socket, "INCRBYFLOAT", "ibf:f", "0.2");
            assertEquals("0.3", readReply(in), "double 里算是 0.30000000000000004");
            send(socket, "INCRBYFLOAT", "ibf:f", "1e21");
            assertEquals("1000000000000000000000", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:f", "-0.5");
            assertEquals("1000000000000000000000", readReply(in), "小于格点间距的增量加不进去");
            send(socket, "INCRBYFLOAT", "ibf:f", "inf");
            assertEquals("-ERR increment would produce NaN or Infinity", readReply(in));
            send(socket, "GET", "ibf:f");
            assertEquals("1000000000000000000000", readReply(in), "被挡下的增量不许动原值");
            send(socket, "INCRBYFLOAT", "ibf:f", "1e99999999999");
            assertEquals("-ERR value is not a valid float", readReply(in));

            // 需要还原二进制网格才算对的四例：整数位超过 64 位有效位
            send(socket, "SET", "ibf:grid", "12345678901234567890123");
            assertEquals("+OK", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:grid", "0");
            assertEquals("12345678901234567889920", readReply(in));
            send(socket, "SET", "ibf:pi", "3.141592653589793238462643383279");
            assertEquals("+OK", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:pi", "0");
            assertEquals("3.14159265358979324", readReply(in));
            send(socket, "SET", "ibf:tiny", "1e-17");
            assertEquals("+OK", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:tiny", "0");
            assertEquals("0.00000000000000001", readReply(in));
            send(socket, "SET", "ibf:tiny2", "2.5e-17");
            assertEquals("+OK", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:tiny2", "0");
            assertEquals("0.00000000000000002", readReply(in));
            // strtold 认十六进制浮点文本
            send(socket, "SET", "ibf:hex", "0x1p3");
            assertEquals("+OK", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:hex", "0");
            assertEquals("8", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:hex", "1");
            assertEquals("9", readReply(in));
            // 库里存的就是 "9"（INCRBYFLOAT 的返回值与写入值同串），所以 APPEND 后长度是 2
            send(socket, "APPEND", "ibf:hex", "x");
            assertEquals(":2", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:hex", "1");
            assertEquals("-ERR value is not a valid float", readReply(in), "原值读不回来时是这一句");

            // 1e4000 在 long double 的射程里：4000 位整数，不是 inf
            send(socket, "SET", "ibf:huge", "0");
            assertEquals("+OK", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:huge", "1e4000");
            String huge = readReply(in);
            assertEquals(4000, huge.length(), "回复的整数位长度实测是 4000");
            assertTrue(huge.startsWith("9999999999999999999965463873099623784932492583506957631301508333043261"),
                    "逐位要还原 64 位有效位的网格: " + huge.substring(0, 40));

            // 类型闸门在这一族排在增量之前（实测 INCRBYFLOAT <list 键> abc → WRONGTYPE）
            send(socket, "LPUSH", "ibf:l", "a");
            assertEquals(":1", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:l", "abc");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
            send(socket, "HSET", "ibf:h0", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "INCRBYFLOAT", "ibf:h0", "1");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));

            // ---- 分数一族：同一个加法给不同答案
            send(socket, "ZADD", "sc:z", "0.1", "m");
            assertEquals(":1", readReply(in));
            send(socket, "ZINCRBY", "sc:z", "0.2", "m");
            assertEquals("0.30000000000000004", readReply(in), "分数一族是 64 位 double 相加");
            send(socket, "ZSCORE", "sc:z", "m");
            assertEquals("0.30000000000000004", readReply(in), "存进去的那串与回复同一形状");
            send(socket, "ZADD", "sc:z2", "1e4000", "m");
            assertEquals("-ERR value is not a valid float", readReply(in),
                    "十进制溢出在分数一族是解析失败，不是 inf");
            send(socket, "ZADD", "sc:z2", "1e309", "m");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZADD", "sc:inf", "inf", "hi");
            assertEquals(":1", readReply(in), "inf 是合法分数");
            send(socket, "ZSCORE", "sc:inf", "hi");
            assertEquals("inf", readReply(in), "回的是 inf，不是 Java 的 Infinity");

            // ---- HINCRBYFLOAT：算术与 INCRBYFLOAT 同一套，非有限值的政策相反
            send(socket, "HINCRBYFLOAT", "hf:h", "f", "0.1");
            assertEquals("0.1", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:h", "f", "0.2");
            assertEquals("0.3", readReply(in));
            send(socket, "HGET", "hf:h", "f");
            assertEquals("0.3", readReply(in), "存进去的那串就是回复那串");
            send(socket, "HINCRBYFLOAT", "hf:h", "f", "-inf");
            assertEquals("-inf", readReply(in), "这一族不挡无穷，并把 -inf 原样写回字段");
            send(socket, "HGET", "hf:h", "f");
            assertEquals("-inf", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:h", "f", "1");
            assertEquals("-inf", readReply(in), "无穷加有限还是无穷");
            send(socket, "HINCRBYFLOAT", "hf:h", "f", "inf");
            assertEquals("-nan", readReply(in), "-inf 加 inf 是带符号的 nan（实测就是这两个字节）");
            send(socket, "HGET", "hf:h", "f");
            assertEquals("-nan", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:h", "f", "1");
            assertEquals("-ERR hash value is not a float", readReply(in),
                    "字段里已经是 -nan 之后再碰它，回的是原值的错");
            send(socket, "HGET", "hf:h", "f");
            assertEquals("-nan", readReply(in), "报错的调用不改字段");
            send(socket, "HINCRBYFLOAT", "hf:h", "f", "nan");
            assertEquals("-ERR value is not a valid float", readReply(in),
                    "增量先解析：同一枚坏字段，坏在增量时回的是增量的文案（ref12）");

            send(socket, "HSET", "hf:bad", "f", "abc");
            assertEquals(":1", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:bad", "f", "1");
            assertEquals("-ERR hash value is not a float", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:bad", "f", "abc");
            assertEquals("-ERR value is not a valid float", readReply(in),
                    "两边都坏时报增量那一边（ref14）");

            // 1e4932 在射程边缘：两下相加越过 LDBL_MAX 就回 inf
            send(socket, "HSET", "hf:max", "f", "1e4932");
            assertEquals(":1", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:max", "f", "1e4932");
            assertEquals("inf", readReply(in));
            send(socket, "HSET", "hf:min", "f", "-1e4932");
            assertEquals(":1", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:min", "f", "-1e4932");
            assertEquals("-inf", readReply(in));

            // 一定失败的调用不许在库里留下空 hash（实测 EXISTS 0、DBSIZE 0）
            send(socket, "HINCRBYFLOAT", "hf:none", "f", "1e99999");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "EXISTS", "hf:none");
            assertEquals(":0", readReply(in));
            send(socket, "TYPE", "hf:none");
            assertEquals("+none", readReply(in));
            // 增量先解析、键的类型后判：实测 HINCRBYFLOAT <string 键> f abc → 增量的错
            send(socket, "SET", "hf:str", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:str", "f", "abc");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:str", "f", "1");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
            send(socket, "GET", "hf:str");
            assertEquals("v", readReply(in));
            send(socket, "HINCRBYFLOAT", "hf:h", "x", "1e4000");
            String hashHuge = readReply(in);
            assertEquals(4000, hashHuge.length(), "这一族写进 hash 的也是 4000 位整数");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * ZADD 的文法与报错先后 —— 每一档都有 ref8 / ref9 / ref16 / ref17 的行号作凭。
     * <p>
     * 先后本身就是被测的判据之一：{@code NX XX} 的互斥排在"数对不够"之后、排在
     * {@code INCR 只许一对}之前（{@code NX XX INCR 1 a 2 b} 回的是互斥那句），
     * 而<b>所有</b>分数解析又排在类型闸门之前
     * （{@code ZADD <string 键> 1 a abc b} 回 {@code value is not a valid float}）。
     */
    @Test
    void zaddGrammarAndErrorPrecedenceMatchTheReference() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "ZADD", "za:z", "1", "a");
            assertEquals(":1", readReply(in));
            send(socket, "ZADD", "za:z", "1", "a");
            assertEquals(":0", readReply(in), "默认只数新增");
            send(socket, "ZADD", "za:z", "CH", "2", "a");
            assertEquals(":1", readReply(in), "带 CH 才算改动");
            send(socket, "ZADD", "za:z", "CH", "2", "a");
            assertEquals(":0", readReply(in), "分数没变，CH 也不算");
            send(socket, "ZADD", "za:z", "NX", "9", "a");
            assertEquals(":0", readReply(in));
            send(socket, "ZSCORE", "za:z", "a");
            assertEquals("2", readReply(in), "NX 挡住了改分数");
            send(socket, "ZADD", "za:z", "XX", "3", "a");
            assertEquals(":0", readReply(in), "XX 下改了分数也只数新增");
            send(socket, "ZSCORE", "za:z", "a");
            assertEquals("3", readReply(in));
            send(socket, "ZADD", "za:z", "XX", "4", "a", "5", "b");
            assertEquals(":0", readReply(in));
            send(socket, "ZSCORE", "za:z", "b");
            assertEquals("$-1", readReply(in), "XX 挡下时新成员一个都不能建");
            send(socket, "ZCARD", "za:z");
            assertEquals(":1", readReply(in));
            send(socket, "ZADD", "za:z", "ch", "nx", "7", "c");
            assertEquals(":1", readReply(in), "修饰位大小写都认");
            send(socket, "ZADD", "za:rep", "CH", "CH", "CH", "1", "a");
            assertEquals(":1", readReply(in), "重复的修饰位不报错");
            send(socket, "ZCARD", "za:rep");
            assertEquals(":1", readReply(in));

            // 成员名不做浮点解释，分数栏才做
            send(socket, "ZADD", "za:nan", "1", "nan");
            assertEquals(":1", readReply(in));
            send(socket, "ZSCORE", "za:nan", "nan");
            assertEquals("1", readReply(in), "成员就叫 nan");
            send(socket, "ZADD", "za:nan", "nan", "m2");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZADD", "za:nan", "-nan", "m3");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZADD", "za:nan", "abc", "m4");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZCARD", "za:nan");
            assertEquals(":1", readReply(in), "三笔坏分数都没建成员");

            // 修饰位只认最前面连续的一段
            send(socket, "ZADD", "za:tail", "1", "a", "INCR");
            assertEquals("-ERR syntax error", readReply(in));
            send(socket, "ZADD", "za:tail", "1", "INCR", "a");
            assertEquals("-ERR syntax error", readReply(in));
            send(socket, "ZADD", "za:tail", "1", "a", "WEIGHTS", "2");
            assertEquals("-ERR value is not a valid float", readReply(in),
                    "认不出的 token 落回分数栏，于是死于浮点解析而不是 syntax error");
            send(socket, "ZADD", "za:tail", "1", "a", "2");
            assertEquals("-ERR syntax error", readReply(in), "数对不成双");
            send(socket, "ZADD", "za:tail", "1");
            assertEquals("-ERR wrong number of arguments for 'zadd' command", readReply(in));
            send(socket, "ZADD", "za:tail", "INCR");
            assertEquals("-ERR wrong number of arguments for 'zadd' command", readReply(in));
            send(socket, "ZADD", "za:tail", "CH");
            assertEquals("-ERR wrong number of arguments for 'zadd' command", readReply(in),
                    "只剩选项没有数对时，参考实现回的是 arity 而不是 syntax error");

            // NX/XX 互斥：排在数对之后、INCR 之前，且报错时整条命令不落库
            send(socket, "ZADD", "za:mx", "NX", "XX", "abc", "m");
            assertEquals("-ERR XX and NX options at the same time are not compatible", readReply(in));
            send(socket, "ZADD", "za:mx", "XX", "NX", "1", "a");
            assertEquals("-ERR XX and NX options at the same time are not compatible", readReply(in));
            send(socket, "ZADD", "za:mx", "NX", "XX", "INCR", "1", "a");
            assertEquals("-ERR XX and NX options at the same time are not compatible", readReply(in));
            send(socket, "ZADD", "za:mx", "INCR", "NX", "XX", "1", "a", "2", "b");
            assertEquals("-ERR XX and NX options at the same time are not compatible", readReply(in));
            send(socket, "ZADD", "za:mx", "NX", "XX");
            assertEquals("-ERR syntax error", readReply(in), "数对不够那一档排在互斥之前");
            send(socket, "EXISTS", "za:mx");
            assertEquals(":0", readReply(in), "四笔互斥错都不该把键建出来");

            // INCR：分数栏是增量，回 bulk；被 NX/XX 挡下回 nil
            send(socket, "ZADD", "za:incr", "INCR", "1", "a");
            assertEquals("1", readReply(in));
            send(socket, "ZADD", "za:incr", "incr", "2", "a");
            assertEquals("3", readReply(in));
            send(socket, "ZADD", "za:incr", "INCR", "NX", "9", "a");
            assertEquals("$-1", readReply(in), "NX 挡下时回 nil 而不是 0");
            send(socket, "ZADD", "za:incr", "INCR", "XX", "5", "a");
            assertEquals("8", readReply(in));
            send(socket, "ZADD", "za:incr", "INCR", "1", "a", "2", "b");
            assertEquals("-ERR INCR option supports a single increment-element pair", readReply(in));
            send(socket, "ZADD", "za:incr", "INCR", "1");
            assertEquals("-ERR syntax error", readReply(in));
            send(socket, "ZADD", "za:fresh", "INCR", "CH", "5", "nosuch");
            assertEquals("5", readReply(in), "CH 不改 INCR 的算术，缺成员照样从 0 起算");
            send(socket, "ZSCORE", "za:fresh", "nosuch");
            assertEquals("5", readReply(in));

            // 分数解析排在类型闸门之前，而且是<b>整串</b>数对解析完才去碰键
            send(socket, "SET", "za:str", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "ZADD", "za:str", "abc", "a");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZADD", "za:str", "1e4000", "a");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZADD", "za:str", "1", "a", "abc", "b");
            assertEquals("-ERR value is not a valid float", readReply(in),
                    "第一对合法也不许先去碰键");
            send(socket, "ZADD", "za:str", "1", "a");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
            send(socket, "GET", "za:str");
            assertEquals("v", readReply(in));
            send(socket, "ZINCRBY", "za:str", "abc", "m");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZINCRBY", "za:str", "nan", "m");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZINCRBY", "za:str", "1e4000", "m");
            assertEquals("-ERR value is not a valid float", readReply(in));
            send(socket, "ZINCRBY", "za:str", "1", "m");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
            send(socket, "ZINCRBY", "za:nokey", "1.5", "x");
            assertEquals("1.5", readReply(in));
            send(socket, "ZINCRBY", "za:nokey", "-0", "x");
            assertEquals("1.5", readReply(in), "减 0 不改分数");

            // 结果不是数：Redis 在算完之后判 nan，此时集合没动过
            send(socket, "ZADD", "za:inf", "inf", "m");
            assertEquals(":1", readReply(in));
            send(socket, "ZINCRBY", "za:inf", "-inf", "m");
            assertEquals("-ERR resulting score is not a number (NaN)", readReply(in));
            send(socket, "ZSCORE", "za:inf", "m");
            assertEquals("inf", readReply(in), "NaN 那一步之前不落库");
            send(socket, "ZINCRBY", "za:inf", "nan", "m");
            assertEquals("-ERR value is not a valid float", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * ZUNIONSTORE / ZINTERSTORE 与 MOVE —— 两件事放在一起，是因为都要"按数读回"才判得准：
     * 前者要读目标键的内容与类型，后者要把键读到另一个库里去。
     * <p>
     * ZSTORE 一族的实测形状（ref2 / ref3 / ref4 / ref6 / ref7 / ref10）：
     * 键数不是整数 → {@code value is not an integer or out of range}；是 0 或负 →
     * {@code at least 1 input key is needed for ZUNIONSTORE/ZINTERSTORE}；键数比给的 token 多 →
     * {@code syntax error}；{@code WEIGHTS} 少给或尾巴不认 → {@code syntax error}；权重不是浮点 →
     * {@code weight value is not a float}（这一族独有的文案），其中 {@code nan} 与 {@code 1e4000}
     * 都算非法而 {@code inf} 合法；{@code AGGREGATE} 只认 SUM/MIN/MAX；目标键上别的类型整个顶掉，
     * 但目标键同时是源键时不能把 zset 那份一起清；算出来是空 → 回 0 且不留目标键。
     * <p>
     * MOVE 的三道判据先后同样只有对岸说得清（ref18）：库号 → 同库 → 才轮到"有没有这个键"，
     * 三档对<b>不存在的键</b>分别回 {@code index out of range} /
     * {@code source and destination objects are the same} / {@code 0}。
     */
    @Test
    void zstoreAndCrossDbMoveFollowTheMeasuredRows() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "ZADD", "zs:z1", "1", "a", "2", "b");
            assertEquals(":2", readReply(in));
            send(socket, "ZADD", "zs:z2", "3", "b", "4", "c");
            assertEquals(":2", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:d", "2", "zs:z1", "zs:z2");
            assertEquals(":3", readReply(in));
            send(socket, "ZRANGE", "zs:d", "0", "-1", "WITHSCORES");
            assertEquals("[a, 1, c, 4, b, 5]", readReplyDeep(in), "并集是同名成员分数相加");
            send(socket, "ZINTERSTORE", "zs:di", "2", "zs:z1", "zs:z2");
            assertEquals(":1", readReply(in), "交集只剩 b");
            send(socket, "ZRANGE", "zs:di", "0", "-1", "WITHSCORES");
            assertEquals("[b, 5]", readReplyDeep(in));
            send(socket, "ZUNIONSTORE", "zs:dmin", "2", "zs:z1", "zs:z2", "AGGREGATE", "MIN");
            assertEquals(":3", readReply(in));
            send(socket, "ZSCORE", "zs:dmin", "b");
            assertEquals("2", readReply(in), "AGGREGATE MIN 取小的那个");
            send(socket, "ZUNIONSTORE", "zs:dmax", "2", "zs:z1", "zs:z2", "AGGREGATE", "MAX");
            assertEquals(":3", readReply(in));
            send(socket, "ZSCORE", "zs:dmax", "b");
            assertEquals("3", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:dw", "2", "zs:z1", "zs:z2", "WEIGHTS", "2", "3");
            assertEquals(":3", readReply(in));
            send(socket, "ZSCORE", "zs:dw", "b");
            assertEquals("13", readReply(in), "2×2 + 3×3");

            // 目标键被别的类型占着时要整个顶掉
            send(socket, "SET", "zs:str", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:str", "1", "zs:z1");
            assertEquals(":2", readReply(in));
            send(socket, "TYPE", "zs:str");
            assertEquals("+zset", readReply(in), "覆盖目标键时连旧的 string 一起换掉");
            // 目标键同时是源键时，源那一份不能被自己清掉
            send(socket, "ZADD", "zs:self", "1", "x", "2", "y");
            assertEquals(":2", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:self", "1", "zs:self", "WEIGHTS", "2");
            assertEquals(":2", readReply(in));
            send(socket, "ZRANGE", "zs:self", "0", "-1", "WITHSCORES");
            assertEquals("[x, 2, y, 4]", readReplyDeep(in), "翻倍是在自己乘二，不是变成空集");
            send(socket, "ZINTERSTORE", "zs:self", "1", "zs:self");
            assertEquals(":2", readReply(in));
            // 算出来是空 → 不留目标键
            send(socket, "ZUNIONSTORE", "zs:empty", "1", "zs:nosuch");
            assertEquals(":0", readReply(in));
            send(socket, "EXISTS", "zs:empty");
            assertEquals(":0", readReply(in), "空结果不该把目标键建出来");
            send(socket, "ZUNIONSTORE", "zs:halfempty", "2", "zs:z1", "zs:nosuch");
            assertEquals(":2", readReply(in), "缺的源当空集，另一份照抄");

            // 参数形状与文案
            send(socket, "ZUNIONSTORE", "zs:a", "0");
            assertEquals("-ERR wrong number of arguments for 'zunionstore' command", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "-1", "zs:z1");
            assertEquals("-ERR at least 1 input key is needed for ZUNIONSTORE/ZINTERSTORE", readReply(in));
            send(socket, "ZINTERSTORE", "zs:a", "-1", "zs:z1");
            assertEquals("-ERR at least 1 input key is needed for ZUNIONSTORE/ZINTERSTORE", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "x", "zs:z1");
            assertEquals("-ERR value is not an integer or out of range", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "3", "zs:z1", "zs:z2");
            assertEquals("-ERR syntax error", readReply(in), "键数比给的 token 多");
            send(socket, "ZUNIONSTORE", "zs:a", "1", "zs:z1", "WEIGHTS");
            assertEquals("-ERR syntax error", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "1", "zs:z1", "WEIGHTS", "2", "3");
            assertEquals("-ERR syntax error", readReply(in), "权重比键多也吃 syntax error");
            send(socket, "ZUNIONSTORE", "zs:a", "1", "zs:z1", "WEIGHTS", "nan");
            assertEquals("-ERR weight value is not a float", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "1", "zs:z1", "WEIGHTS", "1e4000");
            assertEquals("-ERR weight value is not a float", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "1", "zs:z1", "WEIGHTS", "x");
            assertEquals("-ERR weight value is not a float", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:infw", "1", "zs:z1", "WEIGHTS", "inf");
            assertEquals(":2", readReply(in), "inf 权重是合法的");
            send(socket, "ZSCORE", "zs:infw", "a");
            assertEquals("inf", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "1", "zs:z1", "AGGREGATE", "AVG");
            assertEquals("-ERR syntax error", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "1", "zs:z1", "WITHSCORES");
            assertEquals("-ERR syntax error", readReply(in), "尾巴上不认的 token 不静默忽略");
            send(socket, "LPUSH", "zs:li", "a");
            assertEquals(":1", readReply(in));
            send(socket, "ZUNIONSTORE", "zs:a", "1", "zs:li");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));

            // ---- MOVE：库号 → 同库 → 存在性，之后才轮到真的搬
            send(socket, "SET", "mv:a", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "MOVE", "mv:nope", "abc");
            assertEquals("-ERR index out of range", readReply(in), "库号解析不动就报 index out of range");
            send(socket, "MOVE", "mv:nope", "16");
            assertEquals("-ERR index out of range", readReply(in), "只有 0..15 这 16 个库");
            send(socket, "MOVE", "mv:nope", "99999999999");
            assertEquals("-ERR index out of range", readReply(in));
            send(socket, "MOVE", "mv:nope", "-1");
            assertEquals("-ERR index out of range", readReply(in));
            send(socket, "MOVE", "mv:nope", "0");
            assertEquals("-ERR source and destination objects are the same", readReply(in),
                    "同库那一档排在存在性检查之前");
            send(socket, "MOVE", "mv:nope", "3");
            assertEquals(":0", readReply(in), "库号合法、也不是同库，才轮到源库里有没有这个键");
            send(socket, "MOVE", "mv:a");
            assertEquals("-ERR wrong number of arguments for 'move' command", readReply(in));
            send(socket, "MOVE", "mv:a", "1");
            assertEquals(":1", readReply(in));
            send(socket, "EXISTS", "mv:a");
            assertEquals(":0", readReply(in), "搬走之后源库里就没了");
            send(socket, "MOVE", "mv:a", "1");
            assertEquals(":0", readReply(in), "源库里没有时第二次搬回 0");
            send(socket, "SELECT", "1");
            assertEquals("+OK", readReply(in));
            send(socket, "GET", "mv:a");
            assertEquals("v", readReply(in), "键要落在目标库里，内容与库号一起对");
            send(socket, "DBSIZE");
            assertEquals(":1", readReply(in));
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));

            // 带着 TTL 搬：过期时间要跟着键走，不带跟着连接走
            send(socket, "SETEX", "mv:t", "200", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "MOVE", "mv:t", "2");
            assertEquals(":1", readReply(in));
            send(socket, "SELECT", "2");
            assertEquals("+OK", readReply(in));
            send(socket, "TTL", "mv:t");
            String movedTtl = readReply(in);
            assertTrue(movedTtl.startsWith(":") && Long.parseLong(movedTtl.substring(1)) > 150,
                    "MOVE 之后 TTL 要跟着过去: " + movedTtl);
            send(socket, "TYPE", "mv:t");
            assertEquals("+string", readReply(in));
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));
            // 集合类型也能整键搬走（实测 hash/list/zset 三种都回 1）
            send(socket, "HSET", "mv:h", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "MOVE", "mv:h", "5");
            assertEquals(":1", readReply(in));
            send(socket, "EXISTS", "mv:h");
            assertEquals(":0", readReply(in), "搬走的键在源库里不再留壳");
            send(socket, "SELECT", "5");
            assertEquals("+OK", readReply(in));
            send(socket, "HGET", "mv:h", "f");
            assertEquals("v", readReply(in));
            send(socket, "DBSIZE");
            assertEquals(":1", readReply(in), "库 5 里只有搬来的这一枚");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 一批"文案与计数形状"的实测行：MSET / MSETNX / UNLINK / TOUCH / EXPIREAT / LPUSHX / RPUSHX。
     * <p>
     * 值得单独钉住的三条：
     * <ul>
     *   <li>MSET 一族的 arity 文案<b>两套</b>：参数不够长时是小写带引号命令名
     *       （{@code 'mset'} / {@code 'msetnx'}），而"数对不成双"走的是另一条硬编码的
     *       {@code wrong number of arguments for MSET} —— 大写、不带引号，而且 {@code MSETNX}
     *       的不成双也照抄 MSET 这个名字（实测 {@code MSETNX b4:m1 a b4:x}）。</li>
     *   <li>MSETNX 是全有全无，而"已存在"是按<b>所有类型</b>一起看的；同一次调用里键名重复
     *       却算得过去（回 1，后写的赢）。</li>
     *   <li>{@code LPUSHX}/{@code RPUSHX} 在键不存在时回 0 并且<b>不</b>把键建出来。</li>
     * </ul>
     */
    @Test
    void batchWordingCountsAndPushxFollowTheReference() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "MSET", "w:m1", "1", "w:m2", "2");
            assertEquals("+OK", readReply(in));
            send(socket, "MSET", "w:a", "1", "w:c");
            assertEquals("-ERR wrong number of arguments for MSET", readReply(in),
                    "不成双那一档是大写不带引号的原文案");
            send(socket, "MSETNX", "w:a", "1", "w:x");
            assertEquals("-ERR wrong number of arguments for MSET", readReply(in),
                    "MSETNX 的不成双照抄 MSET 这个名字");
            send(socket, "MSET", "w:a");
            assertEquals("-ERR wrong number of arguments for 'mset' command", readReply(in));
            send(socket, "MSET");
            assertEquals("-ERR wrong number of arguments for 'mset' command", readReply(in));
            send(socket, "MSETNX", "w:a");
            assertEquals("-ERR wrong number of arguments for 'msetnx' command", readReply(in));
            send(socket, "MSETNX", "w:m1", "9", "w:b", "9");
            assertEquals(":0", readReply(in), "只要有一个键已存在就整笔不做");
            send(socket, "MGET", "w:m1", "w:b");
            assertEquals("[1, $-1]", readReplyDeep(in), "存在的那枚也不许被覆盖");
            send(socket, "EXISTS", "w:b");
            assertEquals(":0", readReply(in));
            send(socket, "HSET", "w:hash", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "MSETNX", "w:hash", "1", "w:other", "2");
            assertEquals(":0", readReply(in), "\"已存在\"是按所有类型一起看的");
            send(socket, "EXISTS", "w:other");
            assertEquals(":0", readReply(in));
            send(socket, "MSETNX", "w:n1", "a", "w:n1", "b");
            assertEquals(":1", readReply(in), "同一次调用里键名重复算得过去");
            send(socket, "MGET", "w:n1");
            assertEquals("[b]", readReplyDeep(in), "重复时后写的赢");
            send(socket, "DBSIZE");
            assertEquals(":4", readReply(in), "w:m1 w:m2 w:hash w:n1（重复的键名只算一个）");

            send(socket, "MSET", "w:u1", "1", "w:u2", "2", "w:u3", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "TOUCH", "w:u1", "w:nope", "w:u1", "w:u3");
            assertEquals(":3", readReply(in), "TOUCH 与 EXISTS 同一把尺，重复键重复计");
            send(socket, "TOUCH", "w:nope1", "w:nope2");
            assertEquals(":0", readReply(in));
            send(socket, "TOUCH");
            assertEquals("-ERR wrong number of arguments for 'touch' command", readReply(in));
            send(socket, "UNLINK", "w:u1", "w:u2", "w:nope");
            assertEquals(":2", readReply(in), "UNLINK 的计数与 DEL 相同");
            send(socket, "EXISTS", "w:u1", "w:u2");
            assertEquals(":0", readReply(in), "多键 EXISTS 回的是\"存在几枚\"的计数，不是逐键数组");
            send(socket, "EXISTS", "w:u3", "w:nope");
            assertEquals(":1", readReply(in), "计数与 TOUCH 同一把尺");
            send(socket, "ZADD", "w:zz", "1", "a");
            assertEquals(":1", readReply(in));
            send(socket, "UNLINK", "w:zz");
            assertEquals(":1", readReply(in), "zset 键也能 UNLINK");
            send(socket, "UNLINK");
            assertEquals("-ERR wrong number of arguments for 'unlink' command", readReply(in));

            // EXPIREAT / PEXPIREAT
            send(socket, "SET", "w:exp", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "EXPIREAT", "w:exp", "946684800");
            assertEquals(":1", readReply(in), "时刻已过 → 1，且当场就把键删掉");
            send(socket, "EXISTS", "w:exp");
            assertEquals(":0", readReply(in));
            send(socket, "SET", "w:future", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "EXPIREAT", "w:future", "4000000000");
            assertEquals(":1", readReply(in));
            send(socket, "TTL", "w:future");
            String farTtl = readReply(in);
            assertTrue(farTtl.startsWith(":") && Long.parseLong(farTtl.substring(1)) > 2_209_500_000L,
                    "时刻在远未来时 TTL 要照它算（实测 2209587794 量级）: " + farTtl);
            send(socket, "EXPIREAT", "w:nope", "4102444800");
            assertEquals(":0", readReply(in), "不存在的键回 0");
            send(socket, "EXPIREAT", "w:future", "abc");
            assertEquals("-ERR value is not an integer or out of range", readReply(in));
            send(socket, "EXPIREAT", "w:future", "1e10");
            assertEquals("-ERR value is not an integer or out of range", readReply(in));
            send(socket, "EXPIREAT", "w:future", "1", "XX");
            assertEquals("-ERR wrong number of arguments for 'expireat' command", readReply(in));
            send(socket, "PEXPIREAT", "w:future", "4102444800000");
            assertEquals(":1", readReply(in));
            // 已知偏差一条：4.0.9 在 LLONG_MAX 上自己溢出，答成"已经过期"；本实现饱和到
            // 远未来。这一例钉的是<b>我们</b>的行为，不是对岸的 —— 差异写进 README 的偏差清单。
            send(socket, "EXPIREAT", "w:future", "9223372036854775807");
            assertEquals(":1", readReply(in), "本实现把秒→毫秒饱和，不回对岸的溢出结果");
            send(socket, "EXISTS", "w:future");
            assertEquals(":1", readReply(in));

            // LPUSHX / RPUSHX
            send(socket, "RPUSHX", "w:nolist", "v");
            assertEquals(":0", readReply(in));
            send(socket, "EXISTS", "w:nolist");
            assertEquals(":0", readReply(in), "键不存在时回 0 并且不把键建出来");
            send(socket, "LPUSHX", "w:nolist", "v");
            assertEquals(":0", readReply(in));
            send(socket, "TYPE", "w:nolist");
            assertEquals("+none", readReply(in));
            send(socket, "LPUSH", "w:l", "a");
            assertEquals(":1", readReply(in));
            send(socket, "LPUSHX", "w:l", "v1", "v2");
            assertEquals(":3", readReply(in));
            send(socket, "LRANGE", "w:l", "0", "-1");
            assertEquals("[v2, v1, a]", readReplyDeep(in), "多值时逐个压头，最后一个在最前");
            send(socket, "RPUSHX", "w:l", "r1", "r2");
            assertEquals(":5", readReply(in));
            send(socket, "LRANGE", "w:l", "0", "-1");
            assertEquals("[v2, v1, a, r1, r2]", readReplyDeep(in), "RPUSHX 按给定顺序接到尾部");
            send(socket, "LPUSHX", "w:l");
            assertEquals("-ERR wrong number of arguments for 'lpushx' command", readReply(in));
            send(socket, "SET", "w:str", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "LPUSHX", "w:str", "x");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
            send(socket, "HSTRLEN", "w:l", "f");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 位族的写入只 bump 它<b>真的改过</b>的那个键。两条各堵一个洞：
     * <ul>
     *   <li>{@code bumpWatchedKeys} 的通用形状是"键名在下标 1"，而 BITOP 的下标 1 是操作名 ——
     *       照通用形状走，它 bump 的是一个叫 "AND" 的键，真正被改写的目标键反倒没记到，
     *       于是 WATCH 目标键的事务永远不中止（反向的判据也一样重要：源键只被读，WATCH 源键
     *       不该被一次 BITOP 打掉）。</li>
     *   <li>SETBIT 在 1.3.6 之前压根没进 {@code WRITE_COMMANDS}：写进了内存却不记账，
     *       既不进 AOF，WATCH 它的键也看不见。</li>
     * </ul>
     * "目标键被覆盖、类型错的源一个字都不写"是对岸实测的（battery46 第 5—10、33—37 行），
     * 源键只读就是从那里来的。
     */
    @Test
    void bitWritesSignalOnlyTheKeysTheyActuallyChange() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket mine = connect(port); Socket other = connect(port)) {
            DataInputStream in = new DataInputStream(mine.getInputStream());
            DataInputStream oin = new DataInputStream(other.getInputStream());

            send(other, "SET", "sem:bit:src", "hello");
            assertEquals("+OK", readReply(oin), "前置条件: 源键写得进去");

            // 阳性：BITOP 改写目标键 —— WATCH 目标键必须中止
            queueProbe(mine, in, "sem:bit:dest", "sem:bit:src");
            send(other, "BITOP", "OR", "sem:bit:dest", "sem:bit:src");
            assertEquals(":5", readReply(oin), "前置条件: BITOP 本身要成功");
            send(mine, "EXEC");
            assertEquals("*-1", readReply(in), "WATCH 的键被 BITOP 改写，EXEC 必须中止");

            // 反向：源键只被读 —— 事务照常提交，取回的还是入队时那份值
            queueProbe(mine, in, "sem:bit:src", "sem:bit:src");
            send(other, "BITOP", "OR", "sem:bit:dest2", "sem:bit:src");
            assertEquals(":5", readReply(oin));
            send(mine, "EXEC");
            assertEquals("[hello]", readReplyDeep(in), "BITOP 读源不写源，WATCH 源键不该被打掉");

            // 阳性：SETBIT 写的是它自己的键
            queueProbe(mine, in, "sem:bit:k", "sem:bit:k");
            send(other, "SETBIT", "sem:bit:k", "3", "1");
            assertEquals(":0", readReply(oin));
            send(mine, "EXEC");
            assertEquals("*-1", readReply(in), "SETBIT 是写命令，WATCH 它的键必须中止");

            // 阴性对照：BITOP 碰的都是不相干的键，事务照常提交
            queueProbe(mine, in, "sem:bit:unrelated", "sem:bit:src");
            send(other, "BITOP", "OR", "sem:bit:dest3", "sem:bit:src");
            assertEquals(":5", readReply(oin));
            send(mine, "EXEC");
            assertEquals("[hello]", readReplyDeep(in), "不相干键的 BITOP 不该中止事务");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /** WATCH key + MULTI + GET probeKey；三条回包一并吃掉，返回入队是否成功。 */
    private static void queueProbe(Socket socket, DataInputStream in, String watched, String probeKey)
            throws IOException {
        send(socket, "WATCH", watched);
        assertEquals("+OK", readReply(in), "WATCH 要回 +OK");
        send(socket, "MULTI");
        assertEquals("+OK", readReply(in), "MULTI 要回 +OK");
        send(socket, "GET", probeKey);
        assertEquals("+QUEUED", readReply(in), "GET 入队要回 +QUEUED");
    }

    // ==================== helpers ====================

    /** 从 CLIENT LIST 的文本里按对端端口取出某条连接那一行；找不到返回 null。 */
    private static String lineFor(String clientList, int clientSideLocalPort) {
        String needle = ":" + clientSideLocalPort;
        for (String line : clientList.split("\r\n")) {
            int at = line.indexOf("addr=");
            if (at < 0) {
                continue;
            }
            String addr = line.substring(at + "addr=".length()).split(" ")[0];
            if (addr.endsWith(needle)) {
                return line;
            }
        }
        return null;
    }

    /** WATCH key + MULTI + SET key v：返回入队是否成功，把三条回包一并吃掉。 */
    private static boolean startWatchedTransaction(Socket socket, DataInputStream in, String key) throws IOException {
        send(socket, "WATCH", key);
        assertEquals("+OK", readReply(in), "WATCH 要回 +OK");
        send(socket, "MULTI");
        assertEquals("+OK", readReply(in), "MULTI 要回 +OK");
        send(socket, "SET", key, "mine");
        return "+QUEUED".equals(readReply(in));
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static Thread startAndWait(RedisServer server, int port) throws Exception {
        final java.util.concurrent.atomic.AtomicReference<Throwable> died =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try {
                server.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                died.set(t);
            }
        }, "semantics-test-server");
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!thread.isAlive()) {
                // 端口被抢占时 bind 失败只会以"守护线程死了"出现，调用方要么空转到超时
                // （看着像服务器的错），要么把一切归给猜测。异常随线程一起报出来。
                throw new IllegalStateException("server thread died before listening on " + port
                        + " —— 该线程带回来的异常: " + (died.get() == null
                            ? "无（线程干净退出却没开始监听）" : String.valueOf(died.get())));
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return thread;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("server did not start listening on " + port);
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        socket.setSoTimeout((int) DEADLINE_MS);
        return socket;
    }

    private static void send(Socket socket, String... args) throws IOException {
        StringBuilder sb = new StringBuilder("*" + args.length + "\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n").append(arg).append("\r\n");
        }
        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 隔一会儿再从另一条连接推一条命令，用来验"睡到有人推入"这条路径。 */
    private static void sendLater(Socket socket, long delayMillis, String... args) {
        Thread sender = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                send(socket, args);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                throw new IllegalStateException("delayed send failed", e);
            }
        }, "semantics-test-delayed-send");
        sender.setDaemon(true);
        sender.start();
    }

    /** 只解析测试用到的一层 RESP 形状：+/-/: 单行，$ bulk 按声明长度读满。 */
    private static String readReply(DataInputStream in) throws IOException {
        String line = readLine(in);
        if (line.isEmpty()) {
            return line;
        }
        return readBody(in, line);
    }

    /**
     * 递归读一个 RESP 值并压平成可读文本：数组 {@code [a, b, [c]]}，bulk 取字符串，
     * {@code *-1} / {@code $-1} 原样带出。既避开"只读了 {@code *} 头、payload 没吃"造成的
     * 字节流错位，也让 EXEC 中止这类判据能直接按字符串比对。
     */
    private static String readReplyDeep(DataInputStream in) throws IOException {
        String line = readLine(in);
        if (line.startsWith("*")) {
            int n = Integer.parseInt(line.substring(1));
            if (n < 0) {
                return line;
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(readReplyDeep(in));
            }
            return sb.append(']').toString();
        }
        return readBody(in, line);
    }

    /**
     * 连 RESP 的类型前缀一起读一个值：MONITOR 那类"形状本身就是判据"的用例需要分清
     * 服务器推的是 {@code +} 还是 {@code $}，而 {@link #readReply} 会把 bulk 的前缀吃掉。
     */
    private static String readWireReply(DataInputStream in) throws IOException {
        String line = readLine(in);
        if (line.startsWith("$")) {
            int length = Integer.parseInt(line.substring(1));
            if (length < 0) {
                return line;
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            in.readFully(new byte[2]);
            return "$" + new String(payload, StandardCharsets.UTF_8);
        }
        return line;
    }

    private static String readBody(DataInputStream in, String line) throws IOException {
        if (line.isEmpty() || line.charAt(0) != '$') {
            return line;
        }
        int length = Integer.parseInt(line.substring(1));
        if (length < 0) {
            return line;
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        in.readFully(new byte[2]);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read();
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }
}
