import {useState} from 'react'
import {Button, Card, Col, Input, Row, Space, Tag, Tooltip, Typography} from 'antd'
import {
    CodeOutlined,
    CopyOutlined,
    FunctionOutlined,
    InfoCircleOutlined,
    ReloadOutlined,
    SearchOutlined
} from '@ant-design/icons'
import {message} from 'antd'

const {Text, Paragraph} = Typography

/**
 * Mock 模板表达式目录
 * 同步自 MockTemplateEngine (z-mock) 实现的 @xxx 表达式
 */
export const TEMPLATE_EXPRESSIONS = [
    {
        group: '基础数据', icon: <CodeOutlined/>,
        items: [
            {syntax: '@uuid', desc: '随机 UUID', example: 'a3f2e1b7-8c4d-4e6f-9a1b-2c3d4e5f6a7b'},
            {syntax: '@int(1, 100)', desc: '随机整数（含边界）', example: '42'},
            {syntax: '@int', desc: '随机整数（0-1000）', example: '731'},
            {syntax: '@boolean', desc: '随机布尔', example: 'true'},
            {syntax: '@pick("a","b","c")', desc: '从列表随机取一项', example: '"b"'},
        ]
    },
    {
        group: '时间相关', icon: <FunctionOutlined/>,
        items: [
            {syntax: '@datetime', desc: '当前时间 (yyyy-MM-dd HH:mm:ss)', example: '2026-06-17 22:30:15'},
            {syntax: '@date', desc: '当前日期 (yyyy-MM-dd)', example: '2026-06-17'},
            {syntax: '@time', desc: '当前时间戳 (毫秒)', example: '1718639415000'},
            {syntax: '@timestamp', desc: 'Unix 秒级时间戳', example: '1718639415'},
        ]
    },
    {
        group: '文本生成', icon: <FunctionOutlined/>,
        items: [
            {syntax: '@name', desc: '随机中文姓名', example: '张伟'},
            {syntax: '@firstName', desc: '随机名', example: '伟'},
            {syntax: '@lastName', desc: '随机姓', example: '张'},
            {syntax: '@email', desc: '随机邮箱', example: 'zhangwei@example.com'},
            {syntax: '@phone', desc: '随机手机号（中国）', example: '13812345678'},
            {syntax: '@idCard', desc: '随机身份证号', example: '110101199003078234'},
            {syntax: '@word', desc: '随机英文单词', example: 'horizon'},
            {syntax: '@sentence(5, 10)', desc: '随机句子 (5-10 词)', example: 'The quick brown fox jumps.'},
            {syntax: '@paragraph(3, 5)', desc: '随机段落 (3-5 句)', example: '...'},
        ]
    },
    {
        group: '序列与状态', icon: <FunctionOutlined/>,
        items: [
            {syntax: '@sequence(1)', desc: '自增序号（每次调用 +1，1 起始）', example: '1, 2, 3, ...'},
            {syntax: '@sequence(100, 5)', desc: '自增序号（100 起始，步长 5）', example: '100, 105, 110, ...'},
        ]
    },
    {
        group: '请求上下文', icon: <FunctionOutlined/>,
        items: [
            {syntax: '#path.id', desc: '从 URL 路径取值 /users/123 → 123', example: '123'},
            {syntax: '#query.page', desc: '从 query string 取值 ?page=2', example: '2'},
            {syntax: '#header.X-Token', desc: '从请求头取值', example: 'abc.def.ghi'},
            {syntax: '#body.username', desc: '从请求体 JSON 取值', example: '"alice"'},
        ]
    },
]

/**
 * MockTemplateHelper — 模板表达式面板
 *
 * 用法：
 *   <MockTemplateHelper onInsert={(expr) => form.setFieldsValue({ responseTemplate: prev + expr })} />
 *
 * @param onInsert 选中表达式后回调（参数为 syntax 字符串）
 * @param formField 当前正在编辑的 Form.Item 字段名（用于 getFieldValue 读取当前内容）
 * @param form AntD form 实例（可选）
 */
