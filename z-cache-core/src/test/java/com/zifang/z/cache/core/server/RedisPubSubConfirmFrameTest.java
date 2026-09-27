package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.server.RedisServer;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡 #42（13w）—— pubsub 那四家"一次给多个名字"的<em>确认帧</em>：一个名字一条独立帧，
 * 计数是"这一手落定之后"的余量；没给名字的退订要逐条回、手上一空还要回那一条 nil。
 *
 * <p>
 * 期望值全部是<em>量出来的原文</em>，不是我对 C 代码的印象：
 * {@code ~/.cache/zcache_gauges/pubsub_frame_mut/ref_pubsub_frames.tr}（250 的一次性实例
 * redis-server 4.0.9，端口 6392，私有 {@code --dir}，跑完即杀）—— 23 个 case / 68 条帧，
 * md5 {@code 50630707a6173a6f1c8ed464bb5a2752}；第二次独立跑（端口 6393）md5
 * {@code 47b467fbf62729c7049d7c702e4fafbc}。两跑之间<em>只</em>在两个格上不同：无名字
 * {@code UNSUBSCRIBE} 那两格（{@code c8}／{@code m4}）里逐条退的那几帧换了先后 ——
 * 上游用的是自己内部表的迭代序，同一份表两跑就会换序，所以这两格按<em>集合</em>判（见 {@code F14}/{@code F16}），
 * 而不是把某一个顺序钉成契约。
 * </p>
 *
 * <p>
 * 上游的判据（5.0.14 权威树，行号现读）：{@code pubsub.c:51-54} 的
 * {@code clientSubscriptionsCount} 是"频道数 + 图案数"，{@code :79-82} 每手各推一条
 * {@code *3}，命令层 {@code :280-281}／{@code :291-292}／{@code :300-301}／{@code :311-312} 是逐名循环；
 * 无名字那一支在 {@code :178-198}（先逐条退本族、本族本来就空才推 {@code :189-195} 那一条 nil）。
 * 改之前这台回的是<em>一条</em>嵌套数组（{@code CommandHandler} 里
 * {@code ch.length==1 ? r[0] : RespArray.of(r)}），任何按帧解析的订阅端会把第 2..N 手读丢；
 * 无名字退订更糟，回的是 {@code *0}。
 * </p>
 *
 * <p>
 * <em>不在本卡</em>：{@code MULTI} 里排队这几家时上游的形状是"第一帧进 EXEC 的数组、其余帧另外推"
 * （原文 {@code m1}/{@code m2}/{@code m5} 三格，68−65=3 条帧的差额就落在这里），我方把 N 帧整体嵌在
 * 数组元素里；以及订阅态下 {@code MULTI} 那句拒绝文案（{@code m4}）。两格各自另开卡跟，本卡的判据
 * 只钉<em>直接命令</em>那一条路，不许拿"MULTI 还没对齐"当这一支不钉的理由。
 * 但 {@code m3}（MULTI 里只有<em>一个</em>名字）在本卡里，见 {@code F19}：上游回
 * {@code [['subscribe','a',1]]}，折叠那一步多包一层就成了三层 —— 那是这次改动<em>自己</em>
 * 造出来的新分歧，不能挂到"MULTI 那一格另开卡"底下。
 * </p>
 */
class RedisPubSubConfirmFrameTest {

    private static final long DEADLINE_MS = 8_000L;
    /** 一次"读到安静为止"的窗口：帧都到了就靠这一拍收尾，所以它同时也是"总共几条帧"的量具。 */
    private static final int QUIET_MS = 250;
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final AtomicInteger PORT_CURSOR = new AtomicInteger();

    // =================================================================================
    // 交付那一半：真起一台，从线上按帧读
    // =================================================================================

