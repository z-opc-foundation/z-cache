import React, {useCallback, useEffect, useMemo, useState} from 'react'
import {useSearchParams} from 'react-router-dom'
import {
    Alert,
    Badge,
    Button,
    Card,
    Col,
    Drawer,
    Empty,
    Form,
    Input,
    InputNumber,
    message,
    Modal,
    Popconfirm,
    Row,
    Select,
    Space,
    Spin,
    Switch,
    Table,
    Tabs,
    Tag,
    Tooltip
} from 'antd'
import {
    CheckCircleOutlined,
    CodeOutlined,
    CopyOutlined,
    DatabaseOutlined,
    DeleteOutlined,
    EditOutlined,
    FileTextOutlined,
    FormatPainterOutlined,
    HolderOutlined,
    PlusOutlined,
    ReloadOutlined,
    ThunderboltOutlined
} from '@ant-design/icons'
import request from '../../utils/request'

const {Option} = Select
const {TextArea} = Input

// ===== 字段类型定义 =====
const FIELD_TYPES = [
    {type: 'STRING', label: '字符串', icon: <FileTextOutlined/>, color: '#1677ff', desc: '短文本 (默认 VARCHAR 255)'},
    {type: 'TEXT', label: '长文本', icon: <FileTextOutlined/>, color: '#13c2c2', desc: 'TEXT 类型, 无长度限制'},
    {type: 'INT', label: '整数', icon: '#', color: '#722ed1', desc: 'INT 整数'},
    {type: 'LONG', label: '长整数', icon: '#', color: '#722ed1', desc: 'BIGINT 长整数'},
    {type: 'DECIMAL', label: '小数', icon: '0.00', color: '#eb2f96', desc: 'DECIMAL 精确小数'},
    {type: 'BOOLEAN', label: '布尔', icon: <CheckCircleOutlined/>, color: '#52c41a', desc: 'TINYINT(1) 是/否'},
    {type: 'DATE', label: '日期', icon: '📅', color: '#fa8c16', desc: 'DATE yyyy-MM-dd'},
    {type: 'DATETIME', label: '日期时间', icon: '🕐', color: '#fa8c16', desc: 'DATETIME yyyy-MM-dd HH:mm:ss'},
    {type: 'JSON', label: 'JSON', icon: '{}', color: '#2f54eb', desc: 'JSON 字符串'},
    {type: 'REF', label: '引用', icon: '🔗', color: '#8c8c8c', desc: '外键引用 BIGINT'},
]

const FIELD_TYPE_MAP = Object.fromEntries(FIELD_TYPES.map(t => [t.type, t]))

const DRAG_TYPE_PALETTE = 'application/x-lc-field-type'
const DRAG_TYPE_FIELD = 'application/x-lc-field-idx'

/**
 * LowCodeModel: 拖拽式实体/字段编辑器 (z-lc Phase 1)
 * <p>
 * 三列布局:
 * <ul>
 *   <li>左侧调色板: 10 种字段类型, 可拖到中间列表</li>
 *   <li>中间字段列表: 支持拖入新字段 + 字段间拖拽排序</li>
 *   <li>右侧详情: 选中字段后编辑详细属性</li>
 * </ul>
 * <p>
 * 所有提交走事件溯源 (POST /api/lc/app/{appCode}/event), schema 通过 GET /schema 回放得到.
 */
