import * as React from 'react';
import type { ReactNode } from 'react';
import { getTenantId, getToken } from '../api/http';
import { invokeTool } from '../api/tools';
import { registerPluginNav, registerPluginSlot } from './host/pluginSlots';
import { renderReact } from './host/reactBridge';
import { SERVICE_NAMES, createServices } from './host/services';
import type {
  PluginConfigApi,
  PluginHttp,
  PluginLogger,
  PluginSessionService,
  PluginStorage,
} from './host/services';
import { dshHost, installDshShim, setCurrentDshPlugin } from './host/dshShim';
import { transpileDshEsm } from './host/transpile';
import { createHostApi, type PluginHostApi } from './host/hostClient';
import { currentLang, registerMessages, translate } from './host/i18n';

/** 供槽位组件在调用 `apply` 期间设置"当前插件"（DSH 的注册类 API 需要它归属）。 */
export { setCurrentDshPlugin };

/**
 * 插件前端脚本加载器（2026-10-19，D 档「可执行界面」）。
 *
 * <h3>插件脚本长什么样</h3>
 * 一个普通的 IIFE / UMD 产物，执行时**自注册**：
 * <pre>
 *   window.__apPlugin__.register('my_plugin', {
 *     apply(ctx) {
 *       ctx.container.appendChild(el);        // 自己那块容器
 *       const off = ctx.on('agent.changed', (id) => {...});
 *       return () => { off(); el.remove(); };  // disposer（宿主卸载时调用）
 *     }
 *   });
 * </pre>
 *
 * <h3>★ 生命周期与引用计数</h3>
 * 同一插件可能同时被**多个槽位**使用（例如一个挂在覆盖层、一个挂在输入框旁）。
 * 每个挂载点都会调 {@link loadPluginScript}，所以这里按 {@code pluginId} 计数：
 * 引用减到 0 才移除 `<script>` 并清掉模块 —— 否则"一个槽位卸载把另一个槽位的插件也弄停"
 * 这种竞态只会偶发出现，极难复现。
 *
 * <h3>为什么样式不在这里加载</h3>
 * 隔离模式（{@code isolate=true}）下样式必须进 Shadow DOM，否则进 {@code document.head} ——
 * 这两条路径由挂载组件决定（它拥有容器），加载器只认"脚本"这一件事。
 */
export interface PluginScriptModule {
  /** 插件入口；返回值是 disposer（函数或带 dispose() 的对象）。 */
  apply?: (ctx: PluginHostContext) => unknown;
  name?: string;
  /**
   * 声明的宿主服务（Cordis 语义，2026-10-19 P2）。
   *
   * <p>宿主只提供 `storage` / `http` / `session` / `logger` 四个（见 {@code host/services.ts}）。
   * 出现其它名字时**加载会失败并明确报出缺哪个** —— 这是与 DSH 的关键差异：
   * DSH 对未知依赖返回 `{}` 兜底，插件"装上了但功能静默失效"，排查成本极高。</p>
   */
  inject?: string[];
}

/** 宿主目前会派发的事件名（2026-10-19：加了会话生命周期的三个）。 */
export type PluginEventName =
  | 'agent.changed'
  | 'theme.changed'
  | 'session.started'
  | 'session.disposed'
  | 'message.sent'
  /** 一轮回复结束（2026-10-19）：payload = `{ agentId, sessionId, usage:{promptTokens,completionTokens,totalTokens,costUsd?} }`。 */
  | 'run.completed';

