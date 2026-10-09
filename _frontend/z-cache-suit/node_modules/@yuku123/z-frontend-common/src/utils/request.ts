import axios, {AxiosInstance, AxiosResponse} from 'axios'

/**
 * 业务响应解包：识别后端约定的 {code, data, message} 格式。
 *
 * 标准（所有后端应遵守）：
 *   - 成功：{code: 200, data: T, message: 'ok'}  →  返回 data
 *   - 失败：{code: 4xx/5xx, data: null, message: '...'}  →  reject(new Error(message))
 *
 * 容错：响应不含 `code` 字段（旧的 Map / ResponseEntity 直返），按 pass-through 处理，
 * 业务侧自行读取 res.data / res.success。
 */
function defaultUnwrap(data: unknown): unknown {
    if (data && typeof data === 'object' && 'code' in data && 'data' in data) {
        const wrapped = data as {code: number | string; data: unknown; message?: string}
        if (wrapped.code !== 200) {
            return Promise.reject(new Error(wrapped.message || '请求失败'))
        }
        return wrapped.data
    }
    return data
}

/**
 * 请求拦截器配置
 * @param options.tokenKey 自定义 token 键名，默认 'token'
 * @param options.userInfoKey 自定义 userInfo 键名，默认 'userInfo'
 * @param options.unauthorizedRedirect 401 时跳转的地址，默认 '/login'
 */
export function setupInterceptors(
    instance: AxiosInstance,
    options: {
        tokenKey?: string
        userInfoKey?: string
        unauthorizedRedirect?: string
    } = {}
) {
    const {
        tokenKey = 'token',
        userInfoKey = 'userInfo',
        unauthorizedRedirect = '/login',
    } = options

    instance.interceptors.request.use((config) => {
        const token = localStorage.getItem(tokenKey)
        if (token) {
            config.headers.Authorization = `Bearer ${token}`
        }
        return config
    })

    instance.interceptors.response.use(
        (response: AxiosResponse) => defaultUnwrap(response.data),
        (error) => {
            if (error.response?.status === 401) {
                localStorage.removeItem(tokenKey)
                localStorage.removeItem(userInfoKey)
                window.location.href = unauthorizedRedirect
            } else if (error.response?.status === 403) {
                console.error('没有权限访问该资源')
            } else if (error.response?.status === 500) {
                console.error('服务器内部错误')
            }
            return Promise.reject(error)
        }
    )
}

/**
 * 创建请求实例。所有实例共用同一种响应解包策略（见 defaultUnwrap 注释）。
 *
 * @param options.baseURL 接口前缀，默认 '/api'
 * @param options.timeout 超时毫秒，默认 10000
 * @param options.tokenKey token localStorage key，默认 'token'
 * @param options.userInfoKey userInfo localStorage key，默认 'userInfo'
 * @param options.unauthorizedRedirect 401 跳转地址，默认 '/login'
 */
export function createRequest(options: {
    baseURL?: string
    timeout?: number
    tokenKey?: string
    userInfoKey?: string
    unauthorizedRedirect?: string
} = {}): AxiosInstance {
    const instance = axios.create({
        baseURL: options.baseURL ?? '/api',
        timeout: options.timeout ?? 10000,
    })
    setupInterceptors(instance, {
        tokenKey: options.tokenKey,
        userInfoKey: options.userInfoKey,
        unauthorizedRedirect: options.unauthorizedRedirect,
    })
    return instance
}

// 默认实例：用于主应用，baseURL = /api
const request = createRequest()
const authRequest = createRequest()

export {authRequest}
export default request
