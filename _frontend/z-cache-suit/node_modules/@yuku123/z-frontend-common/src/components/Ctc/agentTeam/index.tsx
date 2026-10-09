import {useEffect, useRef, useState} from 'react'
import {PageContainer, ProCard} from '@ant-design/pro-components'
import {Alert, Button, Empty, Input, List, message, Space, Tag, Tooltip} from 'antd'
import {
    CheckCircleOutlined,
    CloseCircleOutlined,
    PoweroffOutlined,
    SendOutlined,
    TeamOutlined,
    UserAddOutlined
} from '@ant-design/icons'
import {agentTeamApi, type AgentTeam, type AgentTeamMember} from '@/services/agentTeam'

/**
 * z-agent-team 即时通讯 (IM) 集成页
 *
 * 后端 (z-agent-team-web)：
 *   - GET    /api/agent/team/list
 *   - POST   /api/agent/team/create
 *   - GET    /api/agent/team/{teamCode}/members
 *   - POST   /api/agent/team/{teamCode}/members/add
 *   - POST   /api/agent/team/{teamCode}/members/remove?appCode=
 *   - WS     /api/agent/team/ws (STOMP)
 *
 * 注意：完整的 IM 客户端 (含 STOMP 订阅) 需要 sockjs-client / @stomp/stompjs，
 *      完整实现见 z-agent-team-frontend (z-agent/z-agent-team/z-agent-team-frontend)。
 *      这里展示：群组管理 + 消息发送 (REST) + WS URL 配置。
 */
const ROLE_COLOR: Record<string, string> = {
    LEADER: 'red',
    WORKER: 'blue',
    OBSERVER: 'default',
}

