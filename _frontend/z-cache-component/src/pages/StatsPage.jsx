import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Descriptions, Row, Select, Space, Statistic, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {cacheApi, cacheErrorText, authorityTag, fmtBytes} from '../services/api'
import {PageHeader} from '@/common/components/ui'

const HOT_COLUMNS = [
    {title: 'key', dataIndex: 'key', key: 'key', ellipsis: true, render: (v) => <code>{v}</code>},
    {
        title: '访问次数', dataIndex: 'accessCount', key: 'accessCount', width: 110,
        sorter: (a, b) => a.accessCount - b.accessCount,
        render: (v) => <Tag color="geekblue">{v}</Tag>
    },
    {title: 'LFU 计数', dataIndex: 'lfuCounter', key: 'lfuCounter', width: 100},
    {
        title: '已过期', dataIndex: 'expired', key: 'expired', width: 90,
        render: (v) => (v ? <Tag color="warning">是</Tag> : <Tag color="success">否</Tag>)
    },
    {title: 'TTL', dataIndex: 'ttlSeconds', key: 'ttlSeconds', width: 110, render: (v) => (v === -1 ? '常驻' : `${v}s`)},
    {
        title: '字节', dataIndex: 'byteLength', key: 'byteLength', width: 100,
        render: (v) => fmtBytes(v)
    },
    {
        title: '最后访问', dataIndex: 'lastAccessTime', key: 'lastAccessTime', width: 180,
        render: (v) => (v ? new Date(v).toLocaleString() : '-')
    },
]

