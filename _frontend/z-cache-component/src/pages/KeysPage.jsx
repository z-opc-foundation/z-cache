import {useCallback, useEffect, useMemo, useState} from 'react'
import {
    Alert, Badge, Button, Card, Descriptions, Drawer, Empty, Input, InputNumber,
    Radio, Select, Space, Table, Tag, Tooltip,
} from 'antd'
import {ReloadOutlined, SearchOutlined} from '@ant-design/icons'
import {cacheApi, cacheErrorText, authorityTag, fmtBytes, fmtTtl, asText} from '../services/api'
import {PageHeader} from '@/common/components/ui'

/** MemoryStore.DataType 的实际取值（z-cache-core 1.3.1 枚举：NONE/STRING/HASH/LIST/SET/ZSET） */
const TYPE_COLOR = {
    STRING: 'blue', HASH: 'purple', LIST: 'cyan', SET: 'orange', ZSET: 'magenta', none: 'default',
}

const TYPE_OPTIONS = [
    {value: 'STRING', label: 'STRING'}, {value: 'HASH', label: 'HASH'},
    {value: 'LIST', label: 'LIST'}, {value: 'SET', label: 'SET'},
    {value: 'ZSET', label: 'ZSET'}, {value: 'none', label: 'NONE'},
]

function renderCell(v) {
    if (v == null) return <span style={{color: '#94a3b8'}}>nil</span>
    if (typeof v === 'object') {
        if (v.notUtf8) {
            return <Tooltip title="非 UTF-8 字节（多半是序列化后的对象），这里给 base64">
                <Tag color="volcano">binary</Tag><code style={{fontSize: 11}}>{String(v.base64).slice(0, 60)}…</code>
            </Tooltip>
        }
        return <pre style={{margin: 0, fontSize: 11, whiteSpace: 'pre-wrap'}}>{JSON.stringify(v, null, 2)}</pre>
    }
    return <span style={{wordBreak: 'break-all'}}>{String(v)}</span>
}

/**
 * 键浏览 —— GET /api/cache/keys + /api/cache/key。
 *
 * source 三选一是这一页的核心，不是装饰：
 *   auto      路 A 可用就用路 A（进程内，权威）
 *   embedded  强制路 A —— 内嵌没起时后端直接 503，页面显示"取不到"而不是"空"
 *   client    强制路 B —— 走业务那条 RESP 连接，读到的是"6379 上那个人"的数据
 * 两者对同一个 pattern 给出不同结果时，差异本身就是 B1 的证据。
 *
 * 两个必须记住的语义差别（不是 bug，是上游给的）：
 *   1. db 选择器只在 source!=client 时有效。路 B 忽略 db 并在表头下方明写原因
 *      —— 发 SELECT 会把业务共用连接永久切库，代价是所有业务的 SET/GET 写错库。
 *   2. KEYS 是 O(N) 全库扫，后端有 1000 条硬上限并回 truncated=true；
 *      页面把"截断"显示成黄标，而不是让用户以为"就只有这么多 key"。
 *
 * 这一页没有任何写入口：没有设值、没有删除、没有清空。后端也只有 @GetMapping，
 * 要发哪个 RESP 命令永远是 Java 侧的常量，HTTP 请求只能给 pattern / key / db / limit。
 */
