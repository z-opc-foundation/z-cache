import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '@yuku123/z-frontend-common'
import {menuItems, routeTable} from '@yuku123/z-cache-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/overview" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-cache 缓存中心" appShort="CACHE"/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
