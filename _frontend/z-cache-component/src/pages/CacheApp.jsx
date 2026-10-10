import {Navigate, Route, Routes} from 'react-router-dom'
import OverviewPage from './OverviewPage'
import KeysPage from './KeysPage'
import StatsPage from './StatsPage'
import InstanceStatus from './InstanceStatus'

/**
 * z-cache（Redis 兼容缓存）管理面 — OpsWorkbench 以 /cache/* 通配挂进来。
 *
 * 与 z-vector / z-graph 那两个壳的唯一区别：这里的后端**不是转发代理**。
 * z-cache 没有 HTTP 面（只有 Netty 上的 RESP2），所以 `CacheProxyController` 是用
 * z-cache 自己的 Java API 现读现拼的**只读** controller，取数两条路：
 *   路 A = 进程内 `MemoryStore`（反射拿内嵌 RedisServer 的 store，不可能跨进程）；
 *   路 B = 容器里已有的 `zCacheClient` bean（业务代码用的那条 RESP 连接）。
 * 四个页面都在显示"这两路各看到了什么、是否分歧"，而不是在显示一份被认为权威的数据。
 *
 * 直达 / 刷新需要四处接线都齐（大需求 004 的四级路由契约）：
 *   MENU_GROUPS「缓存」组 ↔ OPS_ROUTES 的 /cache + /cache/* ↔ main.jsx 顶层
 *   <Route path="/cache/*"> ↔ MainWebConfig.spaPaths 的 "/cache/**"。
 * 缺 main.jsx 那条 = 白屏，缺 spaPaths 那条 = 直达 404。四处粘贴块在交接文件 §四。
 */
export default function CacheApp() {
    return (
        <Routes>
            <Route index element={<Navigate to="overview" replace/>}/>
            <Route path="overview" element={<OverviewPage/>}/>
            <Route path="keys" element={<KeysPage/>}/>
            <Route path="stats" element={<StatsPage/>}/>
            <Route path="instance" element={<InstanceStatus/>}/>
        </Routes>
    )
}
