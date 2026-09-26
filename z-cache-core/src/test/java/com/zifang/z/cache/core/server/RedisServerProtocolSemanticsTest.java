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

            send(socket, "DEBUG", "SLEEP", "400");
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
        Thread thread = new Thread(() -> {
            try {
                server.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "semantics-test-server");
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!thread.isAlive()) {
                // 端口被别人抢占时，bind 失败只会以"守护线程死了 + 一段没人在读的栈"出现，
                // 调用方则空转到超时（8 秒后报"did not start listening"，看着像服务器的错）。
                // 早退并点明这一类，别让它混进被测代码的缺陷里。
                throw new IllegalStateException("server thread died before listening on " + port
                        + " —— 端口探测与 bind 之间的窗口被抢占属于量具问题，重跑即可");
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
