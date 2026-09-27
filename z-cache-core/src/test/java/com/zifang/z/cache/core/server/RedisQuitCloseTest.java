package com.zifang.z.cache.core.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code QUIT} 回完 {@code +OK} 之后，服务端到底关不关这条连接（卡 #50）。
 *
 * <p>改前那半边是"帧对了、连接从不关"：{@code CommandHandler} 里 QUIT 有<em>两处</em>分支
 * （订阅态白名单一支 {@code :294}、主分派一支 {@code :324}，行号取自改前那份字节，
 * 复算 = {@code git show a372918:z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java | grep -n QUIT}），
 * 而 {@code CLIENT_CLOSE_AFTER_REPLY} 的等价物<em>一处都没有</em> —— 回完 {@code +OK}
 * 之后 socket 一直开着。上游 5.0.14 {@code server.c:2585-2596} 的
 * {@code processCommand} 把它放在<em>命令查表之前</em>就地处理：</p>
 *
 * <pre>
 *     if (!strcasecmp(c-&gt;argv[0]-&gt;ptr,"quit")) {
 *         addReply(c,shared.ok);
 *         c-&gt;flags |= CLIENT_CLOSE_AFTER_REPLY;
 *         return C_ERR;
 *     }
 * </pre>
 *
 * <p>这三行决定了五件事，逐件量过（{@code quit} 在 {@code server.c:2600} 的 {@code lookupCommand}
 * 之前返回，所以 arity 门、订阅态闸门（{@code server.c:2727-2733}）、AUTH 门、MULTI 入队
 * <em>四道都轮不到它</em>；而 {@code CLIENT_CLOSE_AFTER_REPLY} 真正落地是在
 * {@code networking.c:1445-1450} —— 回写之后才关，不是当场 {@code ctx.close()}）：</p>
 * <ol>
 *   <li>{@code QUIT extra} 多带的参数不参与判断 —— 还是 {@code +OK}，不是 arity 错；</li>
 *   <li>{@code MULTI} 期间它<em>不入队</em> —— 回 {@code +OK} 而不是 {@code +QUEUED}，
 *       队列里那条 {@code SET q4key v} 永远不被执行（probe 连接上 {@code GET q4key} 是 nil）；</li>
 *   <li>带 {@code requirepass} 的服务器上，未 AUTH 的连接问 QUIT 也回 {@code +OK}（不是 NOAUTH）；</li>
 *   <li>一次 write 里连着发 {@code QUIT} 与 {@code PING}，客户端只读到一帧 {@code +OK}；</li>
 *   <li>关掉的是<em>整个客户端状态</em>：别的连接问 {@code PUBSUB CHANNELS a} 是空、
 *       {@code NUMSUB a} 是 {@code ['a',0]} —— 订阅被服务端摘掉了。</li>
 * </ol>
 *
 * <p><b>期望值不是推演</b>：下面两段 GEN-QUIT-CELLS（普通 54 格 + 带密码 54 格）由
 * {@code ~/.cache/zcache_gauges/quit_close_mut/gen_quit_cells.py} 从参照原文<em>机械</em>生成
 * （{@code ref_quit_plain.tr}／{@code ref_quit_auth.tr}：250 上一次性 redis 4.0.9，端口 6392/6393、
 * 私有 {@code --dir}，跑完即 kill 并复验端口已空；<b>127.0.0.1:6379 是别人在用的真 redis，没碰过</b>）。
 * 同一把尺（{@code quit_gate.py}，两侧共用 {@code ref_frames.py} 的 {@code Reader}/渲染，
 * 两份 md5 逐字相同 {@code 951589fab1e260479231ca1a6d053fff}）也量了我方改前改后：
 * {@code cmp_quit.py} 读数 {@code TOTAL=108 改前红=59 改后红=0}（落
 * {@code logs/quit_13z_cmp_ref_vs_ours.txt}）。复算 = 参照侧 {@code python3 -u quit_gate.py --port 6392 --out ref_quit_plain.tr}
 * 与 {@code --port 6393 --out ref_quit_auth.tr --password secrete}，我方侧
 * {@code python3 -u run_ours_quit.py --out ours_quit_plain.tr}（这个脚本只有 {@code --out}/{@code --password}/{@code --port}
 * 三个参数，它量的是<em>当时工作树</em>那版字节：{@code ours_quit_*_before.tr} 是动生产字节<em>之前</em>跑的，
 * {@code ours_quit_*_after.tr} 是修完之后跑的），最后 {@code python3 -u cmp_quit.py}
 * （它不接参数，读的就是上面那两对 {@code .tr}）；250 那侧的安全与对账读数在 {@code logs/quit_13z_250_hygiene.txt}。</p>
 *
 * <p>"关不关"这件事仓里原本<em>一个字的判据都没有</em>，所以本卡新增的判据主体是
 * {@code closedCell}／{@code finalClosedCell} 那 58 格（每族 19 + 10，两族各 10 个 case）：量具在 python
 * 那侧用 {@code recv(1, MSG_PEEK)}，Java 这侧只能 {@code read()}（没有 PEEK），而每一格的读取都发生在
 * 上一格把字节读到"安静"之后，所以这里 {@code read()} 拿不到待读字节、-1 就只可能是对端 FIN。
 * 万一真有半截字节被吃掉，下一格的 {@code parseAll} 会抛"半截帧（量具坏了）"而不是静默判错。</p>
 */
