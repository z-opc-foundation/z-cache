/**
 * 组件对外的 manifest 出口。
 *
 * ⚠️ HomePage / LoginPage 也要在这里导出（2026-10-22 修）：
 * pages-manifest.jsx 一直导出了它们，但这个文件只转出了 {menuItems, routes, Overview, Keys}
 * ⇒ 消费方按 `import {HomePage, LoginPage} from '@yuku123/z-cache-component/pages'` 取会直接
 * "does not provide an export named 'HomePage'"（独立壳 z-cache-suit 就是这么白屏的）。
 * 补齐后，组件的公开出口与 manifest 实际提供的东西一致。
 */
export {menuItems, routes, Overview, Keys, HomePage, LoginPage} from './pages-manifest.jsx'