export default function AgentTeamIM() {
    const [teams, setTeams] = useState<AgentTeam[]>([])
    const [selectedTeam, setSelectedTeam] = useState<AgentTeam | null>(null)
    const [members, setMembers] = useState<AgentTeamMember[]>([])
    const [loadingTeams, setLoadingTeams] = useState(false)
    const [loadingMembers, setLoadingMembers] = useState(false)
    const [wsState, setWsState] = useState<'disconnected' | 'connecting' | 'connected' | 'error'>('disconnected')
    const [wsLog, setWsLog] = useState<string[]>([])
    const [messageInput, setMessageInput] = useState('')
    const [messages, setMessages] = useState<{from: string; text: string; time: string}[]>([])
    const wsRef = useRef<WebSocket | null>(null)

    // 加载团队列表
    const loadTeams = async () => {
        setLoadingTeams(true)
        try {
            const list = await agentTeamApi.listTeams()
            setTeams(list || [])
        } catch (e: any) {
            message.warning('加载团队失败：' + (e?.message || '未知错误'))
            setTeams([])
        } finally {
            setLoadingTeams(false)
        }
    }

    // 加载成员
    const loadMembers = async (teamCode: string) => {
        setLoadingMembers(true)
        try {
            const list = await agentTeamApi.listMembers(teamCode)
            setMembers(list || [])
        } catch (e: any) {
            message.warning('加载成员失败：' + (e?.message || '未知错误'))
            setMembers([])
        } finally {
            setLoadingMembers(false)
        }
    }

    // WebSocket 连接 (原生 WS，无 STOMP 简化版)
    const connectWs = () => {
        if (wsRef.current) {
            message.warning('已连接')
            return
        }
        const url = agentTeamApi.buildWsUrl()
        setWsState('connecting')
        setWsLog(prev => [...prev, `[${new Date().toLocaleTimeString()}] 连接到 ${url}...`])
        try {
            const ws = new WebSocket(url)
            ws.onopen = () => {
                setWsState('connected')
                setWsLog(prev => [...prev, `[${new Date().toLocaleTimeString()}] ✅ 连接已建立`])
            }
            ws.onmessage = (ev) => {
                const text = typeof ev.data === 'string' ? ev.data : '[binary]'
                setWsLog(prev => [...prev, `[${new Date().toLocaleTimeString()}] ← ${text}`])
                // 简单消息格式: "from|text"
                const m = text.match(/^([^|]+)\|(.+)$/)
                if (m) {
                    setMessages(prev => [...prev, {from: m[1], text: m[2], time: new Date().toLocaleTimeString()}])
                }
            }
            ws.onerror = () => {
                setWsState('error')
                setWsLog(prev => [...prev, `[${new Date().toLocaleTimeString()}] ❌ 连接错误`])
            }
            ws.onclose = () => {
                setWsState('disconnected')
                setWsLog(prev => [...prev, `[${new Date().toLocaleTimeString()}] 🔌 连接已关闭`])
                wsRef.current = null
            }
            wsRef.current = ws
        } catch (e: any) {
            setWsState('error')
            message.error('WS 连接失败：' + (e?.message || '未知错误'))
        }
    }

    const disconnectWs = () => {
        if (wsRef.current) {
            wsRef.current.close()
            wsRef.current = null
        }
    }

    const sendMessage = () => {
        if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
            message.error('WebSocket 未连接')
            return
        }
        if (!messageInput.trim()) return
        const text = `${localStorage.getItem('userInfo') ? JSON.parse(localStorage.getItem('userInfo')!).userName : 'me'}|${messageInput}`
        wsRef.current.send(text)
        setWsLog(prev => [...prev, `[${new Date().toLocaleTimeString()}] → ${messageInput}`])
        setMessages(prev => [...prev, {from: 'me', text: messageInput, time: new Date().toLocaleTimeString()}])
        setMessageInput('')
    }

    useEffect(() => {
        loadTeams()
        return () => {
            disconnectWs()
        }
    }, [])

    useEffect(() => {
        if (selectedTeam) {
            loadMembers(selectedTeam.teamCode)
        }
    }, [selectedTeam])

    const stateColor = {
        disconnected: 'default',
        connecting: 'processing',
        connected: 'success',
        error: 'error',
    } as const
    const stateText = {
        disconnected: '未连接',
        connecting: '连接中',
        connected: '已连接',
        error: '错误',
    } as const

    return (
        <PageContainer
            header={{
                title: 'Agent 群组 IM (z-agent-team)',
                subTitle: '团队协作 + 实时通讯',
                breadcrumb: {},
            }}
        >
            <div style={{display: 'grid', gridTemplateColumns: '300px 1fr', gap: 16}}>
                {/* 团队列表面板 */}
                <ProCard title={<Space><TeamOutlined/> 团队列表</Space>} loading={loadingTeams} headerBordered>
                    {teams.length === 0 ? (
                        <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="暂无团队"
                        >
                            <Button type="primary" size="small" onClick={loadTeams}>
                                刷新
                            </Button>
                        </Empty>
                    ) : (
                        <List
                            dataSource={teams}
                            renderItem={(t) => (
                                <List.Item
                                    onClick={() => setSelectedTeam(t)}
                                    style={{
                                        cursor: 'pointer',
                                        padding: '8px 12px',
                                        background: selectedTeam?.teamCode === t.teamCode ? '#e6f7ff' : undefined,
                                        borderRadius: 4,
                                    }}
                                >
                                    <List.Item.Meta
                                        title={t.teamName}
                                        description={
                                            <Space size={4}>
                                                <Tag>{t.teamCode}</Tag>
                                                {t.status === 'ENABLE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>}
                                            </Space>
                                        }
                                    />
                                </List.Item>
                            )}
                        />
                    )}
                </ProCard>

                {/* 详情面板 */}
                <ProCard
                    title={
                        selectedTeam
                            ? <Space><TeamOutlined/> {selectedTeam.teamName} ({selectedTeam.teamCode})</Space>
                            : '请选择团队'
                    }
                    headerBordered
                    extra={
                        <Space>
                            {wsState === 'connected' ? (
                                <Button icon={<PoweroffOutlined/>} onClick={disconnectWs}>断开</Button>
                            ) : (
                                <Button type="primary" icon={<CheckCircleOutlined/>} onClick={connectWs}>连接</Button>
                            )}
                            <Tag color={stateColor[wsState]}>{stateText[wsState]}</Tag>
                        </Space>
                    }
                >
                    {selectedTeam ? (
                        <div style={{display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16}}>
                            {/* 成员列表 */}
                            <ProCard title="成员" loading={loadingMembers} size="small">
                                <List
                                    dataSource={members}
                                    renderItem={(m) => (
                                        <List.Item>
                                            <List.Item.Meta
                                                title={<Space>{m.appCode}<Tag color={ROLE_COLOR[m.role || 'WORKER']}>{m.role}</Tag></Space>}
                                                description={`优先级: ${m.priority ?? 0} · ${m.enabled === 1 ? '✅ 启用' : '❌ 停用'}`}
                                            />
                                        </List.Item>
                                    )}
                                />
                            </ProCard>

                            {/* 消息面板 */}
                            <ProCard title="实时消息" size="small" styles={{body: {padding: 0, display: 'flex', flexDirection: 'column', height: 400}}}>
                                <div style={{flex: 1, overflow: 'auto', padding: 12}}>
                                    {messages.length === 0 ? (
                                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无消息" />
                                    ) : (
                                        messages.map((m, i) => (
                                            <div key={i} style={{marginBottom: 8}}>
                                                <Tag color={m.from === 'me' ? 'blue' : 'green'}>{m.from}</Tag>
                                                <span style={{marginLeft: 8}}>{m.text}</span>
                                                <span style={{marginLeft: 8, color: '#999', fontSize: 11}}>{m.time}</span>
                                            </div>
                                        ))
                                    )}
                                </div>
                                <div style={{padding: 8, borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8}}>
                                    <Input
                                        value={messageInput}
                                        onChange={e => setMessageInput(e.target.value)}
                                        onPressEnter={sendMessage}
                                        placeholder={wsState === 'connected' ? '输入消息...' : '请先连接 WebSocket'}
                                        disabled={wsState !== 'connected'}
                                    />
                                    <Button type="primary" icon={<SendOutlined/>} onClick={sendMessage}
                                            disabled={wsState !== 'connected'}>
                                        发送
                                    </Button>
                                </div>
                            </ProCard>
                        </div>
                    ) : (
                        <Empty description="请从左侧选择一个团队"/>
                    )}

                    {/* WS 日志 */}
                    {wsLog.length > 0 && (
                        <ProCard title="WebSocket 日志" size="small" style={{marginTop: 16}}>
                            <pre style={{
                                background: '#1e1e1e', color: '#d4d4d4', padding: 8, borderRadius: 4,
                                fontFamily: 'Menlo, monospace', fontSize: 11, maxHeight: 180, overflow: 'auto',
                                margin: 0,
                            }}>
                                {wsLog.join('\n')}
                            </pre>
                        </ProCard>
                    )}
                </ProCard>
            </div>

            <Alert
                style={{marginTop: 16}}
                type="info"
                showIcon
                message="说明"
                description={
                    <span>
                        完整 STOMP 客户端 (含订阅 Topic、ACK、消息历史) 见 <code>z-agent/z-agent-team/z-agent-team-frontend</code>。
                        本页提供轻量级 WS 连接 + 群组管理 REST 端点示例。
                    </span>
                }
            />
        </PageContainer>
    )
}
