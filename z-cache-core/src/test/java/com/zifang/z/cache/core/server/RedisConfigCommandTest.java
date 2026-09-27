package com.zifang.z.cache.core.server;

import com.zifang.z.cache.core.persistence.AofPersistence;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 13n —— {@code CONFIG GET} / {@code CONFIG SET} 的线格式与错误文案，判据全部来自
 * 上游 5.0.14 的源码（本机权威副本 {@code ~/.cache/zcache_gauges/full5x/redis-5.0.14/src}）。
 *
 * <h2>这一支的量的是什么</h2>
 * 两条自动挡旋钮（{@code auto-aof-rewrite-percentage} / {@code -min-size}）在此之前只有
 * Java 侧的 setter，命令层根本没有 {@code CONFIG} 这条命令 —— 打过去回的是
 * {@code unknown command}。README 里"这两个旋钮可调"那句话，在没有 {@code CONFIG} 之前
 * 是一句宣传。这一支把它接到线上，并且钉住接过去之后<b>说的还是上游那几句话</b>：
 * <ul>
 *   <li>{@code config.c:2248} 的 {@code configCommand}：{@code set} 要 argc==4（{@code :2264}）、
 *       {@code get} 要 argc==3（{@code :2266}），其余落 {@code :2285} 的
 *       {@code addReplySubcommandSyntaxError}，句子在 {@code networking.c:623-629}；</li>
 *   <li>{@code config} 在命令表上 arity 是 {@code -2}（{@code server.c:271}），光秃秃一句
 *       {@code CONFIG} 吃的是 {@code server.c:2612-2615} 那句 arity 错，命令名是<b>小写</b>的
 *       {@code 'config'}；</li>
 *   <li>名字是 {@code strcasecmp} 比的（{@code config.c:877} 那串宏），而报错里回的是
 *       客户端<b>打进来的那一串原样</b>（{@code c->argv[1]->ptr} / {@code c->argv[2]->ptr}）——
 *       分派大小写无关、文案照写法，这两半不能共用一个字符串；</li>
 *   <li>percent 走 {@code getLongLongFromObject}（整数文法，{@code 0..INT_MAX}，
 *       {@code config.c:1161}），min-size 走 {@code memtoll}（带单位，{@code 0..LONG_MAX}，
 *       {@code :1262}）—— 两把尺不同，所以 {@code 100mb} 在 percent 那一栏是非法、
 *       在 min-size 那一栏合法；</li>
 *   <li>语法不对与越界共用 {@code :1286} 那一句 {@code Invalid argument '%s' for CONFIG SET '%s'}
 *       （{@code badfmt} 那一档在 {@code :1285}）；认不出的名字走 {@code :1276-1277} 的
 *       {@code Unsupported CONFIG parameter: %s}；</li>
 *   <li>{@code CONFIG GET} 回的是<b>延迟数组里成对的 bulk</b>（{@code addReplyBulkCString}，
 *       {@code config.c:1311-1319}，末尾 {@code :1577} 那句 {@code matches*2}），
 *       一个都不匹配时是 {@code *0}；</li>
 *   <li>{@code CONFIG HELP} 走 {@code networking.c:604} 的 {@code addReplyHelp}：表头与每一条
 *       都是<b>状态回复</b>（{@code +}），不是 bulk —— 这正是 {@code CommandHandler} 里
 *       {@code XGROUP HELP} 那条注释记过的一个字节之差。</li>
 * </ul>
 * 读回复用的是 {@link #readTyped}：它把类型头原样留在校验文本里
 * （{@code *2|$27=auto-aof-rewrite-percentage|$3=100}），所以"数组里装的是 bulk 还是状态串"
 * 这种只差一个字节的事，红的时候当场能看出来。
 *
 * <h2>与上游不同处，逐条写明（不是"以后再说"）</h2>
 * <ol>
 *   <li><b>只有这两个名字有值可读</b>。上游 {@code configGetCommand}（{@code config.c:1328-1578}）
 *       那一张表有 99 项，我们只接了这两条，于是 {@code CONFIG GET maxmemory} 在这里回 {@code *0}、
 *       {@code CONFIG SET maxmemory 100mb} 回 {@code Unsupported CONFIG parameter} ——
 *       走的是上游"认不出的名字"那一档文案，没有自造句子。</li>
 *   <li><b>{@code RESETSTAT} / {@code REWRITE} 不接</b>，回的是上游那句 subcommand 语法错。
 *       这与 {@code CONFIG HELP} 只列 GET/SET 是同一套说法，两处必须一起对得上，
 *       所以也各自成了一格。</li>
 * </ol>
 *
 * <h2>这一支自己改过的一次判据（不是"以后再说"）</h2>
 * 13n 那一版在这里记的是<b>三条</b>差距，第二条写的是"没配 dataDir 的那一台拒 SET，但照常答 GET"，
 * 理由是旋钮挂在 {@code AofPersistence} 上、那个对象只在带 dataDir 启动时才有，回 {@code +OK}
 * 等于"当场收下、下一刻没有落点"。那句话当时是真的，也是一句<em>承认结构不够</em>的话：
 * 上游那两个字段挂在 {@code server} 上、永远存在，AOF 关着也 {@code +OK}（只是没人读），
 * 差别只在我们的服务器<em>没有</em>那个永远存在的持有者。补上持有者之后（旋钮从
 * {@code AofPersistence} 搬进 {@code AofTuning}，每台服务器一份、与日志在不在无关，
 * 而 {@code AofPersistence} 拿的是<em>同一个对象</em>而不是抄本），那条差距不成立了，
 * 于是 {@link #aServerWithoutALogHoldsTheKnobsToo} 里"SET 不许回 +OK"那一格<em>故意翻面</em>。
 * 翻面必须和搬动在同一次提交里发生 —— 判据悄悄重定义比判据严会把缺陷读成通过。
 * "同一个对象而不是抄本"这一条不靠注释担保，{@link #configSetMovesTheGateThatDecidesTheSwap}
 * 里那两格量的是引用相等与 {@code AofPersistence} 自己读回来的数。
 * <p>
 * 期望值出自上面那些行号的逐臂推演；250 通了之后要拿参考实例逐格复跑（本轮不通，
 * 见 {@code RedisMemoryFormatTest} 里同一句交代）。
 */
class RedisConfigCommandTest {

    private static final long DEADLINE_MS = 8_000L;

    /** 上游那两条旋钮的名字，长度是算好的：27 与 25。 */
    private static final String PERC = "auto-aof-rewrite-percentage";
    private static final String MIN_SIZE = "auto-aof-rewrite-min-size";
    private static final String PERC_DEFAULT = "$3=100";
    private static final String MIN_SIZE_DEFAULT = "$8=67108864";

    // =================================================================================
    // 一、命令层：形状、语法、文案
    // =================================================================================

    @Test
    void grammarAndErrorTextsAreTheOnesUpstreamSpeaks() throws Exception {
        Path dir = Files.createTempDirectory("zcache-config-grammar");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        server.setDataDir(dir.toString());
        Thread thread = startAndWait(server, port);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ---- CONFIG GET：延迟数组里成对的 bulk ----
            expectTyped(seen, wrong, socket, in, "GET 全名 ⇒ 一对 bulk",
                    "*2|$27=" + PERC + "|" + PERC_DEFAULT, "CONFIG", "GET", PERC);
            expectTyped(seen, wrong, socket, in, "GET 全名（min-size）",
                    "*2|$25=" + MIN_SIZE + "|" + MIN_SIZE_DEFAULT, "CONFIG", "GET", MIN_SIZE);
            expectTyped(seen, wrong, socket, in, "GET glob ⇒ 两条都在，顺序照上游表里那一列",
                    "*4|$27=" + PERC + "|" + PERC_DEFAULT + "|$25=" + MIN_SIZE + "|" + MIN_SIZE_DEFAULT,
                    "CONFIG", "GET", "auto-aof-*");
            expectTyped(seen, wrong, socket, in, "GET 星号 ⇒ 我们只答得出这两条（差距见类注释第 1 条）",
                    "*4|$27=" + PERC + "|" + PERC_DEFAULT + "|$25=" + MIN_SIZE + "|" + MIN_SIZE_DEFAULT,
                    "CONFIG", "GET", "*");
            expectTyped(seen, wrong, socket, in, "GET 一个匹配不上的 glob ⇒ *0",
                    "*0", "CONFIG", "GET", "maxmemory");
            // ? 与 [] 两臂在线上各走一次：证明 glob 不是"只有星号能用"的简写
            expectTyped(seen, wrong, socket, in, "GET 带 ? 的 pattern",
                    "*2|$25=" + MIN_SIZE + "|" + MIN_SIZE_DEFAULT, "CONFIG", "GET", "?uto-aof-rewrite-min-size");
            expectTyped(seen, wrong, socket, in, "GET 带字符集的 pattern",
                    "*2|$25=" + MIN_SIZE + "|" + MIN_SIZE_DEFAULT, "CONFIG", "GET", "auto-aof-rewrite-m*[si]ze");
            expectTyped(seen, wrong, socket, in, "GET 带字符集但这一位不在集里 ⇒ *0",
                    "*0", "CONFIG", "GET", "auto-aof-rewrite-m[xq]size");
            expectTyped(seen, wrong, socket, in, "GET 名字大写 ⇒ 分派不分大小写（stringmatch 的 nocase）",
                    "*2|$27=" + PERC + "|" + PERC_DEFAULT, "CONFIG", "GET", "AUTO-AOF-REWRITE-PERCENTAGE");
            expectTyped(seen, wrong, socket, in, "GET 前缀不是匹配（少一个字符就是 *0）",
                    "*0", "CONFIG", "GET", "auto-aof-rewrite-perc");

            // ---- CONFIG SET：+OK / badfmt / unsupported ----
            expectTyped(seen, wrong, socket, in, "SET 合法整数 ⇒ +OK",
                    "+OK", "CONFIG", "SET", PERC, "10");
            expectTyped(seen, wrong, socket, in, "SET 之后 GET 读回新值",
                    "*2|$27=" + PERC + "|$2=10", "CONFIG", "GET", PERC);
            expectTyped(seen, wrong, socket, in, "SET 0（上游：0 就是关掉自动挡）",
                    "+OK", "CONFIG", "SET", PERC, "0");
            expectTyped(seen, wrong, socket, in, "SET INT_MAX ⇒ 上界含",
                    "+OK", "CONFIG", "SET", PERC, "2147483647");
            expectTyped(seen, wrong, socket, in, "SET INT_MAX+1 ⇒ 越界与语法共用一句 badfmt",
                    "-ERR Invalid argument '2147483648' for CONFIG SET '" + PERC + "'",
                    "CONFIG", "SET", PERC, "2147483648");
            expectTyped(seen, wrong, socket, in, "SET 负数 ⇒ 越界（-1 是合法整数文法）",
                    "-ERR Invalid argument '-1' for CONFIG SET '" + PERC + "'",
                    "CONFIG", "SET", PERC, "-1");
            expectTyped(seen, wrong, socket, in, "SET 带单位的文本进 percent 栏 ⇒ 整数文法拒",
                    "-ERR Invalid argument '100mb' for CONFIG SET '" + PERC + "'",
                    "CONFIG", "SET", PERC, "100mb");
            expectTyped(seen, wrong, socket, in, "SET 正号 ⇒ string2ll 那把尺不收 +5",
                    "-ERR Invalid argument '+5' for CONFIG SET '" + PERC + "'",
                    "CONFIG", "SET", PERC, "+5");
            expectTyped(seen, wrong, socket, in, "SET 前导零 ⇒ string2ll 也不收 05",
                    "-ERR Invalid argument '05' for CONFIG SET '" + PERC + "'",
                    "CONFIG", "SET", PERC, "05");
            // 上面这一串非法之后，旋钮必须还停在最后一次成功的那一格
            expectTyped(seen, wrong, socket, in, "被拒的 SET 一步都不许动旋钮（摘掉闸门就露）",
                    "*2|$27=" + PERC + "|$10=2147483647", "CONFIG", "GET", PERC);

            expectTyped(seen, wrong, socket, in, "min-size 收 100mb（memtoll：1024 的幂）",
                    "+OK", "CONFIG", "SET", MIN_SIZE, "100mb");
            expectTyped(seen, wrong, socket, in, "GET 读回的是字节数，不是那串带单位的文本",
                    "*2|$25=" + MIN_SIZE + "|$9=104857600", "CONFIG", "GET", MIN_SIZE);
            expectTyped(seen, wrong, socket, in, "min-size 收 100m（k/m/g 是 1000 的幂，与 kb 差 24000）",
                    "+OK", "CONFIG", "SET", MIN_SIZE, "100m");
            expectTyped(seen, wrong, socket, in, "GET 读回 100m ⇒ 100000000",
                    "*2|$25=" + MIN_SIZE + "|$9=100000000", "CONFIG", "GET", MIN_SIZE);
            expectTyped(seen, wrong, socket, in, "min-size 收空单位之外的 kb 且没有数字 ⇒ 0",
                    "+OK", "CONFIG", "SET", MIN_SIZE, "kb");
            expectTyped(seen, wrong, socket, in, "GET 读回 kb ⇒ 0",
                    "*2|$25=" + MIN_SIZE + "|$1=0", "CONFIG", "GET", MIN_SIZE);
            expectTyped(seen, wrong, socket, in, "min-size 拒 1kbb（单位表里没有这一条）",
                    "-ERR Invalid argument '1kbb' for CONFIG SET '" + MIN_SIZE + "'",
                    "CONFIG", "SET", MIN_SIZE, "1kbb");
            expectTyped(seen, wrong, socket, in, "min-size 拒 -1（memtoll 收，范围闸拒）",
                    "-ERR Invalid argument '-1' for CONFIG SET '" + MIN_SIZE + "'",
                    "CONFIG", "SET", MIN_SIZE, "-1");
            expectTyped(seen, wrong, socket, in, "min-size 拒只有一根负号",
                    "-ERR Invalid argument '-' for CONFIG SET '" + MIN_SIZE + "'",
                    "CONFIG", "SET", MIN_SIZE, "-");
            expectTyped(seen, wrong, socket, in, "SET 名字大写 ⇒ 认得（strcasecmp）",
                    "+OK", "CONFIG", "SET", "AUTO-AOF-REWRITE-MIN-SIZE", "2kb");
            expectTyped(seen, wrong, socket, in, "GET 读回 2kb ⇒ 2048",
                    "*2|$25=" + MIN_SIZE + "|$4=2048", "CONFIG", "GET", MIN_SIZE);

            expectTyped(seen, wrong, socket, in, "认不出的名字 ⇒ 上游 config_set_else 那句",
                    "-ERR Unsupported CONFIG parameter: maxmemory", "CONFIG", "SET", "maxmemory", "100mb");
            expectTyped(seen, wrong, socket, in, "认不出的名字 ⇒ 回的是打进来的那一串原样",
                    "-ERR Unsupported CONFIG parameter: MaxMemory", "CONFIG", "SET", "MaxMemory", "1");

            // ---- arity 与子命令语法 ----
            expectTyped(seen, wrong, socket, in, "光秃秃 CONFIG ⇒ 命令表 arity（-2）那句，名字小写",
                    "-ERR wrong number of arguments for 'config' command", "CONFIG");
            expectTyped(seen, wrong, socket, in, "CONFIG GET 少一个参数 ⇒ 子命令语法错（照写法回）",
                    "-ERR Unknown subcommand or wrong number of arguments for 'GET'. Try CONFIG HELP.",
                    "CONFIG", "GET");
            expectTyped(seen, wrong, socket, in, "CONFIG GET 多一个参数 ⇒ 同一句",
                    "-ERR Unknown subcommand or wrong number of arguments for 'GET'. Try CONFIG HELP.",
                    "CONFIG", "GET", PERC, "extra");
            expectTyped(seen, wrong, socket, in, "CONFIG SET 只有名字 ⇒ 同一句（argc!=4）",
                    "-ERR Unknown subcommand or wrong number of arguments for 'SET'. Try CONFIG HELP.",
                    "CONFIG", "SET", PERC);
            expectTyped(seen, wrong, socket, in, "CONFIG SET 多一个参数 ⇒ 同一句",
                    "-ERR Unknown subcommand or wrong number of arguments for 'SET'. Try CONFIG HELP.",
                    "CONFIG", "SET", PERC, "1", "extra");
            expectTyped(seen, wrong, socket, in, "不认识的子命令 ⇒ 同一句，名字照客户打进来的写法",
                    "-ERR Unknown subcommand or wrong number of arguments for 'foo'. Try CONFIG HELP.",
                    "CONFIG", "foo", "bar");
            expectTyped(seen, wrong, socket, in, "不认识的子命令 ⇒ 大写进来就大写回去（不是分派用的那一份）",
                    "-ERR Unknown subcommand or wrong number of arguments for 'FOO'. Try CONFIG HELP.",
                    "CONFIG", "FOO", "bar");
            expectTyped(seen, wrong, socket, in, "子命令大小写无关地认得 get",
                    "*2|$27=" + PERC + "|$10=2147483647", "config", "get", PERC);
            // ---- 我们没接的两支 + HELP ----
            expectTyped(seen, wrong, socket, in, "RESETSTAT 没接 ⇒ 落子命令语法错（差距第 3 条）",
                    "-ERR Unknown subcommand or wrong number of arguments for 'RESETSTAT'. Try CONFIG HELP.",
                    "CONFIG", "RESETSTAT");
            expectTyped(seen, wrong, socket, in, "REWRITE 没接 ⇒ 同上",
                    "-ERR Unknown subcommand or wrong number of arguments for 'REWRITE'. Try CONFIG HELP.",
                    "CONFIG", "REWRITE");
            expectTyped(seen, wrong, socket, in, "HELP 的表头与每一条都是状态串（+，不是 $）；只列真做得到的两条",
                    "*3|+CONFIG <subcommand> arg arg ... arg. Subcommands are:"
                            + "|+GET <pattern> -- Return parameters matching the glob-like <pattern> and their values."
                            + "|+SET <parameter> <value> -- Set parameter to value.",
                    "CONFIG", "HELP");
            expectTyped(seen, wrong, socket, in, "HELP 带参数 ⇒ arity 是 2，落子命令语法错",
                    "-ERR Unknown subcommand or wrong number of arguments for 'HELP'. Try CONFIG HELP.",
                    "CONFIG", "HELP", "extra");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
            deleteTree(dir);
        }
        assertTrue(wrong.isEmpty(), "CONFIG 的线格式（" + seen.size() + " 格）不合格: " + wrong
                + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    // =================================================================================
    // 二、没配 dataDir 的那一台：旋钮照样在这台上，SET 收、GET 读得回
    // =================================================================================

    @Test
    void aServerWithoutALogHoldsTheKnobsToo() throws Exception {
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);      // 故意不 setDataDir
        Thread thread = startAndWait(server, port);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            expectTyped(seen, wrong, socket, in, "没有日志也答得上默认值（与上游同形）",
                    "*2|$27=" + PERC + "|" + PERC_DEFAULT, "CONFIG", "GET", PERC);
            // ↓↓ 这两格就是翻面的那一处：13n 那一版在这里期望的是
            //    "-ERR CONFIG SET '...' is not supported: no data directory configured"。
            expectTyped(seen, wrong, socket, in, "SET 有落点：没有日志的这台也当场收下（照上游）",
                    "+OK", "CONFIG", "SET", PERC, "10");
            expectTyped(seen, wrong, socket, in, "收下之后 GET 读回新值，不是那个默认值",
                    "*2|$27=" + PERC + "|$2=10", "CONFIG", "GET", PERC);
            expectTyped(seen, wrong, socket, in, "地板那一条同样有落点（带单位的写法在这台也认）",
                    "+OK", "CONFIG", "SET", MIN_SIZE, "1kb");
            expectTyped(seen, wrong, socket, in, "地板读回的是字节数",
                    "*2|$25=" + MIN_SIZE + "|$4=1024", "CONFIG", "GET", MIN_SIZE);
            expectTyped(seen, wrong, socket, in, "有落点不许把越界放进门（还是那句 badfmt）",
                    "-ERR Invalid argument '-1' for CONFIG SET '" + PERC + "'",
                    "CONFIG", "SET", PERC, "-1");
            expectTyped(seen, wrong, socket, in, "认不出的名字在那一台上也还是那句（先认名字再谈落点）",
                    "-ERR Unsupported CONFIG parameter: maxmemory", "CONFIG", "SET", "maxmemory", "1");
            expectTyped(seen, wrong, socket, in, "拒过两轮之后先前那个值还在（拒不许顺手改）",
                    "*2|$25=" + MIN_SIZE + "|$4=1024", "CONFIG", "GET", MIN_SIZE);
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
        }
        assertTrue(wrong.isEmpty(), "没有日志的那一台（" + seen.size() + " 格）不合格: " + wrong
                + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    // =================================================================================
    // 三、接到线上之后，那两个旋钮真的管着换文件
    // =================================================================================

    @Test
    void configSetMovesTheGateThatDecidesTheSwap() throws Exception {
        Path dir = Files.createTempDirectory("zcache-config-gate");
        Path aof = dir.resolve("appendonly.aof");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        server.setDataDir(dir.toString());
        Thread thread = startAndWait(server, port);
        AofPersistence a = server.getAofPersistence();
        assertNotNull(a, "前置条件: 这一台得真的起了 AOF，才谈得上旋钮管不管换文件");
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        String key = "gate";
        String[] values = new String[4];
        for (int i = 0; i < values.length; i++) {
            StringBuilder sb = new StringBuilder("v" + (i + 1) + "-");
            while (sb.length() < 400) {
                sb.append('x');
            }
            values[i] = sb.toString();
        }
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            for (String value : values) {
                expectTyped(seen, wrong, socket, in, "写入落得下去", "+OK", "SET", key, value);
            }
            String bodyAtStart = readBody(aof);
            expectTextCell(seen, wrong, "四笔同键在盘上是四条流水",
                    String.valueOf(countSetRecords(bodyAtStart, key, null)), "4",
                    "下面那一格的对照要有数据可对照");

            // 默认旋钮（地板 64MB）下先放五个周期过去：这一格与下面那一格是同一份数据、只差旋钮。
            Thread.sleep(600);
            expectTextCell(seen, wrong, "默认旋钮下再过五个周期，日志还是四条（不换）",
                    String.valueOf(countSetRecords(readBody(aof), key, null)), "4",
                    "地板 64MB 挡着；这一格是下面那格的同形状对照，少了它\"没换\"就说不清是旋钮挡的还是压根没跑");

            expectTyped(seen, wrong, socket, in, "CONFIG SET 地板 ⇒ +OK", "+OK", "CONFIG", "SET", MIN_SIZE, "0");
            expectTyped(seen, wrong, socket, in, "CONFIG SET 百分比 ⇒ +OK", "+OK", "CONFIG", "SET", PERC, "1");
            expectTyped(seen, wrong, socket, in, "读回地板",
                    "*2|$25=" + MIN_SIZE + "|$1=0", "CONFIG", "GET", MIN_SIZE);
            expectTyped(seen, wrong, socket, in, "读回百分比",
                    "*2|$27=" + PERC + "|$1=1", "CONFIG", "GET", PERC);
            // 这两格问的是"线上那一份与日志那一份是不是同一个东西"：只要 SET 落的是抄本、
            // 或者读的是另一处，这两格当场红 —— 而下面那格"自动挡真的换了日志"照样会红，
            // 所以少了这两格，"改了就生效"与"改在别处、恰好也生效"分不开。
            expectTextCell(seen, wrong, "日志那一侧自己读回来的百分比也是这个数（不是两份）",
                    String.valueOf(a.getAutoAofRewritePercentage()), "1",
                    "问的是 aofPersistence 那一份的读数，不是命令层的记账");
            expectTextCell(seen, wrong, "地板也一样：那一侧读回来的是 0",
                    String.valueOf(a.getAutoAofRewriteMinSize()), "0",
                    "两把尺各问一次，只有一把接上了不算数");
            // 上面两格量"值到了"，这一格量"是同一个东西"：接线若是开场抄一份而不是交引用，
            // 值这一趟照样对（抄在 SET 之后），下一趟就漂了 —— 那是只有引用相等拦得住的形状。
            expectTextCell(seen, wrong, "命令层那一份与日志那一份引用相等（不是抄本）",
                    String.valueOf(a.tuning() == server.serverScope().aofTuning()), "true",
                    "结构守卫：AofPersistence 拿的必须就是本台 scope 里那一个 AofTuning");

            int collapsed = awaitRecordCount(aof, key, 1);
            expectTextCell(seen, wrong, "线上改了旋钮之后，自动挡真的把日志换了（四笔塌成一笔）",
                    String.valueOf(collapsed), "1",
                    "等的是盘上的条数，不是 isRewriting() 那本自记账；等了 " + collapsed + " 次读盘的读数");
            expectTextCell(seen, wrong, "留下的那一条是最后写进去的那个值",
                    String.valueOf(countSetRecords(readBody(aof), key, values[3])), "1",
                    "塌成一笔不够，还得是这一笔");
            expectTyped(seen, wrong, socket, in, "换过手之后 GET 读得到最后那个值",
                    "$400=" + values[3], "GET", key);
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
            deleteTree(dir);
        }
        assertTrue(wrong.isEmpty(), "CONFIG SET 真的接着自动挡（" + seen.size() + " 格）不合格: " + wrong
                + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    // =================================================================================
    // 线格式读取
    // =================================================================================

    /**
     * 把一条回复连它的 RESP 类型头一起压成一行可比对的文本：
     * {@code *2|$27=name|$3=100}、{@code +OK}、{@code -ERR …}、{@code :5}、{@code $-1}。
     * <p>
     * 为什么不用只取字符串的那种读法：这一支走线上的那 55 格里，"数组里是 bulk 还是
     * 状态串"这件事只剩一个字节的差别，而按 RESP 类型分支的客户端会走错路
     * （{@code XGROUP HELP} 那条注释里已经栽过一次）。类型头留在文本里，红的时候当场看得见。
     */
    private static String readTyped(DataInputStream in) throws IOException {
        String line = readLine(in);
        if (line.isEmpty()) {
            return line;
        }
        char type = line.charAt(0);
        if (type == '*') {
            int n = Integer.parseInt(line.substring(1));
            if (n < 0) {
                return line;
            }
            StringBuilder sb = new StringBuilder(line);
            for (int i = 0; i < n; i++) {
                sb.append('|').append(readTyped(in));
            }
            return sb.toString();
        }
        if (type == '$') {
            int length = Integer.parseInt(line.substring(1));
            if (length < 0) {
                return line;
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            in.readFully(new byte[2]);
            return "$" + length + "=" + new String(payload, StandardCharsets.UTF_8)
                    .replace("\r", "\\r").replace("\n", "\\n");
        }
        return line;
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

    // =================================================================================
    // 记账
    // =================================================================================

    private static void expectTyped(Map<String, String> seen, Map<String, String> wrong, Socket socket,
                                    DataInputStream in, String label, String expected,
                                    String... command) throws IOException {
        send(socket, command);
        String actual = readTyped(in);
        seen.put(label, actual);
        if (!expected.equals(actual)) {
            wrong.put(label, "期望 " + expected + "，实际 " + actual);
        }
    }

    private static void expectTextCell(Map<String, String> seen, Map<String, String> wrong, String label,
                                       String actual, String expected, String why) {
        seen.put(label, actual);
        if (!expected.equals(actual)) {
            wrong.put(label, "期望 " + expected + "，实际 " + actual + "。" + why);
        }
    }

    // =================================================================================
    // 盘上那一侧
    // =================================================================================

    private static String readBody(Path aof) throws IOException {
        if (!Files.exists(aof)) {
            return "";
        }
        return new String(Files.readAllBytes(aof), StandardCharsets.ISO_8859_1);
    }

    /**
     * 数盘上有几条 {@code SET <key> …} 记录 —— 只按字节找 RESP 的定长头，不读我们自己的记账。
     * value 传 null 就是"不管值，只数这个键"。
     */
    private static int countSetRecords(String body, String key, String value) {
        StringBuilder needle = new StringBuilder("$3\r\nSET\r\n$")
                .append(key.length()).append("\r\n").append(key).append("\r\n");
        if (value != null) {
            needle.append('$').append(value.length()).append("\r\n").append(value).append("\r\n");
        }
        String n = needle.toString();
        int count = 0;
        for (int i = body.indexOf(n); i >= 0; i = body.indexOf(n, i + 1)) {
            count++;
        }
        return count;
    }

    /** 等盘上这个键的 SET 条数变成 expected；返回最后读到的那个数（不是读数就作废）。 */
    private static int awaitRecordCount(Path aof, String key, int expected) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            last = countSetRecords(readBody(aof), key, null);
            if (last == expected) {
                return last;
            }
            Thread.sleep(50);
        }
        return last;
    }

    // =================================================================================
    // 起停
    // =================================================================================

    /** 端口窗口与"为什么不能用 new ServerSocket(0)"那笔账，写在 {@code RedisServerLifecycleTest#freePort}。 */
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final java.util.concurrent.atomic.AtomicInteger PORT_CURSOR =
            new java.util.concurrent.atomic.AtomicInteger(new java.util.Random().nextInt(PORT_SPAN));

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
        }, "test-z-cache-config");
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
        throw new IllegalStateException("server did not start listening on " + port);
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
}
