import React, {useCallback, useEffect, useState} from 'react'
import {useParams, useSearchParams} from 'react-router-dom'
import {
    Alert,
    Button,
    Card,
    DatePicker,
    Empty,
    Form,
    Input,
    InputNumber,
    message,
    Modal,
    Select,
    Space,
    Spin,
    Switch,
    Table,
    Tag
} from 'antd'
import {DatabaseOutlined, DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined} from '@ant-design/icons'
import request from '../../utils/request'

const {Option} = Select

const FIELD_TYPE_INPUT = {
    STRING: {component: Input, props: {}},
    TEXT: {component: Input.TextArea, props: {rows: 3}},
    INT: {component: InputNumber, props: {}},
    LONG: {component: InputNumber, props: {}},
    DECIMAL: {component: InputNumber, props: {step: 0.01}},
    BOOLEAN: {component: Switch, props: {}},
    DATE: {component: DatePicker, props: {format: 'YYYY-MM-DD'}},
    DATETIME: {component: DatePicker, props: {format: 'YYYY-MM-DD HH:mm:ss', showTime: true}},
    JSON: {component: Input.TextArea, props: {rows: 3, placeholder: 'JSON 字符串'}},
    REF: {component: InputNumber, props: {}}
}

/**
 * LowCodeRuntime: 通用 CRUD 页面
 * <p>
 * 通过 GET  /api/lc/app/{appCode}/schema 取 entity 定义
 * 通过 POST /api/lc/runtime/{entityCode}/{list|get|create|update|delete} 走 CRUD
 */
