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
    message,
    Modal,
    Popconfirm,
    Row,
    Select,
    Space,
    Statistic,
    Table,
    Tag,
    Typography
} from 'antd'
import {
    CodeOutlined,
    DeleteOutlined,
    EditOutlined,
    FileSearchOutlined,
    PlayCircleOutlined,
    PlusOutlined,
    ReloadOutlined
} from '@ant-design/icons'
import {mockPlatformApi} from '@/services/api'
import CurlImportModal from './CurlImportModal'

const {Text} = Typography

const PRIORITY_OPTIONS = [
    {value: 'P0', label: 'P0 (最高)'},
    {value: 'P1', label: 'P1 (高)'},
    {value: 'P2', label: 'P2 (中)'},
    {value: 'P3', label: 'P3 (低)'},
]

const PRIORITY_COLORS = {P0: 'red', P1: 'orange', P2: 'blue', P3: 'default'}

const STATUS_COLORS = {PASS: 'green', FAIL: 'red', SKIP: 'default'}

const BODY_MATCH_OPTIONS = [
    {value: 'EXACT', label: '完全匹配'},
    {value: 'CONTAINS', label: '包含'},
    {value: 'REGEX', label: '正则'},
    {value: 'JSONPATH', label: 'JSONPath'},
]

/**
 * 测试用例 Tab - 用例库管理 + 单条/批量执行 + 执行结果查看
 */