class RedisQuitCloseTest {

    private static final long DEADLINE_MS = 8_000L;
    /** 一次"读到安静为止"的窗口：帧都到了就靠这一拍收尾，所以它同时也是"总共几条帧"的量具。 */
    private static final int QUIET_MS = 250;
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final AtomicInteger PORT_CURSOR = new AtomicInteger();

    /** 带密码那一族的服务器口令 —— 全程只用来让 NOAUTH 那一形成立，客户端从不 AUTH。 */
    private static final String PASSWORD = "secrete";
    private static final String NOAUTH = "error:NOAUTH Authentication required.";

    private static final String HANDLER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java";
    private static final String NETTY_HANDLER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/server/RedisServerHandler.java";
    private static final String CORE_MAIN = "z-cache-core/src/main";

    private static final String QUIT_BRANCH = "\"QUIT\".equals(cmd)";

    // =================================================================================
    // 交付那一半：真起一台，从线上按帧 + 按 FIN 读
    // =================================================================================

    /**
     * 普通服务器上的十家 case：帧、每步之后的"连接还被服务端关了吗"、以及 case 跑完
     * <em>另开一条连接</em>去问的 probe（那笔写落库没有、订阅被摘掉没有）。
     *
     * <p>为什么要 probe 这一层：QUIT 关掉的不该只是一条 socket。只量帧 + FIN 的话，
     * "回完 +OK 就关，但订阅还挂在 {@code PubSubManager} 上"这一形能全绿 —— 参照自己
     * 在别的连接上看得见这家不在了。</p>
     */
    @Test
    void quitIsAnsweredThenTheServerClosesTheConnection() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        // 阳性对照：下面 54 格全靠"把整段字节切成若干条顶层帧"和"这条连接关了吗"两把尺，
        // 先量它们自己分不分得开 —— 尤其"一帧"和"两帧"（q6 的分歧就在这）、
        // "零帧"和"一帧"（q7 的分歧就在这）必须一眼就分家。
        cell(seen, wrong, "P0a_two_frames_stay_two_cells", String.valueOf(rendered(
                "+OK\r\n+PONG\r\n".getBytes(StandardCharsets.UTF_8))), "[simple:OK, simple:PONG]");
        cell(seen, wrong, "P0b_one_frame_is_not_two", String.valueOf(rendered(
                "+OK\r\n".getBytes(StandardCharsets.UTF_8))), "[simple:OK]");
        cell(seen, wrong, "P0c_no_bytes_is_zero_frames", String.valueOf(rendered(new byte[0])), "[]");
        // closedNow 的阳性对照：一条<em>确定还开着</em>的 socket 必须是 false，对端 close() 之后
        // 必须是 true。少了这一对，"我方永不关闭"和"量具读不出 FIN"在判据里长得一模一样。
        try (ServerSocket sink = new ServerSocket()) {
            sink.setReuseAddress(true);
            sink.bind(new InetSocketAddress("127.0.0.1", 0));
            try (Socket open = new Socket()) {
                open.connect(sink.getLocalSocketAddress(), 2_000);
                cell(seen, wrong, "P0d_closed_now_false_while_peer_keeps_it",
                        String.valueOf(closedNow(open, 300)), "false");
                sink.accept().close();
                cell(seen, wrong, "P0e_closed_now_true_on_known_fin",
                        String.valueOf(closedNow(open, 400)), "true");
            }
        }

