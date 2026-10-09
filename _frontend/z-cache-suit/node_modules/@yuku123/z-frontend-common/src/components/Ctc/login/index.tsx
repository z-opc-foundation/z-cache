import React, {useState} from 'react';
import {useNavigate, Link} from 'react-router-dom';
import {Alert, Button, Checkbox, Form, Input, message, Tabs} from 'antd';
import {LockOutlined, UserOutlined} from '@ant-design/icons';
import {login} from '@/services/api';

const containerStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    height: '100vh',
    overflow: 'auto',
    background: '#f0f2f5',
    backgroundImage: "url('https://gw.alipayobjects.com/zos/rmsportal/TVYTbAXWheQpRcWDaDMu.svg')",
    backgroundRepeat: 'no-repeat',
    backgroundPosition: 'center 110px',
    backgroundSize: '100%',
};

const contentStyle: React.CSSProperties = {
    flex: 1,
    padding: '32px 0',
    maxWidth: 400,
    margin: '0 auto',
    width: '100%',
};

const Login: React.FC = () => {
    const [userLoginState, setUserLoginState] = useState<any>({});
    const [type] = useState<string>('account');
    const [loading, setLoading] = useState(false);
    const navigate = useNavigate();

    const handleSubmit = async (values: { userName: string; password: string }) => {
        setLoading(true);
        try {
            // api.login() 后端 LoginRequest: {identifier, password}
            const response: any = await login({identifier: values.userName, password: values.password});
            if (response && response.token) {
                localStorage.setItem('token', response.token);
                const acct = response.account || {}
                localStorage.setItem('userInfo', JSON.stringify({
                    userId: acct.id,
                    userName: acct.username,
                    nickname: acct.nickname,
                    tenantCode: acct.tenantCode,
                }));
                message.success('登录成功！');
                navigate('/');
                return;
            }
            setUserLoginState(response);
        } catch (error) {
            message.error('登录失败，请重试！');
        } finally {
            setLoading(false);
        }
    };

    const {status} = userLoginState;

    return (
        <div style={containerStyle}>
            <div style={contentStyle}>
                {/* 品牌标识 — 内联 SVG（不再依赖外部 /logo.svg 文件） */}
                <div style={{textAlign: 'center', marginBottom: 24}}>
                    <svg width="56" height="56" viewBox="0 0 56 56" fill="none" aria-label="logo">
                        <defs>
                            <linearGradient id="brandGrad" x1="0" y1="0" x2="56" y2="56" gradientUnits="userSpaceOnUse">
                                <stop offset="0%" stopColor="#1677ff"/>
                                <stop offset="100%" stopColor="#0958d9"/>
                            </linearGradient>
                        </defs>
                        <rect x="2" y="2" width="52" height="52" rx="14" fill="url(#brandGrad)"/>
                        <path
                            d="M18 18 H40 L18 38 H40"
                            stroke="#ffffff"
                            strokeWidth="3.5"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            fill="none"
                        />
                    </svg>
                </div>

                <Tabs activeKey={type} centered items={[{key: 'account', label: '账户密码登录'}]}/>

                {status === 'error' && (
                    <Alert message="账户或密码错误" type="error" showIcon style={{marginBottom: 16}}/>
                )}

                <Form
                    onFinish={handleSubmit}
                    initialValues={{autoLogin: true}}
                    layout="vertical"
                >
                    <Form.Item
                        name="userName"
                        rules={[{required: true, message: '请输入用户名!'}]}
                    >
                        <Input
                            size="large"
                            prefix={<UserOutlined/>}
                            placeholder="用户名"
                        />
                    </Form.Item>

                    <Form.Item
                        name="password"
                        rules={[{required: true, message: '请输入密码!'}]}
                    >
                        <Input.Password
                            size="large"
                            prefix={<LockOutlined/>}
                            placeholder="密码"
                        />
                    </Form.Item>

                    <Form.Item name="autoLogin" valuePropName="checked">
                        <Checkbox>自动登录</Checkbox>
                    </Form.Item>

                    <Form.Item>
                        <Button type="primary" htmlType="submit" size="large" block loading={loading}>
                            登录
                        </Button>
                    </Form.Item>
                </Form>

                {/* FEATURE012: 用户中心入口 — 注册/找回/手机登录 */}
                <div style={{textAlign: 'center', marginTop: 16, color: '#888', fontSize: 14}}>
                    <Link to="/uc/register" style={{marginRight: 16}}>用户注册</Link>
                    <span style={{margin: '0 8px', color: '#ddd'}}>|</span>
                    <Link to="/uc/reset-password" style={{marginRight: 16}}>找回密码</Link>
                    <span style={{margin: '0 8px', color: '#ddd'}}>|</span>
                    <Link to="/uc/login">手机验证码登录</Link>
                </div>
            </div>
        </div>
    );
};

export default Login;
