import {useEffect, useState} from 'react'
import {Alert, Badge, Button, Card, Col, Form, Input, message, Modal, Popconfirm, Row, Select, Space, Table, Tabs, Tag, Tooltip, Typography} from 'antd'
import {
    CheckCircleOutlined,
    CodeOutlined,
    CopyOutlined,
    DeleteOutlined,
    DownloadOutlined,
    ExportOutlined,
    FileTextOutlined,
    HistoryOutlined,
    ReloadOutlined
} from '@ant-design/icons'
import request from '../../utils/request'

const {Text, Paragraph} = Typography
const {TextArea} = Input

const STATUS_COLORS = {
    PENDING: 'default', GENERATING: 'processing', READY: 'success', FAILED: 'error',
}
const TYPE_COLORS = {
    Entity: 'blue', Mapper: 'cyan', Service: 'green', ServiceImpl: 'lime',
    Controller: 'orange', ReactPage: 'purple', File: 'default',
}

/**
 * z-lc 代码物化页面 (FEATURE006 T1).
 * <p>
 * 路径: /form/_materialize?appCode=xxx
 * <p>
 * 功能:
 * 1. 选择导出路径 + entity 范围 → 触发物化 (异步)
 * 2. 列出该 app 的历史物化批次 + 状态
 * 3. 选中某个 READY 批次 → 查看 diff (生成的文件清单)
 * 4. 单个文件 → 代码预览 + 复制到剪贴板
 */
