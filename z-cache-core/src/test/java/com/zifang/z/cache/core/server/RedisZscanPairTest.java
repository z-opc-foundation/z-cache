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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ZSCAN} 的负载到底是<em>平铺成员</em>还是 {@code member, score} <em>成对平铺</em>（卡 #43）。
 *
 * <p>改前那半边：{@code SortedSetStore.zscan} 只把 {@code getOrderedMembers()} 里过了 MATCH 的成员
 * append 进结果，分数<em>整个不参与</em>；{@code CommandHandler.handleZscan} 的函数体与
 * {@code handleSscan} <em>逐字相同</em>（只换了 store 那一家）—— 所以线上回的是
 * {@code ['0',['m']]}，而参照回 {@code ['0',['m','1']]}。差的是<em>一整列</em>：
 * 客户端按"两两一组"读会读出一半的成员、还把成员名当分数。</p>
 *
 * <p>上游 5.0.14 那一侧：{@code t_zset.c:3128-3136} 的 {@code zscanCommand} 把 {@code type="zset"}
 * 交给 {@code scanGenericCommand}，成员与分数是在 {@code db.c:565-592} 的 {@code scanCallback} 里
 * <em>append 进同一个列表</em>的两件事（OBJ_ZSET 那一支：先 append 成员，再
 * {@code val = createStringObjectFromLongDouble(...)} 之后 {@code if (val) listAddNodeTail(keys, val)}，
 * {@code db.c:582-591}）⇒ 负载长度天然是成员数的两倍。</p>
 *
 * <p><b>期望值不是推演</b>：下面那段 GEN-ZSCAN-CELLS（119 格）由
 * {@code ~/.cache/zcache_gauges/zscan_pair_mut/gen_zscan_cells.py} 从参照原文<em>机械</em>生成
 * （{@code ref_zscan_probe.tr}：250 上一次性 redis 4.0.9，端口 6391、私有 {@code --dir}，跑完即 kill
 * 并复验 6391-6394 无残留 listener；<b>127.0.0.1:6379 是别人在用的真 redis，没碰过</b>）。
 * 同一把尺（两侧都走 {@code zscan_probe.run}，解析与渲染用两机 md5 逐字相同的
 * {@code ref_frames.py} = {@code 951589fab1e260479231ca1a6d053fff}）也量了我方改前改后：
 * {@code cmp_zscan.py} 读数 {@code TOTAL=126 改前红=34 改后红=0}（其中"改前/改后红的 7 格"是
 * 卡 #52 的选项与游标文法那一族，本轮<em>不判</em>，落 {@code logs/zscan_14a_cmp_ref_vs_ours.txt}）。
 * 复算 = 参照侧 {@code python3 -u zscan_probe.py --port 6391 --out ref_zscan_probe.tr}，我方侧
 * {@code python3 -u ours_zscan.py --out ours_zscan_before.tr}（动生产字节<em>之前</em>）与
 * {@code --out ours_zscan_after.tr}（修完之后），最后 {@code python3 -u cmp_zscan.py}。</p>
 *
 * <p>那 24 个取样分数（含 {@code 1e21} → {@code 1e+21}、{@code inf}/{@code -inf}、
 * 次正规 {@code 4.9406564584124654e-324}、{@code 0.30000000000000004}）在参照上<em>同一格</em>里
 * 与 {@code ZSCORE} 逐字同文，所以我方直接共用 {@code RedisDoubleFormat}
 * （{@code formatBytes} 就是 {@code format} 的 UTF-8 形状，与 ZSCORE 那一条腿同源），不新造一个分数文本。
 * 5.0.14 源码里这两条腿的 fmt 其实不同（{@code util.c:520-557 ld2string} 的
 * {@code humanfriendly=0} 走 {@code %.17Lg}，ZSCORE 那侧 {@code humanfriendly=1} 走
 * {@code %.17Lf} + 去尾零），本卡的 24 个取样值分不出这两条路 —— 记在 CHANGELOG 里，不写成"已证等价"。</p>
 */
class RedisZscanPairTest {

    private static final long DEADLINE_MS = 8_000L;
    private static final int QUIET_MS = 250;
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final AtomicInteger PORT_CURSOR = new AtomicInteger();

    private static final String STORE =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/storage/SortedSetStore.java";
    private static final String HANDLER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java";
    private static final String CORE_MAIN = "z-cache-core/src/main";

