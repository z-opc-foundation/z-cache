import {useEffect, useRef, useState} from 'react'
import {App, Avatar, Badge, Button, Empty, Form, Input, Modal, Popconfirm, Space, Spin, Tag, Tooltip, Tree} from 'antd'
import type {DataNode} from 'antd/es/tree'
import {
    CheckCircleOutlined,
    DeleteOutlined,
    FileOutlined,
    FileTextOutlined,
    FolderOpenOutlined,
    FolderOutlined,
    MessageOutlined,
    PlusOutlined,
    ReloadOutlined,
    SendOutlined,
    TeamOutlined,
} from '@ant-design/icons'
import {workspaceApi, workItemApi, type Workspace, type FileNode, type ConversationWorkItem} from '@/services/workspace'
import {agentTeamApi} from '@/services/agentTeam'

/**
 * 用户面板 · 工作项 + 工作空间 + IM 群聊 三合一页面
 *
 * 布局 (WeChat 风格):
 *   ┌──────────────┬─────────────────┬──────────────────────────────┐
 *   │ 工作项列表     │ 工作空间文件树    │ IM 群聊面板                   │
 *   │              │                 │                              │
 *   │ + 工作项      │ (点工作项展开)    │ 头部: 工作项标题 / 状态        │
 *   │ + 工作空间    │ FileTree        │ 消息流: 用户 / Agent 气泡     │
 *   │              │                 │ 输入框 + 发送                  │
 *   └──────────────┴─────────────────┴──────────────────────────────┘
 *
 * 数据流:
 *   - 工作项 (Conv) 一对多 工作空间 (Workspace)
 *   - 工作空间 1:1 映射本地目录 (localPath)
 *   - IM 消息走 z-agent-team WebSocket (/api/agent/team/ws?convCode=xxx)
 */

interface ChatMessage {
    msgCode: string
    senderType: 'USER' | 'AGENT' | 'SYSTEM' | string
    senderCode: string
    senderName: string
    content: string
    timestamp: number
    messageType?: string
}

const STATUS_COLOR: Record<string, string> = {
    OPEN: 'green',
    DONE: 'blue',
    ARCHIVED: 'default',
}

