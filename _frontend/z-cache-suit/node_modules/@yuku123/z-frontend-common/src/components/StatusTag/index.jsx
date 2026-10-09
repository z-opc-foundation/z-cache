import {Tag} from 'antd'

const defaultStatusMap = {
    1: {color: 'green', text: '正常'},
    0: {color: 'red', text: '禁用'},
    pending: {color: 'orange', text: '待处理'},
    approved: {color: 'blue', text: '已通过'},
    rejected: {color: 'red', text: '已拒绝'},
}

export default function StatusTag({status, map}) {
    const config = map?.[status] || defaultStatusMap[status] || {color: 'default', text: String(status)}
    return <Tag color={config.color}>{config.text}</Tag>
}

export {defaultStatusMap}
