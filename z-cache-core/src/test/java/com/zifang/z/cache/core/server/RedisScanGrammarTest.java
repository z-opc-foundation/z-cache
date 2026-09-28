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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SCAN / HSCAN / SSCAN / ZSCAN} 四家的<em>文法</em>：游标那枚参数收哪些文本，
 * 尾巴上的 {@code COUNT}/{@code MATCH}/认不得的东西怎么处理（卡 #52）。
 *
 * <p>改前那半边：四家各有半套、且都是"认不得就放过"。{@code handleScan} 拿
 * {@code RedisIntegerFormat}（<em>有符号</em> LONG_MAX 那把尺，13y 接的）当游标尺，于是
 * {@code ""}、{@code "+1"}、{@code "18446744073709551615"} 三枚合法游标被拒成 invalid cursor；
 * {@code handleHscan}/{@code handleSscan}/{@code handleZscan} 更薄 —— 游标<em>一个字都不判</em>
 * （坏游标静默当 0），选项那一栏只挑 {@code MATCH}，{@code COUNT 0}、{@code COUNT -1}、
 * {@code COUNT abc}、光杆 {@code MATCH}、尾巴上的 {@code EXTRA} <em>全部静默吞掉</em>，
 * 回的是正常形状的结果。同一台参照在<em>同一格</em>上回的是 {@code ERR invalid cursor} 或
 * {@code ERR syntax error} —— 线上对客户端说"你这条命令我没看懂"这件事，改前三家从来没做过。</p>
 *
 * <p>上游 5.0.14 那一侧四家跑的是<em>同一段</em>代码：{@code parseScanCursorOrReply}
 * （{@code db.c:598-611}）在 {@code :604} 走无符号的 {@code strtoul(o->ptr,&eptr,10)}，
 * {@code :605} 三条判据 {@code isspace(ptr[0]) || eptr[0]!='\0' || errno==ERANGE} ⇒
 * {@code :607} 回 {@code "invalid cursor"}；游标那一关之后才进 {@code scanGenericCommand} 的
 * "Step 1: Parse options"（{@code db.c:642-670}）—— {@code COUNT} 要带值（{@code :644} 的
 * {@code j >= 2}）、要过 {@code getLongFromObjectOrReply}（{@code :645}）、还要过
 * {@code count < 1}（{@code :651-653}，那一支回 <em>syntax error</em> 而不是 out of range）；
 * {@code MATCH} 要带值（{@code :657}）；其余任何尾巴都是 syntax error（{@code :666-668}）。
 * 四家的起始下标由 {@code :639} 的 {@code i = (o == NULL) ? 2 : 3} 定；"游标先判"这件事钉在
 * {@code scanCommand}（{@code db.c:804}）与 {@code hscanCommand}/{@code sscanCommand}/
 * {@code zscanCommand}（{@code t_hash.c:830}／{@code t_set.c:1114}／{@code t_zset.c:3132}）。</p>
 *
 * <p><b>期望值不是推演</b>：下面那段 GEN-GRAMMAR-CELLS（178 格）由
 * {@code ~/.cache/zcache_gauges/scan_grammar_mut/gen_grammar_cells.py} 从参照原文
 * {@code ref_grammar.tr}（250 上一次性 redis-server 4.0.9，端口 6391、私有 {@code --dir
 * ~/zcache-250t/scan_grammar_6391}，跑完即 kill 并复验 6391-6394 无 listener，
 * {@code run_id=1de76060d5bc5407ef177568ef1c3cde0df99d5a}；<b>127.0.0.1:6379 是别人在用的真
 * redis，没碰过</b>）<em>机械</em>生成，一格期望值都没人敲过。同一把分类尺（{@code cmp_grammar.py}；
 * 两侧解析走两机 md5 逐字相同的 {@code ref_frames.py} = {@code 951589fab1e260479231ca1a6d053fff}）
 * 也量了我方改前改后，读数 {@code 总=178 E=88 F=40 P=50 改前红=73 改后红=0 半截帧=0/0}，落
 * {@code ~/.cache/zcache_gauges/logs/scan_14b_cmp_ref_vs_ours.txt}。复算三连：参照侧
 * {@code python3 -u scan_grammar_probe.py --port 6391 --out ref_grammar.tr}，我方侧
 * {@code python3 -u ours_scan_grammar.py --out ours_grammar_before.tr}（动生产字节之前）与
 * {@code --out ours_grammar_after.tr}（修完之后），最后 {@code python3 -u cmp_grammar.py
 * ours_grammar_after.tr}。</p>
 *
 * <p><b>三形分档，分母由参照的回答决定</b>（不是由"这张卡该判什么"决定）：
 * {@code E} 参照回 error ⇒ 整条错误文案逐字（88 格，文法这一族本就该逐字对）；
 * {@code F} 参照回游标 {@code '0'} <em>且</em>输入游标文本的 strtoul 值为 0 ⇒ 游标加
 * <em>排序后</em>的负载（40 格；顺序不能比 —— 同一台参照上 {@code SMEMBERS g_set} 给 a,b,c 而
 * {@code SSCAN g_set 0} 给 a,c,b，那是 dict 的桶序不是承诺）；{@code P} 其余 ⇒ <em>只比形状</em>
 * {@code *2}[bulk,array]（50 格，含 10 手建库与 4 手阳性对照）。P 那一档要的是"从第 N 号桶接着扫"
 * 与 {@code COUNT} 当提示那套内部语义，我方三家带 key 的 store 现在<em>根本不读游标</em>
 * （恒回游标 {@code "0"}）—— 那是卡 #53 的活，本卡不冒充覆盖它。</p>
 *
 * <p><b>格名里没有空白</b>：这一卡的参数里有空串、tab、NBSP、前后带空格的游标，名字先把它们换成
 * 可见标记（空格→S、tab→T、NBSP→N、空串→EMP），{@code finish} 又按 {@code " 期 "} 切 ——
 * 14a 那把尺是 {@code substring(0, indexOf(' '))}，它交付那一支的逐格对表在 measure 日志里
 * 塌成了 {@code ["ZSCAN"]} 一格（同名全被截成第一个词）。</p>
 */
class RedisScanGrammarTest {

    private static final long DEADLINE_MS = 8_000L;
    private static final int QUIET_MS = 250;
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final AtomicInteger PORT_CURSOR = new AtomicInteger();

    private static final String HANDLER =
            "z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java";
    private static final String CORE_MAIN = "z-cache-core/src/main";

    private static final String HANDLE_SCAN = "private Object handleScan(String[] args)";
    private static final String HANDLE_HSCAN = "private Object handleHscan(String[] args)";
    private static final String HANDLE_SSCAN = "private Object handleSscan(String[] args)";
    private static final String HANDLE_ZSCAN = "private Object handleZscan(String[] args)";
    private static final String CURSOR_LEG = "private static boolean scanCursorAccepted(String text)";
    private static final String OPTION_LEG = "private static ScanOptions scanOptions(String[] args, int from)";

    private static final String NBSP = "\u00a0";   // 源码里写转义不写裸字符：裸 NBSP 在 diff 里看不见

    // =================================================================================
    // 交付那一半：真起一台，把 178 格按参照同一顺序打到线上
    // =================================================================================

    /** 四家 × (18 枚游标文本 + 20 串选项尾巴 + 3 手"哪一关先响") + 建库与阳性对照 = 178 格。 */
    @Test
    void theFourScanCommandsJudgeCursorAndOptionsLikeTheReference() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        // 阳性对照第一组：形状尺必须分得开"scan 回复那一形"与别的数组。P 档那 50 格全靠它，
        // 若 `*3` 或 `*2`[bulk,bulk] 也被读成 array2，"形状对了"这句话就退化成"回了点东西"。
        cell(seen, wrong, "P0a_scan_reply_shape_is_array2",
                shapeOf(parseFirst("*2\r\n$1\r\n0\r\n*1\r\n$1\r\na\r\n")), "array2");
        cell(seen, wrong, "P0b_three_bulks_are_not_a_scan_reply",
                shapeOf(parseFirst("*3\r\n$1\r\n0\r\n$1\r\na\r\n$1\r\nb\r\n")), "array");
        cell(seen, wrong, "P0c_two_bulks_are_not_a_scan_reply",
                shapeOf(parseFirst("*2\r\n$1\r\n0\r\n$1\r\na\r\n")), "array");
        // P0d 记的是 P 档<em>故意不管</em>的那一半：负载里成没成对不在本卡分子（那是 14a 的判据）。
        cell(seen, wrong, "P0d_array2_says_nothing_about_the_payload",
                shapeOf(parseFirst("*2\r\n$1\r\n0\r\n*2\r\n$1\r\nm\r\n$1\r\n1\r\n")), "array2");

