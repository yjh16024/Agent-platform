/*
 * 「本轮消耗」插件（2026-10-19，v1.1 起配置化）。
 *
 * 每轮回复结束时，在输入框上方弹一个小卡片：本轮 tokens（分入/出）+ 预估花费，
 * 默认 4 秒后淡出；下一轮开始（`message.sent`）时立刻收掉。
 *
 * 设计取舍：
 *   · **零平台接口**：数据来自宿主派发的 `run.completed` 事件（后端随结束帧下发 usage），
 *     插件只做展示，不请求任何 REST。
 *   · **配置由 manifest 声明**（见 manifest.json 的 `config`）：宿主渲染「设置」表单并存储，
 *     这里只管用 `ctx.config.get()` 读值 —— 插件不需要自己写设置界面。
 *   · **价格在插件里算**：平台没有价目表（也不该有 —— 价格随服务商变）。
 *   · **拿不到 usage 就不弹**：部分网关流式不返回用量，"不知道"不等于"0 消耗"。
 */

window.__apPlugin__.register('token_usage', {
  apply(ctx) {
    const box = document.createElement('div');
    box.className = 'ap-token-meter';
    ctx.container.appendChild(box);

    let hideTimer = null;

    /** 每次弹都重新读配置：用户在「设置」里改完，下一次就生效（不需要刷新/重装）。 */
    const readCfg = () => {
      const c = ctx.config.get();
      return {
        inputPerM: Number(c.inputPerM ?? 2),
        outputPerM: Number(c.outputPerM ?? 8),
        currency: typeof c.currency === 'string' && c.currency ? c.currency : '¥',
        showCost: c.showCost !== false,
        showBreakdown: c.showBreakdown !== false,
        showMs: Math.max(1000, Number(c.showMs ?? 4000)),
      };
    };

    const fmt = (n) => Number(n || 0).toLocaleString('zh-CN');
    const money = (n) => (n > 0 && n < 0.0001 ? '<0.0001' : n.toFixed(4));

    const hide = () => {
      if (hideTimer) {
        clearTimeout(hideTimer);
        hideTimer = null;
      }
      box.classList.remove('ap-show');
    };

    const show = (usage) => {
      const prompt = Number(usage.promptTokens || 0);
      const completion = Number(usage.completionTokens || 0);
      const total = Number(usage.totalTokens || prompt + completion);
      if (total <= 0) {
        return; // 没有用量数据就不弹
      }

      const cfg = readCfg();
      const cost = (prompt * cfg.inputPerM + completion * cfg.outputPerM) / 1e6;

      box.innerHTML = `
        <div class="ap-token-meter-title">本轮消耗</div>
        <div class="ap-token-meter-main">
          <span class="ap-token-meter-num">${fmt(total)}</span>
          <span class="ap-token-meter-unit">tokens</span>
        </div>
        ${cfg.showBreakdown ? `<div class="ap-token-meter-sub">入 ${fmt(prompt)} · 出 ${fmt(completion)}</div>` : ''}
        ${cfg.showCost && cost > 0 ? `<div class="ap-token-meter-cost">≈ ${cfg.currency}${money(cost)}</div>` : ''}
      `;

      box.classList.add('ap-show');
      if (hideTimer) {
        clearTimeout(hideTimer);
      }
      hideTimer = setTimeout(hide, cfg.showMs);
    };

    // 一轮结束 → 弹一会儿
    ctx.on('run.completed', (payload) => {
      if (payload && payload.usage) {
        show(payload.usage);
      }
    });

    // 下一轮开始 → 立刻消失（用户要求：在下轮对话出现前消失）
    ctx.on('message.sent', () => hide());

    // 配置变更：当前弹窗按新配置重画（若正好在展示中）
    ctx.config.onChange(() => {
      if (box.classList.contains('ap-show')) {
        hide();
      }
    });

    // 清理：插件被停用/卸载时移除节点与定时器
    ctx.effect(() => () => {
      if (hideTimer) {
        clearTimeout(hideTimer);
      }
      box.remove();
    });
  },
});
