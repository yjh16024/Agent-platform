/*
 * 界面插件示例（2026-10-19，D 档「可执行界面」）。
 *
 * 它验证四件事，缺一不可：
 *   1) 脚本被加载并自注册（__apPlugin__.register）；
 *   2) apply(ctx) 拿到容器，能自己画界面（样式在 shadow root 里，污染不到宿主）；
 *   3) 定时器持续运行（说明生命周期正常，不是"执行一次就停"）；
 *   4) 停用 / 卸载时 disposer 被调用（卡片消失、定时器停止）—— 这是"热切换"的关键，
 *      少了它，停用后插件会变成"幽灵挂件"继续跑。
 *
 * 注意：ctx.agentId 是**挂载时**的值；切换智能体不会重挂，靠 agent.changed 事件拿新值。
 */
window.__apPlugin__.register('usage_widget', {
  apply(ctx) {
    const el = document.createElement('div');
    el.className = 'ap-usage-widget';
    el.textContent = '界面插件已加载';
    ctx.container.appendChild(el);

    let ticks = 0;
    const timer = setInterval(() => {
      ticks += 1;
      const agent = ctx.agentId ? ` · ${ctx.agentId}` : '';
      el.textContent = `界面插件运行中 · ${ticks}s${agent}`;
    }, 1000);

    const off = ctx.on('agent.changed', (agentId) => {
      el.dataset.agent = String(agentId || '');
    });

    // 停用 / 卸载 / 页面切换时由宿主调用
    return () => {
      clearInterval(timer);
      off();
      el.remove();
    };
  },
});
