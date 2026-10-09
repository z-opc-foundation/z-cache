import {Card, Space, Typography} from 'antd'
import {AppLayout} from '@yuku123/z-frontend-common'

const {Title, Paragraph} = Typography

export default function App() {
  return (
    <AppLayout
      menuItems={[]}
      appTitle="缓存"
      appShort="CACHE"
    >
      <Card>
        <Space direction="vertical" size="middle">
          <Title level={3}>缓存</Title>
          <Paragraph>本仓是后端服务仓，独立运行壳已就绪（lead 005 §9.1 suit）。</Paragraph>
          <Paragraph type="secondary">前端页面正在迁移中，详见 z-opc-foundation lead §9。</Paragraph>
        </Space>
      </Card>
    </AppLayout>
  )
}
