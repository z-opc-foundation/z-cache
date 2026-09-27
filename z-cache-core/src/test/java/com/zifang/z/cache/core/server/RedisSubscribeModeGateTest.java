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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订阅态那道闸门（卡 #45）—— 白名单外<em>每一家</em>命令的拒绝原文，以及白名单里
 * {@code PING} 那一支的<em>另一种</em>形状。
 *
 * <p>改前有两处病，都在 {@code CommandHandler.handle()} 的那一个 switch 里：
 * ① default 那一支回的是自造文案 {@code ERR Can't execute this command in subscribe mode}；
 * ② {@code PING} 那一支回 {@code +PONG} —— 那是<em>非</em>订阅态的形状。两处都拿真值对过：
 * 上游闸门在 {@code server.c:2727-2733}（放行 ping/(P)SUB/(P)UNSUBSCRIBE 五个 proc，其余一律
 * {@code addReplyError(c,"only (P)SUBSCRIBE / (P)UNSUBSCRIBE / PING / QUIT allowed in this context")}，
 * 句子里<em>不带</em>命令名），{@code PING} 的形状在 {@code pingCommand}（{@code server.c:2959-2975}）：
 * {@code CLIENT_PUBSUB} 那一支回的是<em>两条 bulk 的数组</em>。</p>
 *
 * <p><b>期望值不是推演</b>：下面每一格都由
 * {@code ~/.cache/zcache_gauges/subscribe_gate_mut/gen_gate_cells.py} 从参照原文<em>机械</em>生成
 * （原文四份：250 上一次性 redis 4.0.9 的 {@code ref_gate_a.tr}／{@code ref_gate_b.tr}／
 * {@code ref_gate_c.tr}／{@code ref_gate_d.tr}，端口 6392/6393、私有 {@code --dir}，跑完即 kill 并复验端口已空）。
 * 同一把尺（{@code ref_gate.py} 的 {@code Reader}/渲染）也量了我方改前改后：
 * {@code cmp_gate.py} 读数 {@code TOTAL=32 改前红=11 改后红=0}；四跑之间唯一不稳定的格子是
 * {@code g10_recover#3}（没给名字的退订按内部集合逐条退，参照自己两跑就换序），那一格退化成多重集判。
 * 复算 = {@code bash ~/.cache/zcache_gauges/subscribe_gate_mut/run_before_after.sh}
 * ＋ {@code python3 ~/.cache/zcache_gauges/subscribe_gate_mut/cmp_gate.py}。</p>
 *
 * <p><em>不在本卡</em>：{@code QUIT} 回完 {@code +OK} 之后上游会<em>关连接</em>
 * （{@code server.c:2592-2596} 的 {@code CLIENT_CLOSE_AFTER_REPLY}），我方两跑都是
 * {@code SOCK_CLOSED_BY_SERVER False} —— 帧对了、连接没关，那是另一件事（卡 #50），
 * 所以 {@code g11_quit#2} 只钉帧，不许顺手把"关"写进判据。</p>
 */
class RedisSubscribeModeGateTest {

    private static final long DEADLINE_MS = 8_000L;
    /** 一次"读到安静为止"的窗口：帧都到了就靠这一拍收尾，所以它同时也是"总共几条帧"的量具。 */
    private static final int QUIET_MS = 250;
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final AtomicInteger PORT_CURSOR = new AtomicInteger();

    private static final String UPSTREAM =
            "error:ERR only (P)SUBSCRIBE / (P)UNSUBSCRIBE / PING / QUIT allowed in this context";
    private static final String OLD = "Can't execute this command in subscribe mode";

    private static final String HANDLER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java";
    private static final String CORE_MAIN = "z-cache-core/src/main";

    // =================================================================================
    // 交付那一半：真起一台，从线上按帧读
    // =================================================================================

    /**
     * 白名单外每一家都要回同一串原文；白名单里的 {@code PING} 要回订阅态那一形。
     *
     * <p>为什么一家一家列而不是只测 {@code MULTI}：闸门那句话是 default 那一支产的，
     * 只测 {@code MULTI} 的话"把文案写对"和"给 MULTI 单开一支"两种改法都能绿。七家
     * （{@code MULTI}／{@code SET}／{@code GET}／{@code EXEC}／{@code AUTH}／{@code INFO}
     * ＋图案态的 {@code MULTI}）各自一格，才钉住"这一句是<em>整族</em>的，不是某一家的"。</p>
     */
    @Test
    void everythingOutsideTheWhitelistGetsTheSameUpstreamSentence() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        // 阳性对照：下面每一格都靠"把整段字节切成若干条顶层帧 + 渲染成原文那一串"这两把尺，
        // 先量它们自己分不分得开 —— 尤其"两条 bulk 的数组"和"+PONG"必须一眼就分家。
        cell(seen, wrong, "P0a_ping_two_bulk", String.valueOf(rendered(
                "*2\r\n$4\r\npong\r\n$0\r\n\r\n".getBytes(StandardCharsets.UTF_8))), "[['pong','']]");
        cell(seen, wrong, "P0b_ping_pong", String.valueOf(rendered(
                "+PONG\r\n".getBytes(StandardCharsets.UTF_8))), "[simple:PONG]");
        cell(seen, wrong, "P0c_empty_vs_nil_bulk", String.valueOf(rendered(
                "$-1\r\n$0\r\n\r\n".getBytes(StandardCharsets.UTF_8))),
                String.valueOf(Arrays.asList("nil", "''")));
        cell(seen, wrong, "P0d_error_vs_simple", String.valueOf(rendered(
                ("-ERR nope\r\n+nope\r\n").getBytes(StandardCharsets.UTF_8))),
                String.valueOf(Arrays.asList("error:ERR nope", "simple:nope")));

        try (Wire wire = Wire.open()) {
            int conn = 0;
            // ---- g1_multi_blocked ----
            conn++;
            step(seen, wrong, wire, "g1_multi_blocked#1", conn, "SUBSCRIBE a b", "['subscribe','a',1]", "['subscribe','b',2]");
            step(seen, wrong, wire, "g1_multi_blocked#2", conn, "MULTI", UPSTREAM);
            // ---- g2_set_blocked ----
            conn++;
            step(seen, wrong, wire, "g2_set_blocked#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g2_set_blocked#2", conn, "SET k v", UPSTREAM);
            // ---- g3_get_blocked ----
            conn++;
            step(seen, wrong, wire, "g3_get_blocked#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g3_get_blocked#2", conn, "GET k", UPSTREAM);
            // ---- g4_exec_blocked ----
            conn++;
            step(seen, wrong, wire, "g4_exec_blocked#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g4_exec_blocked#2", conn, "EXEC", UPSTREAM);
            // ---- g5_auth_blocked：闸门在 AUTH 之前，所以订阅态里连密码都问不成 ----
            conn++;
            step(seen, wrong, wire, "g5_auth_blocked#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g5_auth_blocked#2", conn, "AUTH x", UPSTREAM);
            // ---- g6_ping_bare / g7_ping_arg：订阅态里的 PING 是两条 bulk，不是 +PONG ----
            conn++;
            step(seen, wrong, wire, "g6_ping_bare#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g6_ping_bare#2", conn, "PING", "['pong','']");
            conn++;
            step(seen, wrong, wire, "g7_ping_arg#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g7_ping_arg#2", conn, "PING hello", "['pong','hello']");
            // ---- g8_info_blocked ----
            conn++;
            step(seen, wrong, wire, "g8_info_blocked#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g8_info_blocked#2", conn, "INFO server", UPSTREAM);
            // ---- g9_pattern_gate：图案态与频道态共用同一道闸门、同一句 ----
            conn++;
            step(seen, wrong, wire, "g9_pattern_gate#1", conn, "PSUBSCRIBE x*", "['psubscribe','x*',1]");
            step(seen, wrong, wire, "g9_pattern_gate#2", conn, "MULTI", UPSTREAM);
            // ---- g11_quit：帧在这一卡钉，"回完就关"归卡 #50 ----
            conn++;
            step(seen, wrong, wire, "g11_quit#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g11_quit#2", conn, "QUIT", "simple:OK");
            // ---- g12_ping_two_args：白名单不等于免检，arity 照走 ----
            conn++;
            step(seen, wrong, wire, "g12_ping_two_args#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g12_ping_two_args#2", conn, "PING x y",
                    "error:ERR wrong number of arguments for 'ping' command");
            // ---- g13_ping_outside：订阅态那一形不许漏到普通连接上 ----
            conn++;
            step(seen, wrong, wire, "g13_ping_outside#1", conn, "PING", "simple:PONG");
            step(seen, wrong, wire, "g13_ping_outside#2", conn, "PING hello", "'hello'");
            step(seen, wrong, wire, "g13_ping_outside#3", conn, "PING x y",
                    "error:ERR wrong number of arguments for 'ping' command");
            step(seen, wrong, wire, "g13_ping_outside#4", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "g13_ping_outside#5", conn, "UNSUBSCRIBE a", "['unsubscribe','a',0]");
            step(seen, wrong, wire, "g13_ping_outside#6", conn, "PING hello", "'hello'");
            // 生成器读数：13 个 case / 32 格（本支 12 个 case / 28 步，另有 4 格 P0 阳性控制；g10 那 4 步在下一支）
        }
        finish("everythingOutsideTheWhitelistGetsTheSameUpstreamSentence", seen, wrong);
    }

    /**
     * 被闸门挡掉<em>不该</em>把这条连接怎么样：它还得是订阅态，退干净之后才离开。
     *
     * <p>这一格是"拒绝的方式"里唯一行为性的那半（文案只是它的表皮）：{@code g10_recover#3}
     * 要是读不到那两条 {@code unsubscribe} 帧，说明拒绝路径顺手改了订阅集合或把连接作废了。</p>
     */
    @Test
    void rejectionLeavesTheSubscriptionIntact() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        try (Wire wire = Wire.open()) {
            int conn = 1;
            step(seen, wrong, wire, "g10_recover#1", conn, "SUBSCRIBE a b", "['subscribe','a',1]", "['subscribe','b',2]");
            step(seen, wrong, wire, "g10_recover#2", conn, "MULTI", UPSTREAM);
            // g10_recover#3：参照自己两跑就换序 ⇒ 只钉多重集
            stepAnyOrder(seen, wrong, wire, "g10_recover#3", conn, "UNSUBSCRIBE", "unsubscribe",
                    new String[]{"a", "b"}, new String[]{"0", "1"});
            step(seen, wrong, wire, "g10_recover#4", conn, "PING", "simple:PONG");
        }
        finish("rejectionLeavesTheSubscriptionIntact", seen, wrong);
    }

    // =================================================================================
    // 结构那一半：那一句只许有一处，旧文案一处不剩，PING 那一支不许回普通形状
    // =================================================================================

    @Test
    void theGateIsOneSentenceAndPingHasItsOwnSubscribeSideBranch() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        // 阳性对照：下面全是"数出某个定值"，先量同一把计数器<em>数得出非零</em>。
        List<String> prey = Arrays.asList(
                "                    return RespError.of(\"ERR\",",
                "                            \"only (P)SUBSCRIBE / (P)UNSUBSCRIBE / PING / QUIT allowed in this context\");",
                "        if (pubSub != null && channelContext != null && pubSub.isSubscribed(channelContext)) {",
                "                case \"PING\":         return pubSubPing(args);");
        cell(seen, wrong, "Q0count", String.valueOf(countContaining(prey, "only (P)SUBSCRIBE")), "1");
        cell(seen, wrong, "Q0gate", String.valueOf(countContaining(prey, "isSubscribed(")), "1");
        cell(seen, wrong, "Q0ping", String.valueOf(countContaining(prey, "pubSubPing(")), "1");

        List<String> handler = codeLinesOf(HANDLER);
        cell(seen, wrong, "Qhandler", String.valueOf(handler.size() > 2_000), "true");
        String gate = bodyOf(handler, "public Object handle(");
        // 那一句是<em>整族</em>共用的一句，只此一处；锚到前缀为止，不钉它是被拆成几段写的
        cell(seen, wrong, "Q1once", String.valueOf(countContaining(handler, "only (P)SUBSCRIBE")), "1");
        // 拒绝句里不许出现命令名：钉的不是"不许写 `…… + cmd`"这一种拼法 —— 第一版就是这样，
        // 而 A2 那一支写的是 `…… context for " + cmd`，从它底下<em>走过去了</em>（测量轮记下的）。
        // 现在钉的是 default 那一支的回复窗口里 `cmd` 一次都不许出现，与拼法无关。
        String refusal = refusalBranch(gate);
        cell(seen, wrong, "Q1noCmdName", String.valueOf(countOf(refusal, "cmd")), "0");
        // 上面那格的阳性对照：窗口取错（空串、或整段方法体）都会让"cmd 数不出"变成永真 ——
        // 所以①窗口必须正好含住那一句，②同一把尺在闸门体内要数得出 `cmd` 非零。
        cell(seen, wrong, "Q1window", String.valueOf(countOf(refusal, "only (P)SUBSCRIBE")), "1");
        cell(seen, wrong, "Q1cmdPrey", String.valueOf(countOf(stripLineComments(gate), "cmd") >= 1), "true");
        // 自造文案一处不剩（整个 src/main，不止这个文件）
        cell(seen, wrong, "Q2old", String.valueOf(countInTree(CORE_MAIN, OLD)), "0");
        // countInTree 的阳性对照：同一把遍历尺必须数得出<em>新那句</em>非零 —— 否则"旧文案 0 处"
        // 在尺走错目录、一个文件都没打开的时候也是真的 0。
        cell(seen, wrong, "Q2prey", String.valueOf(countInTree(CORE_MAIN, "only (P)SUBSCRIBE")), "1");

        // 白名单里那一家 PING 要转调订阅态那支，闸门体内不许再出现普通形状的 +PONG
        cell(seen, wrong, "Q3ping", String.valueOf(countOf(gate, "pubSubPing(")), "1");
        cell(seen, wrong, "Q3noPong", String.valueOf(countOf(gate, "RespSimpleString.of(\"PONG\")")), "0");
        // 订阅态那一支自己：数组在这一处建、arity 走的是那条归一过的文案
        String ping = bodyOf(handler, "private Object pubSubPing(");
        cell(seen, wrong, "Q4array", String.valueOf(countOf(ping, "RespArray.of(")), "1");
        cell(seen, wrong, "Q4arity", String.valueOf(countOf(ping, "wrongNumberOfArguments(\"PING\")")), "1");
        cell(seen, wrong, "Q4noPong", String.valueOf(countOf(ping, "RespSimpleString")), "0");
        finish("theGateIsOneSentenceAndPingHasItsOwnSubscribeSideBranch", seen, wrong);
    }

    // =================================================================================
    // 打命令、按帧收
    // =================================================================================

    /** 一格：发一行命令，把安静窗口内到达的整段字节切成<em>若干条</em>顶层帧，逐字和原文那一串比。 */
    private static void step(List<String> seen, List<String> wrong, Wire wire, String id, int conn,
                             String line, String... want) throws IOException {
        List<String> got = sendAndRender(wire, conn, line);
        cell(seen, wrong, id, String.valueOf(got), String.valueOf(Arrays.asList(want)));
    }

    /**
     * "没给名字的退订"是从<em>我方手上的集合</em>里逐条退的：参照两跑自己就换序，
     * 所以顺序不进判据；但"几条帧、哪几个名字、每退完一条还剩多少"三样全要等于原文。
     */
    private static void stepAnyOrder(List<String> seen, List<String> wrong, Wire wire, String id, int conn,
                                     String line, String type, String[] wantNames, String[] wantCounts)
            throws IOException {
        List<Object> values = parseAll(send(wire, conn, line));
        List<String> names = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        boolean shape = values.size() == wantNames.length;
        for (Object value : values) {
            if (!(value instanceof List)) {
                shape = false;
                break;
            }
            List<?> frame = (List<?>) value;
            if (frame.size() != 3 || !type.equals(frame.get(0)) || !(frame.get(2) instanceof Long)) {
                shape = false;
                break;
            }
            names.add(String.valueOf(frame.get(1)));
            counts.add(String.valueOf(frame.get(2)));
        }
        Collections.sort(names);
        Collections.sort(counts);
        cell(seen, wrong, id + "_shape", String.valueOf(shape), "true");
        cell(seen, wrong, id + "_names", String.valueOf(names), String.valueOf(sortedCopy(wantNames)));
        cell(seen, wrong, id + "_counts", String.valueOf(counts), String.valueOf(sortedCopy(wantCounts)));
    }

    private static List<String> sortedCopy(String[] in) {
        List<String> out = new ArrayList<>(Arrays.asList(in));
        Collections.sort(out);
        return out;
    }

    private static List<String> sendAndRender(Wire wire, int conn, String line) throws IOException {
        return rendered(send(wire, conn, line));
    }

    /** 发一行、把安静窗口内的整段字节收回来 —— "几帧"就数这一段。 */
    private static byte[] send(Wire wire, int conn, String line) throws IOException {
        Socket socket = wire.connection(conn);
        socket.getOutputStream().write(encode(line));
        socket.getOutputStream().flush();
        return readUntilQuiet(socket.getInputStream(), socket);
    }

    private static byte[] encode(String line) {
        // `PING `（空载荷）那一格要留得住尾部那个空参数，所以按 -1 切且只丢"整行只有一个空串"这一种
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
        } finally {
            socket.setSoTimeout((int) DEADLINE_MS);
        }
        return buf.toByteArray();
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

    /**
     * default 那一支的回复窗口：句子上方最近的 {@code default:} 起，到该句之后的第一个 {@code ;} 止。
     * 句子不在（文案又被换掉了）就返回空串 —— 那一形由 {@code Q1once}/{@code Q2old} 点名，
     * 这一格不抢功，也不许在窗口取错时假装"cmd 没出现"。
     */
    private static String refusalBranch(String gate) {
        String src = stripLineComments(gate);
        int at = src.indexOf("only (P)SUBSCRIBE");
        if (at < 0) {
            return "";
        }
        int from = src.lastIndexOf("default:", at);
        if (from < 0) {
            return "";
        }
        int semi = src.indexOf(';', at);
        return semi < 0 ? src.substring(from) : src.substring(from, semi + 1);
    }

    /** 逐行砍掉 {@code //} 之后的部分 —— 注释里出现"cmd"不该把结构判据绊红。 */
    private static String stripLineComments(String code) {
        StringBuilder out = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            int slash = line.indexOf("//");
            out.append(slash < 0 ? line : line.substring(0, slash)).append('\n');
        }
        return out.toString();
    }

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

    private static Path resolve(String relativeToRepo) {
        for (String candidate : new String[]{relativeToRepo, withoutFirstSegment(relativeToRepo)}) {
            if (Files.exists(Paths.get(candidate))) {
                return Paths.get(candidate);
            }
        }
        throw new IllegalStateException("找不到 " + relativeToRepo + "（工作目录 "
                + Paths.get("").toAbsolutePath() + "）—— 量具失效，不作判定");
    }

    private static String bodyOf(List<String> lines, String signature) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(signature)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalStateException("源码里找不到 " + signature + " —— 量具失效，不作判定");
        }
        StringBuilder body = new StringBuilder();
        int depth = 0;
        boolean seen = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            body.append(line).append('\n');
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                    seen = true;
                } else if (ch == '}') {
                    depth--;
                }
            }
            if (seen && depth == 0) {
                return body.toString();
            }
        }
        throw new IllegalStateException(signature + " 的大括号没配平，取不出函数体");
    }

    /** 只留代码行（注释与空行剔掉）—— 那一句话写进注释里不算它被引用了一次。 */
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

        static Wire open() throws Exception {
            Path dir = Files.createTempDirectory("zcache-subscribe-gate");
            int port = freePort();
            RedisServer server = new RedisServer("127.0.0.1", port, 0);
            server.setDataDir(dir.toString());
            Thread thread = startAndWait(server, port);
            Wire wire = new Wire(server, dir, thread, port);
            wire.identify();
            return wire;
        }

        private Wire(RedisServer server, Path dir, Thread thread, int port) {
            this.server = server;
            this.dir = dir;
            this.thread = thread;
            this.port = port;
        }

        /** 身份核验：端口能被 bind 不等于对面是我们那台 —— 先问一句 SET。 */
        private void identify() throws IOException {
            String got = String.valueOf(sendAndRender(this, 99, "SET __who 1"));
            if (!got.contains("simple:OK")) {
                throw new IllegalStateException("身份核验失败：SET 没回 OK 而是 " + got
                        + "（端口 " + port + " 上答话的可能根本不是这台服务器）");
            }
        }

        Socket connection(int slot) throws IOException {
            Socket socket = owned.get(slot);
            if (socket == null) {
                socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                } catch (IOException e) {
                    socket.close();
                    throw e;
                }
                socket.setSoTimeout((int) DEADLINE_MS);
                owned.put(slot, socket);
            }
            return socket;
        }

        @Override
        public void close() throws IOException {
            for (Socket s : owned.values()) {
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
        }, "test-z-cache-subscribe-gate");
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
