/** 缓存总览：/cache/overview + /cache/info。 */
import {useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Descriptions, Row, Space, Spin, Statistic, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {cacheApi} from '../services/api'

const {Title, Paragraph} = Typography

function fmtNum(n) {
    if (n == null) return '—'
    return typeof n === 'number' ? n.toLocaleString('zh-CN') : String(n)
}

export default function Overview() {
    const [overview, setOverview] = useState(null)
    const [info, setInfo] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = async () => {
        setLoading(true)
        try {
            const [o, i] = await Promise.all([
                cacheApi.overview().catch(() => null),
                cacheApi.info().catch(() => null),
            ])
            setOverview(o)
            setInfo(i)
            setError(null)
        } catch (e) {
            setError(e?.message || String(e))
        } finally { setLoading(false) }
    }

    useEffect(() => {
        fetch()
        const t = setInterval(fetch, 15000)
        return () => clearInterval(t)
    }, [])

    return (
        <div>
            <Space style={{marginBottom: 16}}>
                <Title level={4} style={{margin: 0}}>缓存总览</Title>
                <Button icon={<ReloadOutlined/>} onClick={fetch} loading={loading}>刷新</Button>
                <Typography.Text type="secondary">15s 自动刷新</Typography.Text>
            </Space>
            <Paragraph type="secondary">/cache/overview + /cache/info：键数 / 内存 / 命中率。</Paragraph>

            {error && <Alert type="error" showIcon style={{marginBottom: 16}} message="后端未连接" description={error}/>}
            {loading && !overview && <Spin/>}

            {overview && (
                <Row gutter={16} style={{marginBottom: 16}}>
                    <Col span={6}><Card><Statistic title="键总数" value={fmtNum(overview.keyCount ?? overview.totalKeys)}/></Card></Col>
                    <Col span={6}><Card><Statistic title="内存使用" value={fmtNum(overview.usedMemory ?? overview.memoryUsed)}/></Card></Col>
                    <Col span={6}><Card><Statistic title="命中率" value={overview.hitRate != null ? `${(overview.hitRate * 100).toFixed(1)}%` : '—'}/></Card></Col>
                    <Col span={6}><Card><Statistic title="过期键" value={fmtNum(overview.expiredKeys)}/></Card></Col>
                </Row>
            )}

            {info && (
                <Card title="Redis INFO">
                    <Descriptions column={2} bordered size="small">
                        {Object.entries(info).slice(0, 20).map(([k, v]) => (
                            <Descriptions.Item key={k} label={k}>
                                {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                            </Descriptions.Item>
                        ))}
                    </Descriptions>
                </Card>
            )}
        </div>
    )
}
