/*
 * 示例插件（P1 + P2，2026-10-19）：自研插件的**推荐写法**。
 *
 * 它一次演示四件事：
 *   1) `ctx.react` + `ctx.render` —— 用宿主 React 写组件，不用手搓 DOM；
 *   2) `inject: ['storage']` + `ctx.storage.define` —— 声明式依赖，数据落本机（按插件隔离）；
 *   3) `ctx.slots.register('right.panel', …)` —— 往宿主右侧面板注册内容；
 *   4) `ctx.effect(() => () => …)` —— Cordis 语义：立即执行，返回值是清理函数。
 *
 * ⚠️ 与 `container.appendChild` 二选一：用了 ctx.render 之后 React 接管容器。
 */
window.__apPlugin__.register('react_widget', {
  inject: ['storage'],

  apply(ctx) {
    const h = ctx.react.createElement;

    // 数据持久化：折叠状态跨刷新保留（键名由宿主自动加上 `ap.plugin.react_widget.` 前缀）
    const collapsedStore = ctx.storage.define({ key: 'collapsed', default: false });

    let ticks = 0;
    let timer = null;

    const Widget = (props) =>
      h(
        'div',
        {
          className: 'ap-react-widget',
          role: 'button',
          title: '点击折叠/展开（状态会保存）',
          onClick: () => {
            collapsedStore.set(!collapsedStore.get());
            draw();
          },
        },
        collapsedStore.get() ? 'React 挂件（已折叠）' : `React 挂件 · ${props.tick}s`,
      );

    const draw = () => {
      ctx.render(h(Widget, { tick: ticks }));
    };

    draw();
    timer = setInterval(() => {
      ticks += 1;
      draw();
    }, 1000);

    // 右侧面板：宿主给区域，插件只给元素
    const offPanel = ctx.slots.register(
      'right.panel',
      h(
        'div',
        {
          style: {
            pointerEvents: 'auto',
            margin: '12px 12px 0 0',
            padding: 12,
            background: '#fff',
            borderRadius: 12,
            boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)',
            fontSize: 12,
            lineHeight: 1.6,
          },
        },
        h('div', { style: { fontWeight: 600, marginBottom: 4 } }, '右侧面板（插件注册）'),
        h('div', { style: { color: '#888' } }, `来自 react_widget · ${new Date().toLocaleTimeString()}`),
      ),
      10,
    );

    // Cordis 语义：fn 立即执行，返回值是 disposer（与上面的 return 一起，宿主都会调用）
    ctx.effect(() => () => clearInterval(timer), 'ticker');

    return () => {
      offPanel();
    };
  },
});
