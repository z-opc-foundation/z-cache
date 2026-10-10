import { HomeOutlined, KeyOutlined, PieChartOutlined } from '@ant-design/icons'
import Overview from './pages/Overview'
import Keys from './pages/Keys'


export {default as Overview} from './pages/Overview'
export {default as Keys} from './pages/Keys'
import HomePage from './pages/HomePage'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-cache 缓存中心', short: 'z-cache' }

export const menuItems = [
    { key: '/z-cache/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-cache/overview', label: '总览', icon: <PieChartOutlined /> },
    { key: '/z-cache/keys', label: '键浏览', icon: <KeyOutlined /> },
]

export const routes = [
    { path: '/z-cache/home', Component: HomePage },
    { path: '/z-cache/overview', Component: Overview },
    { path: '/z-cache/keys', Component: Keys },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
