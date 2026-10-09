import {useEffect, useState} from 'react'
import {
    Alert,
    Button,
    Card,
    Col,
    Descriptions,
    Drawer,
    Input,
    message,
    Popconfirm,
    Row,
    Select,
    Space,
    Statistic,
    Table,
    Tag,
    Typography
} from 'antd'
import {DeleteOutlined, EyeOutlined, ReloadOutlined, SearchOutlined} from '@ant-design/icons'
import {mockPlatformApi} from '@/services/api'

const {Text} = Typography

const STATUS_COLORS = {MOCK: 'green', PROXY: 'blue', '404': 'red', '502': 'orange'}

/**
 * 请求日志 Tab - 查看每一次 Mock 请求/响应
 */
export default function RequestLogsTab() {
    const [logs, setLogs] = useState([])
    const [stats, setStats] = useState(null)
    const [loading, setLoading] = useState(false)
    const [detail, setDetail] = useState(null)
    const [detailOpen, setDetailOpen] = useState(false)
    const [filterPath, setFilterPath] = useState('')
    const [filterMatched, setFilterMatched] = useState()

    useEffect(() => {
        loadData()
    }, [])

    const loadData = async () => {
        setLoading(true)
        try {
            const [logsRes, statsRes] = await Promise.all([
                mockPlatformApi.requestLogs.list({
                    path: filterPath || undefined,
                    matched: filterMatched,
                    limit: 100,
                }),
                mockPlatformApi.requestLogs.stats().catch(() => null),
            ])
            setLogs(Array.isArray(logsRes) ? logsRes : [])
            setStats(statsRes)
        } catch (e) {
            message.warning('加载失败')
        } finally {
            setLoading(false)
        }
    }

    const handleDetail = (record) => {
        setDetail(record)
        setDetailOpen(true)
    }

    const handleClear = async () => {
        try {
            await mockPlatformApi.requestLogs.clear(1000)
            message.success('已清理')
            loadData()
        } catch (e) {
            message.error('清理失败')
        }
    }

    const columns = [
        {
            title: '时间', dataIndex: 'createTime', key: 'createTime', width: 170,
            render: (v) => v ? new Date(v).toLocaleString() : '-'
        },
        {
            title: '环境', dataIndex: 'envCode', key: 'envCode', width: 100,
            render: (v) => <Tag>{v}</Tag>
        },
        {
            title: '方法', dataIndex: 'requestMethod', key: 'requestMethod', width: 80,
            render: (v) => <Tag color="blue">{v}</Tag>
        },
        {
            title: '路径', dataIndex: 'requestPath', key: 'requestPath', ellipsis: true,
            render: (v) => <Text code>{v}</Text>
        },
        {
            title: '状态', dataIndex: 'responseStatus', key: 'responseStatus', width: 90, align: 'center',
            render: (v) => v ? <Tag color={v >= 400 ? 'red' : 'green'}>{v}</Tag> : '-'
        },
        {
            title: '匹配', dataIndex: 'matched', key: 'matched', width: 90, align: 'center',
            render: (v) => v === 1 ? <Tag color="green">命中</Tag> : <Tag color="red">未匹配</Tag>
        },
        {
            title: '回退', dataIndex: 'fallbackType', key: 'fallbackType', width: 100,
            render: (v) => v ? <Tag color={STATUS_COLORS[v]}>{v}</Tag> : '-'
        },
        {
            title: '耗时', dataIndex: 'durationMs', key: 'durationMs', width: 80, align: 'center',
            render: (v) => `${v || 0}ms`
        },
        {
            title: 'Mock', dataIndex: 'mockCode', key: 'mockCode', width: 160, ellipsis: true,
            render: (v) => v ? <Text code>{v}</Text> : '-'
        },
        {
            title: '操作', key: 'action', width: 80,
            render: (_, record) => (
                <Button size="small" type="link" icon={<EyeOutlined/>}
                        onClick={() => handleDetail(record)}>详情</Button>
            )
        },
    ]

    return (
        <div>
            <Alert type="info" showIcon style={{marginBottom: 16}}
                   message="Mock 请求日志 - 排查未匹配请求"
                   description="每一次请求都会留痕。包括请求/响应体、是否匹配 Mock、回退类型、耗时、场景状态等。便于排查“为什么这个请求没有 Mock 命中”问题。"
            />

            {stats && (
                <Row gutter={16} style={{marginBottom: 16}}>
                    <Col span={6}><Card><Statistic title="总请求数" value={stats.total || 0}/></Card></Col>
                    <Col span={6}><Card><Statistic title="Mock 命中" value={stats.matched || 0}
                                                   valueStyle={{color: '#52c41a'}}/></Card></Col>
                    <Col span={6}><Card><Statistic title="未匹配" value={stats.unmatched || 0}
                                                   valueStyle={{color: '#ff4d4f'}}/></Card></Col>
                    <Col span={6}><Card><Statistic title="命中率"
                                                   value={`${stats.total > 0 ? Math.round((stats.matched / stats.total) * 100) : 0}%`}/></Card></Col>
                </Row>
            )}

            <Space style={{marginBottom: 16}}>
                <Input
                    placeholder="搜索路径" prefix={<SearchOutlined/>} allowClear
                    style={{width: 240}} value={filterPath}
                    onChange={(e) => setFilterPath(e.target.value)}
                    onPressEnter={loadData}
                />
                <Select
                    placeholder="匹配状态" allowClear style={{width: 140}}
                    value={filterMatched}
                    onChange={setFilterMatched}
                    options={[
                        {value: 1, label: '已命中'},
                        {value: 0, label: '未匹配'},
                    ]}
                />
                <Button icon={<ReloadOutlined/>} onClick={loadData}>查询</Button>
                <Popconfirm title="确认清理，保留最近 1000 条?" onConfirm={handleClear}>
                    <Button icon={<DeleteOutlined/>} danger>清理</Button>
                </Popconfirm>
            </Space>

            <Table
                dataSource={logs}
                columns={columns}
                rowKey="id"
                loading={loading}
                pagination={{pageSize: 20}}
                scroll={{x: 1300}}
                size="small"
            />

            <Drawer
                title="请求详情"
                open={detailOpen}
                onClose={() => setDetailOpen(false)}
                width={750}
            >
                {detail && (
                    <div>
                        <Descriptions column={2} size="small" bordered style={{marginBottom: 16}}>
                            <Descriptions.Item label="环境">{detail.envCode}</Descriptions.Item>
                            <Descriptions.Item label="方法"><Tag
                                color="blue">{detail.requestMethod}</Tag></Descriptions.Item>
                            <Descriptions.Item label="路径" span={2}><Text
                                code>{detail.requestPath}</Text></Descriptions.Item>
                            <Descriptions.Item label="状态码">
                                <Tag
                                    color={detail.responseStatus >= 400 ? 'red' : 'green'}>{detail.responseStatus}</Tag>
                            </Descriptions.Item>
                            <Descriptions.Item label="耗时">{detail.durationMs}ms</Descriptions.Item>
                            <Descriptions.Item label="匹配">
                                {detail.matched === 1 ? <Tag color="green">命中</Tag> : <Tag color="red">未匹配</Tag>}
                            </Descriptions.Item>
                            <Descriptions.Item label="回退">{detail.fallbackType || '-'}</Descriptions.Item>
                            <Descriptions.Item label="Mock" span={2}>{detail.mockCode || '-'}</Descriptions.Item>
                            <Descriptions.Item label="场景状态"
                                               span={2}>{detail.scenarioState || '-'}</Descriptions.Item>
                            <Descriptions.Item label="客户端 IP">{detail.clientIp || '-'}</Descriptions.Item>
                            <Descriptions.Item label="User-Agent" ellipsis>{detail.userAgent || '-'}</Descriptions.Item>
                            <Descriptions.Item label="时间" span={2}>
                                {detail.createTime ? new Date(detail.createTime).toLocaleString() : '-'}
                            </Descriptions.Item>
                        </Descriptions>

                        <Text strong>请求头:</Text>
                        <pre style={{
                            background: '#f5f5f5', padding: 12, borderRadius: 4,
                            maxHeight: 150, overflow: 'auto', fontSize: 12, fontFamily: 'monospace',
                            whiteSpace: 'pre-wrap',
                        }}>
              {detail.requestHeaders || '(无)'}
            </pre>

                        <Text strong style={{marginTop: 12, display: 'block'}}>请求体:</Text>
                        <pre style={{
                            background: '#f5f5f5', padding: 12, borderRadius: 4,
                            maxHeight: 200, overflow: 'auto', fontSize: 12, fontFamily: 'monospace',
                            whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                        }}>
              {detail.requestBody || '(无)'}
            </pre>

                        <Text strong style={{marginTop: 12, display: 'block'}}>响应体:</Text>
                        <pre style={{
                            background: '#f5f5f5', padding: 12, borderRadius: 4,
                            maxHeight: 300, overflow: 'auto', fontSize: 12, fontFamily: 'monospace',
                            whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                        }}>
              {detail.responseBody || '(无)'}
            </pre>
                    </div>
                )}
            </Drawer>
        </div>
    )
}
