package com.zifang.z.cache.core.server;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 13s —— {@code KEYS} 与 {@code SCAN MATCH} 交付的是哪一门 glob 语言。
 *
 * <h2>这一支量的不是匹配器，是"接没接上"</h2>
 * 匹配器自己那张表在 {@code RedisGlobTest}（62 行，13r 已拿一台活的参照逐行问过，问得到的
 * 59 行逐行同答）。这一支钉的是另一件事：<b>命令层交给客户端的那一条码，是不是同一把尺给出的</b>。
 * 13s 之前不是 —— {@code KEYS} 与 {@code SCAN} 打的是 {@code globToRegex} +
 * {@code String.matches}（正则那一门语言），全仓只有 {@code CONFIG GET} 一家走 {@code RedisGlob}。
 * 那一轮拿 2928 个 {@code (图案, 键名)} 对逐对问过一台 {@code redis-server 4.0.9}，账是这样的：
 * <ul>
 *   <li>交付分歧 74 格，其中 <b>52 格</b>是匹配器自己判得对、只是没接上（本卡收的就是这些）；</li>
 *   <li>接线之后仍分歧 33 格；把上游那句单根 {@code *} 的快路也抄上（{@code db.c:545}、
 *       {@code db.c:663}）之后剩 <b>32</b> 格；</li>
 *   <li>那 32 格里 2 格是版本差（空串键名遇上 {@code **}／{@code ***}：4.0.9 的循环头只挡
 *       {@code patternLen}，5.0.14 的循环头 {@code util.c:51} 多挡一个 {@code stringLen}，
 *       本类照 5.0.14），30 格是 C 按字节走、这里按 {@code char} 走（卡 #36）。</li>
 * </ul>
 *
 * <h2>为什么"抄上游快路"不是顺手优化</h2>
 * 空串键名在 {@code KEYS *} 底下必须收得出来，那是那句快路给的；而匹配器按 5.0.14 判它不匹配。
 * 所以只接线不抄快路，会把"我们现在碰巧对的那一格"改错 —— 13r 的 C 桶（11 格）里就有这一格。
 * 第三支 @Test 因此单独钉它，并拿 {@code KEYS **} 当对照：那一串只差那一句快路。
 *
 * <h2>表是机器产的</h2>
 * 下面 {@link #names()} 与 {@link #patterns()} 里那两段（48 个键名 + 61 行图案，每行两串下标）
 * 由 {@code ~/.cache/zcache_gauges/gen_glob_delivery_table.py} 从 13r 落盘的两份电池读数生成
 * （{@code battery_ref.tsv} md5 {@code fc4e54245739d6b57573927f40f484d4}、
 * {@code battery_ours.tsv} md5 {@code eead3f6803711e7d50d4f74c0c3de9c1}）。人手敲一格期望值
 * 就等于撒谎 —— 同一轮里我真把那两个文件传反过一次，产出一张看着合理的错表，那道拦现在写在
 * {@code glob_battery_diff.py} 的表头判据里。
 * 两串下标：第一列是"接上 {@code RedisGlob} 与那句快路之后该收下的"，第二列是"参照那一台
 * 实际收下的"。哪天 {@code RedisGlob} 自己改了（比如把 #36 修了），第一列会整列作废 ——
 * 那一格红了就该重跑生成器，不许手改下标。
 *
 * <h2>与上游仍不同处，逐条写明</h2>
 * <ol>
 *   <li>字节 vs 码元：上面那 30 格，卡 #36；</li>
 *   <li>{@code **} / {@code ***} 对空串键名：照 5.0.14，不照 4.0.9（参照那台是 4.0.9）；</li>
 *   <li>我们这一台 {@code SCAN} 的游标是排序表上的下标，上游是反向二进制游标 —— 游标<em>值</em>
 *       本来就不要求相同（上游只保证"回 0 即扫完"），所以这里钉的是<em>全集</em>与两面一致性，
 *       不钉游标怎么变；</li>
 *   <li>{@code HSCAN} / {@code SSCAN} / {@code ZSCAN} 与 {@code PSUBSCRIBE} 那四处仍是各自一份
 *       {@code globToRegex}（上游这五家共用 {@code stringmatchlen}：{@code db.c:748}、
 *       {@code db.c:756}、{@code pubsub.c:256}、{@code pubsub.c:351}）。那四处<em>本轮没量过</em>
 *       （13r 那把电池问的是键枚举），所以这里只记名、不动手。</li>
 * </ol>
 */
class RedisGlobDeliveryTest {

    private static final long DEADLINE_MS = 8_000L;
    /** SCAN 的 COUNT 只是提示：故意给一个比键数小得多的值，逼交付那一侧真的转起来。 */
    private static final int SCAN_COUNT = 3;

    // =================================================================================
    // 一、交付 == 匹配器（接线本身），而且两面彼此一致
    // =================================================================================

    @Test
    void keysAndScanDeliverTheSameSetTheMatcherDoes() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        try (Battery battery = Battery.open()) {
            cell(seen, wrong, "SEED", String.valueOf(battery.dbsize()), String.valueOf(NAMES.size()));
            for (int i = 0; i < PATS.size(); i++) {
                Pat pat = PATS.get(i);
                Set<String> expected = pat.ours();
                Set<String> byKeys = battery.keys(pat.pattern);
                Set<String> byScan = battery.scanAll(pat.pattern, SCAN_COUNT);
                setCell(seen, wrong, "K" + i, byKeys, expected, pat.pattern);
                setCell(seen, wrong, "S" + i, byScan, expected, pat.pattern);
                setCell(seen, wrong, "A" + i, byScan, byKeys, pat.pattern);
            }
            // "尺在动"那一格：COUNT=3 之下 `*` 必须真转了多圈（48 枚键 / 每圈 3 枚 ⇒ 至少 16 圈）。
            // 一圈到底说明 MATCH 根本没参与，那上面 61×3 格就全是一次空跑。
            battery.scanAll("*", SCAN_COUNT);
            cell(seen, wrong, "ROUNDS", String.valueOf(battery.lastRounds() >= 16), "true");
        }
        finish("keysAndScanDeliverTheSameSetTheMatcherDoes", seen, wrong);
    }

    // =================================================================================
    // 二、交付 vs 参照：只剩记过账的那 32 格，而且每一格都归得因
    // =================================================================================

    @Test
    void onlyTheBookedRowsStillDifferFromTheReference() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        int booked = 0;
        for (int i = 0; i < PATS.size(); i++) {
            Pat pat = PATS.get(i);
            for (String name : symmetric(pat.ours(), pat.ref())) {
                booked++;
                boolean versionDrift = "".equals(name)
                        && ("**".equals(pat.pattern) || "***".equals(pat.pattern));
                boolean byteAxis = name.getBytes(StandardCharsets.UTF_8).length != name.length();
                if (!versionDrift && !byteAxis) {
                    // 一枚纯 ASCII 键名如果也分歧，那就不是 #36 那一笔，是一笔没归因的坏。
                    wrong.add("UNATTRIBUTED #" + i + " pattern=" + ascii(pat.pattern)
                            + " text=" + ascii(name));
                }
                seen.add("D" + i);
            }
        }
        cell(seen, wrong, "BOOKED_TOTAL", String.valueOf(booked), "32");
        cell(seen, wrong, "BYTE_AXIS_NAMES", String.valueOf(BYTE_NE_CHAR.size()), "10");
        finish("onlyTheBookedRowsStillDifferFromTheReference", seen, wrong);
    }

    // =================================================================================
    // 三、那根单独的 `*`：快路不是匹配器
    // =================================================================================

    @Test
    void aLoneStarBypassesTheMatcherTheWayKeysCommandDoes() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        try (Battery battery = Battery.open()) {
            Set<String> everything = new TreeSet<>(NAMES);
            Set<String> withoutEmpty = new TreeSet<>(NAMES);
            withoutEmpty.remove("");
            // KEYS * 收下全部 48 枚，含那枚空串键名（上游 db.c:545 的 allkeys 给的）
            setCell(seen, wrong, "F1", battery.keys("*"), everything, pat("*"));
            // SCAN 0 MATCH * 同一件事，走的是 db.c:663 那句 use_pattern
            setCell(seen, wrong, "F2", battery.scanAll("*", SCAN_COUNT), everything, pat("*"));
            // 对照：`**` 没有那句快路（上游只对<em>恰好一根</em> * 短路），于是空串键名收不出来。
            // 这一格同时钉住"快路 ≠ 匹配器把 * 判成恒真"—— 两种写法在 `**` 上就分家了。
            setCell(seen, wrong, "F3", battery.keys("**"), withoutEmpty, pat("**"));
            setCell(seen, wrong, "F4", battery.keys("***"), withoutEmpty, pat("***"));
            Set<String> star = new TreeSet<>(battery.keys("*"));
            star.remove("");
            setCell(seen, wrong, "F5", star, withoutEmpty, pat("*"));
            cell(seen, wrong, "F6", String.valueOf(battery.keys("*").size()),
                    String.valueOf(NAMES.size()));
        }
        finish("aLoneStarBypassesTheMatcherTheWayKeysCommandDoes", seen, wrong);
    }

    // =================================================================================
    // 四、结构守卫：键枚举这一族只剩一门 glob 语言
    // =================================================================================

    @Test
    void keyEnumerationHasOneGlobDialectLeft() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        List<String> store = codeLinesOf(
                "z-cache-core/src/main/java/com/zifang/z/cache/core/storage/MemoryStore.java");
        List<String> handler = codeLinesOf(
                "z-cache-core/src/main/java/com/zifang/z/cache/core/command/CommandHandler.java");
        // 正则那一门语言在"键枚举"这一族里必须一处不剩（HSCAN/SSCAN/ZSCAN/PUBSUB 是另一笔账，
        // 那几家各有自己的私有 globToRegex，不在这两个文件里）。
        cell(seen, wrong, "G1", String.valueOf(countContaining(store, "globToRegex")), "0");
        cell(seen, wrong, "G2", String.valueOf(countContaining(handler, "globToRegex")), "0");
        // 一把尺：整个存储层只有 keyPatternMatches 那一条 RedisGlob 调用点
        cell(seen, wrong, "G3", String.valueOf(countContaining(store, "RedisGlob.matches(")), "1");
        // 两个交付点各调用一次 + 定义一次 = 三处；SCAN/KEYS 少任何一家，这个数就掉
        cell(seen, wrong, "G4", String.valueOf(countContaining(store, "keyPatternMatches(")), "3");
        cell(seen, wrong, "G5", String.valueOf(countContaining(store, "isMatchAllPattern(")), "3");
        // 正则那一门语言不许以任何写法回到这个文件。上一格那种"数 .matches( 的个数为 0"的写法
        // 是错的：共享 matcher 自己就含这三个字符，G3 与它永远不可能同时成立。
        for (String[] arm : new String[][]{{"G6a", "Pattern.compile("}, {"G6b", "Pattern.matches("},
                {"G6c", "java.util.regex"}}) {
            cell(seen, wrong, arm[0], String.valueOf(countContaining(store, arm[1])), "0");
        }
        // 这个文件里每一处 `.matches(` 都必须就是那条共享 matcher：老写法
        // {@code key.matches(regex)} 在这一格显形（1-0、2-1 都不等于 0）。
        cell(seen, wrong, "G7", String.valueOf(countContaining(store, ".matches(")
                - countContaining(store, "RedisGlob.matches(")), "0");
        // CONFIG GET 仍得是 nocase=1 那一家（不能顺着这次接线被拖进键枚举的语言）
        cell(seen, wrong, "G8", String.valueOf(countContaining(handler,
                "RedisGlob.matches(pattern, AUTO_AOF_REWRITE")), "2");
        for (int i = 0; i < 2; i++) {
            String body = i == 0 ? bodyOf(store, "public List<String> keysDb(")
                    : bodyOf(store, "public Object[] scan(");
            cell(seen, wrong, "G9" + i, String.valueOf(body.contains("keyPatternMatches(")), "true");
            cell(seen, wrong, "G10" + i, String.valueOf(body.contains("isMatchAllPattern(")), "true");
        }
        finish("keyEnumerationHasOneGlobDialectLeft", seen, wrong);
    }

    // =================================================================================
    // 五、这张表自己带着它所声称的那根轴（字面量没被编辑器折掉）
    // =================================================================================

    @Test
    void theBatteryCarriesTheAxisItClaimsToMeasure() {
        List<String> seen = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        cell(seen, wrong, "T1", String.valueOf(NAMES.size()), "48");
        cell(seen, wrong, "T2", String.valueOf(new LinkedHashSet<>(NAMES).size()), "48");
        cell(seen, wrong, "T3", String.valueOf(BYTE_NE_CHAR.size()), "10");
        // é 的两种写法必须<em>同时</em>在表里而且互不相等：归一化一旦把它们折成一枚，
        // T4/T5 就红，而 #36 那 30 格里跟 é 沾边的几格会静默变成"没人守"。
        cell(seen, wrong, "T4", String.valueOf(NAMES.contains("\u00E9")), "true");
        cell(seen, wrong, "T5", String.valueOf(NAMES.contains("e\u0301")
                && !"\u00E9".equals("e\u0301")), "true");
        cell(seen, wrong, "T6", String.valueOf(NAMES.contains("\uD83D\uDE00")), "true");
        cell(seen, wrong, "T7", String.valueOf("\uD83D\uDE00".length()), "2");
        cell(seen, wrong, "T8", String.valueOf("\uD83D\uDE00".codePointCount(0, 2)), "1");
        cell(seen, wrong, "T9", String.valueOf(PATS.size()), "61");
        finish("theBatteryCarriesTheAxisItClaimsToMeasure", seen, wrong);
    }

    // =================================================================================
    // 判据表（两段由 ~/.cache/zcache_gauges/gen_glob_delivery_table.py 从 13r 电池读数生成）
    // =================================================================================

    private static List<String> names() {
        List<String> out = new ArrayList<>();
        // 生成：~/.cache/zcache_gauges/gen_glob_delivery_table.py 从 13r 两份电池读数产出
        //（现算桶数 A=2843 B=52 C=11 D=22、接线+快路后与参照仍差 32 格，都对得上）。
        // 人手改下面任何一格期望值就等于撒谎；要改请重跑那把尺。
        out.add("auto-aof-rewrite-percentage");
        out.add("auto-aof-rewrite-perc");
        out.add("");
        out.add("x");
        out.add("anything");
        out.add("auto-aof-rewrite-min-size");
        out.add("maxmemory");
        out.add("axxb");
        out.add("ab");
        out.add("ba");
        out.add("abc");
        out.add("aa");
        out.add("a");
        out.add("A");
        out.add("a[c");
        out.add("A_B");
        out.add("b");
        out.add("d");
        out.add("a-c");
        out.add("]");
        out.add("\\");
        out.add("-");
        out.add("c");
        out.add("a*b");
        out.add("a\\b");
        out.add("a_b");
        out.add("A?C");
        out.add("q");
        out.add("aZ");
        out.add("a]b");
        out.add("[ab");
        out.add("*");
        out.add("?");
        out.add("ab*");
        out.add("aB");
        out.add("ABC");
        out.add("aBC");
        out.add("zz");
        out.add("\u4E2D");
        out.add("\u4E2D\u6587");
        out.add("\u65E5\u672C");
        out.add("\u00E9");
        out.add("e\u0301");
        out.add("\u03A9");
        out.add("\uD83D\uDE00");
        out.add("\u4E2Da");
        out.add("a\u4E2D");
        out.add("\u4E2D\u4E2D");
        return out;
    }

    private static List<Pat> patterns() {
        List<Pat> out = new ArrayList<>();
        // 生成：~/.cache/zcache_gauges/gen_glob_delivery_table.py 从 13r 两份电池读数产出
        //（现算桶数 A=2843 B=52 C=11 D=22、接线+快路后与参照仍差 32 格，都对得上）。
        // 人手改下面任何一格期望值就等于撒谎；要改请重跑那把尺。
        out.add(pat("", of(2), of(2)));
        out.add(pat("*", all(), all()));
        out.add(pat("**", allBut(2), all()));
        out.add(pat("***", allBut(2), all()));
        out.add(pat("?", of(3, 12, 13, 16, 17, 19, 20, 21, 22, 27, 31, 32, 38, 41, 43), of(3, 12, 13, 16, 17, 19, 20, 21, 22, 27, 31, 32)));
        out.add(pat("??", of(8, 9, 11, 28, 34, 37, 39, 40, 42, 44, 45, 46, 47), of(8, 9, 11, 28, 34, 37, 41, 43)));
        out.add(pat("???", of(10, 14, 15, 18, 23, 24, 25, 26, 29, 30, 33, 35, 36), of(10, 14, 15, 18, 23, 24, 25, 26, 29, 30, 33, 35, 36, 38, 42)));
        out.add(pat("a*", of(0, 1, 4, 5, 7, 8, 10, 11, 12, 14, 18, 23, 24, 25, 28, 29, 33, 34, 36, 46), of(0, 1, 4, 5, 7, 8, 10, 11, 12, 14, 18, 23, 24, 25, 28, 29, 33, 34, 36, 46)));
        out.add(pat("*b", of(7, 8, 16, 23, 24, 25, 29, 30), of(7, 8, 16, 23, 24, 25, 29, 30)));
        out.add(pat("*c", of(1, 10, 14, 18, 22), of(1, 10, 14, 18, 22)));
        out.add(pat("*a*", of(0, 1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 18, 23, 24, 25, 28, 29, 30, 33, 34, 36, 45, 46), of(0, 1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 18, 23, 24, 25, 28, 29, 30, 33, 34, 36, 45, 46)));
        out.add(pat("?a?", of(30), of(30)));
        out.add(pat("abc*", of(10), of(10)));
        out.add(pat("*abc", of(10), of(10)));
        out.add(pat("a?c", of(10, 14, 18), of(10, 14, 18)));
        out.add(pat("a*c", of(1, 10, 14, 18), of(1, 10, 14, 18)));
        out.add(pat("[abc]", of(12, 16, 22), of(12, 16, 22)));
        out.add(pat("[^abc]", of(3, 13, 17, 19, 20, 21, 27, 31, 32, 38, 41, 43), of(3, 13, 17, 19, 20, 21, 27, 31, 32)));
        out.add(pat("[a-c]", of(12, 16, 22), of(12, 16, 22)));
        out.add(pat("[c-a]", of(12, 16, 22), of(12, 16, 22)));
        out.add(pat("[a-]", of(12, 19), of(12, 19)));
        out.add(pat("[ab", of(12, 16), of(12, 16)));
        out.add(pat("[]", of(), of()));
        out.add(pat("[\\]]", of(19), of(19)));
        out.add(pat("[\\\\]", of(20), of(20)));
        out.add(pat("[\u4E2D]", of(38), of()));
        out.add(pat("[^\u4E2D]", of(3, 12, 13, 16, 17, 19, 20, 21, 22, 27, 31, 32, 41, 43), of(3, 12, 13, 16, 17, 19, 20, 21, 22, 27, 31, 32)));
        out.add(pat("[\u4E00-\u9FA5]", of(38), of()));
        out.add(pat("[abc-]", of(12, 16, 19, 22), of(12, 16, 19, 22)));
        out.add(pat("a\\*b", of(23), of(23)));
        out.add(pat("\\?", of(32), of(32)));
        out.add(pat("a\\", of(), of()));
        out.add(pat("\\[", of(), of()));
        out.add(pat("[", of(), of()));
        out.add(pat("]", of(19), of(19)));
        out.add(pat("^", of(), of()));
        out.add(pat("-", of(21), of(21)));
        out.add(pat("AUTO-AOF-*", of(), of()));
        out.add(pat("auto-aof-*", of(0, 1, 5), of(0, 1, 5)));
        out.add(pat("A?C", of(26, 35), of(26, 35)));
        out.add(pat("a_b", of(25), of(25)));
        out.add(pat("[A-Z]", of(13), of(13)));
        out.add(pat("[a-z]", of(3, 12, 16, 17, 22, 27), of(3, 12, 16, 17, 22, 27)));
        out.add(pat("\u4E2D", of(38), of(38)));
        out.add(pat("\u4E2D?", of(39, 45, 47), of(45)));
        out.add(pat("?\u4E2D", of(46, 47), of(46)));
        out.add(pat("\u4E2D\u6587", of(39), of(39)));
        out.add(pat("*\u4E2D*", of(38, 39, 45, 46, 47), of(38, 39, 45, 46, 47)));
        out.add(pat("????", of(7), of(7, 44, 45, 46)));
        out.add(pat("??????", of(), of(39, 40, 47)));
        out.add(pat("\u4E2D*", of(38, 39, 45, 47), of(38, 39, 45, 47)));
        out.add(pat("*\u4E2D", of(38, 46, 47), of(38, 46, 47)));
        out.add(pat("\uD83D\uDE00", of(44), of(44)));
        out.add(pat("?*b", of(7, 8, 23, 24, 25, 29, 30), of(7, 8, 23, 24, 25, 29, 30)));
        out.add(pat("*[", of(), of()));
        out.add(pat("x[a-z]y", of(), of()));
        out.add(pat("?\u00E9", of(), of()));
        out.add(pat("?e\u0301", of(), of()));
        out.add(pat("\u00E9?", of(), of()));
        out.add(pat("?\u03A9", of(), of()));
        out.add(pat("????????", of(4), of(4)));
        return out;
    }

    private static final List<String> NAMES = names();
    private static final List<Pat> PATS = patterns();

    /**
     * 图案 + 两串下标。
     *
     * @param oursIdx 接上 {@code RedisGlob} 与那句单根 {@code *} 快路之后该收下的
     * @param refIdx  参照那一台（4.0.9）实际收下的
     */
    private static final class Pat {
        final String pattern;
        final int[] oursIdx;
        final int[] refIdx;

        private Pat(String pattern, int[] oursIdx, int[] refIdx) {
            this.pattern = pattern;
            this.oursIdx = oursIdx;
            this.refIdx = refIdx;
        }

        Set<String> ours() {
            return pick(oursIdx);
        }

        Set<String> ref() {
            return pick(refIdx);
        }

        private Set<String> pick(int[] indexes) {
            Set<String> out = new TreeSet<>();
            if (indexes == null) {
                out.addAll(NAMES);                          // all()
                return out;
            }
            for (int i : indexes) {
                out.add(NAMES.get(i));
            }
            return out;
        }
    }

    private static Pat pat(String pattern, int[] ours, int[] ref) {
        return new Pat(pattern, ours, ref);
    }

    /** 表里的三种名单写法：{@code all()} = 48 枚全收，{@code of()} = 一枚不收，{@code allBut} 反之。 */
    private static int[] all() {
        return null;
    }

    private static int[] of(int... indexes) {
        return indexes.length == 0 ? new int[0] : indexes;
    }

    private static int[] allBut(int missing) {
        int[] out = new int[NAMES.size() - 1];
        int n = 0;
        for (int i = 0; i < NAMES.size(); i++) {
            if (i != missing) {
                out[n++] = i;
            }
        }
        return out;
    }

    // =================================================================================
    // 轴本身（从 NAMES 现算，不是抄来的常数）
    // =================================================================================

    /** UTF-8 字节数与 {@code char} 数不等的那些键名 —— 字节/码元那一族的分歧只可能出在这里。 */
    private static final List<String> BYTE_NE_CHAR = byteVsChar();

    private static List<String> byteVsChar() {
        List<String> out = new ArrayList<>();
        for (String name : NAMES) {
            if (name.getBytes(StandardCharsets.UTF_8).length != name.length()) {
                out.add(name);
            }
        }
        return out;
    }

    private static Set<String> symmetric(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        for (String s : b) {
            if (!out.add(s)) {
                out.remove(s);
            }
        }
        return out;
    }

    // =================================================================================
    // 断言的形状：整轮跑完一次性红，红的时候把格名全部点名（变异尺按格名归属）
    // =================================================================================

    private static void cell(List<String> seen, List<String> wrong, String id,
                             String actual, String expected) {
        seen.add(id);
        if (!actual.equals(expected)) {
            wrong.add(id + " 期望 " + expected + "，实际 " + actual);
        }
    }

    private static void setCell(List<String> seen, List<String> wrong, String id,
                                Set<String> actual, Set<String> expected, Pat pat) {
        setCell(seen, wrong, id, actual, expected, pat.pattern);
    }

    private static void setCell(List<String> seen, List<String> wrong, String id,
                                Set<String> actual, Set<String> expected, String pattern) {
        seen.add(id);
        if (actual.equals(expected)) {
            return;
        }
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        wrong.add(id + " pattern=" + ascii(pattern) + " 少收 " + asciiList(missing)
                + " 多收 " + asciiList(extra));
    }

    /** 表里取某一行（快路那一支用）；表里没有就是量具坏了，不许当成"没匹配上"。 */
    private static Pat pat(String pattern) {
        for (Pat p : PATS) {
            if (p.pattern.equals(pattern)) {
                return p;
            }
        }
        throw new IllegalStateException("表里没有图案 " + ascii(pattern) + "，这一跑不成立");
    }

    private static String asciiList(Set<String> names) {
        if (names.isEmpty()) {
            return "[]";
        }
        List<String> out = new ArrayList<>();
        for (String s : names) {
            out.add(ascii(s));
        }
        return "{" + String.join(" ", out) + "}";
    }

    /** 与 Python 的 {@code ascii()} 同义：格名与消息里不许出现制表符、换行或 {@code ]}。 */
    private static String ascii(String s) {
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) {
                out.append(String.format("\\u%04X", (int) c));
            } else if (c == '\'') {
                out.append("\\'");
            } else {
                out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    private static void finish(String method, List<String> seen, List<String> wrong) {
        Set<String> ids = new TreeSet<>();
        for (String w : wrong) {
            ids.add(w.substring(0, w.indexOf(' ')));
        }
        assertTrue(wrong.isEmpty(), method + " 不合格 " + wrong.size() + "/" + seen.size()
                + " 格：\n  " + String.join("\n  ", wrong)
                + "\n[RED_CELLS=" + String.join("|", ids) + "]"
                + " [RED_ROWS=" + wrong.size() + "/" + seen.size() + "]");
    }

    // =================================================================================
    // 真起一台，从线上问（不问 store：这一卡坏的就是"命令层没接上"）
    // =================================================================================

    /** 一台一次性的服务器，播下那 48 枚键名；每一支 @Test 各开一台，跑完关掉。 */
    private static final class Battery implements AutoCloseable {
        private final RedisServer server;
        private final Path dir;
        private final Socket socket;
        private final DataInputStream in;
        private final Thread thread;
        private int rounds;

        private static Battery open() throws Exception {
            Path dir = Files.createTempDirectory("zcache-glob-delivery");
            int port = freePort();
            RedisServer server = new RedisServer("127.0.0.1", port, 0);
            server.setDataDir(dir.toString());
            Thread thread = startAndWait(server, port);
            Socket socket = connect(port);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            Battery battery = new Battery(server, dir, socket, in, thread);
            battery.seed();
            return battery;
        }

        private Battery(RedisServer server, Path dir, Socket socket, DataInputStream in, Thread thread) {
            this.server = server;
            this.dir = dir;
            this.socket = socket;
            this.in = in;
            this.thread = thread;
        }

        /** 先核验"这台就是我刚起的那一台"，再一枚一枚 SET，最后用 DBSIZE 兜住条数。 */
        private void seed() throws IOException {
            String tag = UUID.randomUUID().toString();
            if (!"OK".equals(call("SET", "__tag:" + tag, "1"))) {
                throw new IllegalStateException("身份核验失败：SET 没回 OK");
            }
            if (!"1".equals(call("GET", "__tag:" + tag))) {
                throw new IllegalStateException("身份核验失败：GET 读不回 1");
            }
            call("DEL", "__tag:" + tag);
            List<String> failed = new ArrayList<>();
            for (String name : NAMES) {
                Object reply = call("SET", name, "1");
                if (!"OK".equals(reply)) {
                    failed.add(ascii(name) + "=>" + reply);
                }
            }
            if (!failed.isEmpty()) {
                throw new IllegalStateException("播种失败 " + failed.size() + " 枚："
                        + String.join(" ", failed));
            }
            if (dbsize() != NAMES.size()) {
                throw new IllegalStateException("DBSIZE=" + dbsize() + " 与键名条数 "
                        + NAMES.size() + " 对不上，这一跑不成立");
            }
        }

        private int dbsize() throws IOException {
            Object reply = call("DBSIZE");
            if (!(reply instanceof Long)) {
                throw new IllegalStateException("DBSIZE 回的不是整数：" + reply);
            }
            return ((Long) reply).intValue();
        }

        private Set<String> keys(String pattern) throws IOException {
            return nameSet(call("KEYS", pattern), "KEYS " + ascii(pattern));
        }

        /** 转游标转到回 0 为止 —— COUNT 只是提示，一圈到底那种读法量不到 MATCH。 */
        private Set<String> scanAll(String pattern, int count) throws IOException {
            Set<String> found = new TreeSet<>();
            String cursor = "0";
            rounds = 0;
            for (int guard = 0; guard < 400; guard++) {
                Object reply = call("SCAN", cursor, "MATCH", pattern, "COUNT", String.valueOf(count));
                if (!(reply instanceof List) || ((List<?>) reply).size() != 2) {
                    throw new IllegalStateException("SCAN 回的不是二元组：" + reply);
                }
                List<?> pair = (List<?>) reply;
                if (!(pair.get(0) instanceof String) || !(pair.get(1) instanceof List)) {
                    throw new IllegalStateException("SCAN 的二元组形状不对：" + reply);
                }
                found.addAll(nameSet(pair.get(1), "SCAN MATCH " + ascii(pattern)));
                rounds++;
                cursor = (String) pair.get(0);
                if ("0".equals(cursor)) {
                    return found;
                }
            }
            throw new IllegalStateException("SCAN MATCH " + ascii(pattern) + " 转了 400 圈还没回 0");
        }

        private int lastRounds() {
            return rounds;
        }

        private Set<String> nameSet(Object reply, String cmd) {
            if (!(reply instanceof List)) {
                throw new IllegalStateException(cmd + " 回的不是名单：" + reply);
            }
            Set<String> out = new TreeSet<>();
            for (Object o : (List<?>) reply) {
                if (!(o instanceof String)) {
                    throw new IllegalStateException(cmd + " 的名单里混进了非 bulk：" + o);
                }
                out.add((String) o);
            }
            return out;
        }

        private Object call(String... args) throws IOException {
            send(socket, args);
            return readFrame(in);
        }

        @Override
        public void close() throws IOException {
            socket.close();
            server.stop();
            try {
                thread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            deleteTree(dir);
        }
    }

    // ---------- RESP：读不出形状就炸，不许把错误帧摊成"空名单" ----------

    private static void send(Socket socket, String... args) throws IOException {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String arg : args) {
            sb.append('$').append(arg.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n").append(arg).append("\r\n");
        }
        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** {@code *} → {@code List}、{@code $} → {@code String}、{@code :} → {@code Long}、{@code -} → 抛。 */
    private static Object readFrame(DataInputStream in) throws IOException {
        String line = readLine(in);
        if (line.isEmpty()) {
            throw new IllegalStateException("读到空行：协议帧不齐");
        }
        char tag = line.charAt(0);
        if (tag == '*') {
            int n = Integer.parseInt(line.substring(1));
            if (n < 0) {
                return null;
            }
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(readFrame(in));
            }
            return out;
        }
        if (tag == '$') {
            int n = Integer.parseInt(line.substring(1));
            if (n < 0) {
                return null;
            }
            byte[] payload = new byte[n];
            in.readFully(payload);
            in.readFully(new byte[2]);
            return new String(payload, StandardCharsets.UTF_8);
        }
        if (tag == ':') {
            return Long.valueOf(line.substring(1));
        }
        if (tag == '+') {
            return line.substring(1);
        }
        if (tag == '-') {
            // 错误帧绝不能被摊成"空名单"：那正是"没量到"被读成"量过了"的那张脸。
            throw new IllegalStateException("服务器回错误帧：" + line);
        }
        throw new IllegalStateException("认不出的帧头：" + line);
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

    // ---------- 起停与端口 ----------

    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final AtomicInteger PORT_CURSOR =
            new AtomicInteger(new Random().nextInt(PORT_SPAN));

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

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        socket.setSoTimeout((int) DEADLINE_MS);
        return socket;
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
        }, "test-z-cache-glob-delivery");
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
        throw new IllegalStateException("服务器没在 " + port + " 上听起来");
    }

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

    // ---------- 结构守卫那一侧：读自己的主源码，注释剥掉 ----------

    private static int countContaining(List<String> lines, String needle) {
        int n = 0;
        for (String line : lines) {
            if (line.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    /** 从某个签名的那一行起按大括号配平取到方法尾；取不到就抛，不许把"取不到"读成"没有违规"。 */
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

    /** 从模块目录或 reactor 根目录都能定位到源文件；注释行与 javadoc 行剥掉。 */
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
            code.add(raw);
        }
        return code;
    }

    private static String withoutFirstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