/** 传给插件 {@code apply(ctx)} 的宿主上下文。 */
export interface PluginHostContext {
  /** 插件 id（多个插件共存时用于区分自己）。 */
  pluginId: string;
  /** 宿主给插件的那块容器；隔离模式下它是 Shadow Root 里的 div。 */
  container: HTMLElement;
  /** 当前对话的智能体（可能为空）。 */
  agentId?: string;
  /** 调平台工具 —— 插件前端**唯一**的后端能力入口（走五环责任链）。 */
  invokeTool: (name: string, args?: Record<string, unknown>) => Promise<Record<string, unknown>>;
  /**
   * 订阅宿主事件：
   * - {@code agent.changed}：切换智能体（payload = agentId）；
   * - {@code theme.changed}：换肤 / 明暗切换（payload = 主题名）；
   * - {@code session.started}：开始一段新会话（payload = `{ agentId, sessionId }`）；
   * - {@code session.disposed}：会话结束 / 被切换走（payload 同上）；
   * - {@code message.sent}：用户发出了一条消息（payload = `{ agentId, sessionId, role, content, at }`）。
   *
   * <p>后三个是 2026-10-19（P0）新增的：没有它们，插件做不了"每轮摄取 / 会话小结"这类事，
   * 只能等 LLM 主动调工具。</p>
   *
   * <p>认不出的事件名返回一个空退订函数（不报错）。</p>
   */
  on: (event: PluginEventName | (string & {}), fn: (payload?: unknown) => void) => () => void;
  /**
   * 按选择器取宿主的元素（**仅 {@code isolate=false} 时可用**）。
   * 隔离模式下返回 null —— 插件应据此降级，而不是以为"页面里没有这个元素"。
   */
  mountTo: (selector: string) => HTMLElement | null;

  // ---- P1（2026-10-19）：React 渲染桥 ----

  /**
   * 宿主 React 实例。插件**不需要带 React**，直接 `ctx.react.createElement` 即可写组件 ——
   * 这一条同时也是"DSH 插件那套 React 组件"能被渲染的基础（见 P3 的 shim）。
   */
  react: typeof React;

  /**
   * 用 React 渲染到当前容器；可重复调用（等于更新）；返回卸载函数。
   *
   * <p>⚠️ 与 {@code container.appendChild} **二选一**：React 会接管容器内容，
   * 混用的结果是手写的 DOM 被清掉（看起来像"我的界面不显示"）。</p>
   */
  render: (element: ReactNode) => () => void;

  /**
   * 往宿主指定区域注册元素（如 {@code right.panel} / {@code global.overlay}）；返回注销函数。
   *
   * <p>与 `render` 的区别：那个渲染在**插件自己的容器**里，这个渲染在**宿主提供的区域**里 ——
   * 后者不需要插件自己算位置，也不受 shadow DOM 隔离影响（元素由宿主挂在区域容器里）。
   * 插件卸载时必须调用返回的注销函数（否则元素会留在页面上）。</p>
   */
  slots: {
    register: (slot: string, element: ReactNode, order?: number) => () => void;
  };

  /**
   * 往侧栏注册一个**一级入口**（2026-10-19，P1）：菜单里多一项，点开后主内容区整块渲染 `element`。
   *
   * <p>这是"插件有自己的大页面"的入口（dsh-engram 那类九视图面板就靠它）。
   * 与 `ctx.slots.register` 的区别：那个塞进已有区域，这个新增入口 + 独立视图。</p>
   */
  nav: {
    register: (opts: { label: string; element: ReactNode }) => () => void;
  };

  // ---- P2（2026-10-19）：宿主服务 ----

  /**
   * 插件**自身配置**（2026-10-19 补）：manifest 里声明的字段 + 用户在「设置」里的值。
   *
   * <p>与 `storage` 的分工：那个是自由抽屉（插件想存啥存啥），这个是**声明过的字段** ——
   * 宿主据此渲染设置表单（`PluginConfigModal`），插件只负责读值。
   * `get()` 返回"默认值 + 已保存值"的合并结果，插件不需要自己处理缺省。</p>
   */
  config: PluginConfigApi;