    /**
     * 18 个"直接命令"的 case 加 {@code m3}，一格一行期望值，全部由
     * {@code ~/.cache/zcache_gauges/pubsub_frame_mut/gen_java_cells.py} 从两份原文<em>机械生成</em>
     * —— 手抄期望值会编（同一批台账上已经犯过三次），生成器读数原样贴在最后一行注释里。
     * 格名就是"原文 case 名 + 步号"，变异尺在日志里点名的也是它。
     *
     * <p>每个 case 用一条<em>独立连接</em>：250 那份量具本来就是一个 case 一次 {@code connect()}，
     * 而计数读的是"这条连接手上的余量"。共用连接会把上一格的订阅抬进下一格 —— 第一版判据就是这么
     * 红的（{@code c2_sub_two} 期望 1,2,3、实到 2,2,3，红的是我的夹具不是服务器）。</p>
     */
    @Test
    void confirmationsMatchTheReferenceFrames() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        // 阳性对照：后面每一格都靠"把字节切成几条顶层帧 + 渲染成原文那一串"这两把尺，
        // 先量它们自己分不分得开。这几条不成立，后面全是瞎读数。
        cell(seen, wrong, "P0a_frame_count", String.valueOf(rendered(
                "*3\r\n$9\r\nsubscribe\r\n$1\r\na\r\n:1\r\n*3\r\n$9\r\nsubscribe\r\n$1\r\nb\r\n:2\r\n"
                        .getBytes(StandardCharsets.UTF_8))),
                "[['subscribe','a',1], ['subscribe','b',2]]");
        cell(seen, wrong, "P0b_one_nested", String.valueOf(rendered(
                "*2\r\n*3\r\n$9\r\nsubscribe\r\n$1\r\na\r\n:1\r\n*3\r\n$9\r\nsubscribe\r\n$1\r\nb\r\n:2\r\n"
                        .getBytes(StandardCharsets.UTF_8))),
                "[[['subscribe','a',1],['subscribe','b',2]]]");
        // nil 那一手（c9/c10/c18）和空名字那一手（c15）必须在渲染上就分得开，否则两条帧会读成同一条
        cell(seen, wrong, "P0c_nil_vs_empty", String.valueOf(rendered(
                "*3\r\n$11\r\nunsubscribe\r\n$-1\r\n:0\r\n".getBytes(StandardCharsets.UTF_8))),
                "[['unsubscribe',nil,0]]");
        cell(seen, wrong, "P0d_empty_vs_nil", String.valueOf(rendered(
                "*3\r\n$11\r\nunsubscribe\r\n$0\r\n\r\n:0\r\n".getBytes(StandardCharsets.UTF_8))),
                "[['unsubscribe','',0]]");
        // "回一条 nil 帧"和"回空数组 *0"是这一卡改动的正身，量具必须当场分得开
        cell(seen, wrong, "P0g_empty_array", String.valueOf(rendered("*0\r\n".getBytes(StandardCharsets.UTF_8))),
                "[[]]");
        cell(seen, wrong, "P0h_null_array", String.valueOf(rendered("*-1\r\n".getBytes(StandardCharsets.UTF_8))),
                "[nullarray]");
        // 简单串/错误串也要和 bulk 串分开（m3 那两格钉的正是 simple:OK / simple:QUEUED）
        cell(seen, wrong, "P0e_simple", String.valueOf(rendered("+OK\r\n".getBytes(StandardCharsets.UTF_8))),
                "[simple:OK]");
        cell(seen, wrong, "P0f_error", String.valueOf(rendered("-ERR nope\r\n".getBytes(StandardCharsets.UTF_8))),
                "[error:ERR nope]");

