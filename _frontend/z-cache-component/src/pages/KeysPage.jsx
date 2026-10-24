import {useCallback, useEffect, useMemo, useState} from 'react'
import {
    Alert, Badge, Button, Card, Descriptions, Drawer, Empty, Input, InputNumber,
    Radio, Select, Space, Tag, Tooltip,
} from 'antd'
import {ReloadOutlined, SearchOutlined} from '@ant-design/icons'
import {cacheApi, cacheErrorText, authorityTag, fmtBytes, fmtTtl} from '../services/api'
import {PageHeader, ListTable} from '@yuku123/z-frontend-common/ui'

/**
 * type 的取值域（两路统一成**大写**枚举名）：
 *   路 A = MemoryStore.DataType.name()（STRING/HASH/LIST/SET/ZSET，缺 key 为 NONE）
 *   路 B = 后端把 RESP 应答归一后的同一个集合（D2）
 * 旧版这里拿大写去比小写（server 回 "string"）⇒ 类型筛选永远 0 命中。
 */
const TYPE_COLOR = {
    STRING: 'blue', HASH: 'purple', LIST: 'cyan', SET: 'orange', ZSET: 'magenta', NONE: 'default',
}

const TYPE_OPTIONS = [
    {value: 'STRING', label: 'STRING'}, {value: 'HASH', label: 'HASH'},
    {value: 'LIST', label: 'LIST'}, {value: 'SET', label: 'SET'},
    {value: 'ZSET', label: 'ZSET'}, {value: 'NONE', label: 'NONE'},
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

    /**
     * 列定义。
     *
     * ⚠️ **`unmeasured` 字段是给假绿列探针（fake_green_column_probe.py）读的契约**，不是装饰：
     * 探针现在把"这一列在实测响应里恒为 null"（= 后端**没测**这一列）与"这一列测过且有值"分成两类。
     * 只有"页面显式声明了没测时显示什么"的列，才算"已声明降级"；没声明 ⇒ 探针判**嫌疑**，
     * 因为那种列很可能正把"没测"渲染成一句正面断言（本卡 D4 的病灶：
     * `v == null || v < 0 ? '非 string' : …` —— "这一路压根没测"被显示成"这个 key 不是 string"）。
     * ⇒ 声明里的文字必须与 render 里真正渲染的文字一致。
     */
    const columns = useMemo(() => [
        {
            title: 'key', dataIndex: 'key', key: 'key', ellipsis: true,
            render: (v) => <a onClick={() => openDetail(v)}><code>{v}</code></a>
        },
        {
            title: '类型', dataIndex: 'type', key: 'type', width: 150,
            unmeasured: '未采信',
            // 路 B 的 type 现在是后端归一后的**字符串**（大写枚举名）或 null（D2）。
            // null 有两种来路，必须分开渲染：
            //   desyncSuspect=true  ⇒ 这一行的应答与所发命令对不上（串台），**不采信**（D3）
            //   其它 null            ⇒ 后端没给出类型
            // 旧版这里用 asText() 兜底，把 {value:"string"} 这种 bean 兜成一段 JSON 原文。
            render: (v, row) => {
                if (row?.desyncSuspect) {
                    return (
                        <Tooltip title={row.desyncReason || '这一行的应答与所发命令对不上，不采信'}>
                            <Tag color="red">应答错位·未采信</Tag>
                        </Tooltip>
                    )
                }
                if (v == null) return <span style={{color: '#94a3b8'}}>未采信</span>
                return <Tag color={TYPE_COLOR[v] || 'default'}>{v}</Tag>
            },
            filters: TYPE_OPTIONS.map((o) => ({text: o.label, value: o.value})),
            onFilter: (val, row) => row.type === val,
        },
        {
            title: 'TTL', dataIndex: 'ttlSeconds', key: 'ttl', width: 170,
            unmeasured: '未测',
            render: (v, row) => {
                if (row?.ttlSecondsError) {
                    return (
                        <Tooltip title={row.ttlSecondsError}>
                            <Tag color="red">读数不可信</Tag>
                        </Tooltip>
                    )
                }
                if (v == null) {
                    return <span style={{color: '#94a3b8'}} title={row?.ttlSecondsNote}>未测</span>
                }
                return fmtTtl(v)
            },
        },
        {
            title: '字节', dataIndex: 'byteLength', key: 'byteLength', width: 130,
            unmeasured: '未测',
            // ⚠️ 三个取值必须分开渲染（D4）：
            //   null  ⇒ 后端**没测**这一列（路 B 不发 STRLEN）⇒ 显示"未测"
            //   -1    ⇒ **测过**的结论：这个 key 不是 string
            //   其它   ⇒ 真的字节数
            // 旧版把 null 和 -1 合成一句"非 string" ⇒ 把"没测"渲染成了正面断言（最像假绿的一格）。
            render: (v, row) => {
                if (v == null) {
                    return <span style={{color: '#94a3b8'}} title={row?.byteLengthNote}>未测</span>
                }
                if (v < 0) return <span style={{color: '#94a3b8'}}>非 string</span>
                return fmtBytes(v)
            },
        },
        {
            title: '数据源', dataIndex: 'authority', key: 'authority', width: 190,
            unmeasured: '未采信',
            render: (v, row) => (
                <Tag color={authorityTag(row?.authority ?? v).color}>{row?.authority ?? v ?? '-'}</Tag>
            ),
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

            {meta?.desyncRows > 0 && (
                <Alert type="warning" showIcon style={{marginBottom: 8}}
                       message={`路 B 本次有 ${meta.desyncRows}/${meta.totalRows} 行的应答对不上，这些行的类型/TTL 不参与展示`}
                       description={meta.desyncNote}/>
            )}
            {/* 客户端整表：分页 / 空态三件套交给基座 ListTable。
                这一页的查询条件（pattern / db / limit / source）**走后端**，上面已经有查询面板了
                ⇒ 不传 searchKeys，否则页面上会出现两个语义不同的搜索框。 */}
            <ListTable
                cardTitle={<span>key 列表 {meta && <Tag color="blue">sourceUsed = {meta.sourceUsed}</Tag>}
                    {meta?.authority && <Tag color={authorityTag(meta.authority).color}>{meta.authority}</Tag>}</span>}
                cardExtra={meta && <Space size={8}>
                    <Badge status={meta.truncated ? 'warning' : 'default'}
                           text={meta.truncated
                               ? `后端 KEYS 命中 ${meta.matchedTotal} 条，只取了前 ${meta.limit} 条（KEYS 是 O(N)，硬上限 1000）`
                               : `命中 ${meta.matchedTotal ?? rows.length} 条`}
                    />
                </Space>}
                rows={shown}
                rowKey={(r) => `${r.key}`}
                loading={loading}
                columns={columns}
                emptyTitle={error ? '读取失败' : (!meta ? '还没取到数据' : '没有匹配的 key')}
                emptyDescription={error
                    ? '见上方告警。'
                    : (!meta
                        ? '先点「查询」读一次。'
                        : (meta.sourceUsed === 'embedded'
                            ? '路 A 的进程内 store 里这个 pattern 没有匹配项（本 JVM 自己那份，确定没跨进程）'
                            : '路 B 无应答或该库确实为空 —— 先看「实例与三态」确认应答者是不是本 JVM'))}
                tableProps={{size: 'small'}}
            />

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
                                          {key: 'type', label: '类型', children: detail.desyncSuspect
                                              ? <Tooltip title={detail.desyncReason || '应答与所发命令对不上'}>
                                                  <Tag color="red">应答错位·未采信</Tag>
                                              </Tooltip>
                                              : <Tag color={TYPE_COLOR[detail.type] || 'default'}>{detail.type ?? '未采信'}</Tag>},
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