  /** 本机持久化（localStorage，键名按插件隔离）。 */
  storage: PluginStorage;
  /**
   * 带鉴权的平台 HTTP。
   * ⚠️ **只允许 `/api/` 相对路径** —— 带 token 的请求不允许发往外部域名（服务层硬拦）。
   */
  http: PluginHttp;
  /** 会话读取：当前会话 / 列表 / 详情。 */
  session: PluginSessionService;
  /** 带插件前缀的日志。 */
  logger: PluginLogger;
  /**
   * Cordis 语义：**立即执行** `fn`，把它的返回值当清理函数登记；插件卸载时**逆序**执行。
   *
   * <p>注意是"fn 立即执行、返回值是 disposer"，不是"把 fn 存起来等卸载再调"——
   * 这两种写法在 Cordis 里都存在过，搞错的表现是"注册逻辑从来没跑过"。</p>
   */
  effect: (fn: () => void | (() => void), label?: string) => void;

  // ---- P4（2026-10-19）：本机能力（Host 半侧）----

  /**
   * 本机能力：`fs` / `http` / `exec` / `storage` + 调插件自己 `main.js` 的方法。
   *
   * <p><b>只有桌面版可用</b>（浏览器里 `available=false`，调用会明确报错）。
   * 首次使用某类能力会弹一次授权确认，允许后记住 —— 见 {@code plugins/host/hostClient.ts}。</p>
   *
   * <p>与 {@code ctx.http} 的区别：那个打的是**本平台**接口（带登录态、只允许 `/api/`）；
   * 这个是**任意出站**（由主进程发起，没有 CORS 限制）。</p>
   */
  host: PluginHostApi;

  /**
   * i18n（2026-10-19，P3）：宿主语言 + 插件自己的词典 + 带回落的取文案。
   *
   * <p>宿主没有完整的 i18n 体系，所以这里只给"插件真正需要的三件事"，
   * 不假装是一套国际化框架（详见 `host/i18n.ts` 的说明）。</p>
   */
  i18n: {
    /** 当前语言：中文环境 `zh`，其余 `en`。 */
    lang: () => 'zh' | 'en';
    /** 注册词典：`{ zh: { hello: '你好' }, en: { hello: 'Hi' } }`（多次注册合并）。 */
    register: (messages: Record<string, Record<string, string>>) => void;
    /** 取文案，回落顺序：当前语言 → en → zh → fallback → key。 */
    t: (key: string, fallback?: string) => string;
  };
}

interface LoadedEntry {
  module: PluginScriptModule;
  refs: number;
  /** 自注册路径的 `<script>`；DSH 转译路径执行完即移除，所以是可选的。 */
  scriptEl?: HTMLScriptElement;
}

/** pluginId → 已执行过的模块（引用计数见 {@link LoadedEntry}）。 */
const loaded = new Map<string, LoadedEntry>();
/** 正在加载中的插件（并发挂载时共用同一个 Promise，避免重复注入脚本）。 */
const pending = new Map<string, Promise<PluginScriptModule>>();
/** 脚本自注册的模块（未挂载也可能已注册）。 */
const modules = new Map<string, PluginScriptModule>();
/** 宿主事件订阅。 */
const listeners = new Map<string, Set<(payload?: unknown) => void>>();

let installed = false;

/**
 * 安装全局注册器 `window.__apPlugin__`（幂等）。
 *
 * <p>必须**在注入任何插件脚本之前**执行 —— 所以由本模块在导入时调用一次，
 * 而本模块又被 {@code SlotOutlet} 静态导入（早于任何插件脚本）。</p>
 */
/** 转译路径"注册失败"的原因（比笼统的"没注册"更有信息量）。 */
const dshErrors = new Map<string, string>();

function installRegistry(): void {
  if (installed) {
    return;
  }
  installed = true;
  (window as unknown as { __apPlugin__?: unknown }).__apPlugin__ = {
    register(pluginId: string, module: PluginScriptModule) {
      if (!pluginId || !module) {
        return;
      }
      modules.set(pluginId, module);
    },
  };

  /*
   * DSH 兼容层（P3）：转译后的代码调 `__apHost.registerDsh(id, exports)`。
   * 这里翻译成我们的模块形态（`{ name, inject, apply }`）；失败原因存进 dshErrors，
   * 后续报错才能区分"没导出 apply"与"执行就抛错"。
   */
  installDshShim();
  const host = dshHost();
  if (host) {
    host.registerDsh = (pluginId, exported) => {
      try {
        modules.set(pluginId, toModule(exported));
      } catch (e) {
        dshErrors.set(pluginId, (e as Error).message);
      }
    };
  }
}

