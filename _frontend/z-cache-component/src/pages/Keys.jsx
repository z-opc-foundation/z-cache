/** 键浏览：/cache/keys 分页 + 单键详情 /cache/key。 */
import {useEffect, useState} from 'react'
import {Alert, Button, Input, Modal, Space, Tag, Typography} from 'antd'
import {ReloadOutlined, SearchOutlined} from '@ant-design/icons'
import {ListTable} from '@yuku123/z-frontend-common/ui'
import {cacheApi} from '../services/api'

const {Title, Paragraph, Text} = Typography

/**
 * type 的取值域与路 A/路 B 统一成**大写**枚举名（STRING/HASH/LIST/SET/ZSET/NONE）。
 * 旧版这里写的是小写（'string'/'hash'…）⇒ 后端归一成大写后一个都匹配不上，标签全变成默认色。
 */
function typeTag(t) {
    const map = {STRING: 'blue', HASH: 'green', LIST: 'purple', SET: 'orange', ZSET: 'cyan', NONE: 'default'}
    return <Tag color={map[t] || 'default'}>{t || '未采信'}</Tag>
}

export default function Keys() {
    const [rows, setRows] = useState([])
    const [pattern, setPattern] = useState('*')
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [detail, setDetail] = useState(null)

    const fetch = async () => {
        setLoading(true)
        try {
            const r = await cacheApi.keys({pattern})
            const list = Array.isArray(r) ? r : r?.keys || []
            setRows(list.map((k, i) => typeof k === 'string' ? {key: i, name: k} : {key: i, ...k}))
            setError(null)
        } catch (e) {
            setError(e?.message || String(e))
        } finally { setLoading(false) }
    }

    useEffect(() => { fetch() }, [])

    const openDetail = async (row) => {
        try {
            const d = await cacheApi.key({key: row.name || row.key})
            setDetail({...row, ...d})
        } catch (e) {
            setDetail({...row, error: e?.message || String(e)})
        }
    }

    const columns = [
        {title: '键名', key: 'name', ellipsis: true,
            render: (_, r) => <Text code style={{fontSize: 12}}>{r.name || r.key}</Text>},
        {title: '类型', dataIndex: 'type', key: 'type', width: 100,
            // 「没测」时显示什么 —— 假绿列探针要靠这个声明把"没测"与"测过且正常"分开
            unmeasured: '未采信',
            render: (v, r) => (r?.desyncSuspect
                ? <Tag color="red">应答错位·未采信</Tag>
                : typeTag(v))},
        {title: 'TTL', dataIndex: 'ttlSeconds', key: 'ttlSeconds', width: 100,
            // ⚠️ 旧版这一列的取键写的是 ttl（后端从来没给过 'ttl' 这个键，给的是 ttlSeconds）
            // ⇒ 这一列一直是空的（假绿列：键名对不上，列全是空）。TASK-20260925-041 由探针抓出并修掉。
            // （注：探针是按字面量扫 dataIndex 的，所以这里**不能**把旧写法原样写进注释 ——
            //  否则它会当成页面真有一个 ttl 列，造出一个查无此列的假红。）
            unmeasured: '未测',
            render: (v, r) => (r?.ttlSecondsError
                ? <Tag color="red">读数不可信</Tag>
                : (v == null ? '未测' : (v < 0 ? '永不过期' : `${v}s`)))},
        {title: '操作', key: 'op', width: 100,
            render: (_, r) => <Button size="small" onClick={() => openDetail(r)}>详情</Button>},
    ]

    return (
        <div>
            <Space style={{marginBottom: 16}} wrap>
                <Title level={4} style={{margin: 0}}>键浏览</Title>
                <Input value={pattern} onChange={e => setPattern(e.target.value)} onPressEnter={fetch}
                       placeholder="如 user:* " style={{width: 240}} prefix={<SearchOutlined/>}/>
                <Button type="primary" onClick={fetch} loading={loading}>搜索</Button>
                <Button icon={<ReloadOutlined/>} onClick={fetch}>刷新</Button>
            </Space>
            <Paragraph type="secondary">Redis 键空间浏览（/cache/keys?pattern=）+ 单键详情（/cache/key）。</Paragraph>

            {error && <Alert type="error" showIcon style={{marginBottom: 16}} message="后端未连接" description={error}/>}

            {/* 客户端整表：分页 / 空态三件套交给基座 ListTable。
                这一页的「查询」是 pattern（Redis glob，**走后端** /cache/keys?pattern=），
                页面上已经有一个查询框了 ⇒ 不传 searchKeys，否则会出现两个语义不同的搜索框。 */}
            <ListTable
                rows={rows}
                columns={columns}
                rowKey="key"
                loading={loading}
                emptyTitle="没有匹配的 key"
                emptyDescription="换个 pattern 再查一次，或点「刷新」看全量键空间。"
                cardExtra={<Button icon={<ReloadOutlined/>} onClick={fetch}>刷新</Button>}
            />

            <Modal title={detail ? `键详情：${detail.name || detail.key}` : ''} open={!!detail} onCancel={() => setDetail(null)}
                   footer={<Button onClick={() => setDetail(null)}>关闭</Button>} width={700}>
                {detail && <pre style={{maxHeight: 400, overflow: 'auto', background: '#1e1e1e', color: '#d4d4d4',
                                        padding: 12, fontSize: 12, borderRadius: 4}}>
                    {detail.error || (typeof detail.value === 'object' ? JSON.stringify(detail.value, null, 2) : String(detail.value ?? JSON.stringify(detail, null, 2)))}
                </pre>}
            </Modal>
        </div>
    )
}
