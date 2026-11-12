import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Descriptions, Input, Space, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {cacheApi, cacheErrorText} from '../services/api'
import {EmptyState} from '@yuku123/z-frontend-common/ui'

const CMD_COLUMNS = [
    {title: 'RESP 命令', dataIndex: 'command', key: 'command', width: 140, render: (v) => <code>{v}</code>},
    {title: '说明', dataIndex: 'note', key: 'note', ellipsis: true},
]

function YesNo({value, okText = '是', badText = '否', badColor = 'error', unknown = '未探测'}) {
    if (value == null) return <Tag>{unknown}</Tag>
    return value ? <Tag color="success">{okText}</Tag> : <Tag color={badColor}>{badText}</Tag>
}

export default function InstanceStatus() {
    const [data, setData] = useState(null)
    const [catalog, setCatalog] = useState(null)
    const [error, setError] = useState(null)
    const [loading, setLoading] = useState(false)

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const inst = await cacheApi.instance()
            setData(inst)
            setError(null)
            try {
                setCatalog(await cacheApi.commandCatalog())
            } catch (e) {
                setCatalog(null)
            }
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

    const foreign = !error && data && data.foreignListenerSuspected
    const notBound = !error && data && !data.embeddedRunning

    const [keyword, setKeyword] = useState('')
    const matchCommand = (list) => {
        const rows = Array.isArray(list) ? list : []
        const kw = keyword.trim().toLowerCase()
        if (!kw) return rows
        return rows.filter((r) => `${r?.command || ''} ${r?.note || ''}`.toLowerCase().includes(kw))
    }
    const issuedRows = matchCommand(catalog?.commandsActuallyIssued)
    const whitelistRows = matchCommand(catalog?.readOnlyCommandsAllowedByWhitelist)
    const neverIssuedRows = matchCommand(catalog?.neverIssuedDespiteBeingSupportedByServer)

    return (
        <div>
            {error && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message={`自省接口调用失败：${cacheErrorText(error)}`}/>
            )}

            {notBound && !foreign && (
                <Alert type="warning" showIcon style={{marginBottom: 12}}
                       message={`内嵌 RedisServer 未 bind（configuredPort=${data.configuredPort}）。blockedReason：${data.blockedReason || '-'}`}/>
            )}

            {foreign && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message={`${data.configuredPort} 端口上应答的不是本 JVM —— 该端口读到的是外部进程的缓存`}/>
            )}

            <Card title="实例状态" loading={loading}
                  extra={<Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>刷新</Button>}>
                <Descriptions bordered size="small" column={2}
                              items={[
                                  {
                                      key: 'jvm', label: '本 JVM',
                                      children: <code>{data?.jvm ?? '-'}</code>
                                  },
                                  {
                                      key: 'loaded', label: '内嵌配置已装载',
                                      children: data ? (data.lifecycleBean
                                          ? <Tag color="success">是</Tag>
                                          : <Tag color="error">否</Tag>) : '-'
                                  },
                                  {
                                      key: 'running', label: '内嵌 server 状态',
                                      children: <span>
                                          <YesNo value={data?.embeddedRunning} okText="运行中" badText="未运行"/>
                                          {data?.boundPort > 0 ? <Tag color="success" style={{marginLeft: 6}}>端口 {data.boundPort}</Tag> : null}
                                      </span>,
                                  },
                                  {
                                      key: 'configured', label: '配置端口',
                                      children: data?.configuredPort ?? '-'
                                  },
                                  {
                                      key: 'accepting', label: '端口可连',
                                      children: <YesNo value={data?.acceptingNow} okText="有人应答" badText="无人应答"
                                                       badColor="warning"/>,
                                  },
                                  {
                                      key: 'foreign', label: '外来监听者',
                                      children: data ? (data.foreignListenerSuspected
                                          ? <Tag color="error">有（应答者不是本 JVM）</Tag>
                                          : <Tag color="success">无</Tag>) : '-'
                                  },
                                  {
                                      key: 'clientConn', label: 'client 连接',
                                      children: <span>
                                          <YesNo value={data?.clientTransportConnected} okText="已连"
                                                 badText="未连" badColor="warning"/>
                                          {data?.clientTargetHost
                                              ? <code style={{marginLeft: 6}}>{`${data.clientTargetHost}:${data.clientTargetPort}/db${data.clientTargetDatabase ?? '-'}`}</code>
                                              : null}
                                      </span>,
                                  },
                                  {
                                      key: 'authority', label: 'client 应答者归属',
                                      children: <Tag color={data?.authorityOfClient === 'THIS_JVM_EMBEDDED' ? 'success' : 'warning'}>
                                          {data?.authorityOfClient ?? '-'}
                                      </Tag>,
                                  },
                                  {
                                      key: 'blocked', label: '阻塞原因',
                                      span: 2,
                                      children: data?.blockedReason
                                          ? <span style={{color: '#b45309'}}>{data.blockedReason}</span>
                                          : <Tag color="success">无</Tag>,
                                  },
                              ]}/>
            </Card>

            <Card title="RESP 命令目录"
                  style={{marginTop: 16}}
                  extra={<Input.Search allowClear placeholder="按命令 / 说明过滤" style={{width: 220}}
                                      onChange={(e) => setKeyword(e.target.value)}/>}>
                {!catalog ? (
                    <Alert type="info" showIcon message="命令目录读不到"/>
                ) : (
                    <>
                        <Table size="small" rowKey="command" pagination={false}
                               columns={CMD_COLUMNS}
                               locale={{emptyText: <EmptyState title="没有已下发命令" size="sm"/>}}
                               dataSource={issuedRows}
                               title={() => <span><Tag color="success">已下发（{catalog.commandsActuallyIssued?.length ?? 0} 条）</Tag></span>}/>
                        <Table style={{marginTop: 12}} size="small" rowKey="command" pagination={false}
                               columns={CMD_COLUMNS}
                               locale={{emptyText: <EmptyState title="白名单为空" size="sm"/>}}
                               dataSource={whitelistRows}
                               title={() => <span><Tag color="processing">只读白名单</Tag></span>}/>
                        <Table style={{marginTop: 12}} size="small" rowKey="command" pagination={false}
                               columns={CMD_COLUMNS}
                               locale={{emptyText: <EmptyState title="没有此类命令" size="sm"/>}}
                               dataSource={neverIssuedRows}
                               title={() => <span><Tag color="error">支持但不下发</Tag></span>}/>
                    </>
                )}
            </Card>
        </div>
    )
}