        // 阳性对照第二组：编码器必须按<em>长度</em>分帧。14a 那把 `ask(一行，空格切)` 的尺送不出
        // 这一卡的参数（`SCAN  0` 会被切成三枚参数），所以钉死三条：空串是一枚 $0 参数、前导空格
        // 不切参数、bulk 长度按 UTF-8 <em>字节</em>数（按码元数写的那条形如 `$2` ⇒ 整条 22 字节，
        // 服务端收到的就不是这枚 NBSP 游标，格子会假绿在另一形上）。
        cell(seen, wrong, "P0e_empty_argument_is_a_zero_length_bulk",
                new String(encodeArgs("ECHO", ""), StandardCharsets.UTF_8), "*2\r\n$4\r\nECHO\r\n$0\r\n\r\n");
        cell(seen, wrong, "P0f_leading_space_stays_inside_one_argument",
                new String(encodeArgs("SCAN", " 0"), StandardCharsets.UTF_8), "*2\r\n$4\r\nSCAN\r\n$2\r\n 0\r\n");
        cell(seen, wrong, "P0g_bulk_length_counts_utf8_bytes_not_chars",
                String.valueOf(encodeArgs("ECHO", NBSP + "0").length), "23");

        // 阳性对照第三组：规范形这把尺。<em>40 格 F 档全走它</em>，所以它坏一次就是 40 格假绿。
        // 这里钉的是"成对这件事在尺上看得见" —— 14a 那支判的是服务端有没有发对，本支判的是
        // 这把尺<em>读不读得出</em>发错（那两支各抓一类：只看线上的格子永远不知道尺是不是瞎的）。
        String pair_order = "*2\r\n$1\r\n0\r\n*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$1\r\n2\r\n";
        String bucket_shift = "*2\r\n$1\r\n0\r\n*4\r\n$1\r\nb\r\n$1\r\n2\r\n$1\r\na\r\n$1\r\n1\r\n";
        String value_first = "*2\r\n$1\r\n0\r\n*4\r\n$1\r\n1\r\n$1\r\na\r\n$1\r\n2\r\n$1\r\nb\r\n";
        cell(seen, wrong, "P0j_pairs_keep_the_within_pair_order",
                canonical(parseFirst(pair_order), "pairs"), "['0',['a','1','b','2']]");
        // 桶序不判：同一批对、换了服务端给的次序，规范形必须一样。
        cell(seen, wrong, "P0k_pairs_tolerate_the_server_order",
                canonical(parseFirst(bucket_shift), "pairs"), canonical(parseFirst(pair_order), "pairs"));
        // 这两格是那 40 格的<em>牙</em>：值在前那一形必须被读成"不一样"，而平铺那一档读成"一样"。
        // 后者不是缺陷，是把 flat 档的<em>已知盲区</em>钉在明处 —— SCAN/SSCAN 的负载没有对语义，
        // 一旦哪天有人把 pairs 全改成 flat，P0m 会红、而 P0j/P0k 仍绿（那一改动只在这两格现形）。
        cell(seen, wrong, "P0m_pairs_sees_a_swapped_pair",
                String.valueOf(canonical(parseFirst(value_first), "pairs")
                        .equals(canonical(parseFirst(pair_order), "pairs"))), "false");
        cell(seen, wrong, "P0n_flat_collapses_a_swapped_pair",
                String.valueOf(canonical(parseFirst(value_first), "flat")
                        .equals(canonical(parseFirst(pair_order), "flat"))), "true");
        cell(seen, wrong, "P0o_flat_sorts_names",
                canonical(parseFirst("*2\r\n$1\r\n0\r\n*2\r\n$1\r\nb\r\n$1\r\na\r\n"), "flat"),
                "['0',['a','b']]");
        // 三形坏输入必须抛（不抛就是"悄悄渲染成一个看起来合法的串"）：奇数长度的 pairs 负载、
        // 不认识的档位名、负载里的非 bulk 项，外加"喂进来的根本不是 scan 那一形"。
        cell(seen, wrong, "P0p_odd_pairs_payload_fails_loudly", howCanonicalFails(
                "*2\r\n$1\r\n0\r\n*3\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n", "pairs"), "threw");
        cell(seen, wrong, "P0q_unknown_mode_fails_loudly",
                howCanonicalFails("*2\r\n$1\r\n0\r\n*0\r\n", "pris"), "threw");
        cell(seen, wrong, "P0r_non_bulk_payload_fails_loudly", howCanonicalFails(
                "*2\r\n$1\r\n0\r\n*1\r\n:7\r\n", "flat"), "threw");
        cell(seen, wrong, "P0s_a_lone_bulk_is_not_a_scan_reply",
                howCanonicalFails("*1\r\n$1\r\na\r\n", "flat"), "threw");