export default function LowCodeModel() {
    const [searchParams] = useSearchParams()
    const [appCode, setAppCode] = useState(searchParams.get('appCode') || 'demo')
    const [tenantCode, setTenantCode] = useState(searchParams.get('tenant') || localStorage.getItem('z_tenant') || 'default')
    const [schema, setSchema] = useState([])
    const [lastEventId, setLastEventId] = useState(null)
    const [loading, setLoading] = useState(false)
    const [editingEntity, setEditingEntity] = useState(null)
    const [entityModalOpen, setEntityModalOpen] = useState(false)
    const [entityForm] = Form.useForm()

    // ===== 拖拽态 =====
    // palette drag: 拖字段类型时携带 type 字符串
    const [paletteDragType, setPaletteDragType] = useState(null)
    // field list drag: 拖已有字段重排时, 记录源 idx
    const [dragFieldIdx, setDragFieldIdx] = useState(null)
    // 当前 hover 位置 (palette idx / field insert idx / null)
    const [dropHint, setDropHint] = useState(null)

    // ===== 当前实体编辑态 (右侧 Drawer) =====
    const [activeEntityFields, setActiveEntityFields] = useState([])
    const [selectedFieldIdx, setSelectedFieldIdx] = useState(null)
    const [dirty, setDirty] = useState(false)
    const [rightDrawerOpen, setRightDrawerOpen] = useState(false)

    const fetchSchema = useCallback(async () => {
        if (!appCode) return
        setLoading(true)
        try {
            const list = await request.get(`/lc/app/${appCode}/schema`, {
                params: {tenantCode}
            })
            setSchema(Array.isArray(list) ? list : [])
            setLastEventId(null)
        } catch (e) {
            message.error('加载 schema 失败: ' + (e?.message || '未知错误'))
            setSchema([])
        } finally {
            setLoading(false)
        }
    }, [appCode, tenantCode])

    useEffect(() => {
        fetchSchema()
    }, [fetchSchema])

    // ===== 事件提交 (事件溯源) =====
    const submitEvent = async (eventType, entityCode, eventData) => {
        try {
            const body = {
                tenantCode,
                entityCode,
                eventType,
                eventData: typeof eventData === 'string' ? eventData : JSON.stringify(eventData),
                source: 'USER',
                parentEventId: lastEventId
            }
            const res = await request.post(`/lc/app/${appCode}/event`, body)
            message.success(`事件 ${eventType} 已提交 (eventId=${res?.eventId?.slice(0, 8)}…)`)
            setLastEventId(res?.eventId || null)
            await fetchSchema()
        } catch (e) {
            message.error('提交事件失败: ' + (e?.message || '未知错误'))
        }
    }

    // ===== Entity CRUD =====
    const handleCreateEntity = () => {
        setEditingEntity(null)
        entityForm.resetFields()
        entityForm.setFieldsValue({
            entityCode: '',
            entityName: '',
            tableName: '',
            description: '',
            fields: []
        })
        setEntityModalOpen(true)
    }

    const handleEditEntity = (entity) => {
        setEditingEntity(entity)
        entityForm.setFieldsValue({
            entityCode: entity.entityCode,
            entityName: entity.entityName,
            tableName: entity.tableName || entity.entityCode,
            description: entity.description,
            fields: entity.fields || []
        })
        setEntityModalOpen(true)
    }

    const handleOpenEntityEditor = (entity) => {
        // 打开右侧画布编辑器 (拖拽)
        setActiveEntityFields((entity.fields || []).map((f, i) => ({...f, sortOrder: f.sortOrder ?? i})))
        setSelectedFieldIdx(null)
        setDirty(false)
        setRightDrawerOpen(true)
        // 暂存当前编辑中的 entity 信息
        setEditingEntity(entity)
    }

    const handleDeleteEntity = (entity) => {
        Modal.confirm({
            title: `删除实体 ${entity.entityName || entity.entityCode}?`,
            content: '此操作会发出 DELETE 事件, 所有字段一起删除.',
            okType: 'danger',
            onOk: () => submitEvent('DELETE', entity.entityCode, {entityCode: entity.entityCode})
        })
    }

    const handleSubmitEntity = async () => {
        try {
            const v = await entityForm.validateFields()
            const eventType = editingEntity ? 'UPDATE' : 'CREATE'
            const eventData = {
                entityCode: v.entityCode,
                entityName: v.entityName,
                tableName: v.tableName || v.entityCode,
                description: v.description,
                fields: (v.fields || []).map(f => ({
                    fieldCode: f.fieldCode,
                    fieldName: f.fieldName,
                    fieldType: f.fieldType || 'STRING',
                    required: !!f.required,
                    defaultValue: f.defaultValue || null,
                    dictCode: f.dictCode || null,
                    refEntity: f.refEntity || null,
                    fieldLength: f.fieldLength || null,
                    scale: f.scale || null,
                    sortOrder: f.sortOrder || 0
                }))
            }
            setEntityModalOpen(false)
            await submitEvent(eventType, v.entityCode, eventData)
        } catch (e) {
            if (e?.errorFields) {
                message.error('请补全必填字段')
            } else {
                message.error('提交失败: ' + (e?.message || '未知错误'))
            }
        }
    }

    // ===== 拖拽调色板 → 字段列表 =====
    const handlePaletteDragStart = (e, type) => {
        e.dataTransfer.setData(DRAG_TYPE_PALETTE, type)
        e.dataTransfer.setData('text/plain', type) // 兜底
        e.dataTransfer.effectAllowed = 'copy'
        setPaletteDragType(type)
    }

    const handlePaletteDragEnd = () => {
        setPaletteDragType(null)
        setDropHint(null)
    }

    // ===== 拖拽已有字段重排 =====
    const handleFieldDragStart = (e, idx) => {
        e.dataTransfer.setData(DRAG_TYPE_FIELD, String(idx))
        e.dataTransfer.effectAllowed = 'move'
        setDragFieldIdx(idx)
    }

    const handleFieldDragEnd = () => {
        setDragFieldIdx(null)
        setDropHint(null)
    }

    // ===== 列表接受拖入/拖放 =====
    const handleListDragOver = (e) => {
        e.preventDefault()
        e.dataTransfer.dropEffect = paletteDragType ? 'copy' : 'move'
    }

    const handleListDragLeave = () => {
        setDropHint(null)
    }

    const handleListDrop = (e) => {
        e.preventDefault()
        const paletteType = e.dataTransfer.getData(DRAG_TYPE_PALETTE)
        const fieldIdxStr = e.dataTransfer.getData(DRAG_TYPE_FIELD)
        if (paletteType) {
            // 从调色板拖入: 在末尾追加
            addFieldFromPalette(paletteType, activeEntityFields.length)
        } else if (fieldIdxStr) {
            // 拖到列表末尾 (无 idx 提示): 等同拖到末尾
            const srcIdx = Number(fieldIdxStr)
            moveField(srcIdx, activeEntityFields.length)
        }
        setDropHint(null)
        setPaletteDragType(null)
        setDragFieldIdx(null)
    }

    const handleRowDragOver = (e, insertIdx) => {
        e.preventDefault()
        e.stopPropagation()
        setDropHint(insertIdx)
    }

    const handleRowDrop = (e, insertIdx) => {
        e.preventDefault()
        e.stopPropagation()
        const paletteType = e.dataTransfer.getData(DRAG_TYPE_PALETTE)
        const fieldIdxStr = e.dataTransfer.getData(DRAG_TYPE_FIELD)
        if (paletteType) {
            addFieldFromPalette(paletteType, insertIdx)
        } else if (fieldIdxStr) {
            moveField(Number(fieldIdxStr), insertIdx)
        }
        setDropHint(null)
        setPaletteDragType(null)
        setDragFieldIdx(null)
    }

    const addFieldFromPalette = (type, insertIdx) => {
        const meta = FIELD_TYPE_MAP[type]
        if (!meta) return
        const seq = activeEntityFields.length + 1
        const newField = {
            fieldCode: `field_${seq}`,
            fieldName: `新${meta.label}字段`,
            fieldType: type,
            required: false,
            defaultValue: null,
            dictCode: null,
            refEntity: null,
            fieldLength: type === 'STRING' ? 255 : null,
            scale: type === 'DECIMAL' ? 2 : null,
            sortOrder: insertIdx
        }
        const next = [...activeEntityFields]
        next.splice(insertIdx, 0, newField)
        reassignSortOrder(next)
        setActiveEntityFields(next)
        setSelectedFieldIdx(insertIdx)
        setDirty(true)
    }

    const moveField = (srcIdx, dstIdx) => {
        if (srcIdx === dstIdx || srcIdx < 0 || dstIdx < 0) return
        const next = [...activeEntityFields]
        const [moved] = next.splice(srcIdx, 1)
        // 如果从前往后拖, 目标 idx 要 -1
        const finalIdx = dstIdx > srcIdx ? dstIdx - 1 : dstIdx
        next.splice(finalIdx, 0, moved)
        reassignSortOrder(next)
        setActiveEntityFields(next)
        setDirty(true)
    }

    const reassignSortOrder = (arr) => {
        arr.forEach((f, i) => { f.sortOrder = i })
    }

    // ===== 字段详情编辑 =====
    const updateField = (idx, patch) => {
        const next = [...activeEntityFields]
        next[idx] = {...next[idx], ...patch}
        setActiveEntityFields(next)
        setDirty(true)
    }

    const removeField = (idx) => {
        const next = activeEntityFields.filter((_, i) => i !== idx)
        reassignSortOrder(next)
        setActiveEntityFields(next)
        setSelectedFieldIdx(null)
        setDirty(true)
    }

    const duplicateField = (idx) => {
        const src = activeEntityFields[idx]
        const copy = {...src, fieldCode: `${src.fieldCode}_copy`, fieldName: `${src.fieldName} (副本)`}
        const next = [...activeEntityFields]
        next.splice(idx + 1, 0, copy)
        reassignSortOrder(next)
        setActiveEntityFields(next)
        setSelectedFieldIdx(idx + 1)
        setDirty(true)
    }

    // ===== 提交整个 entity (从画布编辑器) =====
    const handleCommitEntity = async () => {
        if (!editingEntity) return
        const eventData = {
            entityCode: editingEntity.entityCode,
            entityName: editingEntity.entityName,
            tableName: editingEntity.tableName,
            description: editingEntity.description,
            fields: activeEntityFields.map(f => ({
                fieldCode: f.fieldCode,
                fieldName: f.fieldName,
                fieldType: f.fieldType || 'STRING',
                required: !!f.required,
                defaultValue: f.defaultValue || null,
                dictCode: f.dictCode || null,
                refEntity: f.refEntity || null,
                fieldLength: f.fieldLength || null,
                scale: f.scale || null,
                sortOrder: f.sortOrder ?? 0
            }))
        }
        await submitEvent('UPDATE', editingEntity.entityCode, eventData)
        setDirty(false)
    }

    // ===== 自动建表 =====
    const handleProvision = async () => {
        if (!editingEntity) return
        try {
            const ddl = await request.post(
                `/lc/admin/entity/${editingEntity.id}/provision?tenant=${tenantCode}`
            )
            message.success(`已建表 (${activeEntityFields.length} 字段)`)
            console.log('DDL:', ddl)
        } catch (e) {
            message.error('建表失败: ' + (e?.message || '未知错误'))
        }
    }

    // ===== 渲染 =====
    const entityColumns = [
        {title: '编码', dataIndex: 'entityCode', key: 'entityCode', width: 140},
        {title: '名称', dataIndex: 'entityName', key: 'entityName', width: 140},
        {title: '物理表', dataIndex: 'tableName', key: 'tableName', width: 160},
        {
            title: '字段数',
            key: 'fields',
            width: 80,
            render: (_, r) => <Badge count={r.fields?.length || 0} showZero color="blue"/>
        },
        {title: '描述', dataIndex: 'description', key: 'description', ellipsis: true},
        {
            title: '操作',
            key: 'action',
            width: 260,
            render: (_, r) => (
                <Space>
                    <Button type="link" size="small" icon={<FormatPainterOutlined/>}
                            onClick={() => handleOpenEntityEditor(r)}>
                        画布编辑
                    </Button>
                    <Button type="link" size="small" icon={<EditOutlined/>}
                            onClick={() => handleEditEntity(r)}>
                        表单编辑
                    </Button>
                    <Popconfirm title="确定删除?" onConfirm={() => handleDeleteEntity(r)}>
                        <Button type="link" size="small" danger icon={<DeleteOutlined/>}>
                            删除
                        </Button>
                    </Popconfirm>
                </Space>
            )
        }
    ]

    const selectedField = selectedFieldIdx != null ? activeEntityFields[selectedFieldIdx] : null

    return (
        <div>
            <Card
                title={<><ThunderboltOutlined/> z-lc 模型编辑器 (拖拽版)</>}
                extra={
                    <Space>
                        <Input
                            addonBefore="appCode"
                            value={appCode}
                            onChange={e => setAppCode(e.target.value.trim())}
                            style={{width: 200}}
                        />
                        <Input
                            addonBefore="tenant"
                            value={tenantCode}
                            onChange={e => setTenantCode(e.target.value.trim())}
                            style={{width: 160}}
                        />
                        <Button icon={<ReloadOutlined/>} onClick={fetchSchema}>刷新</Button>
                        <Button type="primary" icon={<PlusOutlined/>} onClick={handleCreateEntity}>
                            新建实体
                        </Button>
                    </Space>
                }
            >
                <Alert
                    type="info"
                    showIcon
                    message="提示: 点击实体的「画布编辑」进入拖拽式建模: 从左侧面板拖字段类型到中间, 拖拽字段行可重排, 右侧编辑属性。"
                    style={{marginBottom: 16}}
                />
                <Spin spinning={loading}>
                    {schema.length === 0 ? (
                        <Empty description="暂无实体, 点击右上角 「新建实体」 开始"/>
                    ) : (
                        <Table
                            rowKey="entityCode"
                            columns={entityColumns}
                            dataSource={schema}
                            pagination={false}
                            size="small"
                            expandable={{
                                expandedRowRender: (record) => (
                                    <FieldPreview entity={record}/>
                                )
                            }}
                        />
                    )}
                </Spin>
            </Card>

            {/* 实体表单编辑 (兼容旧路径) */}
            <Modal
                title={editingEntity ? `编辑实体 ${editingEntity.entityCode}` : '新建实体'}
                open={entityModalOpen}
                onCancel={() => setEntityModalOpen(false)}
                onOk={handleSubmitEntity}
                width={760}
                destroyOnClose
            >
                <EntityForm form={entityForm} editing={editingEntity}/>
            </Modal>

            {/* 拖拽式画布编辑器 (右侧 Drawer) */}
            <Drawer
                title={
                    <Space>
                        <FormatPainterOutlined/>
                        {editingEntity ? `画布编辑 - ${editingEntity.entityName || editingEntity.entityCode}` : '画布编辑'}
                        {dirty && <Tag color="orange">未保存</Tag>}
                    </Space>
                }
                open={rightDrawerOpen}
                onClose={() => {
                    if (dirty) {
                        Modal.confirm({
                            title: '有未保存的修改, 确定关闭?',
                            onOk: () => {
                                setRightDrawerOpen(false)
                                setDirty(false)
                            }
                        })
                    } else {
                        setRightDrawerOpen(false)
                    }
                }}
                width="85%"
                destroyOnClose
                extra={
                    <Space>
                        <Button onClick={handleProvision} icon={<DatabaseOutlined/>}>
                            自动建表
                        </Button>
                        <Button
                            type="primary"
                            icon={<ThunderboltOutlined/>}
                            disabled={!dirty}
                            onClick={handleCommitEntity}
                        >
                            提交 (UPDATE 事件)
                        </Button>
                    </Space>
                }
            >
                <Row gutter={16} style={{height: 'calc(100vh - 200px)'}}>
                    {/* 左侧: 字段类型调色板 */}
                    <Col span={5}>
                        <Card size="small" title={<><HolderOutlined/> 字段类型面板</>} style={{height: '100%'}}
                              bodyStyle={{padding: 12, overflowY: 'auto', height: 'calc(100% - 40px)'}}>
                            <div style={{fontSize: 12, color: '#999', marginBottom: 8}}>
                                拖拽字段类型到右侧
                            </div>
                            {FIELD_TYPES.map(t => (
                                <div
                                    key={t.type}
                                    draggable
                                    onDragStart={(e) => handlePaletteDragStart(e, t.type)}
                                    onDragEnd={handlePaletteDragEnd}
                                    style={{
                                        padding: '8px 12px',
                                        marginBottom: 8,
                                        background: paletteDragType === t.type ? '#e6f4ff' : '#fafafa',
                                        border: `1px dashed ${t.color}`,
                                        borderRadius: 6,
                                        cursor: 'grab',
                                        opacity: paletteDragType === t.type ? 0.5 : 1,
                                        transition: 'all 0.2s'
                                    }}
                                >
                                    <Space>
                                        <span style={{
                                            display: 'inline-block',
                                            width: 22, height: 22,
                                            borderRadius: 4,
                                            background: t.color,
                                            color: '#fff',
                                            textAlign: 'center',
                                            lineHeight: '22px',
                                            fontSize: 11,
                                            fontWeight: 'bold'
                                        }}>{t.type.slice(0, 2)}</span>
                                        <span style={{fontWeight: 500}}>{t.label}</span>
                                    </Space>
                                    <div style={{fontSize: 11, color: '#888', marginTop: 4}}>{t.desc}</div>
                                </div>
                            ))}
                        </Card>
                    </Col>

                    {/* 中间: 字段列表 */}
                    <Col span={12}>
                        <Card
                            size="small"
                            title={
                                <Space>
                                    <DatabaseOutlined/>
                                    字段列表 ({activeEntityFields.length})
                                </Space>
                            }
                            style={{height: '100%'}}
                            bodyStyle={{padding: 8, overflowY: 'auto', height: 'calc(100% - 40px)'}}
                        >
                            <div
                                onDragOver={handleListDragOver}
                                onDragLeave={handleListDragLeave}
                                onDrop={handleListDrop}
                                style={{
                                    minHeight: '100%',
                                    padding: 4,
                                    border: '2px dashed transparent',
                                    borderRadius: 6
                                }}
                            >
                                {activeEntityFields.length === 0 ? (
                                    <Empty
                                        description="从左侧拖拽字段类型到这里"
                                        style={{marginTop: 80}}
                                    />
                                ) : (
                                    <>
                                        {activeEntityFields.map((f, idx) => {
                                            const meta = FIELD_TYPE_MAP[f.fieldType] || FIELD_TYPE_MAP.STRING
                                            const isSelected = selectedFieldIdx === idx
                                            const isDragging = dragFieldIdx === idx
                                            return (
                                                <React.Fragment key={`${f.fieldCode}-${idx}`}>
                                                    {/* 行间插入提示 */}
                                                    {dropHint === idx && (
                                                        <div style={{
                                                            height: 4,
                                                            background: '#1677ff',
                                                            borderRadius: 2,
                                                            margin: '4px 0'
                                                        }}/>
                                                    )}
                                                    <div
                                                        draggable
                                                        onDragStart={(e) => handleFieldDragStart(e, idx)}
                                                        onDragEnd={handleFieldDragEnd}
                                                        onDragOver={(e) => handleRowDragOver(e, idx)}
                                                        onDrop={(e) => handleRowDrop(e, idx)}
                                                        onClick={() => setSelectedFieldIdx(idx)}
                                                        style={{
                                                            padding: '10px 12px',
                                                            marginBottom: 6,
                                                            background: isSelected ? '#e6f4ff' : '#fff',
                                                            border: `1px solid ${isSelected ? '#1677ff' : '#d9d9d9'}`,
                                                            borderRadius: 6,
                                                            cursor: 'grab',
                                                            opacity: isDragging ? 0.4 : 1,
                                                            transition: 'all 0.15s'
                                                        }}
                                                    >
                                                        <Space style={{width: '100%', justifyContent: 'space-between'}}>
                                                            <Space>
                                                                <HolderOutlined style={{color: '#999'}}/>
                                                                <span style={{
                                                                    display: 'inline-block',
                                                                    width: 20, height: 20,
                                                                    borderRadius: 3,
                                                                    background: meta.color,
                                                                    color: '#fff',
                                                                    textAlign: 'center',
                                                                    lineHeight: '20px',
                                                                    fontSize: 10
                                                                }}>{f.fieldType.slice(0, 2)}</span>
                                                                <span style={{
                                                                    fontFamily: 'monospace',
                                                                    fontWeight: 500
                                                                }}>{f.fieldCode}</span>
                                                                <span>{f.fieldName}</span>
                                                                {f.required && <Tag color="red" style={{margin: 0}}>必填</Tag>}
                                                                {f.dictCode && <Tag color="blue" style={{margin: 0}}>dict:{f.dictCode}</Tag>}
                                                                {f.refEntity && <Tag color="purple" style={{margin: 0}}>ref:{f.refEntity}</Tag>}
                                                            </Space>
                                                            <Space size={4} onClick={(e) => e.stopPropagation()}>
                                                                <Tooltip title="复制">
                                                                    <Button
                                                                        type="text" size="small"
                                                                        icon={<CopyOutlined/>}
                                                                        onClick={() => duplicateField(idx)}
                                                                    />
                                                                </Tooltip>
                                                                <Tooltip title="删除">
                                                                    <Button
                                                                        type="text" size="small" danger
                                                                        icon={<DeleteOutlined/>}
                                                                        onClick={() => removeField(idx)}
                                                                    />
                                                                </Tooltip>
                                                            </Space>
                                                        </Space>
                                                    </div>
                                                </React.Fragment>
                                            )
                                        })}
                                        {/* 末尾插入提示 */}
                                        {dropHint === activeEntityFields.length && (
                                            <div style={{
                                                height: 4,
                                                background: '#1677ff',
                                                borderRadius: 2,
                                                margin: '4px 0'
                                            }}/>
                                        )}
                                    </>
                                )}
                            </div>
                        </Card>
                    </Col>

                    {/* 右侧: 字段属性面板 */}
                    <Col span={7}>
                        <Card
                            size="small"
                            title={<><CodeOutlined/> 字段属性</>}
                            style={{height: '100%'}}
                            bodyStyle={{padding: 12, overflowY: 'auto', height: 'calc(100% - 40px)'}}
                        >
                            {selectedField ? (
                                <FieldPropertyPanel
                                    field={selectedField}
                                    onChange={(patch) => updateField(selectedFieldIdx, patch)}
                                />
                            ) : (
                                <Empty description="点击中间列表中的字段以编辑属性" style={{marginTop: 80}}/>
                            )}
                        </Card>
                    </Col>
                </Row>
            </Drawer>
        </div>
    )
}

