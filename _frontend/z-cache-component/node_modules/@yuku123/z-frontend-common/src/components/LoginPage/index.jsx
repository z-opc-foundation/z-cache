import {useState} from 'react'
import {Button, Card, Form, Input, message} from 'antd'
import {LockOutlined, UserOutlined} from '@ant-design/icons'
import request from '../../utils/request'

/**
 * 统一登录页面组件
 * @param {Object} props
 * @param {string} props.title - 页面标题
 * @param {string} props.loginApi - 登录 API 路径，默认 /auth/login
 * @param {string} props.redirectUrl - 登录成功后跳转地址，默认 /
 * @param {Function} props.onSuccess - 登录成功回调，覆盖 redirectUrl
 */
export default function LoginPage({
                                      title = '统一管理平台',
                                      loginApi = '/auth/login',
                                      redirectUrl = '/',
                                      onSuccess,
                                  }) {
    const [loading, setLoading] = useState(false)

    const handleSubmit = async (values) => {
        setLoading(true)
        try {
            const res = await request.post(loginApi, values)
            if (res.token) {
                localStorage.setItem('token', res.token)
                localStorage.setItem('userInfo', JSON.stringify(res.user || res))
                message.success('登录成功')
                if (onSuccess) {
                    onSuccess(res)
                } else {
                    window.location.href = redirectUrl
                }
            } else {
                message.error('登录失败：未获取到 token')
            }
        } catch (err) {
            message.error(err.response?.data?.message || err.message || '登录失败')
        } finally {
            setLoading(false)
        }
    }

    return (
        <div style={{
            height: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center',
            background: '#f0f2f5',
        }}>
            <Card title={title} style={{width: 400}}>
                <Form onFinish={handleSubmit} size="large">
                    <Form.Item name="username" rules={[{required: true, message: '请输入用户名'}]}>
                        <Input prefix={<UserOutlined/>} placeholder="用户名"/>
                    </Form.Item>
                    <Form.Item name="password" rules={[{required: true, message: '请输入密码'}]}>
                        <Input.Password prefix={<LockOutlined/>} placeholder="密码"/>
                    </Form.Item>
                    <Form.Item>
                        <Button type="primary" htmlType="submit" loading={loading} block>
                            登 录
                        </Button>
                    </Form.Item>
                </Form>
            </Card>
        </div>
    )
}
