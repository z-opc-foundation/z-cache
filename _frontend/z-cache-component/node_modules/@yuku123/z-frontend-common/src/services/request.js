/**
 * 兼容垫片: 之前在 z-opc-main-starter-frontend/services/request.ts 里的 axios 实例.
 */
import request, {authRequest, createRequest, setupInterceptors} from '../utils/request'
export {request as default, authRequest, createRequest, setupInterceptors}
export const ctcRequest = authRequest