export default function KeysPage() {
    const [source, setSource] = useState('auto')
    const [pattern, setPattern] = useState('*')
    const [db, setDb] = useState(0)
    const [limit, setLimit] = useState(200)
    const [typeFilter, setTypeFilter] = useState(null)

    const [rows, setRows] = useState([])
    const [meta, setMeta] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const [detail, setDetail] = useState(null)
    const [detailLoading, setDetailLoading] = useState(false)
    const [detailError, setDetailError] = useState(null)

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const r = await cacheApi.keys({pattern: pattern || '*', db, limit, source})
            setRows(r.keys ?? [])
            setMeta(r)
            setError(null)
        } catch (e) {
            setRows([])
            setMeta(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [pattern, db, limit, source])

    useEffect(() => {
        load()
    }, [load])

    const openDetail = useCallback(async (key) => {
        setDetail({key})
        setDetailLoading(true)
        setDetailError(null)
        try {
            const d = await cacheApi.key({key, db, source, maxElements: 50})
            setDetail(d)
        } catch (e) {
            setDetail(null)
            setDetailError(e)
        } finally {
            setDetailLoading(false)
        }
    }, [db, source])

    const shown = useMemo(() => (typeFilter ? rows.filter((r) => r.type === typeFilter) : rows), [rows, typeFilter])

    const columns = useMemo(() => [
        {
            title: 'key', dataIndex: 'key', key: 'key', ellipsis: true,
            render: (v) => <a onClick={() => openDetail(v)}><code>{v}</code></a>
        },
        {
            title: '类型', dataIndex: 'type', key: 'type', width: 110,
            // ⚠ 路 B 的 type 走 safeSend(client,"TYPE") —— 命令失败时后端回的是 {command,error} 对象，
            //   直接塞进 React 会 "Objects are not valid as a React child" 崩表。这里必须先归一。
            render: (v) => {
                const t = asText(v)
                const raw = (v && typeof v === 'object') ? JSON.stringify(v) : undefined
                return <Tag color={TYPE_COLOR[t] || 'default'} title={raw}>{t ?? '-'}</Tag>
            },
            filters: TYPE_OPTIONS.map((o) => ({text: o.label, value: o.value})),
            onFilter: (val, row) => asText(row.type) === val,
        },
        {
            title: 'TTL', dataIndex: 'ttlSeconds', key: 'ttl', width: 150,
            render: (v) => fmtTtl(v),
        },
        {
            title: '字节', dataIndex: 'byteLength', key: 'byteLength', width: 100,
            render: (v) => (v == null || v < 0 ? <span style={{color: '#94a3b8'}}>非 string</span> : fmtBytes(v)),
        },
        {
            title: '数据源', dataIndex: 'authority', key: 'authority', width: 190,
            render: (v) => <Tag color={authorityTag(v).color}>{v ?? '-'}</Tag>,
        },
    ], [openDetail])

    const clientPath = meta?.sourceUsed === 'client'

    return (
        <div>
            <PageHeader title="键浏览"
                        subtitle="只读：GET /api/cache/keys + /api/cache/key —— 无设值 / 无删除 / 无清空"/>

            <Card size="small" style={{marginBottom: 12}}>
                <Space wrap size={8}>
                    <span>pattern</span>
                    <Input style={{width: 240}} value={pattern} allowClear prefix={<SearchOutlined/>}
                           placeholder="glob：* ? [] （默认 * = 全部）"
                           onChange={(e) => setPattern(e.target.value)}
                           onPressEnter={load}/>
                    <span>db</span>
                    <Select style={{width: 100}} value={db} onChange={setDb}
                            disabled={clientPath}
                            options={Array.from({length: 16}, (_, i) => ({value: i, label: `db${i}`}))}/>
                    <span>limit</span>
                    <InputNumber style={{width: 90}} min={1} max={1000} value={limit} onChange={setLimit}/>
                    <span>source</span>
                    <Radio.Group value={source} onChange={(e) => setSource(e.target.value)}>
                        <Radio.Button value="auto">auto</Radio.Button>
                        <Radio.Button value="embedded">路 A 进程内</Radio.Button>
                        <Radio.Button value="client">路 B RESP</Radio.Button>
                    </Radio.Group>
                    <Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>查询</Button>
                </Space>
                {clientPath && (
                    <div style={{marginTop: 8, fontSize: 12, color: '#b45309'}}>
                        当前走路 B（RESP），<b>db 选择器已被忽略</b>：{meta?.dbIgnoreReason
                        || 'SELECT 会永久改掉业务共用连接的活动库，所以路 B 不切库'}
                    </div>
                )}
            </Card>

            {error && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message={`读不到 key 列表：${cacheErrorText(error)}`}
                       description={
                           <span>
                               后端 503 的含义是"这条路取不到数"，不是"缓存是空的"。
                               {source === 'embedded' && ' 你强制了路 A，而本 JVM 的内嵌 server 没 bind ⇒ 必然 503；'}
                               换 source=client 或直接看「实例与三态」页确认归属。
                           </span>
                       }/>
            )}

            <Card title={<span>key 列表 {meta && <Tag color="blue">sourceUsed = {meta.sourceUsed}</Tag>}
                {meta?.authority && <Tag color={authorityTag(meta.authority).color}>{meta.authority}</Tag>}</span>}
                  extra={meta && <Space size={8}>
                      <Badge status={meta.truncated ? 'warning' : 'default'}
                             text={meta.truncated
                                 ? `后端 KEYS 命中 ${meta.matchedTotal} 条，只取了前 ${meta.limit} 条（KEYS 是 O(N)，硬上限 1000）`
                                 : `命中 ${meta.matchedTotal ?? rows.length} 条`}
                      />
                  </Space>}>
                  <Table size="small" rowKey={(r) => `${r.key}`} loading={loading} columns={columns}
                         dataSource={shown}
                         locale={{
                             emptyText: error
                                 ? <Empty description="读取失败，见上方告警"/>
                                 : <Empty description={
                                     !meta
                                         ? '还没取到数据'
                                         : (meta.sourceUsed === 'embedded'
                                             ? '路 A 的进程内 store 里这个 pattern 没有匹配项（本 JVM 自己那份，确定没跨进程）'
                                             : '路 B 无应答或该库确实为空 —— 先看「实例与三态」确认应答者是不是本 JVM')
                                 }/>
                         }}
                         pagination={{size: 'small', pageSize: 20, showSizeChanger: true}}/>
            </Card>

            <Drawer width={720} open={!!detail} title={detail ? `key: ${detail.key}` : 'key 详情'}
                    onClose={() => setDetail(null)} destroyOnHidden>
                {detailError && (
                    <Alert type="error" showIcon message={`读详情失败：${cacheErrorText(detailError)}`}/>
                )}
                {!detail && !detailError && <Empty description="载入中…"/>}
                {detail && (
                    <>
                        <Descriptions bordered size="small" column={2}
                                      items={[
                                          {key: 'src', label: '取数路', children: <Tag>{detail.sourceUsed}</Tag>},
                                          {key: 'auth', label: '归属', children: <Tag color={authorityTag(detail.authority).color}>{detail.authority ?? '-'}</Tag>},
                                          {key: 'db', label: '库', children: detail.dbIgnoredForClientPath ? <span>db{detail.db}（<b>已忽略</b>，路 B 不切库）</span> : `db${detail.db ?? '-'}`},
                                          {key: 'type', label: '类型', children: <Tag color={TYPE_COLOR[asText(detail.type)] || 'default'}>{asText(detail.type) ?? '-'}</Tag>},
                                          {key: 'exists', label: '存在', children: detail.exists == null ? '-' : (detail.exists ? <Tag color="success">是</Tag> : <Tag>否</Tag>)},
                                          {key: 'ttl', label: 'TTL', children: fmtTtl(detail.ttlSeconds)},
                                          {key: 'pttl', label: 'PTTL(ms)', children: detail.pttlMillis ?? '-'},
                                          {key: 'ver', label: 'keyVersion（路 A）', children: detail.keyVersion ?? '-'},
                                          detail.error && {key: 'err', label: '读取错误', span: 2, children: <code>{detail.error}</code>},
                                      ].filter(Boolean)}/>
                        <Card size="small" title="值（按类型取，非 UTF-8 会降级成 base64）" style={{marginTop: 12}}>
                            {!detail.value || !Object.keys(detail.value).length
                                ? <Empty description="该类型没有可读的值，或路 A/B 都取不到"/>
                                : (
                                    <Descriptions size="small" column={1} bordered
                                                  items={Object.entries(detail.value).map(([k, v]) => ({
                                                      key: k, label: k, children: renderCell(v),
                                                  }))}/>
                                )}
                        </Card>
                        <div style={{marginTop: 12, fontSize: 12, color: '#64748b'}}>
                            值渲染不改变任何后端状态：路 A 只调 MemoryStore 的读方法，
                            路 B 只发 TYPE/TTL/PTTL/GET/HGETALL/LRANGE/LLEN/SMEMBERS/SCARD/ZCARD/ZRANGE，
                            全部在 Java 侧白名单里，HTTP 无法指定命令名。
                        </div>
                    </>
                )}
            </Drawer>
        </div>
    )
}
