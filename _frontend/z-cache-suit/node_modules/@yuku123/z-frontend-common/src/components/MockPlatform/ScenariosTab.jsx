import {useEffect, useState} from 'react'
import {Alert, Button, Col, Form, Input, message, Modal, Popconfirm, Row, Space, Table, Tag, Typography} from 'antd'
import {
    DeleteOutlined,
    EditOutlined,
    NodeIndexOutlined,
    PlusOutlined,
    ReloadOutlined,
    UndoOutlined
} from '@ant-design/icons'
import {mockPlatformApi} from '@/services/api'

const {Text} = Typography

/**
 * 场景状态机 Tab - 管理 Scenarios（订单流程、用户登录流程等状态机驱动的 Mock 集合）
 */
export default function ScenariosTab() {
    const [scenarios, setScenarios] = useState([])
    const [loading, setLoading] = useState(false)
    const [modalOpen, setModalOpen] = useState(false)
    const [editing, setEditing] = useState(null)
    const [instancesOpen, setInstancesOpen] = useState(false)
    const [instances, setInstances] = useState(null)
    const [form] = Form.useForm()

    useEffect(() => {
        loadScenarios()
    }, [])

    const loadScenarios = async () => {
        setLoading(true)
        try {
            const res = await mockPlatformApi.scenarios.list()
            setScenarios(Array.isArray(res) ? res : [])
        } catch (e) {
            message.warning('加载场景列表失败')
        } finally {
            setLoading(false)
        }
    }

    const handleCreate = () => {
        setEditing(null)
        form.resetFields()
        form.setFieldsValue({initialState: 'started'})
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
            let states
            try {
                states = values.statesJson ? JSON.parse(values.statesJson) : null
            } catch (e) {
                message.error('状态列表 JSON 格式错误');
                return
            }
            const payload = {...values, states}
            delete payload.statesJson
            if (editing) {
                await mockPlatformApi.scenarios.update(editing.scenarioCode, payload)
                message.success('更新成功')
            } else {
                await mockPlatformApi.scenarios.create(payload)
                message.success('创建成功')
            }
            setModalOpen(false)
            loadScenarios()
        } catch (e) {
            if (!e?.errorFields) message.error('保存失败')
        }
    }

    const handleDelete = async (code) => {
        try {
            await mockPlatformApi.scenarios.delete(code)
            message.success('删除成功')
            loadScenarios()
        } catch (e) {
            message.error('删除失败')
        }
    }

    const handleReset = async (code) => {
        try {
            await mockPlatformApi.scenarios.reset(code)
            message.success('已重置所有实例状态')
        } catch (e) {
            message.error('重置失败')
        }
    }

    const handleShowInstances = async (code) => {
        try {
            const res = await mockPlatformApi.scenarios.instances(code)
            setInstances({code, list: res})
            setInstancesOpen(true)
        } catch (e) {
            message.error('加载失败')
        }
    }

    const columns = [
        {
            title: '场景编码', dataIndex: 'scenarioCode', key: 'scenarioCode', width: 160,
            render: (v) => <Text code>{v}</Text>
        },
        {title: '场景名称', dataIndex: 'scenarioName', key: 'scenarioName', width: 200},
        {
            title: '初始状态', dataIndex: 'initialState', key: 'initialState', width: 140,
            render: (v) => <Tag color="blue">{v}</Tag>
        },
        {
            title: '状态列表', dataIndex: 'states', key: 'states',
            render: (v) => {
                if (!v) return '-'
                try {
                    const arr = typeof v === 'string' ? JSON.parse(v) : v
                    return Array.isArray(arr) ? arr.map(s => <Tag key={s}>{s}</Tag>) : '-'
                } catch {
                    return '-'
                }
            }
        },
        {
            title: '描述', dataIndex: 'description', key: 'description', width: 240,
            render: (v) => v || '-'
        },
        {
            title: '操作', key: 'action', width: 280, fixed: 'right',
            render: (_, record) => (
                <Space size="small">
                    <Button size="small" type="link" icon={<NodeIndexOutlined/>}
                            onClick={() => handleShowInstances(record.scenarioCode)}>实例</Button>
                    <Button size="small" type="link" icon={<UndoOutlined/>}
                            onClick={() => handleReset(record.scenarioCode)}>重置</Button>
                    <Button size="small" type="link" icon={<EditOutlined/>}
                            onClick={() => handleEdit(record)}/>
                    <Popconfirm title={`删除场景 ${record.scenarioCode}?`}
                                onConfirm={() => handleDelete(record.scenarioCode)}>
                        <Button size="small" type="link" danger icon={<DeleteOutlined/>}/>
                    </Popconfirm>
                </Space>
            )
        },
    ]

    return (
        <div>
            <Alert type="info" showIcon style={{marginBottom: 16}}
                   message="场景状态机 (Scenario)"
                   description="用状态机驱动 Mock 集合，模拟完整业务流程（如：未登录→登录中→已登录→下单→已支付→完成）。实例通过 X-Session-Id 或 X-Trace-Id 区分，每个实例独立维护当前状态。"
            />

            <Space style={{marginBottom: 16}}>
                <Button icon={<ReloadOutlined/>} onClick={loadScenarios}>刷新</Button>
                <Button type="primary" icon={<PlusOutlined/>} onClick={handleCreate}>新建场景</Button>
            </Space>

            <Table
                dataSource={scenarios}
                columns={columns}
                rowKey="id"
                loading={loading}
                pagination={{pageSize: 10}}
                scroll={{x: 1100}}
                size="small"
            />

            <Modal
                title={editing ? `编辑场景: ${editing.scenarioCode}` : '新建场景'}
                open={modalOpen}
                onCancel={() => setModalOpen(false)}
                onOk={handleSave}
                width={650}
            >
                <Form form={form} layout="vertical" style={{marginTop: 16}}>
                    <Row gutter={16}>
                        <Col span={12}>
                            <Form.Item name="scenarioCode" label="场景编码" rules={[{required: true}]}>
                                <Input placeholder="user-login-flow" disabled={!!editing}/>
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="scenarioName" label="场景名称">
                                <Input placeholder="用户登录流程"/>
                            </Form.Item>
                        </Col>
                    </Row>
                    <Form.Item name="initialState" label="初始状态" rules={[{required: true}]}>
                        <Input placeholder="started"/>
                    </Form.Item>
                    <Form.Item name="statesJson" label="状态列表 (JSON 数组)"
                               extra='例: ["started", "logged-in", "checkout", "paid", "completed"]'>
                        <Input.TextArea rows={3} placeholder='["started", "logged-in", "checkout", "paid"]'/>
                    </Form.Item>
                    <Form.Item name="description" label="描述">
                        <Input.TextArea rows={3} placeholder="场景的业务说明"/>
                    </Form.Item>
                </Form>
            </Modal>

            <Modal
                title={`场景实例: ${instances?.code}`}
                open={instancesOpen}
                onCancel={() => setInstancesOpen(false)}
                footer={<Button onClick={() => setInstancesOpen(false)}>关闭</Button>}
                width={700}
            >
                {instances && (
                    <Table
                        dataSource={Array.isArray(instances.list) ? instances.list : []}
                        columns={[
                            {title: '实例标识', dataIndex: 'instanceKey', key: 'instanceKey'},
                            {
                                title: '当前状态', dataIndex: 'currentState', key: 'currentState',
                                render: (v) => <Tag color="blue">{v}</Tag>
                            },
                            {title: '最后变更', dataIndex: 'lastUpdateTime', key: 'lastUpdateTime'},
                        ]}
                        rowKey={(r, i) => r.instanceKey || i}
                        pagination={false} size="small"
                    />
                )}
            </Modal>
        </div>
    )
}