    private static final String ZSCAN_BODY = "public Object[] zscan(String key, String cursor, String pattern)";
    private static final String HANDLE_ZSCAN = "private Object handleZscan(String[] args)";
    private static final String HANDLE_SSCAN = "private Object handleSscan(String[] args)";
    private static final String HANDLE_ZSCORE = "private Object handleZscore(String[] args)";

    // =================================================================================
    // 交付那一半：真起一台，把 119 格按参照同一顺序打到线上
    // =================================================================================

    /**
     * 24 个分数值 × (ZADD / ZSCORE / ZSCAN / ZRANGE WITHSCORES) + 30 手形状 case。
     *
     * <p>为什么 ZSCORE 与 ZRANGE WITHSCORES 也在格子里：本卡的断言不是"ZSCAN 自己长得像话"，
     * 而是"同一笔分数在三条腿上<em>同文</em>"。只比 ZSCAN 的话，把分数换成 {@code Double.toString}
     * 也能让 ZSCAN 那一列自洽（改前 ZSCAN 一整列都不在，所以那形看不见）。</p>
     */
    @Test
    void zscanPayloadIsMemberScorePairsLikeTheReference() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        // 阳性对照：这 119 格全靠"把负载渲染成可读文本"这一把尺，先量它对<em>成对</em>这件事
        // 看不看得见 —— 若渲染器把嵌套数组压平，`['0',['m']]` 与 `['0',['m','1']]` 会算同一个串，
        // 那本卡的判据结构上就是瞎的（改前那种"少一整列"恰好是它唯一要分得开的一对）。
        String membersOnly = render(parseFirst("*2\r\n$1\r\n0\r\n*1\r\n$1\r\nm\r\n"));
        String paired = render(parseFirst("*2\r\n$1\r\n0\r\n*2\r\n$1\r\nm\r\n$1\r\n1\r\n"));
        cell(seen, wrong, "P0a_members_only_shape_is_visible", membersOnly, "['0',['m']]");
        cell(seen, wrong, "P0b_paired_shape_is_visible", paired, "['0',['m','1']]");
        cell(seen, wrong, "P0c_the_two_shapes_are_not_the_same", String.valueOf(membersOnly.equals(paired)), "false");
        // 第二条：分数是 bulk 不是整数 —— 渲染器若按 Java 的 Long 打，`'1'` 与 `1` 会混成一形
        // （我方真发成 RESP integer 时，格子该红在"类型"上，而不是"看着差不多"）。
        cell(seen, wrong, "P0d_integer_one_is_not_quoted",
                render(parseFirst("*2\r\n$1\r\n0\r\n*2\r\n$1\r\nm\r\n:1\r\n")), "['0',['m',1]]");

