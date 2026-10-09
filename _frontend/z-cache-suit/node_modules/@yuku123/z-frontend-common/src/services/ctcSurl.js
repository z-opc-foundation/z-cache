/** 占位 stub: ctcSurl 服务 — 之前在 z-opc-main-starter-frontend/services/ 里的实现
 * 实际调用方应迁移到具体业务包 (z-ctc-frontend-component, z-meta-frontend-component 等). */
import request from './request'
export const ctcSurlApi = {
    health: () => request.get('/ctcSurl/health').then(r => r.data),
}
export default ctcSurlApi
