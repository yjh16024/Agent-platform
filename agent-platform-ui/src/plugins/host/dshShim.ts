import * as React from 'react';
import * as ReactDOM from 'react-dom';
import * as ReactDOMClient from 'react-dom/client';
import { registerPluginSlot } from './pluginSlots';

/**
 * DSH 形态插件的**模块兼容层**（2026-10-19，P3）。
 *
 * <h3>它补的是哪一层</h3>
 * DSH 插件的 client 入口会 `import … from '@deepseek-ai/dsh-client-runtime'` 等 6 个包。
 * 我们没有那些包 —— 这个文件提供**同名的替身模块**，并挂在 `window.__apHost.modules` 上，
 * 由 {@code transpile.ts} 改写后的代码通过 `__apHost.require(spec)` 取用。
 *
 * <h3>★ 三条刻意的取舍</h3>
 * <ol>
 *   <li><b>React 用宿主的实例</b>：DSH 插件通常 `import { React } from '…/dsh-client-runtime'`，
 *       直接返回宿主 React —— 这样**不存在双实例**（双 React 会让 hooks 抛
 *       {@code Invalid hook call}，这是兼容层最容易翻车的地方）。</li>
 *   <li><b>未实现的 API 访问时**立刻抛错**</b>（而不是返回 undefined）：DSH 自己用
 *       `resolveHostDep` 返回 `{}` 兜底，结果是"插件装上了、apply 也跑了、功能静默失效"。
 *       我们宁愿让它当场失败并说清哪个 API 没实现 —— 这是一个可查的边界，不是一个黑洞。</li>
 *   <li><b>只覆盖"常见形态"</b>：`registerTab` 这类 UI 注册 API 的字段名是按常见写法映射的，
 *       真实 DSH API 若不同，报错会指出具体名字，按需补即可（不追求一次全覆盖）。</li>
 * </ol>
 */

/** 当前正在执行 `apply` 的插件 id —— 给"不知道调用者是谁"的注册类 API 用（同步上下文内有效）。 */
let currentPluginId: string | null = null;

export function setCurrentDshPlugin(pluginId: string | null): void {
  currentPluginId = pluginId;
}

function unimplemented(name: string): never {
  throw new Error(
    `DSH 兼容层未实现：${name}（这是兼容边界，不是插件 bug；如需该能力请改用 ctx API 或提需求）`,
  );
}

/** 包一层 Proxy：**已提供的属性照常返回，未提供的一访问就抛错**。 */
function stub(moduleName: string, provided: Record<string, unknown>): unknown {
  return new Proxy(provided, {
    get(target, prop, receiver) {
      if (typeof prop === 'symbol') {
        return Reflect.get(target, prop, receiver);
      }
      if (prop in target) {
        return Reflect.get(target, prop, receiver);
      }
      return () => unimplemented(`${moduleName}.${prop}`);
    },
  });
}

function runtimeShim(): unknown {
  // Cordis 的 `define` 是类型辅助，运行时恒等；`version` 供插件做兼容判断
  const api: Record<string, unknown> = {
    React,
    define: <T,>(def: T): T => def,
    version: '1.0.0-ap',
  };
  api.default = api;   // `import runtime from '…'` 也要能拿到同一个对象
  return stub('@deepseek-ai/dsh-client-runtime', api);
}

function uiSlotsShim(): unknown {
  /**
   * 映射成"往右侧面板注册一块 React 组件"。
   *
   * <p>⚠️ DSH 真实的 `registerTab` 参数结构未知（可能是 `{ id, title, render }` 或
   * `{ slot, component }`），这里按最常见的几种字段名做兼容；认不出就抛错，
   * 让"该补哪个字段"一目了然，而不是画出一个空白面板。</p>
   */
  const registerTab = (opts: Record<string, unknown>): (() => void) => {
    const pluginId = currentPluginId ?? 'dsh_plugin';
    const component = (opts?.component ?? opts?.render) as unknown;
    if (typeof component !== 'function') {
      throw new Error('dsh-client-ui-slots.registerTab 需要 component 或 render（React 组件函数）');
    }
    const title = String(opts?.title ?? opts?.label ?? '插件面板');
    const order = Number(opts?.order) || 100;

    const element = React.createElement(
      'div',
      {
        style: {
          // 面板自带外观：宿主区域是 pointer-events:none，能点的地方由插件负责
          pointerEvents: 'auto',
          margin: '12px 12px 0 0',
          padding: 12,
          background: 'var(--ap-panel-bg, #fff)',
          color: 'inherit',
          borderRadius: 12,
          boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)',
          maxHeight: 'calc(100vh - 24px)',
          overflow: 'auto',
        },
      },
      React.createElement('div', { style: { fontWeight: 600, marginBottom: 8 } }, title),
      React.createElement(component as React.ComponentType<Record<string, never>>, {}),
    );
    return registerPluginSlot('right.panel', pluginId, element, order);
  };

  return stub('@deepseek-ai/dsh-client-ui-slots', {
    registerTab,
    // 别名：不同版本的 API 名字可能不同，先都指向同一个实现
    registerSlot: registerTab,
  });
}

export interface DshHostGlobal {
  require: (spec: string) => unknown;
  registerDsh?: (pluginId: string, exported: unknown) => void;
  modules: Map<string, unknown>;
}

/** 取 `window.__apHost`（未安装时 null）。 */
export function dshHost(): DshHostGlobal | null {
  return (window as unknown as { __apHost?: DshHostGlobal }).__apHost ?? null;
}

/**
 * 安装兼容层（幂等）—— 必须在注入任何插件脚本之前调用。
 *
 * <p>⚠️ `registerDsh` 由 {@code scriptLoader} 挂上（它拥有"插件模块表"）；
 * 本文件只负责"模块解析"这一件事，避免两个模块互相 import 形成环。</p>
 */
export function installDshShim(): void {
  const existing = dshHost();
  if (existing) {
    return;
  }
  const modules = new Map<string, unknown>();
  modules.set('react', React);
  modules.set('react-dom', ReactDOM);
  modules.set('react-dom/client', ReactDOMClient);
  modules.set('@deepseek-ai/dsh-client-runtime', runtimeShim());
  modules.set('@deepseek-ai/dsh-client-ui-slots', uiSlotsShim());
  // 这三个我们不提供能力 —— 保留空模块，让"导入了但没用到"的插件不至于直接失败；
  // 一旦真调用到属性，Proxy 会明确报"未实现"。
  modules.set('@deepseek-ai/dsh-client-connection', stub('@deepseek-ai/dsh-client-connection', {}));
  modules.set('@deepseek-ai/dsh-commands', stub('@deepseek-ai/dsh-commands', {}));
  modules.set('@deepseek-ai/dsh-credentials', stub('@deepseek-ai/dsh-credentials', {}));

  (window as unknown as { __apHost?: DshHostGlobal }).__apHost = {
    modules,
    require(spec: string) {
      if (!modules.has(spec)) {
        throw new Error(
          `插件导入了未知模块「${spec}」—— 兼容层只提供：${[...modules.keys()].join('、')}`,
        );
      }
      return modules.get(spec);
    },
  };
}
