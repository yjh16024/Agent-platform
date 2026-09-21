import React from 'react';
import ReactDOM from 'react-dom/client';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import App from './App';
import { installDialogDragging } from './components/dialogDrag';
import { ThemeProvider } from './theme/ThemeProvider';
// 全局样式：滚动条外观 + 侧栏设置面板的独占规则（见文件内注释）
import './styles/global.css';
import { CONTRACT_VERSION } from './skin/contract';
import { installCustomizationHost } from './skin/customization';
import { restoreEnabledSkin, setContractVersion } from './skin/runtime';
import { installTitleGuard } from './skin/titleGuard';
import 'antd/dist/reset.css';

// 把契约版本告诉运行时：它是"支持度缓存"的 key 之一 ——
// 我们改了钩子集合，之前对某个皮肤下过的结论就该失效并重测。
setContractVersion(CONTRACT_VERSION);

/*
 * 装「皮肤自定义协议」的宿主侧监听 —— 必须在**任何皮肤脚本执行前**完成，
 * 否则皮肤加载时 dispatch 的那次 register 会没人接（它虽然还会响应 ready 重注册，
 * 但先装好就不必依赖那次握手）。安装时会立刻 dispatch ready，所以两种加载顺序都收敛。
 */
installCustomizationHost();

/*
 * 让弹窗可以拖动：皮肤设置面板 + 所有 antd Modal（含 confirm/info）。
 *
 * 装在这里而不是包一层 DraggableModal —— 全项目 15+ 处 `<Modal>`，而且以后新增的还会忘。
 * 它是 MutationObserver 驱动的（现有 DOM 先扫一遍、之后按需挂），所以**必须早于 React 渲染**调用，
 * 这样首屏出现的弹窗也不会漏。详见 components/dialogDrag.ts。
 */
installDialogDragging();

/*
 * 窗口标题守卫：DSH 系皮肤会**写死**它原本宿主的产品名去改 `document.title`
 * （实测 maid-atelier 是 `'深海女仆工坊 · DeepSeek Harness'`），于是标题栏写着别的产品。
 * 这里直接**固定为宿主产品名**：不维护"别家品牌别名表"（永远列不全），
 * 因为窗口标题是操作系统的界面元素，该由宿主决定。详见 skin/titleGuard.ts。
 *
 * 装在启动期即可：皮肤是 `setTimeout(..., 1500)` 之后才注入的，天然晚于这里。
 */
installTitleGuard();

// 主题（皮肤）由 ThemeProvider 统一提供：它内部就是 ConfigProvider，
// token 随皮肤配色走，同时把 --ap-* CSS 变量写到 :root 供自研布局使用。
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeProvider>
      {/*
        antd 的 <App> 必须放在**最外层**。
        它提供 message / notification / modal 的 context holder —— 只有处在 <App> 之内，
        useApp() 取到的实例才真的能把提示渲染出来。

        之前 <App> 只存在于 AppLayout 内部，而登录页在 AppLayout **之外**
        （App.tsx 里 /login 是独立路由，理由是"未登录时不该出现导航入口"），
        于是登录页那句 message.error(...) 静默失效 —— 表现就是"点了登录没有任何反应"，
        用户只能反复点（后端日志里能看到几十次重复的 401）。

        component={false}：不额外渲染一层 DOM，避免改变既有结构
        —— AppLayout 内部的 DOM 层级被皮肤选择器依赖（如 [data-slot='sidebar'] > :first-child），
        多包一层 div 有踩坏皮肤的风险。
      */}
      <AntApp component={false}>
        <HashRouter>
          <App />
        </HashRouter>
      </AntApp>
    </ThemeProvider>
  </React.StrictMode>,
);

/*
 * 启动时恢复「上次由用户显式启用」的皮肤。
 *
 * 两条自律：
 *  1. **只有用户明确启用过才自动加载** —— 加载皮肤即执行第三方代码，不能替用户默认决定；
 *  2. **延后一点再注入** —— 让宿主的契约钩子（data-slot / data-phase / 类名片段）先挂上去，
 *     否则皮肤一开始就在一个"还没有钩子"的 DOM 上初始化，会静默降级甚至报错。
 */
window.setTimeout(() => {
  restoreEnabledSkin().catch(() => undefined);
}, 1500);
