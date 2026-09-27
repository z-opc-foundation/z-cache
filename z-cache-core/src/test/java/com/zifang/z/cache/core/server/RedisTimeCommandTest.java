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
 * 13p —— {@code TIME} 的线格式、arity 与"它进不进日志"，判据取自上游 5.0.14 源码
 * （本机权威副本 {@code ~/.cache/zcache_gauges/full5x/redis-5.0.14/src}）。
 *
 * <h2>这一支的量的是什么</h2>
 * {@code TIME} 在命令表上是硬存在的一行 —— {@code server.c:295}：
 * {@code {"time",timeCommand,1,"RF",0,NULL,0,0,0,0,0}}。三个可判定的后果都来自这一行：
 * <ul>
 *   <li><b>arity 是 1</b>（正数 ⇒ 恰好一个词）。打 {@code TIME x} 吃的是
 *       {@code server.c:2613-2614} 那句 {@code wrong number of arguments for '%s' command}，
 *       其中 {@code %s} 是表里的 {@code c->cmd->name}，<b>小写</b>的 {@code time}；</li>
 *   <li><b>没有 {@code w} 标</b>。{@code 'R'}／{@code 'F'} 解出来是 {@code CMD_RANDOM}／
 *       {@code CMD_FAST}（{@code server.c:2234}、{@code :2240}；{@code server.h:214}、{@code :220}），
 *       都不是写标 —— 所以 {@code TIME} 一条也不该进 AOF，也不该推 RDB 的写计数；</li>
 *   <li><b>回的是两支 bulk</b>，不是两支整数。{@code server.c:2988-2997} 的 {@code timeCommand}
 *       是 {@code gettimeofday} + {@code addReplyMultiBulkLen(c,2)} + 两个
 *       {@code addReplyBulkLongLong}；后者走 {@code ll2string}（{@code networking.c:596-597}），
 *       打的是<b>十进制原样、不补零</b> —— 微秒 1234 就是 {@code $4=1234}，不是 {@code $6=001234}。
 *       这一条是客户端会踩的：按六位补零去 parse 的写法，在这里会少读两位。</li>
 * </ul>
 * 线上这一条命令在我们这儿根本不存在：分派表里没有 {@code "TIME"}，打过去落到
 * {@code CommandHandler} 的 default 那一支，回 {@code unknown command}。
 * 类里的第一支 @Test 因此有一半格子内就该红，而它红的形状正是"没有这条命令"。
 *
 * <h2>为什么钉的是"与自己的钟同量级"而不是日期</h2>
 * {@code TIME} 的返回值由墙上时钟决定，任何"期望 = 某个具体读数"的写法都是一把会随日历跳红的尺
 * （13n 刚修掉过一条同形状的尺）。所以这里钉的是五件<b>与读数无关</b>的性质：
 * 形状、数值域、单调不减、"线上那一串长度 == 把它按十进制重打的长度"、以及
 * <b>两支合成回毫秒之后落在自己那一次请求的前后两次读数之间</b>。最后这一条是
 * "两半必须同一次取值"的定义式判据（服务器与测试同进程、同一把钟，所以它是因果而不是超时），
 * 它顺带也把"秒是从别的钟来的"这类漂移一并拦下。另一处跟时刻沾边的格（"秒与客户端自己的钟
 * 差在 120 秒内"）比因果那一格宽松，它钉的是这条命令<em>为什么存在</em>那一层说法 ——
 * 答的必须是墙钟，不是开机到现在的秒数；两台钟各自走到哪一天都不影响判定。
 *
 * <h2>补零那一格的猎物怎么来（以及为什么它不是一条会跳红的尺）</h2>
 * "不补零"这件事，只有当某次观测到的微秒<b>本身不足 6 位</b>时，才分得出
 * {@code ll2string} 与 {@code %06d} 两种写法。做法是：先读一次，算出距离下一个整秒还剩多久，
 * 睡到边界前约 2 毫秒，再贴着边界连打 —— 于是跨过那一格的第一次读数天然落在
 * 个位到千位的微秒上。这条"贴边界"的路径<b>只为取证服务</b>，它本身不是判据：
 * 真正的判据是"每一次观测都满足 {@code payload == String.valueOf(它自己的数值)}"，
 * 这一句对正确实现恒真、不会因日历或调度而红。短微秒的观测次数写在失败消息里，
 * 万一某一批变异跑出"补零却全绿"，那个计数就是当场能读到的覆盖面证据（而不是事后追认）。
 *
 * <h2>与上游不同处，逐条写明（不是"以后再说"）</h2>
 * <ol>
 *   <li>{@code TIME} 的 {@code R}/{@code F} 两个标只在 {@code COMMAND}／{@code COMMAND COUNT}
 *       那一串接口上才在线上可见，而我们没有 {@code COMMAND}（下一格 ①）。所以这一支只能
 *       从<b>后果</b>上量"它不是写命令"（第三支 @Test 查盘），标本身没人读得到。</li>
 *   <li>上游 arity 检查发生在分派<b>之前</b>（{@code server.c:2612} 在 processCommand 里），
 *       我们是在分派之后由各 handler 自查参数个数。对 {@code TIME} 这一条，两种位置的回话
 *       逐字相同（下面 B 组那三格钉的就是逐字）；但 {@code MULTI} 里排队的那一支不同 ——
 *       入队时不查 arity，于是上游会在 {@code EXEC} 处以 {@code EXECABORT} 中止
 *       （{@code multi.c:135-137}）而我们不会。那是全命令共有的一个缺口，单独成卡，
 *       不在这一支里顺手改。</li>
 *   <li><b>微秒只有毫秒的分辨率</b>。Java 8 上 {@code System.currentTimeMillis()} 到毫秒，
 *       所以 {@code tv_usec} 恒为 1000 的倍数（{@code $1=0}、{@code $4=1000}、{@code $6=123000}），
 *       而上游 {@code gettimeofday} 给到个位微秒。这一条只影响"同一秒内能分辨多细"，
 *       不影响上面任何一格：数值域、不补零、单调、两支 bulk 四条性质对 1000 的倍数同样成立。
 *       真要拿到微秒得换 native clock，不是这一格的事。</li>
 * </ol>
 * 期望值出自上面那些行号的逐臂推演；250 通了之后要拿参考实例逐格复跑（本轮不通，
 * 见 {@code RedisMemoryFormatTest} 里同一句交代）。
 */