function InfoSection({name, fields}) {
    if (!fields) return null
    return (
        <Card size="small" title={<span># {name}</span>} style={{marginBottom: 12}}>
            <Descriptions size="small" column={2} bordered
                          items={Object.entries(fields).map(([k, v]) => ({key: k, label: k, children: <code>{v}</code>}))}/>
        </Card>
    )
}

/**
 * "INFO 里的版本号能不能拿来对身份" —— 答案是能，但要换个对法。
 *
 * z-cache_version / tcp_port 是命令处理器源码里的字面量，所以<b>不随运行时状态变</b>（拿它对不上
 * 你连的端口），这页原来只说到这一层就停了。但字面量还有一个没写的性质：它在<b>编译期</b>被钉进
 * class 常量池，于是"本 JVM 会印什么" vs "对面印了什么"就是一个身份指纹。实测这一次就对出了真相：
 * 6379 上应答的是 z-cache_version:1.0.0 且整段没有 tcp_port 行，而本次 classpath 里的
 * z-cache-core-1.3.1 会印 1.0.2 + tcp_port:6379 ⇒ 对面是<b>更早一次构建</b>留下的常驻进程。
 * 这条结论不依赖 lsof，正好补上 z-cache 的死穴 —— 内嵌从来没 bind 成功，pid 归属这条路根本走不通。
 */
function BuildFingerprintAlert({fp}) {
    const verdict = fp.verdict
    const color = verdict === 'DIFFERENT_BUILD' ? 'error' : (verdict === 'SAME_BUILD_CANDIDATE' ? 'warning' : 'info')
    return (
        <Alert type={color} showIcon style={{marginTop: 4}}
               message={`构建指纹：${verdict === 'DIFFERENT_BUILD' ? '应答者不是本次构建的产物'
                       : verdict === 'SAME_BUILD_CANDIDATE' ? '版本号一致，但不足以定身份'
                       : '指纹不可用（一边取不到值，页面不猜）'}`}
               description={<>
                   <div style={{fontSize: 12}}>
                       本次构建会印 <code>z-cache_version:{fp.ownBuildConstants?.['z-cache_version'] ?? '?'}</code>
                       ／ 对面印 <code>z-cache_version:{fp.responderVersion ?? '?'}</code>
                       ；<code>tcp_port</code>：本次 <code>{fp.ownBuildConstants?.tcp_port ?? '?'}</code>
                       ／ 对面 <code>{fp.responderTcpPort ?? '?'}</code>
                   </div>
                   <div style={{fontSize: 12, color: '#64748b', marginTop: 4}}>{fp.note}</div>
                   <div style={{fontSize: 11, color: '#94a3b8', marginTop: 2}}>{fp.ownConstantsSource}</div>
               </>}/>
    )
}

/**
 * 统计与热 key —— GET /api/cache/info + /api/cache/hotkeys。
 *
 * 这页刻意把"两个口径的运行指标"分栏放，不合成一个数：
 *   路 A（embedded）= 直接读 MemoryStore 的计数器 + Runtime 的堆。它衡量的是**本 JVM 的内嵌 server**，
 *                     内嵌没起时这一栏整块是空的（不是 0）。
 *   路 B（INFO）     = 上游 CommandHandler.handleInfo 自己拼的文本。它衡量的是**应答那条 RESP 的 server**，
 *                     当前是外部进程，所以它的 uptime / 命中率其实是别人的。
 * INFO 里有两个字段是<b>硬编码字面量</b>（z-cache_version=1.0.2、tcp_port=6379），
 * 页面直接把它们标成红字，防止有人拿它去对 jar 版本或对端口。
 *
 * 热 key 只有路 A 有：MemoryStore.ValueWrapper 自带 accessCount / lastAccessTime / lfuCounter，
 * 是 z-cache 自己的 LFU 埋点，不是本页统计的 —— 所以 scopeNote 原样显示，不隐藏"只覆盖 string 类型"这个限制。
 */
export default function StatsPage() {
    const [info, setInfo] = useState(null)
    const [hot, setHot] = useState(null)
    const [inst, setInst] = useState(null)
    const [error, setError] = useState(null)
    const [hotError, setHotError] = useState(null)
    const [db, setDb] = useState(0)
    const [loading, setLoading] = useState(false)

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const [i, ins] = await Promise.all([cacheApi.info(), cacheApi.instance()])
            setInfo(i)
            setInst(ins)
            setError(null)
        } catch (e) {
            setInfo(null)
            setError(e)
        }
        try {
            setHot(await cacheApi.hotkeys({db, limit: 50}))
            setHotError(null)
        } catch (e) {
            setHot(null)
            setHotError(e)
        } finally {
            setLoading(false)
        }
    }, [db])

    useEffect(() => {
        load()
    }, [load])

    const emb = info?.embedded
    const foreign = !!(inst?.foreignListenerSuspected)
    const sections = info?.sections ?? {}
    const buildFingerprint = info?.responderVsOwnBuild ?? null

    return (
        <div>
            <PageHeader title="统计与热 key"
                        subtitle="GET /api/cache/info（路 B 的 INFO 原文 + 分节）与 /api/cache/hotkeys（路 A 的 LFU 埋点）"/>

            {error && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message={`读不到统计：${cacheErrorText(error)}`}/>
            )}

            {foreign && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message="下面 INFO 一栏是外部进程自报的，不是本 JVM 的运行时指标"
                       description="authorityOfClient = EXTERNAL_PROCESS。要看本 JVM 的数，只有路 A 那一栏（它现在可能是空的，因为内嵌没 bind）。"/>
            )}

            <Row gutter={16}>
                <Col span={12}>
                    <Card title={<span>路 A · 进程内计数 <Tag color="blue">本 JVM 独有</Tag></span>} size="small"
                          loading={loading}>
                        {!emb ? (
                            <Alert type="warning" showIcon
                                   message="路 A 的 store 拿不到 ⇒ 没有进程内运行指标"
                                   description="内嵌 RedisServer 没 bind 成功（或反射失败）。这里显示为空是对的，显示 0 才是错的。"/>
                        ) : (
                            <>
                                <Row gutter={8}>
                                    <Col span={8}><Statistic title="dbsize" value={emb.dbsizeTotal}/></Col>
                                    <Col span={8}><Statistic title="命中率"
                                                             value={`${(emb.hitRate * 100).toFixed(2)}%`}/></Col>
                                    <Col span={8}><Statistic title="淘汰" value={emb.evictions}/></Col>
                                </Row>
                                <Descriptions size="small" column={1} style={{marginTop: 12}} bordered
                                              items={[
                                                  {key: 'hits', label: 'hits / misses', children: `${emb.hits} / ${emb.misses}`},
                                                  {key: 'cmds', label: '累计命令', children: emb.totalCommands},
                                                  {key: 'clients', label: '当前连接', children: emb.connectedClients},
                                                  {key: 'max', label: 'maxEntries', children: emb.maxEntries},
                                                  {key: 'heap', label: 'JVM 堆（已用/上限）', children: `${fmtBytes(emb.jvmHeapUsedBytes)} / ${fmtBytes(emb.jvmHeapMaxBytes)}`},
                                                  {key: 'cpu', label: '可用核数', children: emb.jvmAvailableProcessors},
                                              ]}/>
                            </>
                        )}
                    </Card>
                </Col>
                <Col span={12}>
                    <Card title={<span>路 B · 上游 INFO <Tag color={authorityTag(info?.authorityOfClient).color}>{info?.authorityOfClient ?? '-'}</Tag></span>}
                          size="small" loading={loading}>
                        {info?.clientError && (
                            <Alert type="error" showIcon style={{marginBottom: 8}} message={info.clientError}/>
                        )}
                        {info?.clientNote && <Alert type="warning" showIcon style={{marginBottom: 8}} message={info.clientNote}/>}
                        {Object.keys(sections).length === 0 && !info?.clientError && !info?.clientNote && (
                            <Alert type="info" showIcon message="INFO 为空 —— 应答者没返回任何分节"/>
                        )}
                        {Object.entries(sections).map(([name, fields]) => (
                            <InfoSection key={name} name={name} fields={fields}/>
                        ))}
                        {info?.hardcodedInUpstream && (
                            <Alert type="error" showIcon style={{marginTop: 4}}
                                   message="INFO 里这两个字段是写死的字面量，不要拿去对照 jar 版本或端口"
                                   description={<ul style={{margin: 0, paddingLeft: 18}}>
                                       {info.hardcodedInUpstream.map((t) => <li key={t} style={{fontSize: 12}}>{t}</li>)}
                                   </ul>}/>
                        )}
                        {buildFingerprint && <BuildFingerprintAlert fp={buildFingerprint}/>}
                    </Card>
                </Col>
            </Row>

            {info?.raw && (
                <Card title="INFO 原文（未加工，逐字符来自上游）" size="small" style={{marginTop: 16}}>
                    <pre style={{margin: 0, maxHeight: 260, overflow: 'auto', fontSize: 12}}>{info.raw}</pre>
                </Card>
            )}

            <Card title="热 key（路 A 的 LFU 埋点，只覆盖 string 类型）"
                  style={{marginTop: 16}}
                  size="small"
                  extra={<Space>
                      {/* Redis 协议固定 16 个库；MemoryStore 构造里 dbCount 也夹在 1..16（源码 100-106 行）。
                          这里能选库是因为**路 A 读的是进程内的第 db 张表**，不需要发 SELECT，所以不碰业务连接。 */}
                      <span style={{fontSize: 12, color: '#64748b'}}>库</span>
                      <Select size="small" style={{width: 96}} value={db} onChange={setDb}
                              options={Array.from({length: 16}, (_, i) => ({value: i, label: `db${i}`}))}/>
                      <Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>刷新</Button>
                  </Space>}>
                {hotError && (
                    <Alert type="warning" showIcon style={{marginBottom: 8}}
                           message={`读不到热 key：${cacheErrorText(hotError)}`}
                           description="后端只有路 A 能出这个视图，所以「反射不到内嵌 RedisServer」= 503。这不是「没有热 key」。"/>
                )}
                {hot && (
                    <>
                        <div style={{fontSize: 12, color: '#64748b', marginBottom: 8}}>
                            db{hot.db} · string key 共 {hot.stringKeyTotal} 个{hot.truncated ? `（只显示前 50，已截断）` : ''}
                            · <Tag color={authorityTag(hot.authority).color}>{hot.authority}</Tag>
                        </div>
                        <Table size="small" rowKey="key" columns={HOT_COLUMNS} dataSource={hot.rows ?? []}
                               pagination={{size: 'small', pageSize: 20}}
                               locale={{emptyText: `db${db} 的 string store 里一个 key 都没有（路 A 口径，确定没跨进程）`}}/>
                        <div style={{marginTop: 8, fontSize: 12, color: '#94a3b8'}}>
                            {hot.scopeNote} ／ {hot.sourceNote}
                        </div>
                    </>
                )}
            </Card>
        </div>
    )
}
