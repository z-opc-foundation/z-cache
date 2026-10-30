import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import {fileURLToPath} from 'node:url'
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

/**
 * 后端地址：默认打真的 z-opc（:8888），可用 ZCACHE_API_TARGET 指到别的读数台
 * （TASK-20260925-041 的本地 harness 就靠这个开关跑浏览器实测）。
 */
const API_TARGET = process.env.ZCACHE_API_TARGET || 'http://localhost:8888'

/**
 * LOCAL_SIBLINGS=1 ⇒ 把组件 alias 到**平级仓源码**（而不是 npm 上那一份发布件）。
 * ⚠️ 必须是**绝对路径**：原来的相对写法 '../z-cache-component/src' 会被 vite 按
 * "发起方文件所在目录"解析（= suit/src/），于是 '@yuku123/z-cache-component/pages'
 * 落到 suit/src/../z-cache-component/src/pages —— 不存在 ⇒ 500
 * "Failed to resolve import"（2026-10-22 跑浏览器实测时撞到，已修）。
 */
const LOCAL_COMPONENT_SRC = fileURLToPath(new URL('../z-cache-component/src', import.meta.url))
/**
 * z-frontend-common 也指到**工作区源码**（与 z-opc 主壳 vite.config.js 同一口径）。
 * 原因：组件的 services/api.js 用的是**默认导入**当 axios 实例
 * （`import request from '@yuku123/z-frontend-common'`），
 * 而 npm 上已发布的 1.1.1 的默认导出是个"桶"对象（AppLayout/StatusTag/…/request），
 * 桶上**没有** axios 动词 ⇒ `request.get is not a function`。
 * 工作区源码的 src/index.js 在桶上补了一层动词转发（`bucket.get = (...args) => request.get(...)`），
 * 主壳就是靠这条 alias 让 z-cache/z-graph/z-gw/z-mq/z-msg/z-oss 这 6 个老写法继续能跑。
 * ⚠️ 子路径（/ui）必须排在主路径前面，否则 '/ui' 会被替换成 <src>/index.js/ui。
 */
const WORKSPACE_FRONTEND_COMMON = fileURLToPath(new URL('../../../z-frontend-common/src', import.meta.url))

export default defineConfig({
    plugins: [safeEmptyOutDir(), react()],
  resolve: { dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'] ,
        alias: process.env.LOCAL_SIBLINGS === '1' ? [
            {find: '@yuku123/z-frontend-common/ui', replacement: WORKSPACE_FRONTEND_COMMON + '/components/ui/index.js'},
            {find: '@yuku123/z-frontend-common', replacement: WORKSPACE_FRONTEND_COMMON + '/index.js'},
            {find: '@yuku123/z-cache-component', replacement: LOCAL_COMPONENT_SRC},
        ] : {}},
    server: {
        port: 3026,
        fs: {allow: ['..']},
        proxy: {
            // ⚠️ 组件里的请求是 `request.get('/cache/keys')`，而 request 实例的 baseURL 是 **/api**
            // （z-frontend-common 的默认值）⇒ 真正发出去的是 /api/cache/keys。
            // 所以代理必须盯 **/api**，不是 '/cache'（盯 /cache 时这条请求压根不经过代理，页面永远取不到数）。
            '^/api': {target: API_TARGET, changeOrigin: true},
            '/cache': {target: API_TARGET, changeOrigin: true},
            '/actuator': {target: API_TARGET, changeOrigin: true},
        },
    },
})
