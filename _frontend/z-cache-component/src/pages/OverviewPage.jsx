import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Descriptions, Row, Statistic, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {cacheApi, cacheErrorText, authorityTag, fmtUptime} from '../services/api'
import {PageHeader} from '@/common/components/ui'

const DB_COLUMNS = [
    {title: '库', dataIndex: 'db', key: 'db', width: 70, render: (v) => `db${v}`},
    {
        title: 'key 数', dataIndex: 'keys', key: 'keys', width: 120,
        render: (v) => (v > 0 ? <Tag color="blue">{v}</Tag> : <span style={{color: '#94a3b8'}}>0</span>)
    },
]

/**
 * 缓存总览 —— 双路对账。
 *
 * 这一页的形状和别的中间件页不一样，值得先说明白，因为它就是本卡的"探针"：
 * 同一个 JVM 里用两条**互不相关**的路去数同一件事，然后把它们并排放：
 *   路 A embedded = 反射拿内嵌 RedisServer 的 MemoryStore，直接调 dbsize()/getDbCount()。
 *                    它不开 socket，所以它报的数**只可能是本 JVM 的**。
 *   路 B client    = 走业务共用的 zCacheClient 发 RESP DBSIZE。它报的是"业务此刻看到的数"。
 * 两者的关系就是结论：
 *   - 内嵌 bind 成功后，A 与 B 必须恒等（同一个 store，两种数法）。不等 ⇒ 本 controller 在编数据。
 *   - 现在 A 的 running=false、B 连的是 6379 上那个外部进程 ⇒ authorityDiverges 会是 true，
 *     这正是 B1 的可视化形态，不是 bug。
 * 所以这一屏不允许出现"一个漂亮的总数"，只允许出现"两个数 + 它们为什么可以不等"。
 */
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

    return (
        <div>
            <PageHeader title="缓存总览"
                        subtitle="双路对账：进程内 MemoryStore（路 A） vs zCacheClient 走 RESP（路 B）— GET /api/cache/overview"
                        extra={<Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>刷新</Button>}/>

            {error && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message={`读不到总览：${cacheErrorText(error)}`}
                       description="overview 与 __instance 一起失败 ⇒ 是 z-opc 侧路由/鉴权问题；只有 overview 失败而「实例与三态」能开，则是取数那一层的问题。"/>
            )}

            {foreign && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message="下面 client 一栏的数字来自外部进程，不是本 JVM"
                       description="本 JVM 的 RedisServer 没 bind 上配置端口，而该端口 TCP 可连 ⇒ 应答者是别的进程（实测是 5 天前的另一个 z-opc boot jar）。embedded 一栏才是本 JVM 的真相，而它现在必然是空的。"/>
            )}

            <Row gutter={16}>
                <Col span={12}>
                    <Card title={<span>路 A · 进程内 MemoryStore <Tag color="blue">不可能跨进程</Tag></span>}
                          size="small" loading={loading}>
                        <Row gutter={8}>
                            <Col span={12}>
                                <Statistic title="dbsize（本 JVM 全部库）" value={emb.dbsizeTotal ?? '-'}/>
                            </Col>
                            <Col span={12}>
                                <Statistic title="内嵌 server 在跑"
                                           value={emb.running ? '是' : '否'}
                                           valueStyle={{color: emb.running ? '#16a34a' : '#dc2626', fontSize: 20}}/>
                            </Col>
                        </Row>
                        <Descriptions size="small" column={1} style={{marginTop: 12}}
                                      items={[
                                          {key: 'reflected', label: '反射取到 RedisServer', children: emb.serverReflected ? <Tag color="success">是</Tag> : <Tag color="warning">否</Tag>},
                                          {key: 'reflectErr', label: '反射失败原因', children: emb.reflectError ? <code>{emb.reflectError}</code> : '-'},
                                          {key: 'port', label: '内嵌 RedisServer.getPort()', children: emb.port ?? '-'},
                                          {key: 'dbCount', label: '库数量（DEFAULT_DB_COUNT）', children: emb.dbCount ?? '-'},
                                          {key: 'hits', label: '命中 / 未命中', children: `${emb.hits ?? '-'} / ${emb.misses ?? '-'}`},
                                          {key: 'hitRate', label: '命中率', children: emb.hitRate != null ? `${(emb.hitRate * 100).toFixed(2)}%` : '-'},
                                          {key: 'evict', label: '被淘汰 key', children: emb.evictions ?? '-'},
                                          {key: 'cmds', label: '累计命令数', children: emb.totalCommands ?? '-'},
                                          {key: 'conns', label: '累计连接 / 当前连接', children: `${emb.totalConnections ?? '-'} / ${emb.connectedClients ?? '-'}`},
                                          {key: 'max', label: 'maxEntries（0=不限）', children: emb.maxEntries ?? '-'},
                                          {
                                              key: 'uptime', label: 'store 存在时长',
                                              children: <span>{fmtUptime(emb.storeUptimeSeconds)}
                                                  <span style={{color: '#b45309'}}>（自构造起算，不代表在线时长）</span></span>
                                          },
                                          {key: 'unavailable', label: '不可用原因', children: emb.unavailableReason ?? <Tag color="success">可用</Tag>},
                                      ]}/>
                    </Card>
                </Col>

                <Col span={12}>
                    <Card title={<span>路 B · zCacheClient（RESP DBSIZE） <Tag color="purple">业务看到的数</Tag></span>}
                          size="small" loading={loading}>
                        <Row gutter={8}>
                            <Col span={12}>
                                <Statistic title="dbsize" value={cli.dbsize ?? '-'}/>
                            </Col>
                            <Col span={12}>
                                <Statistic title="RESP 已连接"
                                           value={cli.transportConnected ? '是' : '否'}
                                           valueStyle={{color: cli.transportConnected ? '#16a34a' : '#d97706', fontSize: 20}}/>
                            </Col>
                        </Row>
                        <Descriptions size="small" column={1} style={{marginTop: 12}}
                                      items={[
                                          {key: 'present', label: 'bean 存在', children: cli.present ? <Tag color="success">是</Tag> : <Tag color="warning">否</Tag>},
                                          {key: 'target', label: '连接目标 host:port/db', children: cli.target ? <code>{cli.target}</code> : '-'},
                                          {
                                              key: 'authority', label: '应答者归属',
                                              children: <span>
                                                  <Tag color={authorityTag(cli.authority).color}>{cli.authority ?? '-'}</Tag>
                                                  <span style={{fontSize: 12, color: '#64748b'}}>{authorityTag(cli.authority).text}</span>
                                              </span>
                                          },
                                          {key: 'dbsizeErr', label: 'DBSIZE 报错', children: cli.dbsizeError ? <code>{cli.dbsizeError}</code> : '-'},
                                          {key: 'probeErr', label: '连接探测报错', children: cli.probeError ? <code>{cli.probeError}</code> : '-'},
                                      ]}/>
                    </Card>
                </Col>
            </Row>

            <Card style={{marginTop: 16}} title="双路对账结论（这一格就是本卡的保真探针）"
                  extra={diverges
                      ? <Tag color="error">authorityDiverges = true</Tag>
                      : <Tag color="success">authorityDiverges = false</Tag>}>
                <Alert showIcon
                       type={diverges ? 'error' : (emb.running ? 'success' : 'warning')}
                       message={
                           diverges
                               ? `路 A = ${emb.dbsizeTotal ?? '-'}，路 B = ${cli.dbsize ?? '-'} —— 两路不一致`
                               : (emb.running
                                   ? `两路一致（都是 ${emb.dbsizeTotal ?? '-'}）：内嵌 server 真在跑，且业务连的就是它`
                                   : '路 A 无数据可比（内嵌没 bind），所以现在还不能下"一致/不一致"的结论')
                       }
                       description={
                           <span>
                               判定式：<code>路 A 可用(running) 且 路 B 有应答 且 A.dbsize != B.dbsize</code>。
                               内嵌 bind 成功之后，A 与 B 是同一个 store 的两种数法，<b>必须恒等</b>；
                               一旦不等，就等于证明这个 controller 在自造数据。
                               当前不等的原因是 B1（6379 被另一个进程占着）而不是数据造假 ——
                               区分方式就是 <code>foreignListenerSuspected</code> 这一格。
                           </span>
                       }/>
            </Card>

            <Card title="每库 key 数（只有路 A 能按库统计；路 B 不能发 SELECT）" style={{marginTop: 16}}
                  extra={<Tag color="blue">embedded 侧，16 库</Tag>}>
                {(!emb.perDb || !emb.perDb.length) ? (
                    <Alert type="info" showIcon
                           message="路 A 的 store 现在拿不到 ⇒ 没有按库数据"
                           description="原因见上面「不可用原因」/「反射失败原因」两格。这不是「缓存被清空了」。"/>
                ) : (
                    <Table size="small" rowKey="db" columns={DB_COLUMNS} dataSource={emb.perDb}
                           pagination={false}
                           summary={() => <Table.Summary.Row>
                               <Table.Summary.Cell index={0}>合计</Table.Summary.Cell>
                               <Table.Summary.Cell index={1}>{emb.dbsizeTotal}</Table.Summary.Cell>
                           </Table.Summary.Row>}/>
                )}
            </Card>

            <Card title="口径说明" style={{marginTop: 16}}>
                <div style={{fontSize: 12, color: '#64748b', lineHeight: 1.9}}>
                    · 本页所有数字都无前端缓存、每次刷新重新读；后端不做任何聚合、补零或估算。
                    <br/>· 内存 / 命中率 / 累计命令等运行指标在「统计与命令」页（路 A 走 Runtime+MemoryStore，路 B 走 INFO）。
                    <br/>· 按 key 浏览在「键浏览」页；那里同样只有读操作，没有任何设值 / 删除 / 清空入口。
                    <br/>· 若 <code>embedded.running=false</code>，本页"路 A"一栏就是空的 —— 含义是"本 JVM 没在服务"，
                    <b>不是</b>"缓存里没有数据"。这两件事的区别只由「实例与三态」页那四格决定。
                </div>
            </Card>
        </div>
    )
}
