package com.zifang.z.cache.spring.host;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.common.protocol.RespArray;
import com.zifang.z.cache.common.protocol.RespBulkString;
import com.zifang.z.cache.common.protocol.RespError;
import com.zifang.z.cache.common.protocol.RespInteger;
import com.zifang.z.cache.common.protocol.RespSimpleString;
import com.zifang.z.cache.core.server.RedisServer;
import com.zifang.z.cache.core.storage.MemoryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * z-cache（Redis 兼容缓存，自研）管理面 —— <b>只读</b> controller，挂在 z-opc 自己的 {@code /api/cache/**}。
 *
 * <p><b>文件名沿用需求给的 {@code CacheProxyController}，但它不是 proxy，一处转发都没有。</b>
 * 原因（TASK-20260924-010 的核心发现，交接文件 §二 E2）：z-cache 的对外面<b>只有 RESP2 over TCP</b>，
 * 全仓 {@code grep -rn "HttpServer|RestController|RequestMapping|createContext" z-cache --include='*.java'} 零命中，
 * 唯一的 server 面是 {@link RedisServer} 的 Netty {@code ServerBootstrap}（pipeline =
 * RespDecoder → RespEncoder → RedisServerHandler）。上游不讲 HTTP ⇒ 008/009 那套
 * "HttpURLConnection 环回转发 + 逐字节透传"的形状在这里<b>不成立</b>，硬写就是一坨永远转发不到东西的死代码。
 * 所以按需求预告的分支改成："Java 侧用 z-cache 自己的 API 起一个只读 @RestController"。
 *
 * <p><b>两条取数路，同一个 JVM 内，永不拼假数据：</b>
 * <ul>
 *   <li><b>路 A（进程内，权威）</b>：反射取 {@code ZCacheEmbeddedServerConfig.SHARED_SERVER} →
 *       {@link RedisServer#getStore()} → {@link MemoryStore} 的只读方法。
 *       它不开 socket、不碰端口，<b>物理上不可能读到别的进程的数据</b>，所以它是"本 JVM 自己那份"的唯一权威。
 *       用反射而不是 getter，是因为 {@code SHARED_SERVER} 是
 *       {@code private static volatile RedisServer}、{@link ZCacheEmbeddedServerConfig} 只公开了
 *       {@code isServerStarted()}；而本卡禁止修改任何已跟踪文件。反射失败会降级成
 *       {@code serverFieldReflected=false + serverReflectError}，不影响其余部分。</li>
 *   <li><b>路 B（客户端）</b>：注入容器里<b>已存在</b>的 {@link ZCacheClient} bean
 *       （{@code z-cache-spring-boot-starter} 的 {@code zCacheClient}，实测 E6 存在且唯一），
 *       只调 {@code isConnected/getConfig/dbsize/keys/info/ttl} 与白名单内的 {@code sendCommand}。
 *       它代表"业务代码此刻真正看到的那份"。</li>
 * </ul>
 * 两路结果<b>并排渲染并显式比对</b>：{@code authorityDiverges} = "路 A 有效但路 B 的应答者不是本 JVM"。
 * 一旦本 JVM 真 bind 上 6379，两路必须恒等 —— 不等就是"我在编数据"的证据；
 * 现在（B1 未解）它必然为 true，因为 6379 的属主是 5 天前的另一个 z-opc 进程。这一格就是本卡的保真探针。
 *
 * <p><b>三条刻意的安全约束（不可放宽）：</b>
 * <ol>
 *   <li><b>只读，且命令名不接受外部输入。</b> 全部方法都是 {@code @GetMapping}，没有
 *       {@code @PostMapping/@PutMapping/@DeleteMapping}；HTTP 侧只能传 pattern / key / db / limit，
 *       要发哪个 RESP 命令永远是本文件里的常量 {@link #CLIENT_READ_COMMANDS}。
 *       结构上不存在"前端诱导我发 SET/DEL/FLUSHALL"的路径，页面上也不会有清空类按钮。</li>
 *   <li><b>绝不调 {@code SELECT}。</b> {@code zCacheClient} 是业务代码共用的那一个连接，
 *       {@code SELECT n} 会把它<em>永久</em>切到 n 库，之后所有业务的 {@code SET/GET} 都写错库。
 *       所以路 B 的接口一律忽略 {@code db} 参数，并在响应里回 {@code dbIgnoredForClientPath=true} 说明原因；
 *       要按库看，走路 A（{@link MemoryStore} 的 {@code *Db} 系列方法本来就不改任何共享状态）。</li>
 *   <li><b>只挂 {@code /api/**}，不开裸路径</b>（裸路径不在 {@code sso.intercept-paths} 内 = 不设防，
 *       与 007/008/009 同一条理由）。附加的一条 z-cache 特有理由：内嵌 server 默认
 *       {@code bind 0.0.0.0:6379} 且 {@code z.cache.password} 注释掉不启用 ⇒
 *       6379 本身对整个可达网络裸奔，页面绝对不能引导用户去直连它。</li>
 * </ol>
 *
 * <p><b>{@code __instance} 为什么必须存在：</b>{@code ZCompanyMainStarter} 的 {@code static{}} 用
 * {@code catch Throwable} 吞内嵌启动异常（第 98–103 行），而实际更糟 —— bind 异常在
 * {@code ZCacheEmbeddedServerConfig.startServer()} 那个 daemon thread 的 {@code catch (Exception)} 里
 * 就已经被吞了（实测 E5：{@code BindException: Address already in use} 只出现在 stdout），
 * 而 {@code isServerStarted()} 返回的 {@code SERVER_STARTED} 是<b>进 try 之前就 CAS 成 true</b> 的，
 * 含义是"我们试过"，不是"我们 listen 着"。⇒ 没有这个自省端点，"页面空白"一定会被后来的人读成"缓存里没数据"。
 */
@RestController
public class CacheProxyController {

    private static final String PREFIX = "/api/cache";
    /** key 名与 glob pattern 的字符白名单：Redis key 允许挺多字符，但绝不放控制字符和引号/反斜杠 */
    private static final Pattern SAFE_PATTERN = Pattern.compile("^[A-Za-z0-9_.:\\-]*[*?\\[\\]a-zA-Z0-9_.:\\-]*$");
    private static final int TCP_PROBE_MS = 300;
    /** 路 B 的 keys() 是 O(N) 全库扫，必须有硬上限，否则一个页面请求就能把内嵌 server 拖住 */
    private static final int MAX_LIMIT = 1000;
    private static final Charset UTF8_STRICT = StandardCharsets.UTF_8;

    /**
     * 路 B 允许通过 {@code sendCommand} 下发的命令 —— 全部是 Redis 语义里的读命令。
     * 这个集合是常量，<b>不是</b>从请求里读的；写命令（SET/DEL/EXPIRE/FLUSHALL/CONFIG/SAVE…）不在其中。
     */
    private static final Set<String> CLIENT_READ_COMMANDS = unmodifiableSet(
            "PING", "ECHO", "DBSIZE", "GET", "EXISTS", "TTL", "PTTL", "TYPE", "KEYS", "RANDOMKEY",
            "HGET", "HGETALL", "HKEYS", "HVALS", "HMGET", "HLEN", "HEXISTS",
            "LRANGE", "LINDEX", "LLEN",
            "SMEMBERS", "SCARD", "SISMEMBER",
            "ZCARD", "ZSCORE", "ZRANGE", "INFO");

    /**
     * 归一后 type 的<b>唯一</b>合法取值域：{@code MemoryStore.DataType} 的枚举名（<b>大写</b>）。
     *
     * <p>为什么统一成大写（D2）：上游 server 回的是小写 {@code "string"}，而路 A 给的是
     * {@code MemoryStore.DataType.name()} = {@code "STRING"}。两路本来就不等价，
     * 前端还拿大写去比 ⇒ 类型筛选永远 0 命中。现在两路都在后端归一成大写，前端直接比。
     *
     * <p>为什么是这个集合而不是"任何字符串都算类型"（D3）：归一<b>不</b>等于把错位的应答也洗成合法类型。
     * 拿不到这六个之一，就不是类型名 ⇒ 标 {@code desyncSuspect}，不显示成类型。
     */
    private static final Set<String> VALID_TYPES = unmodifiableSet(
            "STRING", "HASH", "LIST", "SET", "ZSET", "NONE");

    private final ObjectProvider<ZCacheClient> clientProvider;
    private final ObjectProvider<ZCacheEmbeddedServerConfig> embeddedProvider;

    /** server 侧端口：Spring Environment 视角（system property + application.properties 合并后） */
    @Value("${z.cache.server.port:6379}")
    private int serverPortFromSpringEnv;
    /** client 侧目标：starter 的 ZCacheProperties 用的就是这两行，实测 application.properties:220-221 */
    @Value("${z.cache.host:localhost}")
    private String clientHost;
    @Value("${z.cache.port:6379}")
    private int clientPort;
    @Value("${z.cache.database:0}")
    private int clientDatabase;

    public CacheProxyController(ObjectProvider<ZCacheClient> clientProvider,
                                ObjectProvider<ZCacheEmbeddedServerConfig> embeddedProvider) {
        this.clientProvider = clientProvider;
        this.embeddedProvider = embeddedProvider;
    }

    // ------------------------------------------------------------------ 自省

    /**
     * 自省接口：这一路到底连的是谁。返回三态，缺任何一格都会导致后来的人误判。
     */
    @GetMapping(PREFIX + "/__instance")
    public void instance(HttpServletResponse resp) throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jvm", ManagementFactory.getRuntimeMXBean().getName());
        out.put("surface", "RESP2/TCP");
        out.put("httpSurfaceOfZCache", "none —— z-cache 没有任何 HTTP 管理面（TASK-20260924-010 E2）");
        out.put("readApiImplementedBy", "CacheProxyController（z-opc 自己的只读 controller，非转发代理）");

        // ---- 路 A：内嵌 server
        boolean lifecycleBean = embeddedProvider.getIfAvailable() != null;
        out.put("lifecycleBean", Boolean.valueOf(lifecycleBean));
        // starter 自己的声明，**不可信**：语义是"进过 startServer()"，不是"bind 成了"
        out.put("isServerStartedClaimedByStarter",
                Boolean.valueOf(lifecycleBean && ZCacheEmbeddedServerConfig.isServerStarted()));

        RedisServer server = resolveEmbeddedServer();
        MemoryStore store = server == null ? null : server.getStore();
        boolean running = server != null && server.isRunning();
        int boundPort = running && server != null ? server.getPort() : 0;
        out.put("serverFieldReflected", Boolean.valueOf(server != null));
        out.put("serverReflectError", reflectError);
        out.put("embeddedRunning", Boolean.valueOf(running));
        out.put("boundPort", Integer.valueOf(boundPort));
        out.put("storeAvailable", Boolean.valueOf(store != null));
        // D1：storeAvailable 与"路 A 503"曾经互相打脸 —— storeAvailable 取的是 store 对象在不在
        // （不 gate running），而 storeOrNull() 额外有一道 isRunning() 闸门。
        // ⇒ 这两个字段同页并列，谁看得懂谁是权威一目了然：能不能读 = store!=null && running。
        out.put("storeAvailableNote", "store 对象在不在（**不** gate isRunning）"
                + " ⇒ storeAvailable=true 与「路 A 503」可以并存，那不是矛盾，是两个口径");
        out.put("embeddedReadPathUsable", Boolean.valueOf(store != null && running));
        out.put("embeddedReadPathUsableNote", "路 A 真正能不能读 = store!=null && isRunning()；"
                + "false ⇒ /keys?source=embedded 与 /hotkeys 必然 503，hint 里写的是真因（见 D1）");

        // ---- 端口配置：两条解析链的分歧是 z-cache 特有的坑（E8）
        String staticBlockPort = System.getProperty("z.cache.server.port");
        out.put("serverPortFromSystemProperty", staticBlockPort == null ? "(未设, static 块用默认 6379)" : staticBlockPort);
        out.put("serverPortFromSpringEnv", Integer.valueOf(serverPortFromSpringEnv));
        out.put("serverPortResolutionDiverges",
                Boolean.valueOf(staticBlockPort != null && !staticBlockPort.equals(String.valueOf(serverPortFromSpringEnv))));
        out.put("portResolutionNote",
                "ZCompanyMainStarter 的 static 块只读 System.getProperty(\"z.cache.server.port\")，"
                        + "application.properties 的 z.cache.server.port 对内嵌 server 是纯装饰（只有 -D 能挪端口）");

        int effectiveConfigured = serverPortFromSpringEnv;
        out.put("configuredPort", Integer.valueOf(effectiveConfigured));
        out.put("acceptingNow", Boolean.valueOf(tcpConnectable(effectiveConfigured)));
        out.put("embeddedPortOwnedByThisJvm", Boolean.valueOf(running));
        // 本 JVM 没 bind 成 + 配置端口 TCP 可连 ⇒ 应答者必然不是本 JVM
        out.put("foreignListenerSuspected", Boolean.valueOf(!running && tcpConnectable(effectiveConfigured)));

        // ---- 路 B：client
        ZCacheClient client = clientProvider.getIfAvailable();
        out.put("clientBeanPresent", Boolean.valueOf(client != null));
        boolean connected = false;
        if (client != null) {
            try {
                connected = client.isConnected();
            } catch (Exception e) {
                out.put("clientProbeError", e.getClass().getName() + ": " + e.getMessage());
            }
            try {
                if (client.getConfig() != null) {
                    out.put("clientTargetHost", client.getConfig().getHost());
                    out.put("clientTargetPort", Integer.valueOf(client.getConfig().getPort()));
                    out.put("clientTargetDatabase", Integer.valueOf(client.getConfig().getDatabase()));
                    out.put("clientTargetHasPassword",
                            Boolean.valueOf(client.getConfig().getPassword() != null
                                    && !client.getConfig().getPassword().isEmpty()));
                }
            } catch (Exception e) {
                out.put("clientConfigProbeError", e.getClass().getName() + ": " + e.getMessage());
            }
        }
        out.put("clientTransportConnected", Boolean.valueOf(connected));
        out.put("clientTransportConnectedNote",
                "取自 ZCacheClient.isConnected() → ZCacheConnection 的 ConnectionState AtomicBoolean "
                        + "(源码 327-329 行)，【不发命令】。⇒ true 只证明握手时连上过，不证明此刻还在应答；"
                        + "要判活看 /overview 的 client.dbsize 能不能回。");
        out.put("authorityOfClient", authorityOfClient(running, connected));
        out.put("invariant", "内嵌 bind 成功之后，路 A(embedded) 与 路 B(client) 的 dbsize 必须恒等；"
                + "不等即证明本 controller 在编数据。当前 bind 未成功 ⇒ 两路必然分歧，这是预期的 B1 现象。");
        out.put("blockedReason", running ? null
                : "B1: 本 JVM 的 RedisServer 没有 bind 上 " + portText()
                        + "（实测 java.net.BindException: Address already in use，见交接文件 §二 E5）。"
                        + "解法见 §十。");

        // ---- 只读面自证：本 controller 能提供什么、绝不能提供什么
        out.put("readEndpoints", Arrays.asList(
                "/api/cache/__instance", "/api/cache/overview", "/api/cache/keys",
                "/api/cache/key", "/api/cache/info", "/api/cache/hotkeys", "/api/cache/command-catalog"));
        out.put("writeEndpoints", Collections.emptyList());
        out.put("clientAllowedCommands", new ArrayList<String>(CLIENT_READ_COMMANDS));
        out.put("selectNeverIssued", Boolean.TRUE);

        writeJson(resp, 200, out);
    }

    // ------------------------------------------------------------------ 总览

    /** 双路对账总览：路 A 与路 B 并排 + 分歧位。页面首屏就是它。 */
    @GetMapping(PREFIX + "/overview")
    public void overview(HttpServletResponse resp) throws Exception {
        RedisServer server = resolveEmbeddedServer();
        MemoryStore store = server == null ? null : server.getStore();
        boolean running = server != null && server.isRunning();

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jvm", ManagementFactory.getRuntimeMXBean().getName());

        Map<String, Object> emb = new LinkedHashMap<String, Object>();
        emb.put("serverReflected", Boolean.valueOf(server != null));
        emb.put("reflectError", reflectError);
        emb.put("running", Boolean.valueOf(running));
        emb.put("port", Integer.valueOf(server == null ? 0 : server.getPort()));
        emb.put("storeAvailable", Boolean.valueOf(store != null));
        emb.put("dbsizeTotal", null);
        if (store != null) {
            emb.put("dbsizeTotal", Long.valueOf(store.dbsize()));
            emb.put("dbCount", Integer.valueOf(store.getDbCount()));
            List<Map<String, Object>> perDb = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < store.getDbCount(); i++) {
                Map<String, Object> d = new LinkedHashMap<String, Object>();
                d.put("db", Integer.valueOf(i));
                d.put("keys", Long.valueOf(store.dbsizeDb(i)));
                perDb.add(d);
            }
            emb.put("perDb", perDb);
            emb.put("hits", Long.valueOf(store.getHits()));
            emb.put("misses", Long.valueOf(store.getMisses()));
            emb.put("hitRate", Double.valueOf(hitRate(store)));
            emb.put("evictions", Long.valueOf(store.getEvictions()));
            emb.put("totalCommands", Long.valueOf(store.getTotalCommands()));
            emb.put("totalConnections", Long.valueOf(store.getTotalConnections()));
            emb.put("connectedClients", Long.valueOf(store.getConnectedClients()));
            emb.put("maxEntries", Integer.valueOf(store.getMaxEntries()));
            // ⚠ store 是在 startServer() 里 new 出来的，bind 失败也照样有 startTime
            emb.put("storeUptimeSeconds", Long.valueOf((System.currentTimeMillis() - store.getStartTime()) / 1000L));
            emb.put("storeUptimeNote", "自 MemoryStore 构造起算，【不是】自 bind 成功起算；bind 失败时它照样在涨");
        } else {
            // D1 第三处受害者：这里也把"没 bind"说成"反射拿不到"。
            // 改成与 /keys、/hotkeys 同一句真因（分三支），三个端点的口径从此一致。
            emb.put("unavailableReason", embeddedUnavailableHint());
            emb.put("embeddedReadPathUsable", Boolean.FALSE);
        }
        out.put("embedded", emb);

        Map<String, Object> cli = new LinkedHashMap<String, Object>();
        ZCacheClient client = clientProvider.getIfAvailable();
        cli.put("present", Boolean.valueOf(client != null));
        if (client != null) {
            try {
                cli.put("transportConnected", Boolean.valueOf(client.isConnected()));
            } catch (Exception e) {
                cli.put("transportConnected", Boolean.FALSE);
                cli.put("probeError", e.getClass().getName() + ": " + e.getMessage());
            }
            try {
                cli.put("dbsize", client.dbsize());
            } catch (Exception e) {
                cli.put("dbsize", null);
                cli.put("dbsizeError", e.getClass().getName() + ": " + e.getMessage());
            }
            cli.put("target", clientTarget(client));
        }
        boolean clientOk = client != null && Boolean.TRUE.equals(cli.get("transportConnected"));
        cli.put("authority", authorityOfClient(running, clientOk));
        out.put("client", cli);

        boolean embValid = store != null && running;
        Object embSize = emb.get("dbsizeTotal");
        Object cliSize = cli.get("dbsize");
        out.put("authorityDiverges", Boolean.valueOf(embValid && cliSize != null && !cliSize.equals(embSize)));
        out.put("foreignListenerSuspected",
                Boolean.valueOf(!running && tcpConnectable(serverPortFromSpringEnv)));
        writeJson(resp, 200, out);
    }

    // ------------------------------------------------------------------ key 浏览

    /**
     * key 列表。{@code source} 只接受 {@code embedded|client|auto}（默认 auto：路 A 可用就走 A）。
     * 路 B 会忽略 {@code db} —— 见类注释第 2 条约束。
     */
    @GetMapping(PREFIX + "/keys")
    public void keys(@RequestParam(value = "pattern", required = false, defaultValue = "*") String pattern,
                     @RequestParam(value = "db", required = false, defaultValue = "0") int db,
                     @RequestParam(value = "limit", required = false, defaultValue = "200") int limit,
                     @RequestParam(value = "source", required = false, defaultValue = "auto") String source,
                     HttpServletResponse resp) throws Exception {
        if (!SAFE_PATTERN.matcher(pattern).matches()) {
            writeJson(resp, 400, err("bad pattern", "只允许字母/数字/_ . - : 以及 glob 的 * ? []"));
            return;
        }
        int cap = limit <= 0 ? 200 : Math.min(limit, MAX_LIMIT);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("pattern", pattern);
        out.put("limit", Integer.valueOf(cap));

        MemoryStore store = storeOrNull();
        boolean useEmbedded = !"client".equals(source) && store != null;
        if (!"client".equals(source) && "embedded".equals(source) && store == null) {
            writeJson(resp, 503, embeddedUnavailable("in-process MemoryStore unavailable"));
            return;
        }
        out.put("sourceUsed", useEmbedded ? "embedded" : "client");

        if (useEmbedded) {
            if (db < 0 || db >= store.getDbCount()) {
                writeJson(resp, 400, err("bad db", "0.." + (store.getDbCount() - 1)));
                return;
            }
            List<String> all = store.keysDb(db, pattern);
            out.put("db", Integer.valueOf(db));
            out.put("matchedTotal", Integer.valueOf(all.size()));
            out.put("truncated", Boolean.valueOf(all.size() > cap));
            out.put("keys", describeEmbedded(all.size() > cap ? all.subList(0, cap) : all, store, db));
            out.put("authority", "THIS_JVM_IN_PROCESS");
            writeJson(resp, 200, out);
            return;
        }

        ZCacheClient client = clientProvider.getIfAvailable();
        if (client == null) {
            out.put("sourceUsed", "none");
            out.put("keys", Collections.emptyList());
            out.put("reason", "路 A 不可用（内嵌 store 拿不到）且 zCacheClient bean 不存在 ⇒ 没有任何数据源");
            writeJson(resp, 200, out);
            return;
        }
        out.put("db", Integer.valueOf(db));
        out.put("dbIgnoredForClientPath", Boolean.TRUE);
        out.put("dbIgnoreReason", "SELECT 会永久改掉业务共用连接的活动库，所以路 B 不切库；"
                + "要按库浏览请用 source=embedded");
        out.put("authority", authorityOfClient(serverRunning(), isConnected(client)));
        try {
            List<String> ks = client.keys(pattern);
            List<String> page = ks.size() > cap ? ks.subList(0, cap) : ks;
            out.put("matchedTotal", Integer.valueOf(ks.size()));
            out.put("truncated", Boolean.valueOf(ks.size() > cap));
            List<Map<String, Object>> rows = describeClient(page, client);
            out.put("keys", rows);
            // D3：本次采样的错位数与总数**必须**在响应体顶层给出 ——
            // 页面要在表头黄标里写明"路 B 本次有 N/M 行的应答对不上，这些行的类型/TTL 不参与展示"，
            // 否则读者只能看到一堆空格子，不知道是"没数据"还是"数据不可信"。
            int desync = 0;
            for (Map<String, Object> r : rows) {
                if (Boolean.TRUE.equals(r.get("desyncSuspect"))) {
                    desync++;
                }
            }
            out.put("desyncRows", Integer.valueOf(desync));
            out.put("totalRows", Integer.valueOf(rows.size()));
            out.put("desyncRate", Double.valueOf(rows.isEmpty() ? 0d
                    : (double) desync / (double) rows.size()));
            out.put("desyncNote",
                    "路 B 的每行要发命令（TYPE/TTL），走的是**业务共用**的那条 RESP 连接；"
                            + "本 JVM 从没 bind 过 6379（见 blockedReason），对面是谁也不确定。"
                            + "⇒ 本次 " + rows.size() + " 行里有 " + desync
                            + " 行的应答与所发命令对不上（疑似串台），这些行的 type/TTL **不参与展示**，"
                            + "原始应答保留在该行的 rawReply 里，判据见该行的 desyncReason。");
            out.put("typeVocabulary", "大写枚举名，与路 A 同一取值域：" + String.join("/", VALID_TYPES));
        } catch (Exception e) {
            out.put("keys", Collections.emptyList());
            out.put("error", e.getClass().getName() + ": " + e.getMessage());
            out.put("errorHint", "路 B 走 RESP 到 client 配的那个 host:port；报错说明那台 server 没在应答"
                    + "（不代表本 JVM 的缓存是空的）");
        }
        writeJson(resp, 200, out);
    }

    /** 单个 key 的详情：类型 / TTL / 版本 / 值（按类型取，全部走白名单读命令）。 */
    @GetMapping(PREFIX + "/key")
    public void key(@RequestParam("key") String key,
                    @RequestParam(value = "db", required = false, defaultValue = "0") int db,
                    @RequestParam(value = "maxElements", required = false, defaultValue = "50") int maxElements,
                    @RequestParam(value = "source", required = false, defaultValue = "auto") String source,
                    HttpServletResponse resp) throws Exception {
        if (key == null || key.isEmpty() || !SAFE_PATTERN.matcher(key).matches()) {
            writeJson(resp, 400, err("bad key", "字符白名单不通过"));
            return;
        }
        int cap = maxElements <= 0 ? 50 : Math.min(maxElements, MAX_LIMIT);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("key", key);
        MemoryStore store = storeOrNull();
        boolean useEmbedded = !"client".equals(source) && store != null;
        out.put("sourceUsed", useEmbedded ? "embedded" : "client");

        if (useEmbedded) {
            if (db < 0 || db >= store.getDbCount()) {
                writeJson(resp, 400, err("bad db", "0.." + (store.getDbCount() - 1)));
                return;
            }
            MemoryStore.DataType t = store.getKeyType(key, db);
            out.put("db", Integer.valueOf(db));
            out.put("type", t == null ? "NONE" : t.name());
            out.put("exists", Boolean.valueOf(store.existsDb(db, key)));
            out.put("ttlSeconds", Long.valueOf(store.ttlDb(db, key)));
            out.put("pttlMillis", Long.valueOf(store.pttlDb(db, key)));
            out.put("keyVersion", Long.valueOf(store.getKeyVersion(db, key)));
            out.put("authority", "THIS_JVM_IN_PROCESS");
            Map<String, Object> val = new LinkedHashMap<String, Object>();
            if (t == MemoryStore.DataType.STRING) {
                byte[] raw = store.getDb(db, key);
                val.putAll(decode(raw));
            } else if (t == MemoryStore.DataType.HASH) {
                Map<String, byte[]> h = store.getHashStore(db).hgetall(key);
                val.put("fieldCount", Integer.valueOf(h == null ? 0 : h.size()));
                val.put("fields", decodeMap(h, cap));
            } else if (t == MemoryStore.DataType.LIST) {
                long len = store.getListStore(db).llen(key);
                val.put("length", Long.valueOf(len));
                val.put("items", decodeList(store.getListStore(db).lrange(key, 0, (int) Math.min(len, cap) - 1), cap));
            } else if (t == MemoryStore.DataType.SET) {
                val.put("cardinality", Long.valueOf(store.getSetStore(db).scard(key)));
                val.put("members", decodeList(store.getSetStore(db).smembers(key), cap));
            } else if (t == MemoryStore.DataType.ZSET) {
                val.put("cardinality", Long.valueOf(store.getSortedSetStore(db).zcard(key)));
                val.put("membersWithScores", decodeList(
                        store.getSortedSetStore(db).zrange(key, 0, cap - 1, true), cap));
            }
            out.put("value", val);
            writeJson(resp, 200, out);
            return;
        }

        ZCacheClient client = clientProvider.getIfAvailable();
        if (client == null) {
            out.put("sourceUsed", "none");
            out.put("reason", "路 A 不可用且 zCacheClient bean 不存在");
            writeJson(resp, 200, out);
            return;
        }
        out.put("db", Integer.valueOf(db));
        out.put("dbIgnoredForClientPath", Boolean.TRUE);
        out.put("authority", authorityOfClient(serverRunning(), isConnected(client)));
        try {
            // D2：type 归一成大写枚举名（与路 A 同一取值域）；D3：拿不到就 null + 标记，不假装
            TypeReading tr = normalizeTypeReading(safeSend(client, "TYPE", key));
            out.put("type", tr.desync || tr.failed ? null : tr.value);
            out.put("typeNote", tr.note);
            out.put("typeSource", tr.source);
            out.put("desyncSuspect", Boolean.valueOf(tr.desync || tr.failed));
            if (tr.desync || tr.failed) {
                out.put("rawReply", tr.raw);
                out.put("desyncReason", tr.note);
            }
            try {
                out.put("ttlSeconds", client.ttl(key));
            } catch (Exception e) {
                out.put("ttlSeconds", null);
                out.put("ttlSecondsError", e.getClass().getSimpleName() + ": " + e.getMessage());
                out.put("desyncSuspect", Boolean.TRUE);
            }
            try {
                out.put("pttlMillis", client.pttl(key));
            } catch (Exception e) {
                out.put("pttlMillis", null);
                out.put("pttlMillisError", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            String t = tr.desync || tr.failed ? null : tr.value;
            Map<String, Object> val = new LinkedHashMap<String, Object>();
            if (t == null) {
                // 类型不可信 ⇒ 不按类型去取值（按错的类型发命令只会再多一次串台机会）
                val.put("note", "类型读数不可信 ⇒ 不按类型取值（见 desyncReason / rawReply）");
            } else if ("STRING".equals(t)) {
                val.put("value", safeSend(client, "GET", key));
            } else if ("HASH".equals(t)) {
                val.put("entries", safeSend(client, "HGETALL", key));
            } else if ("LIST".equals(t)) {
                val.put("length", safeSend(client, "LLEN", key));
                val.put("items", safeSend(client, "LRANGE", key, "0", String.valueOf(cap - 1)));
            } else if ("SET".equals(t)) {
                val.put("cardinality", safeSend(client, "SCARD", key));
                val.put("members", safeSend(client, "SMEMBERS", key));
            } else if ("ZSET".equals(t)) {
                val.put("cardinality", safeSend(client, "ZCARD", key));
                val.put("membersWithScores", safeSend(client, "ZRANGE", key, "0", String.valueOf(cap - 1), "WITHSCORES"));
            }
            out.put("value", val);
        } catch (Exception e) {
            out.put("error", e.getClass().getName() + ": " + e.getMessage());
        }
        writeJson(resp, 200, out);
    }

    // ------------------------------------------------------------------ 统计

    /** INFO（路 B 原文 + 分节解析）与进程内计数（路 A）并排；把硬编码字段标出来防误读。 */
    @GetMapping(PREFIX + "/info")
    public void info(HttpServletResponse resp) throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        ZCacheClient client = clientProvider.getIfAvailable();
        boolean connected = isConnected(client);
        out.put("authorityOfClient", authorityOfClient(serverRunning(), connected));
        if (client != null && connected) {
            try {
                String raw = client.info();
                out.put("raw", raw);
                out.put("sections", parseInfo(raw));
            } catch (Exception e) {
                out.put("clientError", e.getClass().getName() + ": " + e.getMessage());
            }
        } else {
            out.put("clientPresent", Boolean.valueOf(client != null));
            out.put("clientNote", "zCacheClient 未连上 ⇒ INFO 无从取得（INFO 只能通过 RESP 面取，路 A 没有 INFO）");
        }
        out.put("hardcodedInUpstream", Arrays.asList(
                "z-cache_version=1.0.2（写死在 CommandHandler.handleInfo 第 946 行，与 jar 实际版本 1.3.1 无关）",
                "tcp_port=6379（写死在第 951 行，【不能】用它判断连的是哪个端口）"));
        out.put("responderVsOwnBuild", responderVsOwnBuild(out.get("sections")));
        MemoryStore store = storeOrNull();
        if (store != null) {
            Map<String, Object> emb = new LinkedHashMap<String, Object>();
            emb.put("dbsizeTotal", Long.valueOf(store.dbsize()));
            emb.put("hits", Long.valueOf(store.getHits()));
            emb.put("misses", Long.valueOf(store.getMisses()));
            emb.put("hitRate", Double.valueOf(hitRate(store)));
            emb.put("evictions", Long.valueOf(store.getEvictions()));
            emb.put("totalCommands", Long.valueOf(store.getTotalCommands()));
            emb.put("connectedClients", Long.valueOf(store.getConnectedClients()));
            emb.put("maxEntries", Integer.valueOf(store.getMaxEntries()));
            Runtime rt = Runtime.getRuntime();
            emb.put("jvmHeapUsedBytes", Long.valueOf(rt.totalMemory() - rt.freeMemory()));
            emb.put("jvmHeapMaxBytes", Long.valueOf(rt.maxMemory()));
            emb.put("jvmAvailableProcessors", Integer.valueOf(rt.availableProcessors()));
            out.put("embedded", emb);
        }
        writeJson(resp, 200, out);
    }

    /** 热 key 视图：只有路 A 有（MemoryStore.ValueWrapper 自带 accessCount / lastAccessTime / lfuCounter）。 */
    @GetMapping(PREFIX + "/hotkeys")
    public void hotkeys(@RequestParam(value = "db", required = false, defaultValue = "0") int db,
                        @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
                        HttpServletResponse resp) throws Exception {
        MemoryStore store = storeOrNull();
        if (store == null) {
            // D1：这一处与 /keys?source=embedded 用的是**同一句撒谎的表达式**
            // （reflectError == null ? "SHARED_SERVER 反射为空" : reflectError），
            // 所以 2026-09-26 01:44 那轮普查里 /cache/stats 的 ERROR 也是它背的锅。
            writeJson(resp, 503, embeddedUnavailable("in-process MemoryStore unavailable"));
            return;
        }
        if (db < 0 || db >= store.getDbCount()) {
            writeJson(resp, 400, err("bad db", "0.." + (store.getDbCount() - 1)));
            return;
        }
        int cap = limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, MemoryStore.ValueWrapper> stringStore = store.getStringStore(db);
        synchronized (stringStore) {
            for (Map.Entry<String, MemoryStore.ValueWrapper> e : stringStore.entrySet()) {
                MemoryStore.ValueWrapper w = e.getValue();
                if (w == null) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("key", e.getKey());
                r.put("accessCount", Long.valueOf(w.accessCount));
                r.put("lastAccessTime", Long.valueOf(w.lastAccessTime));
                r.put("lfuCounter", Integer.valueOf(w.lfuCounter));
                // 过期时刻自 1.3.5 起不在 ValueWrapper 上，而在 MemoryStore 的每库时刻表里
                // (ValueWrapper.isExpired()/hasExpiration() 已删，上游注释指明同口径的替代就是这两问)
                r.put("expired", Boolean.valueOf(store.isExpiredDb(db, e.getKey())));
                r.put("hasExpiration", Boolean.valueOf(store.hasExpirationDb(db, e.getKey())));
                r.put("ttlSeconds", Long.valueOf(store.ttlDb(db, e.getKey())));
                r.put("byteLength", Integer.valueOf(w.data == null ? 0 : w.data.length));
                rows.add(r);
            }
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                long x = ((Long) a.get("accessCount")).longValue();
                long y = ((Long) b.get("accessCount")).longValue();
                return x < y ? 1 : (x > y ? -1 : 0);
            }
        });
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("db", Integer.valueOf(db));
        out.put("stringKeyTotal", Integer.valueOf(rows.size()));
        out.put("truncated", Boolean.valueOf(rows.size() > cap));
        out.put("rows", rows.size() > cap ? rows.subList(0, cap) : rows);
        out.put("authority", "THIS_JVM_IN_PROCESS");
        out.put("scopeNote", "只覆盖 string 类型的 key（ValueWrapper 挂在 stringStores 上）；"
                + "hash/list/set/zset 的访问计数在各自 store 里，本卡不做");
        out.put("sourceNote", "accessCount 是 MemoryStore 自带的 LFU 埋点，不是本 controller 统计出来的");
        writeJson(resp, 200, out);
    }

    /**
     * 命令目录 —— <b>静态常量</b>，来源是 z-cache 源码行号，页面上明确标注"代码常量，非实测应答"。
     * 它存在的意义是把"哪些命令本 controller 永远不会发"变成机器可读的事实。
     */
    @GetMapping(PREFIX + "/command-catalog")
    public void commandCatalog(HttpServletResponse resp) throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("provenance", "代码常量（抄自 z-cache-core 1.3.1 CommandHandler 的 switch 行号），"
                + "【不是】向 server 发 COMMAND DOCS 取得的");
        out.put("serverBindAddress", "0.0.0.0（RedisServer.DEFAULT 构造 + application.properties:224 z.cache.server.host）");
        out.put("readOnlyCommandsAllowedByWhitelist", Arrays.asList(
                row("PING", "Redis 基本探活", true), row("ECHO", "", true), row("DBSIZE", "当前库 key 数", true),
                row("GET", "", true), row("EXISTS", "", true), row("TTL", "", true), row("PTTL", "", true),
                row("TYPE", "", true), row("KEYS", "glob 全库匹配（O(N)，有 limit）", true),
                row("RANDOMKEY", "", true), row("HGET", "", true), row("HGETALL", "", true),
                row("HKEYS", "", true), row("HVALS", "", true), row("HMGET", "", true), row("HLEN", "", true),
                row("HEXISTS", "", true), row("LRANGE", "", true), row("LINDEX", "", true), row("LLEN", "", true),
                row("SMEMBERS", "", true), row("SCARD", "", true), row("SISMEMBER", "", true),
                row("ZCARD", "", true), row("ZSCORE", "", true), row("ZRANGE", "带 WITHSCORES", true),
                row("INFO", "server 自报统计（注意 §〇 第 3 条 / E10 的硬编码字段）", true)));
        out.put("whitelistSemantics",
                "这是【上限集合】，不是「都会发」清单：CLIENT_READ_COMMANDS 只在 safeSend() 的入口做校验用。"
                        + "下面 commandsActuallyIssued 才是本 controller 目前真正下发的命令。");
        out.put("commandsActuallyIssued", Arrays.asList(
                row("DBSIZE", "路 B 对账：client.dbsize()（/overview, /__instance 之外唯一取数处）", true),
                row("KEYS", "路 B 列 key：client.keys(pattern)（/keys, source=client）", true),
                row("INFO", "路 B 统计：client.info()（/info）", true),
                row("TTL", "路 B：client.ttl(k)（/keys, /key 的 ttlSeconds）", true),
                row("PTTL", "路 B：client.pttl(k)（/key 的 pttlMillis）", true),
                row("TYPE", "路 B：safeSend(\"TYPE\")（/keys, /key）", true),
                row("GET", "路 B：safeSend(\"GET\")（/key, string）", true),
                row("HGETALL", "路 B：safeSend(\"HGETALL\")（/key, hash）", true),
                row("LRANGE", "路 B：safeSend(\"LRANGE\")（/key, list）", true),
                row("LLEN", "路 B：safeSend(\"LLEN\")（/key, list）", true),
                row("SMEMBERS", "路 B：safeSend(\"SMEMBERS\")（/key, set）", true),
                row("SCARD", "路 B：safeSend(\"SCARD\")（/key, set）", true),
                row("ZRANGE", "路 B：safeSend(\"ZRANGE\", ..., WITHSCORES)（/key, zset）", true),
                row("ZCARD", "路 B：safeSend(\"ZCARD\")（/key, zset）", true)));
        out.put("notCountedAsIssued",
                "PING/ECHO 没被用来探活：client.isConnected() 读的是 ZCacheConnection 里的 ConnectionState "
                        + "AtomicBoolean（源码 ZCacheConnection.java:327-329），一次网络往返都不做，"
                        + "所以 clientTransportConnected=true 只代表上次握手成功，不代表此刻还在应答。");
        out.put("neverIssuedDespiteBeingSupportedByServer", Arrays.asList(
                row("SET", "写", false), row("DEL", "写", false), row("EXPIRE", "写", false),
                row("PERSIST", "写", false), row("FLUSHALL", "全库清空", false),
                row("FLUSHDB", "单库清空", false), row("CONFIG", "改配置", false),
                row("SAVE", "落盘", false), row("SELECT", "切库会改掉【业务共用连接】的活动库", false)));
        out.put("neverIssuedReason", "本卡只做读面（写面单独立卡）；HTTP 侧无法指定命令名，见 CLIENT_READ_COMMANDS");
        writeJson(resp, 200, out);
    }

    /** 兜底：/api/cache 下未注册的子路径。回 404 + 可读端点清单，绝不回 HTML（免得 SPA fallback 把它变成 200 页面）。 */
    @RequestMapping(PREFIX + "/**")
    public void unknown(HttpServletResponse resp) throws Exception {
        Map<String, Object> m = err("unknown read endpoint",
                "GET 支持：__instance / overview / keys / key / info / hotkeys / command-catalog");
        m.put("supportedMethods", Collections.singletonList("GET"));
        writeJson(resp, 404, m);
    }

    // ------------------------------------------------------------------ 内部

    private volatile RedisServer resolvedServer;
    private volatile String reflectError;
    private volatile boolean reflected;

    /**
     * 反射取 {@code ZCacheEmbeddedServerConfig.SHARED_SERVER}。
     * 该类只有 {@code public static boolean isServerStarted()}，没有 server 实例的 getter，
     * 而本卡禁止修改已跟踪文件 ⇒ 只能反射，且结果缓存一次。
     */
    private RedisServer resolveEmbeddedServer() {
        if (reflected) {
            return resolvedServer;
        }
        synchronized (this) {
            if (reflected) {
                return resolvedServer;
            }
            try {
                Field f = ZCacheEmbeddedServerConfig.class.getDeclaredField("SHARED_SERVER");
                f.setAccessible(true);
                Object v = f.get(null);
                resolvedServer = v instanceof RedisServer ? (RedisServer) v : null;
                if (resolvedServer == null) {
                    reflectError = "SHARED_SERVER 字段为 " + (v == null ? "null（static 块没跑到 / startServer 提前返回）" : v.getClass().getName());
                }
            } catch (Throwable t) {
                // 不抛：JDK 版本 / 字段改名 / SecurityManager 都会到这，降级成"路 A 不可用"并自证
                reflectError = t.getClass().getName() + ": " + t.getMessage();
                resolvedServer = null;
            }
            reflected = true;
        }
        return resolvedServer;
    }

    private MemoryStore storeOrNull() {
        RedisServer s = resolveEmbeddedServer();
        if (s == null || !s.isRunning()) {
            // isRunning()==false 时 store 一定没被任何 client 写过（没人连得上），
            // 显示它是"0"只会让人以为"缓存空了"，所以一律按不可用处理
            return null;
        }
        return s.getStore();
    }

    // ------------------------------------------------------------------ D1：路 A 不可用的真因

    /**
     * 路 A（进程内）当前处于什么状态 —— <b>三态 + 口径</b>，不许再压缩成一句"反射为空"。
     *
     * <p>旧实现把三件不同的事混成一句：{@code reflectError == null ? "SHARED_SERVER 反射为空" : reflectError}。
     * 反射**成功**但 server 没 bind（isRunning()=false，B1）时，reflectError 必为空
     * ⇒ 那句"反射为空"**必然**被打印出来，而它是错的（反射是成功的）。
     *
     * @return 键：serverReflected / reflectError / running / storeAvailable / embeddedReadPathUsable / blockedReason
     */
    private Map<String, Object> embeddedState() {
        RedisServer s = resolveEmbeddedServer();
        MemoryStore st = s == null ? null : s.getStore();
        boolean running = s != null && s.isRunning();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("serverReflected", Boolean.valueOf(s != null));
        m.put("reflectError", reflectError);
        m.put("running", Boolean.valueOf(running));
        m.put("storeAvailable", Boolean.valueOf(st != null));
        m.put("embeddedReadPathUsable", Boolean.valueOf(st != null && running));
        m.put("blockedReason", running ? null
                : "B1: 本 JVM 的 RedisServer 没有 bind 上 " + portText()
                        + "（实测 java.net.BindException: Address already in use，见交接文件 §二 E5）。"
                        + "解法见 §十。");
        return m;
    }

    /** 端口的文字形态：没注入（0）时不能写"没有 bind 上 0"这种看不懂的话 */
    private String portText() {
        return serverPortFromSpringEnv > 0 ? String.valueOf(serverPortFromSpringEnv)
                : "(端口未注入/未知，@Value 未生效)";
    }

    /**
     * 路 A 读不到数的**真因**（一句话，含 B1 点名）。
     * 分支与 {@link #embeddedState()} 的字段一一对应，不猜、不兜底成固定文案。
     */
    private String embeddedUnavailableHint() {
        Map<String, Object> st = embeddedState();
        if (st.get("reflectError") != null) {
            return "内嵌 server 反射失败：" + st.get("reflectError")
                    + " ⇒ 这一路在本进程里取不到数（不是「缓存是空的」）";
        }
        if (!Boolean.TRUE.equals(st.get("serverReflected"))) {
            return "内嵌 server 反射不到（SHARED_SERVER 为 null：static 块没跑到 / startServer 提前返回）"
                    + " ⇒ 这一路在本进程里取不到数（不是「缓存是空的」）";
        }
        if (!Boolean.TRUE.equals(st.get("running"))) {
            return "内嵌 server **反射成功但没有 bind 上端口**（isRunning()=false）"
                    + " ⇒ 真因是 B1：本 JVM 的 RedisServer 起不来（实测 java.net.BindException: Address already in use，"
                    + "6379 的属主是别的进程），不是反射问题。"
                    + " 路 A 在本进程里用不了 ⇒ 请改看路 B，并注意路 B 读到的也不是本 JVM 的缓存（authority=EXTERNAL_PROCESS）。";
        }
        if (!Boolean.TRUE.equals(st.get("storeAvailable"))) {
            return "内嵌 server 在跑但 getStore() 返回 null（store 没建起来）⇒ 这一路取不到数";
        }
        return "内嵌读路可用（store!=null && isRunning()=true）—— 本分支不该被走到，若看到请连同上下文报给工具作者";
    }

    /** 路 A 不可用时的 503 响应体：带真因 + 状态字段（不再只有一句错归因） */
    private Map<String, Object> embeddedUnavailable(String message) {
        Map<String, Object> m = err(message, embeddedUnavailableHint());
        Map<String, Object> st = embeddedState();
        m.put("serverFieldReflected", st.get("serverReflected"));
        m.put("serverReflectError", st.get("reflectError"));
        m.put("embeddedRunning", st.get("running"));
        m.put("storeAvailable", st.get("storeAvailable"));
        m.put("embeddedReadPathUsable", st.get("embeddedReadPathUsable"));
        m.put("blockedReason", st.get("blockedReason"));
        return m;
    }

    private boolean serverRunning() {
        RedisServer s = resolveEmbeddedServer();
        return s != null && s.isRunning();
    }

    private static boolean isConnected(ZCacheClient c) {
        if (c == null) {
            return false;
        }
        try {
            return c.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static String clientTarget(ZCacheClient c) {
        try {
            if (c == null || c.getConfig() == null) {
                return "(client 或 config 不存在)";
            }
            return c.getConfig().getHost() + ":" + c.getConfig().getPort() + "/" + c.getConfig().getDatabase();
        } catch (Exception e) {
            return "(取 config 失败: " + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 用 INFO 里那两个"硬编码字段"反过来做归属指纹。
     *
     * <p>上面那条 {@code hardcodedInUpstream} 说的是"这两个值不随运行时状态变"，但漏了半句：
     * 它们在<b>编译期</b>被钉进了 class 文件的常量池。于是把<b>本 JVM 实际加载的</b> CommandHandler
     * 字节里的常量抠出来，与<b>对面应答的</b>值一比，就知道应答者是不是这次构建的产物。
     * 实测正是这里露馅的: 6379 上那个进程回 {@code z-cache_version:1.0.0} 且<b>整段没有</b>
     * {@code tcp_port} 行，而本次 classpath 里的 z-cache-core-1.3.1 会写 {@code 1.0.2} + {@code tcp_port:6379}
     * ⇒ 对面是更早一次构建的常驻进程，这条结论完全不依赖 lsof。
     *
     * <p>刻意不写死 "1.0.2" 这个字面量: 那只会再造一个"源码注释式"的假事实。
     * 常量从 class 字节里读，jar 一换结论就跟着变。
     */
    private Map<String, Object> responderVsOwnBuild(Object sections) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, String> own = ownInfoConstants();
        out.put("ownBuildConstants", own);
        out.put("ownConstantsSource", "扫本 JVM 加载的 CommandHandler.class 常量池，非硬编码");
        String responderVersion = infoValue(sections, "Server", "z-cache_version");
        String responderTcpPort = infoValue(sections, "Server", "tcp_port");
        out.put("responderVersion", responderVersion);
        out.put("responderTcpPort", responderTcpPort == null ? "(应答里没有 tcp_port 行)" : responderTcpPort);
        String ownVersion = own.get("z-cache_version");
        if (responderVersion == null || ownVersion == null) {
            out.put("verdict", "INCONCLUSIVE");
            out.put("note", "本 JVM 的 CommandHandler 常量或对方 INFO 至少一边取不到 ⇒ 不做判定，不猜");
            return out;
        }
        boolean same = ownVersion.equals(responderVersion);
        out.put("verdict", same ? "SAME_BUILD_CANDIDATE" : "DIFFERENT_BUILD");
        out.put("note", same
                ? "两边 z-cache_version 一致；这只能说明「可能」是同一次构建，仍要以 lsof 归属为准"
                : "应答方的 z-cache_version=" + responderVersion + " 而本次构建会写 " + ownVersion
                        + " ⇒ 应答者不是本次构建的产物，与 embeddedRunning=false / authorityOfClient"
                        + "=EXTERNAL_PROCESS 互相印证");
        return out;
    }

    /** 从本 JVM 真正加载到的那份 CommandHandler 字节码里抠 INFO 常量的值；失败返回空 map（由上层判 INCONCLUSIVE） */
    private Map<String, String> ownInfoConstants() {
        Map<String, String> res = new LinkedHashMap<String, String>();
        try {
            Class<?> handler = Class.forName("com.zifang.z.cache.core.command.CommandHandler");
            byte[] bytes = readClassBytes(handler);
            if (bytes == null) {
                return res;
            }
            // 常量池里这些是以 "z-cache_version:1.0.2\r\n" 形态存在的字符串字面量
            String text = new String(bytes, Charset.forName("ISO-8859-1"));
            for (String key : new String[]{"z-cache_version", "tcp_port"}) {
                Matcher m = Pattern.compile(Pattern.quote(key) + ":([0-9][0-9A-Za-z.\\-]*)").matcher(text);
                if (m.find()) {
                    res.put(key, m.group(1));
                }
            }
        } catch (Throwable t) {
            // 拿不到就空着，让 responderVsOwnBuild 报 INCONCLUSIVE —— 不许拿默认值凑一个结论
            res.clear();
        }
        return res;
    }

    /** 把 ClassLoader 能给的那份 class 字节读出来（上限 4 MB，读不到返回 null） */
    private byte[] readClassBytes(Class<?> target) {
        String path = "/" + target.getName().replace('.', '/') + ".class";
        InputStream in = target.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] chunk = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > 4 * 1024 * 1024) {
                    return null; // 异常大到不像一个类 ⇒ 不猜，直接判不可用
                }
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
                // 关闭失败不影响已经读到的常量
            }
        }
    }

    /** 从 parseInfo 的 {分节: {k: v}} 里取值；结构对不上时返回 null 而不是抛 */
    @SuppressWarnings("unchecked")
    private String infoValue(Object sections, String section, String key) {
        if (!(sections instanceof Map)) {
            return null;
        }
        Object sec = ((Map<String, Object>) sections).get(section);
        if (!(sec instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) sec).get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 路 B 的应答者是谁 —— 三态，绝不含糊。 */
    private String authorityOfClient(boolean embeddedRunning, boolean clientConnected) {
        if (!clientConnected) {
            return "NO_ANSWER";
        }
        if (embeddedRunning) {
            return "THIS_JVM_EMBEDDED";
        }
        return tcpConnectable(serverPortFromSpringEnv) ? "EXTERNAL_PROCESS" : "UNKNOWN";
    }

    private Object safeSend(ZCacheClient client, String command, String... args) {
        if (!CLIENT_READ_COMMANDS.contains(command)) {
            throw new IllegalStateException("refused non-read command: " + command);
        }
        try {
            return client.sendCommand(command, (Object[]) args);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("command", command);
            m.put("error", e.getClass().getName() + ": " + e.getMessage());
            return m;
        }
    }

    // ------------------------------------------------------------------ D2 / D3：路 B 的 type 归一与错位标记

    /**
     * 归一后的 type 读数。
     *
     * <p><b>为什么不能直接把 {@code RespXxx} 交给 Jackson</b>（D2）：
     * {@code safeSend} 返回的是 {@code RespSimpleString}/{@code RespBulkString} 这类 bean，
     * 按 bean 序列化出来是 {@code {"value":"string"}} / {@code {"data":[...],"empty":false,"null":false,"string":"..."}} ——
     * 前端拿到的不是"类型名"，是一坨协议细节的 JSON 原文，类型筛选永远 0 命中。
     *
     * <p><b>为什么归一之后还要标错位</b>（D3）：
     * 归一只解决"形状"，不解决"内容对不对"。实测同一次响应里就有三种互斥形状：
     * {@code {value:"string"}}（对）、{@code {data,empty,null,string}}（BulkString 里装的是<em>别的 key 名</em>）、
     * {@code {value:"-1"}}（TTL 的整数应答落进了 TYPE 槽）。
     * ⇒ 归一后若拿不到合法类型名，<b>不许</b>把它塞进大写枚举冒充类型（D2 末尾那句警告），
     * 只能标记 {@code desyncSuspect} + 保留 {@code rawReply}，由页面降级显示。
     */
    private static final class TypeReading {
        /** 合法类型名（大写）；不可信时为 null */
        final String value;
        /** 应答与"TYPE 的应答"不相容 ⇒ 疑似串台 */
        final boolean desync;
        /** 读命令本身失败（连接层异常 / 服务端报错）—— 不是错位，但也采信不了 */
        final boolean failed;
        /** 原始应答的可读形态（进 rawReply） */
        final String raw;
        /** 应答的协议形状名（进 typeSource） */
        final String source;
        /** 人话说明（进 typeNote） */
        final String note;

        TypeReading(String value, boolean desync, boolean failed, String raw, String source, String note) {
            this.value = value;
            this.desync = desync;
            this.failed = failed;
            this.raw = raw;
            this.source = source;
            this.note = note;
        }
    }

    /**
     * 归一 + 判形状。合法类型名只有 {@link #VALID_TYPES} 六个（大写枚举名，两路统一）。
     *
     * <p>协议类是 z-cache-common 里的（本仓同族模块，编译期可见），所以直接按类型判定；
     * 只有"不认识的类"才退到反射兜底 —— 反射拿不到就报"形状不认识"，不猜。
     */
    private static TypeReading normalizeTypeReading(Object raw) {
        if (raw == null) {
            return new TypeReading(null, false, true, "(null)", "NONE",
                    "没拿到应答（读命令没发出去或已返回 null）");
        }
        if (raw instanceof RespSimpleString) {
            String v = ((RespSimpleString) raw).getValue();
            return classifyTypeText(v, "RespSimpleString{value=" + v + "}", "RESP_SIMPLE_STRING");
        }
        if (raw instanceof RespBulkString) {
            RespBulkString b = (RespBulkString) raw;
            String v = b.isNull() ? null : b.getString();
            return classifyTypeText(v, "RespBulkString{string=" + v + "}", "RESP_BULK_STRING");
        }
        if (raw instanceof RespInteger) {
            long v = ((RespInteger) raw).getValue();
            return new TypeReading(null, true, false, "RespInteger{value=" + v + "}", "RESP_INTEGER",
                    "TYPE 槽里回来的是**整数应答**（" + v + "）—— 这不是类型名，"
                            + "多半是 TTL 的应答串到了 TYPE 槽 ⇒ 该行不采信");
        }
        if (raw instanceof RespError) {
            RespError e = (RespError) raw;
            return new TypeReading(null, false, true,
                    "RespError{errorType=" + e.getErrorType() + ", message=" + e.getMessage() + "}",
                    "RESP_ERROR", "命令报错：" + e.getErrorType() + " / " + e.getMessage());
        }
        if (raw instanceof RespArray) {
            RespArray a = (RespArray) raw;
            return new TypeReading(null, true, false, "RespArray{size=" + a.size() + "}", "RESP_ARRAY",
                    "TYPE 槽里回来的是**数组应答**（size=" + a.size() + "）—— 不是类型名 ⇒ 该行不采信");
        }
        if (raw instanceof String) {
            return classifyTypeText((String) raw, (String) raw, "PLAIN_STRING");
        }
        if (raw instanceof Number) {
            return new TypeReading(null, true, false, raw.getClass().getSimpleName() + "{" + raw + "}",
                    "PLAIN_NUMBER", "TYPE 槽里回来的是数字（" + raw + "）—— 不是类型名 ⇒ 该行不采信");
        }
        if (raw instanceof Map) {
            Object err = ((Map<?, ?>) raw).get("error");
            if (err != null) {
                return new TypeReading(null, false, true, String.valueOf(raw), "SAFE_SEND_ERROR",
                        "读命令失败：" + err);
            }
        }
        // 不认识的类：反射兜底一次（getString/getValue），拿不到就报"形状不认识"——不猜
        Object refl = reflectiveString(raw);
        if (refl != null) {
            return classifyTypeText(String.valueOf(refl),
                    raw.getClass().getSimpleName() + "{" + refl + "}", "REFLECTED");
        }
        return new TypeReading(null, true, false, raw.getClass().getName(), "UNKNOWN_SHAPE",
                "不认识的应答形状（" + raw.getClass().getName() + "）⇒ 该行不采信");
    }

    /** 拿字符串形态当类型名判：合法 ⇒ 采信；不合法 ⇒ 判错位，并说清错在哪 */
    private static TypeReading classifyTypeText(String v, String raw, String source) {
        if (v == null || v.isEmpty()) {
            return new TypeReading(null, true, false, raw, source,
                    "TYPE 应答是空的 ⇒ 采信不了（该行不采信）");
        }
        String upper = v.trim().toUpperCase(java.util.Locale.ROOT);
        if (VALID_TYPES.contains(upper)) {
            return new TypeReading(upper, false, false, raw, source,
                    "归一自 " + source + "（原始值 " + v + "）");
        }
        if (v.trim().matches("^-?[0-9]+$")) {
            return new TypeReading(null, true, false, raw, source,
                    "TYPE 槽里回来的是**整数应答**（" + v.trim() + "）—— 不是类型名，"
                            + "多半是 TTL 的应答串到了 TYPE 槽 ⇒ 该行不采信");
        }
        return new TypeReading(null, true, false, raw, source,
                "TYPE 槽里回来的是 \"" + v + "\"，不是类型名（合法值域：" + String.join("/", VALID_TYPES)
                        + "）—— 多半是**别的命令或别的 key 的应答**串台 ⇒ 该行不采信");
    }

    /** 反射兜底：找 getString() / getValue()；都没有就返回 null（由上层判"形状不认识"） */
    private static Object reflectiveString(Object o) {
        for (String m : new String[]{"getString", "getValue"}) {
            try {
                java.lang.reflect.Method mm = o.getClass().getMethod(m);
                Object v = mm.invoke(o);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignore) {
                // 没有这个方法 / 调不动 ⇒ 试下一个
            }
        }
        return null;
    }

    private List<Map<String, Object>> describeEmbedded(List<String> keys, MemoryStore store, int db) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String k : keys) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("key", k);
            MemoryStore.DataType t = store.getKeyType(k, db);
            // 两路统一大写枚举名（D2）：路 A 原来给 "none"（小写），路 B 给 "string"（小写），
            // 前端却拿大写去比 ⇒ 筛选永远 0 命中。现在两路都是 MemoryStore.DataType.name()。
            r.put("type", t == null ? "NONE" : t.name());
            r.put("typeNote", t == null ? "该库里没有这个 key" : "路 A：MemoryStore 进程内直读（权威）");
            r.put("typeSource", "EMBEDDED_STORE");
            r.put("desyncSuspect", Boolean.FALSE);
            r.put("ttlSeconds", Long.valueOf(store.ttlDb(db, k)));
            r.put("byteLength", Integer.valueOf(t == MemoryStore.DataType.STRING
                    ? len(store.getDb(db, k)) : -1));
            // D4：区分"这一格没测"（null）与"测了、结论是非 string"（-1）
            r.put("byteLengthNote", t == MemoryStore.DataType.STRING
                    ? "路 A：MemoryStore 里的原始字节长度"
                    : "路 A：该 key 不是 string，没有长度可言（-1 是**测出来的**结论，不是没测）");
            r.put("authority", "THIS_JVM_IN_PROCESS");
            rows.add(r);
        }
        return rows;
    }

    /**
     * 路 B 的一行。
     *
     * <p><b>D2</b>：type 不再是原始 RESP bean，而是归一后的字符串（大写枚举名）或 null。
     * <p><b>D3</b>：应答错位必须**标记并降级**，不许归一化到"看起来正常" ——
     * 拿不到合法类型名 ⇒ {@code type=null} + {@code desyncSuspect=true} + {@code rawReply} 保留原始应答。
     * <p><b>D4</b>：路 B 没有行内长度来源（不发 STRLEN）⇒ {@code byteLength=null} + note，
     * <b>不</b>用 -1 冒充"非 string"（那会把"没测"渲染成"测过的否定结论"）。
     */
    private List<Map<String, Object>> describeClient(List<String> keys, ZCacheClient client) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        String authority = authorityOfClient(serverRunning(), isConnected(client));
        for (String k : keys) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("key", k);

            TypeReading tr = normalizeTypeReading(safeSend(client, "TYPE", k));
            boolean suspect = tr.desync || tr.failed;
            r.put("type", suspect ? null : tr.value);
            r.put("typeNote", tr.note);
            r.put("typeSource", tr.source);
            if (suspect) {
                // 原始应答留底：这是"为什么不信这一行"的证据，不给就只剩一个空格子
                r.put("rawReply", tr.raw);
            }

            if (suspect) {
                // 同行 TYPE 都没对 ⇒ 整行不采信；此时**不再**为它多发一条 TTL 命令
                // （每多一条命令就多一次串台机会 —— D3 的第 3 条：别为了填列而填列）
                r.put("ttlSeconds", null);
                r.put("ttlSecondsNote", "同行 TYPE 已不可信 ⇒ 本行整体不采信，未再发 TTL");
            } else {
                try {
                    r.put("ttlSeconds", client.ttl(k));
                } catch (Exception e) {
                    r.put("ttlSeconds", null);
                    r.put("ttlSecondsError", e.getClass().getSimpleName() + ": " + e.getMessage());
                    // TTL 读失败 ⇒ 这一行不可信（无论原因是应答串台还是连接层出错）
                    suspect = true;
                    r.put("desyncReason", "TTL 读取失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            r.put("desyncSuspect", Boolean.valueOf(suspect));
            if (suspect && !r.containsKey("desyncReason")) {
                r.put("desyncReason", tr.desync
                        ? "TYPE 应答与所发命令对不上（" + tr.note + "）"
                        : tr.note);
            }
            if (suspect) {
                // 不可信的行，type 一律不下结论（不许显示"看起来正常"的类型）
                r.put("type", null);
            }

            r.put("byteLength", null);
            r.put("byteLengthNote", "路 B 未发 STRLEN（本卡不为了填列而多发一条命令）");
            r.put("authority", authority);
            rows.add(r);
        }
        return rows;
    }

    private static Map<String, Object> row(String cmd, String note, boolean readOnly) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("command", cmd);
        m.put("note", note);
        m.put("readOnly", Boolean.valueOf(readOnly));
        return m;
    }

    private static double hitRate(MemoryStore store) {
        long h = store.getHits();
        long m = store.getMisses();
        return (h + m) == 0L ? 0d : (double) h / (double) (h + m);
    }

    private static int len(byte[] b) {
        return b == null ? 0 : b.length;
    }

    private static List<Object> decodeList(List<byte[]> in, int cap) {
        List<Object> out = new ArrayList<Object>();
        if (in == null) {
            return out;
        }
        int i = 0;
        for (byte[] b : in) {
            if (i++ >= cap) {
                break;
            }
            out.add(decodeOne(b));
        }
        return out;
    }

    private static Map<String, Object> decodeMap(Map<String, byte[]> in, int cap) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (in == null) {
            return out;
        }
        int i = 0;
        for (Map.Entry<String, byte[]> e : in.entrySet()) {
            if (i++ >= cap) {
                break;
            }
            out.put(e.getKey(), decodeOne(e.getValue()));
        }
        return out;
    }

    private static Map<String, Object> decode(byte[] raw) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("byteLength", Integer.valueOf(len(raw)));
        m.put("value", decodeOne(raw));
        return m;
    }

    /** 缓存里可能是任意序列化字节（ZCacheCodec），强转字符串会乱码 ⇒ UTF-8 严格解码失败就给 base64 */
    private static Object decodeOne(byte[] raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UTF8_STRICT.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            Map<String, Object> bin = new LinkedHashMap<String, Object>();
            bin.put("notUtf8", Boolean.TRUE);
            bin.put("base64", Base64.getEncoder().encodeToString(raw));
            return bin;
        }
    }

    /** INFO 原文按 "# Section" + "k:v" 解析成结构化分节 */
    private static Map<String, Object> parseInfo(String raw) {
        Map<String, Object> sections = new LinkedHashMap<String, Object>();
        if (raw == null) {
            return sections;
        }
        String current = "_";
        Map<String, String> cur = new LinkedHashMap<String, String>();
        for (String line : raw.split("\r\n|\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("#")) {
                if (!cur.isEmpty()) {
                    sections.put(current, cur);
                }
                current = s.substring(1).trim();
                cur = new LinkedHashMap<String, String>();
                continue;
            }
            int ix = s.indexOf(':');
            if (ix > 0) {
                cur.put(s.substring(0, ix), s.substring(ix + 1));
            }
        }
        if (!cur.isEmpty()) {
            sections.put(current, cur);
        }
        return sections;
    }

    private static boolean tcpConnectable(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        // 只做三次握手 + 立刻关，**一个 RESP 字节都不发** ⇒ 不违反"不许对实例发命令"
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), TCP_PROBE_MS);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private static Set<String> unmodifiableSet(String... names) {
        Set<String> s = new TreeSet<String>();
        for (String n : names) {
            s.add(n);
        }
        return Collections.unmodifiableSet(s);
    }

    private Map<String, Object> err(String msg, String hint) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("status", "error");
        m.put("source", "z-opc-cache-read-api");
        m.put("message", msg);
        m.put("hint", hint);
        return m;
    }

    private void writeJson(HttpServletResponse resp, int status, Object body) throws Exception {
        byte[] out = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setContentLength(out.length);
        resp.getOutputStream().write(out);
        resp.getOutputStream().flush();
    }
}
