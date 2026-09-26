package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.command.CommandHandler;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与参考实现（250 上并排跑的 redis-server 4.0.9）逐行对拍出来的命令层判据。
 * <p>
 * 每条测试的期望值后面都标了它来自哪份 battery 的第几行 —— 这不是装饰：这些形状里有几条
 * （AUTH 的文案、{@code EXPIRE k 0} 的返回值、SET 的旗标冲突、DEBUG 的子命令文法）曾经
 * <b>整模块 349 例全绿</b>地错了很久，因为老测试里没有一条从协议这一侧问过它们。
 * 判据一律从 socket 拿，且优先写"两把尺会给出不同答案"的输入（{@code +5} 之于
 * {@code Long.parseLong}、{@code EX 4000000000} 之于 {@code int}），这样测试红就红在
 * 真的那件事上，而不是红在我顺手改的文案上。
 */
class RedisServerReferenceParityTest {

    private static final long DEADLINE_MS = 8_000L;

    // ==================== 未知命令 / AUTH ====================

    /**
     * 分派要大小写无关，报错要把客户端敲进来的那一串原样还回去 —— 这两件事共用一个
     * {@code cmd} 变量时，后者必然被前者改写（battery31 第 15/16 行）。
     */
    @Test
    void unknownCommandEchoesTheNameExactlyAsTyped() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "ZzYx", "p31:e", "hello");
                assertEquals("-ERR unknown command 'ZzYx'", readReply(in),
                        "battery31:15 —— 大小写照客户端的写法回");

                send(s, "zaddx", "p31:e", "1", "one");
                assertEquals("-ERR unknown command 'zaddx'", readReply(in), "battery31:16");

                send(s, "NOSUCHCOMMAND", "a", "b");
                assertEquals("-ERR unknown command 'NOSUCHCOMMAND'", readReply(in), "battery31:14");

                // 分派本身仍然大小写无关，别把这条改成了"只认原样"
                send(s, "seT", "p31:k", "v");
                assertEquals("+OK", readReply(in));
                send(s, "gEt", "p31:k");
                assertEquals("$1\r\nv", readReply(in));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 没设密码的实例上，AUTH 的 arity 仍排在"有没有密码"之前（battery31:18、battery33:44-48）。
     */
    @Test
    void authChecksArityBeforeThePasswordQuestion() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "AUTH");
                assertEquals("-ERR wrong number of arguments for 'auth' command", readReply(in),
                        "battery33:44 —— 没设密码也先吃 arity 错");

                send(s, "AUTH", "a", "b");
                assertEquals("-ERR wrong number of arguments for 'auth' command", readReply(in),
                        "battery33:46 —— 6.0 的双参形状在 4.0.9 上就是 arity 错");

                send(s, "AUTH", "a", "b", "c");
                assertEquals("-ERR wrong number of arguments for 'auth' command", readReply(in),
                        "battery33:47");

                send(s, "AUTH", "anything");
                assertEquals("-ERR Client sent AUTH, but no password is set", readReply(in),
                        "battery33:45 —— 这句是「这台实例根本没设密码」的原文案");

                send(s, "auth", "anything");
                assertEquals("-ERR Client sent AUTH, but no password is set", readReply(in),
                        "battery33:48 —— 命令名大小写无关");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 设了 requirepass 的那一侧：三条文案各有各的位置（battery34，用
     * {@code redis-server --requirepass zc-ref-pw} 起的实例量的）。
     */
    @Test
    void authWithPasswordSetMatchesTheReference() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0, "zc-ref-pw");
        Thread thread = startAndWait(server, port);
        try (Socket s = connect(port)) {
            DataInputStream in = new DataInputStream(s.getInputStream());

            send(s, "PING");
            assertEquals("-NOAUTH Authentication required.", readReply(in), "battery34:1");

            send(s, "AUTH");
            assertEquals("-ERR wrong number of arguments for 'auth' command", readReply(in),
                    "battery34:2");

            send(s, "AUTH", "wrong");
            assertEquals("-ERR invalid password", readReply(in),
                    "battery34:3 —— 不是 6.0 之后的那句 WRONGPASS");

            send(s, "AUTH", "zc-ref-pw", "extra");
            assertEquals("-ERR wrong number of arguments for 'auth' command", readReply(in),
                    "battery34:7");

            send(s, "AUTH", "zc-ref-pw");
            assertEquals("+OK", readReply(in), "battery34:4");

            send(s, "PING");
            assertEquals("+PONG", readReply(in), "battery34:5 —— 认证之后才放行");

            send(s, "AUTH", "wrong");
            assertEquals("-ERR invalid password", readReply(in),
                    "battery34:6 —— 已认证的连接上密码错仍然是错，但不影响后续命令");
            send(s, "PING");
            assertEquals("+PONG", readReply(in), "错一次密码不该把这条连接踢回未认证");
        } finally {
            server.stop();
            joinQuietly(thread);
        }
    }

    // ==================== SET 的旗标与过期 ====================

    /**
     * 冲突与尾巴排在取值<b>之前</b>，同一个旗标重复出现则<b>不</b>算冲突
     * （battery32:20/22、battery33:3/4/5/6）。
     */
    @Test
    void setFlagsAreCheckedWhileScanningNotAfter() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "SET", "p32:k", "v");
                assertEquals("+OK", readReply(in));

                send(s, "SET", "p32:k", "v2", "NX", "XX");
                assertEquals("-ERR syntax error", readReply(in), "battery32:22");

                send(s, "SET", "p32:k", "v2", "XX", "NX");
                assertEquals("-ERR syntax error", readReply(in), "battery33:5 —— 先后无所谓");

                send(s, "SET", "p32:k", "v2", "NX", "XX", "EX", "abc");
                assertEquals("-ERR syntax error", readReply(in),
                        "battery33:4 —— 冲突挡在前面，abc 根本没被解析");

                send(s, "SET", "p32:k", "v2", "EX", "10", "PX", "10");
                assertEquals("-ERR syntax error", readReply(in), "battery32:20");

                send(s, "SET", "p32:k", "v2", "FOO");
                assertEquals("-ERR syntax error", readReply(in), "不认识的尾巴要拒，不能默默丢掉");

                // 尾巴这一刀还排在过期时间的范围判断之前：实测对岸回 syntax error，
                // 而不是 invalid expire time（旧实现在扫旗标时就当场判了 -1，抢答了这一句）。
                send(s, "SET", "p32:k", "v2", "EX", "-1", "FOO");
                assertEquals("-ERR syntax error", readReply(in), "battery33:3");

                send(s, "GET", "p32:k");
                assertEquals("$1\r\nv", readReply(in), "上面那些被拒的 SET 一个字节都不该落下去");

                // 同一个旗标出现两次：对岸照收（battery33:6 实测 +OK），旧实现把它当冲突拒了。
                send(s, "SET", "p32:k", "v2", "EX", "10", "EX", "20");
                assertEquals("+OK", readReply(in), "battery33:6");
                send(s, "GET", "p32:k");
                assertEquals("$2\r\nv2", readReply(in), "重复的 EX 不是拒的理由，写还是要写");
                send(s, "TTL", "p32:k");
                long ttl = Long.parseLong(readReply(in).substring(1));
                assertTrue(ttl > 15 && ttl <= 20, "后出现的那一枚说了算，实际 TTL " + ttl);

                // 旗标大小写无关（battery33:10：键已存在时小写 nx 回 nil，而不是 +OK）
                send(s, "SET", "p32:k", "v3", "EX", "10", "nx");
                assertEquals("$-1", readReply(in), "battery33:10 —— nx 认得，且键在，所以不写");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * SET 的 EX/PX 取值：整数语法、必须为正、以及"秒数栏是 long"。
     * battery35:9/11（{@code +10}/{@code -0} 都算语法错）、battery37:56/57（4000000000 收得下）。
     */
    @Test
    void setExpireTimeGrammarAndRange() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "SET", "p35:k", "v");
                assertEquals("+OK", readReply(in));

                send(s, "SET", "p35:k", "v2", "EX", "0");
                assertEquals("-ERR invalid expire time in set", readReply(in), "battery32:23");

                send(s, "SET", "p35:k", "v2", "EX", "-1");
                assertEquals("-ERR invalid expire time in set", readReply(in), "battery32:24");

                send(s, "SET", "p35:k", "v2", "PX", "0");
                assertEquals("-ERR invalid expire time in set", readReply(in), "battery33:8");

                send(s, "SET", "p35:k", "v2", "PX", "-1");
                assertEquals("-ERR invalid expire time in set", readReply(in), "battery33:9");

                send(s, "GET", "p35:k");
                assertEquals("$1\r\nv", readReply(in), "被拒的 EX 不许把值换掉");

                send(s, "SET", "p35:k", "v2", "EX", "+10");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery35:11 —— 语法错排在范围判断之前");

                send(s, "SET", "p35:k", "v2", "EX", "-0");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery35:9");

                send(s, "SET", "p35:k", "v2", "EX", "4000000000");
                assertEquals("+OK", readReply(in), "battery37:56 —— EX 栏是 long，不是 int");
                send(s, "TTL", "p35:k");
                long ttl = Long.parseLong(readReply(in).substring(1));
                assertTrue(ttl > 3_999_990_000L, "battery37:57 —— TTL 也得是那个量级，实际 " + ttl);

                send(s, "SET", "p35:k", "v9", "EX", "9223372036854775");
                assertEquals("+OK", readReply(in), "battery35:4");
                send(s, "TTL", "p35:k");
                // 对岸这一档 TTL 回的是原样那串（battery35:5 = 9223372036854775），因为它的
                // "秒 × 1000 + now" 在 long 上绕了一圈又落回来；我们不复刻那个绕回，折到能表达的
                // 最远一档（贴顶），所以钉的是量级而不是逐位相同 —— 键活着、时刻在未来、不被截成 int。
                long farTtl = Long.parseLong(readReply(in).substring(1));
                assertTrue(farTtl > 9_000_000_000_000_000L,
                        "TTL 必须还在「远得摸不到」那一档，实际 " + farTtl);
                send(s, "GET", "p35:k");
                assertEquals("$2\r\nv9", readReply(in), "远未来的 EX 不许把键删掉");

                // 有意与 4.0.9 不一致的一条（见 CHANGELOG 已知边界）：对岸绕回成"过去"并静默删键，
                // 我们回错。这里钉的是"绝不静默删键"，不是文案与对岸相同。
                send(s, "SET", "p35:k", "v", "EX", "9223372036854776");
                assertEquals("-ERR invalid expire time in set", readReply(in), "battery35:6 的有意偏差");
                send(s, "GET", "p35:k");
                assertEquals("$2\r\nv9", readReply(in), "溢出这一档必须原样保住上一次的写入，对岸是把键删了");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** SETEX / PSETEX 各自报自己的名字（battery33:21/23、battery35:18/19/20）。 */
    @Test
    void setexAndPsetexNameTheCommandThatRejected() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "SETEX", "p33:k", "-1", "v");
                assertEquals("-ERR invalid expire time in setex", readReply(in), "battery33:21");

                send(s, "SETEX", "p33:k", "0", "v");
                assertEquals("-ERR invalid expire time in setex", readReply(in), "battery33:23");

                send(s, "PSETEX", "p33:k", "-1", "v");
                assertEquals("-ERR invalid expire time in psetex", readReply(in), "battery35:19");

                send(s, "PSETEX", "p33:k", "0", "v");
                assertEquals("-ERR invalid expire time in psetex", readReply(in), "battery35:20");

                send(s, "SETEX", "p33:k", "+5", "v");
                assertEquals("-ERR value is not an integer or out of range", readReply(in));

                send(s, "SETEX", "p33:k", "4000000000", "v");
                assertEquals("+OK", readReply(in), "秒数栏同样是 long");
                send(s, "EXISTS", "p33:k");
                assertEquals(":1", readReply(in));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== EXPIRE ====================

    /**
     * {@code EXPIRE k 0} 是"当场删掉并回 1"，不是"回 0 什么都不做"（battery37:28）；
     * 负数同一条路（battery37:37 那一档虽然对岸是绕回的产物，删键回 1 这一点仍一致）。
     */
    @Test
    void expireWithZeroOrNegativeDeletesTheKeyAndReturnsOne() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "SET", "p37:k", "v");
                assertEquals("+OK", readReply(in));
                send(s, "EXPIRE", "p37:k", "0");
                assertEquals(":1", readReply(in), "battery37:28");
                send(s, "EXISTS", "p37:k");
                assertEquals(":0", readReply(in), "battery37:29 —— 回 1 的代价是键真没了");

                send(s, "SET", "p37:k", "v");
                assertEquals("+OK", readReply(in));
                send(s, "EXPIRE", "p37:k", "-5");
                assertEquals(":1", readReply(in));
                send(s, "EXISTS", "p37:k");
                assertEquals(":0", readReply(in));

                send(s, "EXPIRE", "p37:none", "10");
                assertEquals(":0", readReply(in), "键不存在时才是 0");

                send(s, "SET", "p37:k", "v");
                assertEquals("+OK", readReply(in));
                send(s, "EXPIRE", "p37:k", "+10");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery37:26");
                send(s, "EXPIRE", "p37:k", "-0");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery37:27");
                send(s, "TTL", "p37:k");
                assertEquals(":-1", readReply(in), "两次被拒之后键仍是永不过期");

                send(s, "EXPIRE", "p37:k", "4000000000");
                assertEquals(":1", readReply(in), "秒数栏收 long");
                send(s, "TTL", "p37:k");
                assertTrue(Long.parseLong(readReply(in).substring(1)) > 3_999_990_000L);

                send(s, "PEXPIRE", "p37:k", "-9223372036854775808");
                assertEquals(":1", readReply(in), "battery37:37");
                send(s, "EXISTS", "p37:k");
                assertEquals(":0", readReply(in), "battery37:38");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== 整数语法这一族 ====================

    /**
     * Java 的解析比 Redis 宽，而且宽的正好是 {@code +5} / {@code -0} / {@code 05} 三样
     * （battery37:2/3/4）。每个入口都是一个独立的门，所以逐门钉一遍。
     */
    @Test
    void lenientIntegerTextsAreRejectedAtEveryEntry() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "SET", "p37:n", "5");
                assertEquals("+OK", readReply(in));
                for (String bad : new String[]{"+5", "-0", "05", "00", "-05", " 5", "5 ", "0x10", "1e2", "5.0"}) {
                    send(s, "INCRBY", "p37:n", bad);
                    assertEquals("-ERR value is not an integer or out of range", readReply(in),
                            "INCRBY 的增量栏不收 " + bad);
                }
                send(s, "INCRBY", "p37:n", "0");
                assertEquals(":5", readReply(in), "合法的零只有整串一个字符这一种写法");
                send(s, "INCRBY", "p37:n", "-5");
                assertEquals(":0", readReply(in), "负增量照收");

                send(s, "RPUSH", "p37:l", "a", "b", "c");
                assertEquals(":3", readReply(in));
                send(s, "LINDEX", "p37:l", "+0");
                assertEquals("-ERR value is not an integer or out of range", readReply(in), "battery37:17");
                send(s, "LINDEX", "p37:l", "-0");
                assertEquals("-ERR value is not an integer or out of range", readReply(in), "battery37:18");
                send(s, "LRANGE", "p37:l", "+0", "-1");
                assertEquals("-ERR value is not an integer or out of range", readReply(in), "battery37:20");
                send(s, "GETRANGE", "p37:n", "+0", "-1");
                assertEquals("-ERR value is not an integer or out of range", readReply(in), "battery37:23");
                send(s, "SETRANGE", "p37:n", "+0", "X");
                assertEquals("-ERR value is not an integer or out of range", readReply(in), "battery37:24");
                send(s, "ZUNIONSTORE", "p37:d", "+1", "p37:src");
                assertEquals("-ERR value is not an integer or out of range", readReply(in), "battery37:25");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 键里存的那串整数文本也是客户端给的，判据必须与增量栏同源（{@code SET k 05} 之后
     * {@code INCR k} 在对岸是拒的）。
     */
    @Test
    void storedIntegerTextUsesTheSameGauge() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "SET", "p37:s", "05");
                assertEquals("+OK", readReply(in));
                send(s, "INCR", "p37:s");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "存进去时没人管，取出来当整数就得管");

                send(s, "HSET", "p37:h", "f", "+5");
                assertEquals(":1", readReply(in));
                send(s, "HINCRBY", "p37:h", "f", "1");
                // 这句文案也是量出来的：对岸比通用那句短，且不带 "or out of range"
                // （battery38 第 18/21 行 —— 存进去的 +5 / 05 都算坏值）。
                assertEquals("-ERR hash value is not an integer", readReply(in), "battery38:18");
                send(s, "HINCRBY", "p37:h", "f", "+1");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery38:19 —— 增量栏坏与字段值坏，两句不同");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * battery38（42 行，250 一次性实例量出来的）里那些"两把尺给出不同答案"的形状。
     * 每一档都是<b>顺序</b>判据：谁先说话，而不是说什么。
     */
    @Test
    void checksHappenInTheOrderTheReferenceRunsThem() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                // GETRANGE：整数栏先于类型（同一枚坏键名，两种答案）。中央类型闸门跑在分发之前，
                // 会把这两档做反 —— 所以 GETRANGE/SUBSTR 从闸门表里摘出来了。
                send(s, "LPUSH", "p38:l", "x");
                assertEquals(":1", readReply(in));
                send(s, "GETRANGE", "p38:l", "+0", "-1");
                assertEquals("-ERR value is not an integer or out of range", readReply(in), "battery38:15");
                send(s, "GETRANGE", "p38:l", "0", "1");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery38:14");

                // SET 的 EX 重复：最后一枚才被解析，坏的前一枚根本没人读
                send(s, "SET", "p38:s", "hello");
                assertEquals("+OK", readReply(in));
                send(s, "SET", "p38:s", "v", "EX", "abc", "EX", "10");
                assertEquals("+OK", readReply(in), "battery38:29");
                send(s, "TTL", "p38:s");
                long ttl = Long.parseLong(readReply(in).substring(1));
                assertTrue(ttl > 5 && ttl <= 10, "解析的是最后一枚，实际 TTL " + ttl);
                send(s, "SET", "p38:s", "v", "EX", "10", "EX", "-1");
                assertEquals("-ERR invalid expire time in set", readReply(in), "battery38:27");

                // SCAN：游标与尾巴各一句
                send(s, "SCAN", "abc");
                assertEquals("-ERR invalid cursor", readReply(in),
                        "battery38:33 —— 坏游标不许静默当 0 从头再扫一遍");
                send(s, "SCAN", "0", "abc");
                assertEquals("-ERR syntax error", readReply(in), "battery38:35");
                send(s, "SCAN", "0", "COUNT", "abc");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery38:34 —— 这一档不是 syntax error，两句不许合并");
                send(s, "SCAN", "0", "COUNT");
                assertEquals("-ERR syntax error", readReply(in), "只有旗标没有值");

                // BITCOUNT 的多余尾巴：无论第 5 枚是什么都是 syntax error（battery38 第 5/6/7/8/10 行）
                send(s, "BITCOUNT", "p38:s", "1", "2", "9");
                assertEquals("-ERR syntax error", readReply(in), "battery38:5");
                send(s, "BITCOUNT", "p38:s", "1", "2", "bit");
                assertEquals("-ERR syntax error", readReply(in), "battery38:7 —— 大小写都一样");
                send(s, "BITCOUNT", "p38:s", "1", "2", "3", "4");
                assertEquals("-ERR syntax error", readReply(in), "battery38:8");

                // TYPE 的多余尾巴走 arity（battery38:36），与上面那一族不同
                send(s, "TYPE", "p38:s", "extra");
                assertEquals("-ERR wrong number of arguments for 'type' command", readReply(in));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * BITCOUNT 的<b>"键在不在"排在所有语法之争之前</b>（250 实测 battery39 第 2—15 行）。
     * battery31:23 那行 {@code BITCOUNT <不存在的键> 1 2 9 → :0} 是旧实现与对岸唯一相反的一处
     * 判序：我们把"尾巴多不多"排在查键之前，于是对不存在的键回了 syntax error。
     * <p>
     * 反面对照是 GETRANGE（battery39:16/17）：它先解析下标、后查键，两族顺序<b>相反</b>。
     * 所以这两道检查不许抽成一个公共闸门 —— 合起来必有一族是错的。
     */
    @Test
    void bitcountLooksUpTheKeyBeforeGrammar() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                // 键不在：坏下标、多余尾巴都轮不到说话，一律 :0
                send(s, "BITCOUNT", "p39:x", "1");
                assertEquals(":0", readReply(in), "battery39:2 —— 不是 syntax error");
                send(s, "BITCOUNT", "p39:x", "1", "2", "9");
                assertEquals(":0", readReply(in), "battery39:3");
                send(s, "BITCOUNT", "p39:x", "abc");
                assertEquals(":0", readReply(in), "battery39:4");
                send(s, "BITCOUNT", "p39:x", "abc", "def");
                assertEquals(":0", readReply(in), "battery39:5 —— 不是整数那句");
                send(s, "BITCOUNT", "p39:x", "1", "2", "3", "4");
                assertEquals(":0", readReply(in), "battery39:6");

                // 键在但类型不对：WRONGTYPE 同样压过 syntax error 与整数那句
                send(s, "LPUSH", "p39:l", "x");
                assertEquals(":1", readReply(in));
                send(s, "BITCOUNT", "p39:l", "1");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery39:8");
                send(s, "BITCOUNT", "p39:l", "1", "2", "9");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery39:9");
                send(s, "BITCOUNT", "p39:l", "abc", "def");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery39:10 —— 整数那句排在类型之后");

                // 键在而且是 string：这时才轮到语法两档
                send(s, "SET", "p39:s", "hello");
                assertEquals("+OK", readReply(in));
                send(s, "BITCOUNT", "p39:s", "1");
                assertEquals("-ERR syntax error", readReply(in), "battery39:13");
                send(s, "BITCOUNT", "p39:s", "1", "2", "9");
                assertEquals("-ERR syntax error", readReply(in), "battery39:15");
                send(s, "BITCOUNT", "p39:s", "abc", "def");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery39:14");

                // 对照：GETRANGE 是反过来的那一族
                send(s, "GETRANGE", "p39:x", "abc", "def");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery39:16 —— 键不在也躲不掉整数这一档");
                send(s, "GETRANGE", "p39:x", "0", "1");
                assertEquals("$0\r\n", readReply(in), "battery39:17 —— 键不在回空串，不是错");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * GETBIT / SETBIT —— 这两条命令在改动前<b>根本不存在</b>（回 unknown command），
     * 而 {@code _doc/001_arch/01-module-structure.md} 一直把它们列在位操作里。
     * 下面每一档都有 250 实测背书：语法与判序来自 battery41 第 3—8 行、battery42 第 22—28 与
     * 33—36 行、battery43 第 3—7 行；算术与补零来自 battery40 第 2—9 行、battery43 第 25—31 行；
     * "改位不清 TTL" 来自 battery44 全篇（对岸 SETBIT/SETRANGE/APPEND/INCR 都保留 expire，
     * 新建的键才是 -1）。
     */
    @Test
    void getbitAndSetbitFollowTheMeasuredGrammar() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                String offsetErr = "-ERR bit offset is not an integer or out of range";
                String bitErr = "-ERR bit is not an integer or out of range";

                // 坏 bit 不许把键建出来（battery41:2/3）
                send(s, "SETBIT", "p44:fresh", "0", "2");
                assertEquals(bitErr, readReply(in), "battery41:2");
                send(s, "EXISTS", "p44:fresh");
                assertEquals(":0", readReply(in), "battery41:3 —— 被拒的 SETBIT 一个字节都不落");

                // 偏移那一栏：Redis 的整数语法 + 512MB 串换算成的位上限，两句合一
                send(s, "SETBIT", "p44:fresh", "abc", "1");
                assertEquals(offsetErr, readReply(in), "battery41:4");
                send(s, "SETBIT", "p44:fresh", "-1", "1");
                assertEquals(offsetErr, readReply(in), "battery41:5");
                send(s, "SETBIT", "p44:fresh", "1099511627776", "1");
                assertEquals(offsetErr, readReply(in), "battery41:6 —— 2^40");
                send(s, "SETBIT", "p44:fresh", "4294967296", "1");
                assertEquals(offsetErr, readReply(in), "battery42:33 —— 2^32 正好越界");
                send(s, "SETBIT", "p44:fresh", "+0", "1");
                assertEquals(offsetErr, readReply(in), "battery42:22 —— +0 不收");
                send(s, "SETBIT", "p44:fresh", "05", "1");
                assertEquals(offsetErr, readReply(in), "battery42:24 —— 前导零不收");

                // bit 那一栏比整数语法还严：只收 "0" / "1" 两种字面
                send(s, "SETBIT", "p44:fresh", "0", "-0");
                assertEquals(bitErr, readReply(in), "battery42:23");
                send(s, "SETBIT", "p44:fresh", "0", "01");
                assertEquals(bitErr, readReply(in), "battery42:25");
                send(s, "SETBIT", "p44:fresh", "0", "+1");
                assertEquals(bitErr, readReply(in), "battery42:26");
                send(s, "EXISTS", "p44:fresh");
                assertEquals(":0", readReply(in), "上面那一整族都没建出键");

                // arity 各报各的名字（小写是命令表里的原文）
                send(s, "SETBIT", "p44:fresh");
                assertEquals("-ERR wrong number of arguments for 'setbit' command", readReply(in),
                        "battery41:21");
                send(s, "SETBIT", "p44:fresh", "0");
                assertEquals("-ERR wrong number of arguments for 'setbit' command", readReply(in),
                        "battery41:20");
                send(s, "GETBIT", "p44:fresh");
                assertEquals("-ERR wrong number of arguments for 'getbit' command", readReply(in),
                        "battery41:22");

                // 判序：偏移 → 类型 → 取值。坏偏移排在 WRONGTYPE 之前（battery43:4/7）
                send(s, "LPUSH", "p44:l", "x");
                assertEquals(":1", readReply(in));
                send(s, "GETBIT", "p44:l", "abc");
                assertEquals(offsetErr, readReply(in), "battery41:24 —— 不是 WRONGTYPE");
                send(s, "GETBIT", "p44:l", "1099511627776");
                assertEquals(offsetErr, readReply(in), "battery42:35 —— GETBIT 也走同一道范围闸");
                send(s, "SETBIT", "p44:l", "0", "2");
                assertEquals(bitErr, readReply(in), "battery43:3 —— 坏 bit 也排在类型之前");
                send(s, "GETBIT", "p44:l", "0");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery40:11");
                send(s, "SETBIT", "p44:l", "0", "1");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery41:23");
                send(s, "LLEN", "p44:l");
                assertEquals(":1", readReply(in), "被 WRONGTYPE 挡下的 SETBIT 没把 list 覆盖掉");

                // 算术：编号在字节内从高位数起，撑长的那一段补零
                send(s, "SETBIT", "p44:s", "100", "1");
                assertEquals(":0", readReply(in), "battery40:2 —— 回旧值");
                send(s, "GETBIT", "p44:s", "100");
                assertEquals(":1", readReply(in), "battery40:3");
                send(s, "GETBIT", "p44:s", "0");
                assertEquals(":0", readReply(in), "battery40:4");
                send(s, "STRLEN", "p44:s");
                assertEquals(":13", readReply(in), "battery40:5 —— 第 100 位要 13 个字节才装得下");
                send(s, "BITCOUNT", "p44:s");
                assertEquals(":1", readReply(in), "battery43:29 同形 —— 中间补的是零字节");
                send(s, "SETBIT", "p44:s", "0", "1");
                assertEquals(":0", readReply(in));
                send(s, "BITCOUNT", "p44:s");
                assertEquals(":2", readReply(in), "battery40:8");
                send(s, "SETBIT", "p44:s", "100", "0");
                assertEquals(":1", readReply(in), "清位时回的是那一位原来的值");
                send(s, "BITCOUNT", "p44:s");
                assertEquals(":1", readReply(in));

                // 串尾右边一律 0，而不是"越界"（battery43:25/26）
                send(s, "SET", "p44:h", "hello");
                assertEquals("+OK", readReply(in));
                send(s, "GETBIT", "p44:h", "39");
                assertEquals(":1", readReply(in), "battery43:25 —— 'o'=0x6F 的最低位");
                send(s, "GETBIT", "p44:h", "40");
                assertEquals(":0", readReply(in), "battery43:26 —— 已经出了串尾");

                // 空串：长度 0，读位是 0，写第 0 位把它撑成 1 字节
                send(s, "SET", "p44:e", "");
                assertEquals("+OK", readReply(in));
                send(s, "GETBIT", "p44:e", "0");
                assertEquals(":0", readReply(in));
                send(s, "SETBIT", "p44:e", "0", "1");
                assertEquals(":0", readReply(in));
                send(s, "STRLEN", "p44:e");
                assertEquals(":1", readReply(in));

                // 改位不清 TTL，而新建的键没有 TTL（battery44 全篇）
                send(s, "SET", "p44:t", "v", "EX", "100");
                assertEquals("+OK", readReply(in));
                send(s, "SETBIT", "p44:t", "0", "1");
                assertEquals(":0", readReply(in));
                send(s, "TTL", "p44:t");
                long ttl = Long.parseLong(readReply(in).substring(1));
                assertTrue(ttl > 90 && ttl <= 100, "SETBIT 之后 TTL 必须还在，实测 " + ttl);
                send(s, "GETBIT", "p44:t", "0");
                assertEquals(":1", readReply(in));
                send(s, "TTL", "p44:t");
                long ttlAfterRead = Long.parseLong(readReply(in).substring(1));
                assertTrue(ttlAfterRead > 90 && ttlAfterRead <= 100, "GETBIT 也不清 TTL，实测 " + ttlAfterRead);
                send(s, "SETBIT", "p44:new", "3", "1");
                assertEquals(":0", readReply(in));
                send(s, "TTL", "p44:new");
                assertEquals(":-1", readReply(in), "battery44:19 —— 建出来的新键没有过期时间");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * BITPOS —— 改动前同样是不存在的命令。五档判序、区间只折一半、以及"找 0 且没给 end 时
     * 回 长度×8"这三件事，全都由 battery39 第 20—25 行、battery40 第 25—36 行、
     * battery41 第 10—22 行、battery42 第 13—16/29—31 行、battery43 第 10—24/30—40 行钉住；
     * 空串一律 -1 是 6394 上一次单独实测（{@code BITPOS ""} 的四档都是 -1，包括找 0）。
     */
    @Test
    void bitposMatchesTheMeasuredPrecedenceAndFolding() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                String bitArgErr = "-ERR The bit argument must be 1 or 0.";
                String intErr = "-ERR value is not an integer or out of range";
                String wrongType = "-WRONGTYPE Operation against a key holding the wrong kind of value";

                // 第一、二档：bit 的语法与取值都在查键之前，坏键名也不给默认值
                send(s, "BITPOS", "p45x", "5");
                assertEquals(bitArgErr, readReply(in), "battery41:10 —— 键不在也先说 bit");
                send(s, "BITPOS", "p45x", "abc");
                assertEquals(intErr, readReply(in), "battery39:21 —— 这一档用的是普通整数句");
                send(s, "BITPOS", "p45x", "+1");
                assertEquals(intErr, readReply(in), "battery42:29");
                send(s, "BITPOS", "p45x", "01");
                assertEquals(intErr, readReply(in), "battery42:30");

                // 第三档：键不在 → 找 1 是 -1、找 0 是 0，而且挡得住后面的语法之争
                send(s, "BITPOS", "p45x", "1");
                assertEquals(":-1", readReply(in), "battery39:20");
                send(s, "BITPOS", "p45x", "0");
                assertEquals(":0", readReply(in), "battery40:28 —— 串尾右边全是零");
                send(s, "BITPOS", "p45x", "0", "+0");
                assertEquals(":0", readReply(in), "battery42:31 —— 坏 start 根本没被解析");
                send(s, "BITPOS", "p45x", "1", "2", "3", "4");
                assertEquals(":-1", readReply(in), "battery39:22 —— 多余尾巴也排在查键之后");

                // 第四档：类型
                send(s, "LPUSH", "p45l", "x");
                assertEquals(":1", readReply(in));
                send(s, "BITPOS", "p45l", "5");
                assertEquals(bitArgErr, readReply(in), "battery41:21 —— bit 仍排在类型之前");
                send(s, "BITPOS", "p45l", "1", "abc", "def", "BIT");
                assertEquals(wrongType, readReply(in), "battery41:22 —— 类型排在 syntax error 之前");
                send(s, "BITPOS", "p45l", "1");
                assertEquals(wrongType, readReply(in), "battery40:25");

                // 第五档：个数与下标（4.0.9 不认 6.2 的 BYTE|BIT 尾栏）
                send(s, "SET", "p45s", "hello");
                assertEquals("+OK", readReply(in));
                send(s, "BITPOS", "p45s", "1", "2", "3", "4");
                assertEquals("-ERR syntax error", readReply(in), "battery41:15");
                send(s, "BITPOS", "p45s", "1", "abc", "def", "BIT");
                assertEquals("-ERR syntax error", readReply(in), "battery41:14 —— 不是整数那句");
                send(s, "BITPOS", "p45s", "1", "2", "3", "BIT");
                assertEquals("-ERR syntax error", readReply(in), "battery40:30");
                send(s, "BITPOS", "p45s", "1", "2", "3", "byte");
                assertEquals("-ERR syntax error", readReply(in), "battery40:31 —— 大小写都拒");
                send(s, "BITPOS", "p45s", "1", "2", "3", "4", "5");
                assertEquals("-ERR syntax error", readReply(in), "battery40:34");
                send(s, "BITPOS", "p45s", "1", "abc");
                assertEquals(intErr, readReply(in), "battery40:33 —— 过了个数这一档才轮到整数");
                send(s, "BITPOS");
                assertEquals("-ERR wrong number of arguments for 'bitpos' command", readReply(in),
                        "battery40:35");
                send(s, "BITPOS", "p45s");
                assertEquals("-ERR wrong number of arguments for 'bitpos' command", readReply(in),
                        "battery40:36");

                // 值：编号按字节内高位起算，"hello" = 68 65 6C 6C 6F
                String[][] valueRows = {
                        {":0", "BITPOS", "p45s", "0"},                 // battery41:12
                        {":1", "BITPOS", "p45s", "1"},                 // battery41:13
                        {":9", "BITPOS", "p45s", "1", "1", "1"},       // battery41:18
                        {":-1", "BITPOS", "p45s", "0", "1", "0"},      // battery41:19 start>end
                        {":17", "BITPOS", "p45s", "1", "2", "2"},      // battery43:14 'l'=0x6C
                        {":25", "BITPOS", "p45s", "1", "3", "3"},      // battery43:15
                        {":33", "BITPOS", "p45s", "1", "4", "4"},      // battery43:16
                        {":33", "BITPOS", "p45s", "1", "4", "99"},     // battery43:17 end 截到串尾
                        {":32", "BITPOS", "p45s", "0", "4", "4"},      // battery43:18
                        {":-1", "BITPOS", "p45s", "1", "5", "5"},      // battery43:19 整字节出串
                        {":-1", "BITPOS", "p45s", "1", "99"},          // battery43:10 start 不往回截
                        {":-1", "BITPOS", "p45s", "0", "99"},          // battery43:12 —— 也不给 40
                        {":-1", "BITPOS", "p45s", "1", "99", "1"},     // battery43:13
                        {":1", "BITPOS", "p45s", "1", "-99"},          // battery43:11 负数折到 0
                        {":32", "BITPOS", "p45s", "0", "-1", "-1"},    // battery43:20
                };
                for (String[] row : valueRows) {
                    String[] argv = Arrays.copyOfRange(row, 1, row.length);
                    send(s, argv);
                    assertEquals(row[0], readReply(in), String.join(" ", argv));
                }

                // 全是 1 的那一档：没显式给 end 时回"串尾那一位"，给了 end 才回 -1
                for (String bitIndex : new String[]{"0", "1", "2", "3", "4", "5", "6", "7"}) {
                    send(s, "SETBIT", "p45o", bitIndex, "1");
                    assertEquals(":0", readReply(in), "第 " + bitIndex + " 位置 1");
                }
                send(s, "GETBIT", "p45o", "7");
                assertEquals(":1", readReply(in), "battery42:17");
                send(s, "BITPOS", "p45o", "0");
                assertEquals(":8", readReply(in), "battery42:13 —— 裸回长度×8");
                send(s, "BITPOS", "p45o", "0", "-1");
                assertEquals(":8", readReply(in), "battery42:14 —— 只给 start 也算没给 end");
                send(s, "BITPOS", "p45o", "0", "0", "0");
                assertEquals(":-1", readReply(in), "battery42:15 —— 显式 end 才是 -1");
                send(s, "BITPOS", "p45o", "1", "0", "0");
                assertEquals(":0", readReply(in), "battery42:16");
                send(s, "SETRANGE", "p45o", "1", "A");
                assertEquals(":2", readReply(in));
                send(s, "BITPOS", "p45o", "0");
                assertEquals(":8", readReply(in), "battery42:20 —— 第二字节 0x41 的高位就是 0");
                send(s, "BITPOS", "p45o", "0", "0", "1");
                assertEquals(":8", readReply(in), "battery42:21");

                // 空串（长度 0）四档全是 -1，包括找 0 —— 不许回"第 0 位"
                send(s, "SET", "p45e", "");
                assertEquals("+OK", readReply(in));
                String[][] emptyRows = {
                        {"BITPOS", "p45e", "0"},
                        {"BITPOS", "p45e", "1"},
                        {"BITPOS", "p45e", "0", "-1"},
                        {"BITPOS", "p45e", "0", "0", "0"},
                };
                for (String[] argv : emptyRows) {
                    send(s, argv);
                    assertEquals(":-1", readReply(in), String.join(" ", argv) + " —— 6394 实测");
                }

                // 撑出来的零字节区间
                send(s, "SETBIT", "p45g", "40", "1");
                assertEquals(":0", readReply(in), "battery43:27");
                send(s, "STRLEN", "p45g");
                assertEquals(":6", readReply(in), "battery43:28");
                send(s, "BITPOS", "p45g", "1");
                assertEquals(":40", readReply(in), "battery43:30");
                send(s, "BITPOS", "p45g", "0");
                assertEquals(":0", readReply(in), "battery43:31");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 溢出那一档的取舍见 {@code setExpireTimeGrammarAndRange} 末尾；下面这几支管的是文案与算术。 */
    @Test
    void incrementOverflowIsReportedAsTheReferenceSays() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "SET", "p37:big", "5");
                assertEquals("+OK", readReply(in));
                send(s, "INCRBY", "p37:big", "9223372036854775807");
                assertEquals("-ERR increment or decrement would overflow", readReply(in), "battery37:10");
                send(s, "GET", "p37:big");
                assertEquals("$1\r\n5", readReply(in), "battery37:11 —— 溢出的那次加数不落盘");

                send(s, "DECRBY", "p37:big", "9223372036854775808");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "越界与语法不合共用一句");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== ZADD ====================

    /**
     * NX 与 GT/LT 是互斥的（battery32:32、battery33:33/34 都是 syntax error）；
     * XX + GT 不是（现代 Redis 允许），别一并挡掉。
     */
    @Test
    void zaddRejectsNxCombinedWithGtOrLt() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "DEL", "p32:z");
                assertEquals(":0", readReply(in));

                send(s, "ZADD", "p32:z", "NX", "GT", "1", "a");
                assertEquals("-ERR syntax error", readReply(in), "battery32:32");
                send(s, "ZADD", "p32:z", "NX", "LT", "1", "a");
                assertEquals("-ERR syntax error", readReply(in), "battery33:34");
                send(s, "ZADD", "p32:z", "NX", "XX", "1", "a");
                assertEquals("-ERR XX and NX options at the same time are not compatible", readReply(in),
                        "battery32:31 —— 这句不是 syntax error，两条判据别合并");

                send(s, "ZCARD", "p32:z");
                assertEquals(":0", readReply(in), "被挡下的 GT/LT 不许凭空把成员建出来");
                send(s, "EXISTS", "p32:z");
                assertEquals(":0", readReply(in), "一个成员都没建，键也不该在");

                // GT/LT 单独出现是本实现有意超出对岸的一条（4.0.9 回 syntax error）。
                // 这里钉的是它自称的那版（6.2）的语义："Don't add new elements"。
                send(s, "ZADD", "p32:z", "GT", "1", "a");
                assertEquals(":0", readReply(in), "battery33:36 的超出部分 —— GT 不建新成员");
                send(s, "ZADD", "p32:z", "LT", "1", "a");
                assertEquals(":0", readReply(in), "battery33:38 的超出部分");
                send(s, "ZCARD", "p32:z");
                assertEquals(":0", readReply(in), "两个都不建，否则它们只是无操作的样子货");

                send(s, "ZADD", "p32:z", "XX", "GT", "1", "a");
                assertEquals(":0", readReply(in), "XX + GT 合法，不许被 NX 那条互斥顺手打死");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== DEBUG ====================

    /**
     * DEBUG 的文法：裸命令一句提示，其余"不认识或 arity 不对"共用一句，且名字照客户端的写法回
     * （battery31:20、battery33:50-59、battery35:21-25、battery37:60-69）。
     */
    @Test
    void debugSubcommandGrammarMatchesTheReference() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "DEBUG");
                assertEquals("-ERR You must specify a subcommand for DEBUG. Try DEBUG HELP for info.",
                        readReply(in), "battery31:20 —— 不是通用的 arity 错");

                send(s, "DEBUG", "SLEEP");
                assertEquals("-ERR Unknown DEBUG subcommand or wrong number of arguments for 'SLEEP'",
                        readReply(in), "battery33:51");
                send(s, "DEBUG", "SLEEP", "0", "0");
                assertEquals("-ERR Unknown DEBUG subcommand or wrong number of arguments for 'SLEEP'",
                        readReply(in), "battery33:53 —— 参数多了也是这一句");
                send(s, "DEBUG", "ERROR");
                assertEquals("-ERR Unknown DEBUG subcommand or wrong number of arguments for 'ERROR'",
                        readReply(in), "battery33:57");
                send(s, "DEBUG", "FoO");
                assertEquals("-ERR Unknown DEBUG subcommand or wrong number of arguments for 'FoO'",
                        readReply(in), "battery33:59 —— 大小写照原样");

                // SLEEP 的参数量纲是秒，且读不出数就当 0
                send(s, "DEBUG", "SLEEP", "abc");
                assertEquals("+OK", readReply(in), "battery33:52");
                send(s, "DEBUG", "SLEEP", "-1");
                assertEquals("+OK", readReply(in), "battery37:69 —— 旧实现这里是 internal error");
                long t0 = System.currentTimeMillis();
                send(s, "DEBUG", "SLEEP", "0.3");
                assertEquals("+OK", readReply(in), "battery36:4 —— 旧实现把秒当毫秒，直接判整数错");
                long slept = System.currentTimeMillis() - t0;
                assertTrue(slept >= 250, "0.3 秒必须真睡够，实测只等了 " + slept + "ms");

                // ERROR 原样回，不添 ERR 前缀
                send(s, "DEBUG", "ERROR", "hello");
                assertEquals("-hello", readReply(in), "battery35:21");
                send(s, "DEBUG", "ERROR", "hello world");
                assertEquals("-hello world", readReply(in), "battery37:61");
                send(s, "DEBUG", "ERROR", "ERR prefixed");
                assertEquals("-ERR prefixed", readReply(in), "battery37:62");
                send(s, "DEBUG", "ERROR", "-dash-first");
                assertEquals("--dash-first", readReply(in), "battery35:24");
                // 多带一枚 token 就不是 ERROR 这一支了：实测 battery35:23
                // （{@code DEBUG ERROR WRONGTYPE typed}，4 枚）回的是那句共用提示，
                // 而不是把两段拼起来原样还 —— 与 battery35:22（{@code ERROR ERR prefixed}）同一档。
                send(s, "DEBUG", "ERROR", "WRONGTYPE", "typed");
                assertEquals("-ERR Unknown DEBUG subcommand or wrong number of arguments for 'ERROR'",
                        readReply(in), "battery35:23 —— ERROR 只吃一枚参数");

                // OBJECT 的"键不存在"这一档与对岸同句
                send(s, "DEBUG", "OBJECT", "p33:none");
                assertEquals("-ERR no such key", readReply(in), "battery33:54");
                send(s, "SET", "p33:e", "v");
                assertEquals("+OK", readReply(in));
                send(s, "DEBUG", "OBJECT", "p33:e");
                assertTrue(readReply(in).contains("cannot be measured from the JVM"),
                        "键在时回的是「量不出来」那句拒绝，而不是四个常量假字段");

                // 提示里点了 DEBUG HELP，它就得真回东西
                send(s, "DEBUG", "HELP");
                String help = readReplyDeep(in);
                assertTrue(help.startsWith("["), "HELP 得是数组，实际: " + help);
                assertTrue(help.contains("sleep"), "HELP 要列出真做得到的子命令，实际: " + help);
                assertFalse(help.contains("segfault"), "不许照抄对岸那份清单去承诺危险操作");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== BITCOUNT ====================

    /**
     * 位计数的一整张真值表来自 battery33/35（值 "hello"、"1234567"、17 个 'y'），
     * 每个期望值都另用 {@code int.bit_count} 独立算过一遍再抄进来。
     */
    @Test
    void bitcountMatchesTheMeasuredTruthTable() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "SET", "p33:a", "hello");
                assertEquals("+OK", readReply(in));
                String[][] rows = {
                        // {start, end, 期望} —— 空串表示不带区间
                        {"", "", "21"},        // battery33:62
                        {"0", "0", "3"},       // battery33:63  'h'=0x68
                        {"-1", "-1", "6"},     // battery33:64  'o'=0x6F
                        {"5", "5", "0"},       // battery33:65  起点越出串尾
                        {"2", "0", "0"},       // battery33:66  start > end
                        {"-100", "100", "21"}, // battery35:28  两端都贴边
                        {"2", "-1", "14"},     // battery35:29
                        {"-3", "-2", "8"},     // battery35:30
                        {"0", "-1", "21"},     // battery35:31
                        {"1", "1", "4"},       // battery35:32  'e'=0x65
                        {"-1", "-4", "0"},     // battery35:34
                };
                for (String[] row : rows) {
                    if (row[0].isEmpty()) {
                        send(s, "BITCOUNT", "p33:a");
                    } else {
                        send(s, "BITCOUNT", "p33:a", row[0], row[1]);
                    }
                    assertEquals(":" + row[2], readReply(in),
                            "BITCOUNT p33:a " + row[0] + " " + row[1]);
                }

                send(s, "SET", "p33:g", "yyyyyyyyyyyyyyyyy");
                assertEquals("+OK", readReply(in));
                send(s, "BITCOUNT", "p33:g");
                assertEquals(":85", readReply(in), "battery33:82 —— 17×0x79，每个 5 位");

                send(s, "SET", "p33:f", "1234567");
                assertEquals("+OK", readReply(in));
                send(s, "BITCOUNT", "p33:f");
                assertEquals(":26", readReply(in), "battery33:80 同族");

                send(s, "BITCOUNT", "p33:none");
                assertEquals(":0", readReply(in), "battery33:70 —— 键不在是 0，不是错");

                send(s, "BITCOUNT");
                assertEquals("-ERR wrong number of arguments for 'bitcount' command", readReply(in),
                        "battery33:85");
                send(s, "BITCOUNT", "p33:a", "0");
                assertEquals("-ERR syntax error", readReply(in), "battery33:86");
                send(s, "BITCOUNT", "p33:a", "0", "1", "BIT");
                assertEquals("-ERR syntax error", readReply(in), "battery33:68");
                send(s, "BITCOUNT", "p33:a", "0", "1", "BYTE");
                assertEquals("-ERR syntax error", readReply(in), "battery33:69");
                send(s, "BITCOUNT", "p33:a", "abc", "1");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery33:67 —— 这一行两侧都是 string 键，量不出整数与类型谁先，"
                                + "那一档由 battery39 钉（见 bitcountLooksUpTheKeyBeforeGrammar）");
                send(s, "BITCOUNT", "p33:a", "0", "99999999999999999999");
                assertEquals("-ERR value is not an integer or out of range", readReply(in),
                        "battery35:33 —— 20 位那一串越出 long，仍是这句");

                send(s, "LPUSH", "p33:h", "x");
                assertEquals(":1", readReply(in));
                send(s, "BITCOUNT", "p33:h");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery33:84");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * BITOP —— 四档判序、回的是<b>字节数</b>、以及目标键的三种命运（覆盖／删掉／一字未动）。
     * <p>
     * 这一条里最贵的是那个 {@code :5}：位族的其他成员都按<b>位</b>说话（GETBIT／SETBIT／BITPOS
     * 数的是位，BITCOUNT 回的也是位），只有 BITOP 回的是结果的<b>字节数</b>。我先按"位数"写完
     * 再跑对拍，battery45 红 15 行、battery46 红 8 行，而 :40 看上去比 :5 更像位族的回答 ——
     * 把它掰回来的是 250 上那台 4.0.9（battery45:16 实测 {@code :5}，battery40:44 那把 2^25+1
     * 字节的伞实测 {@code :33554433}）。下面每一个数都是从真值里抄的，没有一个是我算出来的。
     */
    @Test
    void bitopRepliesBytesAndFollowsTheMeasuredPrecedence() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());

                send(s, "DEL", "p48a", "p48b", "p48c", "p48d", "p48e", "p48f", "p48l", "p48m",
                        "p48n", "p48t", "p48z");
                readReply(in);
                send(s, "SET", "p48a", "hello");
                assertEquals("+OK", readReply(in));
                send(s, "SET", "p48b", "world");
                assertEquals("+OK", readReply(in));
                send(s, "SET", "p48c", "hi");
                assertEquals("+OK", readReply(in));
                send(s, "RPUSH", "p48l", "v");
                assertEquals(":1", readReply(in));

                // 第一档 arity：少于四个 token 时，操作名对不对都轮不到说话（battery45:7-11）
                String[][] arityRows = {
                        {"BITOP"}, {"BITOP", "AND"}, {"BITOP", "AND", "p48d"},
                        {"BITOP", "FOO"}, {"BITOP", "FOO", "p48d"}, {"BITOP", "XOR"},
                };
                for (String[] row : arityRows) {
                    send(s, row);
                    assertEquals("-ERR wrong number of arguments for 'bitop' command", readReply(in),
                            String.join(" ", row) + " —— battery45:7-11／battery46:43-44");
                }

                // 第二档操作名：不在那四个里就是 syntax error（battery45:12、battery46:41-42）
                send(s, "BITOP", "FOO", "p48d", "p48a");
                assertEquals("-ERR syntax error", readReply(in), "battery45:12");
                send(s, "BITOP", "SET", "p48d", "p48a");
                assertEquals("-ERR syntax error", readReply(in), "battery46:42 —— SET 也不算操作名");

                // 第三档是 NOT 只许一个源那一句，排在类型之前：源里躺着 list 也轮不到 WRONGTYPE
                for (String name : new String[]{"NOT", "Not", "nOt"}) {
                    send(s, "BITOP", name, "p48d", "p48l", "p48a");
                    assertEquals("-ERR BITOP NOT must be called with a single source key.",
                            readReply(in), name + " —— battery45:14／battery46:15-17");
                }

                // 第四档才是源的类型；而类型错的那一次一个字都没落盘（battery46:5-10）
                send(s, "SET", "p48t", "predata");
                assertEquals("+OK", readReply(in));
                send(s, "BITOP", "AND", "p48t", "p48a", "p48l");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery46:5");
                send(s, "GET", "p48t");
                assertEquals("$7\r\npredata", readReply(in), "battery46:7 —— 目标键的旧值原样留着");
                send(s, "BITOP", "NOT", "p48t", "p48l");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery46:9");
                send(s, "GET", "p48t");
                assertEquals("$7\r\npredata", readReply(in), "battery46:10");

                // 回的是字节数：5 字节的 hello AND world 实测 :5，不是 :40（battery45:16-19）
                send(s, "BITOP", "AND", "p48d", "p48a", "p48b");
                assertEquals(":5", readReply(in), "battery45:16 —— 按位数回这里就是 :40");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":15", readReply(in), "battery45:17");
                send(s, "STRLEN", "p48d");
                assertEquals(":5", readReply(in), "battery45:18");
                send(s, "GETRANGE", "p48d", "0", "-1");
                assertEquals(bulk(0x60, 0x65, 0x60, 0x6c, 0x64), readReply(in), "battery45:19");
                send(s, "BITOP", "OR", "p48d", "p48a", "p48b");
                assertEquals(":5", readReply(in), "battery45:20");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":29", readReply(in), "battery45:21");
                send(s, "BITOP", "XOR", "p48d", "p48a", "p48b");
                assertEquals(":5", readReply(in), "battery45:23");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":14", readReply(in), "battery45:24");

                // 最长源定长，短的那一个右边补零参与（battery45 第 26—37 行）
                send(s, "BITOP", "AND", "p48d", "p48a", "p48c");
                assertEquals(":5", readReply(in), "battery45:26 —— 2 字节的 hi 不缩短结果");
                send(s, "STRLEN", "p48d");
                assertEquals(":5", readReply(in), "battery45:27");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":6", readReply(in), "battery45:28");
                send(s, "GETRANGE", "p48d", "0", "-1");
                assertEquals(bulk(0x68, 0x61, 0, 0, 0), readReply(in), "battery45:29");
                send(s, "BITOP", "OR", "p48d", "p48a", "p48c");
                assertEquals(":5", readReply(in), "battery45:30");
                send(s, "GETRANGE", "p48d", "0", "-1");
                assertEquals(bulk(0x68, 0x6d, 0x6c, 0x6c, 0x6f), readReply(in), "battery45:33");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":22", readReply(in), "battery45:32");
                send(s, "BITOP", "XOR", "p48d", "p48a", "p48c");
                assertEquals(":5", readReply(in), "battery45:34");
                send(s, "GETRANGE", "p48d", "0", "-1");
                assertEquals(bulk(0, 0x0c, 0x6c, 0x6c, 0x6f), readReply(in), "battery45:37");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":16", readReply(in), "battery45:36");

                // NOT：长度就是那一个源的长度，位数跟着反（battery45 第 38—44 行）
                send(s, "BITOP", "NOT", "p48d", "p48a");
                assertEquals(":5", readReply(in), "battery45:38");
                send(s, "STRLEN", "p48d");
                assertEquals(":5", readReply(in), "battery45:39");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":19", readReply(in), "battery45:40 —— 40 位里 hello 占了 21");
                send(s, "BITOP", "NOT", "p48d", "p48c");
                assertEquals(":2", readReply(in), "battery45:42");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":9", readReply(in), "battery45:44");

                // 一把由 SETBIT 撑出来的伞：NOT 之后逐位反，STRLEN 不变（battery45 第 73—75 行）
                send(s, "SETBIT", "p48n", "0", "1");
                assertEquals(":0", readReply(in));
                send(s, "BITOP", "NOT", "p48d", "p48n");
                assertEquals(":1", readReply(in), "battery45:73");
                send(s, "GETRANGE", "p48d", "0", "-1");
                assertEquals(bulk(0x7f), readReply(in), "battery45:74");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":7", readReply(in), "battery45:75");

                // 命运一：最长源为 0 时目标键被<b>删掉</b>，而不是留一个空串（battery45:48-54）
                send(s, "SET", "p48d", "predata");
                assertEquals("+OK", readReply(in));
                send(s, "BITOP", "AND", "p48d", "p48m");
                assertEquals(":0", readReply(in), "battery45:53 —— 源全不在");
                send(s, "EXISTS", "p48d");
                assertEquals(":0", readReply(in), "battery45:54 —— 原有数据的键也被删干净");
                // 命运二：长度非零、结果全是 0 —— 目标键照写（battery45:45-47）
                send(s, "BITOP", "AND", "p48d", "p48a", "p48m");
                assertEquals(":5", readReply(in), "battery45:45");
                send(s, "STRLEN", "p48d");
                assertEquals(":5", readReply(in), "battery45:46");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":0", readReply(in), "battery45:47 —— 全零也落盘");
                // 命运三：目标键不做类型检查，list 直接被覆盖成 string（battery46:33-37）
                send(s, "RPUSH", "p48z", "v");
                assertEquals(":1", readReply(in));
                send(s, "BITOP", "XOR", "p48z", "p48a");
                assertEquals(":5", readReply(in), "battery46:33");
                send(s, "TYPE", "p48z");
                assertEquals("+string", readReply(in), "battery46:34");
                send(s, "LRANGE", "p48z", "0", "-1");
                assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value",
                        readReply(in), "battery46:37 —— 那份 list 已经不在了");

                // 目标键同时是源：读的是改之前的那一份（battery45:56-58、battery46:24-26）
                send(s, "SET", "p48t", "predata");
                assertEquals("+OK", readReply(in));
                send(s, "BITOP", "AND", "p48t", "p48a", "p48t");
                assertEquals(":7", readReply(in), "battery45:56 —— 最长的是 7 字节的 predata");
                send(s, "GETRANGE", "p48t", "0", "-1");
                assertEquals(bulk(0x60, 0x60, 0x64, 0x64, 0x61, 0, 0), readReply(in), "battery45:57");
                send(s, "BITOP", "NOT", "p48a", "p48a");
                assertEquals(":5", readReply(in), "battery46:24");
                send(s, "BITCOUNT", "p48a");
                assertEquals(":19", readReply(in), "battery46:26");
                send(s, "SET", "p48a", "hello");
                assertEquals("+OK", readReply(in));

                // 覆盖会清掉目标键原有的过期时间（battery45:64-66、battery46:38-39）
                send(s, "SET", "p48d", "x", "EX", "100");
                assertEquals("+OK", readReply(in));
                send(s, "BITOP", "AND", "p48d", "p48a");
                assertEquals(":5", readReply(in));
                send(s, "TTL", "p48d");
                assertEquals(":-1", readReply(in), "battery45:66 —— 不是 :100");

                // 命令名与操作名都认大小写；重复源、三源都按最长那个走（battery46:19-20、battery45:70-71）
                send(s, "bitop", "and", "p48d", "p48a", "p48a", "p48a", "p48a");
                assertEquals(":5", readReply(in), "battery46:19");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":21", readReply(in), "battery46:20 —— 自己 AND 自己 = 原样");
                send(s, "BITOP", "AND", "p48d", "p48a", "p48b", "p48c");
                assertEquals(":5", readReply(in), "battery45:70");
                send(s, "BITCOUNT", "p48d");
                assertEquals(":5", readReply(in), "battery45:73");

                // 事务里自己改自己 WATCH 的键不中止：EXEC 先查脏、后执行（battery47:5-19）
                send(s, "WATCH", "p48a");
                assertEquals("+OK", readReply(in));
                send(s, "MULTI");
                assertEquals("+OK", readReply(in));
                send(s, "BITOP", "AND", "p48d", "p48a", "p48b");
                assertEquals("+QUEUED", readReply(in));
                send(s, "EXEC");
                assertEquals("[:5]", readReplyDeep(in), "battery47:7");

                // 只在本机当前库里算：DB 3 的 BITOP 不在 DB 0 留下目标键（battery46:45-51）
                send(s, "SELECT", "3");
                assertEquals("+OK", readReply(in));
                send(s, "SET", "p48e", "9");
                assertEquals("+OK", readReply(in));
                send(s, "BITOP", "AND", "p48f", "p48e");
                assertEquals(":1", readReply(in), "battery46:48");
                send(s, "GETRANGE", "p48f", "0", "-1");
                assertEquals(bulk(0x39), readReply(in), "battery46:49");
                send(s, "SELECT", "0");
                assertEquals("+OK", readReply(in));
                send(s, "EXISTS", "p48f");
                assertEquals(":0", readReply(in), "battery46:53 —— 只在 DB 3 写过");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== 结构守卫 ====================

    /**
     * 语法尺只有一个口子（{@code RedisIntegerFormat}），命令层不许再自己调 Java 的解析。
     * <p>
     * 这一条守的不是已经改完的这三十几处，而是"下一个新增的命令处理器" —— 少一个口子就会
     * 多一处 {@code +5} 被收下的入口，而那类入口单测一律是绿的（它自己造的输入本来就是合法文本）。
     * 只认代码行：注释与 javadoc 里出现 {@code Long.parseLong} 是在交代历史，不算违规。
     */
    @Test
    void commandHandlerNeverParsesClientIntegersWithJavaAgain() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String raw : codeLinesOf("z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java")) {
            String line = raw.trim();
            for (String banned : new String[]{"Long.parseLong(", "Integer.parseLong(", "Integer.parseInt("}) {
                if (line.contains(banned)) {
                    offenders.add(banned + " -> " + line);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "客户端整数栏必须走 RedisIntegerFormat（见 longArg/intArg 上的说明），实际: " + offenders);
    }

    /** 同一条文案也只许出现在工厂方法里，改一个字不必满仓找。 */
    @Test
    void integerValueErrorTextIsSourcedFromOnePlace() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String raw : codeLinesOf("z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java")) {
            if (raw.contains("\"value is not an integer or out of range\"")) {
                offenders.add(raw.trim());
            }
        }
        assertTrue(offenders.isEmpty(), "这句要走 RespError.notAnInteger()，实际: " + offenders);
    }

    /** 从模块目录或 reactor 根目录都能定位到源文件；注释行与 javadoc 行剥掉。 */
    private static List<String> codeLinesOf(String relativeToRepo) throws IOException {
        java.nio.file.Path path = null;
        // surefire 的工作目录是模块目录，reactor 根跑时则是上一级 —— 两种写法各试一次。
        // 试的顺序不能反：先试短的，仓库根上那个同名前缀目录会抢走它。
        for (String candidate : new String[]{relativeToRepo, withoutFirstSegment(relativeToRepo)}) {
            if (Files.exists(Paths.get(candidate))) {
                path = Paths.get(candidate);
                break;
            }
        }
        if (path == null) {
            // 两个工作目录都不在，说明这条守卫的量具本身失效了 —— 空集合会伪装成"一处违规都没有"。
            throw new IllegalStateException("找不到源文件 " + relativeToRepo
                    + "（工作目录 " + Paths.get("").toAbsolutePath() + "）—— 量具失效，不作判定");
        }
        List<String> code = new ArrayList<>();
        boolean inBlock = false;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (inBlock) {
                if (line.endsWith("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (line.startsWith("/*")) {
                if (!line.endsWith("*/")) {
                    inBlock = true;
                }
                continue;
            }
            if (line.startsWith("//") || line.startsWith("*") || line.isEmpty()) {
                continue;
            }
            code.add(raw);
        }
        return code;
    }

    /** {@code z-cache-core/src/main/java/X.java} → {@code src/main/java/X.java}（模块目录为 cwd 时的写法）。 */
    private static String withoutFirstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** {@code CommandHandler} 的默认 locale 兜底：土耳其环境下 {@code "i".toUpperCase()} 会改字母。 */
    @Test
    void commandNameFoldingIsLocaleIndependent() throws Exception {
        run(port -> {
            try (Socket s = connect(port)) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                // 小写形式必须与大写走同一处分派；这一步在默认 locale 为 tr 时会漂
                send(s, "hset", "p:i", "f", "v");
                assertEquals(":1", readReply(in));
                send(s, "HGET", "p:i", "f");
                assertEquals("$1\r\nv", readReply(in));
                send(s, "bitcount", "p:i");
                String lowered = readReply(in);
                assertTrue(lowered.startsWith(":") || lowered.startsWith("-"),
                        "小写的 BITCOUNT 也要被认出来，实际: " + lowered);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== 测试脚手架 ====================

    private interface Body {
        void run(int port) throws Exception;
    }

    /** 起一台空库的服务器、跑完一段判据、关掉。用例之间不共库，所以键名前缀只为可读性。 */
    private static void run(Body body) throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        Thread thread = startAndWait(server, port);
        try {
            body.run(port);
        } finally {
            server.stop();
            joinQuietly(thread);
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(DEADLINE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        }, "parity-test-server");
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!thread.isAlive()) {
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

    /** 只解析这里用到的一层形状：+/-/: 单行，$ 按声明长度读满，* 递归一层层读。 */
    /**
     * 把期望的载荷按字节写成 harness 的 bulk 形状。BITOP 的结果天然带 NUL（短的源右边补零），
     * 在 Java 源码里贴一串控制字符既读不出来、又容易被编辑器"顺手"改掉，所以按字节列。
     */
    private static String bulk(int... bytes) {
        StringBuilder sb = new StringBuilder();
        for (int b : bytes) {
            sb.append((char) b);
        }
        return "$" + bytes.length + "\r\n" + sb;
    }

    private static String readReply(DataInputStream in) throws IOException {
        String line = readLine(in);
        if (line.startsWith("$")) {
            int length = Integer.parseInt(line.substring(1));
            if (length < 0) {
                return "$" + length;
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            in.readFully(new byte[2]);
            return "$" + length + "\r\n" + new String(payload, StandardCharsets.UTF_8);
        }
        return line;
    }

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
        if (line.startsWith("$")) {
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

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\r') {
                in.read();
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.toString();
    }
}
