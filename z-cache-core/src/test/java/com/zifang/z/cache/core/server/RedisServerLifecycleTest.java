package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.command.CommandHandler;
import com.zifang.z.cache.core.persistence.AofPersistence;
import com.zifang.z.cache.core.stream.StreamStore;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务器生命周期端到端测试：走真实 Netty bind + RESP 往返，而不是直接调 CommandHandler。
 * <p>
 * 存在的理由：Stream 是 1.3.0 的招牌特性，但 {@code initPersistence()} 在未配 dataDir 时
 * 早退，导致发行形态（没有人调过 setDataDir，仓库里零调用方）下所有 X* 命令返回
 * "Stream not configured"。单测直接 setStreamStore 所以全绿，没人从服务器侧看过这一眼。
 */
class RedisServerLifecycleTest {

    private static final long DEADLINE_MS = 5_000L;

    @Test
    void streamCommandsWithoutDataDir() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "XADD", "demo", "*", "field", "value");
            String id = readReply(in);
            assertFalse(id.startsWith("-ERR"), "XADD 在没有 --data-dir 时被回答: " + id);
            assertNotNull(server.serverScope().streamStore(),
                    "服务器必须给自己这一份建起 StreamStore（不是往进程的静态字段上挂）");

            send(socket, "XLEN", "demo");
            assertEquals(":1", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    @Test
    void infoTracksLiveConnectionCount() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket a = connect(port)) {
            // startAndWait 的探针连接可能还没被 reap，先等 A 读到只剩自己这一条
            assertEquals("1", awaitClients(a, 1),
                    "单连接时 INFO 应报 1");

            try (Socket b = connect(port)) {
                // 这一条才是判据：修之前 INFO 恒为 1（incrementConnectedClient 没人调，
                // 且 getConnectedClients()>0?:1 把 0 兜成 1），第二条连接进来也不会变 2。
                assertEquals("2", awaitClients(a, 2),
                        "开第二条连接后 connected_clients 必须是 2（旧实现永远报 1）");
            }

            assertEquals("1", awaitClients(a, 1),
                    "第二条连接关闭后 connected_clients 必须回落，说明 channelInactive 未减计数");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    @Test
    void authIsEnforcedBeforeOtherCommands() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0, "s3cret");
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "PING");
            assertTrue(readReply(in).startsWith("-NOAUTH"), "未 AUTH 的命令必须被拒");

            send(socket, "AUTH", "wrong");
            assertTrue(readReply(in).startsWith("-"));

            send(socket, "AUTH", "s3cret");
            assertEquals("+OK", readReply(in));

            send(socket, "PING");
            assertEquals("+PONG", readReply(in));
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    @Test
    void stopFromAnotherThreadDoesNotDeadlock() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);

        // start() 以前整个方法都是 synchronized，阻塞在 closeFuture() 时握着监视器，
        // 于是 shutdown hook 里的 stop() 永远拿不到锁 —— 进程关不掉，RDB 快照也永远不落。
        long begin = System.currentTimeMillis();
        server.stop();
        thread.join(DEADLINE_MS);

        assertFalse(thread.isAlive(), "stop() 之后服务线程必须退出（否则就是死锁）");
        assertTrue(System.currentTimeMillis() - begin < DEADLINE_MS, "stop() 不能阻塞等锁");
        assertFalse(server.isRunning());
    }

    @Test
    void blpopPopsOnlyRequestedKeyWithoutFreezingPeers() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket blocker = connect(port); Socket peer = connect(port)) {
            DataInputStream pin = new DataInputStream(peer.getInputStream());
            DataInputStream bin = new DataInputStream(blocker.getInputStream());

            // 库里先放一条"别的"非空列表：旧实现把 key 参数整段丢掉，
            // 于是 BLPOP mine 0 会立刻弹走 unrelated 并把 key 名 "other" 返回给客户端。
            // 前置条件必须自己钉死：这条推入没进库的话，下面整段什么都没测（变异体正是这样逃过一次）。
            send(peer, "LPUSH", "other", "unrelated");
            assertEquals(":1", readReply(pin), "前置条件: 干扰 key 必须真的进了库");

            send(blocker, "BLPOP", "mine", "0");

            // 阻塞期间另一条连接必须照常服务：旧实现在 I/O 线程上 while(true) await，
            // 同 EventLoop 上的所有连接一起冻住，这条 PING 根本回不来。
            send(peer, "PING");
            assertEquals("+PONG", readReply(pin), "BLPOP 阻塞期间其它连接必须还能被服务");

            send(peer, "LPUSH", "mine", "w");
            assertEquals(":1", readReply(pin));

            assertEquals(java.util.Arrays.asList("mine", "w"), readArray(bin),
                    "BLPOP mine 只能弹 mine，且要把命中的 key 一起带回");

            // 另一面：干扰 key 一个字节都不能少。只查回复里的 key 名会被"先扫到 mine"的
            // 遍历顺序救回去，这一条把"不许偷别人的列表"钉成不变量。
            send(peer, "LLEN", "other");
            assertEquals(":1", readReply(pin), "BLPOP mine 不得动别的 key");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 上面那条测试的"没有逃生口"版本：请求的 key 全程无人推入，所以阻塞命令唯一的
     * 正确出路就是超时返回 nil。任何"顺手弹了别的 key"的实现都会在这里立刻现形，
     * 不依赖 ConcurrentHashMap 的遍历顺序（同一份变异体在按顺序断言下有 1/2 概率蒙混过关）。
     */
    @Test
    void blpopTimesOutInsteadOfStealingOtherKeys() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket blocker = connect(port); Socket peer = connect(port)) {
            DataInputStream pin = new DataInputStream(peer.getInputStream());
            DataInputStream bin = new DataInputStream(blocker.getInputStream());

            send(peer, "LPUSH", "other", "unrelated");
            assertEquals(":1", readReply(pin), "前置条件: 库里必须有一条别人的数据当诱饵");

            send(blocker, "BLPOP", "nobody-pushes-this", "1");
            assertEquals("*-1", readReply(bin), "无人推入请求的 key 时 BLPOP 必须超时返回 nil");

            send(peer, "LLEN", "other");
            assertEquals(":1", readReply(pin), "超时也不许顺走别人的列表");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 挂起的阻塞命令不许吃掉普通命令的线程。
     * <p>
     * 线程数在这里被显式压小（2 条普通 / 16 条阻塞），因为这一条要判的是"多少条挂起的
     * BLPOP 能把服务器拖死"，答案不能随机器核数变化：只要实现把阻塞命令塞回普通线程组，
     * 第 3 条挂起就会让后面的 PING 永远排不到。
     */
    @Test
    void blockingCommandsCannotStarveNormalTraffic() throws Exception {
        System.setProperty("zcache.business-threads", "2");
        System.setProperty("zcache.blocking-threads", "16");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        java.util.List<Socket> parked = new java.util.ArrayList<>();
        Thread thread = null;
        try {
            thread = startAndWait(server, port);
            for (int i = 0; i < 8; i++) {
                Socket socket = connect(port);
                parked.add(socket);
                send(socket, "BLPOP", "starve-me", "0");
            }
            try (Socket fresh = connect(port)) {
                DataInputStream in = new DataInputStream(fresh.getInputStream());
                send(fresh, "PING");
                assertEquals("+PONG", readReply(in),
                        "8 条挂起的 BLPOP（普通线程只有 2 条）之后，新连接必须还能被服务");
            }
        } finally {
            for (Socket socket : parked) {
                closeQuietly(socket);
            }
            System.clearProperty("zcache.business-threads");
            System.clearProperty("zcache.blocking-threads");
            server.stop();
            if (thread != null) {
                thread.join(DEADLINE_MS);
            }
        }
    }

    /**
     * 客户端中途断线时，睡在 BLPOP 上的线程必须被叫醒还回线程池。
     * <p>
     * 只压 2 条阻塞线程、跑两轮各 6 条挂起连接：如果第一轮断线后线程没被释放，
     * 第二轮的挂起命令就永远排不上线程，回复永远不来。
     * {@code BLPOP key 0} 加一条断线是任何客户端都会做的事，泄漏在这里是永久性的。
     */
    @Test
    void disconnectReleasesTheBlockedWorkerThread() throws Exception {
        System.setProperty("zcache.business-threads", "4");
        System.setProperty("zcache.blocking-threads", "2");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        java.util.List<Socket> roundOne = new java.util.ArrayList<>();
        java.util.List<Socket> roundTwo = new java.util.ArrayList<>();
        Thread thread = null;
        try {
            thread = startAndWait(server, port);
            for (int i = 0; i < 6; i++) {
                Socket socket = connect(port);
                roundOne.add(socket);
                send(socket, "BLPOP", "gone", "0");
            }
            for (Socket socket : roundOne) {
                closeQuietly(socket);
            }

            for (int i = 0; i < 6; i++) {
                Socket socket = connect(port);
                roundTwo.add(socket);
                send(socket, "BLPOP", "gone", "0");
            }
            try (Socket producer = connect(port)) {
                DataInputStream in = new DataInputStream(producer.getInputStream());
                // 值给到 12 个而等待者只有 6 个：判据是"每条挂起的连接都拿到了回复"，
                // 不是"值刚好被分完"。第一轮哪怕真泄漏了一个还在等位的老连接，
                // 它也只会吃掉一个诱饵值，不会把断言变成随机的。
                String[] push = new String[14];
                push[0] = "LPUSH";
                push[1] = "gone";
                for (int i = 0; i < 12; i++) {
                    push[i + 2] = "v" + i;
                }
                send(producer, push);
                assertEquals(":12", readReply(in), "前置条件: 12 个值必须真的进了库");
                for (Socket socket : roundTwo) {
                    DataInputStream blocked = new DataInputStream(socket.getInputStream());
                    java.util.List<String> reply = readArray(blocked);
                    assertEquals(2, reply.size(), "BLPOP 必须回 [key, value] 两个元素");
                    assertEquals("gone", reply.get(0));
                    assertFalse(reply.get(1).isEmpty());
                }
            }
        } finally {
            for (Socket socket : roundTwo) {
                closeQuietly(socket);
            }
            System.clearProperty("zcache.business-threads");
            System.clearProperty("zcache.blocking-threads");
            server.stop();
            if (thread != null) {
                thread.join(DEADLINE_MS);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关不掉不影响判据
        }
    }

    /**
     * 写命令必须落到读命令看得见的那一份存储里，而且是当前 DB 的那一份。
     * <p>
     * 这批命令（HINCRBYFLOAT / LMOVE / SPOP / ZREMRANGEBYSCORE / ZREMRANGEBYLEX /
     * ZRANDMEMBER / ZLEXCOUNT / ZREMRANGEBYRANK ...）以前打的是 {@code CommandHandler}
     * 里一组静态 Store —— 除了它们自己，全服务器没有任何读路径会去那里看，
     * 于是现象是"命令回 :2 说删掉了两个，ZRANGE 一个不少"。
     * <p>
     * 这条测试必须走真实连接：单测里 setUp 每轮都 {@code new HashStore()} 塞进静态字段，
     * 正好把这个洞盖得严严实实（和 Stream 那个招牌 bug 一模一样的成因）。
     */
    @Test
    void collectionMutationsLandInTheKeyspaceReadersSee() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "ZADD", "zs", "1", "a", "2", "b", "3", "c");
            assertEquals(":3", readReply(in));
            send(socket, "ZREMRANGEBYSCORE", "zs", "0", "2");
            assertEquals(":2", readReply(in), "前置条件: 命令自己声称删了 2 个");
            send(socket, "ZRANGE", "zs", "0", "-1");
            assertEquals(java.util.Collections.singletonList("c"), readArray(in),
                    "ZREMRANGEBYSCORE 报删除成功，ZRANGE 就必须真的少掉那两个");

            send(socket, "ZADD", "z2", "1", "a", "2", "b");
            assertEquals(":2", readReply(in));
            send(socket, "ZREMRANGEBYLEX", "z2", "[a", "[a");
            assertEquals(":1", readReply(in), "前置条件: ZREMRANGEBYLEX 声称删了 1 个");
            send(socket, "ZRANGE", "z2", "0", "-1");
            assertEquals(java.util.Collections.singletonList("b"), readArray(in));

            send(socket, "HSET", "h", "f", "10");
            assertEquals(":1", readReply(in));
            send(socket, "HINCRBYFLOAT", "h", "f", "0.5");
            assertEquals("10.5", readReply(in));
            send(socket, "HGET", "h", "f");
            assertEquals("10.5", readReply(in), "HINCRBYFLOAT 要写进 HGET 读得到的那份 hash");

            send(socket, "LPUSH", "src", "a", "b");
            assertEquals(":2", readReply(in));
            send(socket, "LMOVE", "src", "dst", "LEFT", "RIGHT");
            assertEquals("b", readReply(in));
            send(socket, "LLEN", "dst");
            assertEquals(":1", readReply(in), "LMOVE 的目的地必须真的多了一个元素");
            send(socket, "LLEN", "src");
            assertEquals(":1", readReply(in), "LMOVE 的来源必须真的少了一个元素");

            send(socket, "SADD", "st", "x", "y", "z");
            assertEquals(":3", readReply(in));
            send(socket, "SPOP", "st");
            assertTrue(java.util.Arrays.asList("x", "y", "z").contains(readReply(in)),
                    "SPOP 必须回一个真在集合里的成员");
            send(socket, "SMEMBERS", "st");
            assertEquals(2, readArray(in).size(), "SPOP 之后 SMEMBERS 必须只剩 2 个成员");

            send(socket, "SELECT", "1");
            assertEquals("+OK", readReply(in));
            send(socket, "INFO", "keyspace");
            String keyspace = readReply(in);
            assertTrue(keyspace.contains("db0:keys="),
                    "INFO keyspace 要报出别的库的真实条数，实际: " + keyspace);
            assertFalse(keyspace.contains("db1:keys="),
                    "db1 一条数据都没有，不该出现在 keyspace 里");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * 快照必须装得下全部 16 个库，并且重启后带 TTL 回来。
     * <p>
     * 1.3.3 及之前：RDB 写侧硬编码 dbCount=1 / dbIndex=0，只导出 DB 0，
     * {@code getAllExpirationEntries()} 直接 {@code return new HashMap<>()}。
     * 所以 {@code SELECT 3} 之后写进去的数据在重启后静默消失，而 SETEX 的键回来变成了永久键
     * —— 两个方向都是丢数据，且都没有任何报错。
     * <p>
     * 这里重启前显式删掉 AOF：恢复顺序是"有 AOF 只认 AOF"，不删的话下面读到的全是重放结果，
     * 快照那一份根本没进过内存。
     */
    @Test
    void saveSnapshotsEveryDatabaseAndTheirTtls() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-rdb");
        int port = freePort();
        RedisServer first = new RedisServer("127.0.0.1", port, 0);
        first.setDataDir(dir.toString());
        Thread thread = startAndWait(first, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "SET", "k3", "v3");
            assertEquals("+OK", readReply(in), "前置条件: DB3 写入必须成功");
            send(socket, "SETEX", "t3", "3600", "tv");
            assertEquals("+OK", readReply(in));
            send(socket, "SETEX", "gone", "1", "x");
            assertEquals("+OK", readReply(in));

            send(socket, "SELECT", "7");
            assertEquals("+OK", readReply(in));
            send(socket, "LPUSH", "l7", "e1");
            assertEquals(":1", readReply(in), "前置条件: DB7 列表必须真有 1 个元素");

            // 等 gone 过期（1s + 余量），让快照根本不该带上它
            Thread.sleep(1_300);
            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "GET", "gone");
            assertEquals("$-1", readReply(in), "前置条件: gone 已经过期不可见");

            send(socket, "SAVE");
            assertEquals("+OK", readReply(in), "SAVE 必须如实回 +OK（配了 dataDir 时）");

            send(socket, "LASTSAVE");
            String lastSave = readReply(in);
            assertTrue(lastSave.startsWith(":") && Long.parseLong(lastSave.substring(1)) > 0,
                    "LASTSAVE 要报出真实的快照时刻，实际: " + lastSave);
        } finally {
            first.stop();
            thread.join(DEADLINE_MS);
        }
        assertTrue(java.nio.file.Files.size(dir.resolve("dump.rdb")) > 0,
                "SAVE 报了 +OK，磁盘上就必须有文件");

        java.nio.file.Files.deleteIfExists(dir.resolve("appendonly.aof"));

        int port2 = freePort();
        RedisServer second = new RedisServer("127.0.0.1", port2, 0);
        second.setDataDir(dir.toString());
        Thread thread2 = startAndWait(second, port2);
        try (Socket socket = connect(port2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "GET", "k3");
            assertEquals("v3", readReply(in), "DB3 的键必须活过重启（旧实现只导出 DB 0）");
            send(socket, "TTL", "t3");
            long ttl = Long.parseLong(readReply(in).substring(1));
            assertTrue(ttl > 3_000 && ttl <= 3_600,
                    "SETEX 的剩余时间必须一起回来，实际 TTL=" + ttl + "（旧实现会报 -1，变成永久键）");
            send(socket, "GET", "gone");
            assertEquals("$-1", readReply(in), "停机期间到期的键不得复活");
            send(socket, "DBSIZE");
            assertEquals(":2", readReply(in), "DB3 只该有 k3 与 t3 两个键");

            send(socket, "SELECT", "7");
            assertEquals("+OK", readReply(in));
            send(socket, "LLEN", "l7");
            assertEquals(":1", readReply(in), "DB7 的列表必须活过重启");

            // 反向证据：分库导出不是"全都塞进 DB 0"
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));
            send(socket, "GET", "k3");
            assertEquals("$-1", readReply(in), "k3 属于 DB3，不该出现在 DB0");
            send(socket, "DBSIZE");
            assertEquals(":0", readReply(in));
        } finally {
            second.stop();
            thread2.join(DEADLINE_MS);
        }
    }

    /**
     * 没配 dataDir 时 SAVE 不能装作成功。
     * <p>
     * 旧实现里 SAVE / BGSAVE 都直接返回一个写死的 OK，LASTSAVE 返回当前时间 —— 三个命令
     * 一个字都没落到磁盘上，运维看着"SAVE 成功、LASTSAVE 在涨"以为有快照。
     */
    @Test
    void saveWithoutDataDirFailsHonestly() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(socket, "SET", "k", "v");
            assertEquals("+OK", readReply(in));

            send(socket, "SAVE");
            String save = readReply(in);
            assertTrue(save.startsWith("-ERR"), "没有 dataDir 就没有快照目标，SAVE 必须报错，实际: " + save);

            send(socket, "LASTSAVE");
            assertEquals(":0", readReply(in), "从未成功保存过，LASTSAVE 只能是 0");

            send(socket, "PING");
            assertEquals("+PONG", readReply(in), "SAVE 失败不能把连接带坏");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
    }

    /**
     * AOF 必须真的能恢复数据 —— 它是发行形态里唯一一直在写的持久化路径。
     * <p>
     * 1.3.3 及之前 {@code loadAof()} 在主代码里零调用方：AOF 只写不读，宣传的掉电恢复
     * 一次都没有兑现过。而且它一旦被读起来会暴露三件事（这条测试逐条钉住）：
     * <ul>
     *   <li>写命令表里没有 {@code SELECT} 的库号，重放会把 5 号库的数据全落进 DB 0；</li>
     *   <li>BLPOP 消费掉的那个值在日志里毫无痕迹，重放后它又回到源列表里，可以被消费第二次；</li>
     *   <li>重放本身会再写一遍 AOF，日志每开一次机翻一倍。</li>
     * </ul>
     * 三代实例（A 写 → B 读并再写 → C 读）是为了第三条：只看 B 的话，"重复写入"要下一次开机才现形。
     */
    @Test
    void aofReplayRestoresDataAcrossThreeGenerations() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-aof");

        // ---- 第一代：只写，不 SAVE ----
        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        try (Socket socket = connect(p1)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "SELECT", "5");
            assertEquals("+OK", readReply(in));
            send(socket, "SET", "in5", "v5");
            assertEquals("+OK", readReply(in));
            send(socket, "LPUSH", "dbl", "x");
            assertEquals(":1", readReply(in), "前置条件: 列表先只有 1 个元素");
            send(socket, "LPUSH", "q", "a");
            assertEquals(":1", readReply(in));
            send(socket, "BLPOP", "q", "0");
            assertEquals(java.util.Arrays.asList("q", "a"), readArray(in),
                    "前置条件: BLPOP 当时真的弹到了 a");
            send(socket, "LLEN", "q");
            assertEquals(":0", readReply(in), "前置条件: 弹完就空了");
            send(socket, "SET", "multi", "first\r\nsecond");
            assertEquals("+OK", readReply(in), "前置条件: 含换行的值必须写得进去");
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }
        // 只留 AOF：快照那一份由上一条测试负责，这里叠上来就分不清是谁恢复的了
        java.nio.file.Files.deleteIfExists(dir.resolve("dump.rdb"));

        // ---- 第二代：靠重放恢复，再写一条 ----
        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "GET", "in5");
            assertEquals("$-1", readReply(in), "重放必须尊重库号：in5 在 DB5，不该出现在 DB0");
            send(socket, "SELECT", "5");
            assertEquals("+OK", readReply(in));
            send(socket, "GET", "in5");
            assertEquals("v5", readReply(in), "AOF 里的写入必须活过重启（旧实现根本不放 AOF）");
            send(socket, "LLEN", "dbl");
            assertEquals(":1", readReply(in), "重放只能演一遍：LPUSH dbl 落库后列表仍是 1 个");
            send(socket, "LLEN", "q");
            assertEquals(":0", readReply(in), "BLPOP 消费掉的值不得被重放回炉");
            send(socket, "GET", "multi");
            assertEquals("first\r\nsecond", readReply(in), "含 CRLF 的值必须按声明长度原样取回");
            send(socket, "SET", "in6", "v6");
            assertEquals("+OK", readReply(in));
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }
        java.nio.file.Files.deleteIfExists(dir.resolve("dump.rdb"));

        // ---- 第三代：证明第二代的重放没有回写 AOF ----
        int p3 = freePort();
        RedisServer gen3 = new RedisServer("127.0.0.1", p3, 0);
        gen3.setDataDir(dir.toString());
        Thread t3 = startAndWait(gen3, p3);
        try (Socket socket = connect(p3)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "SELECT", "5");
            assertEquals("+OK", readReply(in));
            send(socket, "GET", "in6");
            assertEquals("v6", readReply(in), "第二代的新写入也要活到第三代");
            send(socket, "LLEN", "dbl");
            assertEquals(":1", readReply(in), "重放若回写 AOF，这一代就会看到 dbl 被 LPUSH 了两次");
            send(socket, "GET", "multi");
            assertEquals("first\r\nsecond", readReply(in));
        } finally {
            gen3.stop();
            t3.join(DEADLINE_MS);
        }
    }

    /**
     * 定时快照必须真的会自己落盘 —— 这是"进程被 kill -9 之后还能捞回多少"的唯一兜底。
     * <p>
     * 修之前有两处：{@code RdbPersistence.start()} 一个调用方都没有（调度器根本没跑），
     * 而就算跑了，{@code onWrite()} 也是零调用 → {@code writeCounter} 恒为 0 →
     * {@code shouldSave()} 永远判 false。两处任缺其一，"每隔 N 秒自动快照"都只是类注释。
     */
    @Test
    void periodicSnapshotRunsWithoutExplicitSave() throws Exception {
        System.setProperty("zcache.save-seconds", "1");
        System.setProperty("zcache.save-changes", "1");
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-periodic");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        server.setDataDir(dir.toString());
        Thread thread = null;
        try {
            thread = startAndWait(server, port);
            try (Socket socket = connect(port)) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                send(socket, "SET", "auto-saved", "yes");
                assertEquals("+OK", readReply(in), "前置条件: 写入必须成功（这一次写入就是快照的触发条件）");
            }

            java.nio.file.Path snapshot = dir.resolve("dump.rdb");
            long deadline = System.currentTimeMillis() + DEADLINE_MS;
            while (System.currentTimeMillis() < deadline && !java.nio.file.Files.exists(snapshot)) {
                Thread.sleep(100);
            }
            assertTrue(java.nio.file.Files.exists(snapshot),
                    "save-seconds=1 / save-changes=1 时，不需要任何 SAVE，快照必须自己出现");
            String raw = new String(java.nio.file.Files.readAllBytes(snapshot), StandardCharsets.ISO_8859_1);
            assertTrue(raw.contains("auto-saved"),
                    "落盘的必须真是这份数据，而不是一个空壳文件");
        } finally {
            System.clearProperty("zcache.save-seconds");
            System.clearProperty("zcache.save-changes");
            server.stop();
            if (thread != null) {
                thread.join(DEADLINE_MS);
            }
        }
    }

    /**
     * AOF 的 fsync 档位要真能配，而且配错时不能装作采纳了。
     * <p>
     * {@code setFsyncPolicy} / {@code parseFsyncPolicy} 一直是对外 API，但服务器侧没人读配置，
     * 所以 {@code appendfsync always} 怎么写都是 EVERYSEC —— 用户以为每条命令都落盘了。
     */
    @Test
    void appendfsyncPolicyIsHonouredAndBadValuesAreVisible() throws Exception {
        System.setProperty("zcache.appendfsync", "always");
        int port = freePort();
        RedisServer strict = new RedisServer("127.0.0.1", port, 0);
        strict.setDataDir(java.nio.file.Files.createTempDirectory("zcache-fsync").toString());
        Thread thread = startAndWait(strict, port);
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "SET", "k", "v");
            assertEquals("+OK", readReply(in));
            assertNotNull(strict.getAofPersistence(), "配了 dataDir 就必须有 AOF");
            assertEquals(AofPersistence.FSYNC_ALWAYS, strict.getAofPersistence().getFsyncPolicy(),
                    "appendfsync=always 必须真的生效");
        } finally {
            strict.stop();
            thread.join(DEADLINE_MS);
        }

        System.setProperty("zcache.appendfsync", "weekly");
        int port2 = freePort();
        RedisServer bogus = new RedisServer("127.0.0.1", port2, 0);
        bogus.setDataDir(java.nio.file.Files.createTempDirectory("zcache-fsync2").toString());
        Thread thread2 = startAndWait(bogus, port2);
        try {
            assertEquals(AofPersistence.FSYNC_EVERYSEC, bogus.getAofPersistence().getFsyncPolicy(),
                    "非法档位不能被静默采纳成别的值");
        } finally {
            System.clearProperty("zcache.appendfsync");
            bogus.stop();
            thread2.join(DEADLINE_MS);
        }
    }

    // ==================== helpers ====================

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
        }, "test-z-cache-server");
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
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

    /**
     * 在既有连接上反复读 INFO clients，直到 connected_clients 等于 expected。
     * 返回值用于断言：命中时是读数本身，超时则是最后一次拿到的整段文本，便于失败定位。
     */
    private static String awaitClients(Socket socket, int expected) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            send(socket, "INFO", "clients");
            last = readReply(in);
            int idx = last.indexOf("connected_clients:");
            if (idx >= 0) {
                String digits = "";
                for (char c : last.substring(idx + "connected_clients:".length()).toCharArray()) {
                    if (!Character.isDigit(c)) {
                        break;
                    }
                    digits += c;
                }
                if (!digits.isEmpty() && Integer.parseInt(digits) == expected) {
                    return digits;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
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

    /** 只解析测试用到的 RESP 形状：+/-/: 单行，$ bulk 按声明长度读满。 */
    private static String readReply(DataInputStream in) throws IOException {
        String line = readLine(in);
        if (line.isEmpty()) {
            return line;
        }
        char type = line.charAt(0);
        if (type == '$') {
            int length = Integer.parseInt(line.substring(1));
            if (length < 0) {
                return line;
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            in.readFully(new byte[2]);
            return new String(payload, StandardCharsets.UTF_8);
        }
        return line;
    }

    /** 读一个 RESP 多批量回复，元素按 bulk 取字符串。 */
    private static java.util.List<String> readArray(DataInputStream in) throws IOException {
        String line = readLine(in);
        assertTrue(line.startsWith("*"), "期望数组回复，实际: " + line);
        int n = Integer.parseInt(line.substring(1));
        java.util.List<String> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(readReply(in));
        }
        return out;
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
