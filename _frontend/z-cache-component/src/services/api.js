import request from '@/common'

/**
 * z-cache（Redis 兼容缓存，自研）管理面数据源。
 *
 * ⚠️ 这一页和 z-vector / z-graph 的**形状不一样**，必须先说清楚，不然会误导后来的人：
 * 008/009 那两个模块的上游"有非 Spring 的 HTTP 面，只是不在 /actuator/mappings 里"，
 * 所以那边写的是**环回 HTTP 转发代理**（`/api/vector/**` → `HttpURLConnection` → 本 JVM 的 6334）。
 * z-cache 的上游**根本没有 HTTP 面** —— 全仓 grep `HttpServer|RestController|createContext` 在
 * `z-cache` 下零命中，唯一的 server 是 `RedisServer` 的 Netty `ServerBootstrap`，讲的是 **RESP2 over TCP**。
 * ⇒ `CacheProxyController`（文件名沿用需求指定，但它不是 proxy）不转发任何 HTTP，
 *   而是用 z-cache 自己的 Java API 现读现拼。所以本文件里没有任何"透传上游报文"的注释，
 *   每个字段都写明它来自**路 A（进程内 MemoryStore）**还是**路 B（zCacheClient 走 RESP）**。
 *
 * 两条取数路（同一个 JVM 内）：
 *   路 A = 反射 `ZCacheEmbeddedServerConfig.SHARED_SERVER` → `RedisServer.getStore()` → `MemoryStore` 只读方法。
 *          不开 socket、不碰端口，**物理上不可能读到别的进程** ⇒ "本 JVM 自己那份"的权威。
 *   路 B = 容器里已存在的 `zCacheClient` bean（starter 自动装配，业务代码用的就是它）。
 *          ⇒ "业务此刻真正看到的那份"。
 * 页面首屏就是把 A 和 B 并排 + 显式比对（`authorityDiverges`）。
 *
 * 实测报文形状（对着 CacheProxyController 源码，不是猜的）：
 *   GET /api/cache/__instance       → {jvm, surface:"RESP2/TCP", httpSurfaceOfZCache, lifecycleBean,
 *                                       isServerStartedClaimedByStarter, serverFieldReflected, serverReflectError,
 *                                       embeddedRunning, boundPort, storeAvailable,
 *                                       serverPortFromSystemProperty, serverPortFromSpringEnv,
 *                                       serverPortResolutionDiverges, configuredPort, acceptingNow,
 *                                       embeddedPortOwnedByThisJvm, foreignListenerSuspected,
 *                                       clientBeanPresent, clientTransportConnected, clientTargetHost/Port/Database,
 *                                       clientTargetHasPassword, clientTransportConnectedNote,
 *                                       authorityOfClient, invariant, blockedReason,
 *                                       readEndpoints[], writeEndpoints[](恒为 []), clientAllowedCommands[],
 *                                       selectNeverIssued}
 *   GET /api/cache/overview         → {jvm, embedded:{…dbsizeTotal, perDb[], hits, misses, hitRate, …},
 *                                       client:{present, transportConnected, dbsize, target, authority},
 *                                       authorityDiverges, foreignListenerSuspected}
 *   GET /api/cache/keys             → {pattern, limit, sourceUsed, db, matchedTotal, truncated,
 *                                       keys:[{key, type, ttlSeconds, byteLength?}], authority
 *                                       [, dbIgnoredForClientPath, dbIgnoreReason]}
 *                                       路 B 的行: {key, type, ttlSeconds} 且 type 可能是 {command,error} 对象
 *   GET /api/cache/key?key=&db=     → {key, sourceUsed, db, type, exists, ttlSeconds, pttlMillis, keyVersion,
 *                                       value:{…按类型…}, authority}
 *   GET /api/cache/info             → {authorityOfClient, raw, sections{Server/Clients/Memory/Stats/Keyspace},
 *                                       hardcodedInUpstream[], responderVsOwnBuild, embedded{…}}
 *                                       responderVsOwnBuild = {ownBuildConstants{z-cache_version,tcp_port},
 *                                       responderVersion, responderTcpPort,
 *                                       verdict: SAME_BUILD_CANDIDATE|DIFFERENT_BUILD|INCONCLUSIVE, note,
 *                                       ownConstantsSource}
 *                                       ⚠ ownBuildConstants 是扫**本 JVM 加载的 CommandHandler.class 常量池**得到的，
 *                                       不是前端写死的版本号 —— 对身份靠的是"字面量在编译期被钉进 class 文件"
 *                                       这一条，而它恰好补上了 z-cache 的死穴：内嵌从没 bind 成功，
 *                                       pid 归属那条路在这里根本走不通（实测对面印 1.0.0 且没有 tcp_port 行）。
 *   GET /api/cache/hotkeys          → {db, stringKeyTotal, truncated, rows:[{key, accessCount, lfuCounter, …}],
 *                                       authority:"THIS_JVM_IN_PROCESS", scopeNote, sourceNote}
 *   GET /api/cache/command-catalog  → {provenance, commandsActuallyIssued[], whitelistSemantics,
 *                                       notCountedAsIssued, readOnlyCommandsAllowedByWhitelist[],
 *                                       neverIssuedDespiteBeingSupportedByServer[], neverIssuedReason, serverBindAddress}
 * 出错时后端回 {status:"error", source:"z-opc-cache-read-api", message, hint} + HTTP 400/404/503。
 *
 * 刻意不提供的东西（不是遗漏）：
 *   - 任何写操作。后端只有 @GetMapping，前端也就不会有"清空 / 批量删除 / 设值"这类按钮 ——
 *     本卡只做读面，写面单独立卡。
 *   - 切库（SELECT）。`zCacheClient` 是**业务共用连接**，发一次 SELECT 会把它永久留在 n 库，
 *     之后所有业务的 SET/GET 都写错库。所以路 B 的接口一律忽略 db 参数
 *     （响应里 `dbIgnoredForClientPath=true` 就是这个意思）；要按库浏览请用 source=embedded。
 */