// ===== 字段预览 (实体列表展开行) =====
function FieldPreview({entity}) {
    const fields = entity.fields || []
    if (fields.length === 0) {
        return <span style={{color: '#999'}}>无字段</span>
    }
    return (
        <Space wrap size={[4, 4]}>
            {fields.map(f => (
                <Tag key={f.fieldCode} color={
                    (FIELD_TYPE_MAP[f.fieldType] || {}).color || 'default'
                }>
                    {f.fieldCode} : {f.fieldType}
                    {f.required ? ' *' : ''}
                </Tag>
            ))}
        </Space>
    )
}

// ===== 实体表单 (兼容旧版) =====
function EntityForm({form, editing}) {
    return (
        <Form form={form} layout="vertical">
            <Space style={{width: '100%'}} size="middle">
                <Form.Item name="entityCode" label="实体编码" rules={[{
                    required: true,
                    pattern: /^[A-Za-z][A-Za-z0-9_]*$/,
                    message: '字母数字下划线'
                }]}>
                    <Input disabled={!!editing} placeholder="如 user"/>
                </Form.Item>
                <Form.Item name="entityName" label="实体名称">
                    <Input placeholder="如 用户"/>
                </Form.Item>
                <Form.Item name="tableName" label="物理表名" rules={[{
                    required: true,
                    pattern: /^[A-Za-z][A-Za-z0-9_]*$/,
                    message: '字母数字下划线'
                }]}>
                    <Input placeholder="默认 = entityCode"/>
                </Form.Item>
            </Space>
            <Form.Item name="description" label="描述">
                <TextArea rows={2}/>
            </Form.Item>
            <Form.List name="fields">
                {(fields, {add, remove}) => (
                    <>
                        <div style={{marginBottom: 8}}>
                            <Button type="dashed" onClick={() => add({fieldType: 'STRING', required: false})}
                                    icon={<PlusOutlined/>}>
                                添加字段
                            </Button>
                        </div>
                        <Table
                            rowKey={(r) => r.fieldCode || Math.random()}
                            columns={[
                                {
                                    title: '编码', dataIndex: 'fieldCode',
                                    render: (_, _r, idx) => (
                                        <Form.Item name={[idx, 'fieldCode']} noStyle rules={[{
                                            required: true,
                                            pattern: /^[A-Za-z][A-Za-z0-9_]*$/,
                                            message: '字母数字下划线'
                                        }]}>
                                            <Input size="small" placeholder="userName"/>
                                        </Form.Item>
                                    )
                                },
                                {
                                    title: '名称', dataIndex: 'fieldName',
                                    render: (_, _r, idx) => (
                                        <Form.Item name={[idx, 'fieldName']} noStyle>
                                            <Input size="small" placeholder="用户名"/>
                                        </Form.Item>
                                    )
                                },
                                {
                                    title: '类型', dataIndex: 'fieldType', width: 120,
                                    render: (_, _r, idx) => (
                                        <Form.Item name={[idx, 'fieldType']} noStyle>
                                            <Select size="small">
                                                {FIELD_TYPES.map(t =>
                                                    <Option key={t.type} value={t.type}>{t.type}</Option>
                                                )}
                                            </Select>
                                        </Form.Item>
                                    )
                                },
                                {
                                    title: '必填', dataIndex: 'required', width: 60,
                                    render: (_, _r, idx) => (
                                        <Form.Item name={[idx, 'required']} noStyle valuePropName="checked">
                                            <Switch size="small"/>
                                        </Form.Item>
                                    )
                                },
                                {
                                    title: '字典', dataIndex: 'dictCode', width: 110,
                                    render: (_, _r, idx) => (
                                        <Form.Item name={[idx, 'dictCode']} noStyle>
                                            <Input size="small" placeholder="可选"/>
                                        </Form.Item>
                                    )
                                },
                                {
                                    title: '引用', dataIndex: 'refEntity', width: 110,
                                    render: (_, _r, idx) => (
                                        <Form.Item name={[idx, 'refEntity']} noStyle>
                                            <Input size="small" placeholder="可选"/>
                                        </Form.Item>
                                    )
                                },
                                {
                                    title: '操作', width: 60,
                                    render: (_, _r, idx) => (
                                        <Button size="small" danger type="link"
                                                onClick={() => remove(idx)}>移除</Button>
                                    )
                                }
                            ]}
                            dataSource={fields}
                            pagination={false}
                            size="small"
                        />
                    </>
                )}
            </Form.List>
        </Form>
    )
}