        try (Wire wire = Wire.open()) {
            int conn = 0;
            // ---- c1_sub_one ----
            conn++;
            step(seen, wrong, wire, "c1_sub_one#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            // ---- c2_sub_two ----
            conn++;
            step(seen, wrong, wire, "c2_sub_two#1", conn, "SUBSCRIBE a b", "['subscribe','a',1]", "['subscribe','b',2]");
            // ---- c3_sub_three ----
            conn++;
            step(seen, wrong, wire, "c3_sub_three#1", conn, "SUBSCRIBE a b c", "['subscribe','a',1]", "['subscribe','b',2]", "['subscribe','c',3]");
            // ---- c4_psun_three ----
            conn++;
            step(seen, wrong, wire, "c4_psun_three#1", conn, "PSUBSCRIBE news.* ? [a-c]*", "['psubscribe','news.*',1]", "['psubscribe','?',2]", "['psubscribe','[a-c]*',3]");
            // ---- c5_sub_dup ----
            conn++;
            step(seen, wrong, wire, "c5_sub_dup#1", conn, "SUBSCRIBE a a", "['subscribe','a',1]", "['subscribe','a',1]");
            // ---- c6_psub_dup ----
            conn++;
            step(seen, wrong, wire, "c6_psub_dup#1", conn, "PSUBSCRIBE news.* news.*", "['psubscribe','news.*',1]", "['psubscribe','news.*',1]");
            // ---- c7_sub_then_unsub_named ----
            conn++;
            step(seen, wrong, wire, "c7_sub_then_unsub_named#1", conn, "SUBSCRIBE a b", "['subscribe','a',1]", "['subscribe','b',2]");
            step(seen, wrong, wire, "c7_sub_then_unsub_named#2", conn, "UNSUBSCRIBE a", "['unsubscribe','a',1]");
            // ---- c8_unsub_noargs_two ----
            conn++;
            step(seen, wrong, wire, "c8_unsub_noargs_two#1", conn, "SUBSCRIBE a b", "['subscribe','a',1]", "['subscribe','b',2]");
            stepAnyOrder(seen, wrong, wire, "c8_unsub_noargs_two#2", conn, "UNSUBSCRIBE", "unsubscribe",
                    new String[]{"b", "a"}, new String[]{"1", "0"});
            // ---- c9_unsub_noargs_none ----
            conn++;
            step(seen, wrong, wire, "c9_unsub_noargs_none#1", conn, "UNSUBSCRIBE", "['unsubscribe',nil,0]");
            // ---- c10_punsub_noargs_none ----
            conn++;
            step(seen, wrong, wire, "c10_punsub_noargs_none#1", conn, "PUNSUBSCRIBE", "['punsubscribe',nil,0]");
            // ---- c11_unsub_never_subscribed ----
            conn++;
            step(seen, wrong, wire, "c11_unsub_never_subscribed#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "c11_unsub_never_subscribed#2", conn, "UNSUBSCRIBE zz", "['unsubscribe','zz',1]");
            // ---- c12_mix_unsub_all ----
            conn++;
            step(seen, wrong, wire, "c12_mix_unsub_all#1", conn, "SUBSCRIBE a", "['subscribe','a',1]");
            step(seen, wrong, wire, "c12_mix_unsub_all#2", conn, "PSUBSCRIBE b*", "['psubscribe','b*',2]");
            step(seen, wrong, wire, "c12_mix_unsub_all#3", conn, "UNSUBSCRIBE", "['unsubscribe','a',1]");
            // ---- c13_sub_two_then_unsub_two ----
            conn++;
            step(seen, wrong, wire, "c13_sub_two_then_unsub_two#1", conn, "SUBSCRIBE a b", "['subscribe','a',1]", "['subscribe','b',2]");
            step(seen, wrong, wire, "c13_sub_two_then_unsub_two#2", conn, "UNSUBSCRIBE a b", "['unsubscribe','a',1]", "['unsubscribe','b',0]");
            // ---- c14_psub_two_then_punsub_noargs ----
            conn++;
            step(seen, wrong, wire, "c14_psub_two_then_punsub_noargs#1", conn, "PSUBSCRIBE x* y*", "['psubscribe','x*',1]", "['psubscribe','y*',2]");
            stepAnyOrder(seen, wrong, wire, "c14_psub_two_then_punsub_noargs#2", conn, "PUNSUBSCRIBE", "punsubscribe",
                    new String[]{"x*", "y*"}, new String[]{"1", "0"});
            // ---- c15_sub_empty_name ----
            conn++;
            step(seen, wrong, wire, "c15_sub_empty_name#1", conn, "SUBSCRIBE ", "['subscribe','',1]");
            // ---- c16_sub_two_then_psub_one ----
            conn++;
            step(seen, wrong, wire, "c16_sub_two_then_psub_one#1", conn, "SUBSCRIBE a b", "['subscribe','a',1]", "['subscribe','b',2]");
            step(seen, wrong, wire, "c16_sub_two_then_psub_one#2", conn, "PSUBSCRIBE z*", "['psubscribe','z*',3]");
            // ---- c17_unsub_dup_names ----
            conn++;
            step(seen, wrong, wire, "c17_unsub_dup_names#1", conn, "SUBSCRIBE a b", "['subscribe','a',1]", "['subscribe','b',2]");
            step(seen, wrong, wire, "c17_unsub_dup_names#2", conn, "UNSUBSCRIBE a a", "['unsubscribe','a',1]", "['unsubscribe','a',1]");
            // ---- c18_psub_then_unsub_and_punsub ----
            conn++;
            step(seen, wrong, wire, "c18_psub_then_unsub_and_punsub#1", conn, "PSUBSCRIBE x*", "['psubscribe','x*',1]");
            step(seen, wrong, wire, "c18_psub_then_unsub_and_punsub#2", conn, "UNSUBSCRIBE", "['unsubscribe',nil,1]");
            step(seen, wrong, wire, "c18_psub_then_unsub_and_punsub#3", conn, "PUNSUBSCRIBE", "['punsubscribe','x*',0]");
            // ---- m3_multi_sub_one ----
            conn++;
            step(seen, wrong, wire, "m3_multi_sub_one#1", conn, "MULTI", "simple:OK");
            step(seen, wrong, wire, "m3_multi_sub_one#2", conn, "SUBSCRIBE a", "simple:QUEUED");
            step(seen, wrong, wire, "m3_multi_sub_one#3", conn, "EXEC", "[['subscribe','a',1]]");
            // 生成器读数： 19 个 case / 32 格，其中顺序不钉、按多重集判的 2 格
            // ---- 帧数对得上、订阅却<em>没真生效</em>也是缺陷 ----
            // 多名字那几手要真把每一手都登记上：从另一条连接 PUBLISH，第 2..N 手也得收到。
            conn++;
            delivers(seen, wrong, wire, "reg_channel_second_name", conn, "SUBSCRIBE a b", "b", "hello",
                    "['message','b','hello']");
            conn++;
            delivers(seen, wrong, wire, "reg_pattern_second_name", conn, "PSUBSCRIBE news.* sport.*",
                    "sport.today", "goal", "['pmessage','sport.*','sport.today','goal']");
        }
        finish("confirmationsMatchTheReferenceFrames", seen, wrong);
    }

    // =================================================================================
    // 结构那一半：多帧只有一个建造处，展开只有一处，EXEC 那一腿必须折回去
    // =================================================================================

    private static final String HANDLER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java";
    private static final String ENCODER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/protocol/RespEncoder.java";
    private static final String SERVER_HANDLER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/server/RedisServerHandler.java";
    private static final String PUBSUB_MANAGER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/pubsub/PubSubManager.java";

    /**
     * "多帧"与"一条数组"必须在<em>类型</em>上分开，而且展开只许有一处。
     *
     * <p>为什么挂在源码上而不是只跑线上：线上一格 {@code F1} 只问得出"这一条命令回几帧"，问不出
     * "以后谁在 EXEC 元素位置上塞了一条 {@code RespFrames}"—— 那会把外层数组的长度写得对不上号
     * （帧数与元素数分家）。这一支红的方式各自都不是推演：把 {@code handleExec} 里那句折叠删掉
     * ⇒ {@code W3fold} 红；在 {@code RedisServerHandler} 里另加一处 {@code instanceof RespFrames}
     * 展开 ⇒ {@code W2spread} 红；把四个 handler 之一改回 {@code RespArray.of(...)} 自己套 ⇒
     * {@code W1build} 与交付那一半同时红。</p>
     */
    @Test
    void multiFrameRepliesAreBuiltInOnePlaceAndSpreadInOnePlace() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        // 阳性对照：下面的 W1…W5 全是"数出某个定值"，先量同一个计数器<em>数得出非零</em>。
        List<String> prey = Arrays.asList(
                "        return RespFrames.of(frames);",
                "        return RespFrames.of(frames);",
                "        } else if (msg instanceof RespFrames) {",
                "                    if (one instanceof RespFrames) {",
                "        if (pattern) pubSub.psubscribe(channelContext, name);");
        cell(seen, wrong, "W0build", String.valueOf(countContaining(prey, "RespFrames.of(")), "2");
        cell(seen, wrong, "W0spread", String.valueOf(countContaining(prey, "instanceof RespFrames")), "2");
        cell(seen, wrong, "W0call", String.valueOf(countContaining(prey, "psubscribe(")), "1");

        List<String> handler = codeLinesOf(HANDLER);
        cell(seen, wrong, "Whandler", String.valueOf(handler.size() > 2_000), "true");
        // 多帧只有那两个建造处：订阅那一族与退订那一族
        cell(seen, wrong, "W1build", String.valueOf(countContaining(handler, "RespFrames.of(")), "2");
        // 四个 handler 一律转调那两个建造处，一家不许自己拼帧
        for (String[] arm : new String[][]{{"handleSubscribe(", "subscriptionConfirmations("},
                {"handlePsubscribe(", "subscriptionConfirmations("},
                {"handleUnsubscribe(", "cancellationConfirmations("},
                {"handlePunsubscribe(", "cancellationConfirmations("}}) {
            String body = bodyOf(handler, "private Object " + arm[0]);
            cell(seen, wrong, "W1" + arm[0], String.valueOf(body.contains(arm[1])), "true");
            cell(seen, wrong, "W2" + arm[0], String.valueOf(body.contains("RespArray.of(")), "false");
        }
        // 13w 之前那个"单名字平铺、多名字套一层"的三元表达式：一处不剩
        cell(seen, wrong, "Wternary",
                String.valueOf(countContaining(handler, "length==1 ? r[0]")), "0");
        // "这一族没东西可退"那一支要回 nil 帧而不是 *0：两个建造处里不许再出现空数组
        List<String> builders = new ArrayList<>(Arrays.asList(
                bodyOf(handler, "private Object subscriptionConfirmations(").split("\n")));
        builders.addAll(Arrays.asList(
                bodyOf(handler, "private Object cancellationConfirmations(").split("\n")));
        cell(seen, wrong, "Wempty", String.valueOf(countContaining(builders, "RespArray.empty(")), "0");

        // EXEC 那一腿把多帧折回嵌套数组，只此一处
        String exec = bodyOf(handler, "private Object handleExec(");
        cell(seen, wrong, "W3fold", String.valueOf(countContaining(Arrays.asList(exec.split("\n")),
                "instanceof RespFrames")), "1");

        List<String> encoder = codeLinesOf(ENCODER);
        cell(seen, wrong, "W4spread", String.valueOf(countContaining(encoder, "instanceof RespFrames")), "1");
        // 顶层展开：带数组头的那条路（encodeArray）和展开那条路必须分开
        cell(seen, wrong, "W4nolimit", String.valueOf(countContaining(encoder, "RespType.ARRAY.getPrefix()")), "1");
        List<String> serverHandler = codeLinesOf(SERVER_HANDLER);
        cell(seen, wrong, "W2spread", String.valueOf(countContaining(serverHandler, "RespFrames")), "0");

        // 无名字那一支要拿得到<em>名字</em>，只给个数是回不出帧的
        List<String> manager = codeLinesOf(PUBSUB_MANAGER);
        cell(seen, wrong, "W5names", String.valueOf(countContaining(handler, "subscribedChannels(")), "1");
        cell(seen, wrong, "W5pnames", String.valueOf(countContaining(handler, "subscribedPatterns(")), "1");
        // codeLinesOf 会把每一行 trim，所以这里比的是去缩进后的整行
        cell(seen, wrong, "W5api", String.valueOf(manager.contains(
                "public Set<String> subscribedChannels(ChannelHandlerContext ctx) {")), "true");
        cell(seen, wrong, "W5papi", String.valueOf(manager.contains(
                "public Set<String> subscribedPatterns(ChannelHandlerContext ctx) {")), "true");
        // 交出去的是快照副本：调用方要在迭代里逐个退订，直接回内部集合会当场 CME。
        // 锚点只到 `HashSet<>(channels)` 为止，不钉 `new 是哪一种 Set` —— 等价变异 E2
        // （换成 LinkedHashSet，迭代顺序换、语义不换）绊红过一次，钉住写法就是把那条改动禁死。
        cell(seen, wrong, "W5copy", String.valueOf(countContaining(manager, "HashSet<>(channels)")), "1");
        cell(seen, wrong, "W5pcopy", String.valueOf(countContaining(manager, "HashSet<>(patterns)")), "1");
        finish("multiFrameRepliesAreBuiltInOnePlaceAndSpreadInOnePlace", seen, wrong);
    }

