package com.zifang.z.cache.spring.host;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.core.server.RedisServer;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 本地读数台 —— 把**真实的** {@link CacheProxyController} 挂到**真实的 HTTP** 上，
 * 后面接一台**真的 RESP server**。
 *
 * <p><b>为什么需要它</b>（TASK-20260925-041）：本卡的验收第 6/7/8 条要的是**实测读数**
 * ——「503 的 hint 里是不是真因」「响应体里有没有一行的 type 是 dict」「错位行有没有被标成
 * desyncSuspect」「顶层 desyncRows/totalRows 对不对得上」。
 * 这些读数必须来自**真的 controller 代码 + 真的 RESP 报文**，不能靠读源码推断，也不能靠造 JSON 假装。
 *
 * <p><b>它模拟的是哪一段现实</b>：
 * <ul>
 *   <li>路 A <b>不可用</b>：把 {@code ZCacheEmbeddedServerConfig.SHARED_SERVER} 反射设成一个
 *       **没有 bind 的** RedisServer（反射成功、{@code isRunning()=false}）
 *       ⇒ 这就是 B1 的现场（真 z-opc 上是 6379 被别的进程占了）。</li>
 *   <li>路 B <b>可走</b>：客户端连一台真的 RESP server。
 *       {@code normal} 模式接 z-cache 自己的 {@link RedisServer}；
 *       {@code desync} 模式接 {@link MisbehavingRespServer} —— 那台会**故意把 TTL 的整数应答
 *       答到 TYPE 槽里**，用来确定性地复现本卡要治的"应答错位"（真实环境里是 6~7% 的间歇错位，
 *       靠运气复现不了；这里让它 100% 发生，判据才是可测的）。</li>
 * </ul>
 *
 * <p><b>用法</b>（测试作用域，不进任何发布件）：
 * <pre>
 *   mvn -pl z-cache-spring-boot-starter test-compile
 *   java -cp &lt;classpath&gt; com.zifang.z.cache.spring.host.CacheProxyControllerLocalHarness [httpPort] [normal|desync]
 *   # 启动后打印 HARNESS_READY port=… 与 RESP 端口，随后：
 *   curl -s 'http://127.0.0.1:&lt;httpPort&gt;/api/cache/keys?source=client&amp;limit=50'
 *   curl -s -i 'http://127.0.0.1:&lt;httpPort&gt;/api/cache/keys?source=embedded'
 *   ZOPC_BASE=http://127.0.0.1:&lt;httpPort&gt;/api TOK=dummy \
 *     python3 ../../z-opc-foundation-lead/003_辅助能力/frontend-incubation-audit/script/cache_resp_shape_probe.py 8 100
 * </pre>
 * 本台**不校验任何鉴权**（SSO 拦截器在 z-opc 那一侧，不在本仓）⇒ 探针的 TOK 只是它自带的 Cookie 头。
 */
public final class CacheProxyControllerLocalHarness {

    private CacheProxyControllerLocalHarness() {
    }

