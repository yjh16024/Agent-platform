/*
 * 本机能力示例的前端（2026-10-19，P4）。
 *
 * 四个按钮分别验证：
 *   ① 系统信息 → ctx.host.invoke('info')      —— 证明 main.js 被主进程加载、IPC 通
 *   ② 执行命令 → ctx.host.exec.run(...)        —— **首次会弹授权**（允许后记住）
 *   ③ 读目录   → ctx.host.fs.list(...)         —— 任意路径（能力全开）
 *   ④ 私有存储 → ctx.host.storage.set/get      —— 插件自己的 KV
 *
 * 浏览器里跑（npm run dev 开网页）时 ctx.host.available=false，会明确提示"需要桌面版"。
 */
window.__apPlugin__.register('node_demo', {
  apply(ctx) {
    const h = ctx.react.createElement;
    const ui = { lines: ['点下面的按钮试试：'], busy: false };

    const log = (s) => {
      ui.lines = [s, ...ui.lines].slice(0, 8);
      draw();
    };

    const wrap = async (label, fn) => {
      ui.busy = true;
      draw();
      try {
        const r = await fn();
        log(`${label}：${typeof r === 'string' ? r : JSON.stringify(r)}`);
      } catch (e) {
        log(`${label} 失败：${e.message}`);
      } finally {
        ui.busy = false;
        draw();
      }
    };

    const Panel = () =>
      h(
        'div',
        { className: 'ap-node-demo' },
        h('div', { className: 'ap-node-demo-title' }, '本机能力（Host 半侧）'),
        h(
          'div',
          { className: 'ap-node-demo-hint' },
          ctx.host.available ? '桌面版 · 首次使用会弹一次授权' : '当前环境没有本机能力（需要桌面版）',
        ),
        h(
          'div',
          { className: 'ap-node-demo-row' },
          h('button', { disabled: ui.busy, onClick: () => void wrap('系统信息', () => ctx.host.invoke('info')) }, '系统信息'),
          h('button', { disabled: ui.busy, onClick: () => void wrap('执行 echo', () => ctx.host.exec.run({ cmd: 'echo', args: ['hello from plugin'] })) }, '执行 echo'),
          h('button', { disabled: ui.busy, onClick: () => void wrap('读 C:\\', () => ctx.host.fs.list('C:\\')) }, '读 C:\\'),
          h('button', { disabled: ui.busy, onClick: () => void wrap('私有存储', async () => {
            const n = (await ctx.host.storage.get('clicks')) || 0;
            await ctx.host.storage.set('clicks', n + 1);
            return `第 ${n + 1} 次（刷新后仍保留）`;
          }) }, '私有存储'),
          h('button', { disabled: ui.busy, onClick: () => void wrap('调平台接口', () => ctx.host.invoke('platformProbe')) }, '调平台接口'),
        ),
        h(
          'div',
          { className: 'ap-node-demo-log' },
          ui.lines.map((line, i) => h('div', { key: i }, line)),
        ),
      );

    const draw = () => ctx.render(h(Panel));
    draw();
  },
});
