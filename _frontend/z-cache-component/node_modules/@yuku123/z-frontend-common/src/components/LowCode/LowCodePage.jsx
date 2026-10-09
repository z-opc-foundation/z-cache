import React from 'react'
import {useParams} from 'react-router-dom'
import LowCodeModel from './LowCodeModel'
import LowCodeRuntime from './LowCodeRuntime'

/**
 * LowCodePage 派发器 (z-lc Phase 1)
 * <p>
 * 单一入口 /form/:entity 拆分为两个真实页面:
 * <ul>
 *   <li>/form/_model?appCode=xxx   → LowCodeModel (实体/字段编辑器)</li>
 *   <li>/form/{entityCode}?appCode=xxx → LowCodeRuntime (通用 CRUD)</li>
 * </ul>
 */
const LowCodePage = () => {
    const {entity: entityCode} = useParams()
    if (!entityCode) {
        return (
            <div style={{padding: 24}}>
                缺少实体编码. 请访问 <code>/form/_model</code> 编辑模型,
                或 <code>/form/&#123;entityCode&#125;</code> 运行时 CRUD.
            </div>
        )
    }
    if (entityCode === '_model') {
        return <LowCodeModel/>
    }
    return <LowCodeRuntime/>
}

export default LowCodePage
