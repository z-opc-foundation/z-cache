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
     * 冲突是在扫到第二个 token 的<b>当场</b>回的，排在取值之前，也排在"键在不在"之前
     * （battery32:22、battery33:4/5）。
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

                send(s, "SET", "p32:k", "v2", "EX", "10", "EX", "20");
                assertEquals("-ERR syntax error", readReply(in), "同一个旗标出现两次");

                send(s, "SET", "p32:k", "v2", "FOO");
                assertEquals("-ERR syntax error", readReply(in), "不认识的尾巴要拒，不能默默丢掉");

                send(s, "GET", "p32:k");
                assertEquals("$1\r\nv", readReply(in), "上面那些被拒的 SET 一个字节都不该落下去");

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
                        "TTL 必须还在"远得摸不到"那一档，实际 " + farTtl);
                send(s, "GET", "p35:k");
                assertEquals("$2\r\nv9", readReply(in), "远未来的 EX 不许把键删掉");

                // 有意与 4.0.9 不一致的一条（见 CHANGELOG 已知边界）：对岸绕回成"过去"并静默删键，
                // 我们回错。这里钉的是"绝不静默删键"，不是文案与对岸相同。
                send(s, "SET", "p35:k", "v", "EX", "9223372036854776");
                assertEquals("-ERR invalid expire time in set", readReply(in), "battery35:6 的有意偏差");
                send(s, "GET", "p35:k");
                assertEquals("$1\r\nv", readReply(in), "溢出这一档必须保住键，对岸是把键删了");
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
                String hincrby = readReply(in);
                assertTrue(hincrby.startsWith("-ERR "),
                        "hash 字段里的同一种文本也不能当整数用，实际: " + hincrby);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 溢出那句没有 "internal error:" 前缀（battery37:10）。 */
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
                        "battery33:67 —— 整数这一档排在类型检查之前");
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
        Thread thread = new Thread(() -> {
            try {
                server.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "parity-test-server");
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!thread.isAlive()) {
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

    /** 只解析这里用到的一层形状：+/-/: 单行，$ 按声明长度读满，* 递归一层层读。 */
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