export default function LowCodeRuntime() {
    const {entity: entityCodeParam} = useParams()
    const [searchParams] = useSearchParams()
    const appCode = searchParams.get('appCode') || 'demo'
    const tenantCode = searchParams.get('tenant') || localStorage.getItem('z_tenant') || 'default'

    const [schema, setSchema] = useState([])
    const [entity, setEntity] = useState(null)
    const [rows, setRows] = useState([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [size, setSize] = useState(10)
    const [loading, setLoading] = useState(false)
    const [modalOpen, setModalOpen] = useState(false)
    const [editing, setEditing] = useState(null) // null = create, { id } = update
    const [form] = Form.useForm()
    const [dictCache, setDictCache] = useState({}) // { dictCode: [{ value, label }] }

    const fetchSchema = useCallback(async () => {
        try {
            const list = await request.get(`/lc/app/${appCode}/schema`, {params: {tenantCode}})
            setSchema(Array.isArray(list) ? list : [])
            const e = (list || []).find(x => x.entityCode === entityCodeParam)
            setEntity(e || null)
        } catch (err) {
            message.error('加载 schema 失败: ' + (err?.message || '未知错误'))
            setSchema([])
            setEntity(null)
        }
    }, [appCode, tenantCode, entityCodeParam])

    const fetchDict = useCallback(async (dictCode) => {
        if (!dictCode || dictCache[dictCode]) return dictCache[dictCode] || []
        try {
            // 走 z-meta 字典接口: /dict/items/get?dictCode=xxx
            const arr = await request.get(`/dict/items/get`, {params: {dictCode}})
            const list = Array.isArray(arr) ? arr : []
            const normalized = list.map(it => ({
                value: it.itemValue ?? it.itemCode,
                label: it.itemName ?? it.itemLabel ?? it.itemCode
            }))
            setDictCache(prev => ({...prev, [dictCode]: normalized}))
            return normalized
        } catch (e) {
            // 字典不存在也不报错 — 业务表允许 dict 字段没值
            return []
        }
    }, [dictCache])

    const fetchList = useCallback(async () => {
        if (!entity) return
        setLoading(true)
        try {
            const res = await request.post(`/lc/runtime/${entity.entityCode}/list`, {
                tenantCode,
                appCode,
                entityCode: entity.entityCode,
                page, size
            })
            setRows(res?.records || [])
            setTotal(res?.total || 0)
        } catch (err) {
            message.error('查询失败: ' + (err?.message || '未知错误'))
            setRows([])
            setTotal(0)
        } finally {
            setLoading(false)
        }
    }, [entity, tenantCode, appCode, page, size])

    useEffect(() => {
        fetchSchema()
    }, [fetchSchema])
    useEffect(() => {
        fetchList()
    }, [fetchList])

    // 预取字典
    useEffect(() => {
        if (!entity || !entity.fields) return
        entity.fields.forEach(f => {
            if (f.dictCode) fetchDict(f.dictCode)
        })
    }, [entity, fetchDict])

    if (!entityCodeParam) {
        return <Empty description="缺少实体编码 (路径变量 :entity)"/>
    }

    if (!entity) {
        return (
            <Card title={<><DatabaseOutlined/> z-lc 运行时</>}>
                <Alert
                    type="warning"
                    showIcon
                    message={`实体 ${entityCodeParam} 未定义`}
                    description={`请先在 模型编辑器 (路径 /form/_model?appCode=${appCode}) 中定义实体, 然后刷新此页.`}
                />
            </Card>
        )
    }

    const fields = entity.fields || []

    const openCreate = () => {
        setEditing(null)
        form.resetFields()
        setModalOpen(true)
    }

    const openEdit = async (row) => {
        setEditing(row)
        try {
            const full = await request.post(`/lc/runtime/${entity.entityCode}/get`, {
                tenantCode, appCode, entityCode: entity.entityCode, id: row.id
            })
            form.resetFields()
            form.setFieldsValue(full || row)
        } catch (e) {
            // 回退到当前行
            form.resetFields()
            form.setFieldsValue(row)
        }
        setModalOpen(true)
    }

    const handleDelete = (row) => {
        Modal.confirm({
            title: '确定删除?',
            content: `id=${row.id}`,
            okType: 'danger',
            onOk: async () => {
                try {
                    await request.post(`/lc/runtime/${entity.entityCode}/delete`, {
                        tenantCode, appCode, entityCode: entity.entityCode, id: row.id
                    })
                    message.success('删除成功')
                    fetchList()
                } catch (e) {
                    message.error('删除失败: ' + (e?.message || '未知错误'))
                }
            }
        })
    }

    const handleSubmit = async () => {
        try {
            const values = await form.validateFields()
            const body = {
                tenantCode, appCode, entityCode: entity.entityCode, fieldValues: {...values}
            }
            if (editing) {
                body.fieldValues.id = editing.id
                await request.post(`/lc/runtime/${entity.entityCode}/update`, body)
                message.success('更新成功')
            } else {
                await request.post(`/lc/runtime/${entity.entityCode}/create`, body)
                message.success('创建成功')
            }
            setModalOpen(false)
            fetchList()
        } catch (e) {
            if (e?.errorFields) {
                message.error('请补全必填字段')
            } else {
                message.error('提交失败: ' + (e?.message || '未知错误'))
            }
        }
    }

    const renderCell = (f, row) => {
        const v = row[f.fieldCode]
        const labelKey = f.fieldCode + '_label'
        if (row[labelKey] != null) {
            return <Tag>{row[labelKey]}</Tag>
        }
        if (v == null) return '-'
        if (f.fieldType === 'BOOLEAN') return v ? '是' : '否'
        if (f.fieldType === 'DATETIME' || f.fieldType === 'DATE') {
            // JdbcTemplate 返回 java.sql.Timestamp / Date
            try {
                return new Date(v).toLocaleString()
            } catch {
                return String(v)
            }
        }
        return String(v)
    }

    const columns = [
        {title: 'ID', dataIndex: 'id', key: 'id', width: 80},
        ...fields.map(f => ({
            title: f.fieldName || f.fieldCode,
            dataIndex: f.fieldCode,
            key: f.fieldCode,
            render: (_, row) => renderCell(f, row)
        })),
        {
            title: '操作',
            key: 'action',
            width: 180,
            fixed: 'right',
            render: (_, row) => (
                <Space>
                    <Button type="link" icon={<EditOutlined/>} onClick={() => openEdit(row)}>编辑</Button>
                    <Button type="link" danger icon={<DeleteOutlined/>} onClick={() => handleDelete(row)}>删除</Button>
                </Space>
            )
        }
    ]

    const renderFormItem = (f) => {
        const type = (f.fieldType || 'STRING').toUpperCase()
        if (f.dictCode) {
            const opts = dictCache[f.dictCode] || []
            return (
                <Select placeholder={`选择 ${f.fieldName || f.fieldCode}`} allowClear showSearch
                        optionFilterProp="label">
                    {opts.map(o => <Option key={o.value} value={o.value} label={o.label}>{o.label}</Option>)}
                </Select>
            )
        }
        const conf = FIELD_TYPE_INPUT[type] || FIELD_TYPE_INPUT.STRING
        const Cmp = conf.component
        return <Cmp {...conf.props} placeholder={f.fieldName || f.fieldCode}/>
    }

    return (
        <Card
            title={<><DatabaseOutlined/> {entity.entityName || entity.entityCode} (运行时 CRUD)</>}
            extra={
                <Space>
                    <Button icon={<ReloadOutlined/>} onClick={fetchList}>刷新</Button>
                    <Button type="primary" icon={<PlusOutlined/>} onClick={openCreate}>新增</Button>
                </Space>
            }
        >
            <Alert
                type="info"
                showIcon
                message={`App=${appCode} · Tenant=${tenantCode} · 字段数=${fields.length}`}
                style={{marginBottom: 16}}
            />
            <Spin spinning={loading}>
                <Table
                    rowKey="id"
                    columns={columns}
                    dataSource={rows}
                    pagination={{
                        current: page, pageSize: size, total,
                        showSizeChanger: true, showTotal: t => `共 ${t} 条`,
                        onChange: (p, s) => {
                            setPage(p);
                            setSize(s)
                        }
                    }}
                    scroll={{x: 'max-content'}}
                    size="small"
                />
            </Spin>

            <Modal
                title={editing ? `编辑 #${editing.id}` : '新增记录'}
                open={modalOpen}
                onCancel={() => setModalOpen(false)}
                onOk={handleSubmit}
                width={680}
                destroyOnClose
            >
                <Form form={form} layout="vertical">
                    {fields.map(f => (
                        <Form.Item
                            key={f.fieldCode}
                            name={f.fieldCode}
                            label={
                                <span>
                  {f.fieldName || f.fieldCode}
                                    {f.required && <span style={{color: 'red'}}> *</span>}
                                    <Tag style={{marginLeft: 8}}>{f.fieldType}</Tag>
                </span>
                            }
                            rules={f.required ? [{required: true, message: '必填'}] : []}
                        >
                            {renderFormItem(f)}
                        </Form.Item>
                    ))}
                </Form>
            </Modal>
        </Card>
    )
}
