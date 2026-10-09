import React from 'react'
import {Alert, Button, Space} from 'antd'
import {BugOutlined, ReloadOutlined} from '@ant-design/icons'

/**
 * React Error Boundary — 捕获子组件的 render/lifecycle/effect 异常，
 * 用 fallback UI 替代冒泡到整棵 React 树导致整体卸载。
 *
 * 用法：
 *   <ErrorBoundary fallbackTitle="流程编辑器加载失败">
 *     <WorkflowEditor />
 *   </ErrorBoundary>
 */
export default class ErrorBoundary extends React.Component {
    constructor(props) {
        super(props)
        this.state = {hasError: false, error: null, errorInfo: null}
    }

    static getDerivedStateFromError(error) {
        return {hasError: true, error}
    }

    componentDidCatch(error, errorInfo) {
        // eslint-disable-next-line no-console
        console.error('[ErrorBoundary] caught error:', error, errorInfo)
        this.setState({errorInfo})
    }

    handleReset = () => {
        this.setState({hasError: false, error: null, errorInfo: null})
        if (typeof this.props.onReset === 'function') {
            this.props.onReset()
        }
    }

    render() {
        if (!this.state.hasError) {
            return this.props.children
        }

        const {fallbackTitle = '组件加载失败', fallbackDescription, showReload = true} = this.props
        const errMsg = (this.state.error && (this.state.error.message || String(this.state.error))) || '未知错误'

        return (
            <div
                style={{
                    padding: 24,
                    background: '#fff2f0',
                    border: '1px solid #ffccc7',
                    borderRadius: 8,
                    minHeight: 200,
                }}
            >
                <Alert
                    type="error"
                    showIcon
                    icon={<BugOutlined/>}
                    message={fallbackTitle}
                    description={
                        <div>
                            <div style={{marginBottom: 8}}>
                                {fallbackDescription || '当前组件发生了异常，已被错误边界捕获。其他功能仍可正常使用。'}
                            </div>
                            <div
                                style={{
                                    fontFamily: 'Menlo, Monaco, Consolas, monospace',
                                    fontSize: 12,
                                    color: '#cf1322',
                                    background: '#fff',
                                    padding: 8,
                                    borderRadius: 4,
                                    border: '1px solid #ffccc7',
                                    wordBreak: 'break-word',
                                    whiteSpace: 'pre-wrap',
                                }}
                            >
                                {errMsg}
                            </div>
                        </div>
                    }
                />
                {showReload && (
                    <Space style={{marginTop: 16}}>
                        <Button icon={<ReloadOutlined/>} onClick={this.handleReset}>
                            重试
                        </Button>
                    </Space>
                )}
            </div>
        )
    }
}