        try (Wire wire = Wire.open()) {
            // 同一条腿的<em>线上</em>对照：编码器发出去的那枚参数，服务端必须原样读回来 ——
            // 只验编码器会漏掉"服务端按另一套长度读"这一形，所以这两格走真连接。
            cell(seen, wrong, "P0h_server_echoes_the_empty_argument", wire.askArgs("ECHO", ""), "''");
            cell(seen, wrong, "P0i_server_echoes_the_nbsp_argument",
                    wire.askArgs("ECHO", NBSP + "0"), "'" + NBSP + "0'");
            // ===== GEN-GRAMMAR-CELLS（gen_grammar_cells.py 从 ref_grammar.tr 机械生成，178 格：E=88 F=40 P=50）=====
            shapeCell(seen, wrong, "FLUSHDB#1", wire.value("FLUSHDB"), "simple");
            shapeCell(seen, wrong, "HSET_g_hash_a_1_b_2_c_3#1", wire.value("HSET", "g_hash", "a", "1", "b", "2", "c", "3"), "integer");
            shapeCell(seen, wrong, "SADD_g_set_a_b_c#1", wire.value("SADD", "g_set", "a", "b", "c"), "integer");
            shapeCell(seen, wrong, "ZADD_g_zset_1_a_2_b_3_c#1", wire.value("ZADD", "g_zset", "1", "a", "2", "b", "3", "c"), "integer");
            shapeCell(seen, wrong, "SET_g_str_v#1", wire.value("SET", "g_str", "v"), "simple");
            shapeCell(seen, wrong, "SET_g_other_v#1", wire.value("SET", "g_other", "v"), "simple");
            shapeCell(seen, wrong, "HLEN_g_hash#1", wire.value("HLEN", "g_hash"), "integer");
            shapeCell(seen, wrong, "SCARD_g_set#1", wire.value("SCARD", "g_set"), "integer");
            shapeCell(seen, wrong, "ZCARD_g_zset#1", wire.value("ZCARD", "g_zset"), "integer");
            shapeCell(seen, wrong, "TYPE_g_str#1", wire.value("TYPE", "g_str"), "simple");
            payloadCell(seen, wrong, "SCAN_0#1", wire.value("SCAN", "0"), "['0',['g_hash','g_other','g_set','g_str','g_zset']]", "flat");
            shapeCell(seen, wrong, "SCAN_1#1", wire.value("SCAN", "1"), "array2");
            shapeCell(seen, wrong, "SCAN_2#1", wire.value("SCAN", "2"), "array2");
            payloadCell(seen, wrong, "SCAN_EMP#1", wire.value("SCAN", ""), "['0',['g_hash','g_other','g_set','g_str','g_zset']]", "flat");
            shapeCell(seen, wrong, "SCAN_-1#1", wire.value("SCAN", "-1"), "array2");
            shapeCell(seen, wrong, "SCAN_+1#1", wire.value("SCAN", "+1"), "array2");
            cell(seen, wrong, "SCAN_S0#1", wire.askArgs("SCAN", " 0"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_T0#1", wire.askArgs("SCAN", "\t0"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_N0#1", wire.askArgs("SCAN", "\u00a00"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_0S#1", wire.askArgs("SCAN", "0 "), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_0x10#1", wire.askArgs("SCAN", "0x10"), "error:ERR invalid cursor");
            shapeCell(seen, wrong, "SCAN_18446744073709551615#1", wire.value("SCAN", "18446744073709551615"), "array2");
            shapeCell(seen, wrong, "SCAN_00018446744073709551615#1", wire.value("SCAN", "00018446744073709551615"), "array2");
            cell(seen, wrong, "SCAN_18446744073709551616#1", wire.askArgs("SCAN", "18446744073709551616"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_-18446744073709551616#1", wire.askArgs("SCAN", "-18446744073709551616"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_99999999999999999999999#1", wire.askArgs("SCAN", "99999999999999999999999"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_abc#1", wire.askArgs("SCAN", "abc"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_-#1", wire.askArgs("SCAN", "-"), "error:ERR invalid cursor");
            payloadCell(seen, wrong, "HSCAN_g_hash_0#1", wire.value("HSCAN", "g_hash", "0"), "['0',['a','1','b','2','c','3']]", "pairs");
            shapeCell(seen, wrong, "HSCAN_g_hash_1#1", wire.value("HSCAN", "g_hash", "1"), "array2");
            shapeCell(seen, wrong, "HSCAN_g_hash_2#1", wire.value("HSCAN", "g_hash", "2"), "array2");
            payloadCell(seen, wrong, "HSCAN_g_hash_EMP#1", wire.value("HSCAN", "g_hash", ""), "['0',['a','1','b','2','c','3']]", "pairs");
            shapeCell(seen, wrong, "HSCAN_g_hash_-1#1", wire.value("HSCAN", "g_hash", "-1"), "array2");
            shapeCell(seen, wrong, "HSCAN_g_hash_+1#1", wire.value("HSCAN", "g_hash", "+1"), "array2");
            cell(seen, wrong, "HSCAN_g_hash_S0#1", wire.askArgs("HSCAN", "g_hash", " 0"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_T0#1", wire.askArgs("HSCAN", "g_hash", "\t0"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_N0#1", wire.askArgs("HSCAN", "g_hash", "\u00a00"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_0S#1", wire.askArgs("HSCAN", "g_hash", "0 "), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_0x10#1", wire.askArgs("HSCAN", "g_hash", "0x10"), "error:ERR invalid cursor");
            shapeCell(seen, wrong, "HSCAN_g_hash_18446744073709551615#1", wire.value("HSCAN", "g_hash", "18446744073709551615"), "array2");
            shapeCell(seen, wrong, "HSCAN_g_hash_00018446744073709551615#1", wire.value("HSCAN", "g_hash", "00018446744073709551615"), "array2");
            cell(seen, wrong, "HSCAN_g_hash_18446744073709551616#1", wire.askArgs("HSCAN", "g_hash", "18446744073709551616"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_-18446744073709551616#1", wire.askArgs("HSCAN", "g_hash", "-18446744073709551616"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_99999999999999999999999#1", wire.askArgs("HSCAN", "g_hash", "99999999999999999999999"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_abc#1", wire.askArgs("HSCAN", "g_hash", "abc"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_-#1", wire.askArgs("HSCAN", "g_hash", "-"), "error:ERR invalid cursor");
            payloadCell(seen, wrong, "SSCAN_g_set_0#1", wire.value("SSCAN", "g_set", "0"), "['0',['a','b','c']]", "flat");
            shapeCell(seen, wrong, "SSCAN_g_set_1#1", wire.value("SSCAN", "g_set", "1"), "array2");
            shapeCell(seen, wrong, "SSCAN_g_set_2#1", wire.value("SSCAN", "g_set", "2"), "array2");
            payloadCell(seen, wrong, "SSCAN_g_set_EMP#1", wire.value("SSCAN", "g_set", ""), "['0',['a','b','c']]", "flat");
            shapeCell(seen, wrong, "SSCAN_g_set_-1#1", wire.value("SSCAN", "g_set", "-1"), "array2");
            shapeCell(seen, wrong, "SSCAN_g_set_+1#1", wire.value("SSCAN", "g_set", "+1"), "array2");
            cell(seen, wrong, "SSCAN_g_set_S0#1", wire.askArgs("SSCAN", "g_set", " 0"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_T0#1", wire.askArgs("SSCAN", "g_set", "\t0"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_N0#1", wire.askArgs("SSCAN", "g_set", "\u00a00"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_0S#1", wire.askArgs("SSCAN", "g_set", "0 "), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_0x10#1", wire.askArgs("SSCAN", "g_set", "0x10"), "error:ERR invalid cursor");
            shapeCell(seen, wrong, "SSCAN_g_set_18446744073709551615#1", wire.value("SSCAN", "g_set", "18446744073709551615"), "array2");
            shapeCell(seen, wrong, "SSCAN_g_set_00018446744073709551615#1", wire.value("SSCAN", "g_set", "00018446744073709551615"), "array2");
            cell(seen, wrong, "SSCAN_g_set_18446744073709551616#1", wire.askArgs("SSCAN", "g_set", "18446744073709551616"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_-18446744073709551616#1", wire.askArgs("SSCAN", "g_set", "-18446744073709551616"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_99999999999999999999999#1", wire.askArgs("SSCAN", "g_set", "99999999999999999999999"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_abc#1", wire.askArgs("SSCAN", "g_set", "abc"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_-#1", wire.askArgs("SSCAN", "g_set", "-"), "error:ERR invalid cursor");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0#1", wire.value("ZSCAN", "g_zset", "0"), "['0',['a','1','b','2','c','3']]", "pairs");
            shapeCell(seen, wrong, "ZSCAN_g_zset_1#1", wire.value("ZSCAN", "g_zset", "1"), "array2");
            shapeCell(seen, wrong, "ZSCAN_g_zset_2#1", wire.value("ZSCAN", "g_zset", "2"), "array2");
            payloadCell(seen, wrong, "ZSCAN_g_zset_EMP#1", wire.value("ZSCAN", "g_zset", ""), "['0',['a','1','b','2','c','3']]", "pairs");
            shapeCell(seen, wrong, "ZSCAN_g_zset_-1#1", wire.value("ZSCAN", "g_zset", "-1"), "array2");
            shapeCell(seen, wrong, "ZSCAN_g_zset_+1#1", wire.value("ZSCAN", "g_zset", "+1"), "array2");
            cell(seen, wrong, "ZSCAN_g_zset_S0#1", wire.askArgs("ZSCAN", "g_zset", " 0"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_T0#1", wire.askArgs("ZSCAN", "g_zset", "\t0"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_N0#1", wire.askArgs("ZSCAN", "g_zset", "\u00a00"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_0S#1", wire.askArgs("ZSCAN", "g_zset", "0 "), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_0x10#1", wire.askArgs("ZSCAN", "g_zset", "0x10"), "error:ERR invalid cursor");
            shapeCell(seen, wrong, "ZSCAN_g_zset_18446744073709551615#1", wire.value("ZSCAN", "g_zset", "18446744073709551615"), "array2");
            shapeCell(seen, wrong, "ZSCAN_g_zset_00018446744073709551615#1", wire.value("ZSCAN", "g_zset", "00018446744073709551615"), "array2");
            cell(seen, wrong, "ZSCAN_g_zset_18446744073709551616#1", wire.askArgs("ZSCAN", "g_zset", "18446744073709551616"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_-18446744073709551616#1", wire.askArgs("ZSCAN", "g_zset", "-18446744073709551616"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_99999999999999999999999#1", wire.askArgs("ZSCAN", "g_zset", "99999999999999999999999"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_abc#1", wire.askArgs("ZSCAN", "g_zset", "abc"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_-#1", wire.askArgs("ZSCAN", "g_zset", "-"), "error:ERR invalid cursor");
            shapeCell(seen, wrong, "SCAN_0_COUNT_1#1", wire.value("SCAN", "0", "COUNT", "1"), "array2");
            shapeCell(seen, wrong, "SCAN_0_COUNT_2#1", wire.value("SCAN", "0", "COUNT", "2"), "array2");
            cell(seen, wrong, "SCAN_0_COUNT_0#1", wire.askArgs("SCAN", "0", "COUNT", "0"), "error:ERR syntax error");
            cell(seen, wrong, "SCAN_0_COUNT_-1#1", wire.askArgs("SCAN", "0", "COUNT", "-1"), "error:ERR syntax error");
            cell(seen, wrong, "SCAN_0_COUNT_abc#1", wire.askArgs("SCAN", "0", "COUNT", "abc"), "error:ERR value is not an integer or out of range");
            cell(seen, wrong, "SCAN_0_COUNT_999999999999999999999#1", wire.askArgs("SCAN", "0", "COUNT", "999999999999999999999"), "error:ERR value is not an integer or out of range");
            cell(seen, wrong, "SCAN_0_COUNT#1", wire.askArgs("SCAN", "0", "COUNT"), "error:ERR syntax error");
            cell(seen, wrong, "SCAN_0_MATCH#1", wire.askArgs("SCAN", "0", "MATCH"), "error:ERR syntax error");
            payloadCell(seen, wrong, "SCAN_0_MATCH_*#1", wire.value("SCAN", "0", "MATCH", "*"), "['0',['g_hash','g_other','g_set','g_str','g_zset']]", "flat");
            payloadCell(seen, wrong, "SCAN_0_MATCH_a*#1", wire.value("SCAN", "0", "MATCH", "a*"), "['0',[]]", "flat");
            payloadCell(seen, wrong, "SCAN_0_MATCH_zz*#1", wire.value("SCAN", "0", "MATCH", "zz*"), "['0',[]]", "flat");
            cell(seen, wrong, "SCAN_0_EXTRA#1", wire.askArgs("SCAN", "0", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "SCAN_0_COUNT_2_EXTRA#1", wire.askArgs("SCAN", "0", "COUNT", "2", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "SCAN_0_MATCH_a*_COUNT#1", wire.askArgs("SCAN", "0", "MATCH", "a*", "COUNT"), "error:ERR syntax error");
            shapeCell(seen, wrong, "SCAN_0_count_1#1", wire.value("SCAN", "0", "count", "1"), "array2");
            payloadCell(seen, wrong, "SCAN_0_match_a*#1", wire.value("SCAN", "0", "match", "a*"), "['0',[]]", "flat");
            payloadCell(seen, wrong, "SCAN_0_MATCH_a*_MATCH_b*#1", wire.value("SCAN", "0", "MATCH", "a*", "MATCH", "b*"), "['0',[]]", "flat");
            shapeCell(seen, wrong, "SCAN_0_COUNT_1_COUNT_2#1", wire.value("SCAN", "0", "COUNT", "1", "COUNT", "2"), "array2");
            shapeCell(seen, wrong, "SCAN_0_MATCH_a*_COUNT_2#1", wire.value("SCAN", "0", "MATCH", "a*", "COUNT", "2"), "array2");
            shapeCell(seen, wrong, "SCAN_0_COUNT_2_MATCH_a*#1", wire.value("SCAN", "0", "COUNT", "2", "MATCH", "a*"), "array2");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_COUNT_1#1", wire.value("HSCAN", "g_hash", "0", "COUNT", "1"), "['0',['a','1','b','2','c','3']]", "pairs");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_COUNT_2#1", wire.value("HSCAN", "g_hash", "0", "COUNT", "2"), "['0',['a','1','b','2','c','3']]", "pairs");
            cell(seen, wrong, "HSCAN_g_hash_0_COUNT_0#1", wire.askArgs("HSCAN", "g_hash", "0", "COUNT", "0"), "error:ERR syntax error");
            cell(seen, wrong, "HSCAN_g_hash_0_COUNT_-1#1", wire.askArgs("HSCAN", "g_hash", "0", "COUNT", "-1"), "error:ERR syntax error");
            cell(seen, wrong, "HSCAN_g_hash_0_COUNT_abc#1", wire.askArgs("HSCAN", "g_hash", "0", "COUNT", "abc"), "error:ERR value is not an integer or out of range");
            cell(seen, wrong, "HSCAN_g_hash_0_COUNT_999999999999999999999#1", wire.askArgs("HSCAN", "g_hash", "0", "COUNT", "999999999999999999999"), "error:ERR value is not an integer or out of range");
            cell(seen, wrong, "HSCAN_g_hash_0_COUNT#1", wire.askArgs("HSCAN", "g_hash", "0", "COUNT"), "error:ERR syntax error");
            cell(seen, wrong, "HSCAN_g_hash_0_MATCH#1", wire.askArgs("HSCAN", "g_hash", "0", "MATCH"), "error:ERR syntax error");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_MATCH_*#1", wire.value("HSCAN", "g_hash", "0", "MATCH", "*"), "['0',['a','1','b','2','c','3']]", "pairs");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_MATCH_a*#1", wire.value("HSCAN", "g_hash", "0", "MATCH", "a*"), "['0',['a','1']]", "pairs");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_MATCH_zz*#1", wire.value("HSCAN", "g_hash", "0", "MATCH", "zz*"), "['0',[]]", "pairs");
            cell(seen, wrong, "HSCAN_g_hash_0_EXTRA#1", wire.askArgs("HSCAN", "g_hash", "0", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "HSCAN_g_hash_0_COUNT_2_EXTRA#1", wire.askArgs("HSCAN", "g_hash", "0", "COUNT", "2", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "HSCAN_g_hash_0_MATCH_a*_COUNT#1", wire.askArgs("HSCAN", "g_hash", "0", "MATCH", "a*", "COUNT"), "error:ERR syntax error");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_count_1#1", wire.value("HSCAN", "g_hash", "0", "count", "1"), "['0',['a','1','b','2','c','3']]", "pairs");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_match_a*#1", wire.value("HSCAN", "g_hash", "0", "match", "a*"), "['0',['a','1']]", "pairs");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_MATCH_a*_MATCH_b*#1", wire.value("HSCAN", "g_hash", "0", "MATCH", "a*", "MATCH", "b*"), "['0',['b','2']]", "pairs");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_COUNT_1_COUNT_2#1", wire.value("HSCAN", "g_hash", "0", "COUNT", "1", "COUNT", "2"), "['0',['a','1','b','2','c','3']]", "pairs");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_MATCH_a*_COUNT_2#1", wire.value("HSCAN", "g_hash", "0", "MATCH", "a*", "COUNT", "2"), "['0',['a','1']]", "pairs");
            payloadCell(seen, wrong, "HSCAN_g_hash_0_COUNT_2_MATCH_a*#1", wire.value("HSCAN", "g_hash", "0", "COUNT", "2", "MATCH", "a*"), "['0',['a','1']]", "pairs");
            shapeCell(seen, wrong, "SSCAN_g_set_0_COUNT_1#1", wire.value("SSCAN", "g_set", "0", "COUNT", "1"), "array2");
            shapeCell(seen, wrong, "SSCAN_g_set_0_COUNT_2#1", wire.value("SSCAN", "g_set", "0", "COUNT", "2"), "array2");
            cell(seen, wrong, "SSCAN_g_set_0_COUNT_0#1", wire.askArgs("SSCAN", "g_set", "0", "COUNT", "0"), "error:ERR syntax error");
            cell(seen, wrong, "SSCAN_g_set_0_COUNT_-1#1", wire.askArgs("SSCAN", "g_set", "0", "COUNT", "-1"), "error:ERR syntax error");
            cell(seen, wrong, "SSCAN_g_set_0_COUNT_abc#1", wire.askArgs("SSCAN", "g_set", "0", "COUNT", "abc"), "error:ERR value is not an integer or out of range");
            cell(seen, wrong, "SSCAN_g_set_0_COUNT_999999999999999999999#1", wire.askArgs("SSCAN", "g_set", "0", "COUNT", "999999999999999999999"), "error:ERR value is not an integer or out of range");
            cell(seen, wrong, "SSCAN_g_set_0_COUNT#1", wire.askArgs("SSCAN", "g_set", "0", "COUNT"), "error:ERR syntax error");
            cell(seen, wrong, "SSCAN_g_set_0_MATCH#1", wire.askArgs("SSCAN", "g_set", "0", "MATCH"), "error:ERR syntax error");
            payloadCell(seen, wrong, "SSCAN_g_set_0_MATCH_*#1", wire.value("SSCAN", "g_set", "0", "MATCH", "*"), "['0',['a','b','c']]", "flat");
            payloadCell(seen, wrong, "SSCAN_g_set_0_MATCH_a*#1", wire.value("SSCAN", "g_set", "0", "MATCH", "a*"), "['0',['a']]", "flat");
            payloadCell(seen, wrong, "SSCAN_g_set_0_MATCH_zz*#1", wire.value("SSCAN", "g_set", "0", "MATCH", "zz*"), "['0',[]]", "flat");
            cell(seen, wrong, "SSCAN_g_set_0_EXTRA#1", wire.askArgs("SSCAN", "g_set", "0", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "SSCAN_g_set_0_COUNT_2_EXTRA#1", wire.askArgs("SSCAN", "g_set", "0", "COUNT", "2", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "SSCAN_g_set_0_MATCH_a*_COUNT#1", wire.askArgs("SSCAN", "g_set", "0", "MATCH", "a*", "COUNT"), "error:ERR syntax error");
            shapeCell(seen, wrong, "SSCAN_g_set_0_count_1#1", wire.value("SSCAN", "g_set", "0", "count", "1"), "array2");
            payloadCell(seen, wrong, "SSCAN_g_set_0_match_a*#1", wire.value("SSCAN", "g_set", "0", "match", "a*"), "['0',['a']]", "flat");
            payloadCell(seen, wrong, "SSCAN_g_set_0_MATCH_a*_MATCH_b*#1", wire.value("SSCAN", "g_set", "0", "MATCH", "a*", "MATCH", "b*"), "['0',['b']]", "flat");
            shapeCell(seen, wrong, "SSCAN_g_set_0_COUNT_1_COUNT_2#1", wire.value("SSCAN", "g_set", "0", "COUNT", "1", "COUNT", "2"), "array2");
            shapeCell(seen, wrong, "SSCAN_g_set_0_MATCH_a*_COUNT_2#1", wire.value("SSCAN", "g_set", "0", "MATCH", "a*", "COUNT", "2"), "array2");
            shapeCell(seen, wrong, "SSCAN_g_set_0_COUNT_2_MATCH_a*#1", wire.value("SSCAN", "g_set", "0", "COUNT", "2", "MATCH", "a*"), "array2");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_COUNT_1#1", wire.value("ZSCAN", "g_zset", "0", "COUNT", "1"), "['0',['a','1','b','2','c','3']]", "pairs");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_COUNT_2#1", wire.value("ZSCAN", "g_zset", "0", "COUNT", "2"), "['0',['a','1','b','2','c','3']]", "pairs");
            cell(seen, wrong, "ZSCAN_g_zset_0_COUNT_0#1", wire.askArgs("ZSCAN", "g_zset", "0", "COUNT", "0"), "error:ERR syntax error");
            cell(seen, wrong, "ZSCAN_g_zset_0_COUNT_-1#1", wire.askArgs("ZSCAN", "g_zset", "0", "COUNT", "-1"), "error:ERR syntax error");
            cell(seen, wrong, "ZSCAN_g_zset_0_COUNT_abc#1", wire.askArgs("ZSCAN", "g_zset", "0", "COUNT", "abc"), "error:ERR value is not an integer or out of range");
            cell(seen, wrong, "ZSCAN_g_zset_0_COUNT_999999999999999999999#1", wire.askArgs("ZSCAN", "g_zset", "0", "COUNT", "999999999999999999999"), "error:ERR value is not an integer or out of range");
            cell(seen, wrong, "ZSCAN_g_zset_0_COUNT#1", wire.askArgs("ZSCAN", "g_zset", "0", "COUNT"), "error:ERR syntax error");
            cell(seen, wrong, "ZSCAN_g_zset_0_MATCH#1", wire.askArgs("ZSCAN", "g_zset", "0", "MATCH"), "error:ERR syntax error");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_MATCH_*#1", wire.value("ZSCAN", "g_zset", "0", "MATCH", "*"), "['0',['a','1','b','2','c','3']]", "pairs");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_MATCH_a*#1", wire.value("ZSCAN", "g_zset", "0", "MATCH", "a*"), "['0',['a','1']]", "pairs");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_MATCH_zz*#1", wire.value("ZSCAN", "g_zset", "0", "MATCH", "zz*"), "['0',[]]", "pairs");
            cell(seen, wrong, "ZSCAN_g_zset_0_EXTRA#1", wire.askArgs("ZSCAN", "g_zset", "0", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "ZSCAN_g_zset_0_COUNT_2_EXTRA#1", wire.askArgs("ZSCAN", "g_zset", "0", "COUNT", "2", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "ZSCAN_g_zset_0_MATCH_a*_COUNT#1", wire.askArgs("ZSCAN", "g_zset", "0", "MATCH", "a*", "COUNT"), "error:ERR syntax error");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_count_1#1", wire.value("ZSCAN", "g_zset", "0", "count", "1"), "['0',['a','1','b','2','c','3']]", "pairs");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_match_a*#1", wire.value("ZSCAN", "g_zset", "0", "match", "a*"), "['0',['a','1']]", "pairs");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_MATCH_a*_MATCH_b*#1", wire.value("ZSCAN", "g_zset", "0", "MATCH", "a*", "MATCH", "b*"), "['0',['b','2']]", "pairs");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_COUNT_1_COUNT_2#1", wire.value("ZSCAN", "g_zset", "0", "COUNT", "1", "COUNT", "2"), "['0',['a','1','b','2','c','3']]", "pairs");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_MATCH_a*_COUNT_2#1", wire.value("ZSCAN", "g_zset", "0", "MATCH", "a*", "COUNT", "2"), "['0',['a','1']]", "pairs");
            payloadCell(seen, wrong, "ZSCAN_g_zset_0_COUNT_2_MATCH_a*#1", wire.value("ZSCAN", "g_zset", "0", "COUNT", "2", "MATCH", "a*"), "['0',['a','1']]", "pairs");
            cell(seen, wrong, "SCAN_abc_EXTRA#1", wire.askArgs("SCAN", "abc", "EXTRA"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_abc_COUNT_0#1", wire.askArgs("SCAN", "abc", "COUNT", "0"), "error:ERR invalid cursor");
            cell(seen, wrong, "SCAN_EMP_EXTRA#1", wire.askArgs("SCAN", "", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "HSCAN_g_hash_abc_EXTRA#1", wire.askArgs("HSCAN", "g_hash", "abc", "EXTRA"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_abc_COUNT_0#1", wire.askArgs("HSCAN", "g_hash", "abc", "COUNT", "0"), "error:ERR invalid cursor");
            cell(seen, wrong, "HSCAN_g_hash_EMP_EXTRA#1", wire.askArgs("HSCAN", "g_hash", "", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "SSCAN_g_set_abc_EXTRA#1", wire.askArgs("SSCAN", "g_set", "abc", "EXTRA"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_abc_COUNT_0#1", wire.askArgs("SSCAN", "g_set", "abc", "COUNT", "0"), "error:ERR invalid cursor");
            cell(seen, wrong, "SSCAN_g_set_EMP_EXTRA#1", wire.askArgs("SSCAN", "g_set", "", "EXTRA"), "error:ERR syntax error");
            cell(seen, wrong, "ZSCAN_g_zset_abc_EXTRA#1", wire.askArgs("ZSCAN", "g_zset", "abc", "EXTRA"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_abc_COUNT_0#1", wire.askArgs("ZSCAN", "g_zset", "abc", "COUNT", "0"), "error:ERR invalid cursor");
            cell(seen, wrong, "ZSCAN_g_zset_EMP_EXTRA#1", wire.askArgs("ZSCAN", "g_zset", "", "EXTRA"), "error:ERR syntax error");
            shapeCell(seen, wrong, "HGETALL_g_hash#1", wire.value("HGETALL", "g_hash"), "array");
            shapeCell(seen, wrong, "SMEMBERS_g_set#1", wire.value("SMEMBERS", "g_set"), "array");
            shapeCell(seen, wrong, "ZRANGE_g_zset_0_-1_WITHSCORES#1", wire.value("ZRANGE", "g_zset", "0", "-1", "WITHSCORES"), "array");
            shapeCell(seen, wrong, "DBSIZE#1", wire.value("DBSIZE"), "integer");
            // ===== END-GEN-GRAMMAR-CELLS =====
        }
        finish("theFourScanCommandsJudgeCursorAndOptionsLikeTheReference", seen, wrong);
    }
    // =================================================================================
    // 源码上的那几格：两关住在哪、是不是四家共用、谁先响
    // =================================================================================

    /**
     * 线上格子能证明"答案对了"，证不了"对在哪一层、是不是四家各一套"。本卡有两条会<em>同时</em>
     * 答对当前网格的路：把两关抄进四个 handler 各一份，和只写一段共用。前者意味着下一次有人加
     * 第五家 scan 时又会漏掉一关（改前三家漏的正是同一套），所以这里钉：游标关与选项关
     * 各<em>只有一段代码</em>，四家都只是调用它，而且两关的先后是游标在前。
     */
    @Test
    void theCursorGateAndTheOptionGateAreWrittenOnceAndSharedByTheFourCommands() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        String handler = readRepoFile(HANDLER);
        String code = withoutComments(handler);
        String cursor = withoutComments(methodBody(handler, CURSOR_LEG));
        String options = withoutComments(methodBody(handler, OPTION_LEG));
        String bScan = withoutComments(methodBody(handler, HANDLE_SCAN));
        String bHscan = withoutComments(methodBody(handler, HANDLE_HSCAN));
        String bSscan = withoutComments(methodBody(handler, HANDLE_SSCAN));
        String bZscan = withoutComments(methodBody(handler, HANDLE_ZSCAN));

        // 阳性对照：切片尺必须真能切出一个方法 —— 同一根 needle 在全份字节里命中得多、在切出来的
        // 那一段里命中得少，两形必须分家；否则下面那些"命中 N 次"读的都是"文件里恰好有这词"。
        cell(seen, wrong, "S0a_slice_is_narrower_than_the_whole_file",
                String.valueOf(countOf(cursor, "scanCursorAccepted(") < countOf(code, "scanCursorAccepted(")), "true");
        cell(seen, wrong, "S0b_needle_is_findable_when_present",
                String.valueOf(countOf(cursor, "ASCII_SPACE.indexOf(")), "1");
        cell(seen, wrong, "S0c_a_handler_that_no_longer_parses_options_reads_zero",
                String.valueOf(countOf(bZscan, "\"MATCH\".equalsIgnoreCase(")), "0");

        // 两关各只有一段代码，四家各有且仅有一处调用
        cell(seen, wrong, "S1_cursor_gate_is_called_by_all_four",
                String.valueOf(countOf(code, "!scanCursorAccepted(")), "4");
        cell(seen, wrong, "S2_option_gate_is_called_by_all_four",
                String.valueOf(countOf(code, "scanOptions(args, ")), "4");
        cell(seen, wrong, "S3_the_two_gates_are_written_once",
                String.valueOf(countOf(code, "private static boolean scanCursorAccepted(")
                        + countOf(code, "private static ScanOptions scanOptions(")), "2");

        // 游标关排在选项关之前。线上那 12 手 ORDER 格只在"两关都在"时才分辨得出先后（坏游标 +
        // 坏选项时先响的那关决定文案），源码这一格直接钉顺序。
        cell(seen, wrong, "S4a_cursor_gate_precedes_option_gate_in_SCAN", gateOrder(bScan), "1");
        cell(seen, wrong, "S4b_cursor_gate_precedes_option_gate_in_HSCAN", gateOrder(bHscan), "1");
        cell(seen, wrong, "S4c_cursor_gate_precedes_option_gate_in_SSCAN", gateOrder(bSscan), "1");
        cell(seen, wrong, "S4d_cursor_gate_precedes_option_gate_in_ZSCAN", gateOrder(bZscan), "1");

        // 选项那一栏只有一处实现：四家里不许再留各自的 MATCH 腿（改前三家就是只挑了 MATCH）。
        cell(seen, wrong, "S5a_no_private_match_leg_in_SCAN",
                String.valueOf(countOf(bScan, "\"MATCH\".equalsIgnoreCase(")), "0");
        cell(seen, wrong, "S5b_no_private_match_leg_in_HSCAN",
                String.valueOf(countOf(bHscan, "\"MATCH\".equalsIgnoreCase(")), "0");
        cell(seen, wrong, "S5c_no_private_match_leg_in_SSCAN",
                String.valueOf(countOf(bSscan, "\"MATCH\".equalsIgnoreCase(")), "0");
        cell(seen, wrong, "S5d_no_private_match_leg_in_ZSCAN",
                String.valueOf(countOf(bZscan, "\"MATCH\".equalsIgnoreCase(")), "0");
        // S5e 是上面那四格"命中 0"的对照：这词在<em>选项那一栏</em>里确实存在（不是全仓查无此词）。
        cell(seen, wrong, "S5e_the_match_flag_lives_in_the_option_leg",
                String.valueOf(countOf(options, "\"MATCH\".equalsIgnoreCase(")), "1");
        cell(seen, wrong, "S6_option_leg_has_one_match_and_one_count",
                String.valueOf(countOf(options, "\"MATCH\".equalsIgnoreCase(")
                        + countOf(options, "\"COUNT\".equalsIgnoreCase(")), "2");
        // 另外两处 COUNT 是别的命令自己的腿（XRANGE/XREAD 那一族），本卡一行没动 ⇒ 钉住它们还在，
        // 免得"共用一段代码"被做成把别人的选项栏也一起改了。
        cell(seen, wrong, "S7_other_commands_keep_their_own_count_leg",
                String.valueOf(countOf(code, "\"COUNT\".equalsIgnoreCase(")), "3");

        // 选项那一栏的三条判据都得在。判据<em>不绑写法</em>：`v < 1` 换成 `v <= 0`、
        // `left >= 2` 换成 `> 1` 都是等价重构，所以要认的是"一处非正数拒绝、两处带值要求"，
        // 不是某个具体拼法（记忆里绑单一写法放过/绊红都吃过亏）。
        cell(seen, wrong, "S8_flags_must_carry_a_value",
                String.valueOf(countOf(options, ">= 2") + countOf(options, "> 1")), "2");
        cell(seen, wrong, "S9_non_positive_count_is_rejected_once",
                String.valueOf(countOf(options, "< 1") + countOf(options, "<= 0")), "1");
        cell(seen, wrong, "S10_unrecognised_tail_is_a_reply_not_a_skip",
                String.valueOf(countOf(options, "ScanOptions.reply(RespError.syntaxError())")), "2");

        // 游标尺是无符号那把：不许换回 RedisIntegerFormat（有符号 LONG_MAX，会把合法的 ULONG_MAX
        // 游标拒成坏游标 —— 改前就是这一形），也不许换成 Character.isWhitespace（它把 NBSP 当空白，
        // 而 C locale 的 isspace 只认六枚 ASCII；实测参照对 `SCAN <空格>0` 与 `SCAN <NBSP>0`
        // 分家：前者 invalid cursor，后者也 invalid cursor，但换成 isWhitespace 后 Java 会把
        // {@code \u00a0} 之外的 NBSP 同族字符一并当空白，那一族就没有参照依据了）。
        cell(seen, wrong, "S11_cursor_leg_does_not_use_the_signed_integer_ruler",
                String.valueOf(countOf(cursor, "RedisIntegerFormat")), "0");
        cell(seen, wrong, "S12_signed_ruler_is_a_real_symbol_elsewhere",
                String.valueOf(countOf(code, "RedisIntegerFormat") > 5), "true");
        cell(seen, wrong, "S13_cursor_leg_does_not_use_java_isWhitespace",
                String.valueOf(countOf(cursor, "isWhitespace")), "0");
        cell(seen, wrong, "S14_upper_bound_is_the_unsigned_one",
                String.valueOf(countOf(cursor, "18446744073709551615")), "1");

        // 那一栏空白必须<em>就是</em> C locale 的六枚，一枚不多一枚不少（把源码里的转义写回真字符
        // 再逐字比，而不是数一下有几个反斜杠）。
        String column = asciiSpaceColumn(code);
        cell(seen, wrong, "S15_the_space_column_is_the_six_c_locale_spaces", column,
                " \t\n\r\u000B\f");
        cell(seen, wrong, "S16_the_space_column_has_no_nbsp",
                String.valueOf(column.indexOf(NBSP.charAt(0))), "-1");
        // S16 的对照：这一栏确实认 ASCII 空格（否则"没有 NBSP"会因为"整栏是空的"而成立）。
        cell(seen, wrong, "S17_the_space_column_has_the_plain_space",
                String.valueOf(column.indexOf(' ') >= 0), "true");
        cell(seen, wrong, "S18_cursor_leg_reads_that_column",
                String.valueOf(countOf(cursor, "ASCII_SPACE.indexOf(")), "1");

        // 解析出来的 pattern/count 才是交给 store 的东西；游标本身照原文传（上游也是把原文
        // 的 strtoul 值拿去定位，把文本改回 "0" 再传就等于没判）。三家带 key 的 store 目前
        // <em>不收</em> count —— 那是卡 #53 欠的那一格，这里如实钉住"只有键空间那家用得到它"。
        cell(seen, wrong, "S19a_scan_asks_with_parsed_pattern_and_count",
                String.valueOf(countOf(bScan, "opt.pattern") + countOf(bScan, "opt.count")), "2");
        cell(seen, wrong, "S19b_hscan_sscan_zscan_pass_the_parsed_pattern",
                String.valueOf(countOf(bHscan, "opt.pattern") + countOf(bSscan, "opt.pattern")
                        + countOf(bZscan, "opt.pattern")), "3");
        cell(seen, wrong, "S20_the_three_keyed_stores_still_take_no_count",
                String.valueOf(countOf(bHscan, "opt.count") + countOf(bSscan, "opt.count")
                        + countOf(bZscan, "opt.count")), "0");
        cell(seen, wrong, "S21_count_hint_defaults_to_ten_like_upstream",
                String.valueOf(countOf(options, "int count = 10")), "1");

        // 整个 core/src/main 里"invalid cursor"这句只有这四家会说，而且没有第二个文法实现。
        cell(seen, wrong, "S22_invalid_cursor_is_said_by_exactly_four_places",
                String.valueOf(countInTree(CORE_MAIN, "\"invalid cursor\"")), "4");
        cell(seen, wrong, "S23_the_tree_scanner_is_not_blind",
                String.valueOf(countInTree(CORE_MAIN, "RespError.syntaxError(") > 20), "true");
        cell(seen, wrong, "S24_the_cursor_ruler_is_defined_once_in_the_tree",
                String.valueOf(countInTree(CORE_MAIN, "scanCursorAccepted(")), "5");
        cell(seen, wrong, "S25_the_option_ruler_is_defined_once_in_the_tree",
                String.valueOf(countInTree(CORE_MAIN, "scanOptions(args, ")), "4");

        finish("theCursorGateAndTheOptionGateAreWrittenOnceAndSharedByTheFourCommands", seen, wrong);
    }
    // =================================================================================
    // RESP 切帧 + 渲染 + 形状 —— 与 250 那份量具同一判据
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

    /**
     * P 档那把形状尺：与 {@code cmp_grammar.py:is_scan_shape} 一一对应 ——
     * {@code array2} 就是"长度为 2 的数组，第一项是 bulk、第二项是 array"，别的一律按种类给个词。
     */
    private static String shapeOf(Object v) {
        if (v == NIL) {
            return "nil";
        }
        if (v == NULL_ARRAY) {
            return "nullarray";
        }
        if (v instanceof Simple) {
            return "simple";
        }
        if (v instanceof Error) {
            return "error";
        }
        if (v instanceof Long) {
            return "integer";
        }
        if (v instanceof String) {
            return "bulk";
        }
        if (v instanceof List) {
            return isScanShape(v) ? "array2" : "array";
        }
        return "other";
    }

    private static boolean isScanShape(Object v) {
        if (!(v instanceof List)) {
            return false;
        }
        List<?> l = (List<?>) v;
        return l.size() == 2 && l.get(0) instanceof String && l.get(1) instanceof List;
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

    /**
     * 逐参数编码（每枚都是一条 bulk，长度按 UTF-8 字节数）。
     *
     * <p>本卡的参数里有空串、tab、NBSP、前后带空格的游标 —— 14a 那把 {@code encode(一行，按空格切)}
     * 的尺把它们切成别的样子（{@code "SCAN  0"} 会切成三枚），所以这里不留那一路。
     * 这一条由 P0e/P0f/P0g 三格钉着。</p>
     */
    private static byte[] encodeArgs(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = ("*" + args.length + "\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(head, 0, head.length);
        for (String arg : args) {
            byte[] body = arg.getBytes(StandardCharsets.UTF_8);
            byte[] line = ("$" + body.length + "\r\n").getBytes(StandardCharsets.UTF_8);
            out.write(line, 0, line.length);
            out.write(body, 0, body.length);
            out.write('\r');
            out.write('\n');
        }
        return out.toByteArray();
    }

    /** 读到"连续 {@code QUIET_MS} 没有新字节"为止 —— 这一拍同时就是"总共几条帧"的量具。 */
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

    private static void shapeCell(List<String> seen, List<String> wrong, String id, Object got, String want) {
        seen.add(id);
        boolean ok;
        if ("array2".equals(want)) {
            ok = isScanShape(got);
        } else if ("array".equals(want)) {
            ok = got instanceof List;
        } else {
            ok = shapeOf(got).equals(want);
        }
        if (!ok) {
            wrong.add(id + " 期 " + want + " 实 " + shapeOf(got) + " [" + brief(render(got)) + "]");
        }
    }

    /** 坏输入必须<em>抛</em>：P0p–P0s 四格量的就是"这把尺瞎不瞎"，静默返回一个串就是瞎。 */
    private static String howCanonicalFails(String raw, String mode) {
        try {
            return "returned " + canonical(parseFirst(raw), mode);
        } catch (IllegalStateException threw) {
            return "threw";
        }
    }

    private static String brief(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }

    /**
     * F 档那一格：把线上回的值折成<em>规范形</em>再比，期望串由参照同一条腿现算。
     *
     * <p>不在生成时就把排序烤进期望串：烤进去的话，"期望=排好序 / 实际=服务端给的次序"这两者
     * 一不符就红，而红消息看着像"顺序问题"（第一版就是这么红了 24 格 HSCAN/ZSCAN，见类注释里的
     * 出处）。规范形放在比较时刻算，两把尺（仓里这支与 {@code cmp_grammar.py}）共用同一判据。</p>
     */
    private static void payloadCell(List<String> seen, List<String> wrong, String id,
                                    Object got, String want, String mode) {
        seen.add(id);
        String gotText;
        try {
            gotText = canonical(got, mode);
        } catch (IllegalStateException gaugeIsBlind) {
            wrong.add(id + " 期 " + want + " 实 <规范形算不出：" + gaugeIsBlind.getMessage() + ">");
            return;
        }
        if (!gotText.equals(want)) {
            wrong.add(id + " 期 " + want + " 实 " + gotText);
        }
    }

    /**
     * scan 回复的规范形 = 游标原文 + 排好序的负载，与 {@code cmp_grammar.py:canonical} 逐字同判据。
     *
     * <p>{@code pairs} 那一档是本卡存在的理由：HSCAN/ZSCAN 的负载是 field,value 与 member,score
     * <em>成对平铺</em>（上游 {@code scanCallback} 把两件事追加进同一个列表，卡 #43 钉的就是这一列），
     * 整表平铺排序会把"键在前"与"值在前"折成同一个串 —— 服务端次序不是承诺，<em>对内的次序是</em>。
     * {@code flat} 档留给 SCAN/SSCAN：那边的负载就是一串名字，没有对语义。</p>
     *
     * <p>坏输入一律抛，不静默渲染：非 bulk 项、奇数长度的 pairs 负载、不认识的档位名，三种都会在
     * 悄悄渲染成"看起来像个规范串"时把"形状已经和参照分家"读成一次普通的值不等（甚至读成相等）。</p>
     */
    private static String canonical(Object v, String mode) {
        if (!isScanShape(v)) {
            throw new IllegalStateException("规范形只喂 scan 那一形，实为 " + shapeOf(v) + " " + brief(render(v)));
        }
        List<?> top = (List<?>) v;
        String cursor = (String) top.get(0);
        List<String> items = new ArrayList<>();
        for (Object item : (List<?>) top.get(1)) {
            if (!(item instanceof String)) {
                throw new IllegalStateException("scan 负载里出现了非 bulk 项 " + shapeOf(item));
            }
            items.add((String) item);
        }
        List<String> ordered;
        if ("pairs".equals(mode)) {
            if (items.size() % 2 != 0) {
                throw new IllegalStateException("pairs 档收到奇数长度负载 " + items);
            }
            List<List<String>> units = new ArrayList<>();
            for (int i = 0; i < items.size(); i += 2) {
                units.add(Arrays.asList(items.get(i), items.get(i + 1)));
            }
            // 与 python 的 tuple 排序同判据：先比键、键同再比值（两元素定长，无"短者在前"那一支）。
            Collections.sort(units, (a, b) -> {
                int c = a.get(0).compareTo(b.get(0));
                return c != 0 ? c : a.get(1).compareTo(b.get(1));
            });
            ordered = new ArrayList<>();
            for (List<String> unit : units) {
                ordered.addAll(unit);
            }
        } else if ("flat".equals(mode)) {
            ordered = new ArrayList<>(items);
            Collections.sort(ordered);
        } else {
            throw new IllegalStateException("不认识的规范形档位 " + mode + "（只许 pairs / flat）");
        }
        StringBuilder out = new StringBuilder("['").append(cursor).append("',[");
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('\'').append(ordered.get(i)).append('\'');
        }
        return out.append("]]").toString();
    }

    /**
     * 把红过的格子名收成 {@code RED_CELLS}。
     *
     * <p>切分按 {@code " 期 "} 而不是"第一个空格"：本卡的参数里有空格、tab、NBSP，14a 那把尺的
     * {@code substring(0, indexOf(' '))} 会把同族格子名统统截成第一个词（它的 measure 日志里交付
     * 那一支的红集就只剩 {@code ["ZSCAN"]}）。格名本身也已经把空白换成可见标记（见类注释）。</p>
     */
    private static void finish(String method, List<String> seen, List<String> wrong) {
        if (wrong.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (String w : wrong) {
            int at = w.indexOf(" 期 ");
            ids.add(at < 0 ? w : w.substring(0, at));
        }
        assertTrue(wrong.isEmpty(), method + " 不合格 " + wrong.size() + "/" + seen.size()
                + " 格：\n  " + String.join("\n  ", wrong)
                + "\n[RED_CELLS=" + String.join("|", ids) + "]"
                + " [RED_ROWS=" + wrong.size() + "/" + seen.size() + "]");
    }
    // =================================================================================
    // 源码上的那几格用的尺
    // =================================================================================

    /**
     * 注释行整行剔掉（{@code //} 与块注释）。
     *
     * <p>本卡 S 族数的是<em>代码</em>里的命中次数：{@code scanCursorAccepted} 与 {@code scanOptions}
     * 头上的 javadoc 抄了整段上游判据，里面就出现 {@code isspace}、{@code j >= 2}、
     * {@code count < 1} 这些<em>字面量</em>，而 {@code handleScan} 的注释里写了
     * {@code RedisIntegerFormat}。不剔注释的话 S9/S8/S11 三格会读成"这文件里有两份判据"——
     * 那时红的是一格<em>不存在</em>的缺陷。与 {@code RedisZscanPairTest.withoutComments} 同一判据；
     * javadoc 里的 {@code \u00a0} 这类转义在预处理阶段就已变成真字符，注释又被整行剔掉，
     * 所以 S16 那格读的只有 {@code ASCII_SPACE} 那一行代码。</p>
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

    /**
     * 整棵 {@code core/src/main} 里命中几次 —— 判"文法没有第二处实现"用的是树，不是单文件。
     *
     * <p>与 {@link #countOf} 一样先 {@link #withoutComments}：树级那三根的针都是 {@code foo(} 这种
     * <em>调用形状</em>，而注释里完全可能正当地提起同一个名字。本轮验牙的 E2 支量到的正是这一形 ——
     * 往 {@code handleZscan} 里抄一行提到 {@code scanCursorAccepted(} 的注释，S24 就从 5 读成 6，
     * 红的是<em>尺</em>不是代码。文件级与函数级两把尺必须同一判据，否则一个改注释的提交会绊红
     * "某个符号被引用了几次"。</p>
     */
    private static int countInTree(String relativeToRepo, String needle) throws IOException {
        Path root = resolve(relativeToRepo);
        int n = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> all = new ArrayList<>();
            walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(all::add);
            for (Path p : all) {
                n += countOf(withoutComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)),
                        needle);
            }
        }
        return n;
    }

    private static String readRepoFile(String relativeToRepo) throws IOException {
        return new String(Files.readAllBytes(resolve(relativeToRepo)), StandardCharsets.UTF_8);
    }

    /**
     * 签名 → 该方法体（不含首尾大括号）。签名不唯一或切不出闭合就当场抛 —— 宁可量具坏在手里，
     * 也不能让它悄悄把整份文件当方法体（那会把 S1~S10 那一片读数全变成假话）。
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

    /**
     * 三关的先后：游标关 → 选项关 → {@code opt.error} 那道短路。缺一或顺序反都返回实际位次，
     * 让红消息直接说"哪一关不在哪儿"（返回 "1" 才是合格）。
     */
    private static String gateOrder(String body) {
        int cursor = body.indexOf("scanCursorAccepted(");
        int options = body.indexOf("scanOptions(args,");
        int shortCircuit = body.indexOf("opt.error != null");
        if (cursor >= 0 && cursor < options && options < shortCircuit) {
            return "1";
        }
        return "cursor=" + cursor + " options=" + options + " shortCircuit=" + shortCircuit;
    }

    /**
     * 把 {@code ASCII_SPACE} 那一行<em>字面量</em>里的源码转义解回真字符。
     *
     * <p>为什么不解而是"数反斜杠"：这一卡的判据本体就是"这栏里到底装了哪几枚字符"，
     * 数反斜杠只能证明<em>写了</em>六个转义，不能证明它们是 {@code ' '}、{@code '\t'}、
     * {@code '\n'}、{@code '\r'}、{@code 0x0B}、{@code '\f'} 这六枚 —— 也证明不了里面没有 NBSP。</p>
     */
    private static String asciiSpaceColumn(String code) {
        int at = code.indexOf("String ASCII_SPACE = \"");
        if (at < 0) {
            throw new IllegalStateException("找不到 ASCII_SPACE 那一行 —— 量具失效，不作判定");
        }
        int i = at + "String ASCII_SPACE = \"".length();
        StringBuilder out = new StringBuilder();
        while (i < code.length() && code.charAt(i) != '"') {
            char c = code.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            char esc = code.charAt(i + 1);
            if (esc == 'u') {
                out.append((char) Integer.parseInt(code.substring(i + 2, i + 6), 16));
                i += 6;
            } else if (esc == 'n') {
                out.append('\n');
                i += 2;
            } else if (esc == 't') {
                out.append('\t');
                i += 2;
            } else if (esc == 'r') {
                out.append('\r');
                i += 2;
            } else if (esc == 'f') {
                out.append('\f');
                i += 2;
            } else if (esc == 'b') {
                out.append('\b');
                i += 2;
            } else {
                throw new IllegalStateException("ASCII_SPACE 里出现没见过的转义 \\" + esc + " —— 量具不认识，不作判定");
            }
        }
        if (i >= code.length()) {
            throw new IllegalStateException("ASCII_SPACE 那一行的字面量没有右引号 —— 量具失效，不作判定");
        }
        return out.toString();
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
            Path dir = Files.createTempDirectory("zcache-scan-grammar");
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

        /** 身份核验：端口能被 bind 不等于对面是我们那台（与 13z/14a 那两支同一判据）。 */
        private void identify() throws IOException {
            String got = askArgs("PING");
            if (!got.equals("simple:PONG")) {
                throw new IllegalStateException("身份核验失败：期望 simple:PONG 实 " + got
                        + "（端口 " + port + " 上答话的可能根本不是这台服务器）");
            }
        }

        /**
         * 一条命令、一条<em>顶层</em>回复、渲染成参照那把尺的文本。
         *
         * <p>多条帧不静默压成一串：本卡每一格在参照侧都只有一条顶层回复（{@code scan_grammar_probe.run}
         * 是一问一答打出来的），真出现多条就是形状已经与参照分家 ⇒ 抛，不 join。</p>
         */
        String askArgs(String... args) throws IOException {
            return render(value(args));
        }

        /** 发一手、读满一拍、返回那条顶层回复的<em>解析值</em>（P 档那 50 格比的是形状，不是文本）。 */
        Object value(String... args) throws IOException {
            Socket socket = connection();
            try {
                socket.getOutputStream().write(encodeArgs(args));
                socket.getOutputStream().flush();
            } catch (IOException peerGone) {
                throw new IllegalStateException("写不进 " + java.util.Arrays.toString(args)
                        + "：对端已经关了这条连接", peerGone);
            }
            List<Object> values = parseAll(readUntilQuiet(socket.getInputStream(), socket));
            if (values.size() != 1) {
                List<String> parts = new ArrayList<>();
                for (Object v : values) {
                    parts.add(render(v));
                }
                throw new IllegalStateException(java.util.Arrays.toString(args) + " 回了 "
                        + values.size() + " 条顶层帧：" + String.join(" | ", parts)
                        + " —— 本卡每一格在参照侧都只有一条");
            }
            return values.get(0);
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
        }, "test-z-cache-scan-grammar");
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