export default function UserPanel() {
    const {message} = App.useApp()
    const userInfoStr = localStorage.getItem('userInfo') || '{}'
    let userInfo: any = {}
    try {
        userInfo = JSON.parse(userInfoStr)
    } catch {
    }
    const currentUserId: string = userInfo?.userId ? String(userInfo.userId) : 'anonymous'
    const currentUserName: string = userInfo?.userName || userInfo?.name || '我'

    // ===== 工作项 (Conv) 列表 =====
    const [workItems, setWorkItems] = useState<ConversationWorkItem[]>([])
    const [loadingItems, setLoadingItems] = useState(false)
    const [selectedItem, setSelectedItem] = useState<ConversationWorkItem | null>(null)

    // ===== 工作空间 (Workspace) 列表 =====
    const [workspaces, setWorkspaces] = useState<Workspace[]>([])
    const [loadingWs, setLoadingWs] = useState(false)

    // ===== 当前工作项的文件树 =====
    const [fileTree, setFileTree] = useState<DataNode[]>([])
    const [loadingTree, setLoadingTree] = useState(false)

    // ===== 当前选中文件内容预览 =====
    const [previewFile, setPreviewFile] = useState<{path: string; content: string} | null>(null)
    const [loadingPreview, setLoadingPreview] = useState(false)

    // ===== IM 聊天 =====
    const [messages, setMessages] = useState<ChatMessage[]>([])
    const [chatInput, setChatInput] = useState('')
    const [wsState, setWsState] = useState<'disconnected' | 'connecting' | 'connected' | 'error'>('disconnected')
    const wsRef = useRef<WebSocket | null>(null)
    const messagesEndRef = useRef<HTMLDivElement | null>(null)
    const [streamingText, setStreamingText] = useState('')
    const [streamingFrom, setStreamingFrom] = useState('')

    // ===== UI 状态 =====
    const [createWsOpen, setCreateWsOpen] = useState(false)
    const [createItemOpen, setCreateItemOpen] = useState(false)
    const [wsForm] = Form.useForm()
    const [itemForm] = Form.useForm()
    const [activeWsCode, setActiveWsCode] = useState<string | null>(null) // 当前左侧选中的 workspace (用于切换文件树)

    // ===== 默认 Team code (用于新建工作项) =====
    const [defaultTeamCode, setDefaultTeamCode] = useState<string>('z-one-company-default')

    useEffect(() => {
        loadTeams()
        loadWorkspaces()
        loadWorkItems()
    }, [])

    useEffect(() => {
        // 自动滚动到消息底部
        if (messagesEndRef.current) {
            messagesEndRef.current.scrollIntoView({behavior: 'smooth'})
        }
    }, [messages, streamingText])

    // ===== 数据加载 =====

    const loadTeams = async () => {
        try {
            const list = await agentTeamApi.listTeams()
            if (list && list.length > 0) {
                setDefaultTeamCode(list[0].teamCode)
            }
        } catch {
            // ignore
        }
    }

    const loadWorkspaces = async () => {
        setLoadingWs(true)
        try {
            const list = await workspaceApi.list(currentUserId)
            setWorkspaces(list || [])
        } catch (e: any) {
            // 容错: 后端未启动时显示空列表
            setWorkspaces([])
        } finally {
            setLoadingWs(false)
        }
    }

    const loadWorkItems = async () => {
        setLoadingItems(true)
        try {
            const list = await workItemApi.listAllByOwner(currentUserId, 100)
            setWorkItems(list || [])
        } catch (e: any) {
            setWorkItems([])
        } finally {
            setLoadingItems(false)
        }
    }

    const loadFileTree = async (workspaceCode: string) => {
        if (!workspaceCode) {
            setFileTree([])
            return
        }
        setLoadingTree(true)
        try {
            const tree = await workspaceApi.fileTree(workspaceCode)
            setFileTree(tree ? [buildAntdTreeNode(tree)] : [])
        } catch (e: any) {
            message.error('加载文件树失败：' + (e?.message || '未知错误'))
            setFileTree([])
        } finally {
            setLoadingTree(false)
        }
    }

    // ===== IM 历史消息 =====

    const loadMessages = async (convCode: string) => {
        try {
            const list = await workItemApi.messages(convCode, 200)
            const mapped: ChatMessage[] = (list || []).map((m: any) => ({
                msgCode: m.msgCode,
                senderType: m.senderType,
                senderCode: m.senderCode,
                senderName: m.senderName,
                content: m.content,
                messageType: m.messageType,
                timestamp: m.gmtCreate ? new Date(m.gmtCreate).getTime() : Date.now(),
            }))
            setMessages(mapped)
        } catch (e: any) {
            setMessages([])
        }
    }

    // ===== WebSocket =====

    const connectWs = (convCode: string) => {
        disconnectWs()
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
        const url = `${protocol}//${window.location.host}/api/agent/team/ws?convCode=${encodeURIComponent(convCode)}&userId=${encodeURIComponent(currentUserId)}&userName=${encodeURIComponent(currentUserName)}`
        setWsState('connecting')
        try {
            const ws = new WebSocket(url)
            ws.onopen = () => {
                setWsState('connected')
            }
            ws.onmessage = (ev) => {
                try {
                    const event = JSON.parse(ev.data)
                    handleStreamEvent(event)
                } catch {
                    // ignore
                }
            }
            ws.onerror = () => {
                setWsState('error')
            }
            ws.onclose = () => {
                setWsState('disconnected')
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
            try {
                wsRef.current.close()
            } catch {
            }
            wsRef.current = null
            setWsState('disconnected')
            setStreamingText('')
        }
    }

    const handleStreamEvent = (event: any) => {
        const t = event.type
        if (t === 'AGENT_RESPONSE_DELTA' || t === 'AGENT_THINKING_DELTA') {
            setStreamingFrom(event.senderName || event.senderCode || 'Agent')
            setStreamingText((prev) => prev + (event.content || ''))
        } else if (t === 'AGENT_RESPONSE' || t === 'AGENT_THINKING') {
            setMessages((prev) => [
                ...prev,
                {
                    msgCode: event.eventId || `evt-${Date.now()}`,
                    senderType: event.senderType || 'AGENT',
                    senderCode: event.senderCode || '',
                    senderName: event.senderName || 'Agent',
                    content: event.content || '',
                    messageType: t,
                    timestamp: event.timestamp || Date.now(),
                },
            ])
            setStreamingText('')
            setStreamingFrom('')
        } else if (t === 'SYSTEM' || t === 'PARTICIPANT_JOIN' || t === 'PARTICIPANT_LEAVE') {
            // 系统消息折叠进消息流 (可选)
            if (event.content && event.content !== 'HEARTBEAT_ACK' && event.content !== 'PONG') {
                setMessages((prev) => [
                    ...prev,
                    {
                        msgCode: event.eventId || `evt-${Date.now()}`,
                        senderType: 'SYSTEM',
                        senderCode: event.senderCode || 'system',
                        senderName: '系统',
                        content: event.content,
                        messageType: t,
                        timestamp: event.timestamp || Date.now(),
                    },
                ])
            }
        } else if (t === 'ERROR') {
            setMessages((prev) => [
                ...prev,
                {
                    msgCode: event.eventId || `err-${Date.now()}`,
                    senderType: 'SYSTEM',
                    senderCode: 'system',
                    senderName: '错误',
                    content: event.content || '未知错误',
                    messageType: t,
                    timestamp: event.timestamp || Date.now(),
                },
            ])
            setStreamingText('')
            setStreamingFrom('')
        } else if (t === 'MESSAGE_END') {
            setStreamingText('')
            setStreamingFrom('')
        }
        // 其他类型 (TOOL_CALL / DELEGATION_* / TYPING / REACTION / READ_RECEIPT / PRESENCE) 暂不展示
    }

    const sendMessage = () => {
        const text = chatInput.trim()
        if (!text) return
        if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
            message.warning('IM 未连接')
            return
        }
        if (!selectedItem) {
            message.warning('请先选择一个工作项')
            return
        }
        const payload = JSON.stringify({type: 'USER_MESSAGE', content: text})
        wsRef.current.send(payload)
        // 立即在本地展示
        setMessages((prev) => [
            ...prev,
            {
                msgCode: `local-${Date.now()}`,
                senderType: 'USER',
                senderCode: currentUserId,
                senderName: currentUserName,
                content: text,
                messageType: 'TEXT',
                timestamp: Date.now(),
            },
        ])
        setChatInput('')
    }

    // ===== 工作项选择 =====

    const handleSelectItem = (item: ConversationWorkItem) => {
        setSelectedItem(item)
        loadMessages(item.convCode)
        connectWs(item.convCode)
        // 自动加载绑定的 workspace 文件树
        if (item.workspaceCode) {
            setActiveWsCode(item.workspaceCode)
            loadFileTree(item.workspaceCode)
        } else {
            setActiveWsCode(null)
            setFileTree([])
        }
    }

    // ===== 创建工作项 =====

    const handleCreateItem = async () => {
        try {
            const values = await itemForm.validateFields()
            const created = await workItemApi.create({
                teamCode: defaultTeamCode,
                userId: currentUserId,
                userName: currentUserName,
                title: values.title,
                workspaceCode: values.workspaceCode || undefined,
            })
            message.success('工作项已创建')
            setCreateItemOpen(false)
            itemForm.resetFields()
            await loadWorkItems()
            if (created) {
                handleSelectItem(created)
            }
        } catch (e: any) {
            message.error('创建失败：' + (e?.message || '未知错误'))
        }
    }

    // ===== 创建工作空间 =====

    const handleCreateWorkspace = async () => {
        try {
            const values = await wsForm.validateFields()
            await workspaceApi.create({
                workspaceName: values.workspaceName,
                localPath: values.localPath,
                ownerId: currentUserId,
                ownerName: currentUserName,
                description: values.description,
            })
            message.success('工作空间已创建')
            setCreateWsOpen(false)
            wsForm.resetFields()
            await loadWorkspaces()
        } catch (e: any) {
            const errMsg = e?.response?.data?.message || e?.message || '未知错误'
            message.error('创建失败：' + errMsg)
        }
    }

    // ===== 文件点击预览 =====

    const handleFileClick = async (absPath: string) => {
        setLoadingPreview(true)
        try {
            const resp = await workspaceApi.fileContent(absPath)
            setPreviewFile({path: absPath, content: resp?.content || ''})
        } catch (e: any) {
            message.error('读取文件失败：' + (e?.message || '未知错误'))
        } finally {
            setLoadingPreview(false)
        }
    }

    // ===== 关闭工作项 =====

    const handleCloseItem = async (convCode: string) => {
        try {
            await workItemApi.close(convCode, 'DONE')
            message.success('已关闭工作项')
            await loadWorkItems()
            if (selectedItem?.convCode === convCode) {
                setSelectedItem(null)
                disconnectWs()
                setMessages([])
                setFileTree([])
            }
        } catch (e: any) {
            message.error('关闭失败：' + (e?.message || '未知错误'))
        }
    }

    // ===== 删除工作空间 =====

    const handleDeleteWs = async (workspaceCode: string) => {
        try {
            await workspaceApi.delete(workspaceCode)
            message.success('已删除')
            await loadWorkspaces()
            if (activeWsCode === workspaceCode) {
                setActiveWsCode(null)
                setFileTree([])
            }
        } catch (e: any) {
            message.error('删除失败：' + (e?.message || '未知错误'))
        }
    }

    // ===== Tree helpers =====

    const buildAntdTreeNode = (node: FileNode): DataNode => {
        return {
            key: node.absolutePath,
            title: (
                <span>
                    {node.isDirectory ? <FolderOutlined style={{marginRight: 4, color: '#faad14'}}/> :
                        <FileOutlined style={{marginRight: 4, color: '#8c8c8c'}}/>}
                    {node.name}
                    {node.isDirectory && node.totalDescendants > 0 ? (
                        <span style={{color: '#bfbfbf', marginLeft: 6, fontSize: 11}}>({node.totalDescendants})</span>
                    ) : null}
                </span>
            ),
            children: node.children?.map(buildAntdTreeNode) || [],
            isLeaf: !node.isDirectory,
            disabled: false,
        }
    }

    // ===== Render =====

    return (
        <div style={{
            display: 'grid',
            gridTemplateColumns: '240px 280px 1fr',
            height: 'calc(100vh - 130px)',
            background: '#fff',
            border: '1px solid #f0f0f0',
            borderRadius: 8,
            overflow: 'hidden',
        }}>
            {/* ===== 第 1 列: 工作项列表 ===== */}
            <div style={{
                borderRight: '1px solid #f0f0f0',
                display: 'flex',
                flexDirection: 'column',
                background: '#fafafa',
            }}>
                <div style={{
                    padding: 12,
                    borderBottom: '1px solid #f0f0f0',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                }}>
                    <Space size={4}>
                        <MessageOutlined style={{color: '#1677ff'}}/>
                        <strong>工作项</strong>
                    </Space>
                    <Space size={4}>
                        <Tooltip title="刷新">
                            <Button size="small" type="text" icon={<ReloadOutlined/>} onClick={loadWorkItems}/>
                        </Tooltip>
                        <Tooltip title="新建工作项">
                            <Button size="small" type="text" icon={<PlusOutlined/>}
                                    onClick={() => setCreateItemOpen(true)}/>
                        </Tooltip>
                    </Space>
                </div>
                <div style={{flex: 1, overflow: 'auto'}}>
                    {loadingItems ? (
                        <div style={{padding: 24, textAlign: 'center'}}><Spin size="small"/></div>
                    ) : workItems.length === 0 ? (
                        <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="暂无工作项"
                            style={{padding: 24, marginTop: 24}}
                        >
                            <Button size="small" type="primary" onClick={() => setCreateItemOpen(true)}>
                                新建工作项
                            </Button>
                        </Empty>
                    ) : (
                        workItems.map((item) => (
                            <div
                                key={item.convCode}
                                onClick={() => handleSelectItem(item)}
                                style={{
                                    padding: '10px 12px',
                                    cursor: 'pointer',
                                    borderBottom: '1px solid #f5f5f5',
                                    background: selectedItem?.convCode === item.convCode ? '#e6f7ff' : 'transparent',
                                    transition: 'background 0.2s',
                                }}
                            >
                                <div style={{
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'space-between',
                                    marginBottom: 4,
                                }}>
                                    <strong style={{
                                        fontSize: 13,
                                        overflow: 'hidden',
                                        textOverflow: 'ellipsis',
                                        whiteSpace: 'nowrap',
                                        maxWidth: 150,
                                    }}>
                                        {item.title || '未命名'}
                                    </strong>
                                    <Badge count={item.unreadCount || 0} size="small"/>
                                </div>
                                <div style={{
                                    fontSize: 11,
                                    color: '#8c8c8c',
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap',
                                }}>
                                    {item.lastMsgPreview || '暂无消息'}
                                </div>
                                <div style={{marginTop: 4, fontSize: 11, color: '#bfbfbf'}}>
                                    <Tag color={STATUS_COLOR[item.status || 'OPEN']} style={{marginRight: 4, fontSize: 10, padding: '0 4px'}}>
                                        {item.status || 'OPEN'}
                                    </Tag>
                                    {item.workspaceCode ? <Tag color="purple" style={{fontSize: 10, padding: '0 4px'}}>有工作空间</Tag> : null}
                                </div>
                            </div>
                        ))
                    )}
                </div>
            </div>

            {/* ===== 第 2 列: 工作空间文件树 ===== */}
            <div style={{
                borderRight: '1px solid #f0f0f0',
                display: 'flex',
                flexDirection: 'column',
                background: '#fff',
            }}>
                <div style={{
                    padding: 12,
                    borderBottom: '1px solid #f0f0f0',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                }}>
                    <Space size={4}>
                        <FolderOpenOutlined style={{color: '#faad14'}}/>
                        <strong>工作空间</strong>
                    </Space>
                    <Space size={4}>
                        <Tooltip title="新建工作空间">
                            <Button size="small" type="text" icon={<PlusOutlined/>}
                                    onClick={() => setCreateWsOpen(true)}/>
                        </Tooltip>
                    </Space>
                </div>

                {/* 当前工作项绑定的 workspace */}
                {selectedItem?.workspaceCode ? (
                    <div style={{
                        padding: '8px 12px',
                        background: '#f0f5ff',
                        borderBottom: '1px solid #f0f0f0',
                        fontSize: 12,
                    }}>
                        <Space size={4}>
                            <Tag color="blue">当前工作项</Tag>
                            <span style={{color: '#1677ff'}}>
                                {workspaces.find(w => w.workspaceCode === selectedItem.workspaceCode)?.workspaceName || selectedItem.workspaceCode}
                            </span>
                            <Button
                                size="small"
                                type="text"
                                icon={<ReloadOutlined/>}
                                onClick={() => loadFileTree(selectedItem.workspaceCode!)}
                            />
                        </Space>
                    </div>
                ) : null}

                {/* 全部 workspace 列表 (可点击切换) */}
                <div style={{
                    maxHeight: 200,
                    overflow: 'auto',
                    borderBottom: '1px solid #f0f0f0',
                }}>
                    {loadingWs ? (
                        <div style={{padding: 12, textAlign: 'center'}}><Spin size="small"/></div>
                    ) : workspaces.length === 0 ? (
                        <div style={{padding: 12, color: '#8c8c8c', fontSize: 12, textAlign: 'center'}}>
                            暂无工作空间
                        </div>
                    ) : (
                        workspaces.map((ws) => (
                            <div
                                key={ws.workspaceCode}
                                onClick={() => {
                                    setActiveWsCode(ws.workspaceCode)
                                    loadFileTree(ws.workspaceCode)
                                }}
                                style={{
                                    padding: '6px 12px',
                                    cursor: 'pointer',
                                    fontSize: 12,
                                    background: activeWsCode === ws.workspaceCode ? '#e6f7ff' : 'transparent',
                                    borderLeft: activeWsCode === ws.workspaceCode ? '3px solid #1677ff' : '3px solid transparent',
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'space-between',
                                }}
                            >
                                <Space size={4} style={{overflow: 'hidden'}}>
                                    <FolderOutlined style={{color: '#faad14', fontSize: 12}}/>
                                    <span style={{
                                        overflow: 'hidden',
                                        textOverflow: 'ellipsis',
                                        whiteSpace: 'nowrap',
                                    }}>
                                        {ws.workspaceName}
                                    </span>
                                </Space>
                                <Popconfirm
                                    title="删除此工作空间?"
                                    onConfirm={(e) => {
                                        e?.stopPropagation()
                                        handleDeleteWs(ws.workspaceCode)
                                    }}
                                    onCancel={(e) => e?.stopPropagation()}
                                >
                                    <Button
                                        size="small"
                                        type="text"
                                        danger
                                        icon={<DeleteOutlined/>}
                                        onClick={(e) => e.stopPropagation()}
                                    />
                                </Popconfirm>
                            </div>
                        ))
                    )}
                </div>

                {/* 文件树 */}
                <div style={{flex: 1, overflow: 'auto', padding: 8}}>
                    {loadingTree ? (
                        <div style={{padding: 24, textAlign: 'center'}}><Spin/></div>
                    ) : !activeWsCode ? (
                        <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="请选择工作空间"
                            style={{marginTop: 40}}
                        />
                    ) : fileTree.length === 0 ? (
                        <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="空目录或无文件"
                            style={{marginTop: 40}}
                        />
                    ) : (
                        <Tree
                            treeData={fileTree}
                            defaultExpandAll={false}
                            showLine
                            showIcon={false}
                            onSelect={(keys, info) => {
                                const node = info.node
                                if (!node.isLeaf && keys.length > 0) {
                                    // 目录: 仅展开
                                    return
                                }
                                if (node.isLeaf && info.node.key) {
                                    handleFileClick(String(info.node.key))
                                }
                            }}
                        />
                    )}
                </div>

                {/* 选中的文件预览 */}
                {previewFile ? (
                    <div style={{
                        borderTop: '1px solid #f0f0f0',
                        maxHeight: 240,
                        overflow: 'hidden',
                        display: 'flex',
                        flexDirection: 'column',
                    }}>
                        <div style={{
                            padding: '6px 12px',
                            background: '#fafafa',
                            borderBottom: '1px solid #f0f0f0',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            fontSize: 11,
                        }}>
                            <Space size={4}>
                                <FileTextOutlined/>
                                <span style={{
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap',
                                    maxWidth: 180,
                                }}>
                                    {previewFile.path.split('/').pop()}
                                </span>
                            </Space>
                            <Button size="small" type="text" onClick={() => setPreviewFile(null)}>关闭</Button>
                        </div>
                        <pre style={{
                            flex: 1,
                            margin: 0,
                            padding: 8,
                            overflow: 'auto',
                            fontSize: 11,
                            background: '#1e1e1e',
                            color: '#d4d4d4',
                            fontFamily: 'Menlo, monospace',
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-all',
                        }}>
                            {loadingPreview ? '加载中...' : previewFile.content || '(空)'}
                        </pre>
                    </div>
                ) : null}
            </div>

            {/* ===== 第 3 列: IM 群聊面板 ===== */}
            <div style={{display: 'flex', flexDirection: 'column', background: '#f5f5f5'}}>
                {/* 头部 */}
                <div style={{
                    padding: '12px 16px',
                    background: '#fff',
                    borderBottom: '1px solid #f0f0f0',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                }}>
                    {selectedItem ? (
                        <Space size={8}>
                            <TeamOutlined style={{color: '#1677ff'}}/>
                            <strong style={{fontSize: 14}}>{selectedItem.title || '未命名'}</strong>
                            <Tag color={STATUS_COLOR[selectedItem.status || 'OPEN']} style={{marginLeft: 4}}>
                                {selectedItem.status || 'OPEN'}
                            </Tag>
                            {selectedItem.workspaceCode ? (
                                <Tag color="purple" icon={<FolderOpenOutlined/>}>
                                    {workspaces.find(w => w.workspaceCode === selectedItem.workspaceCode)?.workspaceName || '工作空间'}
                                </Tag>
                            ) : null}
                            <span style={{color: '#8c8c8c', fontSize: 12}}>
                                {selectedItem.messageCount || 0} 条消息
                            </span>
                        </Space>
                    ) : (
                        <span style={{color: '#8c8c8c'}}>请从左侧选择一个工作项</span>
                    )}
                    {selectedItem ? (
                        <Space size={4}>
                            <Tag color={wsState === 'connected' ? 'green' : wsState === 'error' ? 'red' : 'default'}>
                                {wsState === 'connected' ? '已连接' : wsState === 'connecting' ? '连接中' : wsState === 'error' ? '错误' : '未连接'}
                            </Tag>
                            <Popconfirm
                                title="关闭此工作项?"
                                onConfirm={() => handleCloseItem(selectedItem.convCode)}
                            >
                                <Button size="small" type="text" danger icon={<CheckCircleOutlined/>}>
                                    完成
                                </Button>
                            </Popconfirm>
                        </Space>
                    ) : null}
                </div>

                {/* 消息流 */}
                <div style={{
                    flex: 1,
                    overflow: 'auto',
                    padding: 16,
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 12,
                }}>
                    {!selectedItem ? (
                        <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="从左侧选择工作项开始聊天"
                            style={{marginTop: 80}}
                        />
                    ) : messages.length === 0 && !streamingText ? (
                        <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="暂无消息，开始聊吧"
                            style={{marginTop: 80}}
                        />
                    ) : (
                        <>
                            {messages.map((m) => <MessageBubble key={m.msgCode} msg={m} currentUserId={currentUserId}/>)}
                            {streamingText ? (
                                <MessageBubble
                                    msg={{
                                        msgCode: 'streaming',
                                        senderType: 'AGENT',
                                        senderCode: 'agent',
                                        senderName: streamingFrom,
                                        content: streamingText,
                                        timestamp: Date.now(),
                                        messageType: 'STREAMING',
                                    }}
                                    currentUserId={currentUserId}
                                />
                            ) : null}
                            <div ref={messagesEndRef}/>
                        </>
                    )}
                </div>

                {/* 输入框 */}
                <div style={{
                    padding: 12,
                    background: '#fff',
                    borderTop: '1px solid #f0f0f0',
                    display: 'flex',
                    gap: 8,
                }}>
                    <Input.TextArea
                        value={chatInput}
                        onChange={(e) => setChatInput(e.target.value)}
                        onPressEnter={(e) => {
                            if (!e.shiftKey) {
                                e.preventDefault()
                                sendMessage()
                            }
                        }}
                        placeholder={selectedItem ? (wsState === 'connected' ? '输入消息... (Enter 发送, Shift+Enter 换行)' : '正在连接 IM...') : '请先选择工作项'}
                        autoSize={{minRows: 1, maxRows: 4}}
                        disabled={!selectedItem || wsState !== 'connected'}
                    />
                    <Button
                        type="primary"
                        icon={<SendOutlined/>}
                        onClick={sendMessage}
                        disabled={!selectedItem || wsState !== 'connected' || !chatInput.trim()}
                    >
                        发送
                    </Button>
                </div>
            </div>

            {/* ===== Modal: 新建工作空间 ===== */}
            <Modal
                title="新建工作空间"
                open={createWsOpen}
                onCancel={() => setCreateWsOpen(false)}
                onOk={handleCreateWorkspace}
                okText="创建"
                cancelText="取消"
            >
                <Form form={wsForm} layout="vertical">
                    <Form.Item
                        label="名称"
                        name="workspaceName"
                        rules={[{required: true, message: '请输入工作空间名称'}]}
                    >
                        <Input placeholder="例如：z-opc 主项目"/>
                    </Form.Item>
                    <Form.Item
                        label="本地路径"
                        name="localPath"
                        rules={[{required: true, message: '请输入本地路径'}]}
                        extra="服务器侧的文件系统绝对路径 (例: /Users/zifang/workplace)"
                    >
                        <Input placeholder="/Users/zifang/workplace"/>
                    </Form.Item>
                    <Form.Item label="描述" name="description">
                        <Input.TextArea rows={2} placeholder="可选：工作空间用途说明"/>
                    </Form.Item>
                </Form>
            </Modal>

            {/* ===== Modal: 新建工作项 ===== */}
            <Modal
                title="新建工作项"
                open={createItemOpen}
                onCancel={() => setCreateItemOpen(false)}
                onOk={handleCreateItem}
                okText="创建"
                cancelText="取消"
            >
                <Form form={itemForm} layout="vertical">
                    <Form.Item
                        label="标题"
                        name="title"
                        rules={[{required: true, message: '请输入工作项标题'}]}
                    >
                        <Input placeholder="例如：完成 z-opc 用户面板"/>
                    </Form.Item>
                    <Form.Item
                        label="关联工作空间 (可选)"
                        name="workspaceCode"
                        extra="绑定后，工作项右侧会显示对应工作空间的文件树"
                    >
                        <select
                            style={{width: '100%', height: 32, border: '1px solid #d9d9d9', borderRadius: 6, padding: '0 11px'}}
                        >
                            <option value="">无 (仅 IM 群聊)</option>
                            {workspaces.map((w) => (
                                <option key={w.workspaceCode} value={w.workspaceCode}>
                                    {w.workspaceName} ({w.localPath})
                                </option>
                            ))}
                        </select>
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    )
}