    public static void main(String[] args) throws Exception {
        int httpPort = args.length > 0 ? Integer.parseInt(args[0].trim()) : 18899;
        String mode = args.length > 1 ? args[1].trim() : "normal";

        // ---- 路 A：反射成功但**没 bind**（B1 现场）
        RedisServer notRunning = new RedisServer("127.0.0.1", freePort(), 1024);
        setSharedServer(notRunning);

        // ---- 路 B：真的 RESP server
        int respPort;
        if ("desync".equals(mode)) {
            MisbehavingRespServer bad = new MisbehavingRespServer(freePort());
            bad.start();
            respPort = bad.port();
            System.out.println("[harness] mode=desync ⇒ 故意让 TYPE 槽回 TTL 的整数（复现应答错位）");
        } else {
            RedisServer real = new RedisServer("127.0.0.1", freePort(), 1024);
            Thread t = new Thread(() -> {
                try {
                    real.start();
                } catch (Throwable ignore) {
                    // 停服时正常抛出
                }
            });
            t.setDaemon(true);
            t.start();
            waitUntilRunning(real);
            respPort = real.getPort();
            System.out.println("[harness] mode=normal ⇒ 真的 z-cache RedisServer");
        }

        ZCacheClient client = new ZCacheClient("127.0.0.1", respPort);
        // 造一点数据：类型不一、有的有 TTL、有的没有
        client.set("thread0_key1", "hello");
        client.set("thread0_key2", "world");
        client.set("thread1_key7", "x");

        final CacheProxyController ctl = new CacheProxyController(
                provider(client), provider((ZCacheEmbeddedServerConfig) null));

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", httpPort), 0);
        http.createContext("/api/cache", (HttpExchange ex) -> {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            try {
                dispatch(ctl, ex, resp);
            } catch (Throwable t) {
                resp.setStatus(500);
                try {
                    resp.getWriter().write("{\"status\":\"error\",\"message\":\"harness: " + t + "\"}");
                } catch (Exception ignore) {
                    // ignore
                }
            }
            byte[] body = resp.getContentAsByteArray();
            ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            ex.sendResponseHeaders(resp.getStatus(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
            ex.close();
        });
        http.start();
        System.out.println("[harness] HARNESS_READY http=127.0.0.1:" + httpPort
                + " resp=127.0.0.1:" + respPort + " mode=" + mode);
        System.out.flush();
        Thread.currentThread().join();
    }

    private static void dispatch(CacheProxyController ctl, HttpExchange ex, MockHttpServletResponse resp)
            throws Exception {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());        if (path.endsWith("/__instance")) {
            ctl.instance(resp);
        } else if (path.endsWith("/overview")) {
            ctl.overview(resp);
        } else if (path.endsWith("/keys")) {
            ctl.keys(q.getOrDefault("pattern", "*"), intOf(q.get("db"), 0),
                    intOf(q.get("limit"), 200), q.getOrDefault("source", "auto"), resp);
        } else if (path.endsWith("/key")) {
            ctl.key(q.getOrDefault("key", ""), intOf(q.get("db"), 0),
                    intOf(q.get("maxElements"), 50), q.getOrDefault("source", "auto"), resp);
        } else if (path.endsWith("/info")) {
            ctl.info(resp);
        } else if (path.endsWith("/hotkeys")) {
            ctl.hotkeys(intOf(q.get("db"), 0), intOf(q.get("limit"), 50), resp);
        } else if (path.endsWith("/command-catalog")) {
            ctl.commandCatalog(resp);
        } else {
            resp.setStatus(404);
            resp.getWriter().write("{\"status\":\"error\",\"message\":\"unknown endpoint\"}");
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                try {
                    m.put(java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                            java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                } catch (Exception e) {
                    m.put(kv.substring(0, i), kv.substring(i + 1));
                }
            }
        }
        return m;
    }

    private static int intOf(String s, int dflt) {
        try {
            return s == null ? dflt : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static void waitUntilRunning(RedisServer s) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (s.isRunning()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("RedisServer 没起来");
    }

    /** 把 SHARED_SERVER 设成"反射得到但没 bind"的实例 —— 复现 B1 */
    static void setSharedServer(RedisServer server) throws Exception {
        Field f = ZCacheEmbeddedServerConfig.class.getDeclaredField("SHARED_SERVER");
        f.setAccessible(true);
        f.set(null, server);
    }

    private static <T> ObjectProvider<T> provider(final T value) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject() {
                if (value == null) {
                    throw new NoSuchBeanDefinitionException(Object.class);
                }
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                List<T> l = new ArrayList<T>();
                if (value != null) {
                    l.add(value);
                }
                return l.iterator();
            }
        };
    }

    /**
     * 故意**说错话**的 RESP server：TYPE 槽里回 TTL 的整数应答。
     *
     * <p>真实环境里那 6~7% 的错位是间歇的（对面是更老的一次 z-cache 构建 + 业务共用连接），
     * 靠运气复现 ⇒ 判据没法测。这里让它 100% 发生：**只要本卡把"错位"标出来而不是洗成正常，
     * 这台 server 就是一把尺**（它同时也是回归锁：哪天有人把归一改成"什么字符串都当类型"，
     * 这台立刻会把假类型放行）。
     */
    static final class MisbehavingRespServer implements Runnable {
        private final ServerSocket ss;
        private final Thread thread;

        MisbehavingRespServer(int port) throws Exception {
            this.ss = new ServerSocket();
            this.ss.setReuseAddress(true);
            this.ss.bind(new InetSocketAddress("127.0.0.1", port));
            this.thread = new Thread(this, "misbehaving-resp");
            this.thread.setDaemon(true);
        }

        int port() {
            return ss.getLocalPort();
        }

        void start() {
            thread.start();
        }

        @Override
        public void run() {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    handle(s);
                } catch (Exception ignore) {
                    // 连接断了就等下一条
                }
            }
        }

