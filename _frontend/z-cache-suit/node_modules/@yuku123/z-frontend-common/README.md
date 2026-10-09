# @yuku123/z-frontend-common

> z-opc 共享前端组件库 · React 19 + Ant Design 6

## 安装

```bash
npm install @yuku123/z-frontend-common
# 国内用户加速
# .npmrc: @yuku1231:registry=https://registry.npmmirror.com/
```

## 导出能力

### 组件
- `AppLayout` — 统一布局（Sider + Header + Content）
- `StatusTag` — 状态标签
- `LoginPage` — 统一登录页
- `ErrorBoundary` — React 错误边界

### 请求工具
- `request` — 默认实例（`baseURL=/api`，识别 `{code, data, message}` 后端响应）
- `authRequest` — 同上（语义区分）
- `createRequest({baseURL, tokenKey, userInfoKey, unauthorizedRedirect, timeout})` — 工厂
- `setupInterceptors(instance, options)` — 自定义拦截器

## 使用示例

```jsx
import { AppLayout, LoginPage, request } from '@yuku123/z-frontend-common'

// 业务模块推荐：在 src/utils/request.js 写 2-10 行适配层
import { createRequest } from '@yuku123/z-frontend-common'
const request = createRequest({ baseURL: '/meta', tokenKey: 'token' })
export default request
```

## 统一后端响应格式

```json
{ "code": 200, "data": T, "message": "ok" }
```

- `code=200` → 自动返回 `data`
- 其他 code → reject
- 响应无 `code` 字段 → pass-through 透传

## 长期方案

- 模板从 z-meta 拉取（数据库驱动）
- 业务侧 `res.data.xxx` 写法统一

## 仓库

https://github.com/yuku123/z-opc/tree/main/z-frontend-common

## License

MIT
