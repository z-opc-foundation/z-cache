import {useEffect, useState} from 'react'
import {
    Alert,
    Button,
    Card,
    Col,
    Descriptions,
    Drawer,
    Form,
    Input,
    InputNumber,
    message,
    Modal,
    Popconfirm,
    Row,
    Select,
    Space,
    Switch,
    Table,
    Tabs,
    Tag,
    Tooltip,
    Typography
} from 'antd'
import {
    CloudUploadOutlined,
    CopyOutlined,
    DeleteOutlined,
    EditOutlined,
    ExperimentOutlined,
    PlayCircleOutlined,
    PlusOutlined
} from '@ant-design/icons'
import {mockPlatformApi} from '@/services/api'
import OpenApiImportModal from './OpenApiImportModal'
import MockTemplateHelper from './MockTemplateHelper'

const {TextArea} = Input
const {Text, Paragraph} = Typography

const METHOD_OPTIONS = [
    {value: 'GET', label: 'GET'},
    {value: 'POST', label: 'POST'},
    {value: 'PUT', label: 'PUT'},
    {value: 'DELETE', label: 'DELETE'},
    {value: 'PATCH', label: 'PATCH'},
]
const METHOD_COLORS = {GET: 'green', POST: 'blue', PUT: 'orange', DELETE: 'red', PATCH: 'purple'}

const FAULT_OPTIONS = [
    {value: 'NONE', label: '无故障'},
    {value: 'TIMEOUT', label: '超时 (TIMEOUT)'},
    {value: 'EMPTY_RESPONSE', label: '空响应 (EMPTY)'},
    {value: 'RANDOM_DROP', label: '随机丢弃 (RANDOM_DROP)'},
    {value: 'MALFORMED', label: '畸形响应 (MALFORMED)'},
    {value: '500_ERROR', label: '500 错误'},
    {value: 'RATE_LIMIT', label: '限流 (RATE_LIMIT)'},
]

const BODY_MATCH_TYPES = [
    {value: 'JSON', label: 'JSON'},
    {value: 'JSONPATH', label: 'JSONPath'},
    {value: 'REGEX', label: '正则'},
    {value: 'TEXT', label: '纯文本'},
]


