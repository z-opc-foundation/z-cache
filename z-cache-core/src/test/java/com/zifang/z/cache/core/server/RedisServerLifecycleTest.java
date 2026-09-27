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
import java.util.LinkedHashMap;
import java.util.Map;

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

            // 四种集合键各挂一枚长 TTL，再挂一枚"停机期间会到点"的：前三者要带着时刻一起回来，
            // 后者整枚都不许回来（上游那一判在加载侧，rdb.c:2097）。
            send(socket, "EXPIRE", "l7", "3600");
            assertEquals(":1", readReply(in), "前置条件: 列表键挂得上时刻行");
            send(socket, "HSET", "h7", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "h7", "3600");
            assertEquals(":1", readReply(in), "前置条件: hash 键挂得上时刻行");
            send(socket, "SADD", "s7", "m");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "s7", "3600");
            assertEquals(":1", readReply(in), "前置条件: set 键挂得上时刻行");
            send(socket, "ZADD", "z7", "1", "m");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "z7", "3600");
            assertEquals(":1", readReply(in), "前置条件: zset 键挂得上时刻行");
            send(socket, "HSET", "dead7", "f", "v");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "dead7", "1");
            assertEquals(":1", readReply(in), "前置条件: dead7 的时刻会在停机期间过去");

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

            // 一张表一次断言，六个格子全收进去。JUnit 见第一条红就抛：
            // 四型各写一条 assertEquals，摘掉任何一型只红在它自己那一行，另外三型一起丢了没人说；
            // 值、时刻、dead7 分三批断言（上一版就是这么写的），S6 那种"三样一起坏"的变异只会
            // 归给先红的那一批 —— 于是"摘掉加载侧那道闸"到底等价不等价，量具答不出来。
            // 收进一格之后每一次都逐格交回来，连没坏的那几格也一并交回（那就是归属的阳性对照）。
            String[][] seeded = {{"l7", "LLEN"}, {"h7", "HLEN"}, {"s7", "SCARD"}, {"z7", "ZCARD"}};
            Map<String, String> seen = new LinkedHashMap<>();
            Map<String, String> wrong = new LinkedHashMap<>();
            for (String[] cell : seeded) {
                send(socket, cell[1], cell[0]);
                String size = readReply(in).substring(1);
                send(socket, "TTL", cell[0]);
                String ttlText = readReply(in).substring(1);
                long remaining = Long.parseLong(ttlText);
                seen.put(cell[0], "值 " + size + " 个成员, TTL " + ttlText);
                if (!"1".equals(size) || remaining <= 3_000L || remaining > 3_600L) {
                    wrong.put(cell[0], seen.get(cell[0]));
                }
            }
            send(socket, "EXISTS", "dead7");
            String deadAlive = readReply(in);
            seen.put("dead7", "EXISTS " + deadAlive);
            if (!":0".equals(deadAlive)) {
                wrong.put("dead7", seen.get("dead7"));
            }
            send(socket, "DBSIZE");
            String howMany = readReply(in);
            seen.put("DBSIZE", howMany);
            if (!":4".equals(howMany)) {
                wrong.put("DBSIZE", howMany);
            }
            assertTrue(wrong.isEmpty(), "四种集合键重启后要带着值、也带着剩余时间一起回来，停机期间到点的那一枚"
                    + "整枚都不许回来（TTL -1 = restore 那条腿没读它拿到的 expireAt，键变成永久键；"
                    + "值 0 个成员或 TTL -2 = 值那一半根本没写进去；EXISTS :1 = 只抹时刻、键留着，"
                    + "那比留着旧时刻更糟），逐格: " + seen);

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
     * AOF 里只能记<b>绝对</b>时刻 —— 这是"停机期间到点"那一判在重放之后仍然成立的唯一前提。
     * <p>
     * 上游在落盘<em>之前</em>就换写：{@code feedAppendOnlyFile}（aof.c:594-606）把
     * EXPIRE / PEXPIRE / EXPIREAT 换成 PEXPIREAT，SETEX / PSETEX 拆成 SET + PEXPIREAT，
     * 带 EX/PX 的 SET 同样拆开；换算体 {@code catAppendOnlyExpireAtCommand}（:550-577）里
     * 那一句 {@code when += mstime()}（:566）就是"日志里不留相对时间"的地方，
     * 注释（:543-549）写得很直白：<i>the time is always absolute and not relative</i>。
     * <p>
     * 我们此前把 argv 原样 append，相对毫秒于是从"重放的那一刻"重新起算，后果有两层：
     * 一枚早就在停机期间死掉的键会再活一个完整周期，而所有活着的键都被免费续期停机那一段。
     * 两层各给一格行为判据，末尾再加一格结构守卫（直接读回日志里每条记录的动词）。
     */
    @Test
    void aofReplayCarriesAbsoluteExpiryAcrossDowntime() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-aof-ttl");
        java.nio.file.Path aof = dir.resolve("appendonly.aof");

        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        try (Socket socket = connect(p1)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            // 挂时刻的三种入口各留一枚：EXPIRE（五型各一枚）、SETEX、SET 带 EX。
            String[][] alive = {{"a_str", "STRLEN"}, {"a_list", "LLEN"}, {"a_hash", "HLEN"},
                    {"a_set", "SCARD"}, {"a_zset", "ZCARD"}};
            send(socket, "SET", "a_str", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "LPUSH", "a_list", "e");
            assertEquals(":1", readReply(in), "前置条件: list 真有 1 个成员");
            send(socket, "HSET", "a_hash", "f", "v");
            assertEquals(":1", readReply(in), "前置条件: hash 真有 1 个域");
            send(socket, "SADD", "a_set", "m");
            assertEquals(":1", readReply(in), "前置条件: set 真有 1 个成员");
            send(socket, "ZADD", "a_zset", "1", "m");
            assertEquals(":1", readReply(in), "前置条件: zset 真有 1 个成员");
            for (String[] cell : alive) {
                send(socket, "EXPIRE", cell[0], "3600");
                assertEquals(":1", readReply(in), "前置条件: " + cell[0] + " 挂得上时刻");
            }
            send(socket, "SETEX", "a_setex", "3600", "v");
            assertEquals("+OK", readReply(in));
            // 第六个入口：SET 带 EX —— 上游同样要拆成 SET + PEXPIREAT（aof.c:607-622），
            // 少了这一支，"EX/PX 那一枚没被摘掉"就只有这一格会红。
            send(socket, "SET", "a_setopt", "v", "EX", "3600");
            assertEquals("+OK", readReply(in));
            // 一条失败的 SETEX：键身上那一行时刻是上一次成功挂的。换写若只看时刻、不看回话，
            // 就会把"从来没写进去的那个值"连同旧时刻一起记进日志 —— 重放之后 a_setbad 的内容被改掉。
            send(socket, "SETEX", "a_setbad", "3600", "good");
            assertEquals("+OK", readReply(in));
            send(socket, "SETEX", "a_setbad", "0", "poison");
            assertEquals("-ERR invalid expire time in setex", readReply(in),
                    "前置条件: 第二次 SETEX 当场失败（值没写，k 仍带着上一次挂上的时刻）");
            send(socket, "GET", "a_setbad");
            assertEquals("good", readReply(in), "前置条件: 关服之前 a_setbad 读到的还是 good");
            // 两枚"停机期间会到点"的：一枚走 SETEX 那一支，一枚走 EXPIRE 那一支
            send(socket, "SETEX", "g_setex", "1", "x");
            assertEquals("+OK", readReply(in));
            send(socket, "HSET", "g_hash", "f", "v");
            assertEquals(":1", readReply(in), "前置条件: g_hash 写得进去");
            send(socket, "EXPIRE", "g_hash", "1");
            assertEquals(":1", readReply(in), "前置条件: g_hash 挂得上时刻");
            // 一枚"这一问根本没改库"的 EXPIRE：键不在，时刻挂不上，日志里也就不该留下它
            // （换写做不到时宁可一个字都不记 —— 退回原样记 argv 就是把相对时间留在日志里）。
            send(socket, "EXPIRE", "never_armed", "100");
            assertEquals(":0", readReply(in), "前置条件: 键不在，EXPIRE 回 0");
            send(socket, "TTL", "g_hash");
            assertEquals(":1", readReply(in), "前置条件: 关服之前 g_hash 还活着（否则下面那两格是空跑）");
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }
        // 只留 AOF：停机快照会顺手写一份 dump.rdb，两份都在就分不清是谁恢复的了
        java.nio.file.Files.deleteIfExists(dir.resolve("dump.rdb"));
        long downtimeStart = System.currentTimeMillis();
        Thread.sleep(1_500);   // g_setex / g_hash 在这一段里就已经死了

        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            Map<String, String> seen = new LinkedHashMap<>();
            Map<String, String> wrong = new LinkedHashMap<>();
            long downtimeMillis = System.currentTimeMillis() - downtimeStart;
            // 上界 = 3600 秒减去真实停机时长（留 1 秒余量防整数舍入），下界 = 还活着。
            // 记相对时间的话这里恒等于 3600 —— 每一枚键都被免费续期了停机那一段。
            long ceiling = 3_600L - Math.max(1L, downtimeMillis / 1000L);
            String[][] cells = new String[][]{
                    {"a_str", "STRLEN", ":1"}, {"a_list", "LLEN", ":1"}, {"a_hash", "HLEN", ":1"},
                    {"a_set", "SCARD", ":1"}, {"a_zset", "ZCARD", ":1"}, {"a_setex", "STRLEN", ":1"},
                    {"a_setopt", "STRLEN", ":1"}, {"a_setbad", "GET", "good"}};
            for (String[] cell : cells) {
                send(socket, "EXISTS", cell[0]);
                String exists = readReply(in);
                send(socket, cell[1], cell[0]);
                String content = readReply(in);
                send(socket, "TTL", cell[0]);
                String ttlText = readReply(in);
                long remaining = Long.parseLong(ttlText.substring(1));
                seen.put(cell[0], "EXISTS " + exists + ", " + cell[1] + " " + content + ", TTL " + ttlText);
                if (!":1".equals(exists) || !cell[2].equals(content)
                        || remaining <= 3_000L || remaining > ceiling) {
                    wrong.put(cell[0], seen.get(cell[0]));
                }
            }
            String[] dead = {"g_setex", "g_hash"};
            for (String key : dead) {
                send(socket, "EXISTS", key);
                String exists = readReply(in);
                send(socket, "TTL", key);
                String ttlText = readReply(in);
                seen.put(key, "EXISTS " + exists + ", TTL " + ttlText);
                if (!":0".equals(exists) || !":-2".equals(ttlText)) {
                    wrong.put(key, seen.get(key));
                }
            }
            seen.put("停机时长", downtimeMillis + "ms");
            assertTrue(wrong.isEmpty(), "AOF 重放之后：活着的每一格要带着值和已经扣掉停机时长的剩余时间回来"
                    + "（TTL 恰好 3600 = 日志里记的还是相对时间，重放时重新起算；"
                    + "a_setbad 读到 poison = 一条失败的 SETEX 也被换写进了日志），"
                    + "死了的两格就是死了（EXISTS :1 = 停机期间到点被当成\"重放后又活一个周期\"），逐格: " + seen);

            // 结构守卫：日志里每条过期记录都必须是绝对时刻的那一个动词。
            // 上面那一问是行为尺（"键还在不在、剩余多少"），摘掉某一支换写、但把相对量写对的行为
            // 仍可能全绿；这里直接读回记录的动词，逐字判"日志里不再有相对时间"。
            java.util.List<String> verbs = new java.util.ArrayList<>();
            new com.zifang.z.cache.core.persistence.AofPersistence()
                    .loadAof(aof.toString(), command -> verbs.add(command[0]));
            assertTrue(verbs.contains("PEXPIREAT"), "换写后的记录里必须真有 PEXPIREAT，实际动词: " + verbs);
            java.util.List<String> relative = new java.util.ArrayList<>();
            for (String verb : verbs) {
                if (verb.equals("EXPIRE") || verb.equals("PEXPIRE")
                        || verb.equals("EXPIREAT") || verb.equals("SETEX") || verb.equals("PSETEX")) {
                    relative.add(verb);
                }
            }
            assertTrue(relative.isEmpty(), "AOF 里不许留下任何相对时间的过期命令，实际: " + relative
                    + "（全部动词: " + verbs + "）");
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }
    }

    /**
     * AOF 重写换上去的那一份，必须是"当前状态的最小命令集"。
     * <p>
     * 上游同处 {@code rewriteAppendOnlyFileRio}（{@code aof.c:1299}）逐库补 {@code SELECT j}、
     * 逐键按类型补 {@code SET}/{@code RPUSH}/{@code SADD}/{@code HMSET}/{@code ZADD}，值之后紧跟一条
     * 绝对时刻的 {@code PEXPIREAT}（{@code :1352-1356}），变参命令一条至多 64 个成员
     * （{@code server.h:100}）。重写之所以存在，是因为日志里堆的是<em>流水</em>：同一个键写 61 次，
     * 恢复只需要最后一次。所以判据分两层 —— 行为层问"重启之后还在不在"，结构层问"塌没塌"：
     * 只判前者会放过"原样抄一份旧日志"这一支（数据一字不少，体积一点不降，重写等于白做）。
     * </p>
     */
    @Test
    void aofRewriteExportsTheDatasetInsteadOfEmptyingTheLog() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-aof-rewrite");
        java.nio.file.Path aof = dir.resolve("appendonly.aof");

        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        Map<String, String> shape = new LinkedHashMap<>();
        Map<String, String> shapeWrong = new LinkedHashMap<>();
        try (Socket socket = connect(p1)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            for (int i = 0; i < 60; i++) {
                send(socket, "SET", "r_str", "churn-" + i);
                assertEquals("+OK", readReply(in));
            }
            send(socket, "SET", "r_str", "final");
            assertEquals("+OK", readReply(in));
            send(socket, "EXPIRE", "r_str", "3600");
            assertEquals(":1", readReply(in), "前置条件: r_str 挂得上时刻");
            send(socket, "SETEX", "r_setex", "3600", "v");
            assertEquals("+OK", readReply(in));
            send(socket, "LPUSH", "r_list", "a");
            assertEquals(":1", readReply(in));
            send(socket, "LPUSH", "r_list", "b");
            assertEquals(":2", readReply(in));
            send(socket, "LPUSH", "r_list", "c");
            assertEquals(":3", readReply(in));
            send(socket, "HSET", "r_hash", "f1", "v1");
            assertEquals(":1", readReply(in));
            send(socket, "HSET", "r_hash", "f2", "v2");
            assertEquals(":1", readReply(in));
            for (int i = 0; i < 130; i++) {
                send(socket, "SADD", "r_set", "m" + i);
                assertEquals(":1", readReply(in), "前置条件: 第 " + i + " 个成员要写得进去");
            }
            send(socket, "ZADD", "r_zset", "1", "alpha");
            assertEquals(":1", readReply(in));
            send(socket, "ZADD", "r_zset", "2.5", "beta");
            assertEquals(":1", readReply(in));
            send(socket, "SET", "r_crlf", "first\r\nsecond");
            assertEquals("+OK", readReply(in), "前置条件: 含换行的值写得进去");
            send(socket, "SET", "r_dead", "x");
            assertEquals("+OK", readReply(in));
            send(socket, "DEL", "r_dead");
            assertEquals(":1", readReply(in));
            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "SET", "r_in3", "v3");
            assertEquals("+OK", readReply(in));
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));
            // 重写之前日志的最后一次<em>落盘</em>要在 DB 0（SELECT 本身不进日志，光切回去不算）。
            // 少了这一笔，"换完日志要把落点抹回未知"那一判就没有猎物：重写前最后写的是 DB 3、
            // 重写后第一条也写在 DB 3 的话，即使标志是陈的，补出的前缀碰巧还是对的。
            send(socket, "SET", "r_tail", "t");
            assertEquals("+OK", readReply(in));

            java.util.List<String[]> before = new java.util.ArrayList<>();
            new AofPersistence().loadAof(aof.toString(), before::add);
            int churnRecords = countRecordsFor(before, "SET", "r_str");
            long bytesBeforeRewrite = java.nio.file.Files.size(aof);
            shape.put("重写前记录数", String.valueOf(before.size()));
            shape.put("重写前 SET r_str 次数", String.valueOf(churnRecords));
            // 这一格是下面那条"塌成一条"的猎物：流水本来不止一条，"等于 1"才不是白写的判据。
            assertTrue(churnRecords > 50, "前置条件: 61 次 SET 要在日志里留下 61 条流水，实际 "
                    + churnRecords + " 条");

            gen1.getAofPersistence().rewriteAof(aof.toString());

            java.util.List<String[]> records = new java.util.ArrayList<>();
            new AofPersistence().loadAof(aof.toString(), records::add);
            java.util.List<String> selectDbs = new java.util.ArrayList<>();
            java.util.List<String> relativeVerbs = new java.util.ArrayList<>();
            int saddChunks = 0;
            int setMembers = 0;
            int deadMentions = 0;
            int longest = 0;
            for (String[] record : records) {
                String verb = record[0];
                longest = Math.max(longest, record.length);
                if ("SELECT".equals(verb) && record.length > 1) {
                    selectDbs.add(record[1]);
                }
                if ("SADD".equals(verb) && record.length > 1 && "r_set".equals(record[1])) {
                    saddChunks++;
                    setMembers += record.length - 2;
                }
                if (record.length > 1 && "r_dead".equals(record[1])) {
                    deadMentions++;
                }
                if (verb.equals("EXPIRE") || verb.equals("PEXPIRE") || verb.equals("EXPIREAT")
                        || verb.equals("SETEX") || verb.equals("PSETEX")) {
                    relativeVerbs.add(verb);
                }
            }
            shape.put("重写后记录数", String.valueOf(records.size()));
            shape.put("重写后 SET r_str 次数", String.valueOf(countRecordsFor(records, "SET", "r_str")));
            shape.put("SADD r_set", saddChunks + " 片 / " + setMembers + " 个成员");
            shape.put("SELECT 库号", selectDbs.toString());
            shape.put("PEXPIREAT 条数", String.valueOf(countVerb(records, "PEXPIREAT")));
            shape.put("提到 r_dead 的记录", String.valueOf(deadMentions));
            shape.put("最长记录参数数", String.valueOf(longest));
            shape.put("相对时间动词", relativeVerbs.toString());
            shape.put("字节", java.nio.file.Files.size(aof) + "（重写前 " + bytesBeforeRewrite + "）");
            if (java.nio.file.Files.size(aof) >= bytesBeforeRewrite) {
                shapeWrong.put("体积", "重写的全部意义就是把流水塌小，实际 " + shape.get("字节"));
            }
            if (countRecordsFor(records, "SET", "r_str") != 1) {
                shapeWrong.put("塌成一条", "同一个键的 61 次 SET 重写之后该只剩一条，实际 "
                        + shape.get("重写后 SET r_str 次数"));
            }
            if (saddChunks != 3 || setMembers != 130) {
                shapeWrong.put("分片", "130 个成员按上游 64 一片该是 3 片共 130 个，实际 "
                        + shape.get("SADD r_set"));
            }
            if (!selectDbs.equals(java.util.Arrays.asList("0", "3"))) {
                shapeWrong.put("逐库 SELECT", "只该给非空的库补 SELECT，实际 " + selectDbs);
            }
            if (countVerb(records, "PEXPIREAT") != 2) {
                shapeWrong.put("时刻记录", "r_str 与 r_setex 各一条 PEXPIREAT，实际 "
                        + shape.get("PEXPIREAT 条数"));
            }
            if (deadMentions != 0) {
                shapeWrong.put("删掉的键", "r_dead 已经被 DEL，重写之后日志里不该再提它，实际 "
                        + deadMentions + " 条");
            }
            if (longest > 66) {
                shapeWrong.put("单条上限", "一条记录至多 2 + 64 个参数（上游 AOF_REWRITE_ITEMS_PER_CMD），实际最长 "
                        + longest);
            }
            if (!relativeVerbs.isEmpty()) {
                shapeWrong.put("绝对时刻", "重写出来的过期记录只许是 PEXPIREAT，实际 " + relativeVerbs);
            }
            assertTrue(shapeWrong.isEmpty(), "AOF 重写之后日志本身的形状（重写就是把流水塌成当前状态）: " + shape
                    + " 不合格: " + shapeWrong);

            // 换完文件还要能接着写：旧句柄指的是那个已经被换掉的 inode。
            send(socket, "SET", "r_after", "v");
            assertEquals("+OK", readReply(in));
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }
        // 只留 AOF，别拿快照当恢复的功劳
        java.nio.file.Files.deleteIfExists(dir.resolve("dump.rdb"));

        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            expectCell(seen, wrong, socket, in, "GET r_str", "final", "GET", "r_str");
            expectTtlCell(seen, wrong, socket, in, "r_str", 3_600);
            expectCell(seen, wrong, socket, in, "STRLEN r_setex", ":1", "STRLEN", "r_setex");
            expectTtlCell(seen, wrong, socket, in, "r_setex", 3_600);
            expectListCell(seen, wrong, socket, in, "LRANGE r_list",
                    java.util.Arrays.asList("c", "b", "a"), "LRANGE", "r_list", "0", "-1");
            expectCell(seen, wrong, socket, in, "HGET r_hash f1", "v1", "HGET", "r_hash", "f1");
            expectCell(seen, wrong, socket, in, "HGET r_hash f2", "v2", "HGET", "r_hash", "f2");
            expectCell(seen, wrong, socket, in, "SCARD r_set", ":130", "SCARD", "r_set");
            // 分片边界上的三个成员：头片、片与片之间、尾片 —— 少一片就有一头读不回来
            expectCell(seen, wrong, socket, in, "SISMEMBER m0", ":1", "SISMEMBER", "r_set", "m0");
            expectCell(seen, wrong, socket, in, "SISMEMBER m63", ":1", "SISMEMBER", "r_set", "m63");
            expectCell(seen, wrong, socket, in, "SISMEMBER m64", ":1", "SISMEMBER", "r_set", "m64");
            expectCell(seen, wrong, socket, in, "SISMEMBER m129", ":1", "SISMEMBER", "r_set", "m129");
            expectCell(seen, wrong, socket, in, "ZSCORE beta", "2.5", "ZSCORE", "r_zset", "beta");
            expectCell(seen, wrong, socket, in, "ZCARD r_zset", ":2", "ZCARD", "r_zset");
            expectCell(seen, wrong, socket, in, "GET r_crlf", "first\r\nsecond", "GET", "r_crlf");
            expectCell(seen, wrong, socket, in, "EXISTS r_dead", ":0", "EXISTS", "r_dead");
            expectCell(seen, wrong, socket, in, "EXISTS r_in3 在 0 库", ":0", "EXISTS", "r_in3");
            expectCell(seen, wrong, socket, in, "GET r_tail", "t", "GET", "r_tail");
            expectCell(seen, wrong, socket, in, "GET r_after", "v", "GET", "r_after");
            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            expectCell(seen, wrong, socket, in, "GET r_in3 在 3 库", "v3", "GET", "r_in3");
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }
        assertTrue(wrong.isEmpty(), "AOF 重写之后重启，每一格都要带着值回来（缺哪一格就知道那一支导出没做）: "
                + seen + " 不合格: " + wrong);
    }

    /**
     * 流键过一遍 AOF 重写必须整套活着 —— 这一格收的是 13e ③ 登记的那条待办：{@code StoreAccessor}
     * 当年没有 stream 的枚举口，于是 {@code rewriteAof()} 导出的那份"当前状态"<em>唯独少了这一族</em>。
     * 当时零调用方所以没坏过东西，坏东西的是"下一版接上它"：{@code BGREWRITEAOF} 一补、
     * 或按体积自动重写一触发，第一条就是抹掉所有流键。
     * <p>
     * 结构层读重写之后那份日志的字节，钉上游 {@code rewriteStreamObject}（{@code aof.c:1172-1266}）
     * 的四件事：一条记录一条带<em>显式 ID</em> 的 {@code XADD}（{@code :1181-1197}，这一家不套
     * "64 个成员一批"）、空流用 {@code XADD key MAXLEN 0 <last_id> x y} 那一手（{@code :1198-1210}）、
     * 无条件补一条 {@code XSETID}（{@code :1212-1217}，注释原文 "in case of XDEL lastid"）、
     * 逐组 {@code XGROUP CREATE}（{@code :1220-1233}），以及 {@code :1351-1356} 那条排在类型记录
     * 之后的 {@code PEXPIREAT}。
     * <p>
     * 行为层跨进程：删掉快照只留 AOF 重启，逐格读回。比的是<em>改动前那一份读数</em>而不是写死的
     * 字符串 —— 一条记录里字段的先后由存储侧那张 Map 决定，没有语义，钉死它只是给自己埋一次假红。
     * <p>
     * 消费组的 pending 表不在判据里，也不是忘了：上游靠逐条 {@code XCLAIM … JUSTID FORCE}
     * （{@code :1235-1260}，函数体 {@code :1150-1167}）重建它，而这一侧 {@code XCLAIM} 零处理。
     * 那一格记在 CHANGELOG 的未覆盖面，不算通过。
     */
    @Test
    void aofRewriteCarriesStreamKeysAcrossTheSwap() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-aof-rewrite-stream");
        java.nio.file.Path aof = dir.resolve("appendonly.aof");

        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        Map<String, String> shape = new LinkedHashMap<>();
        // 结构层（日志字节里的形状）与行为层（只留 AOF 重启后读回）合进同一张表、一次判红：
        // 分两次 assertTrue 会让 fail-fast 吞掉后面那一层的读数 —— 摘掉 XGROUP 那一支的变异，
        // 就只报得到"组要重建"，报不到"组重建之后还喂得动"。
        Map<String, String> shapeWrong = new LinkedHashMap<>();
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        String rangeBefore;
        String in3Before;
        String wideBefore;
        try (Socket socket = connect(p1)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "XADD", "s_orders", "1-1", "item", "a", "qty", "2");
            assertEquals("1-1", readReply(in), "前置条件: 显式 ID 写得进");
            send(socket, "XADD", "s_orders", "2-5", "item", "b", "qty", "3");
            assertEquals("2-5", readReply(in));
            send(socket, "XADD", "s_orders", "3-9", "item", "c", "qty", "4");
            assertEquals("3-9", readReply(in));
            send(socket, "XGROUP", "CREATE", "s_orders", "g1", "0-0");
            assertEquals("+OK", readReply(in), "前置条件: 组建得起来");
            send(socket, "XREADGROUP", "GROUP", "g1", "c1", "COUNT", "10", "STREAMS", "s_orders", ">");
            // c1 那一问要的是"组的位置被推到 3-9"这件事，不是读数本身：XREADGROUP 交回的是
            // [[key, [[id, [f, v]]]]]，XRANGE 交回的是 [[id, [f, v]]]，两者不同形，所以
            // 下面那份"改动前的读数"要另问一次 XRANGE，才能和重启后的同一问逐字对得上。
            String c1Reply = readReplyDeep(in);
            assertTrue(c1Reply.contains("3-9"), "前置条件: c1 要把三条都领走，实际 " + c1Reply);
            send(socket, "XADD", "s_orders", "4-1", "item", "d", "qty", "5");
            assertEquals("4-1", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g1", "c2", "COUNT", "10", "STREAMS", "s_orders", ">");
            // 前置条件要的是"只领得到 4-1"：领走 3-9 说明位置没推进，一条都领不到说明组坏了。
            // 形状本身（上游那层 [[key, [[id, [field, value]]]]] 的嵌套）交给下面行为层那一问去钉。
            String c2Reply = readReplyDeep(in);
            assertTrue(c2Reply.contains("4-1") && !c2Reply.contains("3-9"),
                    "前置条件: c2 只领得到 4-1，实际 " + c2Reply);
            // 组的读数位置现在停在 4-1，而下面那条 XDEL 会把它顶出的那条记录删掉：于是"位置"与
            // "还活着的最大学 ID"从这一刻起是两件事，正是上游补一条无条件 XSETID 要防的那种排布。
            send(socket, "XDEL", "s_orders", "4-1");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "s_orders", "3600");
            assertEquals(":1", readReply(in), "前置条件: 流键挂得上时刻");

            // 一条被掏空、但键还在的流：上游专门为其写了 MAXLEN 0 那一手
            send(socket, "XADD", "s_events", "5-1", "k", "v");
            assertEquals("5-1", readReply(in));
            send(socket, "XDEL", "s_events", "5-1");
            assertEquals(":1", readReply(in));
            send(socket, "XLEN", "s_events");
            assertEquals(":0", readReply(in), "前置条件: s_events 现在是一条空流");
            send(socket, "EXISTS", "s_events");
            assertEquals(":1", readReply(in), "前置条件: 空流仍然是键");

            // 整个键都不该再出现
            send(socket, "XADD", "s_gone", "1-1", "f", "v");
            assertEquals("1-1", readReply(in));
            send(socket, "DEL", "s_gone");
            assertEquals(":1", readReply(in));

            // 表顶那两段是 uint64 的位模式。最高位一置起来，"有符号地渲染"就会写出负号，
            // 那条 XSETID 到重放时要么被 ID 文法拒掉、要么挪去别处 —— 所以这一族除了"补不补"，
            // 还要钉"怎么写法"。删掉最大那条，逼着表顶只能由 XSETID 带过去（与 s_orders 同形）。
            send(socket, "XADD", "s_wide", "9223372036854775808-7", "f", "w7");
            assertEquals("9223372036854775808-7", readReply(in), "前置条件: uint64 高位的 ID 写得进");
            send(socket, "XADD", "s_wide", "9223372036854775808-9", "f", "w9");
            assertEquals("9223372036854775808-9", readReply(in));
            send(socket, "XDEL", "s_wide", "9223372036854775808-9");
            assertEquals(":1", readReply(in));
            send(socket, "XRANGE", "s_wide", "-", "+");
            wideBefore = readReplyDeep(in);
            assertTrue(wideBefore.contains("w7") && !wideBefore.contains("w9"),
                    "前置条件: s_wide 现在只剩 -7 那一条而表顶停在 -9，实际 " + wideBefore);

            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "s_in3", "1-1", "f", "v3");
            assertEquals("1-1", readReply(in));
            send(socket, "XRANGE", "s_in3", "-", "+");
            in3Before = readReplyDeep(in);
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));
            // 重写之前日志的最后一次落盘要在 DB 0（13e 那一格立的猎物，这里同形复用）
            send(socket, "SET", "s_tail", "t");
            assertEquals("+OK", readReply(in));
            // 改动前那一份读数：与重启后同一问、同一条命令（XRANGE），内容要逐字对得上。
            send(socket, "XRANGE", "s_orders", "-", "+");
            rangeBefore = readReplyDeep(in);
            assertTrue(rangeBefore.contains("3-9") && !rangeBefore.contains("4-1"),
                    "前置条件: 重写之前 s_orders 里活着的是 1-1/2-5/3-9，实际 " + rangeBefore);

            gen1.getAofPersistence().rewriteAof(aof.toString());

            java.util.List<String[]> records = new java.util.ArrayList<>();
            new AofPersistence().loadAof(aof.toString(), records::add);
            java.util.List<String> orderXadds = new java.util.ArrayList<>();
            java.util.List<String> noExplicitId = new java.util.ArrayList<>();
            java.util.List<String> selectDbs = new java.util.ArrayList<>();
            int xsetidOrders = 0, pexpireatOrders = 0, groupCreates = 0, emptyTrick = 0, mentionsGone = 0;
            int xsetIdAt = -1, lastOrderXaddAt = -1, lastOrdersRecordAt = -1, pexpireatAt = -1;
            int firstIn3At = -1, select3At = -1, wideXsetIdAt = -1;
            String wideXsetIdArg = null;
            for (int i = 0; i < records.size(); i++) {
                String[] record = records.get(i);
                String verb = record[0];
                String key = record.length > 1 ? record[1] : "";
                if ("SELECT".equals(verb) && record.length > 1) {
                    selectDbs.add(record[1]);
                    if ("3".equals(record[1])) {
                        select3At = i;
                    }
                }
                if ("s_gone".equals(key)) {
                    mentionsGone++;
                }
                if ("XADD".equals(verb)) {
                    if ("s_orders".equals(key)) {
                        orderXadds.add(java.util.Arrays.toString(record));
                        lastOrderXaddAt = i;
                        lastOrdersRecordAt = i;
                    }
                    if ("s_events".equals(key)) {
                        if (record.length == 7 && "MAXLEN".equals(record[2]) && "0".equals(record[3])
                                && "5-1".equals(record[4])) {
                            emptyTrick++;
                        }
                    }
                    if ("s_in3".equals(key) && firstIn3At < 0) {
                        firstIn3At = i;
                    }
                    if (record.length >= 3 && !"MAXLEN".equals(record[2])
                            && !record[2].matches("\\d+-\\d+")) {
                        noExplicitId.add(java.util.Arrays.toString(record));
                    }
                } else if ("XSETID".equals(verb) && "s_orders".equals(key)) {
                    xsetidOrders++;
                    xsetIdAt = i;
                    lastOrdersRecordAt = i;
                } else if ("XSETID".equals(verb) && "s_wide".equals(key)) {
                    wideXsetIdAt = i;
                    wideXsetIdArg = record.length > 2 ? record[2] : null;
                } else if ("XGROUP".equals(verb) && record.length == 5 && "CREATE".equals(record[1])
                        && "s_orders".equals(record[2]) && "g1".equals(record[3])) {
                    groupCreates++;
                    lastOrdersRecordAt = i;
                } else if ("PEXPIREAT".equals(verb) && "s_orders".equals(key)) {
                    pexpireatOrders++;
                    pexpireatAt = i;
                }
            }
            shape.put("XADD s_orders", orderXadds.size() + " 条: " + orderXadds);
            shape.put("没有显式 ID 的 XADD", noExplicitId.toString());
            shape.put("s_events 的空流那一手", String.valueOf(emptyTrick));
            shape.put("XSETID s_orders", xsetidOrders + " 条（位置 " + xsetIdAt + "，最后一条 XADD 在 "
                    + lastOrderXaddAt + "）");
            shape.put("XSETID s_wide", String.valueOf(wideXsetIdArg) + "（位置 " + wideXsetIdAt + "）");
            shape.put("XGROUP CREATE s_orders g1", String.valueOf(groupCreates));
            shape.put("PEXPIREAT s_orders", pexpireatOrders + " 条（位置 " + pexpireatAt
                    + "，本键最后一条 stream 记录在 " + lastOrdersRecordAt + "）");
            shape.put("提到 s_gone 的记录", String.valueOf(mentionsGone));
            shape.put("SELECT 库号", selectDbs.toString());
            shape.put("s_in3 的位置", firstIn3At + "（SELECT 3 在 " + select3At + "）");
            if (orderXadds.size() != 3) {
                shapeWrong.put("逐条 XADD", "3 条活着的记录该导出 3 条 XADD（上游一家一条命令，不套 64 一批），"
                        + "实际 " + orderXadds);
            }
            if (!noExplicitId.isEmpty()) {
                shapeWrong.put("显式 ID", "导出的每一条 XADD 都要带上原来的那个 ID，否则重放会按当前钟点重排表顶: "
                        + noExplicitId);
            }
            if (emptyTrick != 1) {
                shapeWrong.put("空流那一手", "被 XDEL 掏空的 s_events 仍是一个键，上游用 "
                        + "XADD key MAXLEN 0 <last_id> x y 演这个状态（aof.c:1198-1210），实际命中 "
                        + emptyTrick + " 条");
            }
            if (xsetidOrders != 1 || xsetIdAt <= lastOrderXaddAt) {
                shapeWrong.put("表顶要补 XSETID", "s_orders 的表顶停在 4-1 而活着的最大学 ID 是 3-9，"
                        + "最后一条 XADD 之后必须无条件补一条 XSETID（aof.c:1212-1217），实际 "
                        + shape.get("XSETID s_orders"));
            }
            if (!"9223372036854775808-9".equals(wideXsetIdArg)) {
                shapeWrong.put("表顶的无符号写法", "s_wide 的表顶是 9223372036854775808-9，那两段按 uint64 的"
                        + "位模式渲染（上游 streamReplyID 用的就是 %PRIu64）；写成有符号就是负号开头的一串，"
                        + "重放时 ID 文法不认，实际 " + shape.get("XSETID s_wide"));
            }
            if (groupCreates != 1) {
                shapeWrong.put("组要重建", "每组一条 XGROUP CREATE key g <读数位置>（aof.c:1227-1233），实际 "
                        + groupCreates + " 条");
            }
            if (pexpireatOrders != 1 || pexpireatAt <= lastOrdersRecordAt) {
                shapeWrong.put("时刻排在类型记录之后", "流键的 PEXPIREAT 恰一条，且要排在它自己的"
                        + " XADD / XSETID / XGROUP 之后（aof.c:1351-1356），实际 "
                        + shape.get("PEXPIREAT s_orders"));
            }
            if (mentionsGone != 0) {
                shapeWrong.put("删掉的键", "s_gone 已经 DEL，重写之后日志里不该再提它，实际 " + mentionsGone + " 条");
            }
            if (!selectDbs.equals(java.util.Arrays.asList("0", "3"))) {
                shapeWrong.put("逐库 SELECT", "只该给非空的库补 SELECT，实际 " + selectDbs);
            }
            if (firstIn3At < 0 || select3At < 0 || firstIn3At < select3At) {
                shapeWrong.put("流键排在所属 SELECT 之后", "DB 3 里只有流键时，那条 XADD 必须排在 SELECT 3 之后，"
                        + "实际 " + shape.get("s_in3 的位置"));
            }
            for (Map.Entry<String, String> cell : shapeWrong.entrySet()) {
                wrong.put("结构层 " + cell.getKey(), cell.getValue());
            }

            send(socket, "SET", "s_after", "v");
            assertEquals("+OK", readReply(in));
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }
        // 只留 AOF，别拿快照当恢复的功劳
        java.nio.file.Files.deleteIfExists(dir.resolve("dump.rdb"));

        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "XRANGE", "s_orders", "-", "+");
            String rangeAfter = readReplyDeep(in);
            seen.put("XRANGE s_orders", rangeAfter);
            if (!rangeBefore.equals(rangeAfter)) {
                wrong.put("XRANGE s_orders", "重写前 " + rangeBefore + " → 重启后 " + rangeAfter);
            }
            // TYPE 交回的是状态行（上游 db.c:838 用的就是 addReplyStatus，文档里那句"Bulk string
            // reply"不是 5.0.14 的线上形状），所以这里带 "+"。
            expectCell(seen, wrong, socket, in, "TYPE s_orders", "+stream", "TYPE", "s_orders");
            expectTtlCell(seen, wrong, socket, in, "s_orders", 3_600);
            // 表顶：4-1 那条已经 XDEL 掉，只有 XSETID 把表顶留在 4-1 时，4-0 才被单调性闸挡下
            send(socket, "XADD", "s_orders", "4-0", "item", "x", "qty", "9");
            String rejected = readReply(in);
            seen.put("XADD s_orders 4-0", rejected);
            if (!rejected.startsWith("-ERR")) {
                wrong.put("表顶活过重写", "4-1 已被 XDEL，但表顶该停在 4-1，比它小的 4-0 该被单调性闸挡下；"
                        + "实际 " + rejected);
            }
            // 组的读数位置：c3 从这里起只该领到 4-1 之后的条目，而 4-1 已被删 → 空。
            // 这一问必须排在下面那条 XADD 4-2 <em>之前</em>：4-2 一进去，"空"就成了改动后的形状，
            // 而"什么都读不到"本身也是一条会被"组压根没重建"复现的答 —— 所以它下面还压着一问猎物。
            send(socket, "XREADGROUP", "GROUP", "g1", "c3", "COUNT", "10", "STREAMS", "s_orders", ">");
            String afterGroup = readReplyDeep(in);
            seen.put("XREADGROUP c3 >", afterGroup);
            // 两问一起判：报 -ERR = 组压根没重建（只问"领没领到条目"会把"组不在"读成"位置卡住了"，
            // 那是一格空过的判据），领到条目 = 位置真的没卡。
            if (afterGroup.startsWith("-")
                    || java.util.regex.Pattern.compile("\\d+-\\d+").matcher(afterGroup).find()) {
                wrong.put("组的读数位置", "组该重建在 4-1（1-1/2-5/3-9 早已投过、4-1 已删）：报 -ERR 是组没重建，"
                        + "领到条目是位置没卡住；新消费者 c3 两条都不该有，实际 " + afterGroup);
            }
            expectCell(seen, wrong, socket, in, "XADD s_orders 4-2（上面那一问的阳性对照）", "4-2",
                    "XADD", "s_orders", "4-2", "item", "e", "qty", "6");
            send(socket, "XREADGROUP", "GROUP", "g1", "c3", "COUNT", "10", "STREAMS", "s_orders", ">");
            String groupFeeds = readReplyDeep(in);
            seen.put("XREADGROUP c3 >（再问）", groupFeeds);
            if (!groupFeeds.contains("4-2")) {
                wrong.put("组重建之后还喂得动", "上一问的『一条都不领到』需要一个猎物：4-2 一进流，同一个 c3 再问一次 > "
                        + "就该领到它，否则『领不到』是组压根没重建，而不是位置卡在 4-1，实际 " + groupFeeds);
            }
            expectCell(seen, wrong, socket, in, "EXISTS s_events", ":1", "EXISTS", "s_events");
            expectCell(seen, wrong, socket, in, "TYPE s_events", "+stream", "TYPE", "s_events");
            expectCell(seen, wrong, socket, in, "XLEN s_events", ":0", "XLEN", "s_events");
            send(socket, "XADD", "s_events", "5-0", "k", "v");
            String eventsRejected = readReply(in);
            seen.put("XADD s_events 5-0", eventsRejected);
            if (!eventsRejected.startsWith("-ERR")) {
                wrong.put("空流的表顶", "空流也要把表顶留在 5-1，否则 5-0 还能塞进去（重放时 4-1 那一幕会重来一遍），实际 "
                        + eventsRejected);
            }
            expectCell(seen, wrong, socket, in, "XADD s_events 6-0（阳性对照）", "6-0",
                    "XADD", "s_events", "6-0", "k", "v2");
            // 与"表顶活过重写"同形，但这一问要的是<em>写法</em>：结构层那条 XSETID 只要带负号，
            // 重放就没把表顶推到 -9，于是 -8 塞得进来。
            send(socket, "XADD", "s_wide", "9223372036854775808-8", "f", "w8");
            String wideRejected = readReply(in);
            seen.put("XADD s_wide 高位-8", wideRejected);
            if (!wideRejected.startsWith("-ERR")) {
                wrong.put("高位的表顶活过重写", "活着的是 9223372036854775808-7 而表顶该停在 ...08-9，"
                        + "比它小的 ...08-8 该被单调性闸挡下；实际 " + wideRejected);
            }
            send(socket, "XRANGE", "s_wide", "-", "+");
            String wideStill = readReplyDeep(in);
            seen.put("XRANGE s_wide（上面那一问的阳性对照）", wideStill);
            if (!wideBefore.equals(wideStill)) {
                wrong.put("XRANGE s_wide（上面那一问的阳性对照）", "被拒的那一问不能顺手改掉内容：重写前 "
                        + wideBefore + " → 重启后 " + wideStill + "（两者不等就说明 s_wide 整个没活过重写，"
                        + "上面那句『-8 被挡下』就成了空跑）");
            }
            expectCell(seen, wrong, socket, in, "EXISTS s_gone", ":0", "EXISTS", "s_gone");
            expectCell(seen, wrong, socket, in, "GET s_tail", "t", "GET", "s_tail");
            expectCell(seen, wrong, socket, in, "GET s_after", "v", "GET", "s_after");
            send(socket, "SELECT", "3");
            String select3 = readReply(in);
            seen.put("SELECT 3", select3);
            if (!"+OK".equals(select3)) {
                wrong.put("SELECT 3", "切库要答 +OK（读不到它就说明上面的字节没吃干净），实际 " + select3);
            }
            send(socket, "XRANGE", "s_in3", "-", "+");
            String in3After = readReplyDeep(in);
            seen.put("XRANGE s_in3 在 3 库", in3After);
            if (!in3Before.equals(in3After)) {
                wrong.put("XRANGE s_in3 在 3 库", "重写前 " + in3Before + " → 重启后 " + in3After);
            }
            send(socket, "SELECT", "0");
            String backTo0 = readReply(in);
            seen.put("SELECT 0", backTo0);
            if (!"+OK".equals(backTo0)) {
                wrong.put("SELECT 0", "切回 0 库要答 +OK，实际 " + backTo0);
            }
            expectCell(seen, wrong, socket, in, "EXISTS s_in3 回到 0 库", ":0", "EXISTS", "s_in3");
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }
        assertTrue(wrong.isEmpty(), "流键过一遍 AOF 重写：结构层（重写后那份日志里的形状）与行为层（只留 AOF"
                + " 重启之后逐格读回）要一起合格。日志形状 " + shape + "；读回的格子 " + seen
                + "；不合格: " + wrong + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    /**
     * 没接 {@code StoreAccessor} 的实例导不出任何东西 —— 那一支 rewriteAof 必须拒绝，
     * 而不是写一份<em>空</em>日志再把真的有内容的那份换掉（这一格改动前的实际形状：
     * 注释自陈"实际实现需要依赖 StoreAccessor"，然后把 {@code appendonly.aof} 删了）。
     */
    /**
     * Stream 一族进得了 RDB 快照。
     * <p>
     * 上游 5.0.14 给这一族留了专门的一种类型字节（{@code rdb.h:93} 的
     * {@code RDB_TYPE_STREAM_LISTPACKS = 15}，写侧在 {@code rdbSaveObjectType} 的 {@code rdb.c:656}，
     * 读侧在 {@code rdb.c:1698}），负载按 listpack 分三摊：条目、组、每组里每个消费者手上未确认的条目。
     * 我们的快照从来不是 Redis 能 load 的那份字节（魔数是 {@code ZCHRDB}，见 {@link RdbPersistence}），
     * 所以这里钉的是"这一族进不进得进快照、进来之后带着什么"，不是 listpack 的排布。
     * <p>
     * 与 {@code aofRewriteCarriesStreamKeysAcrossTheSwap}（13g）是两条独立的腿：那一条走
     * "重写 → 只留 AOF 重启"，这一条走 "SAVE → 只留快照重启"。两条各配一份量具，
     * 因为<b>任一条单独成立都不蕴含另一条</b>（导出侧 13g 之前只有 AOF 有流键，快照那侧一直没有人）。
     */
    @Test
    void saveSnapshotsStreamKeys() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-rdb-stream");
        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        String liveBefore;
        String wideBefore;
        String in3Before;
        try (Socket socket = connect(p1)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 带组的流：读数位置推到 3-9，4-1 投给 c2 之后又被 XDEL —— 于是"表顶"与
            // "活着的最大学 ID"从这一刻起是两件事，快照必须把前者单独带过去。
            send(socket, "XADD", "s_live", "1-1", "item", "a");
            assertEquals("1-1", readReply(in), "前置条件: 显式 ID 写得进");
            send(socket, "XADD", "s_live", "2-5", "item", "b");
            assertEquals("2-5", readReply(in));
            send(socket, "XADD", "s_live", "3-9", "item", "c");
            assertEquals("3-9", readReply(in));
            send(socket, "XGROUP", "CREATE", "s_live", "g1", "0-0");
            assertEquals("+OK", readReply(in), "前置条件: 组建得起来");
            send(socket, "XREADGROUP", "GROUP", "g1", "c1", "COUNT", "10", "STREAMS", "s_live", ">");
            String c1 = readReplyDeep(in);
            assertTrue(c1.contains("3-9"), "前置条件: c1 要把三条都领走，实际 " + c1);
            send(socket, "XADD", "s_live", "4-1", "item", "d");
            assertEquals("4-1", readReply(in));
            send(socket, "XREADGROUP", "GROUP", "g1", "c2", "COUNT", "10", "STREAMS", "s_live", ">");
            String c2 = readReplyDeep(in);
            assertTrue(c2.contains("4-1") && !c2.contains("3-9"),
                    "前置条件: c2 只领得到 4-1，实际 " + c2);
            send(socket, "XDEL", "s_live", "4-1");
            assertEquals(":1", readReply(in));
            send(socket, "EXPIRE", "s_live", "3600");
            assertEquals(":1", readReply(in), "前置条件: 流键挂得上时刻");
            send(socket, "XRANGE", "s_live", "-", "+");
            liveBefore = readReplyDeep(in);
            assertTrue(liveBefore.contains("3-9") && !liveBefore.contains("4-1"),
                    "前置条件: 活着的是 1-1/2-5/3-9，实际 " + liveBefore);

            // 空流：键在、表顶在、记录一条都不在
            send(socket, "XADD", "s_empty", "5-1", "k", "v");
            assertEquals("5-1", readReply(in));
            send(socket, "XDEL", "s_empty", "5-1");
            assertEquals(":1", readReply(in));
            send(socket, "XLEN", "s_empty");
            assertEquals(":0", readReply(in), "前置条件: s_empty 现在是一条空流");
            send(socket, "EXISTS", "s_empty");
            assertEquals(":1", readReply(in), "前置条件: 空流仍然是键");

            // 表顶跨过 2^63：快照里存的应当是两段位模式，而不是某个进制下的文本
            send(socket, "XADD", "s_wide", "9223372036854775808-7", "f", "w7");
            assertEquals("9223372036854775808-7", readReply(in), "前置条件: uint64 高位的 ID 写得进");
            send(socket, "XADD", "s_wide", "9223372036854775808-9", "f", "w9");
            assertEquals("9223372036854775808-9", readReply(in));
            send(socket, "XDEL", "s_wide", "9223372036854775808-9");
            assertEquals(":1", readReply(in));
            send(socket, "XRANGE", "s_wide", "-", "+");
            wideBefore = readReplyDeep(in);
            assertTrue(wideBefore.contains("w7") && !wideBefore.contains("w9"),
                    "前置条件: s_wide 只剩 -7 而表顶停在 -9，实际 " + wideBefore);

            // 快照根本不该带上的两枚：整键被删、停机期间到点
            send(socket, "XADD", "s_gone", "1-1", "f", "v");
            assertEquals("1-1", readReply(in));
            send(socket, "DEL", "s_gone");
            assertEquals(":1", readReply(in));
            send(socket, "XADD", "s_dead", "1-1", "f", "v");
            assertEquals("1-1", readReply(in));
            // 这一枚要的是"进了文件、加载那一判再把它抹掉"，所以 SAVE 时它必须<em>还活着</em>；
            // 而停机那一段必须跨过它的时刻。上一格（saveSnapshotsEveryDatabaseAndTheirTtls）
            // 那枚 dead7 靠的是"重启本来就慢于一秒"，那是没量过的巧合；这里把睡着那一段
            // 显式压在两次运行之间，判据由 2 秒的时刻与 2.1 秒的空档共同钉住。
            send(socket, "EXPIRE", "s_dead", "2");
            assertEquals(":1", readReply(in), "前置条件: s_dead 挂得上时刻");
            send(socket, "EXISTS", "s_dead");
            assertEquals(":1", readReply(in), "前置条件: SAVE 之前 s_dead 还活着，它必须真的进得了文件");

            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "XADD", "s_in3", "1-1", "f", "v3");
            assertEquals("1-1", readReply(in));
            send(socket, "XRANGE", "s_in3", "-", "+");
            in3Before = readReplyDeep(in);
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));

            send(socket, "SAVE");
            assertEquals("+OK", readReply(in), "前置条件: 配了 dataDir，SAVE 要如实回 +OK");
            send(socket, "LASTSAVE");
            String lastSave = readReply(in);
            assertTrue(lastSave.startsWith(":") && Long.parseLong(lastSave.substring(1)) > 0,
                    "LASTSAVE 要报出真实的快照时刻，实际: " + lastSave);
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }
        // 停机那一段要跨过 s_dead 的时刻：显式睡过去，而不是赌"重启够慢"
        Thread.sleep(2_100);
        // 只留快照：别拿 AOF 那一侧的功劳当这一族的证据
        java.nio.file.Files.deleteIfExists(dir.resolve("appendonly.aof"));
        assertTrue(java.nio.file.Files.size(dir.resolve("dump.rdb")) > 0,
                "SAVE 报了 +OK，磁盘上就必须有文件");

        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            expectCell(seen, wrong, socket, in, "DBSIZE", ":3", "DBSIZE");
            send(socket, "XRANGE", "s_live", "-", "+");
            String liveAfter = readReplyDeep(in);
            seen.put("XRANGE s_live", liveAfter);
            if (!liveBefore.equals(liveAfter)) {
                wrong.put("XRANGE s_live", "快照前 " + liveBefore + " → 重启后 " + liveAfter);
            }
            expectCell(seen, wrong, socket, in, "TYPE s_live", "+stream", "TYPE", "s_live");
            expectCell(seen, wrong, socket, in, "XLEN s_live", ":3", "XLEN", "s_live");
            expectTtlCell(seen, wrong, socket, in, "s_live", 3_600);
            // 表顶与"活着的最大学 ID"是两件事：4-1 已被 XDEL，只有快照把表顶单独带过来，4-0 才被挡下
            send(socket, "XADD", "s_live", "4-0", "item", "x");
            String liveRejected = readReply(in);
            seen.put("XADD s_live 4-0", liveRejected);
            if (!liveRejected.startsWith("-ERR")) {
                wrong.put("表顶活过快照", "4-1 已被 XDEL 而表顶该停在 4-1，比它小的 4-0 该被单调性闸挡下；"
                        + "实际 " + liveRejected);
            }
            // 组的读数位置：新消费者 c3 只该领到 4-1 之后的条目，而 4-1 已删 ⇒ 空。
            // 这一问必须排在下面那条 XADD 4-2 之前，且下面还压着一问猎物。
            send(socket, "XREADGROUP", "GROUP", "g1", "c3", "COUNT", "10", "STREAMS", "s_live", ">");
            String afterGroup = readReplyDeep(in);
            seen.put("XREADGROUP c3 >", afterGroup);
            // 两问一起判：报 -ERR = 组压根没重建（只问"领没领到条目"会把"组不在"读成"位置卡住了"，
            // 那是一格空过的判据），领到条目 = 位置真的没卡。
            if (afterGroup.startsWith("-")
                    || java.util.regex.Pattern.compile("\\d+-\\d+").matcher(afterGroup).find()) {
                wrong.put("组的读数位置", "组该重建在 4-1（1-1/2-5/3-9 早已投过、4-1 已删）：报 -ERR 是组没重建，"
                        + "领到条目是位置没卡住；新消费者 c3 两条都不该有，实际 " + afterGroup);
            }
            expectCell(seen, wrong, socket, in, "XADD s_live 4-2（上面那一问的阳性对照）", "4-2",
                    "XADD", "s_live", "4-2", "item", "e");
            send(socket, "XREADGROUP", "GROUP", "g1", "c3", "COUNT", "10", "STREAMS", "s_live", ">");
            String groupFeeds = readReplyDeep(in);
            seen.put("XREADGROUP c3 >（再问）", groupFeeds);
            if (!groupFeeds.contains("4-2")) {
                wrong.put("组重建之后还喂得动", "上一问的『一条都不领到』需要一个猎物：4-2 一进流，同一个 c3 再问一次 > "
                        + "就该领到它，否则『领不到』是组压根没重建，而不是位置卡在 4-1，实际 " + groupFeeds);
            }
            expectCell(seen, wrong, socket, in, "EXISTS s_empty", ":1", "EXISTS", "s_empty");
            expectCell(seen, wrong, socket, in, "TYPE s_empty", "+stream", "TYPE", "s_empty");
            expectCell(seen, wrong, socket, in, "XLEN s_empty", ":0", "XLEN", "s_empty");
            send(socket, "XADD", "s_empty", "5-0", "k", "v");
            String emptyRejected = readReply(in);
            seen.put("XADD s_empty 5-0", emptyRejected);
            if (!emptyRejected.startsWith("-ERR")) {
                wrong.put("空流的表顶", "空流也要把表顶留在 5-1，否则 5-0 还能塞进去，实际 " + emptyRejected);
            }
            expectCell(seen, wrong, socket, in, "XADD s_empty 5-2（阳性对照）", "5-2",
                    "XADD", "s_empty", "5-2", "k", "v2");
            // 与 s_live 同形，但这一问要的是位模式：表顶跨过 2^63 时那两段不能按十进制文本存
            send(socket, "XADD", "s_wide", "9223372036854775808-8", "f", "w8");
            String wideRejected = readReply(in);
            seen.put("XADD s_wide 高位-8", wideRejected);
            if (!wideRejected.startsWith("-ERR")) {
                wrong.put("高位的表顶活过快照", "活着的是 9223372036854775808-7 而表顶该停在 ...08-9，"
                        + "比它小的 ...08-8 该被挡下；实际 " + wideRejected);
            }
            send(socket, "XRANGE", "s_wide", "-", "+");
            String wideStill = readReplyDeep(in);
            seen.put("XRANGE s_wide（上面那一问的阳性对照）", wideStill);
            if (!wideBefore.equals(wideStill)) {
                wrong.put("XRANGE s_wide（上面那一问的阳性对照）", "被拒的那一问不能顺手改掉内容：快照前 "
                        + wideBefore + " → 重启后 " + wideStill);
            }
            expectCell(seen, wrong, socket, in, "EXISTS s_gone", ":0", "EXISTS", "s_gone");
            expectCell(seen, wrong, socket, in, "EXISTS s_dead", ":0", "EXISTS", "s_dead");
            send(socket, "SELECT", "3");
            String select3 = readReply(in);
            seen.put("SELECT 3", select3);
            if (!"+OK".equals(select3)) {
                wrong.put("SELECT 3", "切库要答 +OK（读不到它就说明上面的字节没吃干净），实际 " + select3);
            }
            send(socket, "XRANGE", "s_in3", "-", "+");
            String in3After = readReplyDeep(in);
            seen.put("XRANGE s_in3 在 3 库", in3After);
            if (!in3Before.equals(in3After)) {
                wrong.put("XRANGE s_in3 在 3 库", "快照前 " + in3Before + " → 重启后 " + in3After);
            }
            send(socket, "SELECT", "0");
            String backTo0 = readReply(in);
            seen.put("SELECT 0", backTo0);
            if (!"+OK".equals(backTo0)) {
                wrong.put("SELECT 0", "切回 0 库要答 +OK，实际 " + backTo0);
            }
            expectCell(seen, wrong, socket, in, "EXISTS s_in3 回到 0 库", ":0", "EXISTS", "s_in3");
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }
        assertTrue(wrong.isEmpty(), "流键过一遍快照：只留 dump.rdb 重启之后要逐格读回（一格都不在 = 这一族"
                + "根本没进快照；值在而表顶/组/时刻不在 = 负载只写了一半）。读回的格子 " + seen
                + "；不合格: " + wrong + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    /**
     * {@code BGREWRITEAOF} 得真的把重写跑起来 —— 13g 修好的那一份导出，此前只有 Java 能调。
     * <p>
     * 上游 {@code bgrewriteaofCommand}（{@code aof.c:1629-1640}）三支：正在重写 →
     * {@code -ERR Background append only file rewriting already in progress}（{@code :1631}）；
     * 有 BGSAVE 在跑 → 排到它后面（{@code :1633-1634}）；否则起子进程并回
     * {@code +Background append only file rewriting started}（{@code :1636}）。我们不 fork
     * （"导出 + 换文件 + 重开追加句柄"整段都在追加那把锁里，见
     * {@link com.zifang.z.cache.core.persistence.AofPersistence#rewriteAof}），所以没有
     * "排在 BGSAVE 之后"那一支；而"导不出来"是受理<em>之后</em>才发现的，与上游子进程失败同形 ——
     * 答复已经交出去了，只能进日志。
     * </p>
     */
    @Test
    void bgrewriteaofStartsTheRewriteAndTheLogStaysAppendable() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-bgrewriteaof");
        java.nio.file.Path aof = dir.resolve("appendonly.aof");
        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        try (Socket socket = connect(p1)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            // 流水账：同一个键写三笔，只有最新那一笔才是"当前状态"。重写之后的日志里只该留那一笔，
            // 而它必须还带着时刻（SETEX 在落盘之前就换成 SET + PEXPIREAT，见 13d 那一格）。
            send(socket, "SET", "k", "v1");
            assertEquals("+OK", readReply(in));
            send(socket, "SET", "k", "v2");
            assertEquals("+OK", readReply(in));
            send(socket, "SETEX", "k", "3600", "v3");
            assertEquals("+OK", readReply(in), "前置条件: 最后一笔要带着时刻");
            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "SET", "k3", "v3db");
            assertEquals("+OK", readReply(in));
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));

            int junkBefore = countRecordsFor(readAof(aof), "k");
            assertTrue(junkBefore >= 3, "前置条件: 重写之前日志里得有三笔流水，实际 " + junkBefore);

            send(socket, "BGREWRITEAOF");
            String started = readReply(in);
            seen.put("BGREWRITEAOF 的答复", started);
            if (!"+Background append only file rewriting started".equals(started)) {
                wrong.put("BGREWRITEAOF 的答复", "要的是上游 aof.c:1636 那句原文，实际 " + started);
            }
            // 等的是"日志整个换过一份"这一因（三笔收成一笔），不是睡一秒赌它跑完了。
            // 换文件那一瞬间可能读到半截，所以 IOException 只记账、下一轮再读；期限内没收成
            // 就是这一格红，不许静默往下走。
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            java.util.List<String[]> rewritten = null;
            int junkAfter = junkBefore;
            String readError = "";
            for (; ; ) {
                try {
                    rewritten = readAof(aof);
                    junkAfter = countRecordsFor(rewritten, "k");
                    readError = "";
                    if (junkAfter <= 1) {
                        break;
                    }
                } catch (IOException e) {
                    readError = String.valueOf(e);
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
                Thread.sleep(5);
            }
            expectTextCell(seen, wrong, "结构层 k 只剩一笔", String.valueOf(junkAfter), "1",
                    "重写之后同一键只该留下当前状态那一笔（三笔都在 = 根本没重写；0 笔 = 把键导丢了）；"
                            + "读日志最后一次失败: " + readError);
            java.util.List<String> selectDbs = new java.util.ArrayList<>();
            for (String[] record : rewritten == null ? java.util.Collections.<String[]>emptyList() : rewritten) {
                if ("SELECT".equals(record[0]) && record.length > 1) {
                    selectDbs.add(record[1]);
                }
            }
            expectTextCell(seen, wrong, "结构层 逐库 SELECT", selectDbs.toString(), "[0, 3]",
                    "导出的是一份带库号的最小命令集，DB 3 那一族要排在它自己的 SELECT 之后");

            // 换完文件必须重开追加句柄：留着 writer == null，之后每条写命令都会在判空里静默丢掉。
            send(socket, "SET", "k2", "after-rewrite");
            String appended = readReply(in);
            seen.put("重写之后仍然接得上追加", appended);
            if (!"+OK".equals(appended)) {
                wrong.put("重写之后仍然接得上追加", "换文件之后写侧被堵住或句柄没接回来，实际 " + appended);
            }

            // "已经有人在重写"那一支（上游 aof.c:1630-1631 的 -ERR）。这一格不能靠睡：重写排在
            // 追加用的那把锁上，把锁攥在手里，排队的那一份就走不完，标志就一直是 true —— 上面
            // 那句 +...started 正是"标志已经抢到"的因证。锁一放开它就跑完，不影响后面的格子。
            AofPersistence aofHandle = gen1.getAofPersistence();
            assertNotNull(aofHandle, "前置条件: 这一台得真的起了 AOF，才谈得上\"正在重写\"");
            // 锁内只许发 BGREWRITEAOF：它不碰这把锁。发写命令的话，命令线程会来抢我们手里这把，
            // 我们又在等它的答复 —— 两边都动不了。
            java.util.List<String> busyReplies = new java.util.ArrayList<>();
            String notBusy = "";
            synchronized (aofHandle) {
                for (int i = 0; i < 3 && busyReplies.size() < 2; i++) {
                    send(socket, "PING");
                    String ping = readReply(in);
                    if (!"+PONG".equals(ping)) {
                        notBusy = "第 " + (i + 1) + " 次 PING 没走通: " + ping;
                        break;
                    }
                    send(socket, "BGREWRITEAOF");
                    busyReplies.add(readReply(in));
                }
            }
            String first = busyReplies.isEmpty() ? "(一问都没答)" : busyReplies.get(0);
            String second = busyReplies.size() < 2 ? "(只答了一问)" : busyReplies.get(1);
            // 两问各钉一格：原先是一条 if/else-if 链，第一问一坏第二问就不检查了 —— 那正是
            // "标志抢晚了"与"标志没还"两支变异落在同一个格名上的原因（量具据此分不开两种坏法）。
            expectTextCell(seen, wrong, "锁内第一问仍受理", first,
                    "+Background append only file rewriting started",
                    "锁攥着不放时，第一问还该受理（上一问抢到的标志到重写跑完才还）；" + notBusy);
            expectTextCell(seen, wrong, "锁内第二问撞闸", second,
                    "-ERR Background append only file rewriting already in progress",
                    "第二问要的是上游 aof.c:1631 那句原文：受理了第二问 = 标志没在<em>入队之前</em>抢，"
                            + "两份重写会排进同一个线程池，后一份会拿前一份换过的日志再导一遍；" + notBusy);
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }
        // 快照那份不留下：这一族只认重写之后的 AOF。
        java.nio.file.Files.deleteIfExists(dir.resolve("dump.rdb"));
        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            expectCell(seen, wrong, socket, in, "GET k", "v3", "GET", "k");
            expectTtlCell(seen, wrong, socket, in, "k", 3_600);
            expectCell(seen, wrong, socket, in, "GET k2", "after-rewrite", "GET", "k2");
            expectCell(seen, wrong, socket, in, "DBSIZE", ":2", "DBSIZE");
            expectCell(seen, wrong, socket, in, "SELECT 3", "+OK", "SELECT", "3");
            expectCell(seen, wrong, socket, in, "GET k3 在 3 库", "v3db", "GET", "k3");
            expectCell(seen, wrong, socket, in, "SELECT 0", "+OK", "SELECT", "0");
            expectCell(seen, wrong, socket, in, "EXISTS k3 回到 0 库", ":0", "EXISTS", "k3");
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }
        // 没配 dataDir 的那一台：AOF 根本没起，不能谎报"已经开始了"。
        int p3 = freePort();
        RedisServer bare = new RedisServer("127.0.0.1", p3, 0);
        Thread t3 = startAndWait(bare, p3);
        try (Socket socket = connect(p3)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "BGREWRITEAOF");
            String refused = readReply(in);
            seen.put("没配 dataDir 时如实拒绝", refused);
            if (!refused.startsWith("-ERR") || !refused.contains("no data directory")) {
                wrong.put("没配 dataDir 时如实拒绝", "要的是点名缺什么的 -ERR，实际 " + refused);
            }
        } finally {
            bare.stop();
            t3.join(DEADLINE_MS);
        }
        assertTrue(wrong.isEmpty(), "BGREWRITEAOF 受理之后日志要真的换过一份，且换完还接得上追加、"
                + "只留这一份 AOF 重启要读得回来，逐格: " + seen
                + "；不合格: " + wrong + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    /**
     * 13j：{@code INFO} 的 {@code # Persistence} 段里那几个 AOF 数，必须量得出盘上真实的字节数。
     * <p>
     * 上游同段从 {@code server.c:3358} 的 {@code "# Persistence\\r\\n"} 起：{@code aof_enabled} 由
     * {@code server.aof_state != AOF_OFF} 喂（{@code :3367} 声明、{@code :3383} 取值），
     * {@code aof_rewrite_in_progress} 由 {@code server.aof_child_pid != -1} 喂（{@code :3368} / {@code :3384}），
     * 而 {@code aof_current_size} 与 {@code aof_base_size} 只在 AOF 开着时才出现
     * （{@code :3394} 的 {@code if (server.aof_state != AOF_OFF)}，字段 {@code :3396-3397}，
     * 取值 {@code :3403-3404}）。这两个数的写入点是这一格的要害：
     * <ul>
     *   <li>{@code aof_current_size} 每次写完自增（{@code aof.c:466}、{@code :480}），换过文件之后
     *       按 stat <em>重取</em>而不是接着加 —— {@code aofUpdateCurrentSize}（{@code aof.c:1653-1665}，
     *       注释原文 "normally the size is updated just adding the write length"），调用点在载入收尾
     *       （{@code :865}）与重写收尾（{@code :1772}）。</li>
     *   <li>{@code aof_rewrite_base_size} 是"接手时或上次重写后的底座"（{@code server.h:1079}），
     *       全上游只有三处赋值：初值 0（{@code server.c:1594}）、载入收尾（{@code aof.c:866}）、
     *       重写收尾（{@code aof.c:1773}）；它<em>不跟着写命令涨</em>，因为它是自动重写算增幅的分母
     *       （{@code server.c:1308-1310}）。</li>
     * </ul>
     * 判据按这四个数各自"该等于什么"来问，而不是"字段在不在"：当前大小对磁盘真实长度、底座对
     * 接手那一刻的长度、重写前后各一站。缺的字段也照实在 {@code CHANGELOG} 里点名（{@code loading}
     * 一族、{@code aof_rewrite_scheduled}、{@code aof_last_bgrewrite_status}、{@code *_cow_size} ——
     * 前三个我们<em>没有那台机器</em>，后两个没有 fork 就没有可报的量），先把有尺的四个钉住。
     */
    @Test
    void infoReportsTheRealAofSizes() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-info-aof-sizes");
        java.nio.file.Path aof = dir.resolve("appendonly.aof");
        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        String rewriteWait = "";
        long sizeAtStart;
        long sizeAfterRewrite;
        try (Socket socket = connect(p1)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 接手那一刻：日志还没被写过，三个数（盘上长度、current、base）只能是同一个数。
            sizeAtStart = java.nio.file.Files.size(aof);
            String atStart = persistenceSection(socket, in);
            expectTextCell(seen, wrong, "段头", firstInfoLine(atStart), "# Persistence",
                    "上游同段以 \"# Persistence\\r\\n\" 起头（server.c:3358），实际整段的第一行见读数");
            expectTextCell(seen, wrong, "aof_enabled", infoField(atStart, "aof_enabled"), "1",
                    "这一台配了 dataDir，AOF 真开着（上游 :3383 判的就是 aof_state != AOF_OFF）");
            expectTextCell(seen, wrong, "接手时 current 等于盘上长度",
                    infoField(atStart, "aof_current_size"), String.valueOf(sizeAtStart),
                    "aof.c:466/:480 那两次自增的每一寸都该在这里");
            expectTextCell(seen, wrong, "接手时 base 等于同一份长度",
                    infoField(atStart, "aof_base_size"), String.valueOf(sizeAtStart),
                    "aof.c:866 载入收尾把底座对齐到接手时的大小");
            expectTextCell(seen, wrong, "没人重写时 in_progress 为 0",
                    infoField(atStart, "aof_rewrite_in_progress"), "0",
                    "上游喂的是 aof_child_pid != -1（:3384）；我们只有 rewriting 那一个标志");

            send(socket, "SET", "k", "v1");
            assertEquals("+OK", readReply(in));
            send(socket, "SETEX", "k", "3600", "v2");
            assertEquals("+OK", readReply(in));
            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            send(socket, "SET", "k3", "v3db");
            assertEquals("+OK", readReply(in));
            send(socket, "SELECT", "0");
            assertEquals("+OK", readReply(in));

            long sizeAfterWrites = java.nio.file.Files.size(aof);
            String afterWrites = persistenceSection(socket, in);
            expectTextCell(seen, wrong, "写了几笔之后 current 等于盘上长度",
                    infoField(afterWrites, "aof_current_size"), String.valueOf(sizeAfterWrites),
                    "这一格是下面两格的阳性对照：写侧涨的时候 current 得跟着涨");
            expectTextCell(seen, wrong, "写了几笔之后 base 还停在接手时",
                    infoField(afterWrites, "aof_base_size"), String.valueOf(sizeAtStart),
                    "底座只有载入与重写两处会动（aof.c:866/:1773）；它跟着写命令涨，自动重写算出来的增幅就是 0");

            AofPersistence aofHandle = gen1.getAofPersistence();
            assertNotNull(aofHandle, "前置条件: 这一台得真的起了 AOF，才谈得上重写");
            send(socket, "BGREWRITEAOF");
            assertEquals("+Background append only file rewriting started", readReply(in),
                    "前置条件: 这一格借 13i 那台机器发起重写，它得先受理");
            // 等的是"重写那一份跑完了"这一因 —— 拿 rewriting 标志的归还当信号（13i 钉过它只有一个
            // 归还点，还的时候文件已经换完）。这里<em>不能</em>等自己正在审的那个字段：让被审的渲染
            // 决定"什么时候才开始审它"，一硬编码成 0 就一等就中，后面的 base/current 全在读一份
            // 还没换完的日志 —— 量具 S6 实测就是这么把重写那两站绕过去的。
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (aofHandle.isRewriting() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            if (aofHandle.isRewriting()) {
                rewriteWait = "等 rewriting 标志归还超时，重写没跑完；";
            }
            String afterRewrite = persistenceSection(socket, in);
            sizeAfterRewrite = java.nio.file.Files.size(aof);
            expectTextCell(seen, wrong, "重写收尾后 in_progress 归零",
                    infoField(afterRewrite, "aof_rewrite_in_progress"), "0",
                    "标志还了而字段还在报 1 = 渲染没跟着状态走；" + rewriteWait);
            expectTextCell(seen, wrong, "重写之后 current 等于新日志长度",
                    infoField(afterRewrite, "aof_current_size"), String.valueOf(sizeAfterRewrite),
                    "重写收尾按 stat 重取（aof.c:1653 的 aofUpdateCurrentSize，调用点 :1772）而不是接着往旧数上加 —— "
                            + "旧日志的三笔流水已经不在新文件里，加出来的数比真实文件大一截");
            expectTextCell(seen, wrong, "重写之后 base 挪到新日志大小",
                    infoField(afterRewrite, "aof_base_size"), String.valueOf(sizeAfterRewrite),
                    "aof.c:1773 就在 aofUpdateCurrentSize 下一行：新的一份就是新的起点，"
                            + "不然下一次自动重写拿旧底座算，一算就是几百个百分点");

            send(socket, "SET", "k4", "v4");
            assertEquals("+OK", readReply(in), "前置条件: 换过文件之后追加句柄得还接得上");
            long sizeAfterMore = java.nio.file.Files.size(aof);
            String afterMore = persistenceSection(socket, in);
            expectTextCell(seen, wrong, "再写一笔 current 跟上新长度",
                    infoField(afterMore, "aof_current_size"), String.valueOf(sizeAfterMore),
                    "重写之后写侧还在动，current 必须继续跟着盘上走");
            expectTextCell(seen, wrong, "再写一笔 base 不跟着涨",
                    infoField(afterMore, "aof_base_size"), String.valueOf(sizeAfterRewrite),
                    "底座只在载入/重写两处动，写命令不算它");

            // 攥住重写那把锁：排进线程池的那一份进不来，标志就一直该是 1。
            // 锁内只发不碰这把锁的命令（BGREWRITEAOF 只抢 CAS、INFO 只读字段）。
            String insideLock;
            synchronized (aofHandle) {
                send(socket, "BGREWRITEAOF");
                String accepted = readReply(in);
                assertEquals("+Background append only file rewriting started", accepted,
                        "前置条件: 锁内第一问要受理，才有\"正在重写\"这回事；实际 " + accepted);
                insideLock = persistenceSection(socket, in);
            }
            expectTextCell(seen, wrong, "重写进行中 in_progress 为 1",
                    infoField(insideLock, "aof_rewrite_in_progress"), "1",
                    "上游喂 aof_child_pid != -1（:3384）；我们那份对应的是 rewriting 标志（13i 钉过它在入队之前抢）");

            // 放开锁之后那份排着的重写要跑完才谈得上"重开一代"：它在收尾时会整个换掉文件，
            // 下面的 station 读的就是换完之后的长度。等的还是标志归还，不是自己审的那个字段。
            long drainDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (aofHandle.isRewriting() && System.nanoTime() < drainDeadline) {
                Thread.sleep(5);
            }
            if (aofHandle.isRewriting()) {
                rewriteWait = "锁放开后排着的那份重写没跑完；";
            }
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }

        // 重开一代：日志早就不再是空的了，接手那一刻 current 与 base 只能一起等于盘上那份的真实长度
        //（上游 aof.c:865 现 stat、:866 紧接着把 aof_rewrite_base_size 对齐过去）。少了 :866 那一行，
        // 底座停在 0，下一次自动重写拿 1 当分母（server.c:1308-1309 那个三元），一重启就立刻重写。
        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        long sizeOnReload = java.nio.file.Files.size(aof);
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String onReload = persistenceSection(socket, in);
            expectTextCell(seen, wrong, "重开一代 current 等于盘上长度",
                    infoField(onReload, "aof_current_size"), String.valueOf(sizeOnReload),
                    "这一台是接手一份有内容的日志，不是从零开始；" + rewriteWait);
            expectTextCell(seen, wrong, "重开一代 base 等于同一份长度",
                    infoField(onReload, "aof_base_size"), String.valueOf(sizeOnReload),
                    "aof.c:866 就在载入收尾那两句里；不摆底座就等于把上一次的重写当成从没发生");
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }

        // 没配 dataDir 的那一台：AOF 没开，这一族字段在上游是整块不出现的。
        int p3 = freePort();
        RedisServer bare = new RedisServer("127.0.0.1", p3, 0);
        Thread t3 = startAndWait(bare, p3);
        try (Socket socket = connect(p3)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String body = persistenceSection(socket, in);
            expectTextCell(seen, wrong, "没起 AOF 时 aof_enabled 为 0",
                    infoField(body, "aof_enabled"), "0",
                    "上面\"aof_enabled\"那一格是这一格的阳性对照：同一个字段名在开着的那一台读到 1");
            expectTextCell(seen, wrong, "没起 AOF 时不报 current 长度",
                    infoField(body, "aof_current_size"), "(这一行没有)",
                    "上游那一块整个在 if (server.aof_state != AOF_OFF) 里（server.c:3394）；"
                            + "报一个 0 就等于说\"有日志，只是空的\"");
        } finally {
            bare.stop();
            t3.join(DEADLINE_MS);
        }
        assertTrue(wrong.isEmpty(), "INFO 的 Persistence 段要量得出盘上真实的 AOF 大小，逐格: " + seen
                + "；不合格: " + wrong + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    /** 问一次 {@code INFO persistence}，整段原样交回（已经剥掉 RESP 头）。 */
    private static String persistenceSection(Socket socket, DataInputStream in) throws IOException {
        send(socket, "INFO", "persistence");
        return readReply(in);
    }

    /** 段体的第一行 —— 只用来判段头那一句。 */
    private static String firstInfoLine(String body) {
        return body.isEmpty() ? "(整段没有)" : body.split("\r\n")[0];
    }

    /**
     * 从 INFO 段体里取一个字段。行不存在时交回 {@code "(这一行没有)"} —— 否定式判据要分得开
     * "这行没有"与"这行的值是空串"，否则上游那个 {@code if (aof_state != AOF_OFF)} 的闸门坏了也量不出来。
     */
    private static String infoField(String body, String name) {
        for (String line : body.split("\r\n")) {
            if (line.startsWith(name + ":")) {
                return line.substring(name.length() + 1);
            }
        }
        return "(这一行没有)";
    }

    /** 只读磁盘上的日志，逐条交回命令数组。用一份新实例读，不碰在跑的那一台的句柄。 */
    private static java.util.List<String[]> readAof(java.nio.file.Path aof) throws IOException {
        java.util.List<String[]> records = new java.util.ArrayList<>();
        new AofPersistence().loadAof(aof.toString(), records::add);
        return records;
    }

    /** 日志里挂在某个键名上的写记录有几笔（SET / SETEX / SETNX 族，重写之前是三笔流水）。 */
    private static int countRecordsFor(java.util.List<String[]> records, String key) {
        int n = 0;
        for (String[] record : records) {
            if (record.length >= 2 && key.equals(record[1]) && record[0].startsWith("SET")) {
                n++;
            }
        }
        return n;
    }

    @Test
    void rewriteWithoutStoreAccessorRefusesInsteadOfWipingTheLog() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-aof-bare");
        java.nio.file.Path aof = dir.resolve("appendonly.aof");
        AofPersistence seeded = new AofPersistence();
        seeded.start(aof.toString());
        seeded.appendCommand(new String[]{"SET", "kept", "v"});
        seeded.shutdown();
        byte[] before = java.nio.file.Files.readAllBytes(aof);
        assertTrue(before.length > 0, "前置条件: 日志里先要有内容，\"不许换掉它\"才不是空话");

        AofPersistence bare = new AofPersistence();
        bare.start(aof.toString());
        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> bare.rewriteAof(aof.toString()),
                    "导不出状态就不许换日志 —— 换成空的那一份等于抹掉整个数据集");
            assertTrue(thrown.getMessage().contains("StoreAccessor"),
                    "红消息要点名缺的是谁，实际: " + thrown.getMessage());
            org.junit.jupiter.api.Assertions.assertArrayEquals(before,
                    java.nio.file.Files.readAllBytes(aof), "拒绝重写之后日志的字节一个字都不许动");
        } finally {
            bare.shutdown();
        }
    }

    /**
     * 两条连接交替写时，日志里的每一段都要落回<em>它自己那一库</em>。
     * <p>
     * 上游 {@code server.aof_selected_db}（{@code server.h:1087}）记的是<b>日志</b>当前的落点，
     * 不是连接的当前库：{@code feedAppendOnlyFile} 拿目标库和它比（{@code aof.c:586}），不同才补
     * {@code SELECT} 并更新（{@code :592}）。"我这条连接在不在 DB 0"是另一回事 —— 日志只有一份，
     * 一条连接切去 DB 3 写过之后，留在 DB 0 的那条连接补不出前缀，它的每一次写都会在重放时
     * 落进 DB 3（而 DB 3 里那些键对它不可见，等于数据静默失踪）。
     * </p>
     * <p>
     * 判据分两层：结构层只读日志、按日志自己的 SELECT 走一遍算出"每个键重放时会落在哪一库"，
     * 顺带钉住"同库连写不重复打前缀"；行为层删掉快照、只留 AOF 重启，逐格问它到底在不在。
     * </p>
     */
    @Test
    void aofPositionFollowsEachConnectionsOwnDatabase() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-aof-position");
        java.nio.file.Path aof = dir.resolve("appendonly.aof");

        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        Map<String, String> shape = new LinkedHashMap<>();
        Map<String, String> shapeWrong = new LinkedHashMap<>();
        try (Socket a = connect(p1); Socket b = connect(p1)) {
            DataInputStream ain = new DataInputStream(a.getInputStream());
            DataInputStream bin = new DataInputStream(b.getInputStream());
            send(a, "SELECT", "3");
            assertEquals("+OK", readReply(ain), "前置条件: a 这条连接切到 DB 3");
            send(a, "SET", "a_in3", "v3");
            assertEquals("+OK", readReply(ain));
            // b 是一条全新的连接，它一个字都没 SELECT 过 —— 它就在 DB 0
            send(b, "SET", "b_in0", "v0");
            assertEquals("+OK", readReply(bin));
            send(b, "SET", "c_in0", "v1");
            assertEquals("+OK", readReply(bin));
            send(a, "SET", "d_in3", "v4");
            assertEquals("+OK", readReply(ain));

            java.util.List<String[]> records = new java.util.ArrayList<>();
            new AofPersistence().loadAof(aof.toString(), records::add);
            java.util.List<String> selectDbs = new java.util.ArrayList<>();
            Map<String, String> keyDb = new LinkedHashMap<>();
            int position = 0;   // 重放用的是一个全新连接，起点恒为 DB 0
            for (String[] record : records) {
                if ("SELECT".equals(record[0]) && record.length > 1) {
                    position = Integer.parseInt(record[1]);
                    selectDbs.add(record[1]);
                } else if (record.length > 1 && "SET".equals(record[0])) {
                    keyDb.put(record[1], Integer.toString(position));
                }
            }
            shape.put("记录数", String.valueOf(records.size()));
            shape.put("SELECT 序列", selectDbs.toString());
            shape.put("键重放时所在库", keyDb.toString());
            assertTrue(records.size() >= 4, "前置条件: 四次写至少要在日志里留下四条记录，实际 "
                    + records.size() + " 条");
            // 库号一共变了三次（→3、→0、→3），所以前缀恰好三条：多一条 = 每条写都重复打前缀，
            // 少一条 = 有一次换库没被记下来，那一格下面的 keyDb 对照会点名是哪一库。
            if (!selectDbs.equals(java.util.Arrays.asList("3", "0", "3"))) {
                shapeWrong.put("SELECT 序列", "日志里的落点该是 3→0→3 各补一条前缀，实际 " + selectDbs);
            }
            Map<String, String> expectedDb = new LinkedHashMap<>();
            expectedDb.put("a_in3", "3");
            expectedDb.put("b_in0", "0");
            expectedDb.put("c_in0", "0");
            expectedDb.put("d_in3", "3");
            for (Map.Entry<String, String> cell : expectedDb.entrySet()) {
                String actual = keyDb.get(cell.getKey());
                if (!cell.getValue().equals(actual)) {
                    shapeWrong.put(cell.getKey(), "重放时该落在 DB " + cell.getValue() + "，实际日志把它放在 "
                            + actual);
                }
            }
            assertTrue(shapeWrong.isEmpty(), "AOF 里每一段的落点（前缀跟着日志的落点走，不是跟着连接走）: "
                    + shape + " 不合格: " + shapeWrong);
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }
        java.nio.file.Files.deleteIfExists(dir.resolve("dump.rdb"));

        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            expectCell(seen, wrong, socket, in, "GET b_in0", "v0", "GET", "b_in0");
            expectCell(seen, wrong, socket, in, "GET c_in0", "v1", "GET", "c_in0");
            expectCell(seen, wrong, socket, in, "EXISTS a_in3 在 0 库", ":0", "EXISTS", "a_in3");
            send(socket, "SELECT", "3");
            assertEquals("+OK", readReply(in));
            expectCell(seen, wrong, socket, in, "GET a_in3", "v3", "GET", "a_in3");
            expectCell(seen, wrong, socket, in, "GET d_in3", "v4", "GET", "d_in3");
            expectCell(seen, wrong, socket, in, "EXISTS b_in0 在 3 库", ":0", "EXISTS", "b_in0");
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }
        assertTrue(wrong.isEmpty(), "只留 AOF 重启之后，四个键要各自回到自己那一库（串库 = 数据在原库里失踪）: "
                + seen + " 不合格: " + wrong);
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

    /**
     * 三档 {@code appendfsync} 必须对应三种真实的落盘节奏。上一格只验到"档位这个整数被采纳了"，
     * 而档位背后那一支 {@code syncFile()} 当时只有一句 {@code writer.flush()} 加一条自陈欠账的注释
     * （"实际的 fsync 需要使用 FileChannel 或 FileDescriptor"）—— 三档全部等价于"交给操作系统"，
     * {@code always} 那条广告从来没兑现过，掉电该丢的和 {@code no} 一样多。
     * <p>
     * 判据按档位取，因为三档的<em>差别</em>才是被广告出去的东西：
     * <ul>
     *   <li>{@code ALWAYS}：每条记录一次（上游 {@code aof.c:499-503} 就在 append 之后直接
     *       {@code redis_fsync}）；</li>
     *   <li>{@code EVERYSEC}：写侧一次都不刷，攒着由那一拍刷，且<em>每秒至多一次</em>。判据用上游
     *       那一对偏移量（{@code aof_fsync_offset != aof_current_size}，{@code aof.c:349}），于是
     *       "没人写了但还欠着一截"那一拍仍要刷一次（{@code aof.c:341-345} 的注释专门写的是这件事），
     *       而"没欠"的那一拍必须空转；</li>
     *   <li>{@code NO}：一次都不刷，但字节必须已经在文件里 —— 这一格是"0 次"那两格的阳性对照，
     *       否则 0 是量具坏了而不是档位对了。</li>
     * </ul>
     * 另外两格不属于"三档"但同属这一支：运行中换档要把那一拍跟着挂上（上游 {@code config.c:493}
     * 只改整数是因为它每轮事件循环重读那个整数，我们有定时器就得自己跟上）；收摊时上游
     * {@code stopAppendOnly}（{@code aof.c:236-238}）是 flush → fsync → close，
     * <em>连 NO 档也要在放手前刷最后一次</em>。
     */
    @Test
    void fsyncPolicyDrivesTheRealFsyncCadence() throws Exception {
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();

        // ==== ① ALWAYS：每条记录都要到盘上 ====
        System.setProperty("zcache.appendfsync", "always");
        int portAlways = freePort();
        RedisServer strict = new RedisServer("127.0.0.1", portAlways, 0);
        strict.setDataDir(java.nio.file.Files.createTempDirectory("zcache-fs-always").toString());
        Thread threadAlways = startAndWait(strict, portAlways);
        long alwaysDelta, rewriteDeltaAlways = -1;
        try (Socket socket = connect(portAlways)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            AofPersistence aof = strict.getAofPersistence();
            assertNotNull(aof, "配了 dataDir 就必须有 AOF");
            long base = aof.getFsyncCount();
            for (int i = 0; i < 3; i++) {
                send(socket, "SET", "a" + i, "v");
                assertEquals("+OK", readReply(in));
            }
            alwaysDelta = aof.getFsyncCount() - base;
            // 换进来一份新日志 = 一个新 inode，它自己也有一次到盘的义务（aof.c:1767-1770）
            long beforeRewrite = aof.getFsyncCount();
            aof.rewriteAof(aof.getAofFilePath());
            rewriteDeltaAlways = aof.getFsyncCount() - beforeRewrite;
        } finally {
            strict.stop();
            threadAlways.join(DEADLINE_MS);
            System.clearProperty("zcache.appendfsync");
        }
        // 3 笔 SET，加上启动后第一条写补的那一条 SELECT 前缀 = 4 条记录，每条一次 fsync
        if (alwaysDelta == 4) {
            seen.put("always 每条记录一次", "4");
        } else {
            wrong.put("always 每条记录一次", "3 笔 SET + 首条写补的 SELECT = 4 条记录，实际 fsync "
                    + alwaysDelta + " 次");
        }
        if (rewriteDeltaAlways == 1) {
            seen.put("always 换日志后给新日志刷一次", "1");
        } else {
            wrong.put("always 换日志后给新日志刷一次", "rewriteAof 换进新 inode 之后应当正好刷一次，实际 "
                    + rewriteDeltaAlways + " 次");
        }

        // ==== ② EVERYSEC：写侧不刷，那一拍每秒至多刷一次 ====
        System.setProperty("zcache.appendfsync", "everysec");
        int portSec = freePort();
        RedisServer everysec = new RedisServer("127.0.0.1", portSec, 0);
        everysec.setDataDir(java.nio.file.Files.createTempDirectory("zcache-fs-everysec").toString());
        Thread threadSec = startAndWait(everysec, portSec);
        long duringWrites = -1, afterIdle = -1, rewriteDeltaSec = -1;
        try (Socket socket = connect(portSec)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            AofPersistence aof = everysec.getAofPersistence();
            long base = aof.getFsyncCount();
            for (int i = 0; i < 3; i++) {
                send(socket, "SET", "b" + i, "v");
                assertEquals("+OK", readReply(in));
            }
            duringWrites = aof.getFsyncCount() - base;
            boolean ticked = waitUntilFsync(aof, base + 1, 3_000L);
            long afterTick = aof.getFsyncCount() - base;
            if (!ticked) {
                wrong.put("everysec 攒着的由那一拍刷", "等 3 秒也没有一拍把它刷下去（计数仍为 " + afterTick
                        + "）—— 写侧一停就永远不刷，正是上游 aof.c:341-345 那段注释防的病");
            } else if (afterTick == 1) {
                seen.put("everysec 攒着的由那一拍刷", "1");
            } else {
                wrong.put("everysec 攒着的由那一拍刷", "3 笔写应当攒成一次 fsync，实际累计 " + afterTick
                        + " 次（那一拍不该每笔都刷）");
            }
            // 已经刷平了：再等一拍必须空转，不许每秒白刷一次盘
            Thread.sleep(1_100L);
            afterIdle = aof.getFsyncCount() - base;
            seen.put("everysec 写侧自己刷了几次", String.valueOf(duringWrites));
            // 换日志那一次也要刷 —— 这一格与 always 那一格两头钉住档位判据
            // （上游 aof.c:1767-1770：ALWAYS 同步刷、EVERYSEC 后台刷、NO 两支都不进）
            long beforeRewrite = aof.getFsyncCount();
            aof.rewriteAof(aof.getAofFilePath());
            rewriteDeltaSec = aof.getFsyncCount() - beforeRewrite;
            if (rewriteDeltaSec == 1) {
                seen.put("everysec 换日志后给新日志刷一次", "1");
            } else {
                wrong.put("everysec 换日志后给新日志刷一次", "EVERYSEC 档换日志时那一份新 inode 也要过一次盘"
                        + "（上游走 aof_background_fsync），实际 " + rewriteDeltaSec + " 次");
            }
        } finally {
            everysec.stop();
            threadSec.join(DEADLINE_MS);
            System.clearProperty("zcache.appendfsync");
        }
        if (duringWrites > 1) {
            wrong.put("everysec 写侧不刷盘", "写命令自己就 fsync 了 " + duringWrites
                    + " 次，那就不是 everysec 而是 always");
        }
        if (afterIdle >= 0 && !wrong.containsKey("everysec 攒着的由那一拍刷") && afterIdle != 1) {
            wrong.put("everysec 空转那一拍", "没有新字节时第二拍不该再刷，实际累计 " + afterIdle + " 次");
        } else if (afterIdle == 1) {
            seen.put("everysec 空转那一拍", "仍是 1");
        }

        // ==== ③ NO：一次都不刷，但字节得在文件里 ====
        System.setProperty("zcache.appendfsync", "no");
        int portNo = freePort();
        RedisServer lazy = new RedisServer("127.0.0.1", portNo, 0);
        lazy.setDataDir(java.nio.file.Files.createTempDirectory("zcache-fs-no").toString());
        Thread threadNo = startAndWait(lazy, portNo);
        // 攥住引用：RedisServer.stop() 会把 aofPersistence 置空，收摊那一刷得从同一份对象上读
        AofPersistence lazyAof = lazy.getAofPersistence();
        assertNotNull(lazyAof, "配了 dataDir 就必须有 AOF");
        long noDelta = -1, noAfterIdle = -1, switchDelta = -1, afterStopDelta = -1;
        try (Socket socket = connect(portNo)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            AofPersistence aof = lazyAof;
            long base = aof.getFsyncCount();
            for (int i = 0; i < 3; i++) {
                send(socket, "SET", "c" + i, "v");
                assertEquals("+OK", readReply(in));
            }
            noDelta = aof.getFsyncCount() - base;
            Thread.sleep(1_100L);
            noAfterIdle = aof.getFsyncCount() - base;
            long logBytes = java.nio.file.Files.size(java.nio.file.Paths.get(aof.getAofFilePath()));
            if (logBytes == 0) {
                wrong.put("no 档的字节确实出了手", "日志是空的 —— 这一格的 0 次 fsync 就不是档位对了，"
                        + "而是根本没写过");
            } else {
                seen.put("no 档的字节确实出了手", logBytes + " 字节");
            }

            // 运行中换档：那一拍必须跟着挂上（挂不上就只剩追加路径半边生效）
            long beforeSwitch = aof.getFsyncCount();
            aof.setFsyncPolicy(AofPersistence.FSYNC_EVERYSEC);
            boolean armed = waitUntilFsync(aof, beforeSwitch + 1, 3_000L);
            switchDelta = aof.getFsyncCount() - beforeSwitch;
            if (!armed || switchDelta != 1) {
                wrong.put("换档把每秒那一拍挂上", "NO → EVERYSEC 之后欠着的那一截应由新挂上的一拍刷下去，实际累计 "
                        + switchDelta + " 次（等 3 秒" + (armed ? "后有" : "内没有") + "）");
            } else {
                seen.put("换档把每秒那一拍挂上", "1");
            }
        } finally {
            long beforeStop = lazyAof.getFsyncCount();
            lazy.stop();
            afterStopDelta = lazyAof.getFsyncCount() - beforeStop;
            threadNo.join(DEADLINE_MS);
            System.clearProperty("zcache.appendfsync");
        }
        if (noDelta != 0 || noAfterIdle != 0) {
            wrong.put("no 档不刷盘", "当场 " + noDelta + " 次、等一拍后 " + noAfterIdle
                    + " 次，NO 档两次都应当是 0");
        } else {
            seen.put("no 档不刷盘", "0 / 0");
        }
        if (afterStopDelta != 1) {
            wrong.put("收摊前那一刷", "上游 stopAppendOnly 不看档位，flush → fsync → close"
                    + "（aof.c:236-238）；实际停服时 fsync " + afterStopDelta + " 次");
        } else {
            seen.put("收摊前那一刷", "1");
        }
        assertTrue(wrong.isEmpty(), "三档 appendfsync 的真实落盘节奏（健康格: " + seen
                + "）不合格: " + wrong);
    }

    /** 等到 fsync 计数追上期望值；追上为 true，超时为 false。 */
    private static boolean waitUntilFsync(AofPersistence aof, long expected, long timeoutMs)
            throws InterruptedException {
        long stop = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < stop) {
            if (aof.getFsyncCount() >= expected) {
                return true;
            }
            Thread.sleep(20L);
        }
        return aof.getFsyncCount() >= expected;
    }

    /**
     * 上面那把尺读的是我们自己记的账，所以有一格它结构上量不到：把 {@code syncFile()} 里的
     * {@code getFD().sync()} 摘掉、只留着后面的自增，计数照旧走，界面照旧全绿，而日志再没问过介质。
     * 这一层直接读主代码的那一支，钉的就是"到底有没有向操作系统要过 fsync"这句话本身。
     * <p>
     * 每一格各有独立的猎物，且都是行为尺看不见的：摘 sync 留计数、把计数挪到 sync 之前、
     * {@code openAppending} 只接 writer 不接底下那支流（fsync 永远要不上）、{@code closeLiveWriter}
     * 不把流抹掉（换过日志之后对着已关闭的 fd 要 fsync）。
     * <p>
     * 四格连同"被钉的那几支方法还在不在"一起收进同一张表：这一支第一版让 {@code methodBody}
     * 在找不到签名时当场抛，于是改动前那一跑只报出"找不到 openAppending"，把前两条<em>真有牙</em>的
     * 红（HEAD 的 {@code syncFile()} 里根本没有 {@code getFD().sync()}）吞得干干净净。
     */
    @Test
    void syncFileAsksTheOperatingSystemAndNotJustTheHeap() throws Exception {
        String source = mainSourceOf("AofPersistence.java");
        Map<String, String> wrong = new LinkedHashMap<>();
        String body = methodBody(source, "private void syncFile() throws IOException", wrong, "syncFile");
        String openBody = methodBody(source, "private void openAppending(File file)", wrong, "openAppending");
        String closeBody = methodBody(source, "private void closeLiveWriter(boolean finalSync)", wrong,
                "closeLiveWriter");

        if (!body.contains("getFD().sync()")) {
            wrong.put("真的 fsync", "syncFile() 里没有 getFD().sync() —— 只 flush 是把字节交给内核，"
                    + "不是交给介质（改动前那一句自陈写着\"实际的 fsync 需要使用 FileChannel 或 FileDescriptor\"）");
        }
        int syncAt = body.indexOf("getFD().sync()");
        int countAt = body.indexOf("fsyncCount.incrementAndGet()");
        if (syncAt < 0 || countAt < 0 || countAt < syncAt) {
            wrong.put("记账的顺序", "fsyncCount 必须在 sync() 返回之后才自增（系统保证不了落盘就不许记账），实际 sync@"
                    + syncAt + " 计数@" + countAt);
        }
        if (!openBody.contains("liveStream = stream") || !openBody.contains("writer = new BufferedWriter")) {
            wrong.put("两只手成对打开", "openAppending 必须同时接上 writer 与底下那支持有 fd 的流，实际: " + openBody);
        }
        int writerOff = closeBody.indexOf("writer = null;");
        int streamOff = closeBody.indexOf("liveStream = null;");
        if (writerOff < 0 || streamOff < 0
                || !closeBody.substring(writerOff + "writer = null;".length()).trim().startsWith("liveStream = null;")) {
            wrong.put("两只手成对抹掉", "closeLiveWriter 里 writer 与 liveStream 必须一起松开（紧挨着的两句），"
                    + "只抹 writer 就是留着一个人对着已关闭的 fd 要 fsync；实际 writer@" + writerOff
                    + " 流@" + streamOff);
        }
        assertTrue(wrong.isEmpty(), "syncFile 这一支的结构（读的是 src/main 的字节）不合格: " + wrong);
    }

    /** 主代码的字节 —— surefire 的 cwd 是模块目录，从仓根起算的那一种写法留作兜底。 */
    private static String mainSourceOf(String fileName) throws IOException {
        String tail = "src/main/java/com/zifang/z/cache/core/persistence/" + fileName;
        java.nio.file.Path p = java.nio.file.Paths.get(tail);
        if (!java.nio.file.Files.exists(p)) {
            p = java.nio.file.Paths.get("z-cache-core/" + tail);
        }
        assertTrue(java.nio.file.Files.exists(p), "找不到被结构守卫读的那份源码: " + tail
                + "（cwd=" + java.nio.file.Paths.get("").toAbsolutePath() + "）");
        return new String(java.nio.file.Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 取一个方法的花括号体；找不到就往表里记一笔并交回空串，好让同一张表里的其它几格照旧跑完。 */
    private static String methodBody(String source, String signature, Map<String, String> wrong, String cell) {
        int at = source.indexOf(signature);
        if (at < 0) {
            wrong.put(cell + " 这一支还在", "源码里找不到 " + signature + " —— 这一支被改名或删掉了，"
                    + "靠它的那一格随之失去猎物");
            return "";
        }
        int open = source.indexOf('{', at);
        int depth = 0, end = -1;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end <= open) {
            wrong.put(cell + " 的花括号", signature + " 的花括号配不上对 —— 结构守卫读不出这一支");
            return "";
        }
        return source.substring(open, end);
    }

    /**
     * SETBIT 与 BITOP 必须进 AOF —— 它们写的是真数据，重启之后得还在。
     * <p>
     * 这两个命令一度都不在 {@code WRITE_COMMANDS} 里，而那不是一个会红的缺陷：值进了内存、
     * 当场 GET 得到、测试全绿，只有进程换过一代之后才看得出什么都没留下。所以这里的判据
     * 只能跨进程：第一代只写不 SAVE，删掉快照，第二代只许从 AOF 里读回来。
     * BITOP 还多带一层含义 —— 日志里记的是整条命令，重放时按同样的源重算，因此源键本身
     * 也得恢复得了，否则重算出来的是另一个答案。
     */
    @Test
    void bitWritesAreJournaledAndReplayed() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("zcache-bit-aof");

        int p1 = freePort();
        RedisServer gen1 = new RedisServer("127.0.0.1", p1, 0);
        gen1.setDataDir(dir.toString());
        Thread t1 = startAndWait(gen1, p1);
        try (Socket socket = connect(p1)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "SET", "bit:src", "hello");
            assertEquals("+OK", readReply(in));
            send(socket, "SETBIT", "bit:pad", "100", "1");
            assertEquals(":0", readReply(in), "前置条件: SETBIT 当场要成功");
            send(socket, "BITOP", "OR", "bit:dest", "bit:pad", "bit:src");
            assertEquals(":13", readReply(in), "前置条件: 最长源是 13 字节的 bit:pad");
            send(socket, "BITCOUNT", "bit:dest");
            assertEquals(":22", readReply(in), "battery48:7 实测，不是手算");
        } finally {
            gen1.stop();
            t1.join(DEADLINE_MS);
        }
        java.nio.file.Files.deleteIfExists(dir.resolve("dump.rdb"));

        int p2 = freePort();
        RedisServer gen2 = new RedisServer("127.0.0.1", p2, 0);
        gen2.setDataDir(dir.toString());
        Thread t2 = startAndWait(gen2, p2);
        try (Socket socket = connect(p2)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(socket, "GETBIT", "bit:pad", "100");
            assertEquals(":1", readReply(in), "SETBIT 的位必须活过重启");
            send(socket, "STRLEN", "bit:pad");
            assertEquals(":13", readReply(in), "SETBIT 撑出来的补零长度也要一样");
            send(socket, "GET", "bit:src");
            assertEquals("hello", readReply(in), "BITOP 的源必须先恢复，否则重放重算的是另一个答案");
            send(socket, "STRLEN", "bit:dest");
            assertEquals(":13", readReply(in), "BITOP 的目标键必须活过重启");
            send(socket, "BITCOUNT", "bit:dest");
            assertEquals(":22", readReply(in), "重算出来的内容要和重启前逐位相同 (battery48:7)");
            send(socket, "GETRANGE", "bit:dest", "0", "4");
            assertEquals("hello", readReply(in), "重放是按同样的源重算，不是把结果当字符串抄回来");
        } finally {
            gen2.stop();
            t2.join(DEADLINE_MS);
        }
    }

    /**
     * 结构守卫：测试取号必须落在操作系统<b>出站</b>派发区间之下。
     * <p>
     * 这一条不测服务器行为，测的是 {@link #freePort()} 那一侧的取号方式还成立。写成断言
     * 下界而不是"这枚端口能 bind"，因为"能 bind"新旧两版都成立、挡不住退回
     * {@code new ServerSocket(0)} —— 而退回那一版就是"9 遍全量里 2 遍 BindException"的来路：
     * macOS 的 {@code bind(0)} 交回 49152-65535，探针一关这个号码就能被派给出站连接。
     */
    @Test
    void probePortsComeFromBelowTheEphemeralRange() throws Exception {
        for (int i = 0; i < 20; i++) {
            int port = freePort();
            assertTrue(port >= PORT_BASE && port < PORT_BASE + PORT_SPAN,
                    "取号窗口要一眼看得出来：" + port + " 不在 [" + PORT_BASE + ","
                            + (PORT_BASE + PORT_SPAN) + ") 里");
            assertTrue(port < 32768,
                    "必须低于两侧最小出站区间的下界（Linux 默认 32768、macOS 49152）：" + port);
        }
    }

    // ==================== helpers ====================

    /** 数一个动词在日志里出现了几条。 */
    private static int countVerb(java.util.List<String[]> records, String verb) {
        int count = 0;
        for (String[] record : records) {
            if (verb.equals(record[0])) {
                count++;
            }
        }
        return count;
    }

    /** 数"某动词 + 某键"的记录条数 —— 流水塌没塌就问这一句。 */
    private static int countRecordsFor(java.util.List<String[]> records, String verb, String key) {
        int count = 0;
        for (String[] record : records) {
            if (record.length > 1 && verb.equals(record[0]) && key.equals(record[1])) {
                count++;
            }
        }
        return count;
    }

    /** 打一条命令、按单值形状读回、和期望比对；读数一律留档，红的时候一次看整张表。 */
    private static void expectCell(Map<String, String> seen, Map<String, String> wrong, Socket socket,
                                   DataInputStream in, String label, String expected,
                                   String... command) throws IOException {
        send(socket, command);
        String actual = readReply(in);
        seen.put(label, actual);
        if (!expected.equals(actual)) {
            wrong.put(label, "期望 " + expected + "，实际 " + actual);
        }
    }

    /** 不是"发一条命令问一次"的那一格：读数已经由调用方量好了，这里只管记账与判红。 */
    private static void expectTextCell(Map<String, String> seen, Map<String, String> wrong, String label,
                                       String actual, String expected, String why) {
        seen.put(label, actual);
        if (!expected.equals(actual)) {
            wrong.put(label, "期望 " + expected + "，实际 " + actual + "。" + why);
        }
    }

    /** 多值那一格（LRANGE 之类）：整份顺序都要一样，不是"里面有没有"。 */
    private static void expectListCell(Map<String, String> seen, Map<String, String> wrong, Socket socket,
                                       DataInputStream in, String label, java.util.List<String> expected,
                                       String... command) throws IOException {
        send(socket, command);
        java.util.List<String> actual = readArray(in);
        seen.put(label, actual.toString());
        if (!expected.equals(actual)) {
            wrong.put(label, "期望 " + expected + "，实际 " + actual);
        }
    }

    /**
     * 时刻那一格：还活着，且剩余时间不超过当初挂上的那个数。
     * 上界钉的是"记相对时间就会白续一期"（13d 那条规矩在重写这一支的落点），
     * 下界钉的是"导出没带上时刻" —— 那会让 TTL 读成 -1（永久键）。
     */
    private static void expectTtlCell(Map<String, String> seen, Map<String, String> wrong, Socket socket,
                                      DataInputStream in, String key, int seededSeconds) throws IOException {
        send(socket, "TTL", key);
        String reply = readReply(in);
        seen.put("TTL " + key, reply);
        long remaining = reply.startsWith(":") ? Long.parseLong(reply.substring(1)) : Long.MIN_VALUE;
        if (remaining <= 3_000L || remaining > seededSeconds) {
            wrong.put("TTL " + key, "期望落在 (3000, " + seededSeconds + "]，实际 " + reply);
        }
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
        }, "test-z-cache-server");
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!thread.isAlive()) {
                // 这条线程死了原本只留下"一段没人在读的栈"，调用方要么空转到超时、要么把
                // 一切归给"端口被抢占"这一类猜测。现在把线程自己带回来的异常一起报出来，
                // 归属由它说。
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

    /**
     * 递归读一个 RESP 值并压平成可读文本：数组 {@code [a, b, [c]]}，bulk 取字符串，
     * {@code *-1} / {@code $-1} 原样带出。XRANGE / XREADGROUP 是嵌套形状，只吃 {@code *} 头
     * 会把 payload 留在流上，下一问读到的就是上一问的字节。
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
