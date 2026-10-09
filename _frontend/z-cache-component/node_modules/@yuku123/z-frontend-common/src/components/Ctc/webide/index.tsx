import {useRef, useState} from 'react'
import {ActionType, PageContainer, ProColumns, ProForm, ProFormSelect, ProFormText, ProFormTextArea, ProTable} from '@ant-design/pro-components'
import {Button, Drawer, Form, Input, message, Select, Space, Tag, Tooltip} from 'antd'
import {
    CodeOutlined,
    DeleteOutlined,
    FileTextOutlined,
    PauseOutlined,
    PlayCircleOutlined,
    ReloadOutlined,
    PlusOutlined
} from '@ant-design/icons'
import {webideApi, type ZInspectRequest, type WebIdeContainer, type WebIdeMode} from '@/services/webide'

/**
 * WebIDE 容器管理 (z-webide)
 *
 * 端点：
 *   - POST /api/webide/inspect
 *   - GET  /api/webide/code-server/list
 *   - POST /api/webide/code-server/{id}/start
 *   - POST /api/webide/code-server/{id}/stop
 *   - DELETE /api/webide/code-server/{id}
 *   - GET  /api/webide/code-server/{id}/logs?lines=100
 */
const STATUS_MAP: Record<string, {text: string; color: string}> = {
    CREATING: {text: '创建中', color: 'processing'},
    RUNNING: {text: '运行中', color: 'success'},
    STOPPED: {text: '已停止', color: 'default'},
    ERROR: {text: '错误', color: 'error'},
}

const MODE_MAP: Record<WebIdeMode, {text: string; color: string}> = {
    GIT: {text: 'GIT 模式', color: 'blue'},
    SHARED: {text: 'SHARED 模式', color: 'purple'},
}

