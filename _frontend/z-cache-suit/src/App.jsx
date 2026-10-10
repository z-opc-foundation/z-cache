import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '../../../../_shared/z-frontend-common-local/dist/z-frontend-common.es.js'
import {menuItems, routeTable} from '@yuku123/z-cache-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/overview" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-cache 缓存中心" appShort="CACHE" appIcon={{icon: <img src="/icon.png" alt="CACHE" style={{width: "100%", height: "100%", objectFit: "cover", borderRadius: 8}}/>, color: '#f59e0b', label: 'CACHE'}}/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