function MessageBubble({msg, currentUserId}: {msg: ChatMessage; currentUserId: string}) {
    const isMe = msg.senderType === 'USER' && msg.senderCode === currentUserId
    const isSystem = msg.senderType === 'SYSTEM'

    if (isSystem) {
        return (
            <div style={{textAlign: 'center', color: '#8c8c8c', fontSize: 11}}>
                <Tag color="default">{msg.content}</Tag>
            </div>
        )
    }

    const bubbleColor = isMe ? '#1677ff' : '#fff'
    const textColor = isMe ? '#fff' : '#333'
    const avatarBg = isMe ? '#1677ff' : '#87d068'

    return (
        <div style={{
            display: 'flex',
            flexDirection: isMe ? 'row-reverse' : 'row',
            alignItems: 'flex-start',
            gap: 8,
        }}>
            <Avatar style={{background: avatarBg, flexShrink: 0}}>
                {(msg.senderName || '?').charAt(0).toUpperCase()}
            </Avatar>
            <div style={{maxWidth: '70%'}}>
                <div style={{
                    fontSize: 11,
                    color: '#8c8c8c',
                    marginBottom: 2,
                    textAlign: isMe ? 'right' : 'left',
                }}>
                    {msg.senderName} · {new Date(msg.timestamp).toLocaleTimeString()}
                    {msg.messageType && msg.messageType !== 'TEXT' ? (
                        <Tag style={{marginLeft: 4, fontSize: 10}}>{msg.messageType}</Tag>
                    ) : null}
                </div>
                <div style={{
                    padding: '8px 12px',
                    background: bubbleColor,
                    color: textColor,
                    borderRadius: 8,
                    border: isMe ? 'none' : '1px solid #e8e8e8',
                    wordBreak: 'break-word',
                    whiteSpace: 'pre-wrap',
                    boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
                }}>
                    {msg.content || (msg.messageType === 'STREAMING' ? '...' : '')}
                </div>
            </div>
        </div>
    )
}