import {useState} from 'react'
import {
    Alert,
    Button,
    Card,
    Col,
    Descriptions,
    Form,
    Input,
    message,
    Modal,
    Row,
    Select,
    Space,
    Tabs,
    Tag,
    Typography
} from 'antd'
import {CodeOutlined, FileTextOutlined, ImportOutlined, ThunderboltOutlined} from '@ant-design/icons'
import {mockPlatformApi} from '@/services/api'

const {TextArea} = Input
const {Text, Paragraph} = Typography

/**
 * Curl 一键导入对话框
 * - 粘贴 curl → 自动解析为 method/url/headers/body
 * - "直接执行"：先试跑（不落库）
 * - "导入为测试用例"：落库为 MockTestCase
 * - "导入为 Mock 端点"：落库为 MockEndpoint
 * - 复用 MockPlatformController /api/mock-platform/curl/* 端点
 */
export default function CurlImportModal({open, onClose, onSuccess, envOptions = []}) {
    const [curlText, setCurlText] = useState('')
    const [parseResult, setParseResult] = useState(null)
    const [parseError, setParseError] = useState(null)
    const [running, setRunning] = useState(false)
    const [runResult, setRunResult] = useState(null)
    const [importing, setImporting] = useState(false)
    const [activeTab, setActiveTab] = useState('import')
    const [caseForm] = Form.useForm()
    const [epForm] = Form.useForm()

    const reset = () => {
        setCurlText('')
        setParseResult(null)
        setParseError(null)
        setRunResult(null)
        caseForm.resetFields()
        epForm.resetFields()
    }

    const handleClose = () => {
        reset()
        onClose()
    }

    const handleParse = async () => {
        if (!curlText.trim()) {
            message.warning('请先粘贴 curl 命令');
            return
        }
        setParseError(null)
        setParseResult(null)
        setRunResult(null)
        try {
            const res = await mockPlatformApi.curl.parse(curlText)
            if (res.success) {
                setParseResult(res.data)
                // 自动预填表单
                caseForm.setFieldsValue({
                    caseName: `from-curl-${(res.data?.method || 'GET')}-${(res.data?.path || '').replace(/\W+/g, '-')}`,
                    caseGroup: 'imported',
                    envCode: envOptions[0] || 'default',
                })
                epForm.setFieldsValue({
                    mockName: `mock-${(res.data?.method || 'GET')}-${(res.data?.path || '').replace(/\W+/g, '-')}`,
                    envCode: envOptions[0] || 'default',
                    projectCode: 'default',
                    responseTemplate: '{\n  "code": 0,\n  "message": "ok",\n  "data": {}\n}',
                })
            } else {
                setParseError(res.message || '解析失败')
            }
        } catch (e) {
            setParseError(String(e?.message || e))
        }
    }

    const handleRun = async () => {
        if (!curlText.trim()) {
            message.warning('请先粘贴 curl 命令');
            return
        }
        setRunning(true)
        setRunResult(null)
        try {
            const res = await mockPlatformApi.curl.run(curlText)
            setRunResult(res)
        } catch (e) {
            setRunResult({success: false, error: String(e?.message || e)})
        } finally {
            setRunning(false)
        }
    }

    const handleImportAsCase = async () => {
        if (!parseResult) {
            message.warning('请先解析');
            return
        }
        let values
        try {
            values = await caseForm.validateFields()
        } catch {
            return
        }
        setImporting(true)
        try {
            const res = await mockPlatformApi.curl.importAsCase({
                curl: curlText,
                caseName: values.caseName,
                caseGroup: values.caseGroup,
                envCode: values.envCode,
            })
            if (res.success) {
                message.success(`已导入测试用例: ${res.caseCode}`)
                onSuccess && onSuccess('case', res)
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

    const handleImportAsEndpoint = async () => {
        if (!parseResult) {
            message.warning('请先解析');
            return
        }
        let values
        try {
            values = await epForm.validateFields()
        } catch {
            return
        }
        setImporting(true)
        try {
            const res = await mockPlatformApi.curl.importAsEndpoint({
                curl: curlText,
                mockName: values.mockName,
                envCode: values.envCode,
                projectCode: values.projectCode,
                responseTemplate: values.responseTemplate,
            })
            if (res.success) {
                message.success(`已导入 Mock 端点: ${res.mockCode}`)
                onSuccess && onSuccess('endpoint', res)
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

    return (
        <Modal
            title={<Space><CodeOutlined/> 粘贴 curl 一键导入</Space>}
            open={open}
            onCancel={handleClose}
            footer={null}
            width={900}
            destroyOnClose
        >
            <Paragraph type="secondary" style={{marginBottom: 12}}>
                从浏览器 DevTools / Postman / 终端复制 curl 命令，粘贴到下方即可解析、试跑、入库。
            </Paragraph>

            <TextArea
                value={curlText}
                onChange={(e) => setCurlText(e.target.value)}
                placeholder={`curl -X POST 'https://api.example.com/v1/users/login' \\\n  -H 'Content-Type: application/json' \\\n  -H 'Authorization: Bearer xxx' \\\n  -d '{"username":"alice","password":"123456"}'`}
                autoSize={{minRows: 4, maxRows: 10}}
                style={{fontFamily: 'Menlo, Monaco, monospace', fontSize: 12}}
            />

            <Space style={{marginTop: 12}}>
                <Button icon={<FileTextOutlined/>} onClick={handleParse} type="primary" ghost>
                    解析
                </Button>
                <Button icon={<ThunderboltOutlined/>} onClick={handleRun} loading={running}>
                    直接执行（试跑）
                </Button>
            </Space>

            {parseError && (
                <Alert type="error" showIcon style={{marginTop: 12}} message="解析失败" description={parseError}/>
            )}

            {parseResult && (
                <Card size="small" style={{marginTop: 16}} title="解析结果">
                    <Descriptions column={1} size="small" bordered>
                        <Descriptions.Item label="Method">
                            <Tag color={methodColor(parseResult.method)}>{parseResult.method}</Tag>
                        </Descriptions.Item>
                        <Descriptions.Item label="URL">
                            <Text code copyable>{parseResult.url}</Text>
                        </Descriptions.Item>
                        <Descriptions.Item label="Path">
                            <Text code>{parseResult.path}</Text>
                        </Descriptions.Item>
                        {parseResult.query && (
                            <Descriptions.Item label="Query">
                                <Text code>{parseResult.query}</Text>
                            </Descriptions.Item>
                        )}
                        <Descriptions.Item label="Headers">
                            {Object.keys(parseResult.headers || {}).length === 0
                                ? <Text type="secondary">(无)</Text>
                                : (
                                    <div>
                                        {Object.entries(parseResult.headers).map(([k, v]) => (
                                            <div key={k}><Text strong>{k}:</Text> <Text code>{v}</Text></div>
                                        ))}
                                    </div>
                                )}
                        </Descriptions.Item>
                        {parseResult.body && (
                            <Descriptions.Item label="Body">
                <pre style={{
                    background: '#f5f5f5',
                    padding: 8,
                    margin: 0,
                    fontSize: 12,
                    maxHeight: 200,
                    overflow: 'auto'
                }}>
                  {parseResult.body}
                </pre>
                            </Descriptions.Item>
                        )}
                    </Descriptions>
                </Card>
            )}

            {runResult && (
                <Card size="small" style={{marginTop: 16}} title={
                    <Space>
                        <ThunderboltOutlined/>
                        <span>试跑结果</span>
                        {runResult.success
                            ? <Tag color="green">{runResult.status} · {runResult.durationMs}ms
                                · {runResult.bodySize}B</Tag>
                            : <Tag color="red">FAILED</Tag>}
                    </Space>
                }>
                    {runResult.error && <Alert type="error" showIcon message={runResult.error}/>}
                    {runResult.body != null && (
                        <pre style={{
                            background: '#f5f5f5',
                            padding: 8,
                            margin: 0,
                            fontSize: 12,
                            maxHeight: 240,
                            overflow: 'auto'
                        }}>
              {runResult.body}
            </pre>
                    )}
                </Card>
            )}

            {parseResult && (
                <Tabs
                    activeKey={activeTab}
                    onChange={setActiveTab}
                    style={{marginTop: 16}}
                    items={[
                        {
                            key: 'import',
                            label: <span><ImportOutlined/> 导入为测试用例</span>,
                            children: (
                                <Form form={caseForm} layout="vertical">
                                    <Row gutter={16}>
                                        <Col span={12}>
                                            <Form.Item name="caseName" label="用例名称" rules={[{required: true}]}>
                                                <Input/>
                                            </Form.Item>
                                        </Col>
                                        <Col span={6}>
                                            <Form.Item name="caseGroup" label="用例分组">
                                                <Input/>
                                            </Form.Item>
                                        </Col>
                                        <Col span={6}>
                                            <Form.Item name="envCode" label="环境">
                                                {envOptions.length > 0
                                                    ? <Select options={envOptions.map(e => ({value: e, label: e}))}/>
                                                    : <Input/>}
                                            </Form.Item>
                                        </Col>
                                    </Row>
                                    <Button type="primary" onClick={handleImportAsCase} loading={importing}
                                            icon={<ImportOutlined/>}>
                                        保存为测试用例
                                    </Button>
                                </Form>
                            ),
                        },
                        {
                            key: 'mock',
                            label: <span><CodeOutlined/> 导入为 Mock 端点</span>,
                            children: (
                                <Form form={epForm} layout="vertical">
                                    <Row gutter={16}>
                                        <Col span={12}>
                                            <Form.Item name="mockName" label="Mock 名称" rules={[{required: true}]}>
                                                <Input/>
                                            </Form.Item>
                                        </Col>
                                        <Col span={6}>
                                            <Form.Item name="envCode" label="环境">
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
                                    </Row>
                                    <Form.Item name="responseTemplate" label="响应模板 (JSON)">
                                        <TextArea autoSize={{minRows: 3, maxRows: 8}}
                                                  style={{fontFamily: 'Menlo, Monaco, monospace'}}/>
                                    </Form.Item>
                                    <Button type="primary" onClick={handleImportAsEndpoint} loading={importing}
                                            icon={<CodeOutlined/>}>
                                        保存为 Mock 端点
                                    </Button>
                                </Form>
                            ),
                        },
                    ]}
                />
            )}
        </Modal>
    )
}

function methodColor(method) {
    return {GET: 'blue', POST: 'green', PUT: 'orange', DELETE: 'red', PATCH: 'purple'}[method] || 'default'
}