export default function MockTemplateHelper({onInsert, form, formField = 'responseTemplate'}) {
    const [filter, setFilter] = useState('')

    const handleInsert = (syntax) => {
        // 复制到剪贴板
        try {
            navigator.clipboard?.writeText(syntax)
        } catch {
        }
        if (onInsert) {
            onInsert(syntax)
        } else if (form) {
            const prev = form.getFieldValue(formField) || ''
            form.setFieldsValue({[formField]: prev + (prev.endsWith('\n') || !prev ? '' : ' ') + syntax})
        }
        message.success(`已插入: ${syntax}`)
    }

    const handleCopy = (text) => {
        try {
            navigator.clipboard?.writeText(text)
            message.success('已复制到剪贴板')
        } catch {
            message.warning('复制失败，请手动选择')
        }
    }

    const lowerFilter = filter.toLowerCase()
    const filteredGroups = TEMPLATE_EXPRESSIONS.map(g => ({
        ...g,
        items: g.items.filter(it =>
            !lowerFilter ||
            it.syntax.toLowerCase().includes(lowerFilter) ||
            it.desc.toLowerCase().includes(lowerFilter) ||
            it.example.toLowerCase().includes(lowerFilter)
        )
    })).filter(g => g.items.length > 0)

    const totalCount = TEMPLATE_EXPRESSIONS.reduce((s, g) => s + g.items.length, 0)
    const filteredCount = filteredGroups.reduce((s, g) => s + g.items.length, 0)

    return (
        <Card
            size="small"
            title={
                <Space>
                    <CodeOutlined/>
                    <Text strong>模板表达式</Text>
                    <Tag color="blue">{filteredCount}/{totalCount}</Tag>
                    <Tooltip title="点击按钮即可插入到上方响应模板；点击 syntax 文本可复制">
                        <InfoCircleOutlined style={{color: '#999'}}/>
                    </Tooltip>
                </Space>
            }
            extra={
                <Input
                    size="small"
                    placeholder="搜索 @ 表达式"
                    prefix={<SearchOutlined/>}
                    value={filter}
                    onChange={e => setFilter(e.target.value)}
                    style={{width: 180}}
                    allowClear
                />
            }
            style={{marginTop: 8, background: '#fafafa'}}
            styles={{body: {padding: 12, maxHeight: 320, overflow: 'auto'}}}
        >
            {filteredGroups.length === 0 && (
                <Text type="secondary" style={{display: 'block', textAlign: 'center', padding: 16}}>
                    没有匹配的表达式
                </Text>
            )}
            {filteredGroups.map(group => (
                <div key={group.group} style={{marginBottom: 12}}>
                    <Space size={4} style={{marginBottom: 6}}>
                        {group.icon}
                        <Text strong style={{fontSize: 13}}>{group.group}</Text>
                        <Tag>{group.items.length}</Tag>
                    </Space>
                    <Row gutter={[8, 8]}>
                        {group.items.map(item => (
                            <Col span={24} key={item.syntax}>
                                <div
                                    style={{
                                        display: 'flex',
                                        alignItems: 'center',
                                        padding: '6px 8px',
                                        background: '#fff',
                                        border: '1px solid #f0f0f0',
                                        borderRadius: 4,
                                        gap: 8,
                                    }}
                                >
                                    <Tooltip title="点击复制">
                                        <Text code
                                              style={{cursor: 'pointer', minWidth: 140, fontSize: 12}}
                                              onClick={() => handleCopy(item.syntax)}>
                                            {item.syntax}
                                        </Text>
                                    </Tooltip>
                                    <Text type="secondary" style={{fontSize: 12, flex: 1}}>
                                        {item.desc}
                                    </Text>
                                    <Tag color="default" style={{margin: 0, fontFamily: 'monospace'}}>
                                        {item.example}
                                    </Tag>
                                    <Tooltip title="插入到响应模板">
                                        <Button size="small" type="text" icon={<CopyOutlined/>}
                                                onClick={() => handleInsert(item.syntax)}/>
                                    </Tooltip>
                                </div>
                            </Col>
                        ))}
                    </Row>
                </div>
            ))}
        </Card>
    )
}
