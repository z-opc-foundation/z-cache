/** 键浏览：/cache/keys 分页 + 单键详情 /cache/key。 */
import {useEffect, useState} from 'react'
import {Alert, Button, Card, Input, Modal, Space, Table, Tag, Typography} from 'antd'
import {ReloadOutlined, SearchOutlined} from '@ant-design/icons'
import {cacheApi} from '../services/api'

const {Title, Paragraph, Text} = Typography

function typeTag(t) {
    const map = {string: 'blue', hash: 'green', list: 'purple', set: 'orange', zset: 'cyan'}
    return <Tag color={map[t] || 'default'}>{t || '—'}</Tag>
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
        {title: '类型', dataIndex: 'type', key: 'type', width: 100, render: typeTag},
        {title: 'TTL', dataIndex: 'ttl', key: 'ttl', width: 100,
            render: (v) => v == null ? '—' : v < 0 ? '永不过期' : `${v}s`},
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

            <Card>
                <Table rowKey="key" dataSource={rows} columns={columns} loading={loading} size="small"
                       pagination={{pageSize: 20}}/>
            </Card>

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
