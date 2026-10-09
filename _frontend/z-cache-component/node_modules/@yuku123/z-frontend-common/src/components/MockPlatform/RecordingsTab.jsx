import {useEffect, useState} from 'react'
import {
    Alert,
    Button,
    Checkbox,
    Descriptions,
    Drawer,
    Form,
    Input,
    message,
    Modal,
    Popconfirm,
    Space,
    Table,
    Tabs,
    Tag,
    Typography
} from 'antd'
import {
    CheckCircleOutlined,
    DeleteOutlined,
    ExportOutlined,
    ImportOutlined,
    PlayCircleOutlined,
    ReloadOutlined,
    StopOutlined,
    VideoCameraOutlined
} from '@ant-design/icons'
import {mockPlatformApi} from '@/services/api'

const {Text} = Typography

const STATUS_COLORS = {
    IDLE: 'default', RECORDING: 'red', STOPPED: 'green', EXPIRED: 'gray',
}

/**
 * 录制回放 Tab - 录制真实流量，转换为 Mock 集合
 */
export default function RecordingsTab() {
    const [recordings, setRecordings] = useState([])
    const [loading, setLoading] = useState(false)
    const [startOpen, setStartOpen] = useState(false)
    const [playbackOpen, setPlaybackOpen] = useState(false)
    const [exportOpen, setExportOpen] = useState(false)
    const [detailOpen, setDetailOpen] = useState(false)
    const [detail, setDetail] = useState(null)
    const [detailRequests, setDetailRequests] = useState([])
    const [form] = Form.useForm()
    const [playbackForm] = Form.useForm()
    const [exportForm] = Form.useForm()
    const [exportDrafts, setExportDrafts] = useState([])
    const [exportLoading, setExportLoading] = useState(false)
    const [selectedDrafts, setSelectedDrafts] = useState([])

    useEffect(() => {
        loadRecordings()
    }, [])

    const loadRecordings = async () => {
        setLoading(true)
        try {
            const res = await mockPlatformApi.recordings.list()
            setRecordings(Array.isArray(res) ? res : [])
        } catch (e) {
            message.warning('加载录制列表失败')
        } finally {
            setLoading(false)
        }
    }

    const handleStart = async () => {
        try {
            const values = await form.validateFields()
            await mockPlatformApi.recordings.start(values.recordingCode, values.recordingName, values.targetUrl)
            message.success('录制已开启')
            setStartOpen(false)
            form.resetFields()
            loadRecordings()
        } catch (e) {
            if (!e?.errorFields) message.error('开启失败')
        }
    }

    const handleStop = async (code) => {
        try {
            await mockPlatformApi.recordings.stop(code)
            message.success('录制已停止')
            loadRecordings()
        } catch (e) {
            message.error('停止失败')
        }
    }

    const handleDelete = async (code) => {
        try {
            await mockPlatformApi.recordings.delete(code)
            message.success('删除成功')
            loadRecordings()
        } catch (e) {
            message.error('删除失败')
        }
    }

    const handleDetail = async (record) => {
        try {
            const requests = await mockPlatformApi.recordings.requests(record.recordingCode)
            setDetail(record)
            setDetailRequests(Array.isArray(requests) ? requests : [])
            setDetailOpen(true)
        } catch (e) {
            message.error('加载失败')
        }
    }

    const handleImport = async (record) => {
        Modal.confirm({
            title: '导入录制为 Mock',
            content: `将把录制 "${record.recordingCode}" 转换为 Mock endpoints 并入库。`,
            okText: '确认导入',
            onOk: async () => {
                try {
                    const res = await mockPlatformApi.recordings.import(record.recordingCode, 'default')
                    message.success(`导入成功: ${res?.imported || 0} 个 Mock endpoint`)
                } catch (e) {
                    message.error('导入失败')
                }
            },
        })
    }

    const handlePlayback = async () => {
        try {
            const values = await playbackForm.validateFields()
            const res = await mockPlatformApi.recordings.playback(detail.recordingCode, values.targetUrl)
            Modal.info({
                title: '回放结果',
                width: 700,
                content: (
                    <div>
                        <Text>总数: {res?.[0]?.total || 0} 通过: {res?.[0]?.pass || 0} 失败: {res?.[0]?.fail || 0}</Text>
                        <pre style={{
                            marginTop: 12,
                            background: '#f5f5f5',
                            padding: 12,
                            maxHeight: 400,
                            overflow: 'auto',
                            fontSize: 12
                        }}>
              {JSON.stringify(res, null, 2)}
            </pre>
                    </div>
                )
            })
            setPlaybackOpen(false)
        } catch (e) {
            if (!e?.errorFields) message.error('回放失败')
        }
    }

    const handleOpenExport = async () => {
        try {
            setExportLoading(true)
            // 先取项目编码（可空）
            const values = await exportForm.validateFields().catch(() => ({}))
            const res = await mockPlatformApi.recordings.export(detail.recordingCode, values?.projectCode)
            const drafts = Array.isArray(res?.drafts) ? res.drafts : []
            setExportDrafts(drafts)
            setSelectedDrafts(drafts.map(d => d.mockCode || d.id))
            setExportOpen(true)
        } catch (e) {
            if (!e?.errorFields) message.error('生成草稿失败')
        } finally {
            setExportLoading(false)
        }
    }

    const handleConfirmImport = async () => {
        try {
            const values = await exportForm.validateFields().catch(() => ({}))
            const codes = selectedDrafts
            if (codes.length === 0) {
                message.warning('请至少选择一个 Mock')
                return
            }
            const res = await mockPlatformApi.recordings.import(
                detail.recordingCode,
                values?.envCode,
                values?.projectCode
            )
            message.success(`已导入 ${res?.imported || codes.length} 个 Mock`)
            setExportOpen(false)
            loadRecordings()
        } catch (e) {
            message.error('导入失败：' + (e?.message || '未知错误'))
        }
    }

    const columns = [
        {
            title: '编码', dataIndex: 'recordingCode', key: 'recordingCode', width: 180,
            render: (v) => <Text code>{v}</Text>
        },
        {title: '名称', dataIndex: 'recordingName', key: 'recordingName', width: 200},
        {
            title: '状态', dataIndex: 'recordStatus', key: 'recordStatus', width: 110,
            render: (v) => <Tag color={STATUS_COLORS[v]}>{v}</Tag>
        },
        {title: '请求数', dataIndex: 'totalRequests', key: 'totalRequests', width: 90, align: 'center'},
        {
            title: '目标 URL', dataIndex: 'targetUrl', key: 'targetUrl', ellipsis: true,
            render: (v) => v ? <Text copyable={{text: v}}>{v}</Text> : '-'
        },
        {title: '开始时间', dataIndex: 'startTime', key: 'startTime', width: 170},
        {title: '结束时间', dataIndex: 'endTime', key: 'endTime', width: 170},
        {
            title: '操作', key: 'action', width: 280, fixed: 'right',
            render: (_, record) => (
                <Space size="small">
                    <Button size="small" type="link" icon={<VideoCameraOutlined/>}
                            onClick={() => handleDetail(record)}>详情</Button>
                    {record.recordStatus === 'RECORDING' && (
                        <Button size="small" type="link" danger icon={<StopOutlined/>}
                                onClick={() => handleStop(record.recordingCode)}>停止</Button>
                    )}
                    {record.recordStatus === 'STOPPED' && (
                        <Button size="small" type="link" icon={<ImportOutlined/>}
                                onClick={() => handleImport(record)}>导入为 Mock</Button>
                    )}
                    <Popconfirm title={`删除录制 ${record.recordingCode}?`}
                                onConfirm={() => handleDelete(record.recordingCode)}>
                        <Button size="small" type="link" danger icon={<DeleteOutlined/>}/>
                    </Popconfirm>
                </Space>
            )
        },
    ]

    return (
        <div>
            <Alert type="info" showIcon style={{marginBottom: 16}}
                   message="流量录制与回放"
                   description={
                       <div>
                           <div>• 开启录制后，所有 <Text code>/api/mock/&#123;envCode&#125;/**</Text> 请求会被捕获到数据库
                           </div>
                           <div>• 停止录制后，可一键转为 Mock endpoints（用于离线测试）</div>
                           <div>• 支持回放录制到目标服务（验证录制有效 / 跨环境验证）</div>
                       </div>
                   }
            />

            <Space style={{marginBottom: 16}}>
                <Button icon={<ReloadOutlined/>} onClick={loadRecordings}>刷新</Button>
                <Button type="primary" icon={<VideoCameraOutlined/>}
                        onClick={() => setStartOpen(true)}>开启录制</Button>
            </Space>

            <Table
                dataSource={recordings}
                columns={columns}
                rowKey="id"
                loading={loading}
                pagination={{pageSize: 10}}
                scroll={{x: 1300}}
                size="small"
            />

            <Modal
                title="开启录制"
                open={startOpen}
                onCancel={() => setStartOpen(false)}
                onOk={handleStart}
                okText="开启" cancelText="取消"
            >
                <Form form={form} layout="vertical" style={{marginTop: 16}}>
                    <Form.Item name="recordingCode" label="录制编码" rules={[{required: true}]}>
                        <Input placeholder="prod-trace-2026-06"/>
                    </Form.Item>
                    <Form.Item name="recordingName" label="录制名称" rules={[{required: true}]}>
                        <Input placeholder="生产环境订单流程"/>
                    </Form.Item>
                    <Form.Item name="targetUrl" label="目标服务地址">
                        <Input placeholder="http://prod-api.example.com"/>
                    </Form.Item>
                </Form>
            </Modal>

            <Drawer
                title={<Space><VideoCameraOutlined/>录制详情: {detail?.recordingCode}</Space>}
                open={detailOpen}
                onClose={() => setDetailOpen(false)}
                width={800}
            >
                {detail && (
                    <div>
                        <Descriptions column={2} size="small" bordered style={{marginBottom: 16}}>
                            <Descriptions.Item label="编码">{detail.recordingCode}</Descriptions.Item>
                            <Descriptions.Item label="名称">{detail.recordingName}</Descriptions.Item>
                            <Descriptions.Item label="状态">
                                <Tag color={STATUS_COLORS[detail.recordStatus]}>{detail.recordStatus}</Tag>
                            </Descriptions.Item>
                            <Descriptions.Item label="请求数">{detail.totalRequests}</Descriptions.Item>
                            <Descriptions.Item label="目标 URL" span={2}>
                                <Text copyable>{detail.targetUrl}</Text>
                            </Descriptions.Item>
                            <Descriptions.Item label="开始">{detail.startTime}</Descriptions.Item>
                            <Descriptions.Item label="结束">{detail.endTime || '(录制中)'}</Descriptions.Item>
                        </Descriptions>

                        <Space style={{marginBottom: 16}}>
                            <Button icon={<PlayCircleOutlined/>} onClick={() => setPlaybackOpen(true)}
                                    disabled={detail.recordStatus !== 'STOPPED'}>回放</Button>
                            <Button icon={<ExportOutlined/>} onClick={handleOpenExport} loading={exportLoading}
                                    disabled={detail.recordStatus !== 'STOPPED'}>导出 Mock 草稿</Button>
                            <Button type="primary" icon={<ImportOutlined/>} onClick={() => handleImport(detail)}
                                    disabled={detail.recordStatus !== 'STOPPED'}>导入为 Mock</Button>
                        </Space>

                        <Text strong>请求列表 (共 {detailRequests.length} 条):</Text>
                        <Table
                            style={{marginTop: 8}}
                            dataSource={detailRequests}
                            columns={[
                                {title: '#', dataIndex: 'sequence', key: 'sequence', width: 50},
                                {
                                    title: '方法', dataIndex: 'requestMethod', key: 'requestMethod', width: 80,
                                    render: (v) => <Tag color="blue">{v}</Tag>
                                },
                                {
                                    title: 'URL', dataIndex: 'requestUrl', key: 'requestUrl', ellipsis: true,
                                    render: (v) => <Text code>{v}</Text>
                                },
                                {
                                    title: '响应', dataIndex: 'responseStatus', key: 'responseStatus', width: 80,
                                    render: (v) => <Tag color={v >= 400 ? 'red' : 'green'}>{v}</Tag>
                                },
                                {
                                    title: '耗时', dataIndex: 'responseTime', key: 'responseTime', width: 80,
                                    render: (v) => `${v || 0}ms`
                                },
                            ]}
                            pagination={{pageSize: 10}} size="small" rowKey="id"
                        />
                    </div>
                )}
            </Drawer>

            <Modal title="回放录制" open={playbackOpen}
                   onCancel={() => setPlaybackOpen(false)} onOk={handlePlayback}>
                <Form form={playbackForm} layout="vertical" style={{marginTop: 16}}>
                    <Form.Item name="targetUrl" label="目标服务地址" rules={[{required: true}]}>
                        <Input placeholder="http://staging-api.example.com"/>
                    </Form.Item>
                </Form>
            </Modal>

            <Modal title="导出 Mock 草稿" open={exportOpen}
                   onCancel={() => setExportOpen(false)} onOk={handleConfirmImport}
                   okText={`导入 ${selectedDrafts.length} 个`} width={900}>
                <Form form={exportForm} layout="vertical" style={{marginTop: 8}}>
                    <Space size={16} style={{width: '100%'}}>
                        <Form.Item name="projectCode" label="项目编码" style={{flex: 1, marginBottom: 8}}>
                            <Input placeholder="default"/>
                        </Form.Item>
                        <Form.Item name="envCode" label="目标环境" style={{flex: 1, marginBottom: 8}}>
                            <Input placeholder="default"/>
                        </Form.Item>
                    </Space>
                </Form>
                <Alert type="info" showIcon style={{marginBottom: 12}}
                       message={`从录制中识别出 ${exportDrafts.length} 个去重后的 Mock endpoint，已默认全选`}/>
                <Table
                    size="small"
                    dataSource={exportDrafts}
                    rowKey={r => r.mockCode || r.id}
                    pagination={{pageSize: 8}}
                    scroll={{y: 300}}
                    rowSelection={{
                        selectedRowKeys: selectedDrafts,
                        onChange: (keys) => setSelectedDrafts(keys),
                    }}
                    columns={[
                        {title: '方法', dataIndex: 'method', width: 80,
                            render: v => <Tag color="blue">{v}</Tag>},
                        {title: 'URL 模式', dataIndex: 'urlPattern', ellipsis: true,
                            render: v => <Text code>{v}</Text>},
                        {title: '状态码', dataIndex: 'statusCode', width: 80},
                        {title: '响应模板', dataIndex: 'responseTemplate', ellipsis: true,
                            render: v => <Text type="secondary" style={{fontSize: 11}}>{(v || '').slice(0, 80)}</Text>},
                        {title: '请求数', dataIndex: 'requestCount', width: 80, align: 'center'},
                    ]}
                />
            </Modal>
        </div>
    )
}