        private void handle(Socket s) throws Exception {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (true) {
                List<String> cmd = readCommand(in);
                if (cmd == null) {
                    return;
                }
                String name = cmd.isEmpty() ? "" : cmd.get(0).toUpperCase();
                String reply;
                if ("KEYS".equals(name)) {
                    reply = array("thread0_key1", "thread0_key2");
                } else if ("TYPE".equals(name)) {
                    // ★ 错位点：这里回的是 TTL 的整数应答
                    reply = ":-1\r\n";
                } else if ("TTL".equals(name) || "PTTL".equals(name) || "DBSIZE".equals(name)
                        || "EXISTS".equals(name) || "SCARD".equals(name) || "ZCARD".equals(name)
                        || "LLEN".equals(name) || "HLEN".equals(name)) {
                    reply = ":-1\r\n";
                } else if ("PING".equals(name)) {
                    reply = "+PONG\r\n";
                } else if ("SELECT".equals(name) || "AUTH".equals(name)) {
                    reply = "+OK\r\n";
                } else if ("INFO".equals(name)) {
                    reply = "$3\r\nfoo\r\n";
                } else {
                    reply = "+OK\r\n";
                }
                out.write(reply.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        /** 造一个 bulk string 数组应答（长度必须算准，否则对端解码器直接错位） */
        private String array(String... items) {
            StringBuilder sb = new StringBuilder();
            sb.append('*').append(items.length).append("\r\n");
            for (String it : items) {
                byte[] b = it.getBytes(StandardCharsets.UTF_8);
                sb.append('$').append(b.length).append("\r\n").append(it).append("\r\n");
            }
            return sb.toString();
        }

        /** 读一条 RESP 数组命令（*N\r\n$len\r\narg\r\n…） */
        private List<String> readCommand(InputStream in) throws Exception {
            String head = readLine(in);
            if (head == null) {
                return null;
            }
            if (!head.startsWith("*")) {
                return new ArrayList<String>();
            }
            int n = Integer.parseInt(head.substring(1).trim());
            List<String> args = new ArrayList<String>();
            for (int i = 0; i < n; i++) {
                String lenLine = readLine(in);
                if (lenLine == null) {
                    return null;
                }
                int len = Integer.parseInt(lenLine.substring(1).trim());
                byte[] buf = new byte[len];
                int read = 0;
                while (read < len) {
                    int r = in.read(buf, read, len - read);
                    if (r < 0) {
                        return null;
                    }
                    read += r;
                }
                in.read(); // \r
                in.read(); // \n
                args.add(new String(buf, StandardCharsets.UTF_8));
            }
            return args;
        }

        private String readLine(InputStream in) throws Exception {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\r') {
                    in.read();
                    break;
                }
                sb.append((char) c);
            }
            if (c < 0 && sb.length() == 0) {
                return null;
            }
            return sb.toString();
        }
    }
}
