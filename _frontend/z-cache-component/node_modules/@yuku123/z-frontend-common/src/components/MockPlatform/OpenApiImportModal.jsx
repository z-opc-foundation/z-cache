import {useRef, useState} from 'react'
import {Alert, Button, Card, Col, Form, Input, message, Modal, Row, Select, Space, Table, Tabs, Tag, Upload} from 'antd'
import {
    CloudUploadOutlined,
    FileAddOutlined,
    FileTextOutlined,
    ImportOutlined,
    ThunderboltOutlined
} from '@ant-design/icons'
import {mockPlatformApi} from '@/services/api'

const {TextArea} = Input

/**
 * OpenAPI / Swagger / Postman 批量导入 Mock 端点
 *
 * 流程：上传/粘贴 → 解析 → 预览列表（可选择） → 批量入库
 */
export default function OpenApiImportModal({open, onClose, onSuccess, envOptions = []}) {
    const [activeTab, setActiveTab] = useState('paste')
    const [content, setContent] = useState('')
    const [parsed, setParsed] = useState(null)
    const [parseError, setParseError] = useState(null)
    const [parsing, setParsing] = useState(false)
    const [selectedRowKeys, setSelectedRowKeys] = useState([])
    const [importing, setImporting] = useState(false)
    const [form] = Form.useForm()
    const uploadRef = useRef(null)

    const reset = () => {
        setContent('')
        setParsed(null)
        setParseError(null)
        setSelectedRowKeys([])
        form.resetFields()
    }

    const handleClose = () => {
        reset()
        onClose()
    }

    const handleParse = async () => {
        if (!content.trim()) {
            message.warning('请先粘贴或上传 API 规约');
            return
        }
        setParseError(null)
        setParsing(true)
        try {
            const res = await mockPlatformApi.openapi.parse(content)
            if (res.success) {
                setParsed(res)
                setSelectedRowKeys((res.endpoints || []).map((_, i) => i))
                form.setFieldsValue({
                    envCode: envOptions[0] || 'default',
                    projectCode: 'default',
                    defaultResponse: '{\n  "code": 0,\n  "message": "ok",\n  "data": {}\n}',
                    defaultStatus: 200,
                    priority: 5,
                    tagPrefix: 'openapi-import',
                })
                message.success(`识别为 ${res.format}，共 ${res.count} 个端点`)
            } else {
                setParseError(res.message || '解析失败')
            }
        } catch (e) {
            setParseError(String(e?.message || e))
        } finally {
            setParsing(false)
        }
    }

    const handleLoadSample = async (format) => {
        try {
            const res = await mockPlatformApi.openapi.sample(format)
            if (res.success) {
                setContent(res.content)
                message.success(`已加载 ${res.format} 示例`)
            }
        } catch (e) {
            message.error('加载示例失败: ' + e.message)
        }
    }

    const handleFileUpload = (file) => {
        const reader = new FileReader()
        reader.onload = (e) => {
            setContent(String(e.target?.result || ''))
            message.success(`已读取 ${file.name}（${(file.size / 1024).toFixed(1)}KB）`)
        }
        reader.onerror = () => message.error('文件读取失败')
        reader.readAsText(file)
        return false  // 阻止 antd Upload 自动上传
    }

    const handleImport = async () => {
        if (!parsed) {
            message.warning('请先解析');
            return
        }
        let values
        try {
            values = await form.validateFields()
        } catch {
            return
        }
        setImporting(true)
        try {
            const selected = (parsed.endpoints || []).map((ep, i) => ({
                method: ep.method, path: ep.path
            })).filter((_, i) => selectedRowKeys.includes(i))
            const res = await mockPlatformApi.openapi.importBatch({
                content,
                selected,
                envCode: values.envCode,
                projectCode: values.projectCode,
                defaultResponse: values.defaultResponse,
                defaultStatus: values.defaultStatus,
                priority: values.priority,
                tagPrefix: values.tagPrefix,
            })
            if (res.success) {
                message.success(`已导入 ${res.imported} 个 Mock 端点${res.failed > 0 ? `，失败 ${res.failed}` : ''}`)
                onSuccess && onSuccess(res)
                handleClose()
            } else {
                message.error(res.message || '导入失败')
            }
        } catch (e) {
            message.error(String(e?.message || e))
        } finally {
            setImporting(false)
        }
    }

    const columns = [
        {
            title: 'Method', dataIndex: 'method', width: 90,
            render: (m) => <Tag color={methodColor(m)}>{m}</Tag>
        },
        {title: 'Path', dataIndex: 'path', width: 240, ellipsis: true},
        {title: 'Summary', dataIndex: 'summary', ellipsis: true},
        {
            title: 'Tags', dataIndex: 'tags', width: 160,
            render: (tags) => (tags || []).map(t => <Tag key={t}>{t}</Tag>)
        },
        {
            title: 'Params', dataIndex: 'parameters', width: 80,
            render: (p) => p?.length || 0,
            align: 'center'
        },
        {
            title: '示例', dataIndex: 'responseExample', width: 80,
            render: (s) => s ? <Tag color="green">有</Tag> : <Tag>无</Tag>
        },
    ]

    return (
        <Modal
            title={<Space><CloudUploadOutlined/> 批量导入 API 规约</Space>}
            open={open}
            onCancel={handleClose}
            footer={null}
            width={1100}
            destroyOnClose
        >
            <Tabs
                activeKey={activeTab}
                onChange={setActiveTab}
                items={[
                    {
                        key: 'paste',
                        label: <span><FileTextOutlined/> 粘贴 JSON</span>,
                        children: (
                            <div>
                                <TextArea
                                    value={content}
                                    onChange={(e) => setContent(e.target.value)}
                                    placeholder='粘贴 OpenAPI 3.x / Swagger 2.0 / Postman v2.1 JSON 内容…'
                                    autoSize={{minRows: 6, maxRows: 16}}
                                    style={{fontFamily: 'Menlo, Monaco, monospace', fontSize: 12}}
                                />
                                <Space style={{marginTop: 8}}>
                                    <Button size="small" onClick={() => handleLoadSample('openapi3')}>加载 OpenAPI 3
                                        示例</Button>
                                    <Button size="small" onClick={() => handleLoadSample('swagger2')}>加载 Swagger 2
                                        示例</Button>
                                    <Button size="small" onClick={() => handleLoadSample('postman')}>加载 Postman
                                        示例</Button>
                                </Space>
                            </div>
                        ),
                    },
                    {
                        key: 'upload',
                        label: <span><FileAddOutlined/> 上传文件</span>,
                        children: (
                            <Upload.Dragger
                                ref={uploadRef}
                                accept=".json,.yaml,.yml,application/json,text/yaml"
                                beforeUpload={handleFileUpload}
                                showUploadList={false}
                                style={{padding: 24}}
                            >
                                <p className="ant-upload-drag-icon">
                                    <CloudUploadOutlined style={{fontSize: 48, color: '#1890ff'}}/>
                                </p>
                                <p className="ant-upload-text">点击或拖拽 JSON / YAML 文件到此处</p>
                                <p className="ant-upload-hint">支持 OpenAPI 3.x / Swagger 2.0 / Postman v2.1</p>
                            </Upload.Dragger>
                        ),
                    },
                ]}
            />

            <Space style={{marginTop: 12}}>
                <Button type="primary" icon={<ThunderboltOutlined/>} onClick={handleParse} loading={parsing}>
                    解析
                </Button>
                {parsed && (
                    <Tag color="blue">{parsed.format}</Tag>
                )}
                {parsed && (
                    <Tag>共 {parsed.count} 个端点，已选 {selectedRowKeys.length}</Tag>
                )}
            </Space>

            {parseError && (
                <Alert type="error" showIcon style={{marginTop: 12}} message="解析失败" description={parseError}/>
            )}

            {parsed && (
                <Card size="small" style={{marginTop: 16}} title="预览（可勾选要导入的）">
                    <Table
                        size="small"
                        dataSource={parsed.endpoints || []}
                        columns={columns}
                        rowKey={(_, i) => i}
                        rowSelection={{
                            selectedRowKeys,
                            onChange: setSelectedRowKeys,
                        }}
                        pagination={{pageSize: 8}}
                        scroll={{y: 280}}
                    />
                </Card>
            )}

            {parsed && (
                <Card size="small" style={{marginTop: 16}} title="导入配置">
                    <Form form={form} layout="vertical">
                        <Row gutter={16}>
                            <Col span={6}>
                                <Form.Item name="envCode" label="环境" rules={[{required: true}]}>
                                    {envOptions.length > 0
                                        ? <Select options={envOptions.map(e => ({value: e, label: e}))}/>
                                        : <Input/>}
                                </Form.Item>
                            </Col>
                            <Col span={6}>
                                <Form.Item name="projectCode" label="项目">
                                    <Input/>
                                </Form.Item>
                            </Col>
                            <Col span={4}>
                                <Form.Item name="defaultStatus" label="默认状态码">
                                    <Input type="number"/>
                                </Form.Item>
                            </Col>
                            <Col span={4}>
                                <Form.Item name="priority" label="优先级">
                                    <Input type="number"/>
                                </Form.Item>
                            </Col>
                            <Col span={4}>
                                <Form.Item name="tagPrefix" label="Tag 前缀">
                                    <Input/>
                                </Form.Item>
                            </Col>
                        </Row>
                        <Form.Item name="defaultResponse" label="默认响应（无 example 时使用）">
                            <TextArea autoSize={{minRows: 3, maxRows: 6}}
                                      style={{fontFamily: 'Menlo, Monaco, monospace'}}/>
                        </Form.Item>
                        <Button
                            type="primary"
                            size="large"
                            icon={<ImportOutlined/>}
                            onClick={handleImport}
                            loading={importing}
                            disabled={selectedRowKeys.length === 0}
                        >
                            批量导入 {selectedRowKeys.length} 个端点
                        </Button>
                    </Form>
                </Card>
            )}
        </Modal>
    )
}

function methodColor(method) {
    return {GET: 'blue', POST: 'green', PUT: 'orange', DELETE: 'red', PATCH: 'purple'}[method] || 'default'
}
