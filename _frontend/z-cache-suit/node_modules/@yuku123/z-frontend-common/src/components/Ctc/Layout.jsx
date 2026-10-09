import React, {useEffect, useRef, useState} from 'react'
import {Outlet, useLocation, useNavigate} from 'react-router-dom'
import {Avatar, Cascader, Divider, Dropdown, Layout as AntLayout, Menu, Space, Typography,} from 'antd'
import {
    AppstoreOutlined,
    AuditOutlined,
    CloudOutlined,
    ClusterOutlined,
    CodeOutlined,
    DownOutlined,
    LinkOutlined,
    LogoutOutlined,
    MenuFoldOutlined,
    MenuUnfoldOutlined,
    RobotOutlined,
    SafetyCertificateOutlined,
    SafetyOutlined,
    SettingOutlined,
    TeamOutlined,
    UserOutlined,
} from '@ant-design/icons'
import styles from './Layout.module.css'
import {getCurrentUser} from '../../services/api'

const {Header, Sider, Content} = AntLayout
const {Text} = Typography

const Layout = ({compact = false} = {}) => {
    const location = useLocation()
    const navigate = useNavigate()
    const [collapsed, setCollapsed] = useState(false)
    const [userName, setUserName] = useState('管理员')
    const [userInfo, setUserInfo] = useState(null)
    const [tenantOptions, setTenantOptions] = useState([])
    const [selectedTenant, setSelectedTenant] = useState([])

    const navigateRef = useRef(navigate)
    navigateRef.current = navigate

    useEffect(() => {
        const token = localStorage.getItem('token')
        if (!token) {
            navigateRef.current('/login')
            return
        }

        const userInfoStr = localStorage.getItem('userInfo')
        if (userInfoStr) {
            try {
                const user = JSON.parse(userInfoStr)
                setUserName(user.userName || '管理员')
                setUserInfo(user)
                if (user.tenantCode) {
                    setSelectedTenant([user.tenantCode])
                }
            } catch (e) {
            }
        }
        getCurrentUser().then(res => {
            if (res) {
                setUserInfo(res)
                setUserName(res.userName || '管理员')
                localStorage.setItem('userInfo', JSON.stringify(res))
            }
        }).catch(() => {
        })
        setTenantOptions([
            {
                value: 'tenant1',
                label: '租户1',
                children: [
                    {value: 'domain1-1', label: '域1-1'},
                    {value: 'domain1-2', label: '域1-2'},
                ],
            },
            {
                value: 'tenant2',
                label: '租户2',
                children: [
                    {value: 'domain2-1', label: '域2-1'},
                    {value: 'domain2-2', label: '域2-2'},
                ],
            },
        ])
    }, [])

    // 获取当前选中的菜单项
    const getSelectedKeys = () => {
        const path = location.pathname
        if (path.includes('/user')) return ['user']
        if (path.includes('/role')) return ['role']
        if (path.includes('/permission')) return ['permission']
        if (path.includes('/app')) return ['app']
        if (path.includes('/dict')) return ['dict']
        if (path.includes('/audit')) return ['audit']
        if (path.includes('/tenant')) return ['tenant']
        if (path.includes('/domain')) return ['domain']
        if (path.includes('/org')) return ['org']
        if (path.includes('/dept')) return ['dept']
        if (path.includes('/group')) return ['group']
        // 4A 域 (合并后菜单项直接是 4a/* 作为顶级 key)
        if (path.includes('/4a/')) {
            const parts = path.split('/4a/')[1]?.split('/') || []
            if (parts.length > 0) return [`4a/${parts[0]}`]
        }
        // z-webide
        if (path.includes('/webide')) return ['webide']
        // z-agent-team
        if (path.includes('/agent-team')) return ['agent-team']
        return ['4a/account']
    }

    // 菜单项
    const menuItems = [
        // ===== FEATURE015: 合并 4A 到顶层, 一套菜单 =====
        {
            key: '4a/account',
            icon: <TeamOutlined/>,
            label: '账号管理',
        },
        {
            key: '4a/role',
            icon: <SafetyOutlined/>,
            label: '角色管理',
        },
        {
            key: '4a/application',
            icon: <AppstoreOutlined/>,
            label: '应用管理',
        },
        {
            key: '4a/tenant',
            icon: <TeamOutlined/>,
            label: '租户管理',
        },
        {
            key: '4a/org',
            icon: <ClusterOutlined/>,
            label: '组织管理',
        },
        {
            key: '4a/permission',
            icon: <SafetyCertificateOutlined/>,
            label: '权限管理',
        },
        {
            key: '4a/surl',
            icon: <LinkOutlined/>,
            label: '短链管理',
        },

        {
            key: 'webide',
            icon: <CodeOutlined/>,
            label: 'WebIDE 容器',
        },
        {
            key: 'agent-team',
            icon: <RobotOutlined/>,
            label: 'Agent 群组 IM',
        },
        {
            type: 'divider',
        },
        {
            key: 'settings',
            icon: <SettingOutlined/>,
            label: '系统设置',
        },
    ]

    // 处理菜单点击
    const handleMenuClick = ({key}) => {
        navigate(`/ctc/${key}`)
    }

    return (
        <AntLayout className={styles.layout}>
            {compact ? null : (
            /* 顶部导航栏 (compact 模式: 由主壳 App.jsx 提供) */
            <>
            <Header className={styles.header}>
                <div className={styles.logo}>
                    <span className={styles.logoText}>Z-CTC 统一用户中心</span>
                </div>
                <div className={styles.headerRight}>
                    <Space size={24}>
                        <Dropdown
                            overlay={
                                <div style={{
                                    background: '#fff',
                                    borderRadius: '8px',
                                    boxShadow: '0 6px 16px rgba(0, 0, 0, 0.12)',
                                    padding: '16px',
                                    width: '360px'
                                }}>
                                    <div style={{
                                        display: 'flex',
                                        alignItems: 'center',
                                        gap: '12px',
                                        marginBottom: '12px'
                                    }}>
                                        <span style={{fontSize: '14px', color: '#666'}}>租户域</span>
                                        <Cascader
                                            value={selectedTenant}
                                            onChange={(value) => setSelectedTenant(value)}
                                            options={tenantOptions}
                                            placeholder="请选择租户域"
                                            style={{flex: 1}}
                                        />
                                    </div>
                                    <Divider style={{margin: '12px 0'}}/>
                                    <div style={{display: 'flex', flexDirection: 'column', gap: '8px'}}>
                                        <div style={{display: 'flex', fontSize: '14px'}}>
                                            <span style={{color: '#999', width: '70px'}}>用户名：</span>
                                            <span>{userInfo?.userName || '-'}</span>
                                        </div>
                                        <div style={{display: 'flex', fontSize: '14px'}}>
                                            <span style={{color: '#999', width: '70px'}}>真实姓名：</span>
                                            <span>{userInfo?.realName || '-'}</span>
                                        </div>
                                        <div style={{display: 'flex', fontSize: '14px'}}>
                                            <span style={{color: '#999', width: '70px'}}>邮箱：</span>
                                            <span>{userInfo?.email || '-'}</span>
                                        </div>
                                        <div style={{display: 'flex', fontSize: '14px'}}>
                                            <span style={{color: '#999', width: '70px'}}>手机号：</span>
                                            <span>{userInfo?.phone || '-'}</span>
                                        </div>
                                        <div style={{display: 'flex', fontSize: '14px'}}>
                                            <span style={{color: '#999', width: '70px'}}>状态：</span>
                                            <span>{userInfo?.status === 1 ? '正常' : '停用'}</span>
                                        </div>
                                    </div>
                                    <Divider style={{margin: '12px 0'}}/>
                                    <div style={{display: 'flex', flexDirection: 'column'}}>
                                        <div style={{
                                            display: 'flex',
                                            alignItems: 'center',
                                            gap: '8px',
                                            padding: '8px 12px',
                                            cursor: 'pointer',
                                            borderRadius: '4px',
                                            fontSize: '14px'
                                        }} onClick={() => {
                                        }}>
                                            <UserOutlined/> 个人中心
                                        </div>
                                        <div style={{
                                            display: 'flex',
                                            alignItems: 'center',
                                            gap: '8px',
                                            padding: '8px 12px',
                                            cursor: 'pointer',
                                            borderRadius: '4px',
                                            fontSize: '14px'
                                        }} onClick={() => {
                                        }}>
                                            <SettingOutlined/> 系统设置
                                        </div>
                                        <div style={{
                                            display: 'flex',
                                            alignItems: 'center',
                                            gap: '8px',
                                            padding: '8px 12px',
                                            cursor: 'pointer',
                                            borderRadius: '4px',
                                            fontSize: '14px'
                                        }} onClick={() => {
                                            localStorage.removeItem('token')
                                            localStorage.removeItem('userInfo')
                                            navigate('/ctc/login')
                                        }}>
                                            <LogoutOutlined/> 退出登录
                                        </div>
                                    </div>
                                </div>
                            }
                            placement="bottomRight"
                            trigger={['click']}
                        >
                            <div className={styles.userInfo}>
                                <Avatar icon={<UserOutlined/>} size="small"/>
                                <Text className={styles.userName}>{userName}</Text>
                                <DownOutlined/>
                            </div>
                        </Dropdown>
                    </Space>
                </div>
            </Header>
            </>
            )}

            <AntLayout className={styles.mainLayout}>
                {/* 侧边菜单 */}
                <Sider
                    trigger={null}
                    collapsible
                    collapsed={collapsed}
                    className={styles.sider}
                    width={200}
                >
                    <Menu
                        mode="inline"
                        selectedKeys={getSelectedKeys()}
                        defaultOpenKeys={['user', 'role', 'permission', 'audit', 'tenant', 'domain', 'org', 'dept', 'group']}
                        className={styles.menu}
                        items={menuItems}
                        onClick={handleMenuClick}
                    />
                    <div
                        className={styles.collapsedBtn}
                        onClick={() => setCollapsed(!collapsed)}
                    >
                        {collapsed ? <MenuUnfoldOutlined/> : <MenuFoldOutlined/>}
                    </div>
                </Sider>

                {/* 主内容区 */}
                <Content className={styles.content}>
                    <Outlet/>
                </Content>
            </AntLayout>
        </AntLayout>
    )
}

export default Layout