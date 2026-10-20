import { createRoot, type Root } from 'react-dom/client';
import type { ReactNode } from 'react';

/**
 * 把 React 元素渲染进**插件自己那块容器**（2026-10-19，P1）。
 *
 * <h3>为什么插件要能用 React</h3>
 * 之前插件只有 DOM（`container.appendChild(el)`）—— 稍微复杂一点的界面就要手写
 * 事件绑定、状态同步、卸载清理，写起来比 React 麻烦一个量级。
 * 而**宿主本来就是 React**，把宿主 React 交给插件即可，不需要额外运行时。
 *
 * <h3>两个约束</h3>
 * <ol>
 *   <li><b>一个容器只能有一个 root</b>：重复 `createRoot` 会告警并泄漏（React 18 的行为），
 *       所以这里用 WeakMap 记住每个容器的 root；</li>
 *   <li><b>卸载要 unmount</b>：只清 `innerHTML` 不会触发组件 `useEffect` 的清理，
 *       定时器/订阅会留在后台（幽灵插件的老问题）。</li>
 * </ol>
 *
 * <p>⚠️ 同一容器**不要**混用 `appendChild` 与 {@link renderReact}：
 * React 会接管容器内容，手写的 DOM 会被清掉。插件的两选一要写进文档。</p>
 */
const roots = new WeakMap<HTMLElement, Root>();

/** 渲染（可重复调用即更新）；返回卸载函数。 */
export function renderReact(container: HTMLElement, node: ReactNode): () => void {
  let root = roots.get(container);
  if (!root) {
    root = createRoot(container);
    roots.set(container, root);
  }
  root.render(node);

  return () => {
    const existing = roots.get(container);
    if (!existing) {
      return;
    }
    existing.unmount();
    roots.delete(container);
  };
}
