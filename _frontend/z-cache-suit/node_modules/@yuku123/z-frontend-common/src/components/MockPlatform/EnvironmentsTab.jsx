import {useEffect, useState} from 'react'
import {
    Alert,
    Button,
    Col,
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
    Tag,
    Typography
} from 'antd'
import {DeleteOutlined, EditOutlined, GlobalOutlined, PlusOutlined, ReloadOutlined} from '@ant-design/icons'
import {mockPlatformApi} from '@/services/api'

const {Text} = Typography

const ENV_TYPES = [
    {value: 'MOCK', label: 'MOCK (纯 Mock 拦截)', color: 'green'},
    {value: 'REAL', label: 'REAL (全部代理到真实)', color: 'blue'},
    {value: 'MIXED', label: 'MIXED (匹配失败时转发)', color: 'orange'},
]

const ENV_TYPE_COLORS = {MOCK: 'green', REAL: 'blue', MIXED: 'orange'}

export default function EnvironmentsTab({onEnvChange}) {
    const [envs, setEnvs] = useState([])
    const [loading, setLoading] = useState(false)
    const [modalOpen, setModalOpen] = useState(false)
    const [editing, setEditing] = useState(null)
    const [form] = Form.useForm()

    useEffect(() => {
        loadEnvs()
    }, [])

    const loadEnvs = async () => {
        setLoading(true)
        try {
            const res = await mockPlatformApi.environments.list()
            setEnvs(Array.isArray(res) ? res : [])
        } catch (e) {
            message.warning('加载环境列表失败: ' + e?.message)
        } finally {
            setLoading(false)
        }
    }

    const handleCreate = () => {
        setEditing(null)
        form.resetFields()
        form.setFieldsValue({envType: 'MOCK', isDefault: 0, mockPriority: 1})
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
                await mockPlatformApi.environments.update(editing.envCode, values)
                message.success('更新成功')
            } else {
                await mockPlatformApi.environments.create(values)
                message.success('创建成功')
            }
            setModalOpen(false)
            loadEnvs()
        } catch (e) {
            if (e?.errorFields) return
            message.error('保存失败')
        }
    }

    const handleDelete = async (envCode) => {
        try {
            await mockPlatformApi.environments.delete(envCode)
            message.success('删除成功')
            loadEnvs()
        } catch (e) {
            message.error('删除失败: ' + (e?.message || ''))
        }
    }

    const columns = [
        {
            title: '环境编码', dataIndex: 'envCode', key: 'envCode', width: 140,
            render: (v) => <Text code>{v}</Text>
        },
        {title: '环境名称', dataIndex: 'envName', key: 'envName', width: 160},
        {
            title: '类型', dataIndex: 'envType', key: 'envType', width: 240,
            render: (v) => <Tag color={ENV_TYPE_COLORS[v] || 'default'}>{v}</Tag>
        },
        {
            title: '真实服务地址', dataIndex: 'baseUrl', key: 'baseUrl',
            render: (v) => v ? <Text copyable>{v}</Text> : <Text type="secondary">-</Text>
        },
        {
            title: '默认', dataIndex: 'isDefault', key: 'isDefault', width: 90, align: 'center',
            render: (v) => v === 1 ? <Tag color="gold">默认</Tag> : '-'
        },
        {
            title: '描述', dataIndex: 'description', key: 'description', width: 220,
            render: (v) => v || '-'
        },
        {
            title: '操作', key: 'action', width: 180,
            render: (_, record) => (
                <Space size="small">
                    <Button size="small" type="link" icon={<GlobalOutlined/>}
                            onClick={() => {
                                if (onEnvChange) onEnvChange(record.envCode)
                            }}>切换</Button>
                    <Button size="small" type="link" icon={<EditOutlined/>} onClick={() => handleEdit(record)}/>
                    {record.isDefault !== 1 && (
                        <Popconfirm title={`确认删除环境 ${record.envCode}?`}
                                    onConfirm={() => handleDelete(record.envCode)}>
                            <Button size="small" type="link" danger icon={<DeleteOutlined/>}/>
                        </Popconfirm>
                    )}
                </Space>
            )
        },
    ]

    return (
        <div>
            <Alert type="info" showIcon style={{marginBottom: 16}}
                   message="环境隔离 - 控制 Mock 与真实服务的切换"
                   description={
                       <div>
                           <div><Tag color="green">MOCK</Tag> 完全拦截，仅返回 Mock 数据</div>
                           <div><Tag color="blue">REAL</Tag> 全部代理到 baseUrl（适合压测、回归测试）</div>
                           <div><Tag color="orange">MIXED</Tag> Mock 优先，未匹配时转发到 baseUrl（开发联调）</div>
                       </div>
                   }
            />

            <Space style={{marginBottom: 16}}>
                <Button icon={<ReloadOutlined/>} onClick={loadEnvs}>刷新</Button>
                <Button type="primary" icon={<PlusOutlined/>} onClick={handleCreate}>新建环境</Button>
            </Space>

            <Table
                dataSource={envs}
                columns={columns}
                rowKey="id"
                loading={loading}
                pagination={{pageSize: 10}}
                size="small"
            />

            <Modal
                title={editing ? `编辑环境: ${editing.envCode}` : '新建环境'}
                open={modalOpen}
                onCancel={() => setModalOpen(false)}
                onOk={handleSave}
                width={650}
            >
                <Form form={form} layout="vertical" style={{marginTop: 16}}>
                    <Row gutter={16}>
                        <Col span={12}>
                            <Form.Item name="envCode" label="环境编码" rules={[{required: true}]}>
                                <Input placeholder="dev / staging / prod" disabled={!!editing}/>
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="envName" label="环境名称" rules={[{required: true}]}>
                                <Input placeholder="开发环境"/>
                            </Form.Item>
                        </Col>
                    </Row>
                    <Form.Item name="envType" label="环境类型" rules={[{required: true}]}>
                        <Select options={ENV_TYPES}/>
                    </Form.Item>
                    <Form.Item name="baseUrl" label="真实服务地址"
                               extra="REAL/MIXED 时使用。MIXED 时未匹配的请求会转发到这里">
                        <Input placeholder="https://api.staging.example.com"/>
                    </Form.Item>
                    <Row gutter={16}>
                        <Col span={12}>
                            <Form.Item name="mockPriority" label="Mock 优先级">
                                <InputNumber min={0} max={10} style={{width: '100%'}}/>
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="isDefault" label="是否默认" valuePropName="checked">
                                <Switch checkedChildren="是" unCheckedChildren="否"/>
                            </Form.Item>
                        </Col>
                    </Row>
                    <Form.Item name="description" label="描述">
                        <Input.TextArea rows={3} placeholder="环境的用途说明"/>
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    )
}