    // =================================================================================
    // 打命令、按帧收
    // =================================================================================

    /** 一格：发一行命令，把安静窗口内到达的整段字节切成<em>若干条</em>顶层帧，逐字和原文那一串比。 */
    private static void step(List<String> seen, List<String> wrong, Wire wire, String id, int conn,
                             String line, String... want) throws IOException {
        cell(seen, wrong, id, String.valueOf(sendAndRender(wire, conn, line)),
                String.valueOf(Arrays.asList(want)));
    }

    /**
     * "没给名字的退订"是<em>从我方手上的集合里逐条退</em>的：上游是内部字典、我方是 HashSet，
     * 两边都不把先后当契约（同一份原文两跑就会换序，{@code c8} 那一格就是活证）。
     * 所以顺序不进判据，但"几条帧、哪几个名字、每退完一条还剩多少"三样全要等于原文 ——
     * 名字与计数的<em>配对</em>恰恰是移除顺序的产物，因此也不能比，只能各自比多重集。
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

    /** 确认帧之外还要证明"每一手都真登记了"：从另一条连接 PUBLISH，收得到才算。 */
    private static void delivers(List<String> seen, List<String> wrong, Wire wire, String id, int conn,
                                 String subscribeLine, String channel, String body, String... want)
            throws IOException {
        Socket socket = wire.connection(conn);
        socket.getOutputStream().write(encode(subscribeLine));
        socket.getOutputStream().flush();
        readUntilQuiet(socket.getInputStream(), socket);
        wire.publishFromPool(channel, body);
        cell(seen, wrong, id, String.valueOf(rendered(readUntilQuiet(socket.getInputStream(), socket))),
                String.valueOf(Arrays.asList(want)));
    }
    private static byte[] encode(String line) {
        String[] parts = line.split(" ", -1);
        List<String> args = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty() || args.size() > 0 && p.isEmpty() && parts.length > 1) {
                args.add(p);
            }
        }
        // `SUBSCRIBE `（空名字）那格要留得住那个空参数
        if (args.isEmpty()) {
            args.add(line);
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
    // RESP 切帧 + 渲染 —— 与 250 那份量具同一判据：解析完还剩字节就说明是多条帧
    // =================================================================================

    /** 整段字节 → 每一条顶层帧的渲染串。"几条"和"哪几条"就是本支判据的两把尺。 */
    private static List<String> rendered(byte[] raw) {
        List<String> out = new ArrayList<>();
        for (Object v : parseAll(raw)) {
            out.add(render(v));
        }
        return out;
    }

    /**
     * 渲染成 250 那份量具 {@code render()} 同一种串：数组 {@code [a,b]}、bulk 串带引号、
     * nil 是 {@code nil}、简单串是 {@code simple:OK}、错误是 {@code error:...}、整数裸写。
     * 这样测试里的期望值可以和 {@code ref_pubsub_frames.tr} 的 {@code FRAME} 行逐字对上。
     */
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
            return pyRepr((String) v);
        }
        return String.valueOf(v);
    }

    /** python 的 {@code repr(str)}：单引号包住，反斜杠/引号/控制字符转义。名字中只有 {@code *?[a-c]-} 这些不必转义。 */
    private static String pyRepr(String s) {
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' || ch == '\'') {
                out.append('\\').append(ch);
            } else if (ch == '\n') {
                out.append("\\n");
            } else if (ch == '\r') {
                out.append("\\r");
            } else if (ch == '\t') {
                out.append("\\t");
            } else if (ch < 0x20 || ch == 0x7f) {
                out.append(String.format("\\x%02x", (int) ch));
            } else {
                out.append(ch);
            }
        }
        return out.append('\'').toString();
    }

    /** {@code $-1} —— 和"空 bulk 串"必须是两个值，否则 {@code c9}（nil）会读成 {@code c15}（空名字）。 */
    private static final Object NIL = new Object();
    /** {@code *-1} */
    private static final Object NULL_ARRAY = new Object();

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

    private static final Object NEED_MORE = new Object();

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

    private static int countContaining(List<String> lines, String needle) {
        int n = 0;
        for (String line : lines) {
            if (line.contains(needle)) {
                n++;
            }
        }
        return n;
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

    private static List<String> codeLinesOf(String relativeToRepo) throws IOException {
        Path path = null;
        for (String candidate : new String[]{relativeToRepo, withoutFirstSegment(relativeToRepo)}) {
            if (Files.exists(Paths.get(candidate))) {
                path = Paths.get(candidate);
                break;
            }
        }
        if (path == null) {
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
        private final AtomicInteger tagSeq = new AtomicInteger();

        static Wire open() throws Exception {
            Path dir = Files.createTempDirectory("zcache-pubsub-frames");
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

        /** 身份核验：端口能被 bind 不等于对面是我们那台 —— 先问一句 SET/GET。 */
        private void identify() throws IOException {
            Socket s = connection(99);
            s.getOutputStream().write(encode("SET __who " + tagSeq.incrementAndGet()));
            s.getOutputStream().flush();
            String got = String.valueOf(rendered(readUntilQuiet(s.getInputStream(), s)));
            if (!got.contains("simple:OK")) {
                throw new IllegalStateException("身份核验失败：SET 没回 OK 而是 " + got
                        + "（端口 " + port + " 上答话的可能根本不是这台服务器）");
            }
        }

        Socket connection(int slot) throws IOException {
            // 槽号是<em>键</em>不是下标：用 List 占位的话，一条 PUBLISH 的 98 号槽会顺手开出 98 条连接。
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

        /** 从一个专用槽发 PUBLISH（订阅端自己那条连接正被占用）。 */
        void publishFromPool(String channel, String body) throws IOException {
            Socket s = connection(98);
            s.getOutputStream().write(encode("PUBLISH " + channel + " " + body));
            s.getOutputStream().flush();
            parseAll(readUntilQuiet(s.getInputStream(), s));
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

    /** 深的先删：Files.walk 是前序，父目录排在子文件之前，直接顺着删会撞 DirectoryNotEmpty。 */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
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
        }, "test-z-cache-pubsub-frames");
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
