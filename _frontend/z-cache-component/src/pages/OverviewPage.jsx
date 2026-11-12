import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Row, Statistic, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {cacheApi, cacheErrorText, authorityTag, fmtUptime} from '../services/api'

const DB_COLUMNS = [
    {title: '库', dataIndex: 'db', key: 'db', width: 70, render: (v) => `db${v}`},
    {
        title: 'key 数', dataIndex: 'keys', key: 'keys', width: 120,
        render: (v) => (v > 0 ? <Tag color="blue">{v}</Tag> : <span style={{color: '#94a3b8'}}>0</span>)
    },
]

export default function OverviewPage() {
    const [data, setData] = useState(null)
    const [inst, setInst] = useState(null)
    const [error, setError] = useState(null)
    const [loading, setLoading] = useState(false)

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const [o, i] = await Promise.all([cacheApi.overview(), cacheApi.instance()])
            setData(o)
            setInst(i)
            setError(null)
        } catch (e) {
            setData(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        load()
    }, [load])

    const emb = data?.embedded ?? {}
    const cli = data?.client ?? {}
    const foreign = !!(data?.foreignListenerSuspected || inst?.foreignListenerSuspected)
    const diverges = !!data?.authorityDiverges
    const authority = authorityTag(cli.authority)

    return (
        <div>
            {error && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message={`读不到总览：${cacheErrorText(error)}`}/>
            )}

            {foreign && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message={`配置端口上应答的是外部进程，不是本 JVM —— 下方 client 数字来自 ${cli.target || '外部实例'}`}/>
            )}

            {!foreign && diverges && (
                <Alert type="warning" showIcon style={{marginBottom: 12}}
                       message={`两路读数不一致：进程内 ${emb.dbsizeTotal ?? '-'} / client ${cli.dbsize ?? '-'}`}/>
            )}

            <Row gutter={16}>
                <Col span={6}>
                    <Card size="small" loading={loading}>
                        <Statistic title="key 总数" value={cli.dbsize ?? emb.dbsizeTotal ?? '-'}/>
                    </Card>
                </Col>
                <Col span={6}>
                    <Card size="small" loading={loading}>
                        <Statistic title="命中率"
                                   value={emb.hitRate != null ? `${(emb.hitRate * 100).toFixed(2)}%` : '-'}/>
                    </Card>
                </Col>
                <Col span={6}>
                    <Card size="small" loading={loading}>
                        <Statistic title="当前连接" value={emb.connectedClients ?? '-'}/>
                    </Card>
                </Col>
                <Col span={6}>
                    <Card size="small" loading={loading}>
                        <Statistic title="内嵌服务"
                                   value={emb.running ? '运行中' : '未启动'}
                                   valueStyle={{color: emb.running ? '#16a34a' : '#dc2626'}}/>
                    </Card>
                </Col>
            </Row>

            <Row gutter={16} style={{marginTop: 16}}>
                <Col span={12}>
                    <Card title="进程内指标" size="small" loading={loading}>
                        <Row gutter={8}>
                            <Col span={8}><Statistic title="命中 / 未命中"
                                                     value={emb.hits != null ? `${emb.hits} / ${emb.misses ?? '-'}` : '-'}/></Col>
                            <Col span={8}><Statistic title="累计命令" value={emb.totalCommands ?? '-'}/></Col>
                            <Col span={8}><Statistic title="淘汰 key" value={emb.evictions ?? '-'}/></Col>
                        </Row>
                    </Card>
                </Col>
                <Col span={12}>
                    <Card title="连接信息" size="small" loading={loading}
                          extra={<Tag color={authority.color}>{cli.authority ?? '-'}</Tag>}>
                        <Row gutter={8}>
                            <Col span={8}><Statistic title="连接目标" value={cli.target || '-'}
                                                     valueStyle={{fontSize: 14}}/></Col>
                            <Col span={8}><Statistic title="RESP 已连接"
                                                     value={cli.transportConnected ? '是' : '否'}
                                                     valueStyle={{color: cli.transportConnected ? '#16a34a' : '#d97706'}}/></Col>
                            <Col span={8}><Statistic title="运行时长" value={fmtUptime(emb.storeUptimeSeconds)}
                                                     valueStyle={{fontSize: 14}}/></Col>
                        </Row>
                    </Card>
                </Col>
            </Row>

            <Card title="每库 key 数" size="small" style={{marginTop: 16}}
                  extra={<Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>刷新</Button>}>
                {(!emb.perDb || !emb.perDb.length) ? (
                    <Alert type="info" message="暂无按库数据（内嵌服务未启动）"/>
                ) : (
                    <Table size="small" rowKey="db" columns={DB_COLUMNS} dataSource={emb.perDb}
                           pagination={false}
                           summary={() => <Table.Summary.Row>
                               <Table.Summary.Cell index={0}>合计</Table.Summary.Cell>
                               <Table.Summary.Cell index={1}>{emb.dbsizeTotal}</Table.Summary.Cell>
                           </Table.Summary.Row>}/>
                )}
            </Card>
        </div>
    )
}
