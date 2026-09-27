package com.zifang.z.cache.client;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡 #46（13x）—— 客户端把命令名<em>重复</em>写进自己的参数里。
 *
 * <p>
 * {@link ZCacheConnection#sendCommand(String, Object...)} 走
 * {@code RespArray.command(command, args)}，那一步<em>已经</em>把命令名放在元素 0；
 * 而 {@code ZCacheClient} / {@code ZCachePipeline} 里有 20 个变长参数方法又自己
 * {@code args[0] = "HDEL"} 了一遍，于是线上发出去的是 {@code *6 HDEL HDEL hk f1 f2}。
 * 服务器按"元素 0 是命令名、元素 1 起是参数"拆，就把 {@code HDEL} 当成了键名 ——
 * 每一条这样的命令都在动一个不存在的键，而调用方拿到的是一个看着合理的数字，
 * 所以它不会在任何返回值上露出来。最坏的一支是 {@code subscribe("a","b")}：
 * 线上是 {@code SUBSCRIBE SUBSCRIBE a b}，订阅到了三个频道（13w 的带外探针当场量到，
 * 原文 {@code ~/.cache/zcache_gauges/pubsub_frame_mut/probe_client_desync.out}）。
 * </p>
 *
 * <p>
 * <b>为什么以前全绿：</b>改前的提交树（{@code 8eb872b}）里 {@code z-cache-client} 那 135 例<em>无一例</em>
 * 调用过这 20 个方法 —— 复算 {@code git grep -n -E "\.(hdel|sadd|lpush|rpush|srem|zrem|hmget|hmset|sinter|
 * sunion|sdiff|watch|subscribe)\(" 8eb872b -- z-cache-client/src/test} 命中 <b>0</b>，而同一把尺换成
 * {@code client\.(set|get)\()} 在同一棵树上命中 <b>16</b>（阳性对照：不是 grep 写坏了，是真的没人调用）。
 * 所以本文件的判据不是"补一条边界"，而是这一族命令<em>第一次</em>被量到。
 * 判据分两半：交付那一半用一台<em>只抄字节的影子服务器</em>（不起真服务、也不用生产解码器，
 * 免得拿被测物解码被测物），结构那一半钉"没有站点把命令名塞回自己的 {@code args[0]}"。
 * </p>
 */
class ZCacheClientWireFrameTest {

    private static final long DEADLINE_MS = 8_000L;
    /** 一次调用之后"读到安静为止"的窗口：它同时也是"这一手发了几条命令"的量具。 */
    private static final int QUIET_MS = 300;
    /** 取号只在操作系统<em>出站</em>区间之下，见 {@code ZCacheClientIntegrationTest#freePort()} 的账。 */
    private static final int PORT_BASE = 20000, PORT_SPAN = 10000;
    private static final AtomicInteger PORT_CURSOR =
            new AtomicInteger(new java.util.Random().nextInt(PORT_SPAN));

    private static ServerSocket shadow;
    private static ZCacheClientConfig config;
    private static final List<List<String>> FRAMES = new ArrayList<>();
    private static Thread acceptor;
    private static volatile boolean running;

    @BeforeAll
    static void startShadowServer() throws Exception {
        int port = freePort();
        shadow = new ServerSocket();
        shadow.bind(new InetSocketAddress("127.0.0.1", port), 16);
        running = true;
        acceptor = new Thread(() -> {
            while (running) {
                final Socket client;
                try {
                    client = shadow.accept();
                } catch (IOException gone) {
                    return;
                }
                Thread handler = new Thread(() -> serve(client), "wire-shadow-conn");
                handler.setDaemon(true);
                handler.start();
            }
        }, "wire-shadow-server");
        acceptor.setDaemon(true);
        acceptor.start();
        config = new ZCacheClientConfig("127.0.0.1", port)
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(5));
    }

    /** 逐条抄下客户端发出的命令，一律回 {@code :7}。 */
    private static void serve(Socket client) {
        try (Socket socket = client) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (running) {
                List<String> frame = readArray(in);
                if (frame == null) {
                    return;
                }
                synchronized (FRAMES) {
                    FRAMES.add(frame);
                    FRAMES.notifyAll();
                }
                // 统一回一个整数：toLong 要整数，toString 什么都能接，
                // 而期望 RespArray 的方法都先 instanceof 再取，拿不到就交空表。
                out.write(":7\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException gone) {
            // 收口时连接被关：正常退出
        }
    }

    @AfterAll
    static void stopShadowServer() throws Exception {
        running = false;
        if (shadow != null) {
            shadow.close();
        }
        if (acceptor != null) {
            acceptor.join(DEADLINE_MS);
        }
    }

    // =================================================================================
    // 交付那一半：每个方法各调用一次，把它真正发出去的那条命令逐元素比
    // =================================================================================

    /** 一格：一次调用 → 期望的线上元素序列（命令名必须只出现在元素 0）。 */
    private static final class Case {
        final String cell;
        final List<String> expected;
        final java.util.function.Function<ZCacheClient, Object> call;

        Case(String cell, List<String> expected, java.util.function.Function<ZCacheClient, Object> call) {
            this.cell = cell;
            this.expected = expected;
            this.call = call;
        }
    }

    /**
     * 28 格：7 格<em>正面控制</em>（今天就是对的写法，量具一坏它们先红）+ 13 个
     * {@code ZCacheClient} 站点 + 8 个 {@code ZCachePipeline} 站点。
     */
    private static final List<Case> CASES = Arrays.asList(
            // ---- 正面控制：同一个 sendCommand，只是没往 args[0] 里塞名字
            new Case("C-del", Arrays.asList("DEL", "ck1", "ck2"), c -> c.del("ck1", "ck2")),
            new Case("C-exists", Arrays.asList("EXISTS", "ck1"), c -> c.exists("ck1")),
            new Case("C-mget", Arrays.asList("MGET", "ck1", "ck2"), c -> c.mget("ck1", "ck2")),
            new Case("C-mset", Arrays.asList("MSET", "ck1", "v1"), c -> c.mset("ck1", "v1")),
            new Case("C-set", Arrays.asList("SET", "ck1", "v1"), c -> c.set("ck1", "v1")),
            new Case("C-publish", Arrays.asList("PUBLISH", "ch1", "m1"), c -> c.publish("ch1", "m1")),
            // ---- ZCacheClient 的 13 处
            new Case("V-hdel", Arrays.asList("HDEL", "hk", "f1", "f2"), c -> c.hdel("hk", "f1", "f2")),
            new Case("V-hmget", Arrays.asList("HMGET", "hk", "f1", "f2"), c -> c.hmget("hk", "f1", "f2")),
            new Case("V-hmset", Arrays.asList("HMSET", "hk", "f1", "v1", "f2", "v2"),
                    c -> c.hmset("hk", "f1", "v1", "f2", "v2")),
            new Case("V-lpush", Arrays.asList("LPUSH", "lk", "a", "b"), c -> c.lpush("lk", "a", "b")),
            new Case("V-rpush", Arrays.asList("RPUSH", "lk", "a", "b"), c -> c.rpush("lk", "a", "b")),
            new Case("V-sadd", Arrays.asList("SADD", "sk", "m1", "m2"), c -> c.sadd("sk", "m1", "m2")),
            new Case("V-srem", Arrays.asList("SREM", "sk", "m1", "m2"), c -> c.srem("sk", "m1", "m2")),
            new Case("V-sinter", Arrays.asList("SINTER", "s1", "s2"), c -> c.sinter("s1", "s2")),
            new Case("V-sunion", Arrays.asList("SUNION", "s1", "s2"), c -> c.sunion("s1", "s2")),
            new Case("V-sdiff", Arrays.asList("SDIFF", "s1", "s2"), c -> c.sdiff("s1", "s2")),
            new Case("V-zrem", Arrays.asList("ZREM", "zk", "m1", "m2"), c -> c.zrem("zk", "m1", "m2")),
            new Case("V-watch", Arrays.asList("WATCH", "wk1", "wk2"), c -> c.watch("wk1", "wk2")),
            new Case("V-subscribe", Arrays.asList("SUBSCRIBE", "ca", "cb"), c -> {
                c.subscribe("ca", "cb");
                return null;
            }),
            // ---- ZCachePipeline 的 8 处（一次 sync 把那一条命令推出去）
            new Case("P-set", Arrays.asList("SET", "pk", "pv"), c -> c.pipeline().set("pk", "pv").syncAndReturnAll()),
            new Case("P-del", Arrays.asList("DEL", "pk1", "pk2"), c -> c.pipeline().del("pk1", "pk2").syncAndReturnAll()),
            new Case("P-hdel", Arrays.asList("HDEL", "pk", "f1", "f2"),
                    c -> c.pipeline().hdel("pk", "f1", "f2").syncAndReturnAll()),
            new Case("P-lpush", Arrays.asList("LPUSH", "plk", "a", "b"),
                    c -> c.pipeline().lpush("plk", "a", "b").syncAndReturnAll()),
            new Case("P-rpush", Arrays.asList("RPUSH", "plk", "a", "b"),
                    c -> c.pipeline().rpush("plk", "a", "b").syncAndReturnAll()),
            new Case("P-sadd", Arrays.asList("SADD", "psk", "m1", "m2"),
                    c -> c.pipeline().sadd("psk", "m1", "m2").syncAndReturnAll()),
            new Case("P-srem", Arrays.asList("SREM", "psk", "m1", "m2"),
                    c -> c.pipeline().srem("psk", "m1", "m2").syncAndReturnAll()),
            new Case("P-zrem", Arrays.asList("ZREM", "pzk", "m1", "m2"),
                    c -> c.pipeline().zrem("pzk", "m1", "m2").syncAndReturnAll()));

    @Test
    void eachVarargsSiteEmitsTheCommandNameExactlyOnce() {
        Map<String, String> seen = new TreeMap<>();
        Map<String, List<String>> wrong = new LinkedHashMap<>();
        for (Case kase : CASES) {
            ZCacheClient client = new ZCacheClient(config);
            String actual;
            try {
                client.connect();
                clearFrames();
                kase.call.apply(client);
                actual = describe(awaitFrames());
            } catch (Exception e) {
                actual = "调用抛了 " + e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                client.close();
            }
            cell(seen, wrong, kase.cell, actual, String.join(" ", kase.expected));
        }
        finish("eachVarargsSiteEmitsTheCommandNameExactlyOnce", seen, wrong);
    }

    /**
     * 等这一手的命令都到齐：先等第一帧（{@link #DEADLINE_MS} 内必须到，否则是量具坏），
     * 再读到安静为止 —— "多出一条"本身就是要判的分歧（比如 pipeline 把两条写成一批）。
     */
    private static List<List<String>> awaitFrames() {
        List<List<String>> got = new ArrayList<>();
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        synchronized (FRAMES) {
            while (FRAMES.isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    FRAMES.wait(Math.max(1, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return got;
                }
            }
            long quietUntil = System.currentTimeMillis() + QUIET_MS;
            while (System.currentTimeMillis() < quietUntil) {
                int before = FRAMES.size();
                try {
                    FRAMES.wait(Math.max(1, quietUntil - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (FRAMES.size() > before) {
                    quietUntil = System.currentTimeMillis() + QUIET_MS;
                }
            }
            got.addAll(FRAMES);
        }
        return got;
    }

    private static void clearFrames() {
        synchronized (FRAMES) {
            FRAMES.clear();
        }
    }

    /** 一帧的命令名序列；帧数不是一的时候把它写进值里，红消息就同时说明"发多了/发少了"。 */
    private static String describe(List<List<String>> frames) {
        if (frames.size() == 1) {
            return String.join(" ", frames.get(0));
        }
        StringBuilder sb = new StringBuilder("帧数=").append(frames.size());
        for (List<String> frame : frames) {
            sb.append(" [").append(String.join(" ", frame)).append("]");
        }
        return sb.toString();
    }

    // =================================================================================
    // 结构那一半：没有站点把命令名塞回自己的 args[0]
    // =================================================================================

    private static final Pattern ARGS0 = Pattern.compile("args\\[0\\]\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"");
    private static final Pattern SENDS = Pattern.compile("sendCommand(?:Async)?\\(\"([A-Z][A-Z0-9_]*)\"");

    @Test
    void noSitePassesTheCommandNameInsideItsOwnArgs() {
        Map<String, String> seen = new TreeMap<>();
        Map<String, List<String>> wrong = new LinkedHashMap<>();
        for (String rel : new String[]{
                "z-cache-client/src/main/java/com/zifang/z/cache/client/ZCacheClient.java",
                "z-cache-client/src/main/java/com/zifang/z/cache/client/ZCachePipeline.java"}) {
            String tag = rel.endsWith("ZCacheClient.java") ? "client" : "pipeline";
            Set<String> stuffed = new LinkedHashSet<>();
            Set<String> sent = new LinkedHashSet<>();
            for (String line : codeLinesOf(rel)) {
                collect(ARGS0, line, stuffed);
                collect(SENDS, line, sent);
            }
            Set<String> both = new LinkedHashSet<>(stuffed);
            both.retainAll(sent);
            // 正面控制：解析必须看得见真命令名（两个文件各自 83 / 22 种），否则"交集为空"
            // 只是正则坏了。阈值取 15：离两边都还有余量，又不肯在解析不出东西时通过。
            cell(seen, wrong, "S" + tag + "-seesCommands", String.valueOf(sent.size() >= 15), "true");
            cell(seen, wrong, "S" + tag + "-dupNames", both.toString(), "[]");
        }
        finish("noSitePassesTheCommandNameInsideItsOwnArgs", seen, wrong);
    }

    private static void collect(Pattern pattern, String line, Set<String> into) {
        Matcher m = pattern.matcher(line);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    // =================================================================================
    // 记账：逐格收进表，最后<em>一次</em>断言（JUnit 的 fail-fast 会吞掉同方法里后面的格）
    // =================================================================================

    private static void cell(Map<String, String> seen, Map<String, List<String>> wrong,
                             String name, String actual, String expected) {
        seen.put(name, actual);
        if (!actual.equals(expected)) {
            wrong.put(name, Arrays.asList(expected, actual));
        }
    }

    private static void finish(String test, Map<String, String> seen, Map<String, List<String>> wrong) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : wrong.entrySet()) {
            sb.append("\n  ").append(e.getKey()).append(": 期望 [").append(e.getValue().get(0))
                    .append("] 实得 [").append(e.getValue().get(1)).append("]");
        }
        assertTrue(wrong.isEmpty(),
                test + " 有 " + wrong.size() + "/" + seen.size() + " 格不一致" + sb
                        + "\n[RED_CELLS=" + String.join(",", wrong.keySet()) + "] [RED_ROWS="
                        + wrong.size() + "/" + seen.size() + "]");
    }

    // =================================================================================
    // 影子服务器的读半：只认"一条命令 = 一个 bulk string 数组"
    // =================================================================================

    private static List<String> readArray(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        if (header.isEmpty()) {
            return new ArrayList<>();
        }
        if (header.charAt(0) != '*') {
            throw new IOException("影子服务器只接 RESP 数组，实得 [" + header + "]");
        }
        int n = Integer.parseInt(header.substring(1));
        List<String> elements = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String len = readLine(in);
            if (len == null || len.isEmpty() || len.charAt(0) != '$') {
                throw new IOException("第 " + i + " 个元素不是 bulk string：[" + len + "]");
            }
            int size = Integer.parseInt(len.substring(1));
            byte[] body = new byte[size + 2];
            int off = 0;
            while (off < body.length) {
                int read = in.read(body, off, body.length - off);
                if (read < 0) {
                    return null;
                }
                off += read;
            }
            elements.add(new String(body, 0, size, StandardCharsets.UTF_8));
        }
        return elements;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
        }
        if (b < 0 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<String> codeLinesOf(String relativeToRepo) {
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
        try {
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
        } catch (IOException e) {
            throw new IllegalStateException("读不动 " + path + " —— 量具失效，不作判定", e);
        }
        return code;
    }

    private static String withoutFirstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static int freePort() throws IOException {
        for (int i = 0; i < PORT_SPAN; i++) {
            int port = PORT_BASE + Math.floorMod(PORT_CURSOR.getAndIncrement(), PORT_SPAN);
            try (ServerSocket probe = new ServerSocket()) {
                probe.bind(new InetSocketAddress("127.0.0.1", port), 1);
                return port;
            } catch (IOException taken) {
                // 顺着一个固定的低位窗口往下找，别把号码发进出站派发区间
            }
        }
        throw new IOException("窗口 " + PORT_BASE + "-" + (PORT_BASE + PORT_SPAN - 1)
                + " 里找不出一枚可 bind 的端口");
    }
}
