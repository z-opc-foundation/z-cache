/** 占位 stub: z-webide 后端服务 */
import request from './request'
export const webideApi = {
    health: () => request.get('/webide/health').then(r => r.data),
}
export default webideApi