export default function MaterializePage() {
    const params = new URLSearchParams(window.location.search)
    const initialAppCode = params.get('appCode') || 'demo'

    const [appCode] = useState(initialAppCode)
    const [batches, setBatches] = useState([])
    const [loading, setLoading] = useState(false)
    const [triggerOpen, setTriggerOpen] = useState(false)
    const [diffOpen, setDiffOpen] = useState(false)
    const [diffFiles, setDiffFiles] = useState([])
    const [selectedFile, setSelectedFile] = useState(null)
    const [entities, setEntities] = useState([])
    const [form] = Form.useForm()

    useEffect(() => {
        loadBatches()
        loadEntities()
    }, [appCode])

    const loadBatches = async () => {
        setLoading(true)
        try {
            const r = await request.get(`/lc/app/${appCode}/materialize/list`, {params: {limit: 50}})
            setBatches(r?.data || [])
        } catch (e) {
            message.warning('加载批次列表失败')
        } finally {
            setLoading(false)
        }
    }

    const loadEntities = async () => {
        try {
            const r = await request.get(`/lc/app/${appCode}/schema`)
            const list = r?.data?.entities || r?.data || []
            setEntities(list)
        } catch (e) {
            // ignore
        }
    }

    const handleTrigger = async () => {
        try {
            const values = await form.validateFields()
            const payload = {
                appCode,
                materializationPath: values.materializationPath,
                entityCodes: values.entityCodes?.length ? values.entityCodes : null,
                description: values.description,
                triggerSource: 'USER',
            }
            await request.post(`/lc/app/${appCode}/materialize`, payload)
            message.success('物化已触发，请稍后查看状态')
            setTriggerOpen(false)
            form.resetFields()
            loadBatches()
        } catch (e) {
            if (!e?.errorFields) message.error('触发失败：' + (e?.message || '未知错误'))
        }
    }

    const handleShowDiff = async (batch) => {
        try {
            const r = await request.get(`/lc/app/${appCode}/materialize/diff`, {params: {id: batch.id}})
            const files = r?.data || []
            setDiffFiles(files)
            setDiffOpen(true)
            setSelectedFile(null)
        } catch (e) {
            message.error('加载文件清单失败')
        }
    }

    const handleCopy = (text) => {
        try {
            navigator.clipboard?.writeText(text)
            message.success('已复制到剪贴板')
        } catch {
            message.warning('复制失败')
        }
    }

    const handleDownload = (file) => {
        const blob = new Blob([file.content], {type: 'text/plain;charset=utf-8'})
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = file.relativePath.split('/').pop()
        a.click()
        URL.revokeObjectURL(url)
    }

    const batchColumns = [
        {title: '批次号', dataIndex: 'exportVersion', width: 180, render: v => <Text code>{v}</Text>},
        {
            title: '状态', dataIndex: 'status', width: 100,
            render: v => <Badge status={STATUS_COLORS[v] === 'success' ? 'success' : STATUS_COLORS[v] === 'error' ? 'error' : 'processing'} text={v}/>
        },
        {title: '文件数', dataIndex: 'fileCount', width: 80, align: 'center', render: v => v || 0},
        {title: '来源', dataIndex: 'triggerSource', width: 80, render: v => <Tag>{v}</Tag>},
        {title: '路径', dataIndex: 'materializationPath', ellipsis: true, render: v => <Text type="secondary" style={{fontSize: 12}}>{v}</Text>},
        {title: '创建时间', dataIndex: 'createTime', width: 170},
        {
            title: '操作', key: 'action', width: 200, fixed: 'right',
            render: (_, r) => (
                <Space size="small">
                    <Button size="small" type="link" icon={<FileTextOutlined/>}
                            onClick={() => handleShowDiff(r)}
                            disabled={r.status !== 'READY' && r.status !== 'GENERATING'}>
                        查看文件
                    </Button>
                </Space>
            )
        },
    ]

    const fileColumns = [
        {title: '类型', dataIndex: 'type', width: 100, render: v => <Tag color={TYPE_COLORS[v] || 'default'}>{v}</Tag>},
        {title: 'Entity', dataIndex: 'entityCode', width: 120, render: v => v ? <Text code>{v}</Text> : '-'},
        {title: '相对路径', dataIndex: 'relativePath', ellipsis: true, render: v => <Text code style={{fontSize: 12}}>{v}</Text>},
        {title: '大小', dataIndex: 'sizeBytes', width: 90, render: v => `${(v/1024).toFixed(1)}KB`},
        {
            title: '操作', key: 'action', width: 200, fixed: 'right',
            render: (_, r) => (
                <Space size="small">
                    <Button size="small" type="link" onClick={() => setSelectedFile(r)}>预览</Button>
                    <Button size="small" type="link" icon={<CopyOutlined/>} onClick={() => handleCopy(r.content)}>复制</Button>
                    <Button size="small" type="link" icon={<DownloadOutlined/>} onClick={() => handleDownload(r)}>下载</Button>
                </Space>
            )
        },
    ]

    return (
        <div style={{padding: 16}}>
            <Card>
                <Alert type="info" showIcon icon={<CodeOutlined/>} style={{marginBottom: 16}}
                       message={`z-lc 代码物化 (FEATURE006 T1) — App: ${appCode}`}
                       description="将低代码定义的 entity/field 渲染为可编译的 Java (Entity/Mapper/Service/Controller) + React 页面代码。导出是异步的, 完成后可在此查看文件清单。"/>

                <Space style={{marginBottom: 16}}>
                    <Button icon={<ReloadOutlined/>} onClick={loadBatches}>刷新</Button>
                    <Button type="primary" icon={<ExportOutlined/>} onClick={() => setTriggerOpen(true)}>导出代码</Button>
                    <Button icon={<HistoryOutlined/>} onClick={() => message.info(`共 ${batches.length} 个历史批次`)}>历史</Button>
                </Space>

                <Table dataSource={batches} columns={batchColumns} rowKey="id" loading={loading}
                       pagination={{pageSize: 10}} size="small" scroll={{x: 1000}}/>
            </Card>

            <Modal title="触发代码物化" open={triggerOpen} onCancel={() => setTriggerOpen(false)} onOk={handleTrigger}
                   okText="开始导出" width={600}>
                <Form form={form} layout="vertical" style={{marginTop: 16}}
                      initialValues={{materializationPath: `app-${appCode}/v1`}}>
                    <Form.Item name="materializationPath" label="导出根路径 (相对沙箱默认根)"
                               rules={[{required: true, message: '请输入路径'}]}
                               extra="默认根: ${user.home}/z-lc-materialized. 如需写入绝对路径, 服务端需配置 z-lc.materialize.allow-absolute-path=true">
                        <Input placeholder={`app-${appCode}/v1`}/>
                    </Form.Item>
                    <Form.Item name="entityCodes" label="导出的 entity (留空 = 全量)">
                        <Select mode="multiple" placeholder="选择 entity" allowClear
                                options={entities.map(e => ({value: e.entityCode, label: `${e.entityCode} (${e.tableName})`}))}/>
                    </Form.Item>
                    <Form.Item name="description" label="备注">
                        <TextArea rows={2} placeholder="本次导出的业务说明"/>
                    </Form.Item>
                </Form>
            </Modal>

            <Modal title={`物化文件清单 (${diffFiles.length} 个)`} open={diffOpen}
                   onCancel={() => { setDiffOpen(false); setSelectedFile(null) }}
                   footer={<Button onClick={() => { setDiffOpen(false); setSelectedFile(null) }}>关闭</Button>}
                   width={selectedFile ? 1100 : 800}>
                {selectedFile ? (
                    <div>
                        <Space style={{marginBottom: 8}}>
                            <Button onClick={() => setSelectedFile(null)}>← 返回清单</Button>
                            <Text strong>{selectedFile.relativePath}</Text>
                            <Tag color={TYPE_COLORS[selectedFile.type] || 'default'}>{selectedFile.type}</Tag>
                            <Button size="small" icon={<CopyOutlined/>} onClick={() => handleCopy(selectedFile.content)}>复制</Button>
                            <Button size="small" icon={<DownloadOutlined/>} onClick={() => handleDownload(selectedFile)}>下载</Button>
                        </Space>
                        <pre style={{
                            background: '#1e1e1e', color: '#d4d4d4', padding: 16,
                            borderRadius: 8, maxHeight: 500, overflow: 'auto',
                            fontSize: 12, fontFamily: 'Menlo, Monaco, monospace',
                        }}>
                            {selectedFile.content}
                        </pre>
                    </div>
                ) : (
                    <Table dataSource={diffFiles} columns={fileColumns} rowKey="relativePath"
                           pagination={{pageSize: 20}} size="small" scroll={{x: 700}}/>
                )}
            </Modal>
        </div>
    )
}
