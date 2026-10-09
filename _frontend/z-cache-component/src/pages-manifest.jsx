import {KeyOutlined, PieChartOutlined} from '@ant-design/icons'
import Overview from './pages/Overview'
import Keys from './pages/Keys'

export const menuItems = [
    {key: '/overview', icon: <PieChartOutlined/>, label: '总览'},
    {key: '/keys', icon: <KeyOutlined/>, label: '键浏览'},
]

const routeTable = [
    {path: 'overview', Component: Overview},
    {path: 'keys', Component: Keys},
]
export {routeTable}
export {default as Overview} from './pages/Overview'
export {default as Keys} from './pages/Keys'