        try (Wire wire = Wire.open()) {
            // ===== GEN-ZSCAN-CELLS（由 gen_zscan_cells.py 从 ref_zscan_probe.tr 生成，119 格，另有 7 手归卡 #52 不判）=====
            cell(seen, wrong, "ZADD zs_v0 1 m#1", wire.ask("ZADD zs_v0 1 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v0 m#1", wire.ask("ZSCORE zs_v0 m"), "'1'");
            cell(seen, wrong, "ZSCAN zs_v0 0#1", wire.ask("ZSCAN zs_v0 0"), "['0',['m','1']]");
            cell(seen, wrong, "ZRANGE zs_v0 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v0 0 -1 WITHSCORES"), "['m','1']");
            cell(seen, wrong, "ZADD zs_v1 1.5 m#1", wire.ask("ZADD zs_v1 1.5 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v1 m#1", wire.ask("ZSCORE zs_v1 m"), "'1.5'");
            cell(seen, wrong, "ZSCAN zs_v1 0#1", wire.ask("ZSCAN zs_v1 0"), "['0',['m','1.5']]");
            cell(seen, wrong, "ZRANGE zs_v1 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v1 0 -1 WITHSCORES"), "['m','1.5']");
            cell(seen, wrong, "ZADD zs_v2 0.1 m#1", wire.ask("ZADD zs_v2 0.1 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v2 m#1", wire.ask("ZSCORE zs_v2 m"), "'0.10000000000000001'");
            cell(seen, wrong, "ZSCAN zs_v2 0#1", wire.ask("ZSCAN zs_v2 0"), "['0',['m','0.10000000000000001']]");
            cell(seen, wrong, "ZRANGE zs_v2 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v2 0 -1 WITHSCORES"), "['m','0.10000000000000001']");
            cell(seen, wrong, "ZADD zs_v3 0.30000000000000004 m#1", wire.ask("ZADD zs_v3 0.30000000000000004 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v3 m#1", wire.ask("ZSCORE zs_v3 m"), "'0.30000000000000004'");
            cell(seen, wrong, "ZSCAN zs_v3 0#1", wire.ask("ZSCAN zs_v3 0"), "['0',['m','0.30000000000000004']]");
            cell(seen, wrong, "ZRANGE zs_v3 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v3 0 -1 WITHSCORES"), "['m','0.30000000000000004']");
            cell(seen, wrong, "ZADD zs_v4 3 m#1", wire.ask("ZADD zs_v4 3 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v4 m#1", wire.ask("ZSCORE zs_v4 m"), "'3'");
            cell(seen, wrong, "ZSCAN zs_v4 0#1", wire.ask("ZSCAN zs_v4 0"), "['0',['m','3']]");
            cell(seen, wrong, "ZRANGE zs_v4 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v4 0 -1 WITHSCORES"), "['m','3']");
            cell(seen, wrong, "ZADD zs_v5 0 m#1", wire.ask("ZADD zs_v5 0 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v5 m#1", wire.ask("ZSCORE zs_v5 m"), "'0'");
            cell(seen, wrong, "ZSCAN zs_v5 0#1", wire.ask("ZSCAN zs_v5 0"), "['0',['m','0']]");
            cell(seen, wrong, "ZRANGE zs_v5 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v5 0 -1 WITHSCORES"), "['m','0']");
            cell(seen, wrong, "ZADD zs_v6 -0 m#1", wire.ask("ZADD zs_v6 -0 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v6 m#1", wire.ask("ZSCORE zs_v6 m"), "'-0'");
            cell(seen, wrong, "ZSCAN zs_v6 0#1", wire.ask("ZSCAN zs_v6 0"), "['0',['m','-0']]");
            cell(seen, wrong, "ZRANGE zs_v6 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v6 0 -1 WITHSCORES"), "['m','-0']");
            cell(seen, wrong, "ZADD zs_v7 -1 m#1", wire.ask("ZADD zs_v7 -1 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v7 m#1", wire.ask("ZSCORE zs_v7 m"), "'-1'");
            cell(seen, wrong, "ZSCAN zs_v7 0#1", wire.ask("ZSCAN zs_v7 0"), "['0',['m','-1']]");
            cell(seen, wrong, "ZRANGE zs_v7 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v7 0 -1 WITHSCORES"), "['m','-1']");
            cell(seen, wrong, "ZADD zs_v8 1e5 m#1", wire.ask("ZADD zs_v8 1e5 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v8 m#1", wire.ask("ZSCORE zs_v8 m"), "'100000'");
            cell(seen, wrong, "ZSCAN zs_v8 0#1", wire.ask("ZSCAN zs_v8 0"), "['0',['m','100000']]");
            cell(seen, wrong, "ZRANGE zs_v8 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v8 0 -1 WITHSCORES"), "['m','100000']");
            cell(seen, wrong, "ZADD zs_v9 100000 m#1", wire.ask("ZADD zs_v9 100000 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v9 m#1", wire.ask("ZSCORE zs_v9 m"), "'100000'");
            cell(seen, wrong, "ZSCAN zs_v9 0#1", wire.ask("ZSCAN zs_v9 0"), "['0',['m','100000']]");
            cell(seen, wrong, "ZRANGE zs_v9 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v9 0 -1 WITHSCORES"), "['m','100000']");
            cell(seen, wrong, "ZADD zs_v10 1e15 m#1", wire.ask("ZADD zs_v10 1e15 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v10 m#1", wire.ask("ZSCORE zs_v10 m"), "'1000000000000000'");
            cell(seen, wrong, "ZSCAN zs_v10 0#1", wire.ask("ZSCAN zs_v10 0"), "['0',['m','1000000000000000']]");
            cell(seen, wrong, "ZRANGE zs_v10 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v10 0 -1 WITHSCORES"), "['m','1000000000000000']");
            cell(seen, wrong, "ZADD zs_v11 1e16 m#1", wire.ask("ZADD zs_v11 1e16 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v11 m#1", wire.ask("ZSCORE zs_v11 m"), "'10000000000000000'");
            cell(seen, wrong, "ZSCAN zs_v11 0#1", wire.ask("ZSCAN zs_v11 0"), "['0',['m','10000000000000000']]");
            cell(seen, wrong, "ZRANGE zs_v11 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v11 0 -1 WITHSCORES"), "['m','10000000000000000']");
            cell(seen, wrong, "ZADD zs_v12 1e17 m#1", wire.ask("ZADD zs_v12 1e17 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v12 m#1", wire.ask("ZSCORE zs_v12 m"), "'1e+17'");
            cell(seen, wrong, "ZSCAN zs_v12 0#1", wire.ask("ZSCAN zs_v12 0"), "['0',['m','1e+17']]");
            cell(seen, wrong, "ZRANGE zs_v12 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v12 0 -1 WITHSCORES"), "['m','1e+17']");
            cell(seen, wrong, "ZADD zs_v13 1e21 m#1", wire.ask("ZADD zs_v13 1e21 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v13 m#1", wire.ask("ZSCORE zs_v13 m"), "'1e+21'");
            cell(seen, wrong, "ZSCAN zs_v13 0#1", wire.ask("ZSCAN zs_v13 0"), "['0',['m','1e+21']]");
            cell(seen, wrong, "ZRANGE zs_v13 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v13 0 -1 WITHSCORES"), "['m','1e+21']");
            cell(seen, wrong, "ZADD zs_v14 -1e21 m#1", wire.ask("ZADD zs_v14 -1e21 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v14 m#1", wire.ask("ZSCORE zs_v14 m"), "'-1e+21'");
            cell(seen, wrong, "ZSCAN zs_v14 0#1", wire.ask("ZSCAN zs_v14 0"), "['0',['m','-1e+21']]");
            cell(seen, wrong, "ZRANGE zs_v14 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v14 0 -1 WITHSCORES"), "['m','-1e+21']");
            cell(seen, wrong, "ZADD zs_v15 1e-7 m#1", wire.ask("ZADD zs_v15 1e-7 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v15 m#1", wire.ask("ZSCORE zs_v15 m"), "'9.9999999999999995e-08'");
            cell(seen, wrong, "ZSCAN zs_v15 0#1", wire.ask("ZSCAN zs_v15 0"), "['0',['m','9.9999999999999995e-08']]");
            cell(seen, wrong, "ZRANGE zs_v15 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v15 0 -1 WITHSCORES"), "['m','9.9999999999999995e-08']");
            cell(seen, wrong, "ZADD zs_v16 1e-21 m#1", wire.ask("ZADD zs_v16 1e-21 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v16 m#1", wire.ask("ZSCORE zs_v16 m"), "'9.9999999999999991e-22'");
            cell(seen, wrong, "ZSCAN zs_v16 0#1", wire.ask("ZSCAN zs_v16 0"), "['0',['m','9.9999999999999991e-22']]");
            cell(seen, wrong, "ZRANGE zs_v16 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v16 0 -1 WITHSCORES"), "['m','9.9999999999999991e-22']");
            cell(seen, wrong, "ZADD zs_v17 9007199254740993 m#1", wire.ask("ZADD zs_v17 9007199254740993 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v17 m#1", wire.ask("ZSCORE zs_v17 m"), "'9007199254740992'");
            cell(seen, wrong, "ZSCAN zs_v17 0#1", wire.ask("ZSCAN zs_v17 0"), "['0',['m','9007199254740992']]");
            cell(seen, wrong, "ZRANGE zs_v17 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v17 0 -1 WITHSCORES"), "['m','9007199254740992']");
            cell(seen, wrong, "ZADD zs_v18 9007199254740992 m#1", wire.ask("ZADD zs_v18 9007199254740992 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v18 m#1", wire.ask("ZSCORE zs_v18 m"), "'9007199254740992'");
            cell(seen, wrong, "ZSCAN zs_v18 0#1", wire.ask("ZSCAN zs_v18 0"), "['0',['m','9007199254740992']]");
            cell(seen, wrong, "ZRANGE zs_v18 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v18 0 -1 WITHSCORES"), "['m','9007199254740992']");
            cell(seen, wrong, "ZADD zs_v19 1.7976931348623157e308 m#1", wire.ask("ZADD zs_v19 1.7976931348623157e308 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v19 m#1", wire.ask("ZSCORE zs_v19 m"), "'1.7976931348623157e+308'");
            cell(seen, wrong, "ZSCAN zs_v19 0#1", wire.ask("ZSCAN zs_v19 0"), "['0',['m','1.7976931348623157e+308']]");
            cell(seen, wrong, "ZRANGE zs_v19 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v19 0 -1 WITHSCORES"), "['m','1.7976931348623157e+308']");
            cell(seen, wrong, "ZADD zs_v20 inf m#1", wire.ask("ZADD zs_v20 inf m"), "1");
            cell(seen, wrong, "ZSCORE zs_v20 m#1", wire.ask("ZSCORE zs_v20 m"), "'inf'");
            cell(seen, wrong, "ZSCAN zs_v20 0#1", wire.ask("ZSCAN zs_v20 0"), "['0',['m','inf']]");
            cell(seen, wrong, "ZRANGE zs_v20 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v20 0 -1 WITHSCORES"), "['m','inf']");
            cell(seen, wrong, "ZADD zs_v21 -inf m#1", wire.ask("ZADD zs_v21 -inf m"), "1");
            cell(seen, wrong, "ZSCORE zs_v21 m#1", wire.ask("ZSCORE zs_v21 m"), "'-inf'");
            cell(seen, wrong, "ZSCAN zs_v21 0#1", wire.ask("ZSCAN zs_v21 0"), "['0',['m','-inf']]");
            cell(seen, wrong, "ZRANGE zs_v21 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v21 0 -1 WITHSCORES"), "['m','-inf']");
            cell(seen, wrong, "ZADD zs_v22 123456789.9 m#1", wire.ask("ZADD zs_v22 123456789.9 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v22 m#1", wire.ask("ZSCORE zs_v22 m"), "'123456789.90000001'");
            cell(seen, wrong, "ZSCAN zs_v22 0#1", wire.ask("ZSCAN zs_v22 0"), "['0',['m','123456789.90000001']]");
            cell(seen, wrong, "ZRANGE zs_v22 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v22 0 -1 WITHSCORES"), "['m','123456789.90000001']");
            cell(seen, wrong, "ZADD zs_v23 5e-324 m#1", wire.ask("ZADD zs_v23 5e-324 m"), "1");
            cell(seen, wrong, "ZSCORE zs_v23 m#1", wire.ask("ZSCORE zs_v23 m"), "'4.9406564584124654e-324'");
            cell(seen, wrong, "ZSCAN zs_v23 0#1", wire.ask("ZSCAN zs_v23 0"), "['0',['m','4.9406564584124654e-324']]");
            cell(seen, wrong, "ZRANGE zs_v23 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_v23 0 -1 WITHSCORES"), "['m','4.9406564584124654e-324']");
            cell(seen, wrong, "ZADD zs_multi 1 a 2 b 3.5 c#1", wire.ask("ZADD zs_multi 1 a 2 b 3.5 c"), "3");
            cell(seen, wrong, "ZSCAN zs_multi 0#1", wire.ask("ZSCAN zs_multi 0"), "['0',['a','1','b','2','c','3.5']]");
            cell(seen, wrong, "ZSCAN zs_multi 0 MATCH a*#1", wire.ask("ZSCAN zs_multi 0 MATCH a*"), "['0',['a','1']]");
            cell(seen, wrong, "ZSCAN zs_multi 0 MATCH *#1", wire.ask("ZSCAN zs_multi 0 MATCH *"), "['0',['a','1','b','2','c','3.5']]");
            cell(seen, wrong, "ZSCAN zs_multi 0 COUNT 2#1", wire.ask("ZSCAN zs_multi 0 COUNT 2"), "['0',['a','1','b','2','c','3.5']]");
            cell(seen, wrong, "ZSCAN zs_multi 0 MATCH b* COUNT 1#1", wire.ask("ZSCAN zs_multi 0 MATCH b* COUNT 1"), "['0',['b','2']]");
            // 不塞格子（卡 #52 那一族）：ZSCAN zs_multi 0 COUNT 0#1 参照回 error:ERR syntax error
            // 不塞格子（卡 #52 那一族）：ZSCAN zs_multi 0 COUNT -1#1 参照回 error:ERR syntax error
            // 不塞格子（卡 #52 那一族）：ZSCAN zs_multi 0 COUNT abc#1 参照回 error:ERR value is not an integer or out of range
            // 不塞格子（卡 #52 那一族）：ZSCAN zs_multi abc#1 参照回 error:ERR invalid cursor
            cell(seen, wrong, "ZSCAN zs_multi -1#1", wire.ask("ZSCAN zs_multi -1"), "['0',['a','1','b','2','c','3.5']]");
            // 不塞格子（卡 #52 那一族）：ZSCAN zs_multi 99999999999999999999999#1 参照回 error:ERR invalid cursor
            // 不塞格子（卡 #52 那一族）：ZSCAN zs_multi 0 MATCH#1 参照回 error:ERR syntax error
            // 不塞格子（卡 #52 那一族）：ZSCAN zs_multi 0 COUNT 2 EXTRA#1 参照回 error:ERR syntax error
            cell(seen, wrong, "ZSCAN zs_nosuch 0#1", wire.ask("ZSCAN zs_nosuch 0"), "['0',[]]");
            cell(seen, wrong, "ZSCAN zs_str#1", wire.ask("ZSCAN zs_str"), "error:ERR wrong number of arguments for 'zscan' command");
            cell(seen, wrong, "SET zs_str v#1", wire.ask("SET zs_str v"), "simple:OK");
            cell(seen, wrong, "ZSCAN zs_str 0#1", wire.ask("ZSCAN zs_str 0"), "error:WRONGTYPE Operation against a key holding the wrong kind of value");
            cell(seen, wrong, "ZADD zs_utf 1 中文 2 ké#1", wire.ask("ZADD zs_utf 1 中文 2 ké"), "2");
            cell(seen, wrong, "ZSCAN zs_utf 0#1", wire.ask("ZSCAN zs_utf 0"), "['0',['中文','1','ké','2']]");
            cell(seen, wrong, "ZADD zs_glob 1 a*b 2 a?b 3 a[b#1", wire.ask("ZADD zs_glob 1 a*b 2 a?b 3 a[b"), "3");
            cell(seen, wrong, "ZSCAN zs_glob 0 MATCH a*b#1", wire.ask("ZSCAN zs_glob 0 MATCH a*b"), "['0',['a*b','1','a?b','2','a[b','3']]");
            cell(seen, wrong, "ZADD zs_inf inf p -inf q#1", wire.ask("ZADD zs_inf inf p -inf q"), "2");
            cell(seen, wrong, "ZSCAN zs_inf 0#1", wire.ask("ZSCAN zs_inf 0"), "['0',['q','-inf','p','inf']]");
            cell(seen, wrong, "ZSCORE zs_inf p#1", wire.ask("ZSCORE zs_inf p"), "'inf'");
            cell(seen, wrong, "ZRANGE zs_inf 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_inf 0 -1 WITHSCORES"), "['q','-inf','p','inf']");
            cell(seen, wrong, "ZADD zs_dup 1e21 big#1", wire.ask("ZADD zs_dup 1e21 big"), "1");
            cell(seen, wrong, "ZSCAN zs_dup 0#1", wire.ask("ZSCAN zs_dup 0"), "['0',['big','1e+21']]");
            cell(seen, wrong, "ZSCORE zs_dup big#1", wire.ask("ZSCORE zs_dup big"), "'1e+21'");
            cell(seen, wrong, "ZRANGE zs_dup 0 -1 WITHSCORES#1", wire.ask("ZRANGE zs_dup 0 -1 WITHSCORES"), "['big','1e+21']");
            // ===== END-GEN-ZSCAN-CELLS =====
        }
        finish("zscanPayloadIsMemberScorePairsLikeTheReference", seen, wrong);
    }

    // =================================================================================
    // 源码上那几格：成对这件事住在哪一层
    // =================================================================================

    /**
     * 线上格子能证明"答案对了"，证不了"对在哪一层"。本卡改前有两条会<em>同时</em>答对的路：
     * 一条在 store 里把分数 append 进同一个列表（上游的形状），一条在命令层把 store 的返回值
     * 再 zip 一次（仓里 {@code zrange}/{@code *RANGE WITHSCORES} 那几条腿的做法）。后者会让
     * {@code SCAN} 家族的四家（HSCAN/SSCAN/ZSCAN）形状各长一样、且下一次有人重排 store 的返回就崩，
     * 所以这里钉住：分数文本只在 {@code SortedSetStore.zscan} 里进负载，命令层不参与算分。
     */
    @Test
    void thePairingIsBuiltInTheStoreAndUsesTheSharedScoreFormatter() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        String store = readRepoFile(STORE);
        String handler = readRepoFile(HANDLER);
        String zscan = withoutComments(methodBody(store, ZSCAN_BODY));
        String handleZscan = withoutComments(methodBody(handler, HANDLE_ZSCAN));
        String handleSscan = withoutComments(methodBody(handler, HANDLE_SSCAN));
        String handleZscore = withoutComments(methodBody(handler, HANDLE_ZSCORE));

        // 阳性对照：切片尺必须真能把一个方法从整份文件里切出来 —— 同一把尺切 handleSscan
        // 时 `RedisDoubleFormat.formatBytes(` 命中 0（它压根没分数），切 handleZscan 时
        // `.zscan(` 命中 1。两形必须分家，否则"命中 N 次"这些读数全是"整份文件都有"的假话。
        cell(seen, wrong, "S0a_slice_is_not_the_whole_file_sstore",
                String.valueOf(countOf(zscan, "getOrderedMembers")), "1");
        cell(seen, wrong, "S0b_slice_of_sscan_has_no_score_text",
                String.valueOf(countOf(handleSscan, "RedisDoubleFormat.formatBytes(")), "0");
        cell(seen, wrong, "S0c_slice_of_handleZscan_calls_the_store",
                String.valueOf(countOf(handleZscan, ".zscan(")), "1");
        cell(seen, wrong, "S0d_needle_is_findable_when_present",
                String.valueOf(countOf(zscan, "RedisDoubleFormat.formatBytes(") > 0), "true");

        cell(seen, wrong, "S1_score_text_enters_the_payload_once_per_member",
                String.valueOf(countOf(zscan, "RedisDoubleFormat.formatBytes(")), "1");
        cell(seen, wrong, "S2_payload_gets_two_adds_per_member",
                String.valueOf(countOf(zscan, "result.add(")), "2");
        // S2 的尺子范围对照：整份 store 里 `result.add(` 远不止两格，切片必须窄于全文件。
        cell(seen, wrong, "S3_whole_file_has_far_more_adds_than_the_slice",
                String.valueOf(countOf(store, "result.add(") > 2 && countOf(zscan, "result.add(") < countOf(store, "result.add(")), "true");
        // 钉"分数是按<em>这个成员</em>取的"，但不钉它写法（`memberScores.get(member)` 换成
        // 先取本地变量 `double score = data.memberScores.get(member)` 仍是 1 次命中 ⇒ 等价重构不绊红）。
        cell(seen, wrong, "S4_score_is_read_from_the_member_table",
                String.valueOf(countOf(zscan, "memberScores")), "1");
        cell(seen, wrong, "S5_command_layer_does_not_recompute_scores",
                String.valueOf(countOf(handleZscan, "memberScores")), "0");
        // S5 是负向断言 ⇒ 配阳性对照：同一根 needle 在 store 那份字节里命中很多（不是"这词全仓不存在"）。
        cell(seen, wrong, "S6_memberScores_is_a_real_symbol",
                String.valueOf(countOf(store, "memberScores") > 5), "true");
        cell(seen, wrong, "S7_zscan_leg_shares_the_zscore_formatter",
                String.valueOf(countOf(handleZscore, "RedisDoubleFormat.format(")), "1");
        // 只有命令层这一处会答 ZSCAN：别处（集群/代理/内部分派）再长出一份旧形状，线上格子看不见。
        cell(seen, wrong, "S9_exactly_one_zscan_call_site_in_core_main",
                String.valueOf(countInTree(CORE_MAIN, ".zscan(")), "1");
        cell(seen, wrong, "S10_tree_scanner_is_not_blind",
                String.valueOf(countInTree(CORE_MAIN, "getSortedSetStore(") > 20), "true");

        finish("thePairingIsBuiltInTheStoreAndUsesTheSharedScoreFormatter", seen, wrong);
    }

    // =================================================================================
    // RESP 切帧 + 渲染 —— 与 250 那份量具同一判据
    // =================================================================================

    private static Object parseFirst(String raw) {
        List<Object> all = parseAll(raw.getBytes(StandardCharsets.UTF_8));
        if (all.size() != 1) {
            throw new IllegalStateException("夹具里塞了 " + all.size() + " 条顶层帧，本判据一次只喂一条");
        }
        return all.get(0);
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

    /** 发一行（空格分参数）→ RESP 数组。本卡的参数里没有一个空格，也没有引号。 */
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
            // 对端 RST：已收到的那些字节就是全部，格子会如实记成"读不出期望那一形"。
        } finally {
            socket.setSoTimeout((int) DEADLINE_MS);
        }
        return buf.toByteArray();
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

    /**
     * 注释行整行剔掉（{@code //} 与块注释）。
     *
     * <p>本卡的 S1/S2/S4/S5 数的是<em>代码</em>里的命中次数：{@code SortedSetStore.zscan} 里那段
     * 上游出处的注释写了 {@code createStringObjectFromLongDouble} 与 {@code listAddNodeTail}，
     * 若哪天有人把 {@code RedisDoubleFormat.formatBytes(} 也抄进注释，"命中 1 次"就会读成 2 次 ——
     * 那时红的是一格<em>不存在</em>的缺陷。与 {@code RedisQuitCloseTest.codeLinesOf} 同一判据。</p>
     */
    private static String withoutComments(String body) {
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        for (String raw : body.split("\n", -1)) {
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
            if (line.startsWith("//") || line.startsWith("*")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    /** 整棵 {@code core/src/main} 里命中几次 —— 判"ZSCAN 没有第二个答话人"用的是树，不是单文件。 */
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

    /**
     * 签名 → 该方法体（含首尾大括号之间的原文）。
     *
     * <p>大括号按<em>字面</em>配平，不做词法分析：本卡要切的三处（{@code SortedSetStore.zscan}、
     * {@code handleZscan}/{@code handleSscan}/{@code handleZscore}）里，字符串与注释都没有大括号，
     * 而 {@code new Object[]{"0", result} 这种是真配对的。签名必须<em>唯一</em>，切不出闭合也当场抛
     * —— 宁可量具坏在手里，不能让它悄悄把整份文件当方法体（那会把 S1/S2/S4 三格全读成假话）。</p>
     */
    private static String methodBody(String text, String signature) {
        int hit = text.indexOf(signature);
        if (hit < 0 || text.indexOf(signature, hit + 1) >= 0) {
            throw new IllegalStateException("签名不唯一或找不到：" + signature + "（命中 "
                    + (hit < 0 ? 0 : 1 + text.indexOf(signature, hit + 1)) + " 处起点）—— 量具失效，不作判定");
        }
        int open = text.indexOf('{', hit);
        if (open < 0) {
            throw new IllegalStateException("签名后找不到左大括号：" + signature);
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(open + 1, i);
                }
            }
        }
        throw new IllegalStateException("方法体不闭合：" + signature);
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
        private Socket conn;

        static Wire open() throws Exception {
            Path dir = Files.createTempDirectory("zcache-zscan-pair");
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

        /** 身份核验：端口能被 bind 不等于对面是我们那台（与 13z 那支同一判据）。 */
        private void identify() throws IOException {
            String got = ask("PING");
            if (!got.equals("simple:PONG")) {
                throw new IllegalStateException("身份核验失败：期望 simple:PONG 实 " + got
                        + "（端口 " + port + " 上答话的可能根本不是这台服务器）");
            }
        }

        /**
         * 一条命令、一条顶层回复、渲染成参照那把尺的文本。
         *
         * <p>多条帧不静默压成一串：本卡每一格在参照侧都只有<em>一条</em>顶层回复（{@code zscan_probe}
         * 是一问一答打出来的），真出现多条就是形状已经和参照分家，得让它红在"帧数"上而不是被拼起来
         * 冒充一条 —— 所以这里抛，不 join。</p>
         */
        String ask(String line) throws IOException {
            Socket socket = connection();
            try {
                socket.getOutputStream().write(encode(line));
                socket.getOutputStream().flush();
            } catch (IOException peerGone) {
                throw new IllegalStateException("写不进 " + line + "：对端已经关了这条连接", peerGone);
            }
            List<Object> values = parseAll(readUntilQuiet(socket.getInputStream(), socket));
            if (values.size() != 1) {
                List<String> parts = new ArrayList<>();
                for (Object v : values) {
                    parts.add(render(v));
                }
                throw new IllegalStateException(line + " 回了 " + values.size() + " 条顶层帧："
                        + String.join(" | ", parts) + " —— 本卡每一格在参照侧都只有一条");
            }
            return render(values.get(0));
        }

        private Socket connection() throws IOException {
            if (conn == null || conn.isClosed()) {
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                } catch (IOException e) {
                    socket.close();
                    throw e;
                }
                socket.setSoTimeout((int) DEADLINE_MS);
                conn = socket;
            }
            return conn;
        }

        @Override
        public void close() throws IOException {
            if (conn != null) {
                conn.close();
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
        }, "test-z-cache-zscan-pair");
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