/** DSH 插件导出的对象 → 我们的模块形态。 */
function toModule(exported: unknown): PluginScriptModule {
  if (typeof exported === 'function') {
    return { apply: exported as PluginScriptModule['apply'] };
  }
  if (exported && typeof exported === 'object') {
    const obj = exported as Record<string, unknown>;
    // `export default {…}` 转译后已直接是对象；`{ default: {…} }` 也兼容
    const target =
      obj.default && typeof obj.default === 'object'
        ? (obj.default as Record<string, unknown>)
        : obj;
    if (typeof target.apply === 'function') {
      return {
        name: typeof target.name === 'string' ? target.name : undefined,
        inject: Array.isArray(target.inject) ? (target.inject as string[]) : undefined,
        apply: target.apply as PluginScriptModule['apply'],
      };
    }
  }
  throw new Error('DSH 插件没有导出 { apply }（需 `export default { name, inject, apply }`）');
}

installRegistry();

/**
 * 插件资源的 URL。
 *
 * <p>两类插件的端点不同，靠贡献里的 {@code assetBase} 区分（**不能用猜的**）：
 * jar 插件在 {@code /plugins/{id}/web/}，界面插件（本机目录，无需 jar）在
 * {@code /plugin-web/{id}/file/}。少了这个区分，其中一类会 404，
 * 而表现只是"界面没出现"——最难查的一种。</p>
 */