export default function WebIdeManagement() {
    const actionRef = useRef<ActionType | undefined>(undefined)
    const [inspectOpen, setInspectOpen] = useState(false)
    const [logsOpen, setLogsOpen] = useState(false)
    const [logs, setLogs] = useState<string[]>([])
    const [logsTarget, setLogsTarget] = useState<WebIdeContainer | null>(null)
    const [inspecting, setInspecting] = useState(false)
    const [form] = Form.useForm<ZInspectRequest>()

    const request_ = async () => {
        try {
            const res = await webideApi.listContainers()
            return {data: res || [], success: true, total: (res || []).length}
        } catch (e: any) {
            message.warning('后端未就绪，显示空列表：' + (e?.message || '未知错误'))
            return {data: [], success: false, total: 0}
        }
    }

    const handleInspect = async () => {
        try {
            const values = await form.validateFields()
            setInspecting(true)
            const res = await webideApi.inspect(values)
            message.success(`WebIDE 创建成功：${res.url}`)
            setInspectOpen(false)
            form.resetFields()
            actionRef.current?.reload()
            // 新窗口打开 IDE
            if (res.url) {
                window.open(res.url, '_blank')
            }
        } catch (e: any) {
            if (e?.errorFields) return
            message.error('创建失败：' + (e?.message || '未知错误'))
        } finally {
            setInspecting(false)
        }
    }

    const handleStart = async (id: string) => {
        try {
            await webideApi.startContainer(id)
            message.success('已启动')
            actionRef.current?.reload()
        } catch (e: any) {
            message.error('启动失败：' + (e?.message || '未知错误'))
        }
    }

    const handleStop = async (id: string) => {
        try {
            await webideApi.stopContainer(id)
            message.success('已停止')
            actionRef.current?.reload()
        } catch (e: any) {
            message.error('停止失败：' + (e?.message || '未知错误'))
        }
    }

    const handleRemove = async (id: string) => {
        try {
            await webideApi.removeContainer(id)
            message.success('已删除')
            actionRef.current?.reload()
        } catch (e: any) {
            message.error('删除失败：' + (e?.message || '未知错误'))
        }
    }

    const openLogs = async (r: WebIdeContainer) => {
        setLogsTarget(r)
        setLogsOpen(true)
        setLogs(['加载中...'])
        try {
            const res = await webideApi.getLogs(r.containerId, 200)
            setLogs(res?.lines || [])
        } catch (e: any) {
            setLogs([`加载日志失败：${e?.message || '未知错误'}`])
        }
    }

    const columns: ProColumns<WebIdeContainer>[] = [
        {title: '容器ID', dataIndex: 'containerId', width: 200, fixed: 'left', ellipsis: true},
        {
            title: '模式', dataIndex: 'mode', width: 110,
            render: (_, r) => {
                const m = MODE_MAP[r.mode] || {text: r.mode, color: 'default'}
                return <Tag color={m.color}>{m.text}</Tag>
            },
        },
        {title: 'Git URL', dataIndex: 'gitUrl', width: 200, ellipsis: true},
        {title: '分支', dataIndex: 'branch', width: 100},
        {
            title: '状态', dataIndex: 'status', width: 100,
            render: (_, r) => {
                const s = STATUS_MAP[r.status] || {text: r.status, color: 'default'}
                return <Tag color={s.color}>{s.text}</Tag>
            },
        },
        {title: 'URL', dataIndex: 'url', width: 200, ellipsis: true,
            render: (_, r) => r.url ? <a href={r.url} target="_blank" rel="noreferrer">{r.url}</a> : '-'},
        {title: 'CPU%', dataIndex: 'cpuUsage', width: 80,
            render: (v) => typeof v === 'number' ? `${v.toFixed(1)}%` : '-'},
        {title: '内存%', dataIndex: 'memoryUsage', width: 80,
            render: (v) => typeof v === 'number' ? `${v.toFixed(1)}%` : '-'},
        {title: '创建时间', dataIndex: 'createdAt', width: 170, valueType: 'dateTime'},
        {
            title: '操作', valueType: 'option', width: 240, fixed: 'right',
            render: (_, record) => [
                <Tooltip key="start" title="启动">
                    <Button type="text" size="small" icon={<PlayCircleOutlined/>}
                            onClick={() => handleStart(record.containerId)}>启动</Button>
                </Tooltip>,
                <Tooltip key="stop" title="停止">
                    <Button type="text" size="small" icon={<PauseOutlined/>}
                            onClick={() => handleStop(record.containerId)}>停止</Button>
                </Tooltip>,
                <Tooltip key="logs" title="查看日志">
                    <Button type="text" size="small" icon={<FileTextOutlined/>}
                            onClick={() => openLogs(record)}>日志</Button>
                </Tooltip>,
                <Tooltip key="remove" title="删除">
                    <Button type="text" danger size="small" icon={<DeleteOutlined/>}
                            onClick={() => handleRemove(record.containerId)}>删除</Button>
                </Tooltip>,
            ],
        },
    ]

    return (
        <PageContainer
            header={{
                title: 'WebIDE 容器管理 (z-webide)',
                subTitle: '基于 code-server 的容器化 WebIDE',
                breadcrumb: {},
            }}
        >
            <ProTable<WebIdeContainer>
                actionRef={actionRef}
                rowKey="containerId"
                columns={columns}
                request={request_}
                search={false}
                pagination={{pageSize: 20}}
                headerTitle="容器列表"
                toolBarRender={() => [
                    <Button key="add" type="primary" icon={<PlusOutlined/>} onClick={() => setInspectOpen(true)}>
                        新建 WebIDE
                    </Button>,
                    <Button key="reload" icon={<ReloadOutlined/>} onClick={() => actionRef.current?.reload()}>
                        刷新
                    </Button>,
                ]}
            />

            {/* 新建 WebIDE Modal */}
            <Drawer
                title={<Space><CodeOutlined/> 新建 WebIDE</Space>}
                open={inspectOpen}
                onClose={() => setInspectOpen(false)}
                width={520}
                extra={
                    <Space>
                        <Button onClick={() => setInspectOpen(false)}>取消</Button>
                        <Button type="primary" loading={inspecting} onClick={handleInspect}>
                            创建并打开
                        </Button>
                    </Space>
                }
            >
                <WebIdeInspectForm form={form}/>
            </Drawer>

            {/* 日志抽屉 */}
            <Drawer
                title={logsTarget ? `容器日志 — ${logsTarget.containerId}` : '容器日志'}
                open={logsOpen}
                onClose={() => setLogsOpen(false)}
                width={720}
            >
                <pre style={{
                    background: '#1e1e1e', color: '#d4d4d4', padding: 12, borderRadius: 4,
                    fontFamily: 'Menlo, Consolas, monospace', fontSize: 12,
                    maxHeight: '70vh', overflow: 'auto', whiteSpace: 'pre-wrap',
                }}>
                    {logs.join('\n')}
                </pre>
            </Drawer>
        </PageContainer>
    )
}

// Inspect 表单 (从 Drawer 主体中抽离以避免组件重渲染)
function WebIdeInspectForm({form}: {form: any}) {
    const {mode, setMode} = useInspectMode()
    return (
        <Form form={form} layout="vertical" style={{marginTop: 16}}
              initialValues={{mode: 'GIT' as WebIdeMode}}>
            <Form.Item name="mode" label="模式" rules={[{required: true}]}>
                <Select
                    onChange={v => setMode(v)}
                    options={[
                        {value: 'GIT', label: 'GIT 模式 (从仓库克隆)'},
                        {value: 'SHARED', label: 'SHARED 模式 (共享工作区)'},
                    ]}
                />
            </Form.Item>
            {mode === 'GIT' && (
                <>
                    <Form.Item name="gitUrl" label="Git URL" rules={[{required: true, type: 'url'}]}
                               extra="支持 https://github.com/xxx/yyy.git">
                        <Input placeholder="https://github.com/your/repo.git"/>
                    </Form.Item>
                    <Form.Item name="branch" label="分支">
                        <Input placeholder="main (默认)"/>
                    </Form.Item>
                </>
            )}
            {mode === 'SHARED' && (
                <Form.Item name="shareKey" label="共享 Key" rules={[{required: true}]}>
                    <Input placeholder="SHARED-XXXXXX"/>
                </Form.Item>
            )}
            <Form.Item name="tenantCode" label="租户编码 (可选)">
                <Input placeholder="default"/>
            </Form.Item>
        </Form>
    )
}

function useInspectMode() {
    const [mode, setMode] = useState<WebIdeMode>('GIT')
    return {mode, setMode}
}
