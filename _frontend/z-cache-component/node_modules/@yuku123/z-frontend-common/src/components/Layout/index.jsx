import {Layout, Menu, Spin} from 'antd'
import {MenuFoldOutlined, MenuUnfoldOutlined} from '@ant-design/icons'
import {Outlet, useLocation, useNavigate} from 'react-router-dom'
import {useState} from 'react'

const {Header, Sider, Content} = Layout

/**
 * 统一布局组件
 * @param {Object} props
 * @param {Array} props.menuItems - antd Menu items 格式
 * @param {string} props.appTitle - 应用标题
 * @param {string} props.appShort - 折叠时标题缩写
 * @param {ReactNode} props.headerExtra - 头部右侧额外内容
 * @param {boolean} props.loading - 是否加载中
 */
export default function AppLayout({
                                      menuItems = [],
                                      appTitle = 'One Company',
                                      appShort = 'OC',
                                      headerExtra,
                                      loading = false,
                                  }) {
    const [collapsed, setCollapsed] = useState(false)
    const navigate = useNavigate()
    const location = useLocation()

    const handleMenuClick = ({key}) => {
        navigate(key)
    }

    return (
        <Layout style={{minHeight: '100vh'}}>
            <Sider trigger={null} collapsible collapsed={collapsed}>
                <div style={{
                    height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: 'white', fontSize: collapsed ? 14 : 18, fontWeight: 'bold',
                }}>
                    {collapsed ? appShort : appTitle}
                </div>
                {loading ? (
                    <div style={{padding: 16, textAlign: 'center'}}>
                        <Spin size="small"/>
                    </div>
                ) : (
                    <Menu theme="dark" mode="inline"
                          selectedKeys={[location.pathname]}
                          items={menuItems}
                          onClick={handleMenuClick}
                    />
                )}
            </Sider>
            <Layout>
                <Header style={{
                    padding: '0 16px', background: '#fff',
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                }}>
          <span onClick={() => setCollapsed(!collapsed)} style={{fontSize: 18, cursor: 'pointer'}}>
            {collapsed ? <MenuUnfoldOutlined/> : <MenuFoldOutlined/>}
          </span>
                    {headerExtra}
                </Header>
                <Content style={{margin: 16, padding: 24, background: '#fff', minHeight: 280}}>
                    <Outlet/>
                </Content>
            </Layout>
        </Layout>
    )
}
