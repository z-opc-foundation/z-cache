import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Descriptions, Space, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {cacheApi, cacheErrorText, authorityTag} from '../services/api'
import {PageHeader} from '@/common/components/ui'

const CMD_COLUMNS = [
    {title: 'RESP 命令', dataIndex: 'command', key: 'command', width: 140, render: (v) => <code>{v}</code>},
    {title: '说明', dataIndex: 'note', key: 'note', ellipsis: true},
]

function YesNo({value, okText = '是', badText = '否', badColor = 'error', unknown = '未探测'}) {
    if (value == null) return <Tag>{unknown}</Tag>
    return value ? <Tag color="success">{okText}</Tag> : <Tag color={badColor}>{badText}</Tag>
}

function AuthorityPill({value}) {
    const m = authorityTag(value)
    return <Tag color={m.color}>{value || '-'}</Tag>
}

/**
 * 实例与三态 —— GET /api/cache/__instance 的可视化。
 *
 * 这页存在的唯一理由是：`ZCacheEmbeddedServerConfig.isServerStarted()` 会撒谎。
 * 它返回的 SERVER_STARTED 是在 startServer() **进 try 之前**就 CAS 成 true 的，语义是
 * "我们试过启动"，不是"我们在 listen"；而真正的 bind 结果 RedisServer.isRunning() 藏在
 * 一个 private static 字段后面（没有 getter，本卡禁止改那个文件 ⇒ 只能反射）。
 * 实测今天的 z-opc 就是这个状态：isServerStarted()==true、isRunning()==false，
 * stdout 里躺着 java.net.BindException: Address already in use。
 * 所以这里把三件事分开显示，缺一条都会被读成"缓存里没数据"：
 *   ① 内嵌到底 bind 上了没（embeddedRunning / boundPort）；
 *   ② 那个端口上现在有没有人在应答（acceptingNow），应答的是不是本 JVM（foreignListenerSuspected）；
 *   ③ 业务那条 RESP 连的是谁（clientTransportConnected + clientTarget* + authorityOfClient）。
 *
 * 判定式是机械的：embeddedRunning==false 且 configuredPort TCP 可连 ⇒ 应答者必然不是本 JVM。
 * 这一格为真时，其余三个页面的任何数字都**不属于本次孵化**。
 *
 * 还有一个 z-cache 特有的坑必须写在脸上：挪端口只能靠 -Dz.cache.server.port，
 * application.properties 里的 z.cache.server.port 对内嵌 server 完全无效
 * （static 块只读 System.getProperty，见交接文件 §二 E8）。
 */
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

    return (
        <div>
            <PageHeader title="实例与三态"
                        subtitle="只读 controller 自省接口 GET /api/cache/__instance —— 先证明应答者是谁，再谈数据"/>

            {error && (
                <Alert type="error" showIcon
                       message={`自省接口调用失败：${cacheErrorText(error)}`}
                       description="/api/cache/__instance 由 z-opc 自己的 controller 直接应答，不经过任何 RESP 连接；它都不通说明是 z-opc 侧的路由/鉴权问题（302 到登录页 = 没带会话 Cookie）。"/>
            )}

            {notBound && !foreign && (
                <Alert type="warning" showIcon style={{marginBottom: 12}}
                       message="内嵌 RedisServer 没有 bind 成功 —— 其余三页的空表是「没起」，不是「缓存里没有 key」"
                       description={`configuredPort=${data.configuredPort} 上没有任何监听者。blockedReason：${data.blockedReason || '-'}`}/>
            )}

            {foreign && (
                <Alert type="error" showIcon style={{marginBottom: 12}}
                       message={`${data.configuredPort} 端口上有人在应答，但它不是本 JVM —— 这一路读到的是外部进程的缓存，不能算孵化结果`}
                       description={
                           `embeddedRunning=false（本 JVM 的 RedisServer 没 bind 上）而 ${data.configuredPort} TCP 可连 ⇒ 应答者必然不是本进程。` +
                           `请用 lsof -nP -iTCP:${data.configuredPort} -sTCP:LISTEN 取属主 PID，与本页「应答的 JVM」= ${data.jvm} 比对。` +
                           `实测本仓库当前状态：6379 属主是 5 天前的另一个 z-opc boot jar（交接文件 §二 E3/E5），` +
                           `本 JVM 的 stdout 里有 java.net.BindException: Address already in use。` +
                           `解法只有两种（让出端口，或用 -Dz.cache.server.port 换端口），都需要定夺 —— 页面不会替你占端口。`
                       }/>
            )}

            <Card title="这一路到底连的是谁" loading={loading}
                  extra={<Space>
                      <Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>刷新</Button>
                  </Space>}>
                <Descriptions bordered size="small" column={2}
                              items={[
                                  {
                                      key: 'jvm', label: '应答的 JVM (pid@host)',
                                      children: <code>{data?.jvm ?? '-'}</code>
                                  },
                                  {
                                      key: 'surface', label: 'z-cache 对外面的种类',
                                      children: <span><Tag color="volcano">{data?.surface ?? '-'}</Tag>
                                          <div style={{fontSize: 12, color: '#64748b'}}>{data?.httpSurfaceOfZCache}</div></span>
                                  },
                                  {
                                      key: 'lifecycleBean', label: '内嵌配置已装载',
                                      children: data ? (data.lifecycleBean
                                          ? <Tag color="success">是（z.cache.enabled=true）</Tag>
                                          : <Tag color="error">否</Tag>) : '-'
                                  },
                                  {
                                      key: 'starterClaim', label: 'starter 自报 isServerStarted()',
                                      children: <span>
                                          <YesNo value={data?.isServerStartedClaimedByStarter}
                                                 okText="true" badText="false"/>
                                          <div style={{fontSize: 12, color: '#b45309'}}>
                                              ⚠ 这格的语义是"进过 startServer()"，<b>不是</b>"bind 成功了" —— 单独看它一定误判
                                          </div>
                                      </span>
                                  },
                                  {
                                      key: 'reflected', label: '反射取到内嵌 RedisServer',
                                      children: <span>
                                          <YesNo value={data?.serverFieldReflected} okText="是（路 A 可用）"
                                                 badText="否（只剩路 B）"/>
                                          {data?.serverReflectError
                                              ? <div style={{fontSize: 12, color: '#64748b'}}><code>{data.serverReflectError}</code></div>
                                              : null}
                                      </span>
                                  },
                                  {
                                      key: 'running', label: '内嵌 server 真在跑 (isRunning)',
                                      children: <YesNo value={data?.embeddedRunning} okText="是" badText="否"/>,
                                  },
                                  {
                                      key: 'boundPort', label: '内嵌实际 bind 端口',
                                      children: data ? (data.boundPort > 0
                                          ? <Tag color="success">{data.boundPort}</Tag>
                                          : <Tag color="error">0（没 bind 上）</Tag>) : '-'
                                  },
                                  {
                                      key: 'store', label: '进程内 store 可读（路 A）',
                                      children: <YesNo value={data?.storeAvailable} okText="可用" badText="不可用"
                                                      badColor="warning"/>,
                                  },
                                  {
                                      key: 'configured', label: '配置端口（Spring 环境视角）',
                                      children: data?.configuredPort ?? '-'
                                  },
                                  {
                                      key: 'accepting', label: '该端口此刻 TCP 可连',
                                      children: <YesNo value={data?.acceptingNow} okText="有人应答" badText="无人应答"
                                                      badColor="warning"/>,
                                  },
                                  {
                                      key: 'owned', label: '该端口的属主是本 JVM',
                                      children: <YesNo value={data?.embeddedPortOwnedByThisJvm} badColor="error"/>,
                                  },
                                  {
                                      key: 'foreign', label: '外来监听者嫌疑',
                                      children: data ? (data.foreignListenerSuspected
                                          ? <Tag color="error">有（应答者不是本 JVM）</Tag>
                                          : <Tag color="success">无</Tag>) : '-'
                                  },
                                  {
                                      key: 'clientBean', label: 'zCacheClient bean 存在（路 B）',
                                      children: <YesNo value={data?.clientBeanPresent} badColor="warning"/>,
                                  },
                                  {
                                      key: 'clientConn', label: 'RESP 连接已建立',
                                      children: <YesNo value={data?.clientTransportConnected} okText="已连"
                                                      badText="未连" badColor="warning"/>,
                                  },
                                  {
                                      key: 'clientTarget', label: 'client 被配到',
                                      children: data
                                          ? <code>{`${data.clientTargetHost ?? '-'}:${data.clientTargetPort ?? '-'} /db${data.clientTargetDatabase ?? '-'}`}</code>
                                          : '-',
                                  },
                                  {
                                      key: 'clientAuth', label: 'client 侧启用了密码',
                                      children: <YesNo value={data?.clientTargetHasPassword} okText="有" badText="无（6379 裸奔）"
                                                      badColor="warning"/>,
                                  },
                                  {
                                      key: 'authority', label: '路 B 应答者的归属',
                                      children: <span>
                                          <AuthorityPill value={data?.authorityOfClient}/>
                                          <div style={{fontSize: 12, color: '#64748b'}}>
                                              THIS_JVM_EMBEDDED 才算孵化成功；EXTERNAL_PROCESS = 读到的是别人的实例
                                          </div>
                                      </span>
                                  },
                                  {
                                      key: 'portChain', label: '端口配置的两条解析链',
                                      children: <span>
                                          <code>-Dz.cache.server.port = {String(data?.serverPortFromSystemProperty ?? '-')}</code>
                                          <div style={{fontSize: 12, color: '#64748b'}}>
                                              Spring 环境解析值 = {String(data?.serverPortFromSpringEnv ?? '-')}；
                                              {data?.serverPortResolutionDiverges
                                                  ? <b style={{color: '#b45309'}}> 两者不一致 —— static 块只认 -D，properties 是装饰</b>
                                                  : ' 两者一致'}
                                          </div>
                                      </span>
                                  },
                                  {
                                      key: 'readApi', label: '取数实现',
                                      children: <div style={{fontSize: 12}}>{data?.readApiImplementedBy}</div>
                                  },
                                  {
                                      key: 'prefix', label: '鉴权前缀',
                                      children: <span>
                                          <code>/api/cache/** → sso.intercept-paths=/**/api/**</code>
                                          <div style={{fontSize: 12, color: '#64748b'}}>
                                              实测无 Cookie 时 /api/** 一律 302 到登录页；不开裸路径，也不引导直连 6379（内嵌默认 bind 0.0.0.0 且无密码）
                                          </div>
                                      </span>
                                  },
                                  {
                                      key: 'write', label: '写端点',
                                      children: <Tag color="success">0 个（writeEndpoints 恒为 []，后端只有 @GetMapping）</Tag>,
                                  },
                                  {
                                      key: 'select', label: '是否发过 SELECT',
                                      children: <Tag color="success">从不（会改掉业务共用连接的活动库）</Tag>,
                                  },
                                  {
                                      key: 'invariant', label: '保真不变式（代替 HTTP 转发探针）',
                                      children: <div style={{fontSize: 12}}>{data?.invariant}</div>,
                                  },
                                  {
                                      key: 'blocked', label: '当前阻塞原因',
                                      span: 2,
                                      children: data?.blockedReason
                                          ? <span style={{color: '#b45309'}}>{data.blockedReason}</span>
                                          : <Tag color="success">无</Tag>,
                                  },
                              ]}/>
                <div style={{marginTop: 12, fontSize: 12, color: '#64748b'}}>
                    只读端点清单：{(data?.readEndpoints ?? []).map((p) => <code key={p}
                                                                                style={{marginRight: 6}}>{p}</code>)}
                </div>
            </Card>

            <Card title="本 controller 实际下发 / 白名单允许 / 永不下发的 RESP 命令"
                  style={{marginTop: 16}}
                  extra={<Tag color="blue">命令名是代码常量，HTTP 侧无法指定</Tag>}>
                {!catalog ? (
                    <Alert type="info" showIcon
                           message={data ? '命令目录读不到（不影响上面三态结论）' : '等自省接口回来再看'}/>
                ) : (
                    <>
                        <div style={{fontSize: 12, color: '#64748b', marginBottom: 8}}>{catalog.provenance}</div>
                        <Table size="small" rowKey="command" pagination={false}
                               columns={CMD_COLUMNS}
                               dataSource={catalog.commandsActuallyIssued ?? []}
                               title={() => <span><Tag color="success">只读 · 本 controller 真正会发的（{catalog.commandsActuallyIssued?.length ?? 0} 条）</Tag></span>}/>
                        {catalog.notCountedAsIssued ? (
                            <Alert style={{marginTop: 8}} type="warning" showIcon
                                   message="为什么 PING 不算「已下发」"
                                   description={catalog.notCountedAsIssued}/>
                        ) : null}
                        <Table style={{marginTop: 12}} size="small" rowKey="command" pagination={false}
                               columns={CMD_COLUMNS}
                               dataSource={catalog.readOnlyCommandsAllowedByWhitelist ?? []}
                               title={() => <span><Tag color="processing">只读白名单 = 上限集合（safeSend 入口校验用）</Tag></span>}/>
                        {catalog.whitelistSemantics ? (
                            <div style={{marginTop: 8, fontSize: 12, color: '#64748b'}}>{catalog.whitelistSemantics}</div>
                        ) : null}
                        <Table style={{marginTop: 12}} size="small" rowKey="command" pagination={false}
                               columns={CMD_COLUMNS}
                               dataSource={catalog.neverIssuedDespiteBeingSupportedByServer ?? []}
                               title={() => <span><Tag color="error">server 支持但本 controller 永不下发</Tag></span>}/>
                    </>
                )}
            </Card>

            <Card title="「上游自报存活时长」这一招在 z-cache 上不可用" style={{marginTop: 16}}>
                <Alert type="warning" showIcon
                       message="别拿 storeUptimeSeconds 或 INFO 的字段去反推应答者是谁"
                       description={
                           <div>
                               <div>· MemoryStore.getStartTime() 在内嵌 RedisServer 被 new 出来时就有了，bind 失败也照样涨
                                   ⇒ 它衡量的是「对象存在多久」，不是「服务在线多久」。</div>
                               <div>· 上游 INFO 里的 tcp_port:6379 与 z-cache_version:1.0.2 是
                                   CommandHandler.handleInfo 里的<b>硬编码字面量</b>（实测 classpath 上的 jar 是 1.3.1），
                                   拿它判断端口/版本必然得出错误结论。</div>
                               <div>· 所以 z-cache 这条路上，归属判定只能用本页的 embeddedRunning + acceptingNow +
                                   foreignListenerSuspected + authorityOfClient 四格，
                                   外加 shell 侧的 lsof / ps / 本 JVM stdout 三条互不依赖的证据。</div>
                           </div>
                       }/>
            </Card>
        </div>
    )
}