export default function EndpointsTab({activeEnvCode}) {
    const [endpoints, setEndpoints] = useState([])
    const [loading, setLoading] = useState(false)
    const [mockError, setMockError] = useState(null)
    const [modalOpen, setModalOpen] = useState(false)
    const [editing, setEditing] = useState(null)
    const [form] = Form.useForm()
    const [previewOpen, setPreviewOpen] = useState(false)
    const [previewEndpoint, setPreviewEndpoint] = useState(null)
    const [openApiImportOpen, setOpenApiImportOpen] = useState(false)

    useEffect(() => {
        loadEndpoints()
    }, [activeEnvCode])

    const loadEndpoints = async () => {
        setLoading(true)
        setMockError(null)
        try {
            const res = await mockPlatformApi.endpoints.list(activeEnvCode)
            setEndpoints(Array.isArray(res) ? res : [])
        } catch (e) {
            setMockError(e?.message || 'API unavailable')
            setEndpoints([])
        } finally {
            setLoading(false)
        }
    }

    const handleCreate = () => {
        setEditing(null)
        form.resetFields()
        form.setFieldsValue({
            method: 'GET', statusCode: 200, delayMs: 0, priority: 5,
            envCode: activeEnvCode || 'default',
            matchBodyType: 'JSON', faultType: 'NONE', status: 1,
        })
        setModalOpen(true)
    }

    const handleEdit = (record) => {
        setEditing(record)
        form.setFieldsValue(record)
        setModalOpen(true)
    }

    const handleSave = async () => {
        try {
            const values = await form.validateFields()
            if (editing) {
                await mockPlatformApi.endpoints.update(editing.mockCode, values)
                message.success('更新成功')
            } else {
                await mockPlatformApi.endpoints.create(values)
                message.success('创建成功')
            }
            setModalOpen(false)
            loadEndpoints()
        } catch (e) {
            if (e?.errorFields) return
            message.error('保存失败: ' + (e?.message || ''))
        }
    }

    const handleDelete = async (mockCode) => {
        try {
            await mockPlatformApi.endpoints.delete(mockCode)
            message.success('删除成功')
            loadEndpoints()
        } catch (e) {
            message.error('删除失败')
        }
    }

    const handleToggle = async (record) => {
        const newStatus = record.status === 1 ? 0 : 1
        try {
            await mockPlatformApi.endpoints.toggle(record.mockCode, newStatus)
            message.success(newStatus === 1 ? '已启用' : '已禁用')
            loadEndpoints()
        } catch (e) {
            message.error('操作失败')
        }
    }

    const getEndpointUrl = (record) => {
        return `${window.location.origin}/api/mock/${record.envCode || activeEnvCode || 'default'}${record.path || ''}`
    }

    const columns = [
        {
            title: '路径', dataIndex: 'path', key: 'path', width: 280,
            render: (text, r) => (
                <Space>
                    <Tag color={METHOD_COLORS[r.method] || 'default'}>{r.method}</Tag>
                    <Text code copyable={{text}}>{text}</Text>
                </Space>
            )
        },
        {title: '编码', dataIndex: 'mockCode', key: 'mockCode', width: 160},
        {
            title: '优先级', dataIndex: 'priority', key: 'priority', width: 80, align: 'center',
            render: (v) => <Tag color="blue">P{v || 5}</Tag>
        },
        {title: '状态码', dataIndex: 'statusCode', key: 'statusCode', width: 80, align: 'center'},
        {
            title: '延迟', dataIndex: 'delayMs', key: 'delayMs', width: 80, align: 'center',
            render: (v) => v > 0 ? <Text type="warning">{v}ms</Text> : <Text type="secondary">-</Text>
        },
        {
            title: '故障', dataIndex: 'faultType', key: 'faultType', width: 110,
            render: (v) => v && v !== 'NONE' ? <Tag color="red">{v}</Tag> : <Text type="secondary">-</Text>
        },
        {
            title: '场景', dataIndex: 'scenarioCode', key: 'scenarioCode', width: 130,
            render: (v) => v ? <Tag color="purple">{v}</Tag> : <Text type="secondary">-</Text>
        },
        {
            title: '命中', dataIndex: 'hitCount', key: 'hitCount', width: 80, align: 'center',
            render: (v) => <Text strong>{v || 0}</Text>
        },
        {
            title: '状态', key: 'status', width: 100, align: 'center',
            render: (_, record) => (
                <Switch checked={record.status === 1} onChange={() => handleToggle(record)}
                        checkedChildren="启用" unCheckedChildren="禁用" size="small"/>
            )
        },
        {
            title: '操作', key: 'action', width: 220, fixed: 'right',
            render: (_, record) => (
                <Space size="small">
                    <Tooltip title="预览响应">
                        <Button size="small" type="link" icon={<PlayCircleOutlined/>}
                                onClick={() => {
                                    setPreviewEndpoint(record);
                                    setPreviewOpen(true)
                                }}>预览</Button>
                    </Tooltip>
                    <Tooltip title="复制URL">
                        <Button size="small" type="link" icon={<CopyOutlined/>}
                                onClick={() => {
                                    navigator.clipboard.writeText(getEndpointUrl(record));
                                    message.success('已复制')
                                }}/>
                    </Tooltip>
                    <Tooltip title="编辑">
                        <Button size="small" type="link" icon={<EditOutlined/>} onClick={() => handleEdit(record)}/>
                    </Tooltip>
                    <Popconfirm title="确认删除?" onConfirm={() => handleDelete(record.mockCode)}>
                        <Button size="small" type="link" danger icon={<DeleteOutlined/>}/>
                    </Popconfirm>
                </Space>
            )
        },
    ]

    return (
        <div>
            {mockError && (
                <Alert type="warning" showIcon style={{marginBottom: 16}}
                       message="后端 API 暂未连接" description={mockError}/>
            )}

            <Row gutter={16} style={{marginBottom: 16}}>
                <Col span={6}><Card><strong>总端点</strong>
                    <div style={{fontSize: 22}}>{endpoints.length}</div>
                </Card></Col>
                <Col span={6}><Card><strong>已启用</strong>
                    <div style={{fontSize: 22, color: '#52c41a'}}>{endpoints.filter(e => e.status === 1).length}</div>
                </Card></Col>
                <Col span={6}><Card><strong>已禁用</strong>
                    <div style={{fontSize: 22, color: '#ff4d4f'}}>{endpoints.filter(e => e.status === 0).length}</div>
                </Card></Col>
                <Col span={6}><Card><strong>总命中</strong>
                    <div style={{
                        fontSize: 22,
                        color: '#1890ff'
                    }}>{endpoints.reduce((s, e) => s + (e.hitCount || 0), 0)}</div>
                </Card></Col>
            </Row>

            <Space style={{marginBottom: 16}}>
                <Button icon={<PlayCircleOutlined/>} onClick={loadEndpoints}>刷新</Button>
                <Button type="primary" icon={<PlusOutlined/>} onClick={handleCreate}>新建 Mock 端点</Button>
                <Button icon={<CloudUploadOutlined/>} onClick={() => setOpenApiImportOpen(true)}>
                    批量导入 (OpenAPI / Swagger / Postman)
                </Button>
            </Space>

            <Table
                dataSource={endpoints}
                columns={columns}
                rowKey="id"
                loading={loading}
                pagination={{pageSize: 10}}
                scroll={{x: 1300}}
                size="small"
            />

            <Modal
                title={editing ? `编辑 Mock 端点: ${editing.mockCode}` : '新建 Mock 端点'}
                open={modalOpen}
                onCancel={() => setModalOpen(false)}
                onOk={handleSave}
                width={900}
                okText="保存" cancelText="取消"
            >
                <Form form={form} layout="vertical" style={{marginTop: 16}}>
                    <Tabs
                        items={[
                            {
                                key: 'basic', label: '基本信息',
                                children: (
                                    <>
                                        <Row gutter={16}>
                                            <Col span={8}>
                                                <Form.Item name="mockCode" label="Mock 编码" rules={[{required: true}]}>
                                                    <Input placeholder="mock-user-list" disabled={!!editing}/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={8}>
                                                <Form.Item name="mockName" label="名称">
                                                    <Input placeholder="User List Endpoint"/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={8}>
                                                <Form.Item name="envCode" label="环境编码">
                                                    <Input placeholder="default"/>
                                                </Form.Item>
                                            </Col>
                                        </Row>
                                        <Row gutter={16}>
                                            <Col span={12}>
                                                <Form.Item name="path" label="路径" rules={[{required: true}]}>
                                                    <Input placeholder="/api/user/list"/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={4}>
                                                <Form.Item name="method" label="方法">
                                                    <Select options={METHOD_OPTIONS}/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={4}>
                                                <Form.Item name="priority" label="优先级">
                                                    <InputNumber min={0} max={99} style={{width: '100%'}}/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={4}>
                                                <Form.Item name="statusCode" label="状态码">
                                                    <InputNumber min={100} max={599} style={{width: '100%'}}/>
                                                </Form.Item>
                                            </Col>
                                        </Row>
                                        <Row gutter={16}>
                                            <Col span={8}>
                                                <Form.Item name="delayMs" label="延迟(ms)">
                                                    <InputNumber min={0} max={60000} style={{width: '100%'}}/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={8}>
                                                <Form.Item name="projectCode" label="项目">
                                                    <Input placeholder="default"/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={8}>
                                                <Form.Item name="moduleCode" label="模块">
                                                    <Input placeholder="user"/>
                                                </Form.Item>
                                            </Col>
                                        </Row>
                                        <Form.Item name="description" label="描述">
                                            <TextArea rows={2}/>
                                        </Form.Item>
                                    </>
                                )
                            },
                            {
                                key: 'match', label: '请求匹配',
                                children: (
                                    <>
                                        <Form.Item name="matchUrlPattern" label="URL 匹配模式 (Ant风格)"
                                                   extra="支持 /api/users/{id} 通配符，留空则按 path 精确匹配">
                                            <Input placeholder="/api/users/{id}"/>
                                        </Form.Item>
                                        <Row gutter={16}>
                                            <Col span={12}>
                                                <Form.Item name="matchHeaders" label="Header 匹配 (JSON)"
                                                           extra='例: {"Authorization": "Bearer *", "Content-Type": "application/json"}'>
                                                    <TextArea rows={3} placeholder='{"Authorization": "Bearer *"}'/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={12}>
                                                <Form.Item name="matchQuery" label="Query 匹配 (JSON)"
                                                           extra='例: {"page": "1", "size": "10"}'>
                                                    <TextArea rows={3} placeholder='{"page": "1"}'/>
                                                </Form.Item>
                                            </Col>
                                        </Row>
                                        <Form.Item name="matchBodyType" label="Body 匹配类型">
                                            <Select options={BODY_MATCH_TYPES}/>
                                        </Form.Item>
                                        <Form.Item name="matchBody" label="Body 匹配表达式"
                                                   extra="JSON 类型支持 JSONPath，REGEX 类型为正则表达式">
                                            <TextArea rows={3} placeholder='$.user.id == "100"'/>
                                        </Form.Item>
                                    </>
                                )
                            },
                            {
                                key: 'scenario', label: '场景状态机',
                                children: (
                                    <>
                                        <Form.Item name="scenarioCode" label="场景编码"
                                                   extra="关联场景实现状态机驱动 (如 user-flow, payment-flow)">
                                            <Input placeholder="user-flow"/>
                                        </Form.Item>
                                        <Row gutter={16}>
                                            <Col span={12}>
                                                <Form.Item name="requiredState" label="匹配所需状态"
                                                           extra="仅当实例处于此状态时才匹配 (留空 = 不限制)">
                                                    <Input placeholder="started"/>
                                                </Form.Item>
                                            </Col>
                                            <Col span={12}>
                                                <Form.Item name="newState" label="匹配后新状态"
                                                           extra="匹配成功后自动切换">
                                                    <Input placeholder="logged-in"/>
                                                </Form.Item>
                                            </Col>
                                        </Row>
                                    </>
                                )
                            },
                            {
                                key: 'fault', label: '故障注入',
                                children: (
                                    <>
                                        <Form.Item name="faultType" label="故障类型"
                                                   extra="模拟网络异常，验证客户端容错">
                                            <Select options={FAULT_OPTIONS}/>
                                        </Form.Item>
                                        <Form.Item name="faultConfig" label="故障配置 (JSON)"
                                                   extra='例: {"errorRate": 0.5, "rateLimitQps": 10, "timeoutMs": 30000}'>
                                            <TextArea rows={4} placeholder='{"errorRate": 0.5}'/>
                                        </Form.Item>
                                        <Form.Item name="proxyTo" label="代理目标 URL"
                                                   extra="未匹配时转发到真实服务 (MockEnvironment 模式)">
                                            <Input placeholder="http://real-service:8080"/>
                                        </Form.Item>
                                    </>
                                )
                            },
                            {
                                key: 'response', label: '响应',
                                children: (
                                    <>
                                        <Form.Item name="responseHeaders" label="响应头 (JSON)"
                                                   extra='例: {"Content-Type": "application/json", "X-Trace-Id": "..."}'>
                                            <TextArea rows={2} placeholder='{"Content-Type": "application/json"}'/>
                                        </Form.Item>
                                        <Form.Item name="responseTemplate" label="响应模板 (JSON)"
                                                   rules={[{required: true}]}
                                                   extra={
                                                       <div>
                                                           <Text type="secondary" style={{display: 'block', marginBottom: 4}}>
                                                               支持 <code>@int(1,100)</code> / <code>@uuid</code> / <code>@datetime</code> / <code>#path.id</code> 等动态表达式
                                                           </Text>
                                                           <MockTemplateHelper form={form} formField="responseTemplate"/>
                                                       </div>
                                                   }>
                                            <TextArea
                                                rows={14}
                                                placeholder={'{\n  "success": true,\n  "data": [],\n  "code": 200\n}'}
                                                style={{fontFamily: 'monospace', fontSize: 13}}
                                            />
                                        </Form.Item>
                                    </>
                                )
                            },
                        ]}
                    />
                </Form>
            </Modal>

            <Drawer
                title={<Space><ExperimentOutlined/>端点详情: {previewEndpoint?.mockCode}</Space>}
                open={previewOpen}
                onClose={() => setPreviewOpen(false)}
                width={650}
            >
                {previewEndpoint && (
                    <div>
                        <Descriptions column={2} size="small" bordered style={{marginBottom: 16}}>
                            <Descriptions.Item label="编码">{previewEndpoint.mockCode}</Descriptions.Item>
                            <Descriptions.Item label="名称">{previewEndpoint.mockName}</Descriptions.Item>
                            <Descriptions.Item label="方法">
                                <Tag color={METHOD_COLORS[previewEndpoint.method]}>{previewEndpoint.method}</Tag>
                            </Descriptions.Item>
                            <Descriptions.Item label="环境">{previewEndpoint.envCode}</Descriptions.Item>
                            <Descriptions.Item label="路径" span={2}>{previewEndpoint.path}</Descriptions.Item>
                            <Descriptions.Item label="状态码">{previewEndpoint.statusCode}</Descriptions.Item>
                            <Descriptions.Item label="延迟">{previewEndpoint.delayMs}ms</Descriptions.Item>
                            <Descriptions.Item label="优先级">P{previewEndpoint.priority}</Descriptions.Item>
                            <Descriptions.Item label="命中">{previewEndpoint.hitCount || 0}</Descriptions.Item>
                            <Descriptions.Item label="故障">
                                {previewEndpoint.faultType && previewEndpoint.faultType !== 'NONE' ?
                                    <Tag color="red">{previewEndpoint.faultType}</Tag> :
                                    <Text type="secondary">-</Text>}
                            </Descriptions.Item>
                            <Descriptions.Item label="场景">{previewEndpoint.scenarioCode || '-'}</Descriptions.Item>
                            <Descriptions.Item label="状态" span={2}>
                                {previewEndpoint.status === 1 ? <Tag color="green">启用</Tag> : <Tag>禁用</Tag>}
                            </Descriptions.Item>
                            <Descriptions.Item label="完整 URL" span={2}>
                                <Text copyable>{getEndpointUrl(previewEndpoint)}</Text>
                            </Descriptions.Item>
                        </Descriptions>

                        <Paragraph strong>响应模板:</Paragraph>
                        <pre style={{
                            background: '#f5f5f5', padding: 12, borderRadius: 4,
                            maxHeight: 350, overflow: 'auto', fontSize: 13, fontFamily: 'monospace',
                            whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                        }}>
              {previewEndpoint.responseTemplate}
            </pre>
                    </div>
                )}
            </Drawer>

            <OpenApiImportModal
                open={openApiImportOpen}
                onClose={() => setOpenApiImportOpen(false)}
                onSuccess={() => {
                    message.success('已刷新列表');
                    loadEndpoints()
                }}
            />
        </div>
    )
}