export default function TestCasesTab() {
    const [cases, setCases] = useState([])
    const [loading, setLoading] = useState(false)
    const [selectedRowKeys, setSelectedRowKeys] = useState([])
    const [modalOpen, setModalOpen] = useState(false)
    const [editing, setEditing] = useState(null)
    const [resultOpen, setResultOpen] = useState(false)
    const [result, setResult] = useState(null)
    const [batchResult, setBatchResult] = useState(null)
    const [batchOpen, setBatchOpen] = useState(false)
    const [curlImportOpen, setCurlImportOpen] = useState(false)
    const [form] = Form.useForm()

    useEffect(() => {
        loadCases()
    }, [])

    const loadCases = async () => {
        setLoading(true)
        try {
            const res = await mockPlatformApi.cases.list()
            setCases(Array.isArray(res) ? res : [])
        } catch (e) {
            message.warning('加载用例列表失败')
        } finally {
            setLoading(false)
        }
    }

    const handleCreate = () => {
        setEditing(null)
        form.resetFields()
        form.setFieldsValue({
            requestMethod: 'GET', expectedStatus: 200,
            priority: 'P1', status: 1, expectedBodyMatchType: 'EXACT',
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
            let assertions
            try {
                assertions = values.assertionsJson ? JSON.parse(values.assertionsJson) : null
            } catch {
                message.error('断言 JSON 格式错误');
                return
            }
            const payload = {...values, assertions}
            delete payload.assertionsJson
            if (editing) {
                await mockPlatformApi.cases.update(editing.caseCode, payload)
                message.success('更新成功')
            } else {
                await mockPlatformApi.cases.create(payload)
                message.success('创建成功')
            }
            setModalOpen(false)
            loadCases()
        } catch (e) {
            if (!e?.errorFields) message.error('保存失败')
        }
    }

    const handleDelete = async (code) => {
        try {
            await mockPlatformApi.cases.delete(code)
            message.success('删除成功')
            loadCases()
        } catch (e) {
            message.error('删除失败')
        }
    }

    const handleRun = async (record) => {
        try {
            const res = await mockPlatformApi.cases.run(record.caseCode)
            setResult(res)
            setResultOpen(true)
            if (res?.status === 'PASS') message.success('用例通过')
            else message.error('用例失败: ' + (res?.error || res?.actualStatus))
            loadCases()
        } catch (e) {
            message.error('执行失败: ' + e?.message)
        }
    }

    const handleBatchRun = async () => {
        if (selectedRowKeys.length === 0) {
            message.warning('请先选择用例')
            return
        }
        try {
            const res = await mockPlatformApi.cases.runBatch(selectedRowKeys)
            setBatchResult(res)
            setBatchOpen(true)
            message.success(`批量执行完成: ${res?.pass} 通过, ${res?.fail} 失败`)
            loadCases()
        } catch (e) {
            message.error('批量执行失败')
        }
    }

    const columns = [
        {
            title: '用例编码', dataIndex: 'caseCode', key: 'caseCode', width: 180,
            render: (v) => <Text code>{v}</Text>
        },
        {title: '名称', dataIndex: 'caseName', key: 'caseName', width: 220},
        {
            title: '分组', dataIndex: 'caseGroup', key: 'caseGroup', width: 130,
            render: (v) => v ? <Tag>{v}</Tag> : '-'
        },
        {
            title: '方法', dataIndex: 'requestMethod', key: 'requestMethod', width: 80,
            render: (v) => <Tag color="blue">{v}</Tag>
        },
        {
            title: '路径', dataIndex: 'requestUrl', key: 'requestUrl',
            ellipsis: true,
            render: (v) => <Text code>{v}</Text>
        },
        {
            title: '优先级', dataIndex: 'priority', key: 'priority', width: 80, align: 'center',
            render: (v) => <Tag color={PRIORITY_COLORS[v]}>{v || 'P1'}</Tag>
        },
        {
            title: '上次结果', dataIndex: 'lastRunResult', key: 'lastRunResult', width: 100, align: 'center',
            render: (v) => v ? <Tag color={STATUS_COLORS[v]}>{v}</Tag> : '-'
        },
        {
            title: '统计', key: 'stats', width: 130, align: 'center',
            render: (_, r) => (
                <Space size="small">
                    <Text type="success">{r.passCount || 0}</Text>/<Text type="danger">{r.failCount || 0}</Text>
                    <Text type="secondary">({r.runCount || 0})</Text>
                </Space>
            )
        },
        {
            title: '操作', key: 'action', width: 220, fixed: 'right',
            render: (_, record) => (
                <Space size="small">
                    <Button size="small" type="link" icon={<PlayCircleOutlined/>}
                            onClick={() => handleRun(record)}>运行</Button>
                    <Button size="small" type="link" icon={<EditOutlined/>}
                            onClick={() => handleEdit(record)}/>
                    <Popconfirm title={`删除用例 ${record.caseCode}?`}
                                onConfirm={() => handleDelete(record.caseCode)}>
                        <Button size="small" type="link" danger icon={<DeleteOutlined/>}/>
                    </Popconfirm>
                </Space>
            )
        },
    ]

    return (
        <div>
            <Alert type="info" showIcon style={{marginBottom: 16}}
                   message="测试用例库"
                   description="对 Mock 接口进行断言验证。支持 7 种断言类型：STATUS / HEADER / BODY / JSON_PATH / REGEX / SCHEMA / RESPONSE_TIME。支持单条运行与批量运行，自动统计通过/失败次数。"
            />

            <Space style={{marginBottom: 16}}>
                <Button icon={<ReloadOutlined/>} onClick={loadCases}>刷新</Button>
                <Button type="primary" icon={<PlusOutlined/>} onClick={handleCreate}>新建用例</Button>
                <Button icon={<CodeOutlined/>} onClick={() => setCurlImportOpen(true)}>
                    粘贴 curl 导入
                </Button>
                <Button type="primary" icon={<PlayCircleOutlined/>}
                        onClick={handleBatchRun} disabled={selectedRowKeys.length === 0}>
                    批量运行 ({selectedRowKeys.length})
                </Button>
            </Space>

            <Table
                dataSource={cases}
                columns={columns}
                rowKey="id"
                loading={loading}
                rowSelection={{selectedRowKeys, onChange: setSelectedRowKeys}}
                pagination={{pageSize: 10}}
                scroll={{x: 1300}}
                size="small"
            />

            <Modal
                title={editing ? `编辑用例: ${editing.caseCode}` : '新建测试用例'}
                open={modalOpen}
                onCancel={() => setModalOpen(false)}
                onOk={handleSave}
                width={750}
            >
                <Form form={form} layout="vertical" style={{marginTop: 16}}>
                    <Row gutter={16}>
                        <Col span={12}>
                            <Form.Item name="caseCode" label="用例编码" rules={[{required: true}]}>
                                <Input placeholder="case-user-login" disabled={!!editing}/>
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="caseName" label="用例名称">
                                <Input placeholder="用户登录成功用例"/>
                            </Form.Item>
                        </Col>
                    </Row>
                    <Row gutter={16}>
                        <Col span={8}>
                            <Form.Item name="caseGroup" label="用例分组">
                                <Input placeholder="login"/>
                            </Form.Item>
                        </Col>
                        <Col span={8}>
                            <Form.Item name="priority" label="优先级">
                                <Select options={PRIORITY_OPTIONS}/>
                            </Form.Item>
                        </Col>
                        <Col span={8}>
                            <Form.Item name="envCode" label="环境">
                                <Input placeholder="default"/>
                            </Form.Item>
                        </Col>
                    </Row>
                    <Row gutter={16}>
                        <Col span={6}>
                            <Form.Item name="requestMethod" label="方法">
                                <Select options={[
                                    {value: 'GET', label: 'GET'},
                                    {value: 'POST', label: 'POST'},
                                    {value: 'PUT', label: 'PUT'},
                                    {value: 'DELETE', label: 'DELETE'},
                                ]}/>
                            </Form.Item>
                        </Col>
                        <Col span={18}>
                            <Form.Item name="requestUrl" label="请求 URL" rules={[{required: true}]}>
                                <Input placeholder="/api/mock/default/user/login"/>
                            </Form.Item>
                        </Col>
                    </Row>
                    <Row gutter={16}>
                        <Col span={12}>
                            <Form.Item name="requestQuery" label="查询参数">
                                <Input placeholder="debug=true"/>
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="expectedStatus" label="期望状态码">
                                <Select options={[
                                    {value: 200, label: '200 OK'},
                                    {value: 201, label: '201 Created'},
                                    {value: 400, label: '400 Bad Request'},
                                    {value: 401, label: '401 Unauthorized'},
                                    {value: 404, label: '404 Not Found'},
                                    {value: 500, label: '500 Server Error'},
                                ]}/>
                            </Form.Item>
                        </Col>
                    </Row>
                    <Form.Item name="requestHeaders" label="请求头 (JSON)"
                               extra='例: {"Authorization": "Bearer xxx"}'>
                        <Input.TextArea rows={2} placeholder='{"X-Trace-Id": "abc"}'/>
                    </Form.Item>
                    <Form.Item name="requestBody" label="请求体">
                        <Input.TextArea rows={3} placeholder='{"username":"test","password":"xxx"}'/>
                    </Form.Item>
                    <Form.Item name="expectedBodyMatchType" label="Body 匹配方式">
                        <Select options={BODY_MATCH_OPTIONS}/>
                    </Form.Item>
                    <Form.Item name="expectedBody" label="期望 Body">
                        <Input.TextArea rows={3} placeholder='{"code": 200, "success": true}'/>
                    </Form.Item>
                    <Form.Item name="assertionsJson" label="断言列表 (JSON 数组)"
                               extra='例: [{"assertType":"STATUS","expected":200,"comparator":"EQUALS"}]'>
                        <Input.TextArea rows={4} placeholder='[{"assertType":"STATUS","expected":200}]'/>
                    </Form.Item>
                    <Form.Item name="description" label="描述">
                        <Input.TextArea rows={2}/>
                    </Form.Item>
                </Form>
            </Modal>

            <Drawer
                title={<Space><FileSearchOutlined/>用例执行结果</Space>}
                open={resultOpen}
                onClose={() => setResultOpen(false)}
                width={650}
            >
                {result && (
                    <div>
                        <Row gutter={16} style={{marginBottom: 16}}>
                            <Col span={8}>
                                <Card>
                                    <Statistic
                                        title="结果"
                                        value={result.status}
                                        valueStyle={{color: STATUS_COLORS[result.status] === 'green' ? '#52c41a' : '#ff4d4f'}}
                                    />
                                </Card>
                            </Col>
                            <Col span={8}>
                                <Card>
                                    <Statistic title="实际状态码" value={result.actualStatus || '-'}/>
                                </Card>
                            </Col>
                            <Col span={8}>
                                <Card>
                                    <Statistic title="耗时(ms)" value={result.durationMs || 0}/>
                                </Card>
                            </Col>
                        </Row>

                        <Descriptions column={1} size="small" bordered style={{marginBottom: 16}}>
                            <Descriptions.Item label="用例编码">{result.caseCode}</Descriptions.Item>
                            <Descriptions.Item label="用例名称">{result.caseName}</Descriptions.Item>
                            <Descriptions.Item label="错误信息">{result.error || '-'}</Descriptions.Item>
                        </Descriptions>

                        {result.assertionResults && (
                            <>
                                <Text strong>断言详情:</Text>
                                <Table
                                    style={{marginTop: 8}}
                                    dataSource={result.assertionResults}
                                    columns={[
                                        {title: '断言类型', dataIndex: 'assertType', key: 'assertType', width: 110},
                                        {title: '表达式', dataIndex: 'expression', key: 'expression'},
                                        {title: '期望值', dataIndex: 'expected', key: 'expected'},
                                        {title: '实际值', dataIndex: 'actual', key: 'actual'},
                                        {
                                            title: '结果', dataIndex: 'passed', key: 'passed', width: 80,
                                            render: (v) => v ? <Tag color="green">PASS</Tag> :
                                                <Tag color="red">FAIL</Tag>
                                        },
                                    ]}
                                    pagination={false} size="small"
                                />
                            </>
                        )}

                        <Text strong style={{marginTop: 16, display: 'block'}}>响应体:</Text>
                        <pre style={{
                            background: '#f5f5f5', padding: 12, borderRadius: 4,
                            maxHeight: 250, overflow: 'auto', fontSize: 12, fontFamily: 'monospace',
                            whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                        }}>
              {result.responseBody || '(empty)'}
            </pre>
                    </div>
                )}
            </Drawer>

            <Modal
                title={`批量执行结果 (${batchResult?.total || 0} 条)`}
                open={batchOpen}
                onCancel={() => setBatchOpen(false)}
                footer={<Button onClick={() => setBatchOpen(false)}>关闭</Button>}
                width={800}
            >
                {batchResult && (
                    <>
                        <Row gutter={16} style={{marginBottom: 16}}>
                            <Col span={6}><Card><Statistic title="总数" value={batchResult.total}/></Card></Col>
                            <Col span={6}><Card><Statistic title="通过" value={batchResult.pass}
                                                           valueStyle={{color: '#52c41a'}}/></Card></Col>
                            <Col span={6}><Card><Statistic title="失败" value={batchResult.fail}
                                                           valueStyle={{color: '#ff4d4f'}}/></Card></Col>
                            <Col span={6}><Card><Statistic title="通过率"
                                                           value={`${batchResult.total > 0 ? Math.round((batchResult.pass / batchResult.total) * 100) : 0}%`}/></Card></Col>
                        </Row>
                        <Table
                            dataSource={batchResult.results || []}
                            columns={[
                                {title: '编码', dataIndex: 'caseCode', key: 'caseCode', width: 180},
                                {title: '名称', dataIndex: 'caseName', key: 'caseName'},
                                {
                                    title: '结果', dataIndex: 'status', key: 'status', width: 80,
                                    render: (v) => <Tag color={STATUS_COLORS[v]}>{v}</Tag>
                                },
                                {title: '耗时', dataIndex: 'durationMs', key: 'durationMs', width: 80, align: 'center'},
                                {title: '错误', dataIndex: 'error', key: 'error', ellipsis: true},
                            ]}
                            pagination={false} size="small" rowKey="caseCode"
                        />
                    </>
                )}
            </Modal>

            <CurlImportModal
                open={curlImportOpen}
                onClose={() => setCurlImportOpen(false)}
                onSuccess={(type, res) => {
                    message.success(type === 'case' ? '测试用例已导入，正在刷新列表' : 'Mock 端点已导入')
                    loadCases()
                }}
            />
        </div>
    )
}
