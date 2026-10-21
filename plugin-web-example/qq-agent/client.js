/*
 * QQ 助手 · 前端面板（2026-10-20，P1）
 *
 * 两件事：**看状态** 和 **开关总闸**；外加一个**可拖动的悬浮球**（与消息通知球手感一致）。
 * 复杂配置（白名单 / 人格 / 节奏）走卡片上的「设置」表单（平台按 manifest.config 渲染）。
 */
window.__apPlugin__.register('qq_agent', {
  apply(ctx) {
    const h = ctx.react.createElement;
    const ui = { open: false, st: null, busy: false, toolsResult: '' };

    /** 球尺寸（与 CSS 一致，吸附计算依赖它）。 */
    const BALL_W = 62;
    const BALL_H = 34;
    /** 距边小于它 → 吸附半隐藏（与通知球同一套参数）。 */
    const EDGE = 30;
    /** 位移超过它才算拖动，否则算点击。 */
    const DRAG_THRESHOLD = 4;
    const POS_KEY = 'ap.qq.ball';

    let alive = true;
    /** null = 用默认位置（右下偏上，避开通话球）。 */
    let pos = loadPos();
    /** 拖动中：{ startX, startY, originX, originY, moved } */
    let drag = null;

    function defaultPos() {
      return {
        x: window.innerWidth - BALL_W - 24,
        // 默认放在通知球（右下角）上方，避免两个球叠在一起
        y: window.innerHeight - BALL_H - 130,
      };
    }

    function loadPos() {
      try {
        const raw = localStorage.getItem(POS_KEY);
        if (!raw) return null;
        const p = JSON.parse(raw);
        if (typeof p?.x !== 'number' || typeof p?.y !== 'number') return null;
        return p;
      } catch {
        return null;
      }
    }

    function savePos(p) {
      try {
        localStorage.setItem(POS_KEY, JSON.stringify(p));
      } catch {
        // 隐私模式写不进去：位置只在本次会话有效，不影响功能
      }
    }

    /**
     * 边界吸附：靠边就半隐藏（让一半移出屏幕）。
     *
     * 贴边只是"靠边放"，仍占着整块区域；半隐藏后只剩一个可点的把手，
     * 视觉上真正让位给内容 —— 与通知球一致。
     */
    function snap(p) {
      const halfW = BALL_W / 2;
      const halfH = BALL_H / 2;
      let { x, y } = p;
      if (x < EDGE) {
        x = -halfW;
      } else if (x > window.innerWidth - BALL_W - EDGE) {
        x = window.innerWidth - BALL_W + halfW;
      }
      if (y < EDGE) {
        y = -halfH;
      } else if (y > window.innerHeight - BALL_H - EDGE) {
        y = window.innerHeight - BALL_H + halfH;
      }
      return { x, y };
    }

    /** 窗口尺寸变化后把球收回可视区（否则缩小窗口会让它"消失在屏幕外"）。 */
    function clampIntoView(p) {
      return {
        x: Math.min(Math.max(p.x, -BALL_W / 2), window.innerWidth - BALL_W / 2),
        y: Math.min(Math.max(p.y, 0), window.innerHeight - BALL_H),
      };
    }

    const draw = () => {
      if (!alive) return;
      ctx.render(h(View));
    };

    const refresh = async () => {
      try {
        ui.st = await ctx.host.invoke('status');
      } catch (e) {
        ui.st = { connected: false, lastError: e.message };
      }
      draw();
    };

    /**
     * 测试「插件侧工具通道」（2026-10-21）。
     *
     * <p>与"模型自己调工具"是两条路：这条由插件代码主动发起（`api.tools.execute`），
     * 用来确认 Node 侧能直接调平台工具、以及当前用户能看到哪些工具。</p>
     */
    const testTools = async () => {
      ui.busy = true;
      ui.toolsResult = '测试中…';
      draw();
      try {
        const r = await ctx.host.invoke('testTools');
        if (r && r.ok) {
          const out = JSON.stringify((r.result && r.result.output) || r.result || '');
          ui.toolsResult = `✅ ${r.tool} 成功：${out.slice(0, 100)}`;
        } else {
          const parts = [`❌ ${(r && r.error) || '未知错误'}`];
          if (r && r.hint) {
            parts.push(`💡 ${r.hint}`);
          }
          if (r && Array.isArray(r.tools) && r.tools.length) {
            parts.push(`可用工具：${r.tools.slice(0, 10).join(', ')}`);
          }
          ui.toolsResult = parts.join('　');
        }
      } catch (e) {
        ui.toolsResult = `❌ ${e.message}`;
      } finally {
        ui.busy = false;
        draw();
      }
    };

    const toggle = async () => {
      ui.busy = true;
      draw();
      try {
        await ctx.host.invoke('saveConfig', { enabled: !(ui.st && ui.st.enabled) });
        await refresh();
      } catch (e) {
        ui.st = { ...(ui.st || {}), lastError: e.message };
      } finally {
        ui.busy = false;
        draw();
      }
    };

    // ---------------------------------------------------------------- 拖动

    function onPointerDown(e) {
      if (e.button !== undefined && e.button !== 0) return;
      const cur = pos || defaultPos();
      drag = {
        startX: e.clientX,
        startY: e.clientY,
        originX: cur.x,
        originY: cur.y,
        moved: false,
      };
      pos = cur;
      try {
        e.currentTarget.setPointerCapture(e.pointerId);
      } catch {
        // 老浏览器没有指针捕获：退化为普通 move 监听，仍能拖
      }
      draw();
    }

    function onPointerMove(e) {
      if (!drag) return;
      const dx = e.clientX - drag.startX;
      const dy = e.clientY - drag.startY;
      if (!drag.moved && Math.abs(dx) + Math.abs(dy) < DRAG_THRESHOLD) {
        return; // 手抖：还当点击处理
      }
      drag.moved = true;
      pos = clampIntoView({ x: drag.originX + dx, y: drag.originY + dy });
      draw();
    }

    function onPointerUp(e) {
      if (!drag) return;
      const moved = drag.moved;
      drag = null;
      try {
        e.currentTarget.releasePointerCapture(e.pointerId);
      } catch {
        // 同上
      }
      if (moved) {
        pos = snap(pos || defaultPos());
        savePos(pos);
      } else {
        ui.open = !ui.open;
        if (ui.open) void refresh();
      }
      draw();
    }

    // ---------------------------------------------------------------- 视图

    const Ball = () => {
      const on = !!(ui.st && ui.st.connected);
      const p = pos || defaultPos();
      return h(
        'div',
        {
          className: 'ap-qq-ball' + (on ? ' on' : '') + (drag ? ' dragging' : ''),
          style: { left: p.x + 'px', top: p.y + 'px' },
          title: on
            ? `已连接 ${ui.st.nickname || ui.st.uin}（可拖动）`
            : '未连接 NapCat（可拖动）',
          onPointerDown,
          onPointerMove,
          onPointerUp,
          onPointerCancel: onPointerUp,
        },
        h('span', { className: 'ap-qq-dot' }),
        h('span', { className: 'ap-qq-label' }, 'QQ'),
      );
    };

    const Row = (k, v) => h('div', { className: 'ap-qq-row' }, h('span', {}, k), h('b', {}, String(v)));

    const Panel = () => {
      const st = ui.st || {};
      const p = pos || defaultPos();
      // 球在下半屏时面板向上弹，否则会被窗口底部裁掉
      const up = p.y > window.innerHeight / 2;
      const style = {
        left: Math.min(Math.max(p.x - 90, 8), window.innerWidth - 260) + 'px',
        top: up ? undefined : p.y + BALL_H + 8 + 'px',
        bottom: up ? window.innerHeight - p.y + 8 + 'px' : undefined,
      };
      return h(
        'div',
        { className: 'ap-qq-panel', style },
        h('div', { className: 'ap-qq-title' }, 'QQ 助手'),
        Row('状态', st.connected ? '已连接' : '未连接'),
        st.uin ? Row('账号', `${st.nickname || ''} ${st.uin}`) : null,
        Row('收到 / 回复', `${st.received || 0} / ${st.replied || 0}`),
        Row('跳过', `${st.skipped || 0}${st.lastSkipReason ? '（' + st.lastSkipReason + '）' : ''}`),
        st.lastError ? h('div', { className: 'ap-qq-err' }, st.lastError) : null,
        h(
          'div',
          { className: 'ap-qq-actions' },
          h(
            'button',
            { disabled: ui.busy, onClick: () => void toggle() },
            st.enabled ? '停用' : '启用',
          ),
          h('button', { disabled: ui.busy, onClick: () => void refresh() }, '刷新'),
          h('button', { disabled: ui.busy, onClick: () => void testTools() }, '测试联网'),
        ),
        ui.toolsResult ? h('div', { className: 'ap-qq-hint' }, ui.toolsResult) : null,
        h(
          'div',
          { className: 'ap-qq-hint' },
          '详细配置在插件卡片的「设置」里',
          h('br'),
          st.configPath || '（首次启用后生成 config.json）',
        ),
      );
    };

    function View() {
      return h('div', null, h(Ball), ui.open ? h(Panel) : null);
    }

    draw();
    void refresh();

    const timer = setInterval(() => {
      if (ui.open) void refresh();
    }, 5000);

    const onResize = () => {
      if (pos) {
        pos = clampIntoView(pos);
        draw();
      }
    };
    window.addEventListener('resize', onResize);

    ctx.effect(() => () => {
      alive = false;
      clearInterval(timer);
      window.removeEventListener('resize', onResize);
    });
  },
});
