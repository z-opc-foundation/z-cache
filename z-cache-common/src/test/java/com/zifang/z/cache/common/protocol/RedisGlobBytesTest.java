package com.zifang.z.cache.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 13t —— 匹配器自己按<em>字节</em>走的那一面：8856 格的字节轴电池。
 *
 * <h2>为什么已有的 {@code RedisGlobTest} 守不住这一面</h2>
 * 那张 68 行的表里<em>一枚非 ASCII 键名都没有</em>（13t 实测：那一支里转义写法 0 处，
 * 非 ASCII 字面量 0 枚）。所以把 {@code RedisGlob} 从"按字节"改回"按 char"，那一支 68 例
 * 照旧全绿 —— 现测：拿 {@code git show HEAD:} 那份按 char 的实现换进工作树跑这两个类，
 * 红的只有本类那一支（{@code RedisGlobTest} 1 条 @Test 0 红，见 CHANGELOG 13t 那段）。
 * 它压根没碰那根轴。这不怪它：它钉的是臂的形状（{@code *} 回溯、{@code [..]} 取反、转义……），
 * 粒度是另一根轴。这一支就是补那根轴的。
 *
 * <h2>期望值是谁给的，两档不一样，别混着吹</h2>
 * <ul>
 *   <li>{@code nocase=0} 的 4428 格：一台<em>活的</em> {@code redis-server 4.0.9}（250 上一次性
 *       实例，{@code KEYS} 与 {@code SCAN MATCH} 两面各问一次且要求彼此一致）给的；</li>
 *   <li>{@code nocase=1} 的 4428 格：<em>没有</em>活参照 —— 键枚举这一族不存在不分大小写的入口，
 *       而 {@code CONFIG GET} 的参数名不归我播种。这一档只由"从权威 5.0.14 源里按行摘出的
 *       {@code stringmatchlen()} 编成的可执行件"给。它是<em>弱一档</em>的证据，写在这里就是为了
 *       不让它冒充参照实测。不过它先过了一道硬闸：同一件提取体在 {@code nocase=0} 那 4428 格上
 *       与活的 4.0.9 <b>0 格不同答</b>（产表时现算，见下面 md5 注释那三行），所以它至少是
 *       "读得懂那一版 C 的机器"，不是我脑内的推演。</li>
 * </ul>
 *
 * <h2>符号性这一档是被量出来的，不是"上游就这么写"</h2>
 * C 的区间臂 {@code int start = pattern[0]}：x86_64 Linux 的 {@code char} 带符号，于是
 * {@code é} 的首字节 0xC3 到了 C 里是 <em>−61</em> 而不是 195。同一份字节数组按无符号编一份，
 * 与按带符号的那份在 8856 格里差 <b>1646</b> 格，而<em>带符号</em>那一档与活的 4.0.9 逐格同答、
 * 无符号那一档差 836 格。所以 {@link RedisGlob#at} 不许写 {@code & 0xFF}。
 * 顺带一句：13s/13r 那把 2928 格的电池对符号性<em>分辨力为 0</em>（两支 C 在它上面逐格同答），
 * 这就是另打一把电池的理由。
 *
 * <h2>这把电池分得出"按字节"与"按 char"吗</h2>
 * 分得出，而且分得很开：拿 git {@code d88bd3f}（改动之前，按 char 走）那一版当影子实现逐格比，
 * 8856 格里<b>2704</b> 格不同答。这句话不是形容词，产表的脚本
 * {@code ~/.cache/zcache_gauges/gen_glob_bytes_table.py} 把它当闸门：影子与本机的读数一旦
 * 逐格相同（比如哪天电池退化了），脚本直接 FATAL 不产表。
 *
 * <h2>与上游仍不同处</h2>
 * <ol>
 *   <li>本电池<em>不含</em>裸 {@code *} / {@code **} / {@code ***} 那三枚图案 —— 它们对空串键名
 *       是 4.0.9 与 5.0.14 的版本差（{@code util.c:51} 多挡一个 {@code stringLen}），把版本差混进
 *       粒度电池里，将来谁也说清红的是哪一笔。那一格由
 *       {@code RedisGlobDeliveryTest#onlyTheBookedRowsStillDifferFromTheReference} 单独钉。</li>
 *   <li>键名在进匹配器之前已被 {@code new String(bytes, UTF_8)} 解过一次
 *       （{@code RedisServerHandler.java:124} → {@code RespArray.java:105} →
 *       {@code RespBulkString.java:57}），非法 UTF-8 的字节序列在那一步就换了字节。
 *       本电池的 27 枚键名<em>全是合法 UTF-8</em>（生成脚本里 {@code _c()} 双向自校验），
 *       所以这里<em>量不到</em>那一笔 —— 那是另一张卡，不许被"8856 格 0 不同答"顺带盖掉。</li>
 * </ol>
 *
 * <h2>表是机器产的</h2>
 * 下面 {@link #names()} 与 {@link #rows()} 两段（27 枚键名 + 164 行图案，每行两个 27 位掩码）
 * 由 {@code ~/.cache/zcache_gauges/gen_glob_bytes_table.py} 从三份读数生成；人手敲一格期望值
 * 就等于撒谎。要改请重跑那把尺（读数在 {@code ~/.cache/zcache_gauges/}，日志
 * {@code logs/glob_bytes_report_13t.log}），改一格下标/掩码不算修bug。
 * 这一支<em>第一版就是靠红着进门</em>的：那次表里 17 行的 😀 被转义成了单反斜杠-u 加
 * {@code 1F600}（Java 会把它读成 "U+1F60" 再加一个字符 {@code 0}），跑出来 19 格红。红本身是好事 —— 它说明期望值不是
 * 脑补的；坏的是转义能一路通过"看起来合理"的产表检查。所以生成器现在多了一道
 * "转义→反解必须逐字相同"的闸，上面那两格 {@code T14}/{@code T15} 是仓里的第二道。
 */
class RedisGlobBytesTest {

    private static final List<String> NAMES = names();
    private static final List<Row> ROWS = rows();

    // =================================================================================
    // 一、本机匹配器 ⇄ 那 8856 格的期望（328 个掩码，一格一格比）
    // =================================================================================

    @Test
    void matcherAnswersTheByteBatteryTheWayTheReferenceDoes() {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        long cells = 0;
        for (int i = 0; i < ROWS.size(); i++) {
            Row row = ROWS.get(i);
            for (int arm = 0; arm < 2; arm++) {
                boolean nocase = arm == 1;
                int expected = nocase ? row.mask1 : row.mask0;
                int got = 0;
                for (int t = 0; t < NAMES.size(); t++) {
                    String name = NAMES.get(t);
                    // 逐格问的是匹配器本体；这里没有服务器，也没有第二把尺可依赖。
                    if (RedisGlob.matches(row.pattern, name, nocase)) {
                        got |= 1 << t;
                        cells++;
                    }
                }
                seen.add("R" + i + arm);
                if (got != expected) {
                    wrong.add("R" + i + arm + " pattern=" + ascii(row.pattern)
                            + " nocase=" + arm + " 期望掩码=" + expected + " 实得=" + got
                            + " 差在键名 " + whichDiff(expected, got));
                }
            }
        }
        cell(seen, wrong, "CELLS_ASKED", String.valueOf(ROWS.size() * 2), "328");
        cell(seen, wrong, "CELLS_TRUE", String.valueOf(cells), "1780");
        finish("matcherAnswersTheByteBatteryTheWayTheReferenceDoes", seen, wrong);
    }

    // =================================================================================
    // 二、这把电池带着它声称的那两根轴（轴不在，上面那一格就是空跑）
    // =================================================================================

    @Test
    void theBatteryCarriesTheAxesItClaimsToMeasure() {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        cell(seen, wrong, "T1_NAMES", String.valueOf(NAMES.size()), "27");
        cell(seen, wrong, "T2_DISTINCT", String.valueOf(new LinkedHashSet<>(NAMES).size()), "27");
        // 粒度轴：字节数 ≠ 码元数的键名
        int byteNeChar = 0;
        for (String t : NAMES) {
            if (t.getBytes(StandardCharsets.UTF_8).length != t.length()) byteNeChar++;
        }
        cell(seen, wrong, "T3_BYTE_NE_CHAR", String.valueOf(byteNeChar), "13");
        cell(seen, wrong, "T4_EMPTY_SEEDED", String.valueOf(NAMES.contains("")), "true");
        // 符号性轴：区间端点落在 0x80 之外（首字节带符号与否会改判据）的那些图案
        int bracket = 0;
        int bracketNonAscii = 0;
        int nonAscii = 0;
        for (Row r : ROWS) {
            boolean hasBracket = r.pattern.indexOf('[') >= 0;
            boolean hasNonAscii = false;
            for (int i = 0; i < r.pattern.length(); i++) {
                if (r.pattern.charAt(i) > 0x7f) hasNonAscii = true;
            }
            if (hasBracket) bracket++;
            if (hasNonAscii) nonAscii++;
            if (hasBracket && hasNonAscii) bracketNonAscii++;
        }
        cell(seen, wrong, "T5_ROWS", String.valueOf(ROWS.size()), "164");
        cell(seen, wrong, "T6_ROWS_DISTINCT", String.valueOf(new LinkedHashSet<>(patterns()).size()), "164");
        cell(seen, wrong, "T7_NON_ASCII_PATTERNS", String.valueOf(nonAscii), "132");
        cell(seen, wrong, "T8_BRACKET_PATTERNS", String.valueOf(bracket), "153");
        cell(seen, wrong, "T9_BRACKET_NON_ASCII", String.valueOf(bracketNonAscii), "126");
        // 两档（nocase 0/1）不同的行：不分大小写那一面真被问到了
        int armsDiffer = 0;
        int trivial = 0;
        int hits = 0;
        int hitsOnByteAxis = 0;
        for (Row r : ROWS) {
            if (r.mask0 != r.mask1) armsDiffer++;
            if (r.mask0 == 0 || r.mask0 == FULL) trivial++;
            for (int t = 0; t < NAMES.size(); t++) {
                boolean b0 = r.bit(r.mask0, t), b1 = r.bit(r.mask1, t);
                if (b0) hits++;
                if (b1) hits++;
                boolean byteAxis = NAMES.get(t).getBytes(StandardCharsets.UTF_8).length
                        != NAMES.get(t).length();
                if (byteAxis) {
                    if (b0) hitsOnByteAxis++;
                    if (b1) hitsOnByteAxis++;
                }
                // 掩码不许有电池之外的位：多一位就是下标漂了
                if ((r.mask0 | r.mask1) >> NAMES.size() != 0) {
                    wrong.add("MASK_OVERFLOW pattern=" + ascii(r.pattern));
                }
            }
        }
        cell(seen, wrong, "T10_ARMS_DIFFER", String.valueOf(armsDiffer), "60");
        cell(seen, wrong, "T11_NON_TRIVIAL", String.valueOf(ROWS.size() - trivial), "132");
        cell(seen, wrong, "T12_HITS", String.valueOf(hits), "1780");
        cell(seen, wrong, "T13_HITS_ON_BYTE_AXIS", String.valueOf(hitsOnByteAxis), "34");
        // 非 BMP 那一档必须真在表里：13t 产第一版这张表时把 😀 转义成了 "\\u1F600"，
        // Java 词法把它读成 "U+1F60 加字符 0" —— 图案就此换了，而红消息看着像实现错。
        // 生成器现在自己反解校验（gen_glob_bytes_table.java_unescape），这一格是仓里的第二道：
        // 转义再写歪，码元数就不会大于码点数，这两格先红。
        cell(seen, wrong, "T14_ASTRAL_NAMES", String.valueOf(countAstral(NAMES)), "2");
        cell(seen, wrong, "T15_ASTRAL_PATTERNS", String.valueOf(countAstral(patterns())), "15");
        finish("theBatteryCarriesTheAxesItClaimsToMeasure", seen, wrong);
    }

    /** 含非 BMP 码元（代理对）的枚数 —— 数的是 Java 里的 char 数 &gt; 码点数。 */
    private static int countAstral(List<String> xs) {
        int n = 0;
        for (String x : xs) {
            if (x.length() > x.codePointCount(0, x.length())) n++;
        }
        return n;
    }

    // =================================================================================

    private static final int FULL = (1 << 27) - 1;

    private static List<String> patterns() {
        List<String> out = new ArrayList<>();
        for (Row r : ROWS) out.add(r.pattern);
        return out;
    }

    /** 期望与实得差在哪几枚键名上 —— 红消息要能一眼指出格子，别让人去解掩码。 */
    private static String whichDiff(int expected, int got) {
        List<String> diff = new ArrayList<>();
        for (int t = 0; t < NAMES.size(); t++) {
            boolean e = ((expected >>> t) & 1) != 0, g = ((got >>> t) & 1) != 0;
            if (e != g) diff.add(ascii(NAMES.get(t)) + (e ? "(应收)" : "(收多了)"));
        }
        return diff.toString();
    }

    private static void cell(List<String> seen, List<String> wrong, String id, String got, String want) {
        seen.add(id);
        if (!got.equals(want)) wrong.add(id + " 期望=" + want + " 实得=" + got);
    }

    private static void finish(String what, List<String> seen, List<String> wrong) {
        if (!wrong.isEmpty()) {
            throw new AssertionError(what + " 有 " + wrong.size() + " 格不合格（共问 " + seen.size()
                    + " 格）：\n  " + String.join("\n  ", wrong));
        }
    }

    private static String ascii(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04X", (int) c));
            else if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static final class Row {
        final String pattern;
        final int mask0;
        final int mask1;

        Row(String pattern, int mask0, int mask1) {
            this.pattern = pattern;
            this.mask0 = mask0;
            this.mask1 = mask1;
        }

        boolean bit(int mask, int i) {
            return ((mask >>> i) & 1) != 0;
        }
    }

    private static Row row(String pattern, int mask0, int mask1) {
        return new Row(pattern, mask0, mask1);
    }

    // =================================================================================
    // 判据表（两段由 ~/.cache/zcache_gauges/gen_glob_bytes_table.py 从三份读数生成）
    // =================================================================================

    private static List<String> names() {
        List<String> out = new ArrayList<>();
                // 以下三段由 ~/.cache/zcache_gauges/gen_glob_bytes_table.py 从三份读数生成，
        // bytes_ref_13t.tsv        md5=0d0090ead1e4e5a68ac333a372c266b9  活的参照 4.0.9 的交付读数
        // bytes_c_signed.tsv       md5=45112af7bbc306e909a549b4fcf22f9a  摘自 5.0.14 源码的 C 量具
        // bytes_java.tsv           md5=45112af7bbc306e909a549b4fcf22f9a  本机匹配器（只当闸门，不进期望值）
        // 三道闸门（产表时现算，任一不过就不产）：提取体⇄参照 不同答=0 格、本机⇄期望源 不同答=0 格、符号性可分辨=1646 格。
        // 人手改下面任何一格期望值就等于撒谎；要改请重跑那把尺。
        out.add("");
        out.add("a");
        out.add("b");
        out.add("z");
        out.add("Z");
        out.add("!");
        out.add("0");
        out.add("~");
        out.add("\\");
        out.add("[");
        out.add("]");
        out.add("-");
        out.add("^");
        out.add("\u00E9");
        out.add("\u03A9");
        out.add("\u00A8");
        out.add("\u4E2D");
        out.add("\u4E00");
        out.add("\u9FA5");
        out.add("\u6587");
        out.add("\uD83D\uDE00");
        out.add("a\u4E2D");
        out.add("\u4E2Da");
        out.add("\u00E9a");
        out.add("\u4E2D\u4E2D");
        out.add("aa");
        out.add("\uD83D\uDE00a");
        return out;
    }

    private static List<Row> rows() {
        List<Row> out = new ArrayList<>();
                // 每行一枚图案 + 两个掩码：bit i = 第 i 枚键名收不收（低位起）。
        out.add(row("[a-a]", 2, 2));
        out.add(row("[^a-a]", 8188, 8188));
        out.add(row("[a-\u00E9]", 8050, 8034));
        out.add(row("[^a-\u00E9]", 140, 156));
        out.add(row("[a-\u03A9]", 8050, 8034));
        out.add(row("[^a-\u03A9]", 140, 156));
        out.add(row("[a-\u00A8]", 8050, 8034));
        out.add(row("[^a-\u00A8]", 140, 156));
        out.add(row("[a-\u4E2D]", 8050, 8034));
        out.add(row("[^a-\u4E2D]", 140, 156));
        out.add(row("[a-\u4E00]", 8050, 8034));
        out.add(row("[^a-\u4E00]", 140, 156));
        out.add(row("[a-\u9FA5]", 8050, 8034));
        out.add(row("[^a-\u9FA5]", 140, 156));
        out.add(row("[a-\uD83D\uDE00]", 8050, 8034));
        out.add(row("[^a-\uD83D\uDE00]", 140, 156));
        out.add(row("[a-z]", 14, 30));
        out.add(row("[^a-z]", 8176, 8160));
        out.add(row("[a-Z]", 5906, 0));
        out.add(row("[^a-Z]", 2284, 8190));
        out.add(row("[Z-a]", 5906, 0));
        out.add(row("[^Z-a]", 2284, 8190));
        out.add(row("[Z-\u00E9]", 2160, 8062));
        out.add(row("[^Z-\u00E9]", 6030, 128));
        out.add(row("[Z-\u03A9]", 2160, 8062));
        out.add(row("[^Z-\u03A9]", 6030, 128));
        out.add(row("[Z-\u00A8]", 2160, 8062));
        out.add(row("[^Z-\u00A8]", 6030, 128));
        out.add(row("[Z-\u4E2D]", 2160, 8062));
        out.add(row("[^Z-\u4E2D]", 6030, 128));
        out.add(row("[Z-\u4E00]", 2160, 8062));
        out.add(row("[^Z-\u4E00]", 6030, 128));
        out.add(row("[Z-\u9FA5]", 2160, 8062));
        out.add(row("[^Z-\u9FA5]", 6030, 128));
        out.add(row("[Z-\uD83D\uDE00]", 2160, 8062));
        out.add(row("[^Z-\uD83D\uDE00]", 6030, 128));
        out.add(row("[Z-z]", 5918, 24));
        out.add(row("[^Z-z]", 2272, 8166));
        out.add(row("[Z-Z]", 16, 24));
        out.add(row("[^Z-Z]", 8174, 8166));
        out.add(row("[!-a]", 8050, 8034));
        out.add(row("[^!-a]", 140, 156));
        out.add(row("[!-\u00E9]", 32, 32));
        out.add(row("[^!-\u00E9]", 8158, 8158));
        out.add(row("[!-\u03A9]", 32, 32));
        out.add(row("[^!-\u03A9]", 8158, 8158));
        out.add(row("[!-\u00A8]", 32, 32));
        out.add(row("[^!-\u00A8]", 8158, 8158));
        out.add(row("[!-\u4E2D]", 32, 32));
        out.add(row("[^!-\u4E2D]", 8158, 8158));
        out.add(row("[!-\u4E00]", 32, 32));
        out.add(row("[^!-\u4E00]", 8158, 8158));
        out.add(row("[!-\u9FA5]", 32, 32));
        out.add(row("[^!-\u9FA5]", 8158, 8158));
        out.add(row("[!-\uD83D\uDE00]", 32, 32));
        out.add(row("[^!-\uD83D\uDE00]", 8158, 8158));
        out.add(row("[!-z]", 8062, 8062));
        out.add(row("[^!-z]", 128, 128));
        out.add(row("[!-Z]", 2160, 8062));
        out.add(row("[^!-Z]", 6030, 128));
        out.add(row("[0-a]", 5970, 5954));
        out.add(row("[^0-a]", 2220, 2236));
        out.add(row("[0-\u00E9]", 2144, 2144));
        out.add(row("[^0-\u00E9]", 6046, 6046));
        out.add(row("[0-\u03A9]", 2144, 2144));
        out.add(row("[^0-\u03A9]", 6046, 6046));
        out.add(row("[0-\u00A8]", 2144, 2144));
        out.add(row("[^0-\u00A8]", 6046, 6046));
        out.add(row("[0-\u4E2D]", 2144, 2144));
        out.add(row("[^0-\u4E2D]", 6046, 6046));
        out.add(row("[0-\u4E00]", 2144, 2144));
        out.add(row("[^0-\u4E00]", 6046, 6046));
        out.add(row("[0-\u9FA5]", 2144, 2144));
        out.add(row("[^0-\u9FA5]", 6046, 6046));
        out.add(row("[0-\uD83D\uDE00]", 2144, 2144));
        out.add(row("[^0-\uD83D\uDE00]", 6046, 6046));
        out.add(row("[0-z]", 5982, 5982));
        out.add(row("[^0-z]", 2208, 2208));
        out.add(row("[0-Z]", 80, 5982));
        out.add(row("[^0-Z]", 8110, 2208));
        out.add(row("[\u00E9-a]", 8050, 8034));
        out.add(row("[^\u00E9-a]", 140, 156));
        out.add(row("[\u00E9-\u00E9]", 0, 0));
        out.add(row("[^\u00E9-\u00E9]", 8190, 8190));
        out.add(row("[\u00E9-\u03A9]", 0, 0));
        out.add(row("[^\u00E9-\u03A9]", 8190, 8190));
        out.add(row("[\u00E9-\u00A8]", 0, 0));
        out.add(row("[^\u00E9-\u00A8]", 8190, 8190));
        out.add(row("[\u00E9-\u4E2D]", 0, 0));
        out.add(row("[^\u00E9-\u4E2D]", 8190, 8190));
        out.add(row("[\u00E9-\u4E00]", 0, 0));
        out.add(row("[^\u00E9-\u4E00]", 8190, 8190));
        out.add(row("[\u00E9-\u9FA5]", 0, 0));
        out.add(row("[^\u00E9-\u9FA5]", 8190, 8190));
        out.add(row("[\u00E9-\uD83D\uDE00]", 0, 0));
        out.add(row("[^\u00E9-\uD83D\uDE00]", 8190, 8190));
        out.add(row("[\u00E9-z]", 8062, 8062));
        out.add(row("[^\u00E9-z]", 128, 128));
        out.add(row("[\u00E9-Z]", 2160, 8062));
        out.add(row("[^\u00E9-Z]", 6030, 128));
        out.add(row("[\u03A9-a]", 8050, 8034));
        out.add(row("[^\u03A9-a]", 140, 156));
        out.add(row("[\u03A9-\u00E9]", 0, 0));
        out.add(row("[^\u03A9-\u00E9]", 8190, 8190));
        out.add(row("[\u03A9-\u03A9]", 0, 0));
        out.add(row("[^\u03A9-\u03A9]", 8190, 8190));
        out.add(row("[\u03A9-\u00A8]", 0, 0));
        out.add(row("[^\u03A9-\u00A8]", 8190, 8190));
        out.add(row("[\u03A9-\u4E2D]", 0, 0));
        out.add(row("[^\u03A9-\u4E2D]", 8190, 8190));
        out.add(row("[\u03A9-\u4E00]", 0, 0));
        out.add(row("[^\u03A9-\u4E00]", 8190, 8190));
        out.add(row("[\u03A9-\u9FA5]", 0, 0));
        out.add(row("[^\u03A9-\u9FA5]", 8190, 8190));
        out.add(row("[\u03A9-\uD83D\uDE00]", 0, 0));
        out.add(row("[^\u03A9-\uD83D\uDE00]", 8190, 8190));
        out.add(row("[\u03A9-z]", 8062, 8062));
        out.add(row("[^\u03A9-z]", 128, 128));
        out.add(row("[\u03A9-Z]", 2160, 8062));
        out.add(row("[^\u03A9-Z]", 6030, 128));
        out.add(row("[\u4E2D-a]", 8050, 8034));
        out.add(row("[^\u4E2D-a]", 140, 156));
        out.add(row("[\u4E2D-\u00E9]", 0, 0));
        out.add(row("[^\u4E2D-\u00E9]", 8190, 8190));
        out.add(row("[\u4E2D-\u03A9]", 0, 0));
        out.add(row("[^\u4E2D-\u03A9]", 8190, 8190));
        out.add(row("[\u4E2D-\u00A8]", 0, 0));
        out.add(row("[^\u4E2D-\u00A8]", 8190, 8190));
        out.add(row("[\u4E2D-\u4E2D]", 0, 0));
        out.add(row("[^\u4E2D-\u4E2D]", 8190, 8190));
        out.add(row("[\u4E2D-\u4E00]", 0, 0));
        out.add(row("[^\u4E2D-\u4E00]", 8190, 8190));
        out.add(row("[\u4E2D-\u9FA5]", 0, 0));
        out.add(row("[^\u4E2D-\u9FA5]", 8190, 8190));
        out.add(row("[\u4E2D-\uD83D\uDE00]", 0, 0));
        out.add(row("[^\u4E2D-\uD83D\uDE00]", 8190, 8190));
        out.add(row("[\u4E2D-z]", 8062, 8062));
        out.add(row("[^\u4E2D-z]", 128, 128));
        out.add(row("[\u4E2D-Z]", 2160, 8062));
        out.add(row("[^\u4E2D-Z]", 6030, 128));
        out.add(row("[\u00E9]", 0, 0));
        out.add(row("[^\u00E9]", 8190, 8190));
        out.add(row("[\u4E2D]", 0, 0));
        out.add(row("[\uD83D\uDE00]", 0, 0));
        out.add(row("[\\\u00E9]", 0, 0));
        out.add(row("[\u00E9\\\u4E2D]", 0, 0));
        out.add(row("[\u00E9-", 2048, 2048));
        out.add(row("[a-", 2050, 2050));
        out.add(row("[]a-c]", 0, 0));
        out.add(row("[a-c]\\", 0, 0));
        out.add(row("\\\u00E9", 8192, 8192));
        out.add(row("\u00E9?", 8388608, 8388608));
        out.add(row("?\u00E9", 0, 0));
        out.add(row("??\u00E9", 0, 0));
        out.add(row("\u00E9*", 8396800, 8396800));
        out.add(row("*\u00E9", 8192, 8192));
        out.add(row("??", 33611776, 33611776));
        out.add(row("???", 9371648, 9371648));
        out.add(row("????", 7340032, 7340032));
        out.add(row("??????", 16777216, 16777216));
        out.add(row("???????", 0, 0));
        out.add(row("[\u00A8-\u4E2D]", 0, 0));
        out.add(row("[a-\u00E9x]", 8050, 8034));
        out.add(row("[a-\u00FF]", 8050, 8034));
        return out;
    }
}