export function pluginAssetUrl(pluginId: string, path: string, assetBase?: string): string {
  const rel = path.replace(/^web\//, '');
  const encoded = rel
    .split('/')
    .filter(Boolean)
    .map((seg) => encodeURIComponent(seg))
    .join('/');
  if (assetBase === 'plugin-web') {
    return `/api/v1/plugin-web/${encodeURIComponent(pluginId)}/file/${encoded}`;
  }
  return `/api/v1/plugins/${encodeURIComponent(pluginId)}/web/${encoded}`;
}

/**
 * 取插件资源文本（**必须带 token**）。
 *
 * <h3>★ 为什么不能用 {@code <script src>} / {@code <link href>} 直接加载</h3>
 * 浏览器对 `src`/`href` 发起的子资源请求**不会带 `Authorization` 头** —— 而我们所有
 * `/api/**` 都要 Bearer 认证，于是资源全部 401。表现就是"插件装上了、界面一片空白"，
 * 而 F12 里只有一行 `Failed to load resource: 401`（2026-10-19 实测踩到）。
 * 所以统一走"带 token 的 fetch → Blob → 执行"。
 *
 * <p>⚠️ 代价：CSS 里的**相对路径**（{@code url('./bg.png')}）会失效 —— 相对的是文档而不是插件目录。
 * 插件要引图片请用绝对路径 {@code /api/v1/plugin-web/<id>/file/<path>}。</p>
 */
export async function fetchPluginAssetText(url: string): Promise<string> {
  const token = getToken();
  const res = await fetch(url, {
    headers: {
      'X-Tenant-Id': getTenantId(),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
  if (!res.ok) {
    throw new Error(`资源请求失败（HTTP ${res.status}）`);
  }
  return res.text();
}

/** 用 Blob 执行一段脚本（绕过"子资源不带 Authorization"的限制）。 */
async function execBlob(code: string, pluginId: string, mark?: string): Promise<void> {
  const blobUrl = URL.createObjectURL(new Blob([code], { type: 'text/javascript' }));
  try {
    await new Promise<void>((resolve, reject) => {
      const el = document.createElement('script');
      el.src = blobUrl;
      el.async = true;
      el.dataset.apPlugin = pluginId;
      if (mark) {
        el.dataset[mark] = '1';
      }
      const timer = window.setTimeout(() => {
        el.remove();
        reject(new Error('脚本执行超时（10s）'));
      }, 10_000);
      el.onload = () => {
        window.clearTimeout(timer);
        el.remove();   // 代码已执行，元素不必留在 head 里
        resolve();
      };
      el.onerror = () => {
        window.clearTimeout(timer);
        el.remove();
        reject(new Error('脚本执行失败（语法或运行时错误，详见控制台）'));
      };
      document.head.appendChild(el);
    });
  } finally {
    URL.revokeObjectURL(blobUrl);
  }
}

/**
 * 加载并执行插件脚本（引用计数 +1）。
 *
 * <p>流程：**带 token 取源码** → 以 Blob 原样执行（自注册 IIFE 走这条）→
 * 若没注册再按 DSH 形态转译后执行。取源码放在最前，是因为子资源请求无法携带认证头，
 * 只有 `fetch` 能带 —— 两种形态都省不掉这一步。</p>
 *
 * @returns module 为空时 `error` 一定有值（401 / 没自注册 / 转译越界 / 超时）
 */
export async function loadPluginScript(opts: {
  pluginId: string;
  script: string;
  assetBase?: string;
}): Promise<{ module: PluginScriptModule | null; error?: string }> {
  const existing = loaded.get(opts.pluginId);
  if (existing) {
    existing.refs += 1;
    return { module: existing.module };
  }

  const inflight = pending.get(opts.pluginId);
  if (inflight) {
    try {
      const module = await inflight;
      const entry = loaded.get(opts.pluginId);
      if (entry) {
        entry.refs += 1;
      }
      return { module };
    } catch (e) {
      return { module: null, error: (e as Error).message };
    }
  }

  const url = pluginAssetUrl(opts.pluginId, opts.script, opts.assetBase);
  const loading = (async (): Promise<PluginScriptModule> => {
    // 取源码失败就没有第二条路可走（401 / 404 / 网络）
    const source = await fetchPluginAssetText(url);

    const errors: string[] = [];

    // ① 原样执行：自注册 IIFE（自研插件的主路径）
    try {
      await execBlob(source, opts.pluginId);
      const registered = modules.get(opts.pluginId);
      if (registered) {
        loaded.set(opts.pluginId, { module: registered, refs: 1 });
        return registered;
      }
      errors.push('脚本执行后没有调用 __apPlugin__.register');
    } catch (e) {
      errors.push((e as Error).message);
    }

    /*
     * ② DSH 形态（P3）：ESM 的 `import/export` 在经典脚本里是语法错误，整段不执行 ——
     * 所以第一遍失败不会留下副作用，这里转译后再执行是安全的。
     */
    try {
      const { code } = transpileDshEsm(source, opts.pluginId);
      await execBlob(code, opts.pluginId, 'apTranspiled');
      const dsh = modules.get(opts.pluginId);
      if (!dsh) {
        throw new Error(dshErrors.get(opts.pluginId) ?? '执行后没有注册（`export default` 里没有 apply？）');
      }
      loaded.set(opts.pluginId, { module: dsh, refs: 1 });
      return dsh;
    } catch (e) {
      errors.push(`DSH 兼容路径：${(e as Error).message}`);
    }

    throw new Error(errors.join('；'));
  })();
  pending.set(opts.pluginId, loading);

  try {
    return { module: await loading };
  } catch (e) {
    return { module: null, error: (e as Error).message };
  } finally {
    pending.delete(opts.pluginId);
  }
}

/**
 * 释放一次引用（引用计数 -1）；减到 0 才真正卸载。
 *
 * <p>⚠️ 卸载后 {@code modules} 也要删掉：下次重新挂载必须**重新执行脚本**，
 * 否则拿到的是上一轮已经 dispose 过的模块（插件内部状态还在，但容器已换）。
 */
export function releasePluginScript(pluginId: string): void {
  const entry = loaded.get(pluginId);
  if (!entry) {
    return;
  }
  entry.refs -= 1;
  if (entry.refs > 0) {
    return;
  }
  loaded.delete(pluginId);
  entry.scriptEl?.remove();
  modules.delete(pluginId);
  dshErrors.delete(pluginId);
}

/** 订阅宿主事件。 */
export function onPluginEvent(
  event: string,
  fn: (payload?: unknown) => void,
): () => void {
  const set = listeners.get(event) ?? new Set();
  set.add(fn);
  listeners.set(event, set);
  return () => {
    set.delete(fn);
  };
}

/** 派发宿主事件（由宿主在环境变化时调用）。 */
export function emitPluginEvent(event: string, payload?: unknown): void {
  const set = listeners.get(event);
  if (!set) {
    return;
  }
  for (const fn of [...set]) {
    try {
      fn(payload);
    } catch (e) {
      // 订阅者自己的错误不能打断其它插件，更不能冒泡到宿主
      console.warn(`[plugin] 事件 ${event} 的订阅者抛错：`, e);
    }
  }
}

/**
 * 构造传给插件 {@code apply(ctx)} 的上下文，并返回**统一的 dispose**。
 *
 * <p>为什么 dispose 要由宿主收口：插件登记的清理来源有三条 ——
 * `apply` 的返回值、`ctx.effect()` 的回调、`ctx.render()/ctx.slots.register()` 的注销函数。
 * 只依赖插件"记得调用每一个返回的函数"是不可靠的（漏一个就留下定时器/幽灵元素），
 * 所以这里统一登记，卸载时**逆序**执行。</p>
 */
export function createPluginContext(opts: {
  pluginId: string;
  container: HTMLElement;
  agentId?: string;
  isolate: boolean;
}): { ctx: PluginHostContext; dispose: () => void } {
  const services = createServices(opts.pluginId);
  const cleanups: Array<() => void> = [];

  const ctx: PluginHostContext = {
    pluginId: opts.pluginId,
    container: opts.container,
    agentId: opts.agentId,
    invokeTool: (name, args) => invokeTool(name, args ?? {}),
    on: (event, fn) => onPluginEvent(event, fn),
    mountTo: (selector) => (opts.isolate ? null : document.querySelector<HTMLElement>(selector)),

    react: React,
    render: (element) => {
      const unmount = renderReact(opts.container, element);
      cleanups.push(unmount);
      return unmount;
    },
    slots: {
      register: (slot, element, order) => {
        const off = registerPluginSlot(slot, opts.pluginId, element, order);
        cleanups.push(off);
        return off;
      },
    },
    nav: {
      register: ({ label, element }) => {
        const off = registerPluginNav(opts.pluginId, label, element);
        cleanups.push(off);
        return off;
      },
    },

    ...services,
    host: createHostApi(opts.pluginId),
    i18n: {
      lang: currentLang,
      register: registerMessages,
      t: translate,
    },
    effect: (fn, label) => {
      try {
        const returned = fn();
        if (typeof returned === 'function') {
          cleanups.push(returned);
        }
      } catch (e) {
        // 一个 effect 抛错不该阻断插件的其它部分（与工具链路的兜底哲学一致）
        console.warn(`[plugin:${opts.pluginId}] effect(${label ?? 'no-label'}) 抛错：`, e);
      }
    },
  };

  return {
    ctx,
    dispose: () => {
      for (const fn of cleanups.reverse()) {
        try {
          fn();
        } catch (e) {
          console.warn(`[plugin:${opts.pluginId}] 清理函数抛错：`, e);
        }
      }
      cleanups.length = 0;
    },
  };
}

/**
 * 校验插件声明的 `inject` 是否都能满足。
 *
 * <p>返回**缺失的服务名**（空数组 = 全满足）。调用方应据此拒绝加载并明确告知用户
 * "该插件需要 XXX 能力，本平台未提供" —— 这比"装上但什么都不发生"好一个数量级
 * （DSH 的 `resolveHostDep` 空对象兜底正是后者）。</p>
 */
export function missingServices(inject?: string[]): string[] {
  if (!inject || inject.length === 0) {
    return [];
  }
  const provided = new Set<string>(SERVICE_NAMES);
  return inject.filter((name) => !provided.has(name));
}
