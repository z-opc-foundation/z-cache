/**
 * z-frontend-common 开发调试入口
 */
import React from 'react'
import ReactDOM from 'react-dom/client'
import {LoginPage, StatusTag} from './index'

const DevApp = () => {
    return (
        <div style={{padding: 20}}>
            <h1>z-frontend-common Dev</h1>

            <h2>StatusTag 示例</h2>
            <StatusTag status={1}/>
            <StatusTag status={0}/>
            <StatusTag status="pending"/>
            <StatusTag status="approved"/>
            <StatusTag status="rejected"/>

            <h2>LoginPage 示例</h2>
            <div style={{height: 400, border: '1px solid #ccc', borderRadius: 8, overflow: 'hidden'}}>
                <LoginPage title="测试登录"/>
            </div>
        </div>
    )
}

ReactDOM.createRoot(document.getElementById('root')).render(<DevApp/>)