// ===== 字段属性编辑面板 =====
function FieldPropertyPanel({field, onChange}) {
    return (
        <div>
            <Form layout="vertical" size="small">
                <Form.Item label="字段编码 (物理列名)" required>
                    <Input
                        value={field.fieldCode || ''}
                        onChange={e => onChange({fieldCode: e.target.value})}
                        placeholder="如 userName"
                    />
                </Form.Item>
                <Form.Item label="显示名称">
                    <Input
                        value={field.fieldName || ''}
                        onChange={e => onChange({fieldName: e.target.value})}
                        placeholder="如 用户名"
                    />
                </Form.Item>
                <Form.Item label="字段类型">
                    <Select
                        value={field.fieldType || 'STRING'}
                        onChange={v => onChange({fieldType: v})}
                        style={{width: '100%'}}
                    >
                        {FIELD_TYPES.map(t => (
                            <Option key={t.type} value={t.type}>{t.label} ({t.type})</Option>
                        ))}
                    </Select>
                </Form.Item>
                <Form.Item label="必填">
                    <Switch
                        checked={!!field.required}
                        onChange={v => onChange({required: v})}
                    />
                </Form.Item>
                {(field.fieldType === 'STRING' || field.fieldType === 'TEXT') && (
                    <Form.Item label="字段长度">
                        <InputNumber
                            value={field.fieldLength}
                            onChange={v => onChange({fieldLength: v})}
                            min={1}
                            max={65535}
                            style={{width: '100%'}}
                        />
                    </Form.Item>
                )}
                {field.fieldType === 'DECIMAL' && (
                    <Form.Item label="小数位数">
                        <InputNumber
                            value={field.scale}
                            onChange={v => onChange({scale: v})}
                            min={0}
                            max={10}
                            style={{width: '100%'}}
                        />
                    </Form.Item>
                )}
                <Form.Item label="默认值">
                    <Input
                        value={field.defaultValue || ''}
                        onChange={e => onChange({defaultValue: e.target.value})}
                        placeholder="可选"
                    />
                </Form.Item>
                <Form.Item label="字典编码 (dictCode)">
                    <Input
                        value={field.dictCode || ''}
                        onChange={e => onChange({dictCode: e.target.value})}
                        placeholder="如 user_status"
                    />
                </Form.Item>
                <Form.Item label="引用实体 (refEntity)">
                    <Input
                        value={field.refEntity || ''}
                        onChange={e => onChange({refEntity: e.target.value})}
                        placeholder="如 其他 entity 的 entityCode"
                    />
                </Form.Item>
                <Form.Item label="排序号">
                    <InputNumber
                        value={field.sortOrder ?? 0}
                        onChange={v => onChange({sortOrder: v})}
                        style={{width: '100%'}}
                    />
                </Form.Item>
                <Form.Item label="备注">
                    <TextArea
                        rows={2}
                        value={field.description || ''}
                        onChange={e => onChange({description: e.target.value})}
                    />
                </Form.Item>
            </Form>
        </div>
    )
}
