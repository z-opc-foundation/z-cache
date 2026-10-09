import {Card, Col, Row, Statistic, Typography} from 'antd'
import {AuditOutlined, FileTextOutlined, SettingOutlined, UserOutlined,} from '@ant-design/icons'

const {Title, Text, Paragraph} = Typography

const Overview = () => {
    return (
        <div style={{padding: '0'}}>
            <div style={{marginBottom: 24}}>
                <Title level={2} style={{margin: 0}}>Z-One Company 统一管理平台</Title>
                <Text type="secondary">一站式企业级应用管理平台</Text>
            </div>

            <Row gutter={16} style={{marginBottom: 24}}>
                <Col span={6}>
                    <Card>
                        <Statistic
                            title="配置中心"
                            value={0}
                            prefix={<SettingOutlined/>}
                        />
                    </Card>
                </Col>
                <Col span={6}>
                    <Card>
                        <Statistic
                            title="任务中心"
                            value={0}
                            prefix={<FileTextOutlined/>}
                        />
                    </Card>
                </Col>
                <Col span={6}>
                    <Card>
                        <Statistic
                            title="工作流"
                            value={0}
                            prefix={<AuditOutlined/>}
                        />
                    </Card>
                </Col>
                <Col span={6}>
                    <Card>
                        <Statistic
                            title="用户中心"
                            value={0}
                            prefix={<UserOutlined/>}
                        />
                    </Card>
                </Col>
            </Row>

            <Card title="关于 Z-One Company">
                <Paragraph>
                    Z-One Company 是一站式企业级应用管理平台，整合了配置中心、任务中心、工作流、用户中心等多个模块。
                </Paragraph>
                <Paragraph>
                    <strong>核心模块：</strong>
                </Paragraph>
                <ul>
                    <li>配置中心 - 集中管理应用配置</li>
                    <li>任务中心 - 任务调度与执行</li>
                    <li>工作流 - 流程审批与管理</li>
                    <li>用户中心 - 统一身份认证与权限管理</li>
                    <li>调度中心 - 定时任务管理</li>
                    <li>Mist - 密钥管理</li>
                    <li>元数据 - API与应用管理</li>
                </ul>
            </Card>
        </div>
    )
}

export default Overview
