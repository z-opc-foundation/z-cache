/**
 * workspace 服务 — FEATURE016 工作空间 / 工作项 (z-agent-team 后端)
 * 占位 stub: 实际 API 在 z-agent-frontend-component 内的 agentTeam service.
 */
import request from './request'

export const workspaceApi = {
    list: () => request.get('/agent/team/workspace/list').then(r => r.data),
    create: (data) => request.post('/agent/team/workspace', data).then(r => r.data),
    update: (id, data) => request.put(`/agent/team/workspace/${id}`, data).then(r => r.data),
    delete: (id) => request.delete(`/agent/team/workspace/${id}`).then(r => r.data),
    getFileTree: (path) => request.get('/agent/team/workspace/file-tree-by-path', {params: {path}}).then(r => r.data),
    getFileContent: (path) => request.get('/agent/team/workspace/file-content', {params: {path}}).then(r => r.data),
}

export const workItemApi = {
    list: (conversationId) => request.get(`/agent/team/conversation/${conversationId}/work-items`).then(r => r.data),
    create: (data) => request.post('/agent/team/work-item', data).then(r => r.data),
    update: (id, data) => request.put(`/agent/team/work-item/${id}`, data).then(r => r.data),
    delete: (id) => request.delete(`/agent/team/work-item/${id}`).then(r => r.data),
}