export const cacheApi = {
    instance: () => request.get('/cache/__instance'),
    overview: () => request.get('/cache/overview'),
    keys: (params) => request.get('/cache/keys', {params}),
    key: (params) => request.get('/cache/key', {params}),
    info: () => request.get('/cache/info'),
    hotkeys: (params) => request.get('/cache/hotkeys', {params}),
    commandCatalog: () => request.get('/cache/command-catalog'),
}

/**
 * authority 三态 → 颜色 + 一句话。
 * 这是本卡最重要的一格：它决定"这一屏数字能不能算孵化结果"。
 */
export const AUTHORITY_META = {
    THIS_JVM_IN_PROCESS: {color: 'success', text: '本 JVM 进程内对象（不可能跨进程）'},
    THIS_JVM_EMBEDDED: {color: 'success', text: '本 JVM 内嵌 server 应答'},
    EXTERNAL_PROCESS: {
        color: 'error',
        text: '外部进程应答 —— 6379 的属主不是本 JVM，这一路读到的是别的进程的缓存',
    },
    NO_ANSWER: {color: 'warning', text: '没有任何 server 在应答'},
    UNKNOWN: {color: 'warning', text: '连上了，但无法判定连的是谁'},
}

export function authorityTag(value) {
    return AUTHORITY_META[value] || {color: 'default', text: value || '-'}
}

/** 后端错误体是 {status:"error", source, message, hint}；不透出 message 页面只剩一句 Request failed with status code 503 */
export function cacheErrorText(e) {
    const data = e?.response?.data
    if (data && typeof data === 'object') {
        const message = data.message || data.error
        const hint = data.hint ? `｜${data.hint}` : ''
        const source = data.source ? ` (${data.source})` : ''
        if (message) return `${message}${source}${hint}`
    }
    if (typeof data === 'string' && data.trim()) return data.slice(0, 240)
    return e?.message || String(e)
}

/** 自省接口本身不通 = z-opc 侧的路由/鉴权问题；它通了但内嵌没起 = 中间件问题。两者提示必须分开 */
export function isNotBoundError(e) {
    const status = e?.response?.status
    const source = e?.response?.data?.source
    return status === 503 && source === 'z-opc-cache-read-api'
}

/** 字节数友好显示 */
export function fmtBytes(b) {
    if (b == null) return '-'
    const n = Number(b)
    if (!isFinite(n)) return '-'
    if (n < 1024) return `${n} B`
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`
    if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MiB`
    return `${(n / 1024 / 1024 / 1024).toFixed(2)} GiB`
}

/** Redis TTL 语义：-1 = 常驻，-2 = 不存在。直接显示成"负数秒"会被读成 bug。
 *  本文件是 .js（不是 .jsx），所以这里**只能返回字符串**，不要写 JSX。 */
export function fmtTtl(sec) {
    if (sec == null) return '-'
    const n = Number(sec)
    if (n === -2) return '不存在（-2）'
    if (n === -1) return '常驻（无过期）'
    if (n < 10) return `${n} 秒`
    if (n < 600) return `${Math.floor(n / 60)} 分 ${n % 60} 秒`
    return `${Math.floor(n / 86400)} 天 ${Math.floor((n % 86400) / 3600)} 时`
}

export function fmtUptime(sec) {
    if (sec == null) return '-'
    const n = Number(sec)
    const d = Math.floor(n / 86400), h = Math.floor((n % 86400) / 3600), m = Math.floor((n % 3600) / 60)
    if (d > 0) return `${d} 天 ${h} 时 ${m} 分`
    if (h > 0) return `${h} 时 ${m} 分 ${n % 60} 秒`
    return `${m} 分 ${n % 60} 秒`
}

/**
 * 路 B (RESP) 的单值可能是后端 safeSend() 失败时回的对象 {command, error}，
 * 也可能是 array / number。React 直接渲染对象会抛
 * "Objects are not valid as a React child" ⇒ 整张表崩掉。
 * 本函数把任何值归一成**字符串或 null**，让页面永远只降级不崩。
 * 本文件是 .js，不能返回 JSX。
 */
export function asText(v) {
    if (v == null) return null
    if (typeof v === 'string') return v
    if (typeof v === 'number' || typeof v === 'boolean') return String(v)
    if (Array.isArray(v)) return v.map(asText).filter((x) => x != null).join(', ')
    if (typeof v.error === 'string') return `命令失败：${v.command ?? '?'} → ${v.error}`
    try {
        return JSON.stringify(v)
    } catch (e) {
        return String(v)
    }
}
