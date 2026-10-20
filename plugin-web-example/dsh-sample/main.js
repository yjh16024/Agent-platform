/*
 * 示例插件（P3，2026-10-19）：**DSH 形态**的写法。
 *
 * 这段代码照着 DSH 插件的 client 入口写：
 *   - ESM（`import` / `export default`）；
 *   - 依赖 `@deepseek-ai/dsh-client-runtime`（拿 React）与 `@deepseek-ai/dsh-client-ui-slots`（注册面板）；
 *   - `export default { name, inject, apply }` —— 这正是 Cordis 插件的形态。
 *
 * 它会被当成普通 `<script>` 加载一次（`import` 在经典脚本里是语法错误 → 整个文件不执行），
 * 然后宿主走「受限转译」路径：把 import 改写成 `__apHost.require(...)`、把 export default
 * 改成赋值，再以 blob 执行。所以**这里不需要打包器**。
 *
 * ⚠️ 边界：相对导入（`./chunk.js`）与 `export const` 不在支持范围 —— 那种插件需要 esbuild。
 */
import { React } from '@deepseek-ai/dsh-client-runtime';
import { registerTab } from '@deepseek-ai/dsh-client-ui-slots';

export default {
  name: 'dsh-sample',
  inject: ['storage'],

  apply(ctx) {
    const h = React.createElement;
    const clicks = ctx.storage.define({ key: 'clicks', default: 0 });

    const App = () =>
      h(
        'div',
        {
          className: 'ap-dsh-sample',
          title: '点击 +1（计数存在 storage 里）',
          onClick: () => {
            clicks.set(clicks.get() + 1);
            ctx.render(h(App));
          },
        },
        `DSH 形态插件 · 点击 ${clicks.get()} 次`,
      );

    ctx.render(h(App));

    // ui-slots 兼容层：注册到宿主右侧面板
    const offPanel = registerTab({
      title: 'DSH 示例面板',
      component: () =>
        h('div', { style: { fontSize: 12, color: '#555' } }, '这块内容来自 registerTab()（ui-slots 兼容层）'),
    });
    ctx.effect(() => offPanel, 'ui-slots panel');
  },
};