        try (Wire wire = Wire.open(null)) {
            // ===== GEN-QUIT-CELLS-PLAIN（由 gen_quit_cells.py 从 ref_quit_plain.tr 生成，54 格）=====
            // ---- q1_plain ----
            wire.openConn(1);
            stepCell(seen, wrong, wire, "q1_plain#1.frames", 1, "[simple:PONG]", "PING");
            closedCell(seen, wrong, "q1_plain#1.closed", wire, 1, "false");
            stepCell(seen, wrong, wire, "q1_plain#2.frames", 1, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q1_plain#2.closed", wire, 1, "true");
            finalClosedCell(seen, wrong, "q1_plain#final", wire, 1, "true");
            // ---- q2_write_extra_args ----
            wire.openConn(2);
            stepCell(seen, wrong, wire, "q2_write_extra_args#1.frames", 2, "[simple:OK]", "SET q2key v1");
            closedCell(seen, wrong, "q2_write_extra_args#1.closed", wire, 2, "false");
            stepCell(seen, wrong, wire, "q2_write_extra_args#2.frames", 2, "[simple:OK]", "QUIT extra");
            closedCell(seen, wrong, "q2_write_extra_args#2.closed", wire, 2, "true");
            probeCell(seen, wrong, wire, "q2_write_extra_args#probe1", "['v1']", "GET q2key");
            finalClosedCell(seen, wrong, "q2_write_extra_args#final", wire, 2, "true");
            // ---- q3_lowercase ----
            wire.openConn(3);
            stepCell(seen, wrong, wire, "q3_lowercase#1.frames", 3, "[simple:OK]", "quit");
            closedCell(seen, wrong, "q3_lowercase#1.closed", wire, 3, "true");
            finalClosedCell(seen, wrong, "q3_lowercase#final", wire, 3, "true");
            // ---- q4_multi_queued ----
            wire.openConn(4);
            stepCell(seen, wrong, wire, "q4_multi_queued#1.frames", 4, "[simple:OK]", "MULTI");
            closedCell(seen, wrong, "q4_multi_queued#1.closed", wire, 4, "false");
            stepCell(seen, wrong, wire, "q4_multi_queued#2.frames", 4, "[simple:QUEUED]", "SET q4key v");
            closedCell(seen, wrong, "q4_multi_queued#2.closed", wire, 4, "false");
            stepCell(seen, wrong, wire, "q4_multi_queued#3.frames", 4, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q4_multi_queued#3.closed", wire, 4, "true");
            probeCell(seen, wrong, wire, "q4_multi_queued#probe1", "[nil]", "GET q4key");
            finalClosedCell(seen, wrong, "q4_multi_queued#final", wire, 4, "true");
            // ---- q5_subscribe ----
            wire.openConn(5);
            stepCell(seen, wrong, wire, "q5_subscribe#1.frames", 5, "[['subscribe','a',1]]", "SUBSCRIBE a");
            closedCell(seen, wrong, "q5_subscribe#1.closed", wire, 5, "false");
            stepCell(seen, wrong, wire, "q5_subscribe#2.frames", 5, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q5_subscribe#2.closed", wire, 5, "true");
            finalClosedCell(seen, wrong, "q5_subscribe#final", wire, 5, "true");
            // ---- q6_inline_pipeline ----
            wire.openConn(6);
            stepCell(seen, wrong, wire, "q6_inline_pipeline#1.frames", 6, "[simple:OK]", "QUIT", "PING");
            closedCell(seen, wrong, "q6_inline_pipeline#1.closed", wire, 6, "true");
            finalClosedCell(seen, wrong, "q6_inline_pipeline#final", wire, 6, "true");
            // ---- q7_quit_then_ping ----
            wire.openConn(7);
            stepCell(seen, wrong, wire, "q7_quit_then_ping#1.frames", 7, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q7_quit_then_ping#1.closed", wire, 7, "true");
            stepCell(seen, wrong, wire, "q7_quit_then_ping#2.frames", 7, "[]", "PING");
            closedCell(seen, wrong, "q7_quit_then_ping#2.closed", wire, 7, "true");
            finalClosedCell(seen, wrong, "q7_quit_then_ping#final", wire, 7, "true");
            // ---- q8_psubscribe ----
            wire.openConn(8);
            stepCell(seen, wrong, wire, "q8_psubscribe#1.frames", 8, "[['psubscribe','x*',1]]", "PSUBSCRIBE x*");
            closedCell(seen, wrong, "q8_psubscribe#1.closed", wire, 8, "false");
            stepCell(seen, wrong, wire, "q8_psubscribe#2.frames", 8, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q8_psubscribe#2.closed", wire, 8, "true");
            finalClosedCell(seen, wrong, "q8_psubscribe#final", wire, 8, "true");
            // ---- q9_sub_quit_others ----
            wire.openConn(9);
            stepCell(seen, wrong, wire, "q9_sub_quit_others#1.frames", 9, "[['subscribe','a',1]]", "SUBSCRIBE a");
            closedCell(seen, wrong, "q9_sub_quit_others#1.closed", wire, 9, "false");
            stepCell(seen, wrong, wire, "q9_sub_quit_others#2.frames", 9, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q9_sub_quit_others#2.closed", wire, 9, "true");
            probeCell(seen, wrong, wire, "q9_sub_quit_others#probe1", "[[]]", "PUBSUB CHANNELS a");
            probeCell(seen, wrong, wire, "q9_sub_quit_others#probe2", "[['a',0]]", "PUBSUB NUMSUB a");
            finalClosedCell(seen, wrong, "q9_sub_quit_others#final", wire, 9, "true");
            // ---- q10_psub_quit_others ----
            wire.openConn(10);
            stepCell(seen, wrong, wire, "q10_psub_quit_others#1.frames", 10, "[['psubscribe','q10*',1]]", "PSUBSCRIBE q10*");
            closedCell(seen, wrong, "q10_psub_quit_others#1.closed", wire, 10, "false");
            stepCell(seen, wrong, wire, "q10_psub_quit_others#2.frames", 10, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q10_psub_quit_others#2.closed", wire, 10, "true");
            probeCell(seen, wrong, wire, "q10_psub_quit_others#probe1", "[[]]", "PUBSUB CHANNELS q10*");
            probeCell(seen, wrong, wire, "q10_psub_quit_others#probe2", "[0]", "PUBSUB NUMPAT");
            finalClosedCell(seen, wrong, "q10_psub_quit_others#final", wire, 10, "true");
            // ===== END-GEN-QUIT-CELLS-PLAIN =====
        }
        finish("quitIsAnsweredThenTheServerClosesTheConnection", seen, wrong);
    }

    /**
     * 带 {@code requirepass} 的同一族 case：QUIT 在 AUTH 门<em>之前</em>，所以未认证的连接
     * 上它照样 {@code +OK} 并关连接，而<em>别的</em>家一律 {@code -NOAUTH}。
     *
     * <p>这一半不是重复劳动：改前三处分支里，主分派那一支在 AUTH 门后面（未认证时根本到不了），
     * 于是"QUIT 也被 NOAUTH 挡下来"是我方实际形状。只在普通服务器上量，这一形看不见。</p>
     */
    @Test
    void quitIsAnsweredBeforeTheAuthGate() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        try (Wire wire = Wire.open(PASSWORD)) {
            // ===== GEN-QUIT-CELLS-AUTH（由 gen_quit_cells.py 从 ref_quit_auth.tr 生成，54 格）=====
            // ---- q1_plain ----
            wire.openConn(1);
            stepCell(seen, wrong, wire, "q1_plain#1.frames", 1, "[error:NOAUTH Authentication required.]", "PING");
            closedCell(seen, wrong, "q1_plain#1.closed", wire, 1, "false");
            stepCell(seen, wrong, wire, "q1_plain#2.frames", 1, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q1_plain#2.closed", wire, 1, "true");
            finalClosedCell(seen, wrong, "q1_plain#final", wire, 1, "true");
            // ---- q2_write_extra_args ----
            wire.openConn(2);
            stepCell(seen, wrong, wire, "q2_write_extra_args#1.frames", 2, "[error:NOAUTH Authentication required.]", "SET q2key v1");
            closedCell(seen, wrong, "q2_write_extra_args#1.closed", wire, 2, "false");
            stepCell(seen, wrong, wire, "q2_write_extra_args#2.frames", 2, "[simple:OK]", "QUIT extra");
            closedCell(seen, wrong, "q2_write_extra_args#2.closed", wire, 2, "true");
            probeCell(seen, wrong, wire, "q2_write_extra_args#probe1", "[error:NOAUTH Authentication required.]", "GET q2key");
            finalClosedCell(seen, wrong, "q2_write_extra_args#final", wire, 2, "true");
            // ---- q3_lowercase ----
            wire.openConn(3);
            stepCell(seen, wrong, wire, "q3_lowercase#1.frames", 3, "[simple:OK]", "quit");
            closedCell(seen, wrong, "q3_lowercase#1.closed", wire, 3, "true");
            finalClosedCell(seen, wrong, "q3_lowercase#final", wire, 3, "true");
            // ---- q4_multi_queued ----
            wire.openConn(4);
            stepCell(seen, wrong, wire, "q4_multi_queued#1.frames", 4, "[error:NOAUTH Authentication required.]", "MULTI");
            closedCell(seen, wrong, "q4_multi_queued#1.closed", wire, 4, "false");
            stepCell(seen, wrong, wire, "q4_multi_queued#2.frames", 4, "[error:NOAUTH Authentication required.]", "SET q4key v");
            closedCell(seen, wrong, "q4_multi_queued#2.closed", wire, 4, "false");
            stepCell(seen, wrong, wire, "q4_multi_queued#3.frames", 4, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q4_multi_queued#3.closed", wire, 4, "true");
            probeCell(seen, wrong, wire, "q4_multi_queued#probe1", "[error:NOAUTH Authentication required.]", "GET q4key");
            finalClosedCell(seen, wrong, "q4_multi_queued#final", wire, 4, "true");
            // ---- q5_subscribe ----
            wire.openConn(5);
            stepCell(seen, wrong, wire, "q5_subscribe#1.frames", 5, "[error:NOAUTH Authentication required.]", "SUBSCRIBE a");
            closedCell(seen, wrong, "q5_subscribe#1.closed", wire, 5, "false");
            stepCell(seen, wrong, wire, "q5_subscribe#2.frames", 5, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q5_subscribe#2.closed", wire, 5, "true");
            finalClosedCell(seen, wrong, "q5_subscribe#final", wire, 5, "true");
            // ---- q6_inline_pipeline ----
            wire.openConn(6);
            stepCell(seen, wrong, wire, "q6_inline_pipeline#1.frames", 6, "[simple:OK]", "QUIT", "PING");
            closedCell(seen, wrong, "q6_inline_pipeline#1.closed", wire, 6, "true");
            finalClosedCell(seen, wrong, "q6_inline_pipeline#final", wire, 6, "true");
            // ---- q7_quit_then_ping ----
            wire.openConn(7);
            stepCell(seen, wrong, wire, "q7_quit_then_ping#1.frames", 7, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q7_quit_then_ping#1.closed", wire, 7, "true");
            stepCell(seen, wrong, wire, "q7_quit_then_ping#2.frames", 7, "[]", "PING");
            closedCell(seen, wrong, "q7_quit_then_ping#2.closed", wire, 7, "true");
            finalClosedCell(seen, wrong, "q7_quit_then_ping#final", wire, 7, "true");
            // ---- q8_psubscribe ----
            wire.openConn(8);
            stepCell(seen, wrong, wire, "q8_psubscribe#1.frames", 8, "[error:NOAUTH Authentication required.]", "PSUBSCRIBE x*");
            closedCell(seen, wrong, "q8_psubscribe#1.closed", wire, 8, "false");
            stepCell(seen, wrong, wire, "q8_psubscribe#2.frames", 8, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q8_psubscribe#2.closed", wire, 8, "true");
            finalClosedCell(seen, wrong, "q8_psubscribe#final", wire, 8, "true");
            // ---- q9_sub_quit_others ----
            wire.openConn(9);
            stepCell(seen, wrong, wire, "q9_sub_quit_others#1.frames", 9, "[error:NOAUTH Authentication required.]", "SUBSCRIBE a");
            closedCell(seen, wrong, "q9_sub_quit_others#1.closed", wire, 9, "false");
            stepCell(seen, wrong, wire, "q9_sub_quit_others#2.frames", 9, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q9_sub_quit_others#2.closed", wire, 9, "true");
            probeCell(seen, wrong, wire, "q9_sub_quit_others#probe1", "[error:NOAUTH Authentication required.]", "PUBSUB CHANNELS a");
            probeCell(seen, wrong, wire, "q9_sub_quit_others#probe2", "[error:NOAUTH Authentication required.]", "PUBSUB NUMSUB a");
            finalClosedCell(seen, wrong, "q9_sub_quit_others#final", wire, 9, "true");
            // ---- q10_psub_quit_others ----
            wire.openConn(10);
            stepCell(seen, wrong, wire, "q10_psub_quit_others#1.frames", 10, "[error:NOAUTH Authentication required.]", "PSUBSCRIBE q10*");
            closedCell(seen, wrong, "q10_psub_quit_others#1.closed", wire, 10, "false");
            stepCell(seen, wrong, wire, "q10_psub_quit_others#2.frames", 10, "[simple:OK]", "QUIT");
            closedCell(seen, wrong, "q10_psub_quit_others#2.closed", wire, 10, "true");
            probeCell(seen, wrong, wire, "q10_psub_quit_others#probe1", "[error:NOAUTH Authentication required.]", "PUBSUB CHANNELS q10*");
            probeCell(seen, wrong, wire, "q10_psub_quit_others#probe2", "[error:NOAUTH Authentication required.]", "PUBSUB NUMPAT");
            finalClosedCell(seen, wrong, "q10_psub_quit_others#final", wire, 10, "true");
            // ===== END-GEN-QUIT-CELLS-AUTH =====
        }
        finish("quitIsAnsweredBeforeTheAuthGate", seen, wrong);
    }

    /**
     * 结构守卫：QUIT 是<em>一道</em>分支，而且排在订阅态闸门／AUTH 门／MULTI 入队<em>之前</em>；
     * 关闭是"回写落地之后"挂上去的，不是当场 {@code ctx.close()}。
     *
     * <p>为什么行为尺之外还要这一层：把 QUIT 拆成"闸门里一支 + 主分派里一支"能骗过所有帧判据
     * （两支都回 {@code +OK}），但下一次给闸门加白名单的人就会漏掉"它不该被闸门管"这件事。
     * 顺序判据（{@code S4}–{@code S6}）钉的是上游那个位置的语义，不是某一串字符。</p>
     */
    @Test
    void quitIsOneBranchAheadOfTheThreeGatesAndClosingHappensAfterTheFlush() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        List<String> handler = codeLinesOf(HANDLER);
        String handlerCode = squeeze(join(handler));
        String nettyCode = squeeze(join(codeLinesOf(NETTY_HANDLER)));

        // 阳性对照：这两把尺（同文件的行扫描、整树扫描）都<em>看得见</em>要被它们数的形状 ——
        // 否则"case "QUIT": 在整棵 core/main 里 0 命中"这一格可以是永远绿的。
        cell(seen, wrong, "S0preyQuitInSight", String.valueOf(countContaining(handler, "\"QUIT\"") >= 1),
                "true");
        cell(seen, wrong, "S0preyCaseLabelsInTree",
                String.valueOf(countInTree(CORE_MAIN, "case \"SUBSCRIBE\":") >= 1), "true");
        cell(seen, wrong, "S0preyCloseMentionsInComments",
                String.valueOf(countOf(readRepoFile(NETTY_HANDLER), "ctx.close()") >= 2), "true");

        cell(seen, wrong, "S1noQuitCaseAnywhere", String.valueOf(countInTree(CORE_MAIN, "case \"QUIT\":")), "0");
        cell(seen, wrong, "S2oneQuitBranch", String.valueOf(countOf(handlerCode, QUIT_BRANCH)), "1");
        cell(seen, wrong, "S3oneMarkSite",
                String.valueOf(countOf(handlerCode, "closeAfterReply=true")), "1");
        cell(seen, wrong, "S4quitAheadOfPubSubGate",
                String.valueOf(ahead(handlerCode, QUIT_BRANCH, "only(P)SUBSCRIBE")), "true");
        cell(seen, wrong, "S5quitAheadOfAuthGate",
                String.valueOf(ahead(handlerCode, QUIT_BRANCH, "if(!authenticated)")), "true");
        cell(seen, wrong, "S6quitAheadOfMultiQueue",
                String.valueOf(ahead(handlerCode, QUIT_BRANCH, "RespSimpleString.of(\"QUEUED\")")), "true");

        cell(seen, wrong, "S7entryGuardAndWriteBranch",
                String.valueOf(countOf(nettyCode, "if(commandHandler.closeAfterReply())")), "2");
        cell(seen, wrong, "S8closeIsOnTheWriteFuture", String.valueOf(countOf(nettyCode,
                "ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)")), "1");
        // 全场只留<em>一处</em>直接的 ctx.close()（那是既有的出错路径，在本卡之后仍是 1）。
        // 摘掉 CLOSE 监听、改成"发完就关"会让这一格变 2 —— 那一形在 q5/q8 上会丢帧，
        // 但"丢哪一帧"取决于时序，所以这里也钉一层。
        cell(seen, wrong, "S9exactlyOneEagerClose", String.valueOf(countOf(nettyCode, "ctx.close()")), "1");
        finish("quitIsOneBranchAheadOfTheThreeGatesAndClosingHappensAfterTheFlush", seen, wrong);
    }

    /** 两道锚点都在代码里出现，且前者在后者之前。任一缺席都是 false（不许"找不到就算通过"）。 */
    private static boolean ahead(String code, String first, String second) {
        int at = code.indexOf(first);
        int than = code.indexOf(second);
        return at >= 0 && than >= 0 && at < than;
    }

    /**
     * 把所有空白抹平再比 —— 结构那几格锚的是"这一处调用存不存在、谁在谁前面"，
     * 改缩进、在 {@code if} 与括号之间补一个空格都不该是契约。不 squeeze 的话，一次纯粹的
     * formatter 跑就会把 S2–S9 全绊红（13w 的 E2 那一课：over-pin）。
     */
    private static String squeeze(String code) {
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    // =================================================================================
    // 格子：发命令读帧 / 读 FIN / 另开一条连接去问
    // =================================================================================

    /**
     * 一步：把 {@code lines} 里的每条命令编码后<em>拼成一次 write</em> 发出去，
     * 再把安静窗口内到达的整段字节切成若干条顶层帧，逐字和原文那一串比。
     *
     * <p>"一次 write"是有意的：q6 量的就是管道里 QUIT 后面那条命令还答不答。</p>
     */
    private static void stepCell(List<String> seen, List<String> wrong, Wire wire, String id, int slot,
                                 String want, String... lines) throws IOException {
        cell(seen, wrong, id, String.valueOf(sendAndRender(wire, slot, lines)), want);
    }

    /** 这一步之后：服务端关了吗。{@code true}/{@code false} 由 {@link #closedNow} 量出来。 */
    private static void closedCell(List<String> seen, List<String> wrong, String id, Wire wire, int slot,
                                   String want) throws IOException {
        cell(seen, wrong, id, String.valueOf(closedNow(wire.connection(slot), 400)), want);
    }

    /** case 跑完、所有命令都发完之后：这条连接<em>仍然</em>是被服务端关着的（中间不靠时序翻盘）。 */
    private static void finalClosedCell(List<String> seen, List<String> wrong, String id, Wire wire, int slot,
                                        String want) throws IOException {
        cell(seen, wrong, id, String.valueOf(closedNow(wire.connection(slot), 200)), want);
    }

    /**
     * probe：另开一条连接去问一句，量"这条连接关掉之后，服务器<em>自己</em>还认不认它" ——
     * {@code GET} 看那笔写落库没有，{@code PUBSUB CHANNELS}/{@code NUMSUB}/{@code NUMPAT}
     * 看订阅被服务端摘掉没有。
     */
    private static void probeCell(List<String> seen, List<String> wrong, Wire wire, String id,
                                  String want, String line) throws IOException {
        cell(seen, wrong, id, String.valueOf(wire.probe(line)), want);
    }

    private static List<String> sendAndRender(Wire wire, int slot, String... lines) throws IOException {
        Socket socket = wire.connection(slot);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (String line : lines) {
            raw.write(encode(line));
        }
        byte[] bytes = raw.toByteArray();
        try {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        } catch (IOException peerGone) {
            // 对端已经关了还写 ⇒ 写不进去。参照那侧同一形记的是 SENDERR + 0 帧，
            // 所以这里如实返回"没读到任何帧"，由格子自己判这是不是期望值。
            return new ArrayList<>();
        }
        return rendered(readUntilQuiet(socket.getInputStream(), socket));
    }

    /** 发一行（空格分参数）→ RESP 数组。 */
    private static byte[] encode(String line) {
        String[] parts = line.split(" ", -1);
        List<String> args = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty() || i > 0) {
                args.add(parts[i]);
            }
        }
        StringBuilder out = new StringBuilder("*" + args.size() + "\r\n");
        for (String arg : args) {
            out.append("$").append(arg.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(arg).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 读到"连续 {@code quietMs} 没有新字节"为止 —— 这一拍同时就是"总共几条帧"的量具。 */
    private static byte[] readUntilQuiet(InputStream in, Socket socket) throws IOException {
        socket.setSoTimeout(QUIET_MS);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        byte[] chunk = new byte[8192];
        try {
            while (System.currentTimeMillis() < deadline) {
                int n = in.read(chunk);
                if (n < 0) {
                    break;
                }
                buf.write(chunk, 0, n);
                deadline = System.currentTimeMillis() + DEADLINE_MS;
            }
        } catch (SocketTimeoutException quiet) {
            // 安静到点了：这就是全部
        } catch (IOException reset) {
            // 对端 RST：已收到的那些字节就是全部，"关没关"由 closedCell 那一格如实记。
        } finally {
            socket.setSoTimeout((int) DEADLINE_MS);
        }
        return buf.toByteArray();
    }

    /**
     * 对端已经 FIN 或 RST ⇒ {@code true}；还开着（读超时）⇒ {@code false}。
     *
     * <p>python 那侧用 {@code recv(1, MSG_PEEK)} 是为了<em>不吃掉</em>一个字节；Java 的
     * {@code Socket} 没有 PEEK，所以调用点必须已经把所有帧读干净（{@link #readUntilQuiet} 就是）。
     * 读到 {@code >=0} 时连接当然还开着，返回 false 也是对的 —— 只是会把那一字节从流里拿走，
     * 下一格 {@code parseAll} 会抛"半截帧"而不是静默误判。</p>
     */
    private static boolean closedNow(Socket socket, int waitMs) throws IOException {
        try {
            socket.setSoTimeout(waitMs);
            return socket.getInputStream().read() < 0;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException reset) {
            return true;
        } finally {
            socket.setSoTimeout((int) DEADLINE_MS);
        }
    }

    // =================================================================================
    // RESP 切帧 + 渲染 —— 与 250 那份量具同一判据
    // =================================================================================

    private static List<String> rendered(byte[] raw) {
        List<String> out = new ArrayList<>();
        for (Object v : parseAll(raw)) {
            out.add(render(v));
        }
        return out;
    }

    private static String render(Object v) {
        if (v == NIL) {
            return "nil";
        }
        if (v == NULL_ARRAY) {
            return "nullarray";
        }
        if (v instanceof List) {
            List<String> parts = new ArrayList<>();
            for (Object item : (List<?>) v) {
                parts.add(render(item));
            }
            return "[" + String.join(",", parts) + "]";
        }
        if (v instanceof Simple) {
            return "simple:" + ((Simple) v).value;
        }
        if (v instanceof Error) {
            return "error:" + ((Error) v).value;
        }
        if (v instanceof String) {
            return "'" + ((String) v).replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        return String.valueOf(v);
    }

    private static final Object NIL = new Object();
    private static final Object NULL_ARRAY = new Object();
    private static final Object NEED_MORE = new Object();

    private static final class Simple {
        private final String value;

        private Simple(String value) {
            this.value = value;
        }
    }

    private static final class Error {
        private final String value;

        private Error(String value) {
            this.value = value;
        }
    }

    private static List<Object> parseAll(byte[] raw) {
        List<Object> out = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < raw.length) {
            Object v = parseValue(raw, pos);
            if (v == NEED_MORE) {
                throw new IllegalStateException("半截帧（量具坏了，不作判定）："
                        + new String(raw, StandardCharsets.UTF_8));
            }
            out.add(v);
        }
        return out;
    }

    private static Object parseValue(byte[] raw, int[] pos) {
        int at = indexOfCrlf(raw, pos[0]);
        if (at < 0) {
            return NEED_MORE;
        }
        String head = new String(raw, pos[0], at - pos[0], StandardCharsets.UTF_8);
        pos[0] = at + 2;
        char tag = head.charAt(0);
        String rest = head.substring(1);
        switch (tag) {
            case '+':
                return new Simple(rest);
            case '-':
                return new Error(rest);
            case ':':
                return Long.valueOf(rest);
            case '$': {
                int len = Integer.parseInt(rest);
                if (len < 0) {
                    return NIL;
                }
                if (pos[0] + len + 2 > raw.length) {
                    return NEED_MORE;
                }
                String body = new String(raw, pos[0], len, StandardCharsets.UTF_8);
                pos[0] += len + 2;
                return body;
            }
            case '*': {
                int n = Integer.parseInt(rest);
                if (n < 0) {
                    return NULL_ARRAY;
                }
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    Object v = parseValue(raw, pos);
                    if (v == NEED_MORE) {
                        return NEED_MORE;
                    }
                    items.add(v);
                }
                return items;
            }
            default:
                throw new IllegalStateException("不是 RESP 首字节 '" + tag + "'：" + head);
        }
    }

    private static int indexOfCrlf(byte[] raw, int from) {
        for (int i = from; i + 1 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    // =================================================================================
    // 记账 —— 一格不成就整支红，红消息点名是哪几格
    // =================================================================================

    private static void cell(List<String> seen, List<String> wrong, String id, String got, String want) {
        seen.add(id);
        if (!got.equals(want)) {
            wrong.add(id + " 期 " + want + " 实 " + got);
        }
    }

    private static void finish(String method, List<String> seen, List<String> wrong) {
        if (wrong.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (String w : wrong) {
            ids.add(w.substring(0, w.indexOf(' ')));
        }
        assertTrue(wrong.isEmpty(), method + " 不合格 " + wrong.size() + "/" + seen.size()
                + " 格：\n  " + String.join("\n  ", wrong)
                + "\n[RED_CELLS=" + String.join("|", ids) + "]"
                + " [RED_ROWS=" + wrong.size() + "/" + seen.size() + "]");
    }

    // =================================================================================
    // 源码上的那几格
    // =================================================================================

    private static int countContaining(List<String> lines, String needle) {
        int n = 0;
        for (String line : lines) {
            if (line.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    /** 整棵 {@code core/src/main} 里命中几次 —— 判"QUIT 没在别处被重新分派吞掉"用的是树，不是单文件。 */
    private static int countInTree(String relativeToRepo, String needle) throws IOException {
        Path root = resolve(relativeToRepo);
        int n = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> all = new ArrayList<>();
            walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(all::add);
            for (Path p : all) {
                n += countOf(new String(Files.readAllBytes(p), StandardCharsets.UTF_8), needle);
            }
        }
        return n;
    }

    private static String readRepoFile(String relativeToRepo) throws IOException {
        return new String(Files.readAllBytes(resolve(relativeToRepo)), StandardCharsets.UTF_8);
    }

    /** 只留代码行（注释与空行剔掉）—— 注释里出现 {@code ctx.close()} 不算它被调了一次。 */
    private static List<String> codeLinesOf(String relativeToRepo) throws IOException {
        Path path = resolve(relativeToRepo);
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
            code.add(line);
        }
        return code;
    }

    private static Path resolve(String relativeToRepo) {
        for (String candidate : new String[]{relativeToRepo, withoutFirstSegment(relativeToRepo)}) {
            if (Files.exists(Paths.get(candidate))) {
                return Paths.get(candidate);
            }
        }
        throw new IllegalStateException("找不到 " + relativeToRepo + "（工作目录 "
                + Paths.get("").toAbsolutePath() + "）—— 量具失效，不作判定");
    }

    private static String withoutFirstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // =================================================================================
    // 一台一次性的服务器
    // =================================================================================

    private static final class Wire implements AutoCloseable {
        private final RedisServer server;
        private final Path dir;
        private final Thread thread;
        private final int port;
        private final Map<Integer, Socket> owned = new LinkedHashMap<>();
        private final List<Socket> probes = new ArrayList<>();

        static Wire open(String password) throws Exception {
            Path dir = Files.createTempDirectory("zcache-quit-close");
            int port = freePort();
            RedisServer server = password == null
                    ? new RedisServer("127.0.0.1", port, 0)
                    : new RedisServer("127.0.0.1", port, 0, password);
            server.setDataDir(dir.toString());
            Thread thread = startAndWait(server, port);
            Wire wire = new Wire(server, dir, thread, port);
            wire.identify(password);
            return wire;
        }

        private Wire(RedisServer server, Path dir, Thread thread, int port) {
            this.server = server;
            this.dir = dir;
            this.thread = thread;
            this.port = port;
        }

        /**
         * 身份核验：端口能被 bind 不等于对面是我们那台。带密码那台还要<em>真的</em>把口令接上了
         * —— 否则整个 auth 那 54 格量的是一台无密码服务器，形状"看着对"其实是 NOAUTH 那一格没生效。
         */
        private void identify(String password) throws IOException {
            String got = String.valueOf(sendAndRender(this, openConn(99),
                    password == null ? "PING" : "SET __who 1"));
            String want = password == null ? "[simple:PONG]" : "[" + NOAUTH + "]";
            if (!got.equals(want)) {
                throw new IllegalStateException("身份核验失败：期望 " + want + " 实 " + got
                        + "（端口 " + port + " 上答话的可能根本不是这台服务器，或口令没接上）");
            }
            closeConn(99);
        }

        /** 新开一条连接并占住这个槽位（每个 case 一条新连接 —— 闸门与订阅态都是按连接算的）。 */
        int openConn(int slot) throws IOException {
            closeConn(slot);
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
            socket.setSoTimeout((int) DEADLINE_MS);
            owned.put(slot, socket);
            return slot;
        }

        Socket connection(int slot) throws IOException {
            Socket socket = owned.get(slot);
            if (socket == null) {
                openConn(slot);
                socket = owned.get(slot);
            }
            return socket;
        }

        private void closeConn(int slot) throws IOException {
            Socket socket = owned.remove(slot);
            if (socket != null) {
                socket.close();
            }
        }

        /** probe：另开一条<em>短命</em>连接去问一句。 */
        List<String> probe(String line) throws IOException {
            Socket socket = new Socket();
            probes.add(socket);
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                socket.setSoTimeout((int) DEADLINE_MS);
                try {
                    socket.getOutputStream().write(encode(line));
                    socket.getOutputStream().flush();
                } catch (IOException peerGone) {
                    return new ArrayList<>();
                }
                return rendered(readUntilQuiet(socket.getInputStream(), socket));
            } finally {
                socket.close();
                probes.remove(socket);
            }
        }

        @Override
        public void close() throws IOException {
            for (Socket s : owned.values()) {
                s.close();
            }
            for (Socket s : new ArrayList<>(probes)) {
                s.close();
            }
            server.stop();
            try {
                thread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            deleteTree(dir);
        }
    }

    /** 深的先删：{@code Files.walk} 是前序，父目录排在子文件之前，顺着删会撞 DirectoryNotEmpty。 */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.forEach(all::add);
        }
        all.sort((a, b) -> b.getNameCount() - a.getNameCount());
        for (Path p : all) {
            Files.deleteIfExists(p);
        }
    }

    private static int freePort() throws IOException {
        for (int tries = 0; tries < PORT_SPAN; tries++) {
            int port = PORT_BASE + PORT_CURSOR.getAndIncrement() % PORT_SPAN;
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress("127.0.0.1", port));
            } catch (IOException taken) {
                continue;
            }
            return port;
        }
        throw new IOException("窗口 " + PORT_BASE + "-" + (PORT_BASE + PORT_SPAN - 1)
                + " 里找不出一枚可 bind 的端口");
    }

    private static Thread startAndWait(RedisServer server, int port) throws Exception {
        Thread thread = new Thread(() -> {
            try {
                server.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, "test-z-cache-quit-close");
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
        throw new IllegalStateException("服务器没在 " + port + " 上起来");
    }
}
