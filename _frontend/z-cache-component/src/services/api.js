/**
 * z-cache API client：走 /cache/** 面（缓存中心：overview / keys / info / hotkeys）。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '', tokenKey: 'zcache_token'})

export default request

export function configureCache(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

export const cacheApi = {
    instance: () => request.get('/cache/__instance'),
    overview: () => request.get('/cache/overview'),
    keys: (params) => request.get('/cache/keys', {params}),
    key: (params) => request.get('/cache/key', {params}),
    info: () => request.get('/cache/info'),
    hotkeys: (params) => request.get('/cache/hotkeys', {params}),
}