class RedisTimeCommandTest {

    private static final long DEADLINE_MS = 8_000L;

    /** 贴边界取证那一圈的时间与次数上限：超了就照现有观测收工，不拿它当判据。 */
    private static final long GRAB_MS = 2_000L;
    private static final int GRAB_CALLS = 3_000;

    private static final String ARITY_TEXT =
            "-ERR wrong number of arguments for 'time' command";

    // =================================================================================
    // 一、形状与钟：两支 bulk、数值域、单调、不补零
    // =================================================================================

    @Test
    void timeAnswersWithTwoBulkStringsFromTheClock() throws Exception {
        Path dir = Files.createTempDirectory("zcache-time-clock");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        server.setDataDir(dir.toString());
        Thread thread = startAndWait(server, port);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            Obs first = timedCall(socket, in, "TIME");
            expectTextCell(seen, wrong, "TIME ⇒ *2 里两支十进制 bulk（不是整数、不是状态串）",
                    shapeTag(first.line), "*2 两支十进制 bulk",
                    "上游是 addReplyMultiBulkLen(c,2) + 两个 addReplyBulkLongLong（server.c:2994-2996）；"
                            + "实际读到的整行是 " + first.line);

            long clientSec = System.currentTimeMillis() / 1000L;
            expectTextCell(seen, wrong, "秒那一支与客户端自己的钟差在 120 秒内",
                    String.valueOf(first.payload != null && Math.abs(first.sec - clientSec) <= 120L), "true",
                    "服务器 sec=" + (first.payload == null ? "读不出" : String.valueOf(first.sec))
                            + "，客户端 sec=" + clientSec);
            expectTextCell(seen, wrong, "微秒那一支落在 0..999999",
                    String.valueOf(first.payload != null && first.usec >= 0 && first.usec <= 999_999L), "true",
                    "gettimeofday 的 tv_usec 上界是一秒之内；第一次读到 "
                            + (first.payload == null ? first.line : first.payload[1]));
            // 两支必须是<em>同一次</em>取值：合起来得落在这一次请求自己的前后两次读数之间
            // （服务器与测试同进程、同一把钟，所以这是一条硬因果，不是宽容的超时）。
            expectTextCell(seen, wrong, "第一次取样的 (sec,usec) 合起来落在它自己那一次请求的前后之间",
                    String.valueOf(causalOk(first)), "true",
                    "sec=" + first.sec + " usec=" + first.usec + " 合成 " + combinedMs(first)
                            + "，而请求前/后 = " + first.beforeMs + "/" + first.afterMs);

            // 贴着下一个整秒的边界取证：跨过那一格的第一次读数就是"不足 6 位"的猎物。
            int calls = 1, backwards = 0, badFormat = 0, shapeBreaks = 0, causalBreaks = 0;
            int shortUsec = first.payload != null && first.payload[1].length() < 6 ? 1 : 0;
            long prevSec = first.sec, prevUsec = first.usec;
            if (first.payload != null) {
                long msIntoSecond = 1000L - (first.usec / 1000L);
                if (msIntoSecond > 20L) {
                    Thread.sleep(Math.max(0L, msIntoSecond - 2L));
                }
            }
            boolean crossed = false;
            long grabDeadline = System.currentTimeMillis() + GRAB_MS;
            while (calls < GRAB_CALLS && System.currentTimeMillis() < grabDeadline && shapeBreaks == 0) {
                Obs o = timedCall(socket, in, "TIME");
                calls++;
                if (o.payload == null) {
                    shapeBreaks++;
                    break;
                }
                if (o.sec < prevSec || (o.sec == prevSec && o.usec < prevUsec)) {
                    backwards++;
                }
                // 补零、正号、空格这类形状漂移都落在这一句上：线上那一串必须等于
                // 把它按十进制重打出来的那一串。对正确实现恒真，不随日历跳红。
                if (!o.payload[0].equals(String.valueOf(o.sec)) || !o.payload[1].equals(String.valueOf(o.usec))) {
                    badFormat++;
                }
                if (!causalOk(o)) {
                    causalBreaks++;
                }
                if (o.payload[1].length() < 6) {
                    shortUsec++;
                }
                boolean rolled = o.sec != prevSec;
                prevSec = o.sec;
                prevUsec = o.usec;
                if (rolled) {
                    crossed = true;
                    break;
                }
            }

            expectTextCell(seen, wrong, "贴边界那一圈里每一次读到的都还是 *2 两支 bulk",
                    String.valueOf(shapeBreaks), "0",
                    "打了 " + calls + " 次；这一格坏掉时消息里带的是第一次崩掉的形状");
            expectTextCell(seen, wrong, "这一圈里 (sec,usec) 从不倒退",
                    String.valueOf(backwards), "0",
                    "打了 " + calls + " 次、跨过整秒=" + crossed);
            expectTextCell(seen, wrong, "每一次观测都等于把它按十进制重打（ll2string 不补零）",
                    String.valueOf(badFormat), "0",
                    "观测 " + calls + " 次，其中不足 6 位的微秒 " + shortUsec + " 次"
                            + "（这个计数就是'补零有没有猎物'的覆盖面证据；跨整秒=" + crossed + "）");
            expectTextCell(seen, wrong, "每一次观测合起来都落在自己那一次请求的前后之间（两半同一次取值）",
                    String.valueOf(causalBreaks), "0",
                    "打了 " + calls + " 次；两半若是各自现读时钟，跨过整秒的那一次就会合出一个过去的毫秒数");

            // 上面那一格抓不到它想抓的那种坏法：两支各自现读时钟，只有<em>恰好</em>跨整秒的那一次
            // 才合出一个过去的毫秒数，而取样的那一圈是几十到几百次响应，摊得到的概率只有几个
            // 百分点（这不是推测：第一份测量轮 logs/measure_13p.out 里 TM6 那支的因果两格当场没红，
            // 整支 SURVIVED）。所以同一件事再钉一层结构的 —— 取时钟那一句在函数体里只许出现一次。
            // 结构守卫的两支证据写在 time_teeth.py 的 --selftest 第 5 道拦里：
            // 换回被否掉的旧设计必须红（TM6），一处逐字等价的改写必须绿。
            String body = handleTimeBody();
            expectTextCell(seen, wrong, "handleTime 的函数体里只读一次时钟（结构守卫）",
                    body == null ? "源码读不到" : String.valueOf(countClockReads(body)), "1",
                    "源码 " + HANDLER_SOURCE + "，函数体 "
                            + (body == null ? "没读到（这一格不许静默通过）" : body.length() + " 字节"));

            // 分派大小写无关：上游在命令表里 strcasecmp 比名字。
            send(socket, "time");
            String lower = readTyped(in);
            expectTextCell(seen, wrong, "小写 time 一样答（分派不分大小写）",
                    shapeTag(lower), "*2 两支十进制 bulk", "实际读到的整行是 " + lower);
            send(socket, "TiMe");
            String mixed = readTyped(in);
            expectTextCell(seen, wrong, "混合写法 TiMe 一样答",
                    shapeTag(mixed), "*2 两支十进制 bulk", "实际读到的整行是 " + mixed);

            // 否定式的同形状猎物：认不出的名字必须仍然回 unknown command，
            // 否则上面两格"大小写都答"就可能是"谁都被塞进 TIME"造成的。
            expectTyped(seen, wrong, socket, in, "ZzQwTime 仍然认不出（上面两格的阳性对照）",
                    "-ERR unknown command 'ZzQwTime'", "ZzQwTime");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
            deleteTree(dir);
        }
        assertTrue(wrong.isEmpty(), "TIME 的两支 bulk（" + seen.size() + " 格）不合格: " + wrong
                + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    // =================================================================================
    // 二、arity：表里那一个 1，翻成线上就是"恰好一个词"
    // =================================================================================

    @Test
    void arityIsTheOneWrittenInTheCommandTable() throws Exception {
        Path dir = Files.createTempDirectory("zcache-time-arity");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        server.setDataDir(dir.toString());
        Thread thread = startAndWait(server, port);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // arity==1 且为正数 ⇒ 两个词就错，哪怕第二个词是空串（'TIME ""' 的 argc 是 2）。
            expectTyped(seen, wrong, socket, in, "TIME 加一个词 ⇒ arity 错，命令名小写",
                    ARITY_TEXT, "TIME", "extra");
            expectTyped(seen, wrong, socket, in, "TIME 加三个词 ⇒ 同一句",
                    ARITY_TEXT, "TIME", "a", "b", "c");
            expectTyped(seen, wrong, socket, in, "TIME 加一个空词 ⇒ 照样 arity 错（不是少一个参数）",
                    ARITY_TEXT, "TIME", "");
            // 出错之后这条连接还在，且光秃秃一句 TIME 是合法的。
            expectTyped(seen, wrong, socket, in, "回完 arity 错之后框架没散（PING ⇒ +PONG）",
                    "+PONG", "PING");
            // 这一格不测服务器测的是尺：'这条命令压根不存在'与'arity 那一闸反过来装'都会让上面
            // 三格红，具名尺上它俩同名。加词与不加词必须回出<em>不同形状</em>才分得开 ——
            // 比的是 shapeTag 而不是原始串，否则两串时钟读数恰好相同会把这一格变成抛硬币。
            String bare = readAfterSend(socket, in, "TIME");
            String padded = readAfterSend(socket, in, "TIME", "extra");
            expectTextCell(seen, wrong, "加词与不加词回的不是同一种形状（TM2 与 TM8 的分界）",
                    String.valueOf(!shapeTag(bare).equals(shapeTag(padded))), "true",
                    "bare=" + shapeTag(bare) + " / extra=" + shapeTag(padded));
            expectTextCell(seen, wrong, "光秃秃一句 TIME 答的还是 *2 两支 bulk",
                    shapeTag(bare), "*2 两支十进制 bulk", "arity 1 的意思就是'恰好这一个词'");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
            deleteTree(dir);
        }
        assertTrue(wrong.isEmpty(), "TIME 的 arity（" + seen.size() + " 格）不合格: " + wrong
                + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    // =================================================================================
    // 三、它不是写命令：一条也不进日志
    // =================================================================================

    @Test
    void timeWritesNothingToTheLog() throws Exception {
        Path dir = Files.createTempDirectory("zcache-time-aof");
        Path aof = dir.resolve("appendonly.aof");
        int port = freePort();
        RedisServer server = new RedisServer("127.0.0.1", port, 0);
        server.setDataDir(dir.toString());
        Thread thread = startAndWait(server, port);
        AofPersistence a = server.getAofPersistence();
        assertNotNull(a, "前置条件: 这一台得真的起了 AOF，才谈得上 TIME 进不进日志");
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        String key = "tkey";
        try (Socket socket = connect(port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            expectTyped(seen, wrong, socket, in, "前置：写入落得下去", "+OK", "SET", key, "one");
            String before = readBody(aof);
            expectTextCell(seen, wrong, "那条 SET 在盘上留下一条记录",
                    String.valueOf(countSetRecords(before, key, null)), "1",
                    "盘上没记录的话，下面'TIME 没进日志'是空跑");

            for (int i = 0; i < 40; i++) {
                send(socket, i % 2 == 0 ? "TIME" : "time");
                readTyped(in);
            }
            String after = readBody(aof);
            expectTextCell(seen, wrong, "打了 40 次 TIME 之后，SET 记录还是 1 条",
                    String.valueOf(countSetRecords(after, key, null)), "1",
                    "TIME 在表上没有 w 标（server.c:295 的 \"RF\"），不该被记成写");
            String upperNeedle = "$4\r\nTIME\r\n";
            String lowerNeedle = "$4\r\ntime\r\n";
            expectTextCell(seen, wrong, "盘上找不到 TIME 的原文（大小写两种形状都数）",
                    (after.contains(upperNeedle) || after.contains(lowerNeedle)) ? "有" : "无", "无",
                    "大写命中 " + after.indexOf(upperNeedle) + "，小写命中 " + after.indexOf(lowerNeedle));
            // 上面那格是"找不到"，它的猎物必须是同一把尺在同样的形状上找得到东西。
            expectTextCell(seen, wrong, "同一把尺在 SET 那种形状上找得到（上一条负向断言的阳性对照）",
                    after.contains("$3\r\nSET\r\n") ? "有" : "无", "有",
                    "needle 的形状与上一条完全一致，只差命令名与长度头");

            expectTyped(seen, wrong, socket, in, "再来一条 SET 落得下去", "+OK", "SET", key, "two");
            expectTextCell(seen, wrong, "条数跟着变成 2（尺在动，不是读到旧盘）",
                    String.valueOf(countSetRecords(readBody(aof), key, null)), "2",
                    "上一格的'还是 1 条'要靠这一格证明它不是读不到东西");
        } finally {
            server.stop();
            thread.join(DEADLINE_MS);
            deleteTree(dir);
        }
        assertTrue(wrong.isEmpty(), "TIME 不进日志（" + seen.size() + " 格）不合格: " + wrong
                + " [RED_CELLS=" + String.join("|", wrong.keySet()) + "]");
    }

    // =================================================================================
    // 线格式读取（与 RedisConfigCommandTest 同一套：类型头留在文本里）
    // =================================================================================

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

    private static String readAfterSend(Socket socket, DataInputStream in, String... args) throws IOException {
        send(socket, args);
        return readTyped(in);
    }

    /** 一次取样的全部证据：请求前后的本地毫秒、线上读到的那一整行、从那一行剥出来的两支。 */
    private static final class Obs {
        private long beforeMs, afterMs;
        private String line;
        private String[] payload;
        private long sec = -1L, usec = -1L;
    }

    private static Obs timedCall(Socket socket, DataInputStream in, String... args) throws IOException {
        Obs o = new Obs();
        o.beforeMs = System.currentTimeMillis();
        send(socket, args);
        o.line = readTyped(in);
        o.afterMs = System.currentTimeMillis();
        o.payload = twoBulkPayloads(o.line);
        if (o.payload != null) {
            o.sec = Long.parseLong(o.payload[0]);
            o.usec = Long.parseLong(o.payload[1]);
        }
        return o;
    }

    /** 两支合成回毫秒。剥不出两支就是 {@code -1}，那条因果自然不成立。 */
    private static long combinedMs(Obs o) {
        return o.payload == null ? -1L : o.sec * 1000L + o.usec / 1000L;
    }

    /**
     * 因果判据：服务器读钟发生在"我发请求"与"我读完"之间，而两边用的是同一把进程内的钟，
     * 所以合成出来的毫秒必须落在这两个读数之内（留一枚毫秒的抖动，因为 {@code currentTimeMillis}
     * 本身按毫秒跳）。这一条不是超时，是<em>同一次取值</em>的定义。
     */
    private static boolean causalOk(Obs o) {
        long v = combinedMs(o);
        return v >= o.beforeMs - 1L && v <= o.afterMs + 1L;
    }

    /**
     * 把一行回复收成"形状"这一个可比对的短标签：{@code *2 两支十进制 bulk} 或者
     * {@code 形状不是: <原行>}。判据只认前者，红消息里带的是后者，两者不用互相迁就。
     */
    private static String shapeTag(String typed) {
        return twoBulkPayloads(typed) != null ? "*2 两支十进制 bulk" : "形状不是: " + typed;
    }

    /**
     * {@code *2|$10=1758…|$4=1234} 剥成两支 payload；形状不对返回 {@code null}。
     * 剥的时候逐位严格：必须是恰好两支、都必须带 {@code $<数字>=} 头、payload 只能是十进制，
     * 而且第二支之后不许再有东西。
     */
    private static String[] twoBulkPayloads(String typed) {
        if (typed == null || !typed.startsWith("*2|")) {
            return null;
        }
        String[] out = new String[2];
        int at = 3;
        for (int i = 0; i < 2; i++) {
            if (at >= typed.length() || typed.charAt(at) != '$') {
                return null;
            }
            int eq = typed.indexOf('=', at);
            if (eq < 0) {
                return null;
            }
            String head = typed.substring(at + 1, eq);
            if (!head.matches("\\d+")) {
                return null;
            }
            int len = Integer.parseInt(head);
            if (eq + 1 + len > typed.length()) {
                return null;
            }
            String payload = typed.substring(eq + 1, eq + 1 + len);
            if (!payload.matches("\\d+")) {
                return null;
            }
            out[i] = payload;
            at = eq + 1 + len;
            if (i == 0) {
                if (at >= typed.length() || typed.charAt(at) != '|') {
                    return null;
                }
                at++;
            }
        }
        return at == typed.length() ? out : null;
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
    // 起停（与 RedisConfigCommandTest 同一套）
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
        }, "test-z-cache-time");
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

    // =================================================================================
    // 主源码那一侧（结构守卫用）
    // =================================================================================

    /** surefire 的工作目录就是模块 basedir，所以这个相对路径是对的；读不到时那一格当场红。 */
    private static final String HANDLER_SOURCE =
            "src/main/java/com/zifang/z/cache/core/command/CommandHandler.java";

    private static String handleTimeBody() {
        java.io.File f = new java.io.File(HANDLER_SOURCE);
        if (!f.isFile()) {
            f = new java.io.File("../z-cache-core/" + HANDLER_SOURCE);
        }
        if (!f.isFile()) {
            return null;
        }
        try {
            String all = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            int at = all.indexOf("private Object handleTime(String[] args) {");
            if (at < 0) {
                return null;
            }
            int end = all.indexOf("\n    }\n", at);
            return end < 0 ? null : all.substring(at, end);
        } catch (IOException read) {
            return null;
        }
    }

    /** 函数体里"取一次现在"的写法有几种，全都算进来数一遍 —— 只认某一种写法会放过另外几种。 */
    private static int countClockReads(String body) {
        int n = 0;
        for (String needle : new String[]{"System.currentTimeMillis(", "System.nanoTime(",
                "Instant.now(", "new Date(", "Calendar.getInstance("}) {
            for (int i = body.indexOf(needle); i >= 0; i = body.indexOf(needle, i + 1)) {
                n++;
            }
        }
        return n;
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

    /** 数盘上有几条 {@code SET <key> …} 记录 —— 只按字节找 RESP 的定长头，不读我们自己的记账。 */
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
}
