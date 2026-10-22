# z-cache-component

z-cache（Redis 兼容缓存，自研）跨仓唯一前端面：把「缓存总览 / 键浏览 / 统计 / 实例自省」
这一组**只读**的缓存运维视图收敛成一个可复用的 React 组件包，由 `z-cache-suit` 独立壳、
z-team 的 OpsWorkbench（`/cache/*` 通配挂载）或 z-opc 主壳挂载。

- **npm 包名**：`@yuku123/z-cache-component`
- **宿主接入**：`@yuku123/z-cache-component/pages`（manifest + 页面组件），主壳可只取 `ComponentDocPage`
- **后端**：z-opc 侧的 `CacheProxyController`（`GET /api/cache/**`）—— **它不是转发代理**，
  而是用 z-cache 自己的 Java API 现读现拼
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

包里有**两套**页面入口，路由前缀不同，别混：

### 1.1 manifest 面（`/z-cache/*`，`src/pages-manifest.jsx`）

| 页面 | 路由（相对 `/z-cache/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 首页 | `home` | `pages/HomePage.jsx` | 读 `userInfo` 显示登录人，按 `menuItems` 渲染功能入口卡片 |
| 总览 | `overview` | `pages/Overview.jsx` | 缓存总览（`/cache/overview` + `/cache/info`） |
| 键浏览 | `keys` | `pages/Keys.jsx` | 键浏览（`/cache/keys`）+ 单键详情（`/cache/key`） |
| 登录 | （不在菜单内） | `pages/LoginPage.jsx` | mock 登录，写 `token` / `userInfo` 后跳 `/z-cache/home` |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

### 1.2 CacheApp 面（`/cache/*`，`src/pages/CacheApp.jsx`）

`CacheApp` 是给 OpsWorkbench 通配挂载用的**子路由集合**，入口重定向到 `overview`：

| 子路由 | 源文件 | 能力 |
|--------|--------|------|
| `overview` | `pages/OverviewPage.jsx` | 缓存总览 —— 双路对账（路 A 进程内 MemoryStore vs 路 B zCacheClient） |
| `keys` | `pages/KeysPage.jsx` | 键浏览（支持切库 / 切数据源 / 类型筛选 / 详情抽屉） |
| `stats` | `pages/StatsPage.jsx` | 热点 key 统计（`/cache/hotkeys`）+ 实例自省 |
| `instance` | `pages/InstanceStatus.jsx` | 实例自省（`/cache/__instance`）+ 命令目录（`/cache/command-catalog`） |

## 二、导出面

```js
// 主入口（. 桶）：数据源 + 域挂载点
import {cacheApi, AUTHORITY_META, authorityTag, cacheErrorText, isNotBoundError,
        fmtBytes, fmtTtl, fmtUptime, asText, CacheApp} from '@yuku123/z-cache-component'

// 宿主组装面（./pages 桶）：manifest + 页面组件
import {menuItems, routes, HomePage, LoginPage, Overview, Keys, ComponentDocPage} from '@yuku123/z-cache-component/pages'

// 组件文档页（主壳接线用，名字全仓统一）
import {ComponentDocPage} from '@yuku123/z-cache-component'
```

## 三、API 端点

请求实例是基座的默认 `request`（`@yuku123/z-frontend-common` 的 `createRequest()` 预建实例，
`baseURL` 默认 `/api`），token 走 `Authorization: Bearer`。
下表路径是 `src/services/api.js` 里 `cacheApi` 定义的**实际路径**（拼上 baseURL 即 `/api/cache/**`）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/cache/__instance` | 实例自省（是否 bind 上、store 是否可用、client 是否连上、authority 判定） |
| GET | `/api/cache/overview` | 缓存总览（路 A / 路 B 双路数据 + `authorityDiverges`） |
| GET | `/api/cache/keys` | 键浏览（query：`pattern` / `limit` / `db` / `source`） |
| GET | `/api/cache/key` | 单键详情（query：`key` / `db` / `source` / `maxElements`） |
| GET | `/api/cache/info` | 服务端 INFO（`Server` / `Clients` / `Memory` / `Stats` / `Keyspace` 五段） |
| GET | `/api/cache/hotkeys` | 热点 key（query：`db` / `limit`） |
| GET | `/api/cache/command-catalog` | RESP 命令目录（实际发过哪些命令 / 白名单语义） |

### 3.1 两条取数路（这是本包最需要先讲清楚的一件事）

z-cache 的上游**根本没有 HTTP 面** —— 全仓 grep `HttpServer|RestController|createContext`
在 `z-cache` 下零命中，唯一的 server 是 `RedisServer` 的 Netty `ServerBootstrap`，
讲的是 **RESP2 over TCP**。⇒ `CacheProxyController`（文件名沿用需求指定，但它不是 proxy）
不转发任何 HTTP。

同一个 JVM 内有两条**互不相关**的取数路：

- **路 A = 反射** `ZCacheEmbeddedServerConfig.SHARED_SERVER` → `RedisServer.getStore()` → `MemoryStore`
  只读方法。不开 socket、不碰端口，**物理上不可能读到别的进程** ⇒「本 JVM 自己那份」的权威。
- **路 B = 容器里已存在的 `zCacheClient` bean**（starter 自动装配，业务代码用的就是它）
  ⇒「业务此刻真正看到的那份」。

页面首屏就是把 A 和 B 并排 + 显式比对（`authorityDiverges`）。

### 3.2 authority 三态

| 取值 | 含义 |
|------|------|
| `THIS_JVM_IN_PROCESS` | 本 JVM 进程内对象（不可能跨进程） |
| `THIS_JVM_EMBEDDED` | 本 JVM 内嵌 server 应答 |
| `EXTERNAL_PROCESS` | 外部进程应答 —— 6379 的属主不是本 JVM，这一路读到的是别的进程的缓存 |
| `NO_ANSWER` | 没有任何 server 在应答 |
| `UNKNOWN` | 连上了，但无法判定连的是谁 |

## 四、页面说明

### 4.1 首页

登录后的着陆页。读 `localStorage.userInfo` 显示欢迎语与角色标签，再把 `menuItems`（去掉首页自身）
渲染成入口卡片 —— 菜单加一项，首页卡片自动多一张，不用改页面代码。

### 4.2 总览

`pages/Overview.jsx`：`/cache/overview` + `/cache/info` 两个接口并排渲染，
其中 `/cache/info` 失败时降级为 `null`，不拖垮主表。

`pages/OverviewPage.jsx`（CacheApp 版）：双路对账，逐库列出 key 数、命中率等。

### 4.3 键浏览

`pages/Keys.jsx`：`/cache/keys` 分页 + 点行开 `/cache/key` 详情。

`pages/KeysPage.jsx`（CacheApp 版）：在此基础上支持切库（`db`）、切数据源（`source`）、
类型筛选（`type`）、详情抽屉。

⚠ 类型取值域两路统一成**大写**枚举名（`STRING` / `HASH` / `LIST` / `SET` / `ZSET` / `NONE`）——
旧版拿大写去比小写（server 回 `"string"`）⇒ 类型筛选永远 0 命中。

### 4.4 统计

`pages/StatsPage.jsx`：热点 key 表（key / 访问次数 / LFU 计数 / 是否已过期）+ 实例自省。
热点表默认取 `db` 下 `limit: 50`。

### 4.5 实例自省

`pages/InstanceStatus.jsx`：`/cache/__instance` + `/cache/command-catalog`。
把「自省接口本身不通」（z-opc 侧路由/鉴权问题）与「自省通了但内嵌没起」
（中间件问题）分成两套提示，判据是 HTTP 503 且 `source === 'z-opc-cache-read-api'`。

### 4.6 登录

当前是 **mock 模式**：比对一对写死在 `src/pages/LoginPage.jsx` 里的管理员凭据
（明文口令不在本文复述，见源码），成功后写 `localStorage.token` 与 `localStorage.userInfo`，
再 `window.location.href = '/z-cache/home'`。CTC/SSO 尚未接入。

### 4.7 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| 请求实例 | 基座默认 `request` | `baseURL` 默认 `/api`，token 走 `Authorization: Bearer`（读 `localStorage.token`） |
| 热点 key 条数 | `50` | `hotkeys` 的 `limit` |
| 详情元素上限 | `50` | `KeysPage` 调 `key` 时传的 `maxElements` |

## 六、已知限制

- **本卡是只读的**：后端只有 `@GetMapping`，前端也就不会有「清空 / 批量删除 / 设值」这类按钮。
  写面单独立卡，不在本包范围内。
- **切库（SELECT）被刻意禁用**：`zCacheClient` 是**业务共用连接**，发一次 SELECT 会把它永久留在 n 库，
  之后所有业务的 SET/GET 都写错库。所以路 B 的接口一律忽略 `db` 参数
  （响应里 `dbIgnoredForClientPath=true` 就是这个意思）；要按库浏览请用 `source=embedded`。
- **路 B 的 `byteLength` 恒为 null**（路 B 不发 `STRLEN`）⇒ 页面显示「未测」，
  不是「非 string」—— 后者会把「没测」渲染成否定结论。
- **路 B 的 `type` 可能为 null**：null 且 `desyncSuspect=true` ⇒ 这一行的应答与所发命令对不上（串台），
  **不采信**，原始应答留在 `rawReply`、判据在 `desyncReason`。
- **TTL 语义**：`-1` = 常驻、`-2` = 不存在，直接显示成「负数秒」会被读成 bug，所以 `fmtTtl` 单独翻译。
- **没有任何页面调用的接口**：无（`cacheApi` 的 7 个方法都有页面在用）。
- 直达 / 刷新需要四处接线都齐（大需求 004 的四级路由契约）：MENU_GROUPS「缓存」组 ↔
  OPS_ROUTES 的 `/cache` + `/cache/*` ↔ main.jsx 顶层 `<Route path="/cache/*">` ↔
  `MainWebConfig.spaPaths` 的 `/cache/**`。缺 main.jsx 那条 = 白屏，缺 spaPaths 那条 = 直达 404。
- z-cache 已被 z-opc 主壳的 `domainRoutes.ts` 与 z-team 的 OpsWorkbench 引用
  ⇒ `/z-cache/docs` 在平台主壳里**可达**。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.2 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.2 | 首版：缓存总览 / 键浏览 / 统计 / 实例自省 + manifest + CacheApp 挂载点 |
