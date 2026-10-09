import {useEffect, useState} from 'react'
import {Button, Card, message, Select, Space, Tabs, Tag, Typography} from 'antd'
import {DashboardOutlined, GlobalOutlined, ReloadOutlined} from '@ant-design/icons'
import EndpointsTab from './EndpointsTab'
import EnvironmentsTab from './EnvironmentsTab'
import ScenariosTab from './ScenariosTab'
import TestCasesTab from './TestCasesTab'
import RecordingsTab from './RecordingsTab'
import RequestLogsTab from './RequestLogsTab'
import {mockPlatformApi} from '@/services/api'

const {Text} = Typography

const TAB_ITEMS = [
    {key: 'endpoints', label: 'Mock 端点', component: EndpointsTab},
    {key: 'environments', label: '环境 (MOCK/REAL/MIXED)', component: EnvironmentsTab},
    {key: 'scenarios', label: '场景状态机', component: ScenariosTab},
    {key: 'cases', label: '测试用例', component: TestCasesTab},
    {key: 'recordings', label: '录制回放', component: RecordingsTab},
    {key: 'request-logs', label: '请求日志', component: RequestLogsTab},
]

/**
 * Mock 平台主页 - 全功能 Mock 平台
 *
 * 能力对标 WireMock / Apifox / MockServer / ApiPost：
 *   1. Mock 端点管理 - URL/Header/Query/Body 多维匹配、优先级、模板渲染
 *   2. 环境隔离 - MOCK | REAL | MIXED 三种模式，无缝切换真实/Mock
 *   3. 场景状态机 - 业务流驱动的 Mock 集合（登录、下单、支付）
 *   4. 测试用例库 - 7 种断言，单条/批量运行，自动统计
 *   5. 录制回放 - 录制真实流量，一键转换为 Mock 集合
 *   6. 请求日志 - 排查"为什么没命中"，性能分析
 */
export default function MockPlatformPage() {
    const [activeTab, setActiveTab] = useState('endpoints')
    const [envs, setEnvs] = useState([])
    const [activeEnv, setActiveEnv] = useState('default')
    const [platformStats, setPlatformStats] = useState(null)

    useEffect(() => {
        loadEnvs()
        loadPlatformStats()
    }, [])

    const loadEnvs = async () => {
        try {
            const res = await mockPlatformApi.environments.list()
            const list = Array.isArray(res) ? res : []
            setEnvs(list)
            // Set default env if current is not in list
            if (list.length > 0 && !list.find(e => e.envCode === activeEnv)) {
                const def = list.find(e => e.isDefault === 1) || list[0]
                setActiveEnv(def.envCode)
            }
        } catch (e) {
            // backend not ready - keep defaults
        }
    }

    const loadPlatformStats = async () => {
        try {
            const stats = await mockPlatformApi.stats()
            setPlatformStats(stats)
        } catch (e) { /* ignore */
        }
    }

    const ActiveComponent = TAB_ITEMS.find(t => t.key === activeTab)?.component

    return (
        <div>
            <Card style={{marginBottom: 16}} bodyStyle={{padding: 16}}>
                <Space size="large" wrap>
                    <Space>
                        <DashboardOutlined style={{fontSize: 18, color: '#1890ff'}}/>
                        <Text strong style={{fontSize: 16}}>Mock 平台</Text>
                        <Tag color="blue">WireMock 级</Tag>
                    </Space>
                    <Space>
                        <GlobalOutlined/>
                        <Text>当前环境:</Text>
                        <Select
                            value={activeEnv}
                            onChange={setActiveEnv}
                            style={{width: 200}}
                            options={envs.map(e => ({
                                value: e.envCode,
                                label: `${e.envName || e.envCode} (${e.envType})`,
                            }))}
                        />
                    </Space>
                    {platformStats && (
                        <Space size="large">
                            <Text type="secondary">端点: <Text strong>{platformStats.totalEndpoints}</Text></Text>
                            <Text type="secondary">启用: <Text strong
                                                               style={{color: '#52c41a'}}>{platformStats.activeEndpoints}</Text></Text>
                            <Text type="secondary">场景: <Text strong>{platformStats.totalScenarios}</Text></Text>
                            <Text type="secondary">用例: <Text strong>{platformStats.totalCases}</Text></Text>
                        </Space>
                    )}
                    <Button icon={<ReloadOutlined/>} onClick={() => {
                        loadEnvs();
                        loadPlatformStats()
                    }}>刷新</Button>
                </Space>
            </Card>

            <Tabs
                activeKey={activeTab}
                onChange={setActiveTab}
                type="card"
                items={TAB_ITEMS.map(tab => ({
                    key: tab.key,
                    label: tab.label,
                    children: tab.component === EndpointsTab
                        ? <EndpointsTab activeEnvCode={activeEnv}/>
                        : tab.component === EnvironmentsTab
                            ? <EnvironmentsTab onEnvChange={(code) => {
                                setActiveEnv(code);
                                message.success(`已切换到环境: ${code}`)
                            }}/>
                            : <tab.component/>,
                }))}
            />
        </div>
    )
}
