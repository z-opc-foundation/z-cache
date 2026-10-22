/**
 * 浏览器实测入口 —— **只**挂组件自己的 CacheApp（z-opc 主壳里就是以 /cache/* 挂进来的那一个）。
 *
 * <p>为什么不走 suit 的主 App（src/App.jsx）：那条路外面套了 AppLayout，
 * 而 AppLayout 自己带一个 Router ⇒ 组件的 <Routes> 落进"Router 套 Router"，
 * react-router 直接抛 "You cannot render a <Router> inside another <Router>"（2026-10-22 实测）。
 * 实测要的是**组件本体**的渲染结果，所以这里只留一个 Router。
 *
 * <p>用法：
 * <pre>
 *   cd _frontend/z-cache-suit
 *   LOCAL_SIBLINGS=1 npm run dev            # 吃平级仓源码（组件正在改的那份）
 *   ZCACHE_API_TARGET=http://127.0.0.1:8888 # 后端读数台（默认就是 :8888）
 *   # 然后打开 http://localhost:3026/browser-probe.html#/app/keys
 * </pre>
 */
import {StrictMode} from 'react'
import {createRoot} from 'react-dom/client'
import {HashRouter, Route, Routes} from 'react-router-dom'
import {CacheApp} from '@yuku123/z-cache-component'

createRoot(document.getElementById('root')).render(
    <StrictMode>
        <HashRouter>
            <Routes>
                <Route path="/app/*" element={<CacheApp/>}/>
                <Route path="*" element={<CacheApp/>}/>
            </Routes>
        </HashRouter>
    </StrictMode>,
)
