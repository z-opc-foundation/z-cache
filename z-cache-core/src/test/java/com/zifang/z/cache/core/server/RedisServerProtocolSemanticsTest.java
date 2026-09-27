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
            // 1.3.6 重钉：这一行原先断的是 "-ERR wrong number of arguments for 'xtrim' command"。
            // 上游 xtrim 的 arity 是 -2（server.c :327），`XTRIM k MAXLEN` 进得了函数；圈的判定是
            // `!strcasecmp(opt,"maxlen") && moreargs`（:2477），这里 moreargs=0 不成立，落进
            // :2498 的 else 即 `shared.syntaxerr`。两句都是错，但只有 syntax error 是上游那句。
            assertEquals("-ERR syntax error", readReply(in), "缺 count 时 moreargs 不成立，落 syntax err");
            send(socket, "XTRIM", "sem:trim", "MAXLEN", "3", "4");
            assertEquals("-ERR syntax error", readReply(in));
            send(socket, "XTRIM", "sem:trim", "MAXLEN", "-1");
            // 1.3.6 重钉：原来自造的 "MAXLEN requires a non-negative integer" 换成上游原文
            // （XTRIM :2491-2494，XADD :1268-1271 是同一句）。
            assertEquals("-ERR The MAXLEN argument must be >= 0.", readReply(in));
            send(socket, "XTRIM", "sem:trim", "MINID", "3");
            // 1.3.6 重钉：5.0.14 根本没有 MINID 这一策略，认不得的字一律 syntax err（:2498），
            // 而不是我们那句自造的 "unsupported XTRIM strategy"。意图不变：不能被当成 MAXLEN 蒙过去。
            assertTrue(readReply(in).startsWith("-ERR syntax error"),
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
     * Stream ID 的文法从协议这一侧走一遍：客户端写进来的每一个 ID 要么按上游的规矩收下并
     * 规范化，要么回 {@code Invalid stream ID specified as stream command argument}，
     * <b>绝不允许</b>把 Java 自己的报错文本（{@code For input string: "…"}）漏到线上。
     * <p>
     * 判据来自上游 {@code t_stream.c}（redis 5.0.14）而不是对拍：z-cache 钉的参考实例是
     * redis-server 4.0.9，Stream 是 5.0 才有的类型，4.0.9 对每条 XADD 都回
     * {@code -ERR unknown command 'XADD'}（250 实测 battery49 整批）。所以这一支只钉两件事：
     * <ol>
     *   <li>文法本身（哪些写法收、收下的怎么写回去、哪些拒）——逐条对上源码行号；</li>
     *   <li>"语法错不能被报成服务器内部错"——这是 1.3.6 之前的真实故障形态。</li>
     * </ol>
     * 旧实现两处都漏：{@code Long.parseLong} 抛出的异常被 {@code handle()} 兜成
     * {@code -ERR internal error: For input string: "1-1-1"}；而 {@code XREADGROUP} 的 ID 位
     * 一个字都不判，坏 ID 被当成 {@code 0-0} 于是回了整段历史（battery50:21 实测）。
     */
    @Test
    void streamIdsFollowTheUpstreamGrammarOverTheWire() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        java.util.List<String> wire = new java.util.ArrayList<>();
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- 收下并且规范化（addReplyStreamID 由数值反推写法，所以 "05-1" echo 成 "5-1"）----
            String[][] accepted = {
                    {"05-1", "5-1"},          // strtoull 吃前导零（:1156），string2ll 不吃
                    {"+1-1", "1-1"},          // 可选正号同理
                    {"7", "7-0"},             // 没有 '-' 时补 missing_seq=0（:1199）
                    {" 1", "1-0"},            // 前导空白同理
                    {"1-01", "1-1"},
                    {"18446744073709551615-1", "18446744073709551615-1"}, // uint64 不是 int64
            };
            for (int row = 0; row < accepted.length; row++) {
                // 每行一个键：这一支量的是"写法怎么规范化"，不能让上一条的表顶把这一条
                // 按单调性闸挡下来（+1-1 规范成 1-1，而 05-1 已经写过 5-1）。
                send(socket, "XADD", "sem:sid" + row, accepted[row][0], "f", "v");
                wire.add(readReply(in));
            }
            for (int i = 0; i < accepted.length; i++) {
                assertEquals(accepted[i][1], wire.get(i),
                        "XADD \"" + accepted[i][0] + "\" 要按上游的规范化写法回写");
            }

            // ---- 拒掉，且拒的那句话逐字是上游 :1205 的原文 ----
            String[] rejected = {
                    "abc", "1-1-1", "1-", "1 ", "-1", "1--1",
                    "18446744073709551616-0", "92233720368547758081-0",
                    "+", "-",                        // XADD 是 strict 位（:1276）
            };
            for (String bad : rejected) {
                send(socket, "XADD", "sem:sid", bad, "f", "v");
                wire.add(readReply(in));
                assertEquals("-ERR " + com.zifang.z.cache.common.protocol.StreamIdFormat.INVALID_ID,
                        wire.get(wire.size() - 1), "XADD \"" + bad + "\" 必须按上游原文拒");
            }

            // 0-0 是另一句：上游 :1293 专门提前挡它，否则"建了流又插不进去"会留下空键
            send(socket, "XADD", "sem:sid", "0-0", "f", "v");
            wire.add(readReply(in));
            assertEquals("-ERR The ID specified in XADD must be greater than 0-0",
                    wire.get(wire.size() - 1));
            send(socket, "XADD", "sem:sid", "0", "f", "v");
            wire.add(readReply(in));
            assertEquals("-ERR The ID specified in XADD must be greater than 0-0",
                    wire.get(wire.size() - 1), "没有 '-' 时补 seq=0，于是 \"0\" 也是 0-0");

            // ---- 读侧：范围两端都不 strict，但缺省 seq 不对称（:1356-1357）----
            send(socket, "XADD", "sem:range", "1-1", "f", "a");
            readReply(in);
            send(socket, "XADD", "sem:range", "1-2", "f", "b");
            readReply(in);
            send(socket, "XADD", "sem:range", "1-18446744073709551615", "f", "c");
            readReply(in);
            send(socket, "XADD", "sem:range", "2-0", "f", "d");
            readReply(in);
            send(socket, "XRANGE", "sem:range", "1", "1");
            wire.add(readReplyDeep(in));
            assertEquals("[[1-1, [f, a]], [1-2, [f, b]], [1-18446744073709551615, [f, c]]]",
                    wire.get(wire.size() - 1),
                    "起点 \"1\" 补 seq=0，终点 \"1\" 补 seq=UINT64_MAX —— 两端都缺就是全要");
            send(socket, "XRANGE", "sem:range", "-", "-");
            assertEquals("[]", readReplyDeep(in),
                    "\"-\" 是最小 ID（0-0）本身，不是\"第一条之前\"；而 0-0 存不进来（:1293），所以这一对恒空");
            send(socket, "XRANGE", "sem:range", "-", "1-1");
            assertEquals("[[1-1, [f, a]]]", readReplyDeep(in), "把 \"-\" 当起点用才是全量扫描的头");
            send(socket, "XRANGE", "sem:range", "+", "+");
            assertEquals("[]", readReplyDeep(in), "\"+\" 同理是最大 ID，不是\"最后一条之后\"");
            send(socket, "XRANGE", "sem:range", "1-2", "+");
            assertEquals("[[1-2, [f, b]], [1-18446744073709551615, [f, c]], [2-0, [f, d]]]",
                    readReplyDeep(in),
                    "无符号的 UINT64_MAX 那一条必须排在 2-0 之前——有符号比较会把它送到队尾");
            send(socket, "XRANGE", "sem:range", "abc", "+");
            wire.add(readReply(in));
            assertTrue(wire.get(wire.size() - 1).startsWith("-ERR Invalid stream ID"), "范围起点也要判文法");

            // ---- XDEL：先把每个 ID 判一遍再动手（:2420-2427），不能删一半才报错 ----
            send(socket, "XDEL", "sem:range", "1-1", "not-an-id");
            wire.add(readReply(in));
            assertEquals("-ERR " + com.zifang.z.cache.common.protocol.StreamIdFormat.INVALID_ID,
                    wire.get(wire.size() - 1));
            send(socket, "XLEN", "sem:range");
            assertEquals(":4", readReply(in), "ID 有一个非法就一条都不许删");

            // ---- XREAD / XREADGROUP：$ 与 > 各有专属句子，其余一律 strict ----
            send(socket, "XGROUP", "CREATE", "sem:range", "g", "$");
            assertEquals("+OK", readReply(in));
            send(socket, "XGROUP", "CREATE", "sem:range", "g-bad", "not-an-id");
            wire.add(readReply(in));
            assertTrue(wire.get(wire.size() - 1).startsWith("-ERR Invalid stream ID"),
                    "XGROUP CREATE 的 ID 位是 strict（:1869）");

            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:range", "abc");
            wire.add(readReply(in));
            assertEquals("-ERR " + com.zifang.z.cache.common.protocol.StreamIdFormat.INVALID_ID,
                    wire.get(wire.size() - 1),
                    "以前这一位不判，坏 ID 变成 0-0 于是回了整段历史");
            send(socket, "XREAD", "STREAMS", "sem:range", "abc");
            wire.add(readReply(in));
            assertEquals("-ERR " + com.zifang.z.cache.common.protocol.StreamIdFormat.INVALID_ID,
                    wire.get(wire.size() - 1));
            send(socket, "XREAD", "STREAMS", "sem:range", ">");
            wire.add(readReply(in));
            assertTrue(wire.get(wire.size() - 1).startsWith("-ERR The > ID can be specified only when calling XREADGROUP"),
                    "\">\" 只在 XREADGROUP 上合法（:1535-1539），拒绝它要用那句原文而不是语法错");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:range", "$");
            wire.add(readReply(in));
            assertTrue(wire.get(wire.size() - 1).startsWith("-ERR The $ ID is meaningless in the context of XREADGROUP"),
                    "$ 在 XREADGROUP 上是另一句（:1518-1524）");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:range", ">");
            assertEquals("*-1", readReplyDeep(in),
                    "\">\" 是合法写法（:1535），而这条组创建于 $ 之后没有新条目 —— 上游在这种情况下"
                            + "回的正是 nullmultibulk（:1664），不是空数组");

            // ---- 这一支真正的兜底：任何一条回复里都不许出现 JVM 的内部文本 ----
            for (String reply : wire) {
                assertFalse(reply.contains("For input string"),
                        "语法错被报成内部错，正是 1.3.6 之前的形态: " + reply);
                assertFalse(reply.contains("java.lang"), "客户端不该看到 JVM 类名: " + reply);
                assertFalse(reply.contains("internal error"), "客户端不该看到 internal error: " + reply);
            }
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * XADD 的单调性：等于或小于表顶的 ID 一律不写，ID 用尽之后连 {@code *} 也不写。
     * <p>
     * 补这一条之前（本机 {@code battery50:27/28} 实测）：往已经有 {@code 1-3} 的流里再写
     * {@code 1-2}、{@code 1-1} 都成功，于是同一个 ID 在流里出现两次、条目顺序也不升 ——
     * {@code XRANGE} 交回的就是乱序表，而 {@code XREAD} 那类"按 ID 续读"的用法直接失去意义。
     * 上游为此有两句不同的话：{@code :1315}（等于或小于表顶）与 {@code :1304}（ID 用尽），
     * 后者排在前面，所以连显式的小 ID 也回 {@code :1304} 那一句。
     * <p>
     * 依据档次与上一支相同：4.0.9 不认 Stream（{@code battery49} 整批
     * {@code -ERR unknown command 'XADD'}），所以这里钉的是上游源码的判序，不是对拍读数。
     */
    @Test
    void xaddRejectsIdsAtOrBelowTheStreamTop() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String atOrBelow = "-ERR The ID specified in XADD is equal or smaller than "
                    + "the target stream top item";
            String exhausted = "-ERR The stream has exhausted the last possible ID, "
                    + "unable to add more items";

            send(socket, "XADD", "sem:mono", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));

            // 等于表顶、seq 更小、ms 更小 —— 三种"不升"都要拒
            for (String[] bad : new String[][]{{"1-1", "等于表顶"}, {"1-0", "同 ms 但 seq 更小"},
                    {"0-5", "ms 更小，尽管 seq 更大"}}) {
                send(socket, "XADD", "sem:mono", bad[0], "b", "2");
                assertEquals(atOrBelow, readReply(in), bad[1] + " 的写法不能写进去");
            }
            send(socket, "XLEN", "sem:mono");
            assertEquals(":1", readReply(in), "被拒的三条一条都没写进去");
            send(socket, "XRANGE", "sem:mono", "-", "+");
            assertEquals("[[1-1, [a, 1]]]", readReplyDeep(in), "表里仍然是那一条，没有重复 ID");

            // 升序照写；写完之后再回头写小的还是拒
            send(socket, "XADD", "sem:mono", "1-2", "b", "2");
            assertEquals("1-2", readReply(in));
            send(socket, "XADD", "sem:mono", "18446744073709551615-18446744073709551615", "c", "3");
            assertEquals("18446744073709551615-18446744073709551615", readReply(in),
                    "uint64 的最大值本身是合法 ID，写进去就把 ID 空间用尽了");

            // 用尽之后的两句判序：:1304 在 :1315 之前，所以小 ID 与 * 都回"用尽"那句
            send(socket, "XADD", "sem:mono", "1-1", "d", "4");
            assertEquals(exhausted, readReply(in),
                    "这一条既小于表顶、又落在 ID 用尽之后 —— 上游先回用尽（:1304 早于 append）");
            send(socket, "XADD", "sem:mono", "*", "d", "4");
            assertEquals(exhausted, readReply(in), "自增 ID 也没有位置可给了");
            send(socket, "XLEN", "sem:mono");
            assertEquals(":3", readReply(in));
            send(socket, "XRANGE", "sem:mono", "-", "+");
            assertEquals("[[1-1, [a, 1]], [1-2, [b, 2]], [18446744073709551615-18446744073709551615, [c, 3]]]",
                    readReplyDeep(in), "顺序必须还是升的：无符号比较把最大 ID 放到队尾，有符号比较会把它放到队首");

            // 表顶是"迄今写过的最大 ID"，不是"最后一条条目"：删空、裁空都不许把 ID 空间退回来
            send(socket, "XADD", "sem:persist", "5-5", "f", "v");
            assertEquals("5-5", readReply(in));
            send(socket, "XADD", "sem:persist", "5-6", "f", "v");
            assertEquals("5-6", readReply(in));
            send(socket, "XDEL", "sem:persist", "5-5", "5-6");
            assertEquals(":2", readReply(in));
            send(socket, "XLEN", "sem:persist");
            assertEquals(":0", readReply(in));
            send(socket, "XADD", "sem:persist", "5-5", "f", "v");
            assertEquals(atOrBelow, readReply(in), "流都空了也不能把 5-5 重发一遍");
            send(socket, "XADD", "sem:persist", "5-6", "f", "v");
            assertEquals(atOrBelow, readReply(in));
            send(socket, "XADD", "sem:persist", "5-7", "f", "v");
            assertEquals("5-7", readReply(in), "比表顶大的照写");

            send(socket, "XTRIM", "sem:persist", "MAXLEN", "0");
            assertEquals(":1", readReply(in));
            send(socket, "XADD", "sem:persist", "5-7", "f", "v");
            assertEquals(atOrBelow, readReply(in), "XTRIM 裁空同样不退表顶");

            // 被拒的 XADD 不得留下键（上游把 0-0 提前挡掉就是为了这件事，:1293 的注释）
            send(socket, "XADD", "sem:fresh", "abc", "f", "v");
            assertTrue(readReply(in).startsWith("-ERR Invalid stream ID"));
            send(socket, "EXISTS", "sem:fresh");
            assertEquals(":0", readReply(in), "语法错的 XADD 不能先把键建出来");
            send(socket, "XADD", "sem:fresh2", "1-1", "f", "v");
            assertEquals("1-1", readReply(in), "键不存在时表顶就是 0-0，任何合法显式 ID 都该照常写");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * XREAD / XREADGROUP 的 ID 位是一个"位置"，不是一个"范围起点"。
     * <p>
     * 上游 {@code t_stream.c:1560} 在那一行写的就是 "ID must be greater than this."，
     * 而它交给范围查询的 start 是 {@code streamIncrID} 之后的值（:1602-1603）—— 于是
     * {@code XREAD STREAMS k 1-1} 交回的是 {@code 1-2} 往后，<b>不含 1-1 自己</b>。
     * 这一支把三件事钉在一起，它们共用同一个位置概念：
     * <ol>
     *   <li>严格大于；"这个位置之后没东西"时<b>整个键不点名</b>（:1586-1593 先问存活条目的最大值），
     *       一个键都没点名就是 {@code *-1}；</li>
     *   <li>{@code $} 是"这条流当前的位置"（:1527-1533 取 {@code s->last_id}），不是整条流；</li>
     *   <li>{@code XREADGROUP} 带明确 ID 读的是<b>这个消费者自己的 PEL</b>（:981-984 整支换掉
     *       查询来源，:1083 只认本地 PEL），所以别的消费者领走的东西不会出现在这里，
     *       而且<b>空历史也要点名这个键</b>（:1596-1598 的 arraylen 无条件自增）。</li>
     * </ol>
     * 判据来自源码不是对拍：参考实例是 redis-server 4.0.9，它压根没有 stream 命令族。
     * 改之前这几条实测（battery52 共 17 行答错）：{@code XREAD … 1-1} 把 1-1 自己也交出去、
     * {@code XREAD … $} 交回整条流、{@code XREADGROUP … <别人的位置>} 把整条流当历史交给
     * 一个从没领过任何条目的消费者，而被 XDEL 带走的那条在历史里静默消失。
     */
    @Test
    void xreadPositionsAreExclusiveAndHistoryComesFromTheConsumerPel() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "sem:pos", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "sem:pos", "1-2", "a", "2");
            assertEquals("1-2", readReply(in));
            send(socket, "XADD", "sem:pos", "2-0", "a", "3");
            assertEquals("2-0", readReply(in));

            // ---- 严格大于 ----
            send(socket, "XREAD", "STREAMS", "sem:pos", "0-0");
            assertEquals("[[sem:pos, [[1-1, [a, 1]], [1-2, [a, 2]], [2-0, [a, 3]]]]]",
                    readReplyDeep(in), "0-0 之后就是全部");
            send(socket, "XREAD", "STREAMS", "sem:pos", "1-1");
            assertEquals("[[sem:pos, [[1-2, [a, 2]], [2-0, [a, 3]]]]]",
                    readReplyDeep(in), "1-1 自己不算——它是客户端已经读走的那一条");
            send(socket, "XREAD", "STREAMS", "sem:pos", "2-0");
            assertEquals("*-1", readReplyDeep(in), "没有更大的了就连键名都不该出现，不是空列表");
            send(socket, "XREAD", "COUNT", "1", "STREAMS", "sem:pos", "1-1");
            assertEquals("[[sem:pos, [[1-2, [a, 2]]]]]", readReplyDeep(in), "COUNT 是在严格大于之后截");
            send(socket, "XREAD", "COUNT", "0", "STREAMS", "sem:pos", "1-1");
            assertEquals("[[sem:pos, [[1-2, [a, 2]], [2-0, [a, 3]]]]]",
                    readReplyDeep(in), "COUNT 0 在上游是「不限」（:1441 负数折 0，:1063 0 不截断）");
            send(socket, "XREAD", "STREAMS", "sem:pos", "1-18446744073709551615");
            assertEquals("[[sem:pos, [[2-0, [a, 3]]]]]",
                    readReplyDeep(in), "seq 段用尽时后继进到毫秒段（streamIncrID :78-85）");
            send(socket, "XREAD", "STREAMS", "sem:pos", "18446744073709551615-18446744073709551615");
            assertEquals("*-1", readReplyDeep(in),
                    "MAX-MAX 的后继回绕成 0-0，XREAD 靠:1591 那道闸挡住它，不能因此交出全流");

            // ---- $ 是"当前的位置" ----
            send(socket, "XREAD", "STREAMS", "sem:pos", "$");
            assertEquals("*-1", readReplyDeep(in), "$ 就是最后一个条目，其后无物");
            send(socket, "XREAD", "STREAMS", "sem:ghost", "$");
            assertEquals("*-1", readReplyDeep(in), "键不在时 $ 折成 0-0，但键不在就不 serve");
            send(socket, "XADD", "sem:pos", "3-0", "a", "4");
            assertEquals("3-0", readReply(in));
            send(socket, "XREAD", "STREAMS", "sem:pos", "$");
            assertEquals("*-1", readReplyDeep(in));
            send(socket, "XDEL", "sem:pos", "3-0");
            assertEquals(":1", readReply(in));
            send(socket, "XREAD", "STREAMS", "sem:pos", "$");
            assertEquals("*-1", readReplyDeep(in), "表顶不因 XDEL 后退，$ 也就还停在 3-0");
            send(socket, "XREAD", "STREAMS", "sem:pos", "1-2");
            assertEquals("[[sem:pos, [[2-0, [a, 3]]]]]",
                    readReplyDeep(in), "读得到的条目仍然读得到——$ 之外没有别的位置被抬高");
            send(socket, "XREAD", "STREAMS", "sem:pos", "2-0");
            assertEquals("*-1", readReplyDeep(in),
                    "2-0 之后已经没有活着的条目：3-0 被 XDEL 带走了，表顶仍停在 3-0 也不该让这个键被点名"
                            + "（:1590 拿的是 streamLastValidID，不是 s->last_id）");

            // ---- XREADGROUP 的历史位：各消费者一份 PEL ----
            send(socket, "XADD", "sem:grp", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "sem:grp", "2-0", "a", "2");
            assertEquals("2-0", readReply(in));
            send(socket, "XGROUP", "CREATE", "sem:grp", "g", "0-0");
            assertEquals("+OK", readReply(in));

            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp", ">");
            assertEquals("[[sem:grp, [[1-1, [a, 1]], [2-0, [a, 2]]]]]", readReplyDeep(in));
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp", "0-0");
            assertEquals("[[sem:grp, [[1-1, [a, 1]], [2-0, [a, 2]]]]]",
                    readReplyDeep(in), "c1 的历史就是它手上没 ACK 的两条");
            send(socket, "XREADGROUP", "GROUP", "g", "c2", "STREAMS", "sem:grp", "0-0");
            assertEquals("[[sem:grp, []]]", readReplyDeep(in),
                    "c2 一条都没领过：点名这个键、给空列表，而不是把整条流当它的历史");
            send(socket, "XACK", "sem:grp", "g", "2-0");
            assertEquals(":1", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp", "0-0");
            assertEquals("[[sem:grp, [[1-1, [a, 1]]]]]", readReplyDeep(in), "ACK 过的那条从历史里退掉");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp", "1-0");
            assertEquals("[[sem:grp, [[1-1, [a, 1]]]]]", readReplyDeep(in),
                    "1-0 的后继正好是 1-1：位置本身排他，加过一之后那一头是闭区间");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp", "1-1");
            assertEquals("[[sem:grp, []]]", readReplyDeep(in),
                    "历史位也一样排他：1-1 正是 c1 手上那条，位置写它自己也不能把它再交出来"
                            + "（:1603 加过一，:1093 才按 >= 在 PEL 上 seek）");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp", "2-0");
            assertEquals("[[sem:grp, []]]", readReplyDeep(in), "2-0 之后的历史是空的，但键仍要点名");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp", "2-1");
            assertEquals("[[sem:grp, []]]", readReplyDeep(in),
                    "1-1 落在 2-1 之前，不该被当成 2-1 之后的历史交出去");
            // 同一个键写两遍是两问，不是"后者覆盖前者"：上游那一段是数组，不是查表
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp", "sem:grp", "0-0", "2-0");
            assertEquals("[[sem:grp, [[1-1, [a, 1]]]], [sem:grp, []]]", readReplyDeep(in),
                    "两个位置各答各的，重复的键名不会被并成一个");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:grp",
                    "18446744073709551615-18446744073709551615");
            assertEquals("[[sem:grp, [[1-1, [a, 1]]]]]", readReplyDeep(in),
                    "历史位没有:1591 那道闸，于是看得见 MAX-MAX 后继回绕成 0-0 的效果");
            send(socket, "XINFO", "CONSUMERS", "sem:grp", "g");
            String consumers = readReplyDeep(in);
            assertTrue(consumers.contains("c2, pending, :0"),
                    "读一份空历史也要把这个消费者登记出来: " + consumers);

            // ---- 历史里被 XDEL 带走的那条：交回 [id, nil]，不是悄悄少一条 ----
            send(socket, "XADD", "sem:gone", "1-1", "f", "v");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "sem:gone", "1-2", "f", "w");
            assertEquals("1-2", readReply(in));
            send(socket, "XGROUP", "CREATE", "sem:gone", "g", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:gone", ">");
            assertEquals("[[sem:gone, [[1-1, [f, v]], [1-2, [f, w]]]]]", readReplyDeep(in));
            send(socket, "XDEL", "sem:gone", "1-1");
            assertEquals(":1", readReply(in));
            send(socket, "XREAD", "STREAMS", "sem:gone", "0-0");
            assertEquals("[[sem:gone, [[1-2, [f, w]]]]]", readReplyDeep(in), "读流：1-1 已经不在");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:gone", "0-0");
            assertEquals("[[sem:gone, [[1-1, *-1], [1-2, [f, w]]]]]", readReplyDeep(in),
                    "读 PEL：这条还压在账上，内容没了要明说，否则客户端以为从没领过它");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * {@code $} 与 {@code >} 各只属于一个命令，而"用错了命令"有自己的一句话
     * （{@code t_stream.c:1519-1525}、{@code :1536-1541}），不是通用的
     * {@code Invalid stream ID specified as stream command argument}：拿语法错去回答
     * "用法不对"会把客户端指错方向。两句都在网线上逐字钉住，并且要在<b>整条命令</b>层面生效
     * ——上游是 {@code goto cleanup}，同一条命令里后面那些合法的位置也一并作废。
     */
    @Test
    void eachSpecialPositionIdBelongsToOneCommand() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "sem:sp", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XGROUP", "CREATE", "sem:sp", "g", "0-0");
            assertEquals("+OK", readReply(in));

            // 阳性对照：两个特例在自家命令上都要被收下，否则下面的负判据是空跑
            send(socket, "XREAD", "STREAMS", "sem:sp", "$");
            assertEquals("*-1", readReply(in), "$ 在 XREAD 上合法，只是那个位置之后没东西");
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:sp", ">");
            assertEquals("[[sem:sp, [[1-1, [a, 1]]]]]", readReplyDeep(in), "> 在 XREADGROUP 上合法");

            String dollar = "-ERR The $ ID is meaningless in the context of XREADGROUP: "
                    + "you want to read the history of this consumer by specifying a proper ID, "
                    + "or use the > ID to get new messages. The $ ID would just return an "
                    + "empty result set.";
            String gt = "-ERR The > ID can be specified only when calling XREADGROUP "
                    + "using the GROUP <group> <consumer> option.";

            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:sp", "$");
            assertEquals(dollar, readReply(in), "$ 在 XREADGROUP 上要回它自己那句话");
            send(socket, "XREAD", "STREAMS", "sem:sp", ">");
            assertEquals(gt, readReply(in), "> 在 XREAD 上要回它自己那句话");

            // 整条命令作废：后面那个位置是合法的，也不许先把结果交出去
            send(socket, "XREADGROUP", "GROUP", "g", "c2", "STREAMS", "sem:sp", "sem:sp", "$", "0-0");
            assertEquals(dollar, readReply(in));
            send(socket, "XREAD", "STREAMS", "sem:sp", "sem:sp", "0-0", ">");
            assertEquals(gt, readReply(in));
            send(socket, "XINFO", "CONSUMERS", "sem:sp", "g");
            String consumers = readReplyDeep(in);
            assertTrue(consumers.contains("name, c1"), "c1 是被前面那次 \">\" 真读过的: " + consumers);
            assertTrue(!consumers.contains("c2"),
                    "被作废的那两条命令连消费者都不该登记出来: " + consumers);

            send(socket, "XREADGROUP", "GROUP", "g", "c2", "STREAMS", "sem:sp", "0-0");
            assertEquals("[[sem:sp, []]]", readReplyDeep(in),
                    "c2 的账上是空的：作废的那两条一条都没投递给它，读历史也就什么都没有");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * XPENDING 的汇总形式要给出每个消费者的真实待确认数。
     * <p>
     * 旧实现填的是 {@code consumer.getPendingCount()} —— 那个字段从没自增过，恒为 0。
     * 现在按 pending 表现数。至于"账上为 0 的消费者列不列"是另一问，而且两问答案相反
     * （汇总不列、XINFO CONSUMERS 列），那一问钉在
     * {@link #xpendingSummaryShapeFollowsTheReference}。
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
     * XPENDING 汇总第 4 项的形状，两处都是"看着像、其实不是"：
     * <ul>
     *   <li>PEL 空时上游发 {@code shared.nullmultibulk}（t_stream.c:2059-2062），线上一行
     *       {@code *-1}；我们发的是 {@code *0}。RESP 里这是两个值：{@code *0} 说"有零个消费者"，
     *       {@code *-1} 说"这一项没有"。按 null 分支的客户端会把前者当成一个真实读数。</li>
     *   <li>PEL 非空时 :2086 那句 {@code if (raxSize(consumer->pel) == 0) continue;} 跳过
     *       手上没货的消费者；我们照单全列，于是多出一行 {@code [c2, "0"]}。</li>
     * </ul>
     * 两条都只能在命令层修：同一个现场 XINFO CONSUMERS（:2568 按 {@code raxSize(cg->consumers)}
     * 整份列出）必须仍看到 c2，所以下面把两个读者放在一起断言。一句实话打底：这一句阳性对照
     * <b>拦不住</b>"把过滤下沉进 {@code perConsumerPending()}"那种改法 —— XINFO 那一支自己遍历
     * {@code getConsumers()}、计数用 {@code getOrDefault(…, 0L)} 兜，下沉之后两侧都绿（探针 U4
     * 实测 SURVIVED，等价变异）。它拦的是"0 条这个读数本身有没有人钉"。
     * 补一支反方向的 U7（把那道兜底换成裸 {@code get(…)}）：单独打同样不红，因为
     * {@code perConsumerPending()} 的种子行保证了键必在；<b>U4 与 U7 一起打才炸</b>（XINFO 的计数
     * 拿到 null），所以这两处是一对备份，不是两处各有人读的口径。
     */
    @Test
    void xpendingSummaryShapeFollowsTheReference() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "xp:shape", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XGROUP", "CREATE", "xp:shape", "g", "0-0");
            assertEquals("+OK", readReply(in));

            send(socket, "XPENDING", "xp:shape", "g");
            assertEquals("*4", readWireReply(in), "addReplyMultiBulkLen(c,4)");
            assertEquals(":0", readWireReply(in));
            assertEquals("$-1", readWireReply(in), "start 是 nullbulk");
            assertEquals("$-1", readWireReply(in), "end 是 nullbulk");
            assertEquals("*-1", readWireReply(in), ":2062 那一支发 nullmultibulk，不是空数组");

            // 领走那一条，PEL 就此非空
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "COUNT", "1", "STREAMS", "xp:shape", ">");
            assertTrue(readReplyDeep(in).contains("1-1"), "先确认真的投递过");
            // 再造一个"存在而手上没货"的消费者。CREATECONSUMER 是 6.2 才有的（本仓提前做了），
            // 这里只当量具用：不为测它，只为造出 :2086 那句 continue 的现场。
            send(socket, "XGROUP", "CREATECONSUMER", "xp:shape", "g", "c2");
            assertEquals(":1", readReply(in));

            send(socket, "XPENDING", "xp:shape", "g");
            assertEquals("*4", readWireReply(in));
            assertEquals(":1", readWireReply(in));
            assertEquals("$1-1", readWireReply(in));
            assertEquals("$1-1", readWireReply(in));
            assertEquals("*1", readWireReply(in), ":2086 跳过 PEL 为空的 c2，只剩一项");
            assertEquals("*2", readWireReply(in));
            assertEquals("$c1", readWireReply(in));
            assertEquals("$1", readWireReply(in), "计数是 addReplyBulkLongLong，不是 integer");

            // 阳性对照：同一个现场 XINFO CONSUMERS 必须仍列出 0 条的 c2。顺带能看见两问的
            // 计数不同形：这里是 :2582 的 addReplyLongLong（integer），而 XPENDING 那一行是
            // :2089 的 addReplyBulkLongLong（bulk）—— 同名同数，类型是两个命令各自的。
            send(socket, "XINFO", "CONSUMERS", "xp:shape", "g");
            String consumers = readReplyDeep(in);
            assertTrue(consumers.contains("[name, c1, pending, :1"), consumers);
            assertTrue(consumers.contains("[name, c2, pending, :0"),
                    "XINFO CONSUMERS 按整份消费者表列，含 0 条的: " + consumers);

            send(socket, "XACK", "xp:shape", "g", "1-1");
            assertEquals(":1", readReply(in));
            // 账清了。此刻 c1、c2 两个消费者都还在，所以第 4 项回到 *-1 钉的是"PEL 空"这个判据，
            // 而不是"有没有消费者"—— 后者会做出一个能过前三行、过不了这一段的答案。
            send(socket, "XPENDING", "xp:shape", "g");
            assertEquals("*4", readWireReply(in));
            assertEquals(":0", readWireReply(in));
            assertEquals("$-1", readWireReply(in));
            assertEquals("$-1", readWireReply(in));
            assertEquals("*-1", readWireReply(in), "消费者还在而 PEL 已空，仍是 null 数组");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * XREAD / XREADGROUP 的选项那一圈（上游 t_stream.c:1430-1486）改前只有三个出口：
     * 一个自造的 arity 句、一个 syntax error、以及"把没配对的清单当成位置写法不对"。
     * 上游给的是四句各有各位置的话，加上 STREAMS 之后那两列的配对判据排在取键与查类型<b>之前</b>。
     * NOACK 那一支顺带钉住它只做一件事：不记 PEL —— 组的投递位置照样推进、消费者照样存在
     * （:990-992 与 :1610 的 {@code SLC_NONE}）。
     */
    @Test
    void xreadOptionSentencesFollowTheReference() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "xr:opt", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XGROUP", "CREATE", "xr:opt", "g", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "SET", "xr:str", "v");
            assertEquals("+OK", readReply(in));

            // arity 下限（server.c:318 的 -4）排在选项扫描之前：这一行是控制项，
            // 它保证下面那句 Unbalanced 不是从"参数太少"里绕出来的。
            send(socket, "XREAD", "STREAMS", "xr:opt");
            assertEquals("-ERR wrong number of arguments for 'xread' command", readReply(in));

            String unbalanced = "-ERR Unbalanced XREAD list of streams: "
                    + "for each stream key an ID or '$' must be specified.";
            // 两键一位（:1445-1449 的奇数那一问）
            send(socket, "XREAD", "STREAMS", "xr:opt", "xr:opt", "0-0");
            assertEquals(unbalanced, readReply(in));
            // 带 COUNT 也一样：配对那一问看的是 STREAMS 之后的词数
            send(socket, "XREAD", "COUNT", "2", "STREAMS", "xr:opt");
            assertEquals(unbalanced, readReply(in));
            // 预排在类型那一问之前：清单里混着一个 String 键，报的仍是配对而不是 WRONGTYPE。
            // 改前这一格与上面那格答的是两句不同的话（一句 WRONGTYPE 一句坏 ID），
            // 说明它当时根本没有"配对"这一问。
            send(socket, "XREAD", "STREAMS", "xr:str", "xr:str", "0-0");
            assertEquals(unbalanced, readReply(in));

            String groupMine = "-ERR The GROUP option is only supported by XREADGROUP. "
                    + "You called XREAD instead.";
            String noackMine = "-ERR The NOACK option is only supported by XREADGROUP. "
                    + "You called XREAD instead.";
            send(socket, "XREAD", "GROUP", "g", "c", "STREAMS", "xr:opt", "0-0");
            assertEquals(groupMine, readReply(in));
            send(socket, "XREAD", "NOACK", "STREAMS", "xr:opt", "0-0");
            assertEquals(noackMine, readReply(in));
            // 两句都排在"没见过 STREAMS"那一问之前（:1475-1479），所以后面没有 STREAMS 也照样报它
            send(socket, "XREAD", "GROUP", "g", "c", "NOACK");
            assertEquals(groupMine, readReply(in));

            // XREADGROUP 的 arity 是 -7（server.c:319）：少 GROUP 而词数够不到的写法，
            // 要在选项那一圈里报"Missing GROUP"，够不到的在闸外就报了 arity。
            send(socket, "XREADGROUP", "STREAMS", "xr:opt", "0-0");
            assertEquals("-ERR wrong number of arguments for 'xreadgroup' command", readReply(in));
            send(socket, "XREADGROUP", "COUNT", "2", "STREAMS", "xr:opt", "xr:opt", "0-0", "0-0");
            assertEquals("-ERR Missing GROUP option for XREADGROUP", readReply(in));
            // 判序控制项：两个特例问都排在"没见过 STREAMS"之后（:1476 在 :1483 之前）。
            // 这一行选项全认得、词数够 -7 的闸，而清单里根本没有 STREAMS —— 报的必须是 syntax error，
            // 把上面那两问挪到它前面就变成"Missing GROUP"。
            send(socket, "XREADGROUP", "COUNT", "1", "NOACK", "COUNT", "2", "NOACK");
            assertEquals("-ERR syntax error", readReply(in));

            // NOACK 交的是"投出去而不记账"
            send(socket, "XREADGROUP", "GROUP", "g", "c", "NOACK", "COUNT", "1",
                    "STREAMS", "xr:opt", ">");
            String delivered = readReplyDeep(in);
            assertTrue(delivered.contains("1-1"), "NOACK 也要把条目投出去: " + delivered);
            send(socket, "XPENDING", "xr:opt", "g");
            assertEquals("*4", readWireReply(in));
            assertEquals(":0", readWireReply(in), "NOACK 不记 PEL（:1020 那一块整段跳过）");
            assertEquals("$-1", readWireReply(in));
            assertEquals("$-1", readWireReply(in));
            assertEquals("*-1", readWireReply(in));
            // 消费者还是要被建出来：:1610 用 SLC_NONE，而那个函数"查不到就顺手创建"（:1745-1758）
            send(socket, "XINFO", "CONSUMERS", "xr:opt", "g");
            String consumers = readReplyDeep(in);
            assertTrue(consumers.contains("[name, c, pending, :0"),
                    "NOACK 读过之后消费者存在而手上没货: " + consumers);
            // 组的投递位置也确实推进了：再来一次 > 是空的（不是重投同一条）
            send(socket, "XREADGROUP", "GROUP", "g", "c", "COUNT", "1", "STREAMS", "xr:opt", ">");
            assertEquals("*-1", readWireReply(in), "位置已推进，同一条不重投");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * XINFO GROUPS 的一行有 <b>8</b> 个元素，第四对是 {@code last-delivered-id}
     * （上游 t_stream.c:2600-2608，那个值是 :2608 的 {@code addReplyStreamID} 发的 bulk），
     * 而行的先后由那棵 rax 决定：:2594-2597 是 {@code raxSize} + {@code raxSeek("^")} +
     * {@code raxNext}，也就是<b>按组名升序</b>，不是建组的先后。
     * <p>
     * 两边以前都不对：行只有 6 个元素（组的位置只在 {@code XGROUP CREATE/SETID} 里写进对象、
     * 从没交出去），而遍历的是 {@code ConcurrentHashMap}。行序这条有实测的现场：建组顺序是
     * zeta / Alpha / mid，修之前 wire 上第一行是 zeta（{@code battery65.pre} 第 9 行）。
     */
    @Test
    void xinfoGroupsRowsCarryTheGroupPositionAndComeOutInNameOrder() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "xi:s", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "xi:s", "1-2", "a", "2");
            assertEquals("1-2", readReply(in));
            send(socket, "XADD", "xi:s", "1-3", "a", "3");
            assertEquals("1-3", readReply(in));

            send(socket, "XGROUP", "CREATE", "xi:s", "zeta", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XGROUP", "CREATE", "xi:s", "Alpha", "1-1");
            assertEquals("+OK", readReply(in));
            send(socket, "XGROUP", "CREATE", "xi:s", "mid", "0-0");
            assertEquals("+OK", readReply(in));

            send(socket, "XINFO", "GROUPS", "xi:s");
            assertEquals("*3", readWireReply(in), ":2594 addReplyMultiBulkLen(c, raxSize(s->cgroups))");
            assertEquals("[name, Alpha, consumers, :0, pending, :0, last-delivered-id, 1-1]",
                    readReplyDeep(in), "行序按名字升序：Alpha 在 zeta 前，而建组是 zeta 先；每行 8 个元素");
            assertEquals("[name, mid, consumers, :0, pending, :0, last-delivered-id, 0-0]",
                    readReplyDeep(in));
            assertEquals("[name, zeta, consumers, :0, pending, :0, last-delivered-id, 0-0]",
                    readReplyDeep(in));

            send(socket, "XADD", "xi:bare", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XINFO", "GROUPS", "xi:bare");
            assertEquals("*0", readWireReply(in), ":2590 一个组都没有时交空数组，不是 null 数组");

            // 投递把组的位置推上去：组起点 0-0，所以 COUNT 2 投的是头两条，不是尾两条
            send(socket, "XREADGROUP", "GROUP", "mid", "cA", "COUNT", "2", "STREAMS", "xi:s", ">");
            assertEquals("[[xi:s, [[1-1, [a, 1]], [1-2, [a, 2]]]]]", readReplyDeep(in),
                    "前置条件: 投出去的是 1-1 与 1-2");
            send(socket, "XINFO", "GROUPS", "xi:s");
            assertEquals("*3", readWireReply(in));
            assertEquals("[name, Alpha, consumers, :0, pending, :0, last-delivered-id, 1-1]",
                    readReplyDeep(in));
            assertEquals("[name, mid, consumers, :1, pending, :2, last-delivered-id, 1-2]",
                    readReplyDeep(in), "last-delivered-id 跟着投递走");
            assertEquals("[name, zeta, consumers, :0, pending, :0, last-delivered-id, 0-0]",
                    readReplyDeep(in));

            send(socket, "XGROUP", "SETID", "xi:s", "zeta", "7-7");
            assertEquals("+OK", readReply(in));
            send(socket, "XGROUP", "CREATE", "xi:s", "top",
                    "18446744073709551615-18446744073709551615");
            assertEquals("+OK", readReply(in));
            send(socket, "XINFO", "GROUPS", "xi:s");
            assertEquals("*4", readWireReply(in));
            assertEquals("[name, Alpha, consumers, :0, pending, :0, last-delivered-id, 1-1]",
                    readReplyDeep(in));
            assertEquals("[name, mid, consumers, :1, pending, :2, last-delivered-id, 1-2]",
                    readReplyDeep(in));
            // 顶格位置回读必须是**无符号**那串文本：这两个 long 的位模式是 -1L，
            // 走 String.valueOf 会交回 "-1--1"。top 排在 mid 之后、zeta 之前，也是行序的一格。
            assertEquals("[name, top, consumers, :0, pending, :0, "
                    + "last-delivered-id, 18446744073709551615-18446744073709551615]",
                    readReplyDeep(in), "SETID/CREATE 写进去的位置要看得见，且不带负号");
            assertEquals("[name, zeta, consumers, :0, pending, :0, last-delivered-id, 7-7]",
                    readReplyDeep(in), "SETID 改过的那一组要交回 7-7，不是建组时的 0-0");

            // CONSUMERS 那一支吃的是同一条序规则。名字是量出来挑的：c2、c3 由投递顺手建，
            // c1 后建，而改前那个 jar 在这三个名字上的哈希表遍历序实测是 c3 c1 c2
            // （battery65 第 35 行 pre 侧），跟升序不同 —— 先拿 cA/cB 试过一对，那两个名字
            // 天然的表序恰好就是升序，改前改后一个字节都不差，这一格等于没量。
            send(socket, "XADD", "xi:c", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "xi:c", "1-2", "a", "2");
            assertEquals("1-2", readReply(in));
            send(socket, "XGROUP", "CREATE", "xi:c", "og", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "og", "c2", "COUNT", "1", "STREAMS", "xi:c", ">");
            assertTrue(readReplyDeep(in).contains("1-1"), "前置条件: c2 领走 1-1");
            send(socket, "XREADGROUP", "GROUP", "og", "c3", "COUNT", "1", "STREAMS", "xi:c", ">");
            assertTrue(readReplyDeep(in).contains("1-2"), "前置条件: c3 领走 1-2");
            send(socket, "XGROUP", "CREATECONSUMER", "xi:c", "og", "c1");
            assertEquals(":1", readReply(in));

            // 手上没货的 c1 照样列（:2568 遍历整份消费者表），计数是 :2582 的 integer 不是 bulk。
            String[] consumerRows = {"c1:0", "c2:1", "c3:1"};
            send(socket, "XINFO", "CONSUMERS", "xi:c", "og");
            assertEquals("*3", readWireReply(in));
            for (String row : consumerRows) {
                assertEquals("*6", readWireReply(in), ":2578 一行 6 个元素");
                assertEquals("$name", readWireReply(in));
                assertEquals("$" + row.substring(0, 2), readWireReply(in),
                        "行序按名字升序，不是哈希表的遍历序，也不是建消费者的先后");
                assertEquals("$pending", readWireReply(in));
                assertEquals(":" + row.substring(3), readWireReply(in));
                assertEquals("$idle", readWireReply(in));
                readWireReply(in); // idle 是时间量：把这一格读干净，不钉值
            }

            // 名字比的是**码点**，不是 UTF-16 码元：U+F000 在 U+2B000 之前，
            // 而 String.compareTo 会因为 U+2B000 的头一个码元是 \uD86C 把它排到前面。
            // 建组顺序也故意反着来，这样"插入序"与"码元序"都不是正确答案。
            send(socket, "XADD", "xi:u", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XGROUP", "CREATE", "xi:u", "\uD86C\uDC00", "0-0");
            assertEquals("+OK", readReply(in), "U+2B000 这个名字要能原样进出");
            send(socket, "XGROUP", "CREATE", "xi:u", "\uF000", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XINFO", "GROUPS", "xi:u");
            assertEquals("*2", readWireReply(in));
            assertEquals("[name, \uF000, consumers, :0, pending, :0, last-delivered-id, 0-0]",
                    readReplyDeep(in), "U+F000 要排在 U+2B000 之前（rax 比的是字节）");
            assertEquals("[name, \uD86C\uDC00, consumers, :0, pending, :0, last-delivered-id, 0-0]",
                    readReplyDeep(in));

            // 同一条序规则也管着 XPENDING 汇总里的那些消费者行（:2079-2089 遍历的还是那棵
            // 消费者 rax）。两个消费者都必须手上有货，否则 :2086 那句 continue 会跳过空手的那个，
            // 这一格就量不到序。
            send(socket, "XADD", "xi:p", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "xi:p", "1-2", "a", "2");
            assertEquals("1-2", readReply(in));
            send(socket, "XGROUP", "CREATE", "xi:p", "g", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g", "\uD86C\uDC00", "COUNT", "1", "STREAMS", "xi:p", ">");
            assertTrue(readReplyDeep(in).contains("1-1"), "先让 U+2B000 领走 1-1");
            send(socket, "XREADGROUP", "GROUP", "g", "\uF000", "COUNT", "1", "STREAMS", "xi:p", ">");
            assertTrue(readReplyDeep(in).contains("1-2"), "再让 U+F000 领走 1-2");
            send(socket, "XPENDING", "xi:p", "g");
            assertEquals("*4", readWireReply(in));
            assertEquals(":2", readWireReply(in));
            assertEquals("$1-1", readWireReply(in));
            assertEquals("$1-2", readWireReply(in));
            assertEquals("*2", readWireReply(in));
            assertEquals("*2", readWireReply(in));
            assertEquals("$\uF000", readWireReply(in), "汇总里的消费者行也按名字升序，U+F000 在前");
            assertEquals("$1", readWireReply(in), "计数是 addReplyBulkLongLong");
            assertEquals("*2", readWireReply(in));
            assertEquals("$\uD86C\uDC00", readWireReply(in));
            assertEquals("$1", readWireReply(in));
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

            // 键不在排在组不在之前（:2553 的 lookup 先于 :2560 的 streamLookupCG）：
            // 抱怨一个没建过的组，等于让客户端以为键是好的。
            send(socket, "XINFO", "CONSUMERS", "sem:no-such-stream", "pool");
            assertEquals("-ERR no such key", readReply(in), "不存在的键要说键");
            send(socket, "XINFO", "CONSUMERS", "sem:consumers", "nosuchgroup");
            assertEquals("-NOGROUP No such consumer group 'nosuchgroup' for key name 'sem:consumers'",
                    readReply(in), "码必须是 NOGROUP：多包一层 ERR，按码分支的客户端就把它读成未知错误");
            send(socket, "XINFO", "CONSUMERS", "sem:consumers");
            assertTrue(readReply(in).startsWith("-ERR wrong number"), "缺组名要报 arity");
            // 三个子命令共用这一问，谁都不许回"空表"糊过去（改前三条分别回 *-、*-、-ERR NOGROUP）
            send(socket, "XINFO", "STREAM", "sem:no-such-stream");
            assertEquals("-ERR no such key", readReply(in));
            send(socket, "XINFO", "GROUPS", "sem:no-such-stream");
            assertEquals("-ERR no such key", readReply(in));
            send(socket, "XINFO", "STREAM");
            assertEquals("-ERR syntax error, try 'XINFO HELP'", readReply(in));
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
     * stream 族同样守"一个键名只有一种类型"，而且守门的是<b>每一条</b>命令取键的那一问。
     * <p>
     * 上游不是一个总闸，而是把 {@code checkType} 摊在十二处（{@code t_stream.c} 5.0.14：
     * {@code XRANGE} :1377、{@code XLEN} :1402、{@code XREAD/XREADGROUP} :1500、
     * {@code XGROUP} :1830、{@code XACK} :1970、{@code XPENDING} :2043、{@code XDEL} :2417、
     * {@code XTRIM} :2462、{@code XINFO} :2554，{@code XADD} 在
     * {@code streamTypeLookupWriteOrCreate} :1128-1138 里）—— 少一处就开一扇门。
     * 修之前开的正是 {@code XADD} 那一扇：250 实测 {@code battery53} 第 4/5/6 行，
     * {@code SET t53:str hello} 之后 {@code XADD t53:str 1-1 f v} 回 {@code "1-1"} 成功，
     * {@code GET} 仍回 {@code "hello"}、{@code XRANGE} 回那条流条目，同一个键名下两种类型并存。
     * <p>
     * 这一族<b>没有参照实例</b>可实测（250 上的 redis 4.0.9 连 {@code XADD} 都不认，每一行都回
     * {@code -ERR unknown command 'XADD'}），所以判序一律按上面那份源码行号，
     * 下面每一支断言旁边都写清了是谁先说话。
     */
    @Test
    void streamFamilyHoldsTheSameOneTypeInvariant() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 五种类型各占一枚键名，另备一枚真流键 —— 闸门要是只会一律拒绝，下面的阳性对照就红了
            send(socket, "SET", "sem:ty:string", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "HSET", "sem:ty:hash", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "LPUSH", "sem:ty:list", "x");
            assertEquals(":1", readReply(in));
            send(socket, "SADD", "sem:ty:set", "x");
            assertEquals(":1", readReply(in));
            send(socket, "ZADD", "sem:ty:zset", "1", "x");
            assertEquals(":1", readReply(in));

            send(socket, "XADD", "sem:ty:stream", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XGROUP", "CREATE", "sem:ty:stream", "g", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g", "c1", "STREAMS", "sem:ty:stream", ">");
            assertEquals("[[sem:ty:stream, [[1-1, [a, 1]]]]]", readReplyDeep(in));

            // ---- 阳性对照：这些命令在流键上都要真办成事 ----
            send(socket, "XLEN", "sem:ty:stream");
            assertEquals(":1", readReply(in));
            send(socket, "XRANGE", "sem:ty:stream", "-", "+");
            assertEquals("[[1-1, [a, 1]]]", readReplyDeep(in));
            send(socket, "XREVRANGE", "sem:ty:stream", "+", "-");
            assertEquals("[[1-1, [a, 1]]]", readReplyDeep(in));
            send(socket, "XADD", "sem:ty:stream", "2-2", "b", "2");
            assertEquals("2-2", readReply(in));
            send(socket, "XDEL", "sem:ty:stream", "2-2");
            assertEquals(":1", readReply(in));
            send(socket, "XTRIM", "sem:ty:stream", "MAXLEN", "10");
            assertEquals(":0", readReply(in));
            send(socket, "XACK", "sem:ty:stream", "g", "1-1");
            assertEquals(":1", readReply(in));
            send(socket, "XPENDING", "sem:ty:stream", "g");
            assertFalse(readReplyDeep(in).startsWith("-"), "汇总形态在流键上合法");
            send(socket, "XINFO", "STREAM", "sem:ty:stream");
            assertTrue(readReplyDeep(in).contains("length"), "XINFO STREAM 在流键上合法");
            send(socket, "XINFO", "GROUPS", "sem:ty:stream");
            assertTrue(readReplyDeep(in).contains("g"), "XINFO GROUPS 在流键上合法");
            send(socket, "XINFO", "CONSUMERS", "sem:ty:stream", "g");
            assertTrue(readReplyDeep(in).contains("c1"), "XINFO CONSUMERS 在流键上合法");
            send(socket, "XGROUP", "CREATECONSUMER", "sem:ty:stream", "g", "c9");
            assertEquals(":1", readReply(in));
            send(socket, "XGROUP", "DELCONSUMER", "sem:ty:stream", "g", "c9");
            // 这一格量的是"这个消费者手上还压着几条"（t_stream.c:1916-1917 的
            // streamDelConsumer 返回值），而 c9 是 CREATECONSUMER 凭空建出来的、手上没东西，
            // 所以是 :0。1.3.5 那轮把它钉成 :1，钉的是我们自己的布尔答复（"删掉了"翻成 1），
            // 那一版把"压着两条"和"一条没有"混成同一格。删没删掉改由下面那一问来证。
            assertEquals(":0", readReply(in));
            send(socket, "XINFO", "CONSUMERS", "sem:ty:stream", "g");
            String consumersAfterDelete = readReplyDeep(in);
            assertTrue(consumersAfterDelete.contains("c1"), "c1 还在");
            assertFalse(consumersAfterDelete.contains("c9"), "c9 真被删掉了：" + consumersAfterDelete);
            send(socket, "XGROUP", "DESTROY", "sem:ty:stream", "g");
            assertEquals(":1", readReply(in));
            send(socket, "XREAD", "STREAMS", "sem:ty:stream", "0-0");
            assertEquals("[[sem:ty:stream, [[1-1, [a, 1]]]]]", readReplyDeep(in));

            // ---- 负判据：12 处取键一处都不许漏，五种类型逐个键名扫一遍 ----
            for (String family : new String[]{"string", "hash", "list", "set", "zset"}) {
                String held = "sem:ty:" + family;
                expectWrongType(socket, in, "XLEN", held);
                expectWrongType(socket, in, "XRANGE", held, "-", "+");
                expectWrongType(socket, in, "XREVRANGE", held, "+", "-");
                expectWrongType(socket, in, "XADD", held, "9-9", "f", "v");
                expectWrongType(socket, in, "XDEL", held, "1-1");
                expectWrongType(socket, in, "XTRIM", held, "MAXLEN", "1");
                expectWrongType(socket, in, "XACK", held, "g", "1-1");
                expectWrongType(socket, in, "XPENDING", held, "g");
                expectWrongType(socket, in, "XINFO", "STREAM", held);
                expectWrongType(socket, in, "XINFO", "GROUPS", held);
                expectWrongType(socket, in, "XINFO", "CONSUMERS", held, "g");
                expectWrongType(socket, in, "XGROUP", "CREATE", held, "g", "0-0");
                expectWrongType(socket, in, "XGROUP", "DESTROY", held, "g");
                expectWrongType(socket, in, "XGROUP", "CREATECONSUMER", held, "g", "c");
                expectWrongType(socket, in, "XGROUP", "DELCONSUMER", held, "g", "c");
                expectWrongType(socket, in, "XREAD", "STREAMS", held, "0-0");
                expectWrongType(socket, in, "XREADGROUP", "GROUP", "g", "c", "STREAMS", held, "0-0");
                expectWrongType(socket, in, "XREADGROUP", "GROUP", "g", "c", "STREAMS", held, ">");
            }

            // 拦下来不等于顺手写进去：五种类型原来的值一个字节都没被 stream 命令动过
            send(socket, "GET", "sem:ty:string");
            assertEquals("hello", readReply(in), "XADD 被挡下时不能把流建在 string 键名下");
            send(socket, "HGET", "sem:ty:hash", "f");
            assertEquals("v", readReply(in));
            send(socket, "LLEN", "sem:ty:list");
            assertEquals(":1", readReply(in));
            send(socket, "SISMEMBER", "sem:ty:set", "x");
            assertEquals(":1", readReply(in));
            send(socket, "ZCARD", "sem:ty:zset");
            assertEquals(":1", readReply(in));

            // ---- 判序：同一枚坏键名，谁先说话由源码行号定，不是由"哪个检查写在前头"定 ----
            // XRANGE 的取键排在两端 ID 之后（:1356 解析 → :1377 才 lookup）
            send(socket, "XRANGE", "sem:ty:string", "not-an-id", "+");
            assertEquals("-ERR Invalid stream ID specified as stream command argument", readReply(in),
                    "坏 ID 要先说话，闸门不能抢答成类型问题");
            // XDEL 正好相反：:2416 取键 → :2425 才逐条体检 ID
            expectWrongType(socket, in, "XDEL", "sem:ty:string", "not-an-id");
            // XADD 的 0-0 闸在取键之前（:1293 对 :1300）
            send(socket, "XADD", "sem:ty:string", "0-0", "f", "v");
            assertEquals("-ERR The ID specified in XADD must be greater than 0-0", readReply(in));
            // XTRIM 的取键排在选项解析之前（:2461），所以坏策略位轮不到说话
            expectWrongType(socket, in, "XTRIM", "sem:ty:string", "MINID", "3");
            // XPENDING 是先问类型再问组（:2043 对 :2047）：不能把类型错误伪装成"组不存在"
            expectWrongType(socket, in, "XPENDING", "sem:ty:string", "nosuchgroup");
            // XREADGROUP 也是先问类型（:1500），那句 "$ 在 XREADGROUP 里没意义" 排在它后面
            expectWrongType(socket, in, "XREADGROUP", "GROUP", "g", "c", "STREAMS", "sem:ty:string", "$");
            // 组不在的 XACK 干脆回 :0，坏 ID 那句排在取组之后（:1976 对 :1982）
            send(socket, "XACK", "sem:ty:stream", "nosuchgroup", "not-an-id");
            assertEquals(":0", readReply(in));

            // 多键命令里一枚坏键作废整条（:1500 的 goto cleanup）：好键那份结果不许先交出去
            expectWrongType(socket, in, "XREAD", "STREAMS", "sem:ty:string", "sem:ty:stream", "0-0", "0-0");
            send(socket, "XREAD", "STREAMS", "sem:ty:stream", "sem:ty:stream", "0-0", "0-0");
            assertEquals("[[sem:ty:stream, [[1-1, [a, 1]]]], [sem:ty:stream, [[1-1, [a, 1]]]]]",
                    readReplyDeep(in), "阳性对照：两枚好键确实各回一份，上面那一问不是空跑");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * -NOGROUP / -BUSYGROUP 的<b>码本身</b>就是契约：上游那两句都不是普通 ERR ——
     * {@code t_stream.c:1509} 是 {@code addReplyErrorFormat(c, "-NOGROUP …")}（串自带前导
     * "-"，所以不再叠 ERR），{@code :1888-1889} 干脆是
     * {@code addReplySds(c, sdsnew("-BUSYGROUP Consumer Group name already exists\r\n"))}。
     * 我们原先写成 {@code -ERR NOGROUP …} / {@code -ERR BUSYGROUP …}（实测 battery55:10 :11 :17），
     * 按码分支的客户端一律落到"未知错误"：消费组的两个最常见判断（组不存在要先建、重名要跳过）
     * 就此失效，而 {@code XPENDING} 那一支早就在发正确的 {@code -NOGROUP}（battery54:22），
     * 同一个码在同一族里两种写法。
     */
    @Test
    void streamErrorCodesTravelAsTheirOwnCode() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "sem:code", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XGROUP", "CREATE", "sem:code", "g", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XGROUP", "CREATE", "sem:code", "g", "0-0");
            assertEquals("-BUSYGROUP Consumer Group name already exists", readReply(in),
                    "重名的码是 BUSYGROUP，不是 ERR");

            // 键在组不在、键在组在位置是 ">"、键根本不在 —— :1505-1514 三种情形同一句原文
            String noGroupSem = "-NOGROUP No such key '%s' or consumer group '%s' "
                    + "in XREADGROUP with GROUP option";
            send(socket, "XREADGROUP", "GROUP", "nosuch", "c", "STREAMS", "sem:code", "0-0");
            assertEquals(String.format(noGroupSem, "sem:code", "nosuch"), readReply(in));
            send(socket, "XREADGROUP", "GROUP", "nosuch", "c", "STREAMS", "sem:code", ">");
            assertEquals(String.format(noGroupSem, "sem:code", "nosuch"), readReply(in),
                    "\">\" 也要先问组在不在，回 *-1 等于让客户端以为在轮询等消息");
            send(socket, "XREADGROUP", "GROUP", "g", "c", "STREAMS", "sem:nokey", ">");
            assertEquals(String.format(noGroupSem, "sem:nokey", "g"), readReply(in));

            // 整条命令作废（:1513 的 goto cleanup）：第二枚键才犯错的，第一枚那份合法结果也不许先交。
            // 判据是"这一问拿回来的第一个响应就是错误" —— 先交了第一键的数组就会读成数组而红。
            send(socket, "XADD", "sem:code2", "1-1", "a", "1");
            readReply(in);
            send(socket, "XREADGROUP", "GROUP", "g", "c", "STREAMS", "sem:code", "sem:code2", "0-0", "0-0");
            assertEquals(String.format(noGroupSem, "sem:code2", "g"), readReply(in),
                    "sem:code2 上没有组 g，第二问要作废整条");
            // 作废要作废在**动手之前**：第一枚键上的 c 手上不该多出任何账
            send(socket, "XREADGROUP", "GROUP", "g", "c", "STREAMS", "sem:code", "0-0");
            assertEquals("[[sem:code, []]]", readReplyDeep(in),
                    "上一问如果先投递再报错，这里就会看到 1-1");
            send(socket, "XREAD", "STREAMS", "sem:code", "0-0");
            assertEquals("[[sem:code, [[1-1, [a, 1]]]]]", readReplyDeep(in),
                    "作废之后这条连接还能正常应答（没留下第二个响应错位）");

            // 这一问只属于 XREADGROUP：XREAD 读不存在的键仍是"整个 *-1"（:1505 的 if (groupname)）
            send(socket, "XREAD", "STREAMS", "sem:nokey", "0-0");
            assertEquals("*-1", readReply(in));

            // 判序：:1505 的组问排在 :1518 的 `$` 之前，所以组不在时报的是组，不是"位置写法不对"
            send(socket, "XREADGROUP", "GROUP", "nosuch", "c", "STREAMS", "sem:code", "$");
            assertEquals(String.format(noGroupSem, "sem:code", "nosuch"), readReply(in),
                    "组不存在要先说话，不能让 `$` 那句抢答");
            // 而组在的时候 `$` 仍回它自己那句（另一条用例钉句子原文，这里只要码不被牵连）
            send(socket, "XREADGROUP", "GROUP", "g", "c", "STREAMS", "sem:code", "$");
            assertTrue(readReply(in).startsWith("-ERR The $ ID is meaningless"),
                    "组在的时候才轮到 `$` 那一句");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /** 把一条 stream 命令打出去，断言它被取键那一问的类型闸门挡下（同一句 WRONGTYPE 原文）。 */
    private static void expectWrongType(Socket socket, DataInputStream in, String... cmd)
            throws IOException {
        send(socket, cmd);
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                readReply(in), "键被别的类型占着时必须挡下: " + String.join(" ", cmd));
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

            // 另一个载体：XTRIM 的选项位。1.3.6 重钉 —— 上游把取键排在选项解析之前
            // （:2461-2462 的 lookupKeyWriteOrReply(…, shared.czero)），所以这里**必须先有这枚键**，
            // 否则 `err:trim` 不在就直接答 `:0`（那是"删了 0 条"，不是报错），这一段的载体就没意义了。
            send(socket, "XADD", "err:trim", "1-1", "a", "1");
            assertEquals("1-1", readReply(in), "载体要落在能报错的那一格上");
            send(socket, "XTRIM", "err:trim", "MAXLENX\r\n+FORGED2\r\n", "3");
            String second = readReply(in);
            assertTrue(second.startsWith("-"), "不认识的裁剪策略要报错，实得 " + second);
            send(socket, "PING");
            assertEquals("+PONG", readReply(in), "XTRIM 那条报错若劈开了帧，这里对不上");
            // 1.3.6 起这一格回的是上游那句固定文本 `ERR syntax error`（:2498），**不再回显客户端
            // 输入**，所以"文本里还带着 +FORGED2"这一问在这里没有载体了 —— 回显那一问仍由上面
            // 那条 unknown command（消息里有命令名）钉着（:1670、:1675）。这里只留"帧不劈开"。
            assertEquals("-ERR syntax error", second);

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
            // 集合类型也能整键搬走（实测 hash/list/zset 三种都回 1），而且<b>时刻行跟着键走</b>：
            // 上游 moveCommand（db.c:919）没有类型分支 —— :957 取 expire、:965 挂到目标库、
            // :969 才从源库 dbDelete（那一手顺带收走源库的时刻行）。
            send(socket, "HSET", "mv:h", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "mv:h", "200");
            assertEquals(":1", readReply(in), "前置: hash 键上得了时刻行");
            send(socket, "MOVE", "mv:h", "5");
            assertEquals(":1", readReply(in));
            send(socket, "EXISTS", "mv:h");
            assertEquals(":0", readReply(in), "搬走的键在源库里不再留壳");
            send(socket, "TTL", "mv:h");
            assertEquals(":-2", readReply(in), "源库里键都走了，不许留一行没人认领的过期时刻");
            send(socket, "SELECT", "5");
            assertEquals("+OK", readReply(in));
            send(socket, "HGET", "mv:h", "f");
            assertEquals("v", readReply(in));
            send(socket, "TTL", "mv:h");
            String movedHashTtl = readReply(in);
            assertTrue(movedHashTtl.startsWith(":") && Long.parseLong(movedHashTtl.substring(1)) > 150,
                    "MOVE 之后集合键的 TTL 也要跟着过去: " + movedHashTtl);
            send(socket, "DBSIZE");
            assertEquals(":1", readReply(in), "库 5 里只有搬来的这一枚");

            // 第六种类型单独走一趟，顺带钉住"源库那一行不许留在原地没人认领"：stream 刻意不在
            // {@code wireExpiryRecycling} 的名单里（上游 xdelCommand 不删空流），所以搬走一枚流键时，
            // 源库的时刻行只能由 MOVE 自己收尾 —— 上一段那次 SELECT 0 之后同名重写，读回的必须是 -1。
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "mv:s", "1-1", "f", "v");
            assertEquals("1-1", readReply(in));
            send(socket, "EXPIRE", "mv:s", "200");
            assertEquals(":1", readReply(in), "前置: 流键上得了时刻行");
            send(socket, "MOVE", "mv:s", "4");
            assertEquals(":1", readReply(in), "流键整键搬走，表顶跟着走");
            send(socket, "SELECT", "4");
            assertEquals("+OK", readReply(in));
            send(socket, "XRANGE", "mv:s", "-", "+");
            assertEquals("[[1-1, [f, v]]]", readReplyDeep(in), "搬过去的还是原来那一条条目");
            send(socket, "TTL", "mv:s");
            String movedStreamTtl = readReply(in);
            assertTrue(movedStreamTtl.startsWith(":") && Long.parseLong(movedStreamTtl.substring(1)) > 150,
                    "MOVE 之后流键的 TTL 也要跟着过去: " + movedStreamTtl);
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "mv:s", "1-1", "f", "again");
            assertEquals("1-1", readReply(in));
            send(socket, "TTL", "mv:s");
            assertEquals(":-1", readReply(in), "搬走的流在源库里留下的时刻行不许被同名新键继承");
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

    /** 探测用的端口窗口：只在操作系统**出站**区间之下取，见 {@link #freePort()}。 */
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final java.util.concurrent.atomic.AtomicInteger PORT_CURSOR =
            new java.util.concurrent.atomic.AtomicInteger(new java.util.Random().nextInt(PORT_SPAN));

    /**
     * 挑一枚测试用的端口：在一个固定的低位窗口里顺着一个游标找一枚真能 bind 上的号码。
     * <p>
     * 不能再写 {@code new ServerSocket(0)} —— 那样"探到再放开"的号码出自操作系统的**出站**
     * 派发区间（macOS 49152-65535、Linux 默认 32768-60999），探针一关那个号码立刻重新可派，
     * 而本机常驻代理一直在起出站连接：实测 9 遍全量里撞红 2 遍（64088 与 51981，红的用例
     * 还各不相同），{@code lsof -nP -iTCP:64088} 当场抓到它挂在一条 {@code FIN_WAIT_2} 的
     * 出站连接上 —— 也就是"这个号码在探完之后被派给了别人"，不是本仓哪个测试没关服务器。
     * 窗口之下的号码操作系统不派给出站连接，"探得到"和"bind 得上"之间就没有那道窗口了；
     * 窗口里撞上别人的常驻服务照常换下一枚。下面 {@code startAndWait} 里那句"先 bind 一个
     * 临时端口再放开"的注释，防的是同一段窗口的另一头，两半都要在。
     */
    private static int freePort() throws IOException {
        for (int tries = 0; tries < PORT_SPAN; tries++) {
            int port = PORT_BASE + PORT_CURSOR.getAndIncrement() % PORT_SPAN;
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            } catch (IOException taken) {
                continue;
            }
            return port;
        }
        throw new IOException("窗口 " + PORT_BASE + "-" + (PORT_BASE + PORT_SPAN - 1)
                + " 里找不出一枚可 bind 的端口");
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
            // 就绪的判据不能只是"连得上"。freePort() 是先 bind 一个临时端口再放开，放开到
            // 真正 bind 之间有窗口，本机同时有别的战役在跑 surefire 时这个窗口会被别人插进来；
            // 裸 connect 在那种情况下照样立刻"就绪"，于是后面每条断言都在读别人家的响应
            // （实测过一次 `expected: <+OK> but was: <HTTP/1.1 400 Bad Request>`，而这串字符
            // 不在本仓任何源码里）。要对方答一句话，且答的必须是 RESP 形状。
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                probe.setSoTimeout(300);
                probe.getOutputStream().write(
                        "*1\r\n$4\r\nPING\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                probe.getOutputStream().flush();
                int first = probe.getInputStream().read();
                if (first == '+' || first == '-' || first == ':' || first == '$' || first == '*') {
                    return thread;
                }
                throw new IllegalStateException("port " + port + " 上答话的不是 RESP，首字节 "
                        + (first < 0 ? "是流已关闭" : "'" + (char) first + "'(" + first + ")")
                        + " —— 端口大概率在 freePort() 放开后被别的进程占走了");
            } catch (java.net.SocketTimeoutException stillWarmingUp) {
                // 内核已 accept 而事件循环还没读：这是我们自己起步慢，不能算别人的端口
                Thread.sleep(50);
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

    /**
     * XRANGE / XREVRANGE 的 COUNT：{@code COUNT 0} 是 nil 数组而不是"不限"，
     * 而"键不在"是空数组 —— 上游把这两问排在一条命令的两个位置（:1376 与 :1380）。
     * 改前实测（battery57）：{@code COUNT 0} 交整表、{@code COUNT -1} 交整表、
     * 裸 {@code COUNT} 与多余字交整表、{@code COUNT 1 COUNT 2} 第一个生效。
     */
    @Test
    void xrangeCountHasThreeShapesAndItsOwnPlaceInTheQueue() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "sem:cnt", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "sem:cnt", "2-2", "b", "2");
            assertEquals("2-2", readReply(in));
            send(socket, "XADD", "sem:cnt", "3-3", "c", "3");
            assertEquals("3-3", readReply(in));

            // ---- 阳性对照：正数照旧是"截断"，不能把 nil 当成"什么都空" ----
            send(socket, "XRANGE", "sem:cnt", "-", "+");
            assertEquals("[[1-1, [a, 1]], [2-2, [b, 2]], [3-3, [c, 3]]]", readReplyDeep(in));
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "2");
            assertEquals("[[1-1, [a, 1]], [2-2, [b, 2]]]", readReplyDeep(in), "COUNT 2 截两条");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "9");
            assertEquals("[[1-1, [a, 1]], [2-2, [b, 2]], [3-3, [c, 3]]]",
                    readReplyDeep(in), "COUNT 大于条数就是全表，不是错误");

            // ---- 这一支的主角：0 是 nil 数组 ----
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "0");
            assertEquals("*-1", readReplyDeep(in), "COUNT 0 回 *-1，不是空表也不是整表");
            send(socket, "XREVRANGE", "sem:cnt", "+", "-", "COUNT", "0");
            assertEquals("*-1", readReplyDeep(in), "降序共用同一段解析");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "-1");
            assertEquals("*-1", readReplyDeep(in), "负数在上游先钳成 0（:1366），于是与 0 同答");

            // ---- 两问的判序：键不在（:1376）排在 COUNT 0（:1380）之前 ----
            send(socket, "XRANGE", "sem:cnt:ghost", "-", "+", "COUNT", "0");
            assertEquals("[]", readReplyDeep(in),
                    "键不在答空表（:1376），nil 那一只只留给存在的键（:1380）");
            send(socket, "SET", "sem:cnt:str", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "XRANGE", "sem:cnt:str", "-", "+", "COUNT", "0");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                    readReply(in), "类型那一问也排在 count 之前");

            // ---- 多余参数只有两种下场：COUNT 配对，其余 syntax error ----
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT");
            assertEquals("-ERR syntax error", readReply(in), "裸 COUNT 后面没值，:1363 不成立");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "EXTRA");
            assertEquals("-ERR syntax error", readReply(in), ":1369 的 else 支");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "count", "2");
            assertEquals("[[1-1, [a, 1]], [2-2, [b, 2]]]", readReplyDeep(in), "大小写不敏感");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "1", "COUNT", "2");
            assertEquals("[[1-1, [a, 1]], [2-2, [b, 2]]]",
                    readReplyDeep(in), "重复 COUNT 是后写的赢（:1364 就地覆盖），改前是第一个生效");

            // ---- 值的文法：long 而不是 int，坏值一句话 ----
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "abc");
            assertEquals("-ERR value is not an integer or out of range", readReply(in));
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "+2");
            assertEquals("-ERR value is not an integer or out of range",
                    readReply(in), "Redis 的整数尺不吃 +2（RedisIntegerFormat）");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "9999999999");
            assertEquals("[[1-1, [a, 1]], [2-2, [b, 2]], [3-3, [c, 3]]]",
                    readReplyDeep(in), "上游按 long long 取，超出 int 的 COUNT 不该被拒");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "4294967296");
            assertEquals("[[1-1, [a, 1]], [2-2, [b, 2]], [3-3, [c, 3]]]",
                    readReplyDeep(in),
                    "2^32 直接窄化成 int 会得 0，而 0 在下面那层是\"不限\"");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "4294967297");
            assertEquals("[[1-1, [a, 1]], [2-2, [b, 2]], [3-3, [c, 3]]]",
                    readReplyDeep(in),
                    "2^32+1 窄化成 1 —— 钳位不在窄化之前就会把三条截成一条");

            // ---- 判序：坏 ID 永远先说话（:1356-1357 在 :1360 之前） ----
            send(socket, "XRANGE", "sem:cnt", "bad-id", "+", "COUNT", "0");
            assertEquals("-ERR Invalid stream ID specified as stream command argument",
                    readReply(in), "COUNT 0 不该抢答坏 ID");
            send(socket, "XRANGE", "sem:cnt", "-", "+", "COUNT", "0", "bad-id");
            assertEquals("-ERR syntax error", readReply(in), "多余的非 COUNT 参数也一样排在 count 之后");

            // ---- 闸门不改写字节：一整轮下来条目还在 ----
            send(socket, "XLEN", "sem:cnt");
            assertEquals(":3", readReply(in));
            send(socket, "GET", "sem:cnt:str");
            assertEquals("hello", readReply(in));
        } finally {
            server.stop();
            thread.join(2000);
        }
    }

    /**
     * XADD / XTRIM 的那一圈"要么是选项、要么就是 ID"的扫描（上游 t_stream.c :1248-1287 与
     * :2461-2510）。两件事在这一支里各自钉住：<b>哪一形的 arity 句</b>（命令表的
     * {@code 'xadd' command}，对 手写的裸句 {@code for XADD}，分界是 arity -5 / -2），
     * 以及<b>谁先说话</b>（XADD：ID → arity → 0-0；XTRIM：取键 → 选项）。
     * 改前实测（{@code battery58.pre}，46 行）翻 <b>16</b> 行：9-13、16、19、32-35、38-42；
     * 其余 30 行是这一支的对照组（两侧各 {@code wrote=46 lost=none}，见 {@code battery58_replay.log}）。
     */
    @Test
    void xaddAndXtrimOptionCircleAnswersInUpstreamOrder() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "sem:xc", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "sem:xc", "2-2", "b", "2");
            assertEquals("2-2", readReply(in));

            // ---- 阳性对照：合法的三种形状照旧收 ----
            send(socket, "XADD", "sem:xc", "3-3", "c", "3", "d", "4");
            assertEquals("3-3", readReply(in), "两组 field/value 是合法的");
            send(socket, "XADD", "sem:capa", "1-1", "a", "1");
            readReply(in);
            send(socket, "XADD", "sem:capa", "2-2", "b", "2");
            readReply(in);
            send(socket, "XADD", "sem:capa", "3-3", "c", "3");
            readReply(in);
            send(socket, "XADD", "sem:capa", "MAXLEN", "=", "2", "6-6", "d", "4");
            assertEquals("6-6", readReply(in), "MAXLEN = n 是上游认的精确裁剪写法（:1262-1264）");
            send(socket, "XLEN", "sem:capa");
            assertEquals(":2", readReply(in), "裁到 2 条，剩下最新的两条");

            // ---- arity 的两种形状，分界是命令表的 -5（server.c :314） ----
            send(socket, "XADD", "sem:xc");
            assertEquals("-ERR wrong number of arguments for 'xadd' command", readReply(in),
                    "四个字都到不了手写的 arity 检查（:1283），先吃命令表那句");
            send(socket, "XADD", "sem:xc", "5-5", "a");
            assertEquals("-ERR wrong number of arguments for 'xadd' command", readReply(in));
            send(socket, "XADD", "sem:xc", "5-5", "a", "1", "b");
            assertEquals("-ERR wrong number of arguments for XADD", readReply(in),
                    "六个字进得来，:1284-1286 那句是裸句、大写、没有 '…' command");
            send(socket, "XADD", "sem:xc", "MAXLEN", "2", "5-5", "a");
            assertEquals("-ERR wrong number of arguments for XADD", readReply(in),
                    "MAXLEN 吃掉一个位置之后字段数不足，同样是裸句");

            // ---- MAXLEN 的值：负数是那一句原文 ----
            send(socket, "XADD", "sem:xc", "MAXLEN", "-1", "5-5", "a", "1");
            assertEquals("-ERR The MAXLEN argument must be >= 0.", readReply(in), ":1268-1271");
            send(socket, "XADD", "sem:xc", "MAXLEN", "abc", "5-5", "a", "1");
            assertEquals("-ERR value is not an integer or out of range", readReply(in));
            // 被拒的 XADD 不建键（上游 :1292 那句注释就是为了这个）。
            send(socket, "XADD", "sem:xcnew", "MAXLEN", "-1", "5-5", "a", "1");
            assertEquals("-ERR The MAXLEN argument must be >= 0.", readReply(in));
            send(socket, "XLEN", "sem:xcnew");
            assertEquals(":0", readReply(in));

            // ---- XADD 的判序：ID 先于 arity，arity 先于 0-0 ----
            send(socket, "XADD", "sem:xc", "bad-id", "a", "1", "b");
            assertEquals("-ERR Invalid stream ID specified as stream command argument",
                    readReply(in), "六个字里字段数也是不齐的（3 个），而 ID 在那一圈里就先解析了（:1276）");
            send(socket, "XADD", "sem:xc", "0-0", "a", "1", "b", "2", "3");
            assertEquals("-ERR wrong number of arguments for XADD", readReply(in),
                    "字段数不齐时轮不到 :1292 的 0-0 那一问");
            send(socket, "XADD", "sem:xc", "0-0", "a", "1");
            assertEquals("-ERR The ID specified in XADD must be greater than 0-0", readReply(in),
                    " arity 过了之后才是 0-0");

            // ---- XTRIM：取键排在选项之前（:2461-2462） ----
            send(socket, "XTRIM", "sem:ghost", "MAXLEN", "abc");
            assertEquals(":0", readReply(in), "键不在就不在，值对不对根本问不着");
            send(socket, "XTRIM", "sem:ghost", "FOO");
            assertEquals(":0", readReply(in));
            send(socket, "XTRIM", "sem:ghost", "MAXLEN", "-1");
            assertEquals(":0", readReply(in));
            send(socket, "SET", "sem:xcstr", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "XTRIM", "sem:xcstr", "FOO");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                    readReply(in), "类型那一问也在选项之前");

            // ---- XTRIM 的三种"认不得"，各回各的 ----
            send(socket, "XTRIM");
            assertEquals("-ERR wrong number of arguments for 'xtrim' command", readReply(in),
                    "命令表 arity 是 -2（server.c :327），只有光杆吃这句");
            send(socket, "XTRIM", "sem:xc");
            assertEquals("-ERR XTRIM called without an option to trim the stream", readReply(in),
                    ":2507-2510 —— 键在而没给策略，不是 arity 错");
            send(socket, "XTRIM", "sem:xc", "MAXLEN");
            assertEquals("-ERR syntax error", readReply(in), ":1255/:2477 的 moreargs 不成立");
            send(socket, "XTRIM", "sem:xc", "FOO");
            assertEquals("-ERR syntax error", readReply(in));
            send(socket, "XTRIM", "sem:xc", "MAXLEN", "~");
            assertEquals("-ERR value is not an integer or out of range", readReply(in),
                    "波浪号要吃掉，值那一格才是 ~ 后面的字");
            send(socket, "XTRIM", "sem:xc", "MAXLEN", "-1");
            assertEquals("-ERR The MAXLEN argument must be >= 0.", readReply(in),
                    ":2491-2494，与 XADD 同一句");

            // ---- 真的裁一刀，字节还在 ----
            send(socket, "XTRIM", "sem:xc", "MAXLEN", "1");
            assertEquals(":2", readReply(in));
            send(socket, "XLEN", "sem:xc");
            assertEquals(":1", readReply(in));
            // 这一条顺手钉住多 field 条目的形状：上游 :1000 是 `addReplyMultiBulkLen(c,
            // numfields*2)`，即**平铺的 2n 个数**，不是 n 个二元对。我先按"每对再套一层"写了期望，
            // 当场红 —— 红的是这条断言而不是代码，上游读下来才确认我们的字节本来就是对的那一方。
            send(socket, "XRANGE", "sem:xc", "-", "+");
            assertEquals("[[3-3, [c, 3, d, 4]]]", readReplyDeep(in));
            send(socket, "GET", "sem:xcstr");
            assertEquals("hello", readReply(in), "被闸门拦下的 XRANGE/XTRIM 不改写别的键");
        } finally {
            server.stop();
            thread.join(2000);
        }
    }

    /**
     * {@code XADD … MAXLEN 0 …} 里的那个 0 是一个真实的裁剪值，不是"没给"。上游把两件事
     * 分成两个值：{@code maxlen} 的初值是 <b>-1</b>（{@code t_stream.c:1240}，注释原文
     * "If left to -1 no trimming is performed"），负数在 :1268 就被那句
     * {@code The MAXLEN argument must be >= 0.} 挡掉，所以到得了裁剪那一跳的只有
     * {@code >= 0}（:1327）—— 而那一跳排在 :1321 的 {@code addReplyStreamID} <b>之后</b>，
     * 于是"清空"也要先把这一条的 ID 交回去。
     * 改前实测（{@code battery66.pre}，30 行）的原始账是 <b>真实不一致 9 行</b>
     * （{@code zdiff.py}：{@code 行数=30 顺序不同=0 真实不一致=9}）；其中 {@code :19} 只是
     * 自动 ID 里的那一毫秒（两侧都是 {@code $"<ms>-0"}，逐字节比必然不同），算噪声，
     * 剩下 8 行 {@code :6 :7 :20 :21 :23 :25 :27 :30} 全是"该空的地方没空"。
     */
    @Test
    void xaddMaxlenZeroClearsTheStreamAfterReplying() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- 阳性对照：非零的 MAXLEN 一直是活的 ----
            send(socket, "XADD", "mx:a", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "mx:a", "MAXLEN", "3", "1-2", "a", "2");
            assertEquals("1-2", readReply(in));
            send(socket, "XLEN", "mx:a");
            assertEquals(":2", readReply(in));

            // ---- 0：先交 ID，再把整条流清空 ----
            send(socket, "XADD", "mx:a", "MAXLEN", "0", "1-3", "a", "3");
            assertEquals("1-3", readReply(in), ":1321 的回 ID 排在 :1327 的裁剪之前");
            send(socket, "XLEN", "mx:a");
            assertEquals(":0", readReply(in),
                    "改前这里是整条流的长度 —— 0 被当成了不裁剪的哨兵（battery66.pre 第 6 行实测 :4）");
            send(socket, "XRANGE", "mx:a", "-", "+");
            assertEquals("*0", readWireReply(in),
                    "空的是零个元素的数组（:987 的 addDeferredMultiBulkLength 带着 0 收口），不是 *-1");

            // ---- 自动 ID 那一支同样不例外：MAXLEN 0 不吃掉 * 的那一格 ----
            send(socket, "XADD", "mx:b", "1-1", "a", "1");
            readReply(in);
            send(socket, "XADD", "mx:b", "MAXLEN", "0", "*", "a", "2");
            String autoId = readReply(in);
            assertTrue(autoId.matches("\\d+-\\d+"), "自动 ID 照常生成，实测 <" + autoId + ">");
            send(socket, "XLEN", "mx:b");
            assertEquals(":0", readReply(in));

            // ---- 清空不等于删键：键还在、表顶还在 ----
            // 上游的 streamTrimByLength（:424）只从 rax 里摘条目，既不动 s->last_id，
            // 整个 t_stream.c 里也没有一处 dbDelete —— 所以同一个 ID 再写仍要吃单调性闸。
            send(socket, "XADD", "mx:c", "MAXLEN", "0", "5-5", "a", "1");
            assertEquals("5-5", readReply(in));
            send(socket, "XLEN", "mx:c");
            assertEquals(":0", readReply(in));
            send(socket, "XADD", "mx:c", "5-5", "a", "2");
            assertEquals("-ERR The ID specified in XADD is equal or smaller than "
                    + "the target stream top item", readReply(in),
                    "假如裁剪顺手删了键，这一条就会被收下、同一个 ID 能重发一遍");
            send(socket, "XADD", "mx:c", "6-6", "a", "3");
            assertEquals("6-6", readReply(in));
            send(socket, "XLEN", "mx:c");
            assertEquals(":1", readReply(in), "没给 MAXLEN 的那一刀不裁（初值 -1）");

            // ---- XTRIM 的 0 一直是通的（同族的另一格，改前就正确）----
            send(socket, "XTRIM", "mx:ghost", "MAXLEN", "0");
            assertEquals(":0", readReply(in), "键不在也是 :0，而不是错");
            send(socket, "XTRIM", "mx:c", "MAXLEN", "0");
            assertEquals(":1", readReply(in));
            send(socket, "XLEN", "mx:c");
            assertEquals(":0", readReply(in));

            // ---- 负数走的是那一句原文，不是"清空" ----
            send(socket, "XADD", "mx:d", "MAXLEN", "-1", "1-1", "a", "1");
            assertEquals("-ERR The MAXLEN argument must be >= 0.", readReply(in), ":1268-1271");
            send(socket, "XLEN", "mx:d");
            assertEquals(":0", readReply(in), "被拒的 XADD 不建键");
        } finally {
            server.stop();
            thread.join(2000);
        }
    }

    /**
     * {@code XSETID key <id>}（上游 {@code t_stream.c:1931-1956}）：这一格的数法全在"精确 3"的
     * arity（5.0.14 命令表 {@code server.c:321}）与"什么时候才问比表顶小"那一条上。
     * 改前实测（{@code battery67.pre}，43 行）里 XSETID 的 <b>20</b> 行全不是上游的答 ——
     * 它那时是一个不存在的命令。账是 {@code zdiff.py battery67.pre battery67.post} 量出来的：
     * 行数 43、顺序不同 0、<b>真实不一致 27</b>，其中 20 行就是 XSETID 本身，另外 7 行
     * （{@code :5 :12 :22 :25 :34 :37 :43}）是表顶被挪动之后 XADD/XLEN 跟着变的下游证据。
     * {@code :40 :41} 两侧同答（{@code XGROUP CREATE} 收 0、{@code XGROUP SETID} 照收 {@code -}），
     * 是"非严格那一支本来就在"的对照 —— 严格与否则由 {@code :38 :39} 那两枚 {@code -}/{@code +} 定。
     * 对照组 {@code :17} 的 {@code XAUTOCLAIM} 两侧都回 unknown，这一格<b>本来就是对的</b>：
     * 5.0.14 的命令表 {@code server.c:314-327} 里没有它。{@code :16} 的 {@code XCLAIM} 同样两侧
     * unknown，但那一格是真缺口（命令表 {@code :324} 有它，arity 是 {@code -6}），归下一支。
     */
    @Test
    void xsetidMovesTheTopAndOnlyRefusesToCrossALiveEntry() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- 挪得动，而且 XADD 读的就是挪过的那个表顶 ----
            send(socket, "XADD", "xs:a", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XSETID", "xs:a", "2-2");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "xs:a", "1-5", "a", "2");
            assertEquals("-ERR The ID specified in XADD is equal or smaller than "
                    + "the target stream top item", readReply(in),
                    "1-5 本来是能写的（表顶在 1-1）：这一条被拒才是 XSETID 真的动了 s->last_id 的证据");
            send(socket, "XADD", "xs:a", "3-1", "a", "3");
            assertEquals("3-1", readReply(in));

            // ---- 等于存活表顶是允许的：:1946 的条件是 `< 0`，不是 XADD 那个 `<= 0` ----
            send(socket, "XSETID", "xs:a", "3-1");
            assertEquals("+OK", readReply(in), "同一个位置 XADD 会拒（等于表顶），XSETID 不拒");
            // ---- 比存活表顶小才拒，而且拒了不改任何东西 ----
            send(socket, "XSETID", "xs:a", "2-2");
            assertEquals("-ERR The ID specified in XSETID is smaller than "
                    + "the target stream top item", readReply(in), ":1946-1950");
            send(socket, "XADD", "xs:a", "MAXLEN", "0", "2-2", "a", "4");
            assertEquals("-ERR The ID specified in XADD is equal or smaller than "
                    + "the target stream top item", readReply(in), "被拒的 XADD 连裁剪都不跑");
            send(socket, "XLEN", "xs:a");
            assertEquals(":2", readReply(in), "上面两问都没动条目");

            // ---- 空流可以往回挪，而那正是这一支存在的理由 ----
            // :1942 那一问外面套着 `if (s->length > 0)`：一条都不在的时候 ID 空间是允许重开的。
            send(socket, "XADD", "xs:c", "MAXLEN", "0", "6-6", "a", "5");
            assertEquals("6-6", readReply(in));
            send(socket, "XLEN", "xs:c");
            assertEquals(":0", readReply(in));
            send(socket, "XADD", "xs:c", "2-2", "a", "6");
            assertEquals("-ERR The ID specified in XADD is equal or smaller than "
                    + "the target stream top item", readReply(in), "清空不退回 ID 空间（上一格那条）");
            send(socket, "XSETID", "xs:c", "1-1");
            assertEquals("+OK", readReply(in), "空流上没有\"比表顶小\"这一问");
            // 同一个位置换成 0-0 也照样过：:1937 只做 strict 解析，"0-0 一律拒"是 XADD
            // 独有的那一道（:1292-1295），XSETID 这一支没有它（battery67:33 量的就是这一格）。
            send(socket, "XSETID", "xs:c", "0-0");
            assertEquals("+OK", readReply(in), ":1942 的 `if (s->length > 0)` 不进，0-0 就写得进去");
            send(socket, "XADD", "xs:c", "2-2", "a", "6");
            assertEquals("2-2", readReply(in), "退回去之后 2-2 就又能写了");
            // 一旦有活条目，同一个 0-0 立刻过不了（battery67:21 同形：那边表顶在 2-2）。
            send(socket, "XSETID", "xs:c", "0-0");
            assertEquals("-ERR The ID specified in XSETID is smaller than "
                    + "the target stream top item", readReply(in),
                    "同一句话在空流上不成句、在有条目的流上才成句，差别只在 s->length");

            // ---- ID 是 strict 解析，而 strict 只管得住 `-` 与 `+`（:1179-1180）----
            // 与 XGROUP SETID 正好相反，那边 :1895 用的是 streamParseIDOrReply（非严格）：
            // 同一枚 `-` 在这里非法、在那里是合法位置（解析成 0-0）。`$` 与 `*` 两支都过不了
            // string2ull（:1197），分不出严格与否 —— 只有 `-`/`+` 分得出。这一格是 Y2 变异
            // （把 true 摘成 false）当初钻过去的地方，所以两侧都得上树量。
            send(socket, "XSETID", "xs:c", "-");
            assertEquals("-ERR Invalid stream ID specified as stream command argument", readReply(in),
                    ":1179 那一问只在 strict 为真时才拦");
            send(socket, "XSETID", "xs:c", "+");
            assertEquals("-ERR Invalid stream ID specified as stream command argument", readReply(in));
            send(socket, "XGROUP", "CREATE", "xs:c", "g1", "0");
            assertEquals("+OK", readReply(in));
            send(socket, "XGROUP", "SETID", "xs:c", "g1", "-");
            assertEquals("+OK", readReply(in), "对照：非严格那一支照收同一枚 -");
            send(socket, "XSETID", "xs:c", "$");
            assertEquals("-ERR Invalid stream ID specified as stream command argument", readReply(in));
            send(socket, "XSETID", "xs:c", "*");
            assertEquals("-ERR Invalid stream ID specified as stream command argument", readReply(in));

            // ---- 没有 `-` 时补的是 0（:1937 传下去的 missing_seq 就是 0，不是 MAX）----
            send(socket, "XSETID", "xs:c", "4");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "xs:c", "4-1", "a", "7");
            assertEquals("4-1", readReply(in), "裸 4 落的是 4-0；补成 4-MAX 这一条就写不进了");

            // ---- arity 是精确 3（server.c:321），三个字之外一律命令表那句 ----
            send(socket, "XSETID");
            assertEquals("-ERR wrong number of arguments for 'xsetid' command", readReply(in));
            send(socket, "XSETID", "xs:c");
            assertEquals("-ERR wrong number of arguments for 'xsetid' command", readReply(in));
            send(socket, "XSETID", "xs:c", "2-2", "extra");
            assertEquals("-ERR wrong number of arguments for 'xsetid' command", readReply(in),
                    "同族的 xadd/-5、xgroup/-2、xpending/-3 都收多余字，只有这一支多一个字都不收");

            // ---- 判序：取键在 ID 解析之前（:1932 在 :1937 之前）----
            send(socket, "XSETID", "xs:ghost", "bad-id");
            assertEquals("-ERR no such key", readReply(in),
                    "shared.nokeyerr（server.c:1462-1463）：它不像 XADD 那样把流建起来");
            send(socket, "SET", "xs:str", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "XSETID", "xs:str", "bad-id");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                    readReply(in), "类型那一问也在 ID 之前，所以这里看不到 invalid stream ID");
            send(socket, "GET", "xs:str");
            assertEquals("hello", readReply(in), "被拦下的 XSETID 没有把那枚 String 变成别的");
        } finally {
            server.stop();
            thread.join(2000);
        }
    }

    /**
     * XGROUP 的三道闸与分派（上游 {@code t_stream.c:1798-1926}，句子在
     * {@code networking.c:623-630}）。这一支钉的是<b>顺序</b>而不是"认不认得子命令"：
     * 第六个字必须是 MKSTREAM（:1817-1824）→ 取键问类型（:1827-1834）→ 键必须存在、
     * SETID/DELCONSUMER 还要求组存在（:1837-1857）→ 最后才轮到分派，而分派把每个子命令的
     * 参数个数钉死（CREATE 只认 5/6、DESTROY 只认 4、DELCONSUMER 只认 5）。
     * 改前实测（{@code battery59.pre}，39 行）翻 14 行：{@code :7 :14 :15 :16 :17 :18 :20
     * :24 :25 :27 :28 :29 :31 :37}（其中 {@code :18 :27} 只是换了形状的假句，HELP 与 SETID
     * 的分派仍未兑现，记在已知边界）。
     */
    @Test
    void xgroupGatesRunBeforeSubcommandDispatch() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- 阳性对照：建流、建组、重名 ----
            send(socket, "SET", "xg:str", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "xg:s", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "xg:s", "2-2", "b", "2");
            assertEquals("2-2", readReply(in));
            send(socket, "XGROUP", "CREATE", "xg:s", "g1", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XGROUP", "CREATE", "xg:s", "g1", "0-0");
            assertEquals("-BUSYGROUP Consumer Group name already exists", readReply(in));
            send(socket, "XGROUP", "create", "xg:s", "g1", "0-0");
            assertEquals("-BUSYGROUP Consumer Group name already exists", readReply(in),
                    ":1860 的 strcasecmp：子命令名大小写不敏感，落回那一句时才是照原样的那一格");

            // ---- 第一道闸：六个字里第六个必须是 MKSTREAM（:1817-1824，排在取键之前） ----
            send(socket, "XGROUP", "CREATE", "xg:s", "g2", "0-0", "EXTRA");
            assertEquals("-ERR Unknown subcommand or wrong number of arguments for 'CREATE'."
                    + " Try XGROUP HELP.", readReply(in), "改前这一行是 +OK：EXTRA 被当空气");
            send(socket, "XGROUP", "CREATE", "xg:s", "g2", "0-0");
            assertEquals("+OK", readReply(in), "被拒的那一次没把组留下");
            send(socket, "XGROUP", "CREATE", "xg:s", "g3", "0-0", "MKSTREAM");
            assertEquals("+OK", readReply(in), "键本来就在，MKSTREAM 是白给的");
            send(socket, "XGROUP", "CREATE", "xg:mk", "g4", "0-0", "MKSTREAM");
            assertEquals("+OK", readReply(in), "键不在时 MKSTREAM 才顶住那道存在性闸");
            send(socket, "XGROUP", "CREATE", "xg:mk", "g4", "0-0");
            assertEquals("-BUSYGROUP Consumer Group name already exists", readReply(in),
                    "键不在时 XLEN 量不出区别（空流与无键都是 :0），所以拿组重名当 MKSTREAM 的阳性对照");
            send(socket, "XGROUP", "CREATE", "xg:mk2", "g5", "bad-id", "MKSTREAM");
            assertEquals("-ERR Invalid stream ID specified as stream command argument", readReply(in),
                    ":1869 的 ID 那一问排在 :1873-1879 的建流之前");
            send(socket, "XGROUP", "CREATE", "xg:mk3", "g88", "0-0", "mkstream");
            assertEquals("+OK", readReply(in), ":1818 也是 strcasecmp");
            send(socket, "XGROUP", "CREATE", "xg:mk3", "g88", "0-0");
            assertEquals("-BUSYGROUP Consumer Group name already exists", readReply(in),
                    "小写那一次确实把流和组建出来了");

            // ---- 第二、三道闸：类型 → 键存在 → 组存在（:1827-1857） ----
            send(socket, "XGROUP", "CREATE", "xg:ghost", "g9", "0-0");
            assertEquals("-ERR The XGROUP subcommand requires the key to exist. Note that for CREATE"
                    + " you may want to use the MKSTREAM option to create an empty stream automatically.",
                    readReply(in), "改前这一行是 +OK，键被顺手建了出来");
            send(socket, "XGROUP", "DESTROY", "xg:ghost", "g9");
            assertEquals("-ERR The XGROUP subcommand requires the key to exist. Note that for CREATE"
                    + " you may want to use the MKSTREAM option to create an empty stream automatically.",
                    readReply(in), "闸在分派之前，所以 DESTROY 自己那句 :0 轮不到说话");
            send(socket, "XGROUP", "DELCONSUMER", "xg:ghost", "g9", "c1");
            assertEquals("-ERR The XGROUP subcommand requires the key to exist. Note that for CREATE"
                    + " you may want to use the MKSTREAM option to create an empty stream automatically.",
                    readReply(in));
            send(socket, "XGROUP", "CREATECONSUMER", "xg:ghost", "g9", "c1");
            assertEquals("-ERR The XGROUP subcommand requires the key to exist. Note that for CREATE"
                    + " you may want to use the MKSTREAM option to create an empty stream automatically.",
                    readReply(in), "改前 :0");
            send(socket, "XGROUP", "DESTROY", "xg:s", "nosuchgroup");
            assertEquals(":0", readReply(in), "键在而组不在：DESTROY 不在 :1849-1850 那份名单里，才是 :0");
            send(socket, "XGROUP", "DELCONSUMER", "xg:s", "nosuchgroup", "c1");
            assertEquals("-NOGROUP No such consumer group 'nosuchgroup' for key name 'xg:s'",
                    readReply(in), ":1852-1854，码就是 NOGROUP 本身");
            send(socket, "XGROUP", "SETID", "xg:s", "nosuchgroup", "0-0");
            assertEquals("-NOGROUP No such consumer group 'nosuchgroup' for key name 'xg:s'",
                    readReply(in));
            send(socket, "XGROUP", "SETID", "xg:ghost", "g1", "0-0");
            assertEquals("-ERR The XGROUP subcommand requires the key to exist. Note that for CREATE"
                    + " you may want to use the MKSTREAM option to create an empty stream automatically.",
                    readReply(in), "键那一问排在组那一问之前");
            send(socket, "XGROUP", "CREATE", "xg:str", "g9", "0-0");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                    readReply(in));
            send(socket, "XGROUP", "DELCONSUMER", "xg:str", "g9", "c1");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                    readReply(in), "类型那一问也在键存在与组存在之前");

            // ---- 分派：个数钉死，落回的都是同一句（:1860 :1902 :1913 :1923-1924） ----
            String unknown = "Unknown subcommand or wrong number of arguments";
            send(socket, "XGROUP");
            assertEquals("-ERR wrong number of arguments for 'xgroup' command", readReply(in),
                    "server.c:320 的 arity 是 -2：只有光杆吃命令表那一句");
            send(socket, "XGROUP", "CREATE", "xg:s");
            assertEquals("-ERR " + unknown + " for 'CREATE'. Try XGROUP HELP.", readReply(in),
                    "三个字：够不着取键那一问（:1827 要 argc>=4），也够不着分派，落回同一句");
            send(socket, "XGROUP", "CREATE", "xg:s", "g8");
            assertEquals("-ERR " + unknown + " for 'CREATE'. Try XGROUP HELP.", readReply(in),
                    "改前是自造的 for 'xgroup create' command");
            send(socket, "XGROUP", "CREATE", "xg:s", "g8", "0-0", "A", "B");
            assertEquals("-ERR " + unknown + " for 'CREATE'. Try XGROUP HELP.", readReply(in),
                    "七个字：既不是 5 也不是 6");
            send(socket, "XGROUP", "DESTROY", "xg:s", "g1", "EXTRA");
            assertEquals("-ERR " + unknown + " for 'DESTROY'. Try XGROUP HELP.", readReply(in));
            send(socket, "XGROUP", "DESTROY", "xg:s", "g1");
            assertEquals(":1", readReply(in), "上面那次拒绝没动到 g1");
            send(socket, "XGROUP", "DESTROY", "xg:s", "g1");
            assertEquals(":0", readReply(in));
            send(socket, "XGROUP", "FOO", "xg:s", "g2");
            assertEquals("-ERR " + unknown + " for 'FOO'. Try XGROUP HELP.", readReply(in), "改前是 syntax error");
            send(socket, "XGROUP", "foo", "xg:s", "g2");
            assertEquals("-ERR " + unknown + " for 'foo'. Try XGROUP HELP.", readReply(in),
                    "networking.c:627 的第一个占位是 argv[1] 照原样的那个字，只有命令名才大写");
            // 组不在的 DELCONSUMER 只有四个字：闸（:1848）排在分派（:1913）之前，所以先答 NOGROUP。
            send(socket, "XGROUP", "DELCONSUMER", "xg:s", "nosuchgroup");
            assertEquals("-NOGROUP No such consumer group 'nosuchgroup' for key name 'xg:s'",
                    readReply(in));

            // ---- DELCONSUMER 交的是"还压着几条"（:1916-1917），不是"删没删掉" ----
            send(socket, "XGROUP", "CREATE", "xg:s", "g6", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g6", "c1", "COUNT", "2", "STREAMS", "xg:s", ">");
            assertEquals("[[xg:s, [[1-1, [a, 1]], [2-2, [b, 2]]]]]", readReplyDeep(in));
            send(socket, "XGROUP", "DELCONSUMER", "xg:s", "g6", "c1");
            assertEquals(":2", readReply(in), "改前 :1 —— 布尔答复把\"压着两条\"和\"一条没有\"混成一格");
            send(socket, "XGROUP", "DELCONSUMER", "xg:s", "g6", "c1");
            assertEquals(":0", readReply(in), "消费者已经不在");
            send(socket, "XGROUP", "CREATECONSUMER", "xg:s", "g6", "c2");
            assertEquals(":1", readReply(in));
            send(socket, "XGROUP", "DELCONSUMER", "xg:s", "g6", "c2");
            assertEquals(":0", readReply(in), "删掉一个手上没东西的消费者也是 :0：这一格量的确实是条数");
            // 再钉一层"条数是现数的、不是发过几条"：换个新组重发两条、ACK 掉一条，答复要跟着降到 :1。
            send(socket, "XGROUP", "CREATE", "xg:s", "g7", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g7", "c3", "COUNT", "2", "STREAMS", "xg:s", ">");
            assertEquals("[[xg:s, [[1-1, [a, 1]], [2-2, [b, 2]]]]]", readReplyDeep(in));
            send(socket, "XACK", "xg:s", "g7", "1-1");
            assertEquals(":1", readReply(in));
            send(socket, "XGROUP", "DELCONSUMER", "xg:s", "g7", "c3");
            assertEquals(":1", readReply(in), "不是\"发过两条\"，是\"还剩一条\"");
            // 清账与否用历史读来验，不去碰 XPENDING 摘要那一套形状。
            send(socket, "XREADGROUP", "GROUP", "g6", "c1", "STREAMS", "xg:s", "0-0");
            assertEquals("[[xg:s, []]]", readReplyDeep(in),
                    "c1 名下的账已经跟着消费者一起销了");

            send(socket, "GET", "xg:str");
            assertEquals("hello", readReply(in), "被闸门拦下的 XGROUP 不改写别的键");
        } finally {
            server.stop();
            thread.join(2000);
        }
    }

    /**
     * {@code XGROUP SETID} 与 {@code XGROUP HELP}（上游 {@code t_stream.c:1891-1901}、
     * {@code :1921-1922}，HELP 的线形在 {@code networking.c:604-617}）。
     * 前一支钉的是"搬得动、且两段一起搬"，后一支钉的是每一项的类型字节。
     *
     * <p>改前实测（{@code battery60.pre}，33 行）里 SETID 那 12 行全部落在"认不得的子命令"，
     * HELP 那 3 行落在同一句；补上分派后翻 15 行（{@code :6 :7 :8 :10 :11 :12 :13 :14 :16 :23
     * :24 :25 :26 :32 :33}），其中一条顺带照出一个更早的病：
     * 组的"最后投递位"是两个 uint64，而 {@code 18446744073709551615} 在 Java 里只能存成
     * {@code -1}，旧代码把它拼回字符串再解析（{@code "-1--1"}）会被 {@code string2ll} 的
     * "负数即越界"拒收、退成 {@code 0-0}，于是顶格位置读起来像流起点 —— 一条都不该投的
     * 变成整条流重投。{@code battery61} 在新旧两版 jar 上逐行相同，证明这一条与 SETID 无关，
     * 光用 {@code XGROUP CREATE k g 18446744073709551615-…} 就已经能踩到（{@code :1147}）。
     */
    @Test
    void xgroupSetidMovesThePositionAndHelpRepliesStatusStrings() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- 阳性对照：三条目的流 + 一个从 0-0 起步的组 ----
            send(socket, "SET", "si:str", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "si:s", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "XADD", "si:s", "2-2", "b", "2");
            assertEquals("2-2", readReply(in));
            send(socket, "XADD", "si:s", "3-3", "c", "3");
            assertEquals("3-3", readReply(in));
            send(socket, "XGROUP", "CREATE", "si:s", "g1", "0-0");
            assertEquals("+OK", readReply(in));

            // ---- 位置确实搬得动，答复是 +OK（:1900）----
            send(socket, "XGROUP", "SETID", "si:s", "g1", "2-2");
            assertEquals("+OK", readReply(in), "改前是\"认不得的子命令\"");
            send(socket, "XREADGROUP", "GROUP", "g1", "c1", "STREAMS", "si:s", ">");
            assertEquals("[[si:s, [[3-3, [c, 3]]]]]", readReplyDeep(in), "停在 2-2 之后只剩 3-3");
            send(socket, "XGROUP", "SETID", "si:s", "g1", "$");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g1", "c2", "STREAMS", "si:s", ">");
            assertEquals("*-1", readReplyDeep(in),
                    ":1893-1894 取的是 s->last_id，比它更新的一条都没有；:1575-1585 不 serve，落 :1664 的 nullmultibulk");
            send(socket, "XGROUP", "SETID", "si:s", "g1", "0-0");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g1", "c3", "COUNT", "5", "STREAMS", "si:s", ">");
            assertEquals("[[si:s, [[1-1, [a, 1]], [2-2, [b, 2]], [3-3, [c, 3]]]]]", readReplyDeep(in),
                    "搬回起点，整条流又是新的");

            // ---- 两段一起换：bare "3" 补的是 missing_seq=0（:1199），不是停在旧 seq 上 ----
            send(socket, "XGROUP", "SETID", "si:s", "g1", "3-3");
            assertEquals("+OK", readReply(in));
            send(socket, "XGROUP", "SETID", "si:s", "g1", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g1", "c4", "STREAMS", "si:s", ">");
            assertEquals("[[si:s, [[3-3, [c, 3]]]]]", readReplyDeep(in),
                    "只换毫秒段会停在 3-3 什么也不投；两段一起换才退回 3-0，于是 3-3 又算新的");

            // ---- SETID 用非严格解析（:1895），CREATE 用严格的那一支（:1869）----
            String invalidId = "-ERR Invalid stream ID specified as stream command argument";
            send(socket, "XGROUP", "CREATE", "si:s", "g9", "-");
            assertEquals(invalidId, readReply(in));
            send(socket, "XGROUP", "CREATE", "si:s", "g9", "+");
            assertEquals(invalidId, readReply(in), ":1179-1180 那一问只在 strict 时拦");
            send(socket, "XGROUP", "SETID", "si:s", "g1", "-");
            assertEquals("+OK", readReply(in), "同一个 '-'，SETID 收并展开成 0-0");
            send(socket, "XREADGROUP", "GROUP", "g1", "c5", "COUNT", "5", "STREAMS", "si:s", ">");
            assertEquals("[[si:s, [[1-1, [a, 1]], [2-2, [b, 2]], [3-3, [c, 3]]]]]", readReplyDeep(in),
                    "位置真的落在 0-0，不是 +OK 之后什么也没动");
            send(socket, "XGROUP", "SETID", "si:s", "g1", "+");
            assertEquals("+OK", readReply(in), "'+' 展开成 MAX-MAX");
            send(socket, "XREADGROUP", "GROUP", "g1", "c6", "STREAMS", "si:s", ">");
            assertEquals("*-1", readReplyDeep(in),
                    "改前这一行交出整条流：MAX-MAX 拼成 \"-1--1\" 后解析失败、退成 0-0");
            send(socket, "XREADGROUP", "GROUP", "g1", "c6", "STREAMS", "si:s", "0-0");
            assertEquals("[[si:s, []]]", readReplyDeep(in), "c6 名下的历史也是空的，不是投了没记");

            // ---- 同一个坑不在 SETID 这一侧：从 CREATE 起步的顶格位置一样要拦得住 ----
            send(socket, "XGROUP", "CREATE", "si:s", "gmax", "18446744073709551615-18446744073709551615");
            assertEquals("+OK", readReply(in), "uint64 顶格上游收（string2ull 退到 strtoull，:1147）");
            send(socket, "XREADGROUP", "GROUP", "gmax", "r1", "STREAMS", "si:s", ">");
            assertEquals("*-1", readReplyDeep(in), "battery61:5 改前是整条流");
            send(socket, "XGROUP", "CREATE", "si:s", "gmid", "9223372036854775808-0");
            assertEquals("+OK", readReply(in), "跨过 2^63 不是边界，无符号比才认得它");
            send(socket, "XREADGROUP", "GROUP", "gmid", "r2", "STREAMS", "si:s", ">");
            assertEquals("*-1", readReplyDeep(in));
            send(socket, "XGROUP", "CREATE", "si:s", "gseq", "1-18446744073709551615");
            assertEquals("+OK", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "gseq", "r4", "STREAMS", "si:s", ">");
            assertEquals("[[si:s, [[2-2, [b, 2]], [3-3, [c, 3]]]]]", readReplyDeep(in),
                    "seq 段顶格而 ms 段还在 1：只有 ms 更大的算新（改前 1-1 也被投出去）");

            // ---- SETID 的 arity 与闸门的先后 ----
            String unknown = "Unknown subcommand or wrong number of arguments";
            send(socket, "XGROUP", "SETID", "si:s", "g1");
            assertEquals("-ERR " + unknown + " for 'SETID'. Try XGROUP HELP.", readReply(in),
                    ":1891 把 SETID 钉在 argc==5，改前这一行是假的 arity 句");
            send(socket, "XGROUP", "SETID", "si:s", "g1", "0-0", "EXTRA");
            assertEquals("-ERR " + unknown + " for 'SETID'. Try XGROUP HELP.", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g1", "c7", "STREAMS", "si:s", ">");
            assertEquals("*-1", readReplyDeep(in),
                    "g1 仍在 MAX-MAX：被 arity 拒掉的那两次没有半途把位置搬走");
            send(socket, "XGROUP", "SETID", "si:s", "nosuch", "0-0", "EXTRA");
            assertEquals("-NOGROUP No such consumer group 'nosuch' for key name 'si:s'", readReply(in),
                    ":1848 那一问不看个数，所以组存在性排在 SETID 的 arity 之前");
            send(socket, "XGROUP", "SETID", "si:s", "g1", "bad-id");
            assertEquals(invalidId, readReply(in), ":1895 非严格不等于不检查");
            send(socket, "XGROUP", "SETID", "si:str", "g1", "0-0");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                    readReply(in));
            send(socket, "GET", "si:str");
            assertEquals("hello", readReply(in), "SETID 不碰别的键");

            // ---- HELP：七项，且每一项都是状态串而不是 bulk ----
            final String[] lines = {
                    "XGROUP <subcommand> arg arg ... arg. Subcommands are:",
                    "CREATE      <key> <groupname> <id or $> [opt] -- Create a new consumer group.",
                    "            option MKSTREAM: create the empty stream if it does not exist.",
                    "SETID       <key> <groupname> <id or $>  -- Set the current group ID.",
                    "DESTROY     <key> <groupname>            -- Remove the specified group.",
                    "DELCONSUMER <key> <groupname> <consumer> -- Remove the specified consumer.",
                    "HELP                                     -- Prints this help."};
            String[][] helpCalls = {{"HELP"}, {"help"}, {"HELP", "extra"}, {"HELP", "si:ghost"}};
            for (String[] call : helpCalls) {
                String[] argv = new String[call.length + 1];
                argv[0] = "XGROUP";
                System.arraycopy(call, 0, argv, 1, call.length);
                send(socket, argv);
                assertEquals("*7", readWireReply(in),
                        ":1921 那一支不数参数；且三个字（含命令名）够不着 :1827 那道 argc>=4 的闸");
                String[] onWire = new String[lines.length];
                for (int i = 0; i < lines.length; i++) {
                    onWire[i] = readWireReply(in);
                    assertEquals("+" + lines[i], onWire[i],
                            "第 " + i + " 项：addReplyHelp（:610-612）逐项 addReplyStatus，类型字节是 + 而不是 $");
                }
                // 判的是服务器交回来的那七行，不是测试自己写的那份常量
                assertFalse(String.join("|", onWire).contains("CREATECONSUMER"),
                        "那是我们超出 5.0.14 的一支，列出去等于向 5.0.14 的用户承诺它没有的子命令");
            }
            // 不列 ≠ 不兑现：这一支照样做得动，只是不对外写
            send(socket, "XGROUP", "CREATECONSUMER", "si:s", "g1", "cX");
            assertEquals(":1", readReply(in));

            // 闸门不会因为"它是 HELP"就跳过：:1826 的注释写着 HELP 不要键，而 :1837 的代码
            // 只看 argc>=4，没有给 HELP 豁免 —— 按代码，不按注释。
            send(socket, "XGROUP", "HELP", "si:ghost", "g1");
            assertEquals("-ERR The XGROUP subcommand requires the key to exist. Note that for CREATE"
                    + " you may want to use the MKSTREAM option to create an empty stream automatically.",
                    readReply(in), "四个字（含命令名）就够着了那道闸");
            send(socket, "XGROUP", "HELP", "si:str", "g1");
            assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                    readReply(in), "同一道闸里的类型那一问（:1830）也照样管着 HELP");
        } finally {
            server.stop();
            thread.join(2000);
        }
    }

    /**
     * stream 键是键 —— 键空间那一层十问从此把第六张表一起算进来。
     * <p>
     * 上游没有"stream 自己的一份键表"这种东西：一个 db 就一本 dict，这些命令全在那本 dict 上
     * 做，除 {@code TYPE} 之外没有一处按类型分支（5.0.14 行号）——
     * {@code dbsizeCommand} {@code db.c:808} 就一句 {@code dictSize(c->db->dict)}；
     * {@code typeCommand} {@code db.c:816-839} 是一个 {@code switch(o->type)}，
     * {@code OBJ_STREAM} 那一支写着 {@code "stream"}（:830）；
     * {@code renameGenericCommand} {@code db.c:869-907} 搬的是 robj 指针
     * （{@code incrRefCount(o)} → {@code dbAdd(dst,o)} → {@code dbDelete(src)}），
     * 压根不知道搬的是哪种类型；{@code moveCommand} {@code db.c:919} 同样只问
     * {@code lookupKeyWrite}；{@code flushdbCommand} {@code db.c:432-437} 交回
     * {@code emptyDb(c->db->id, …)} 把整本 dict 丢掉。
     * <p>
     * 我们这一侧流住在 {@code ServerScope} 的第六张表里，而键空间用的那把尺
     * （{@code MemoryStore.typeOfDb}）只翻前五张表，于是实测到的形状是
     * "同一枚键名，两问两答"（battery68 两侧各 83 行，30 处真实不一致，全在这一格）：
     * {@code TYPE} 回 {@code +none} 而 {@code XLEN} 回 {@code :1}；{@code DEL} 回 {@code :0}
     * 而东西照旧在（第 5/7 行）；{@code SET} 顶掉流键之后 {@code DEL} 只删得掉 string 那半份，
     * 剩下那半份 {@code XLEN} 还能数出旧条目（第 46 行）；{@code RENAME}/{@code MOVE}
     * 回 {@code -ERR no such key}；{@code FLUSHDB} 之后旧表顶还在（第 56 行）。
     * <p>
     * 修法只动一把尺：{@code typeOfDb} 现在也问第六张表，凡是拿它当判据的地方一起跟上。
     * 所以这一支的断言面不是"stream 命令能跑了"（那是 {@link
     * #streamFamilyHoldsTheSameOneTypeInvariant()} 那一支），而是<b>不相干的那十问
     * 现在必须把流键当一枚普通键</b>。
     */
    @Test
    void streamsAreOrdinaryKeysForKeyspaceCommands() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- 一把尺的自证：六张表各摆一枚，十问给出的个数必须彼此咬合 ----
            send(socket, "SET", "kt:string", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "HSET", "kt:hash", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "RPUSH", "kt:list", "x");
            assertEquals(":1", readReply(in));
            send(socket, "SADD", "kt:set", "x");
            assertEquals(":1", readReply(in));
            send(socket, "ZADD", "kt:zset", "1", "x");
            assertEquals(":1", readReply(in));
            send(socket, "XADD", "kt:stream", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));

            send(socket, "DBSIZE");
            assertEquals(":6", readReply(in), "少的那一枚一定是漏看了某张表；改之前这里是 :5");
            for (String typeName : new String[]{"string", "hash", "list", "set", "zset", "stream"}) {
                send(socket, "TYPE", "kt:" + typeName);
                assertEquals("+" + typeName, readReply(in));
                send(socket, "EXISTS", "kt:" + typeName);
                assertEquals(":1", readReply(in), typeName + " 键不能只在 TYPE 那一问里存在");
            }
            // KEYS 的次序是按表走的（五张表走完才轮到 stream 表），SCAN 自己排过序。
            // 钉这两串的次序不是上游承诺（dict 迭代序两边都不承诺），钉的是"流键不再被漏掉"。
            send(socket, "KEYS", "kt:*");
            assertEquals("[kt:string, kt:hash, kt:list, kt:set, kt:zset, kt:stream]", readReplyDeep(in));
            send(socket, "SCAN", "0", "COUNT", "100");
            assertEquals("[0, [kt:hash, kt:list, kt:set, kt:stream, kt:string, kt:zset]]", readReplyDeep(in));
            // 第四把尺：INFO 的 keyspace 一行。改之前它是"第五把尺" —— 五张表各自 size() 相加，
            // 既不算流键、也不判过期，于是这六枚键在 DBSIZE 里是 6、在 INFO 里是 5，谁都不报错。
            send(socket, "INFO", "keyspace");
            String keyspace = readReply(in);
            java.util.regex.Matcher ks = java.util.regex.Pattern.compile("db0:keys=(\\d+)").matcher(keyspace);
            assertTrue(ks.find(), "INFO 里连 db0:keys= 这一行都没有，实际: " + keyspace);
            assertEquals(6, Integer.parseInt(ks.group(1)),
                    "INFO 报的键数必须与 DBSIZE 是同一个数（少的那一枚是流键），实际: " + keyspace);
            // 六枚一起 DEL：改之前这一句只回 :5，流那枚既删不掉也不算数
            send(socket, "DEL", "kt:string", "kt:hash", "kt:list", "kt:set", "kt:zset", "kt:stream");
            assertEquals(":6", readReply(in));
            send(socket, "DBSIZE");
            assertEquals(":0", readReply(in), "一句都不剩");

            // ---- EXISTS / DEL / 表顶：删掉的是整枚对象，不是"把条目清空" ----
            send(socket, "XADD", "ks:s", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "TYPE", "ks:s");
            assertEquals("+stream", readReply(in), "db.c:830 那一支");
            send(socket, "EXISTS", "ks:s");
            assertEquals(":1", readReply(in));
            send(socket, "DEL", "ks:s");
            assertEquals(":1", readReply(in), "DEL 说没删掉而 XLEN 说还有一条，是修之前的形状");
            send(socket, "EXISTS", "ks:s");
            assertEquals(":0", readReply(in));
            send(socket, "TYPE", "ks:s");
            assertEquals("+none", readReply(in));
            send(socket, "XLEN", "ks:s");
            assertEquals(":0", readReply(in));
            // 表顶跟着对象一起没了，所以 1-1 又能写进去。这一问只能排在 DEL 之后：
            // 上面那一串已经让 ks:s 空过一轮，改成断言"清空"也照样绿。
            send(socket, "XADD", "ks:s", "1-1", "b", "2");
            assertEquals("1-1", readReply(in), "只清空不删对象的话，这一句会吃 ID 太小");

            // ---- RANDOMKEY：库里只剩流键时它必须被选中（改之前这一问回 nil） ----
            send(socket, "RANDOMKEY");
            assertEquals("ks:s", readReply(in));

            // ---- 中央类型闸门：别的族往流键上伸手，三问都得说 WRONGTYPE ----
            // 改之前这三问是这一格里最坏的一段：GET 回 nil（客户端读成"键不存在"）、
            // HGETALL 回空数组，而 LPUSH 干脆<b>办成了</b> —— 它看见 typeOfDb 说 NONE 就当新键建，
            // 于是一枚键名下同时挂着流和 list（battery68 第 24/25/26 行）。
            expectWrongType(socket, in, "GET", "ks:s");
            expectWrongType(socket, in, "HGETALL", "ks:s");
            expectWrongType(socket, in, "LPUSH", "ks:s", "x");
            send(socket, "TYPE", "ks:s");
            assertEquals("+stream", readReply(in), "拦下来不等于换掉了类型");
            send(socket, "DBSIZE");
            assertEquals(":1", readReply(in), "三问一起拒掉，也没有多开一格");

            // ---- 一个键名只有一种类型：SET 顶掉流键，就得把流一起带走 ----
            send(socket, "XADD", "ks:m", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "SET", "ks:m", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "TYPE", "ks:m");
            assertEquals("+string", readReply(in));
            send(socket, "XLEN", "ks:m");
            assertTrue(readReply(in).startsWith("-WRONGTYPE"), "旧的半份流不许还能数得出条目");
            send(socket, "GET", "ks:m");
            assertEquals("hello", readReply(in));
            send(socket, "DBSIZE");
            assertEquals(":2", readReply(in), "一个键名只占一格；改之前这里是 :3（两半各算一次）");
            send(socket, "DEL", "ks:m");
            assertEquals(":1", readReply(in));
            send(socket, "EXISTS", "ks:m");
            assertEquals(":0", readReply(in));
            send(socket, "XLEN", "ks:m");
            assertEquals(":0", readReply(in), "battery68 第 46 行：改之前这里回 :1，DEL 只删得掉 string 那半份");

            // ---- RENAME 搬的是整条流：消费组、PEL、表顶一起走 ----
            send(socket, "XADD", "ks:g", "1-1", "a", "0");
            assertEquals("1-1", readReply(in));
            send(socket, "XGROUP", "CREATE", "ks:g", "grp", "1-1");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "ks:g", "2-1", "a", "1");
            assertEquals("2-1", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "grp", "c1", "COUNT", "1", "STREAMS", "ks:g", ">");
            assertEquals("[[ks:g, [[2-1, [a, 1]]]]]", readReplyDeep(in));
            send(socket, "RENAME", "ks:g", "ks:gr");
            assertEquals("+OK", readReply(in), "改之前这里是 -ERR no such key");
            send(socket, "TYPE", "ks:gr");
            assertEquals("+stream", readReply(in));
            send(socket, "XLEN", "ks:gr");
            assertEquals(":2", readReply(in));
            send(socket, "XLEN", "ks:g");
            assertEquals(":0", readReply(in), "源键整个没了，不是留了一份副本");
            // 组跟着走：last-delivered 还停在 2-1，PEL 里那条仍压在 c1 手上。
            // 汇总那四格与上游逐格对得上（t_stream.c:2056-2090）：个数是整数、
            // 每个消费者那一格是 addReplyBulkLongLong（:2085）而不是整数 —— 所以是 "1" 不是 :1。
            send(socket, "XINFO", "GROUPS", "ks:gr");
            assertEquals("[[name, grp, consumers, :1, pending, :1, last-delivered-id, 2-1]]", readReplyDeep(in));
            send(socket, "XPENDING", "ks:gr", "grp");
            assertEquals("[:1, 2-1, 2-1, [[c1, 1]]]", readReplyDeep(in));
            send(socket, "XACK", "ks:gr", "grp", "2-1");
            assertEquals(":1", readReply(in), "确认的是搬过来那个组手上的条目");
            // 清空后那三格上游交的是三个 nil（:2060-2062：nullbulk、nullbulk、nullmultibulk），
            // 不是空串也不是空数组 —— 这一串钉的是"确认完 PEL 真的空了"，形状顺带对上了上游。
            send(socket, "XPENDING", "ks:gr", "grp");
            assertEquals("[:0, $-1, $-1, *-1]", readReplyDeep(in));
            // 旧键名重新可用，而且不再记得旧表顶 —— 这是"搬走"而不是"复制"的另一面
            send(socket, "XADD", "ks:g", "1-1", "z", "9");
            assertEquals("1-1", readReply(in));
            send(socket, "XPENDING", "ks:g", "grp");
            assertTrue(readReply(in).startsWith("-NOGROUP"), "组也没有留在旧名上被接着用");

            // ---- MOVE：跨库也是搬对象，源库那本表当场清账 ----
            send(socket, "FLUSHDB");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "ks:mv", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));
            send(socket, "MOVE", "ks:mv", "1");
            assertEquals(":1", readReply(in), "改之前这里是 :0，而键也确实没搬");
            send(socket, "EXISTS", "ks:mv");
            assertEquals(":0", readReply(in), "源库里已经没有");
            send(socket, "SELECT", "1");
            assertEquals("+OK", readReply(in));
            send(socket, "TYPE", "ks:mv");
            assertEquals("+stream", readReply(in));
            send(socket, "XLEN", "ks:mv");
            assertEquals(":1", readReply(in));
            send(socket, "DBSIZE");
            assertEquals(":1", readReply(in));
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));

            // ---- FLUSHDB 之后四问一起归零，旧表顶不再压着新写入 ----
            send(socket, "XADD", "ks:mv", "9-9", "a", "1");
            assertEquals("9-9", readReply(in), "回到 0 库，同名键是一枚新的空流");
            send(socket, "FLUSHDB");
            assertEquals("+OK", readReply(in));
            send(socket, "DBSIZE");
            assertEquals(":0", readReply(in));
            send(socket, "KEYS", "*");
            assertEquals("[]", readReplyDeep(in));
            send(socket, "TYPE", "ks:mv");
            assertEquals("+none", readReply(in));
            send(socket, "XADD", "ks:mv", "1-1", "a", "1");
            assertEquals("1-1", readReply(in), "battery68 第 56 行：改之前这里吃 ID 太小，9-9 那个表顶没人清");

            // ---- 六种类型都能挂上过期，而且挂上之后真的会到点 ----
            // 这一串以前是**钉现状的 fence**：EXPIRE 在流键和 list 键上都回 :0，注释写着
            // "五种集合键共同的 TTL 缺口，不在这一格里修"。上游 expireGenericCommand
            // （expire.c:415-451）没有类型分支、:426 只问 lookupKeyWrite，回的是 :1 ——
            // 五支 TTL 方法改成只问 typeOfDb 一把尺之后，这里翻成钉合格，六种类型各钉一问。
            send(socket, "SET", "tt:string", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "HSET", "tt:hash", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "RPUSH", "tt:list", "x");
            assertEquals(":1", readReply(in));
            send(socket, "SADD", "tt:set", "x");
            assertEquals(":1", readReply(in));
            send(socket, "ZADD", "tt:zset", "1", "x");
            assertEquals(":1", readReply(in));
            send(socket, "XADD", "tt:stream", "1-1", "a", "1");
            assertEquals("1-1", readReply(in));

            // 两条前提，缺任何一条下面那一串 -1 都是空跑
            send(socket, "TTL", "tt:nosuch");
            assertEquals(":-2", readReply(in), "前提：键真的不在才是 -2");
            send(socket, "EXPIRE", "tt:nosuch", "100");
            assertEquals(":0", readReply(in), "前提：键不在时 EXPIRE 不许顺手造出一枚键");

            for (String typeName : new String[]{"string", "hash", "list", "set", "zset", "stream"}) {
                String k = "tt:" + typeName;
                send(socket, "TTL", k);
                assertEquals(":-1", readReply(in), typeName + " 键在而没挂过期：-1，不是 -2");
                send(socket, "EXPIRE", k, "100");
                assertEquals(":1", readReply(in), typeName + " 键也要挂得上过期");
                send(socket, "PERSIST", k);
                assertEquals(":1", readReply(in), typeName + " 挂上之后 PERSIST 读得到那一行");
                send(socket, "TTL", k);
                assertEquals(":-1", readReply(in), typeName + " 取消之后回到 -1，而不是 -2（键还在）");
                send(socket, "PERSIST", k);
                assertEquals(":0", readReply(in), typeName + " 没有可取消的过期时回 0");
                send(socket, "EXISTS", k);
                assertEquals(":1", readReply(in), typeName + " 回 0 的那一句不许顺手把键删了");
            }

            // 挂上之后真的会到点：上一格修好的"类型无关的惰性删除"到这里才第一次能拿集合键量到
            send(socket, "PEXPIRE", "tt:zset", "1");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIREAT", "tt:hash", "1");
            assertEquals(":1", readReply(in), "一个过去的绝对时刻：当场删、回 1（上游 checkAlreadyExpired 那一支）");
            Thread.sleep(30);
            send(socket, "EXISTS", "tt:zset");
            assertEquals(":0", readReply(in), "到点的 zset 键不在");
            send(socket, "TYPE", "tt:hash");
            assertEquals("+none", readReply(in), "到点的 hash 键也不在");

            // ---- 键是"自己掏空"没的，时刻行也必须跟着没 ----
            // 上游删键只有一个口（dbSyncDelete 先删 db->expires 再删 db->dict，db.c:271-281），
            // 而我们"最后一个元素走了所以键不在了"这个决定长在四个 store 里（LPOP / HDEL /
            // SPOP / ZREM 那 21 处 store.remove），它们看不见时刻表 —— 接上通告口就是这一段在量的。
            // 留一行的后果不是"多一条垃圾"：那行是个未来的时刻，同名键复活会直接继承它。
            expiryRowDiesWhenTheKeyEmptiesItself(in, socket,
                    new String[]{"RPUSH", "e:list", "x"}, new String[]{"LPOP", "e:list"}, "x");
            expiryRowDiesWhenTheKeyEmptiesItself(in, socket,
                    new String[]{"HSET", "e:hash", "f", "v"}, new String[]{"HDEL", "e:hash", "f"}, ":1");
            expiryRowDiesWhenTheKeyEmptiesItself(in, socket,
                    new String[]{"SADD", "e:set", "x"}, new String[]{"SREM", "e:set", "x"}, ":1");
            expiryRowDiesWhenTheKeyEmptiesItself(in, socket,
                    new String[]{"ZADD", "e:zset", "1", "x"}, new String[]{"ZREM", "e:zset", "x"}, ":1");

            // 与 DBSIZE / KEYS 同一把尺对账：ks:mv 一枚 + 六种里活着的四枚（tt:hash、tt:zset 已到点）
            // + 复活的 e:* 四枚
            send(socket, "DBSIZE");
            assertEquals(":9", readReply(in));
            send(socket, "KEYS", "tt:*");
            assertEquals("[tt:string, tt:list, tt:set, tt:stream]", readReplyDeep(in),
                    "到点那两枚（tt:hash、tt:zset 的第一枚）不许还留在 KEYS 里");

            // 上面那几问（EXISTS / TYPE / TTL）都会顺手把到点的键摘掉，所以那两枚"不在"其实是被
            // 读路径回收的。这一段换一条没人碰过的路：挂上过期后让时钟走完，中间不做任何一次读，
            // 第一次问它的人就是 KEYS —— 只有枚举自己负责回收，才算量到了 liveKeys。
            send(socket, "HSET", "px:hash", "f", "1");
            assertEquals(":1", readReply(in));
            send(socket, "LPUSH", "px:list", "x");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "px:hash", "1");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "px:list", "1");
            assertEquals(":1", readReply(in));
            Thread.sleep(1200);
            send(socket, "KEYS", "px:*");
            assertEquals("[]", readReplyDeep(in),
                    "到点之后没有一次读发生过：两枚集合键必须由 KEYS 自己收走");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 一枚集合键"把自己掏空"的那一趟，四种类型共用：写入唯一一个成员 → 挂上过期 → 摘掉那一个成员
     * （这一步是各 store 内部的 {@code store.remove(key)}，不经过 {@code MemoryStore} 的删键口）
     * → 确认键已经不算存在 → 同名再写一次 → TTL 必须回到 -1。
     *
     * <p>三条前置各自带断言：挂不上过期（回 0）、那一个成员没被真摘掉、空键还 {@code EXISTS} 的话，
     * 最后那句 -1 就是空跑而不是判据。
     *
     * @param add          写入那一个成员的整条命令（含键名）
     * @param removeLast   摘掉那一个成员的整条命令（含键名）
     * @param removedReply 摘掉那一句该有的原文：{@code LPOP} 回的是值本身，其余回 {@code :1}
     */
    private static void expiryRowDiesWhenTheKeyEmptiesItself(DataInputStream in, Socket socket,
            String[] add, String[] removeLast, String removedReply) throws IOException {
        String key = add[1];
        send(socket, add);
        assertEquals(":1", readReply(in), key + " 的前置：那一个成员写进去了");
        send(socket, "EXPIRE", key, "100");
        assertEquals(":1", readReply(in), key + " 的前置：掏空之前先挂得上过期，否则下面全是空跑");
        send(socket, removeLast);
        assertEquals(removedReply, readReply(in), key + " 的前置：那一个成员真的被摘掉了");
        send(socket, "EXISTS", key);
        assertEquals(":0", readReply(in), key + " 空值键已经不算存在");
        send(socket, add);
        assertEquals(":1", readReply(in), key + " 同名重新写入");
        send(socket, "TTL", key);
        assertEquals(":-1", readReply(in),
                key + " 复活之后不许继承上一枚键的时刻行 —— 上游那里键没了时刻跟着一起没");
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
