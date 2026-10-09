export {default as AppLayout} from './components/Layout'
export {default as StatusTag, defaultStatusMap} from './components/StatusTag'
export {default as LoginPage} from './components/LoginPage'
export {default as ErrorBoundary} from './components/ErrorBoundary'
export {default as Overview} from './components/Overview'
export {default as request} from './utils/request'
export {authRequest, createRequest, setupInterceptors} from './utils/request'
export {ctcRequest} from './services/request'

// 4A 中心 Layout + 登录
export {default as CtcLayout} from './components/Ctc/Layout'
export {default as CtcAuthPage} from './components/Ctc/login/AuthPage'
export {default as CtcLogin} from './components/Ctc/login/index'
export {default as AgentTeamIM} from './components/Ctc/agentTeam'
export {default as UserPanel} from './components/Ctc/userPanel'
export {default as Webide} from './components/Ctc/webide'

// 低代码建模
export {default as LowCodePage} from './components/LowCode/LowCodePage'
export {default as LowCodeRuntime} from './components/LowCode/LowCodeRuntime'
export {default as LowCodeModel} from './components/LowCode/LowCodeModel'
export {default as MaterializePage} from './components/LowCode/MaterializePage'

// Mock 平台 (工具平台)
export {default as MockPlatform} from './components/MockPlatform/index'
export {default as EndpointsTab} from './components/MockPlatform/EndpointsTab'
export {default as ScenariosTab} from './components/MockPlatform/ScenariosTab'
export {default as TestCasesTab} from './components/MockPlatform/TestCasesTab'
export {default as RequestLogsTab} from './components/MockPlatform/RequestLogsTab'
export {default as RecordingsTab} from './components/MockPlatform/RecordingsTab'
export {default as EnvironmentsTab} from './components/MockPlatform/EnvironmentsTab'
export {default as MockTemplateHelper} from './components/MockPlatform/MockTemplateHelper'
export {default as CurlImportModal} from './components/MockPlatform/CurlImportModal'
export {default as OpenApiImportModal} from './components/MockPlatform/OpenApiImportModal'

export default common
