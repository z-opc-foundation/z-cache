/**
 * z-frontend-common 兼容垫片 - 之前在 z-opc-main-starter-frontend/services/api.ts 里的所有 API
 */
import axios from 'axios'

let _baseURL = '/api'
export function configureBaseURL(baseURL) { _baseURL = baseURL || '/api' }
function makeRequest() {
    return axios.create({baseURL: _baseURL, timeout: 15000})
}
const request = makeRequest()
const authRequest = makeRequest()

// 拦截器
request.interceptors.request.use(config => {
    const token = localStorage.getItem('token')
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
})

// === 鉴权 API (FEATURE015) ===
export const login = (data) => request.post('/ctc/auth/login', data).then(r => r.data)
export const getCurrentUser = () => {
    try {
        const raw = localStorage.getItem('userInfo')
        return Promise.resolve(raw ? JSON.parse(raw) : null)
    } catch {
        return Promise.resolve(null)
    }
}
export const switchTenant = (tenantCode, domainCode) =>
    authRequest.post('/ctc/auth/switch-tenant', {tenantCode: tenantCode || '', domainCode: domainCode || ''}).then(r => r.data)

// === 占位 (实际调用从 z-ctc-frontend-component) ===
export const getDomainByTenantCode = async () => [{domainCode: 'default', domainName: '默认域'}]
export const getTenantList = async () => []
export const getDynamicMenu = async () => []

// === 动态 API 路由表 (从 apiRouter.ts 迁入) ===
function makeApi(name) {
    return {
        list: (params) => request.get(`/${name}/list`, {params}).then(r => r.data),
        page: (params) => request.get(`/${name}/page`, {params}).then(r => r.data),
        get: (id) => request.get(`/${name}/${id}`).then(r => r.data),
        create: (data) => request.post(`/${name}`, data).then(r => r.data),
        update: (id, data) => request.put(`/${name}/${id}`, data).then(r => r.data),
        delete: (id) => request.delete(`/${name}/${id}`).then(r => r.data),
    }
}

export const configApi = makeApi('config')
export const approvalApi = makeApi('approval')
export const designerApi = makeApi('designer')
export const mcpApi = makeApi('mcp')
export const mockPlatformApi = makeApi('mock')
export const namingApi = makeApi('naming')
export const opsApi = makeApi('ops')
export const ossApi = makeApi('oss')
export const scriptApi = makeApi('script')
export const skillApi = makeApi('skill')
export const agentApi = makeApi('agent')
export const flowApi = makeApi('flow')
export const traceApi = makeApi('trace')
export const llmApi = makeApi('llm')
export const productApi = makeApi('product')
export const sceneApi = makeApi('scene')
export const userOrgRelApi = {
    usersByDept: (_code) => Promise.resolve([]),
    usersByGroup: (_code) => Promise.resolve([]),
}

// === 项目 / 任务 (FEATURE015) ===
export const listMyProjects = () => authRequest.get('/project/user/list').then(r => r.data)
export const createProject = (data) => request.post('/project', data).then(r => r.data)
export const updateProject = (id, data) => request.put(`/project/${id}`, data).then(r => r.data)
export const archiveProject = (id) => request.put(`/project/${id}/archive`).then(r => r.data)
export const listTasksByProject = (projectId) => request.get('/task/project/list', {params: {projectId}}).then(r => r.data)
export const createTask = (data) => request.post('/task', data).then(r => r.data)
export const completeTask = (id) => request.post('/task/complete', null, {params: {taskId: id}}).then(r => r.data)
export const moveTask = (taskId, targetListId, position) =>
    request.put('/task/move', null, {params: {taskId, targetListId, position}}).then(r => r.data)
export const listTasksByList = (listId) => request.get('/task/list', {params: {listId}}).then(r => r.data)

export default request
