import React, {useState} from 'react'
import {useNavigate} from 'react-router-dom'
import {Button, Card, Checkbox, Form, Input, message, Tabs, Typography,} from 'antd'
import {LockOutlined, MailOutlined, MobileOutlined, UserOutlined} from '@ant-design/icons'
import {authRequest} from '../../../services/request'
import styles from './AuthPage.module.css'

const {Title, Text} = Typography

type LoginType = 'account' | 'phone' | 'email'

// 登录请求 — 后端 AuthController @ /api/ctc/auth/login, body: {identifier, password}
const loginByUsername = (data: { identifier: string; password: string }) =>
    authRequest.post('/ctc/auth/login', data)

// 注册 / 手机登录 / 验证码 — 后端暂未实现，占位返回空响应避免 UI 报错
const stubNotImplemented = () =>
    Promise.resolve({code: 0, message: '功能暂未开放'})

const AuthPage: React.FC = () => {
    const navigate = useNavigate()
    const [action, setAction] = useState<'login' | 'register'>('login')
    const [type, setType] = useState<LoginType>('account')
    const [sendingCode, setSendingCode] = useState(false)
    const [countdown, setCountdown] = useState(0)
    const [form] = Form.useForm()

    // 发送验证码 (后端暂未实现)
    const handleSendCode = async () => {
        message.info('验证码功能暂未开放')
    }

    // 处理登录/注册提交
    const handleSubmit = async (values: any) => {
        try {
            let response: any

            if (action === 'login' && type === 'account') {
                // 后端 LoginRequest: {identifier, password, tenantCode?}
                response = await loginByUsername({identifier: values.username, password: values.password})
                // 后端返回: {token, account: {id, username, nickname, tenantCode, ...}}
                if (response && response.token) {
                    message.success('登录成功！')
                    localStorage.setItem('token', response.token)
                    const acct = response.account || {}
                    localStorage.setItem('userInfo', JSON.stringify({
                        userId: acct.id,
                        userName: acct.username,
                        nickname: acct.nickname,
                        tenantCode: acct.tenantCode,
                    }))
                    navigate('/ctc')
                    return
                }
                message.error(response?.message || '登录失败')
            } else {
                // 注册 / 手机登录 — 后端暂未实现
                await stubNotImplemented()
                message.info('该功能暂未开放')
                return
            }
        } catch (error: any) {
            message.error(error.message || '操作失败，请重试')
        }
    }

    // Tab配置
    const getTabItems = () => {
        const items = [
            {key: 'account', label: '账户密码'},
        ]

        if (action === 'register') {
            items.push({key: 'phone', label: '手机注册'})
            items.push({key: 'email', label: '邮箱注册'})
        } else {
            items.push({key: 'phone', label: '手机验证码'})
        }

        return items
    }

    // 获取表单项
    const getFormItems = () => {
        const isLogin = action === 'login'

        return (
            <>
                {type === 'account' && (
                    <>
                        <Form.Item name="username" rules={[{required: true, message: '请输入用户名!'}]}>
                            <Input
                                size="large"
                                prefix={<UserOutlined/>}
                                placeholder="用户名"
                            />
                        </Form.Item>
                        <Form.Item name="password" rules={[{required: true, message: '请输入密码!'}]}>
                            <Input.Password
                                size="large"
                                prefix={<LockOutlined/>}
                                placeholder="密码"
                            />
                        </Form.Item>
                    </>
                )}

                {(type === 'phone' || type === 'email') && (
                    <>
                        <Form.Item
                            name="receiver"
                            rules={[
                                {required: true, message: type === 'phone' ? '请输入手机号!' : '请输入邮箱!'}
                            ]}
                        >
                            <Input
                                size="large"
                                prefix={type === 'phone' ? <MobileOutlined/> : <MailOutlined/>}
                                placeholder={type === 'phone' ? '手机号' : '邮箱'}
                                disabled={countdown > 0}
                            />
                        </Form.Item>
                        <Form.Item>
                            <div style={{display: 'flex', gap: 8}}>
                                <Input
                                    size="large"
                                    prefix={<LockOutlined/>}
                                    placeholder="验证码"
                                    style={{flex: 1}}
                                />
                                <Button
                                    type="primary"
                                    onClick={handleSendCode}
                                    loading={sendingCode}
                                    disabled={countdown > 0}
                                    style={{minWidth: 100}}
                                >
                                    {countdown > 0 ? `${countdown}秒` : '获取验证码'}
                                </Button>
                            </div>
                        </Form.Item>
                        {!isLogin && (
                            <Form.Item name="password" rules={[{required: true, message: '请输入密码!'}]}>
                                <Input.Password
                                    size="large"
                                    prefix={<LockOutlined/>}
                                    placeholder="设置密码"
                                />
                            </Form.Item>
                        )}
                    </>
                )}
            </>
        )
    }

    return (
        <div className={styles.container}>
            <div className={styles.content}>
                <Card className={styles.card}>
                    {/* 品牌标识 — 内联 SVG（不再显示"CTC 组织管理中心 / 4A + SSO 统一身份认证平台"那种奇怪描述） */}
                    <div className={styles.header} style={{textAlign: 'center'}}>
                        <svg width="48" height="48" viewBox="0 0 56 56" fill="none" aria-label="logo"
                             style={{marginBottom: 8}}>
                            <defs>
                                <linearGradient id="brandGradAuth" x1="0" y1="0" x2="56" y2="56"
                                                gradientUnits="userSpaceOnUse">
                                    <stop offset="0%" stopColor="#1677ff"/>
                                    <stop offset="100%" stopColor="#0958d9"/>
                                </linearGradient>
                            </defs>
                            <rect x="2" y="2" width="52" height="52" rx="14" fill="url(#brandGradAuth)"/>
                            <path
                                d="M18 18 H40 L18 38 H40"
                                stroke="#ffffff"
                                strokeWidth="3.5"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                fill="none"
                            />
                        </svg>
                        <Title level={4} style={{margin: 0}}>
                            {action === 'register' ? '用户注册' : '欢迎登录'}
                        </Title>
                    </div>

                    <Form
                        form={form}
                        layout="vertical"
                        onFinish={handleSubmit}
                        initialValues={{autoLogin: true}}
                    >
                        {action !== 'forgot' && (
                            <Tabs
                                activeKey={type}
                                onChange={(key) => setType(key as LoginType)}
                                centered
                                items={getTabItems()}
                            />
                        )}

                        {getFormItems()}

                        <Form.Item>
                            <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
                                {action === 'login' && (
                                    <Form.Item name="autoLogin" valuePropName="checked" noStyle>
                                        <Checkbox>自动登录</Checkbox>
                                    </Form.Item>
                                )}
                                <a
                                    onClick={() => {
                                        setAction(action === 'login' ? 'register' : 'login')
                                    }}
                                >
                                    {action === 'login'
                                        ? '没有账号？立即注册'
                                        : '已有账号？立即登录'}
                                </a>
                            </div>
                        </Form.Item>

                        <Form.Item>
                            <Button type="primary" htmlType="submit" block size="large">
                                {action === 'login' ? '登录' : '注册'}
                            </Button>
                        </Form.Item>
                    </Form>
                </Card>
            </div>
        </div>
    )
}

export default AuthPage