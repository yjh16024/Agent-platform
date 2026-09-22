/**
 * DSH 皮肤运行时（方案甲的接缝）。
 *
 * <h3>为什么是这个形状</h3>
 * 读完真实皮肤的产物后，DSH 的客户端插件加载契约是确定的：
 *
 * ```js
 * window.__ModuleLoader__.load({ id: "open-sea-skin", factory: (require) => {
 *   var module = { exports: {} };
 *   var exports = module.exports;
 *   ...
 *   exports.name   = 'open-sea-skin-client';
 *   exports.inject = [];
 *   exports.apply  = function apply(ctx) { ... ctx.effect(() => stop(), 'label') ... };
 *   return module.exports;
 * }});
 * ```
 *
 * 也就是说：**宿主提供 `load()` 与 `require()`，插件只交出 `{ name, inject, apply }`**。
 * 皮肤自己负责建 DOM、注入 CSS、监听宿主状态（`MutationObserver`）、并在 `ctx` 上登记清理函数。
 * 所以我们的工作就是**忠实地扮演这个宿主**：
 *
 * 1. 先装好 `__ModuleLoader__`（必须在皮肤脚本执行前就绪）；
 * 2. 用 `<script>` 注入皮肤 bundle，让它自注册；
 * 3. 调用它的 `apply(ctx)`，`ctx` 用 Proxy 兜底——**缺 API 时返回可调用的空对象而不是抛错**，
 *    这样皮肤不会因为少一个宿主 API 就整个崩掉（这是"尽量让它活"的关键）。
 *
 * <h3>安全</h3>
 * 注入即执行第三方代码。所以这里：
 * - 只由用户显式启用时才 `activateSkin`；
 * - 捕获加载期的全局错误，便于界面上如实回报；
 * - 提供 `deactivateSkin` 走皮肤自己登记的 disposer 做卸载。
 */
import * as React from 'react';
import * as ReactDOM from 'react-dom';
import * as ReactDOMClient from 'react-dom/client';
import { releaseCustomizationOf } from './customization';
import { fetchSkinBundle } from '../api/skins';

/** DSH 客户端插件导出的形状。 */
export interface SkinPlugin {
  name?: string;
  /** 需要宿主提供的服务名；我们绝大多数不提供，用兜底 Proxy 糊过去。 */
  inject?: string[];
  apply?: (ctx: unknown) => unknown;
}

export interface LoadedSkin {
  /**
   * **市场 id**（= 安装目录名 = `ap.skin.enabledId` = 市场条目 id）。
   * 这是平台侧的稳定主键，registry 一律用它索引。
   */
  id: string;
  /**
   * bundle 自己 `__ModuleLoader__.load({ id })` 声明的模块名，例如
   * `@dsh-external/dsh-client-ui-skin-orca-link`。
   *
   * <p>**不能拿它当主键**：它由皮肤自己写、可能随版本改，不同皮肤也可能重名。
   * 之前就是用它当主键，导致 `deactivateSkin(市场id)` 查不到记录直接返回
   * ——「停止 JS」实际没有卸载任何东西。</p>
   */
  moduleId?: string;
  plugin: SkinPlugin;
  /** 皮肤通过 ctx.effect 登记的清理函数 */
  disposers: Array<() => void>;
  /** 加载与激活期间捕获到的错误 */
  errors: string[];
  /** 皮肤自己声明的 name（支持度报告用） */
  pluginName?: string;
  /** 它向宿主 require 过的依赖（支持度报告用） */
  deps?: string[];
  /** 第一条 ctx.effect 的 label（通常就是它的 wiring id，用于确认契约对上了） */
  effectLabel?: string;
}

export interface ModuleLoaderPayload {
  id: string;
  factory: (require: (dep: string) => unknown) => SkinPlugin;
}

const loaded = new Map<string, LoadedSkin>();
/** 全局运行日志（供界面上如实展示"皮肤到底报了什么错"）。 */
const logLines: string[] = [];

function log(msg: string) {
  logLines.push(msg);
  if (logLines.length > 200) {
    logLines.shift();
  }
  // eslint-disable-next-line no-console
  console.info('[skin]', msg);
}

// ---------------------------------------------------------------- 可调用的空对象

let noopInstance: unknown = null;

/**
 * 一个"什么都能点、点了还是它自己"的对象。
 * 用于 `ctx` 上缺失的宿主 API：皮肤普遍的写法是 `ctx.foo(...)` / `ctx.foo.bar(...)`，
 * 直接返回 undefined 会抛 TypeError 把整个皮肤带崩，返回它则静默降级。
 */
function magicNoop(): any {
  if (noopInstance) {
    return noopInstance;
  }
  const target = function noop() {
    return magicNoop();
  } as unknown as Record<string | symbol, unknown>;
  noopInstance = new Proxy(target, {
    get(_t, prop) {
      if (prop === Symbol.toPrimitive) {
        return () => '';
      }
      if (prop === 'toString') {
        return () => '[skin-stub]';
      }
      if (prop === 'then') {
        // 不能表现得像 thenable，否则 await 会挂住
        return undefined;
      }
      return magicNoop();
    },
    apply() {
      return magicNoop();
    },
    set() {
      return true;
    },
  });
  return noopInstance;
}

// ---------------------------------------------------------------- 宿主侧 require

/**
 * 解析皮肤 `require(...)` 的依赖。
 *
 * <p>实测皮肤会 require 三类：{@code react}（我们本来就有）、
 * {@code @deepseek-ai/dsh-client-ui-primitives}（DSH 私有 UI 原语，皮肤普遍用 try/catch 包住，
 * 给 undefined 它会自己降级）、以及各自的内部 chunk。</p>
 */
function resolveHostDep(dep: string): unknown {
  currentDeps.push(dep);
  switch (dep) {
    case 'react':
      return React;
    case 'react-dom':
      return ReactDOM;
    case 'react-dom/client':
      return ReactDOMClient;
    case 'react/jsx-runtime':
      return (React as unknown as Record<string, unknown>)['jsx-runtime'] ?? React;
    default:
      log(`依赖未提供，返回空对象：${dep}`);
      return magicNoop();
  }
}

/** 本次激活期间皮肤 require 过的依赖（每次 activate 前清空）。 */
let currentDeps: string[] = [];

/**
 * 正在注入的皮肤的市场 id。
 *
 * <p>用途：`__ModuleLoader__.load()` 需要知道"这次注册对应哪个市场皮肤"，
 * 而 bundle 自报的 id 是另一套命名（`@dsh-external/...`），不能当主键。</p>
 *
 * <p>用单槽足够：皮肤脚本是在 `activateSkin` 里**同步**注入并立即执行完 `load()` 的，
 * 不存在并发注入。若将来改成异步注入，这里必须换成栈。</p>
 */
let pendingSkinId: string | null = null;

// ---------------------------------------------------------------- ctx

/**
 * 构造传给皮肤 `apply(ctx)` 的上下文。
 *
 * <p>已知会被用到的：{@code ctx.effect(disposer, label)}（登记清理）。
 * 其余宿主 API 一律走 {@link magicNoop} 兜底，让皮肤优雅降级而不是崩掉。</p>
 */
function makeCtx(record: LoadedSkin): unknown {
  const base: Record<string, unknown> = {
    /**
     * Cordis 的 `ctx.effect(fn)`：**调用 fn**，把 fn 的**返回值**（清理函数）登记。
     *
     * <p>真实皮肤的两种用法都印证这一点：
     * <pre>
     *   ctx.effect(() => installOrcaCustomization(), '...: customization declaration')  // fn 返回 cleanup
     *   ctx.effect(() => () => { disposeScene(); … },  '...: technical chrome')          // fn 返回 cleanup
     * </pre>
     * 也就是说 fn 是"立即执行的生产逻辑"，返回值才是 disposer。</p>
     *
     * <p><b>早期实现是个真 bug</b>：它把 fn 本身当 disposer 存进数组、**从不调用**。
     * 后果很隐蔽 —— 表面上看 `disposers` 有值（所以日志像"登记成功"），实际：
     * ① 皮肤的注册类逻辑从未执行（`installOrcaCustomization()` 没跑 → 自定义协议从未注册，
     *    所以 html 上永远没有 `data-dsh-whale-*` 部件开关）；
     * ② 卸载时那些清理函数也不会跑，停用皮肤会残留节点。</p>
     */
    effect(fn: unknown, label?: string) {
      if (typeof fn !== 'function') {
        log(`effect 收到非函数（${label ?? ''}），已忽略`);
        return;
      }
      if (!record.effectLabel) {
        record.effectLabel = label;
      }
      try {
        // 关键：**先调用**，再把它返回的清理函数收起来
        const returned = (fn as () => unknown)();
        const disposer =
          typeof returned === 'function'
            ? (returned as () => void)
            : returned && typeof returned === 'object' && typeof (returned as { dispose?: unknown }).dispose === 'function'
              ? () => (returned as { dispose: () => void }).dispose()
              : null;
        if (disposer) {
          record.disposers.push(disposer);
          log(`effect 已执行，登记清理函数：${label ?? '(no label)'}`);
        } else {
          log(`effect 已执行（无返回值，无需清理）：${label ?? '(no label)'}`);
        }
      } catch (e) {
        const msg = `effect(${label ?? ''}) 执行抛错：${(e as Error)?.message ?? String(e)}`;
        record.errors.push(msg);
        log(msg);
      }
    },
    /** 部分插件用 ctx.on / ctx.off 订阅事件 */
    on() {
      return () => undefined;
    },
    off() {
      return () => undefined;
    },
    /** 让皮肤能拿到自己的 id，方便调试 */
    skinId: record.id,
    id: record.id,
    logger: {
      info: (m: unknown) => log(`[${record.id}] ${String(m)}`),
      warn: (m: unknown) => log(`[${record.id}][warn] ${String(m)}`),
      error: (m: unknown) => log(`[${record.id}][error] ${String(m)}`),
    },
  };
  return new Proxy(base, {
    get(t, prop) {
      if (prop in t) {
        return (t as Record<string | symbol, unknown>)[prop];
      }
      log(`ctx.${String(prop)} 被访问，宿主未实现，返回兜底对象`);
      return magicNoop();
    },
    has() {
      // 让 typeof 探测都成立，皮肤走"有该 API"的分支（会静默降级）
      return true;
    },
  });
}

// ---------------------------------------------------------------- ModuleLoader

/** 在**任何皮肤脚本执行前**调用一次。 */
export function installModuleLoader(): void {
  // 让位样式先于加载器装好（幂等，重复调用安全）
  installSkinYieldStyles();
  const w = window as unknown as Record<string, unknown>;
  if (w.__ModuleLoader__) {
    return;
  }
  w.__ModuleLoader__ = {
    load(payload: ModuleLoaderPayload) {
      const moduleId = payload?.id ?? '(unknown)';
      /*
       * 主键必须是**市场 id**（activateSkin 传进来的那个），而不是 bundle 自称的模块名。
       * 皮肤脚本是在 activateSkin 里同步注入执行的，所以 `pendingSkinId` 一定就是当前这个。
       */
      const id = pendingSkinId ?? moduleId;
      try {
        log(`load(${moduleId}) → 注册为 ${id}`);
        const plugin = payload.factory(resolveHostDep);
        const record: LoadedSkin = {
          id,
          moduleId,
          plugin,
          disposers: [],
          errors: [],
          pluginName: plugin?.name,
          deps: [...currentDeps],
        };
        loaded.set(id, record);
        // DSH 是"注册即激活"：模块 load 完就 apply
        if (typeof plugin?.apply === 'function') {
          try {
            const maybeDisposer = plugin.apply(makeCtx(record));
            if (typeof maybeDisposer === 'function') {
              record.disposers.push(maybeDisposer as () => void);
            }
            log(`apply(${id}) 完成`);
          } catch (e) {
            const msg = `apply 抛错：${(e as Error)?.message ?? String(e)}`;
            record.errors.push(msg);
            log(msg);
          }
        } else {
          const msg = '插件没有导出 apply()';
          record.errors.push(msg);
          log(msg);
        }
      } catch (e) {
        const msg = `factory 抛错：${(e as Error)?.message ?? String(e)}`;
        log(msg);
        const rec = loaded.get(id) ?? { id, plugin: {}, disposers: [], errors: [] };
        rec.errors.push(msg);
        loaded.set(id, rec);
      }
    },
    get(id: string) {
      return loaded.get(id)?.plugin;
    },
    ids() {
      return [...loaded.keys()];
    },
  };
  log('__ModuleLoader__ 已安装');
}

// ---------------------------------------------------------------- 激活 / 卸载

/**
 * 拉取皮肤 bundle 文本。
 *
 * <p>必须走 {@code api/skins.ts} 的封装，**不能用裸 fetch**：`/skins/bundle` 在
 * `SkinController` 上是 `@RequiresPermission("skin:manage")` 管控的接口，
 * 只有经 `http` 封装才会带上 `Authorization: Bearer`。裸 fetch 不带 token，
 * 在 `security.enabled=true` 时必然 401 —— 用户看到的正是
 * 「取皮肤 bundle 失败：HTTP 401」，而且因为代码里写的是"bundle 失败"，
 * 极易被误判成上游图床/仓库的问题。</p>
 */
async function fetchBundleText(id: string): Promise<string> {
  const data = await fetchSkinBundle(id);
  const text = data?.text;
  if (!text) {
    throw new Error('皮肤 bundle 为空');
  }
  log(`bundle = ${data?.path}（${text.length} 字符）`);
  return text;
}

/**
 * 激活一个皮肤：注入它的自注册脚本。
 *
 * <p>用 `<script>` 而不是 `new Function`：皮肤产物里有 `var` 全局写法与裸 `window` 访问，
 * `script` 标签的执行环境与真实宿主一致，踩坑最少。</p>
 */
export async function activateSkin(id: string): Promise<LoadedSkin> {
  installModuleLoader();
  const existing = loaded.get(id);
  if (existing) {
    log(`${id} 已加载，跳过`);
    return existing;
  }
  currentDeps = [];
  const text = await fetchBundleText(id);
  // 捕获加载期的全局错误（皮肤脚本在顶层抛错时不会走我们的 try/catch）
  const onError = (e: ErrorEvent) => {
    const msg = `脚本错误：${e.message}`;
    log(msg);
    const rec = loaded.get(id);
    if (rec) {
      rec.errors.push(msg);
    }
  };
  window.addEventListener('error', onError);
  try {
    const el = document.createElement('script');
    el.dataset.apSkin = id;
    el.textContent = text;
    // 告诉 load()：这次注册的市场 id 是这个（脚本同步执行，所以紧接着就会用到）
    pendingSkinId = id;
    document.head.appendChild(el);
  } finally {
    pendingSkinId = null;
    // 只在加载窗口内监听
    window.setTimeout(() => window.removeEventListener('error', onError), 4000);
  }
  // factory 是同步执行的，load() 应该已经跑完
  notifySkinRuntime();
  return (
    loaded.get(id) ?? {
      id,
      plugin: {},
      disposers: [],
      errors: ['脚本执行后没有调用 __ModuleLoader__.load（可能不是自注册产物）'],
    }
  );
}

/** 卸载：先走皮肤登记的 disposer，再移除脚本节点。 */
export function deactivateSkin(id: string): void {
  const rec = loaded.get(id);
  if (rec) {
    for (const d of [...rec.disposers].reverse()) {
      try {
        d();
      } catch (e) {
        log(`disposer 抛错：${(e as Error)?.message ?? String(e)}`);
      }
    }
    rec.disposers.length = 0;
  } else {
    /*
     * 查不到记录也要继续往下清理。
     * 以前这里直接 `return` —— 配上"load() 用模块名当主键"那个 bug，
     * 后果就是 deactivateSkin(市场id) 一直静默什么都不做，「停止 JS」根本没卸载。
     */
    log(`${id} 不在注册表里，仍执行清理（脚本节点 / 自定义状态）`);
  }
  document.querySelectorAll(`script[data-ap-skin="${id}"]`).forEach((n) => n.remove());
  loaded.delete(id);
  // 注册表按皮肤**自报的** skinId 索引（如 orca-link），与市场 id 不同名，所以两个都传
  releaseCustomizationOf(id, rec?.moduleId ?? '');
  notifySkinRuntime();
  log(`${id} 已卸载`);
}

export function loadedSkins(): LoadedSkin[] {
  return [...loaded.values()];
}

export function skinRuntimeLog(): string[] {
  return [...logLines];
}

/*
 * 把运行时的**真实入口与状态**挂到 window 上，供自动化台架使用。
 *
 * 起因有两层：
 * 1. prod 构建会丢掉 `console.*`，Electron 只收得到它自己的告警 —— 于是"皮肤加载
 *    过程到底发生了什么"在台架里完全不可见。
 * 2. 更要紧的是：台架原来**自己 fetch bundle、自己 new 一个 mock ctx** 去跑皮肤，
 *    那套 mock 与真实实现分叉后（它的 effect 把 fn 当 disposer 存、从不调用），
 *    台架报"加载成功"而实际什么都没发生 —— 结论是假的，还会误导排查方向。
 *
 * 所以这里把真实入口暴露出来，让台架**只能**走生产路径（即用户点「运行 JS」的那条路），
 * 从根上消除"台架与生产不一致"。
 */
if (typeof window !== 'undefined') {
  const w = window as unknown as Record<string, unknown>;
  /** 皮肤加载过程的运行时日志（数组）。 */
  w.__apSkinRuntimeLog = () => [...logLines];
  /** 当前已加载 / 已启用的皮肤。 */
  w.__apSkinState = () => ({
    loaded: loadedSkins().map((s) => s.id),
    enabled: enabledSkinId(),
  });
  /** 真实启用入口（= enableSkin）。 */
  w.__apEnableSkin = (id: string) => enableSkin(String(id));
  /** 真实停用入口（= disableSkin）。 */
  w.__apDisableSkin = (id: string) => {
    disableSkin(String(id));
    return true;
  };
}

/** 皮肤运行时状态（已加载列表 / 启用项）变化的订阅，供界面刷新。 */
const runtimeListeners = new Set<() => void>();

export function subscribeSkinRuntime(fn: () => void): () => void {
  runtimeListeners.add(fn);
  return () => runtimeListeners.delete(fn);
}

function notifySkinRuntime(): void {
  syncSkinActiveAttr();
  for (const fn of [...runtimeListeners]) {
    try {
      fn();
    } catch {
      /* 单个订阅者出错不影响其它 */
    }
  }
}

/**
 * 在 `<html>` 上标记"有皮肤 JS 正在运行"。
 *
 * <p>宿主用它决定"外观要不要让位给皮肤"；皮肤自己也可以用（不是必需，但有了更省事）。</p>
 */
function syncSkinActiveAttr(): void {
  const root = document.documentElement;
  if (loaded.size > 0) {
    root.setAttribute('data-ap-skin-active', '');
  } else {
    root.removeAttribute('data-ap-skin-active');
  }
}

/**
 * React 侧订阅"当前是否有皮肤 JS 在运行"。
 *
 * <p>用途：`ThemeProvider` 用它决定要不要把 antd 的内容容器配色让位给皮肤
 * （皮肤的画会被大片不透明白卡片盖住）。</p>
 */
export function useSkinActive(): boolean {
  const [active, setActive] = React.useState(() => loadedSkins().length > 0);
  React.useEffect(() => subscribeSkinRuntime(() => setActive(loadedSkins().length > 0)), []);
  return active;
}

/**
 * 皮肤运行时，宿主把输入卡片的外观**让位**给皮肤。
 *
 * <h3>为什么是一条样式表，而不是 JSX 上的条件内联样式</h3>
 * 最初写成"React 里判断有无皮肤，然后给内联样式换一套值"。实测（无头 Electron，
 * 对**同一个元素实例**同时取 inline 与 computed）发现不管用：
 * <pre>
 *   inline   = border: 1px solid transparent; box-shadow: none
 *   computed = border-color: rgba(0,0,0,0.06); box-shadow: 0 6px 24px rgba(0,0,0,.1)
 * </pre>
 * 也就是说内联的让位值被别处更高优先级的声明压住了。与其去追是谁盖的，
 * 不如把让位写成一条**确定性规则**：皮肤对 background / border-color / box-shadow
 * 这三个都没有用 `!important`，所以我们带 `!important` 一定赢。
 *
 * <p>拖拽高亮是例外：卡片带 `data-dragging` 时不让位，否则用户看不到投放反馈。</p>
 *
 * <p>另外保留了 `backdrop-filter: blur` —— 皮肤画了不透明外框时它不可见，
 * 没画时又能兜住文字可读性。</p>
 */
const SKIN_YIELD_STYLE_ID = 'ap-skin-yield';

const SKIN_YIELD_CSS = `
html[data-ap-skin-active] [data-composer-card]:not([data-dragging]) {
  background: transparent !important;
  border-color: transparent !important;
  box-shadow: none !important;
  border-radius: 0 !important;
  /*
   * 必须一并关掉过渡。
   *
   * 按层叠规则，**运行中的 CSS 过渡优先级高于 !important**，所以卡片上那条
   * transition: border-color .15s, box-shadow .15s 会让这两个属性一直停在旧值上
   * （实测：background 与 border-radius 让位成功，border-color 与 box-shadow 没有，
   *   而恰好只有后两者在过渡列表里）。皮肤接管外观后我们也不需要这个过渡了。
   */
  transition: none !important;
  /*
   * 模糊层也一并去掉：它只是"半透明磨砂"，会让皮肤自己的画变得发灰发糊。
   * 皮肤既然画了框和底，宿主就不该再叠一层滤镜。
   */
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}
`;

function installSkinYieldStyles(): void {
  if (document.getElementById(SKIN_YIELD_STYLE_ID)) {
    return;
  }
  const el = document.createElement('style');
  el.id = SKIN_YIELD_STYLE_ID;
  // 打上标记，便于在 DOM 里与皮肤自己注入的样式表区分
  el.setAttribute('data-ap-skin', 'yield');
  el.textContent = SKIN_YIELD_CSS;
  document.head.appendChild(el);
}

// ---------------------------------------------------------------- 互斥 / 持久化 / 支持度

/**
 * 显式启用开关：只有用户明确"启用"过的皮肤才会在下次启动时自动加载。
 * <p>第三方代码不应因为我们"顺手记住了"就跑起来，所以默认是"不记住"。</p>
 */
const ENABLED_KEY = 'ap.skin.enabled';
/** 最近一次运行结果（用于在界面上**如实标注**支持度，而不是假装全兼容）。 */
const RUN_KEY = 'ap.skin.runs';
/** 契约版本要参与支持度缓存的 key —— 我们改了钩子，之前的结论就该失效。 */
const CONTRACT_KEY = 'ap.skin.contract';

export interface SkinRunReport {
  at: string;
  contractVersion: number;
  ok: boolean;
  registeredId?: string;
  effectLabel?: string;
  disposerCount?: number;
  errors: string[];
  /** 皮肤自己报的 name/inject，便于诊断 */
  pluginName?: string;
  deps?: string[];
}

function readJson<T>(key: string, dflt: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : dflt;
  } catch {
    return dflt;
  }
}

function writeJson(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* 存储不可用时只影响记忆，不影响本次运行 */
  }
}

/** 读取某个皮肤最近一次的运行结果。 */
export function lastRunOf(skinId: string): SkinRunReport | null {
  const all = readJson<Record<string, SkinRunReport>>(RUN_KEY, {});
  return all[skinId] ?? null;
}

function recordRun(skinId: string, rec: LoadedSkin): SkinRunReport {
  const report: SkinRunReport = {
    at: new Date().toISOString(),
    contractVersion: getContractVersion(),
    ok: rec.errors.length === 0 && Object.keys(rec.plugin ?? {}).length > 0,
    registeredId: rec.id,
    effectLabel: rec.effectLabel,
    disposerCount: rec.disposers.length,
    errors: rec.errors,
    pluginName: rec.pluginName,
    deps: rec.deps,
  };
  const all = readJson<Record<string, SkinRunReport>>(RUN_KEY, {});
  all[skinId] = report;
  writeJson(RUN_KEY, all);
  return report;
}

/** 契约版本：由 contract.ts 写入，运行时读取；用于让"支持度"随钩子变更失效。 */
let contractVersion = 1;
export function setContractVersion(v: number): void {
  contractVersion = v;
  writeJson(CONTRACT_KEY, v);
}
function getContractVersion(): number {
  return contractVersion;
}

/** 当前显式启用的皮肤 id（只有一个 —— 对齐 DSH 的 bodyAttr 单激活）。 */
export function enabledSkinId(): string | null {
  try {
    return localStorage.getItem(ENABLED_KEY);
  } catch {
    return null;
  }
}

function setEnabledSkinId(id: string | null): void {
  try {
    if (id) {
      localStorage.setItem(ENABLED_KEY, id);
    } else {
      localStorage.removeItem(ENABLED_KEY);
    }
  } catch {
    /* ignore */
  }
}

/**
 * 启用一个皮肤（**互斥**：先停掉其它已加载的）。
 *
 * <p>为什么必须互斥：DSH 靠"只有一个皮肤的作用域属性在场"来保证皮肤之间互不干扰
 * （每个皮肤声明 {@code bodyAttr}，所有选择器都带它前缀）。我们如果同时跑两个，
 * 两个作用域属性同时在场，两套皮肤的规则会互相覆盖——那正是 DSH 用
 * `stage-mutual-exclusion.mjs` 专门避免的事。</p>
 */
export async function enableSkin(skinId: string): Promise<{ rec: LoadedSkin; report: SkinRunReport }> {
  for (const other of [...loaded.keys()]) {
    if (other !== skinId) {
      log(`互斥：先停掉 ${other}`);
      deactivateSkin(other);
      clearSkinBodyAttrs(other);
    }
  }
  const rec = await activateSkin(skinId);
  const report = recordRun(skinId, rec);
  setEnabledSkinId(skinId);
  notifySkinRuntime();
  return { rec, report };
}

/** 停用：卸载并清掉它留在我们身上的痕迹（作用域属性）。 */
export function disableSkin(skinId: string): void {
  deactivateSkin(skinId);
  clearSkinBodyAttrs(skinId);
  if (enabledSkinId() === skinId) {
    setEnabledSkinId(null);
  }
  notifySkinRuntime();
}

/**
 * 清掉皮肤挂在 `body` 上的作用域属性。
 *
 * <p>皮肤自己会设 `body[data-dsh-xxx]`，停用后必须移除，否则它的 CSS 仍然生效
 * （选择器还在匹配）、且会挡住别的皮肤启用。</p>
 */
function clearSkinBodyAttrs(skinId: string): void {
  const rec = loaded.get(skinId);
  const candidates = new Set<string>();
  // 常见形态：data-dsh-<name> / data-<name>
  const short = skinId.includes('.') ? skinId.split('.').pop()! : skinId;
  candidates.add(`data-dsh-${short}`);
  candidates.add(`data-${short}`);
  for (const attr of [...document.body.attributes].map((a) => a.name)) {
    if (attr.startsWith('data-dsh-') || attr.startsWith('data-')) {
      // 只清"看起来是这个皮肤留下的"
      if (attr.includes(short) || short.includes(attr.replace(/^data-(dsh-)?/, ''))) {
        candidates.add(attr);
      }
    }
  }
  for (const c of candidates) {
    if (document.body.hasAttribute(c)) {
      document.body.removeAttribute(c);
      log(`清除作用域属性：${c}${rec ? '' : ' (皮肤未加载)'}`);
    }
  }
  // 皮肤可能还留了别的状态属性（data-orca-scene / data-maid-* 等）
  for (const attr of [...document.body.attributes].map((a) => a.name)) {
    if (/^data-(orca|maid|furina|mc|liang|endfield|aionui|dsh)-/.test(attr) || /^data-dsh-/.test(attr)) {
      document.body.removeAttribute(attr);
      log(`清除皮肤状态属性：${attr}`);
    }
  }
}

/** 启动时恢复"上次显式启用"的皮肤。返回是否成功。 */
export async function restoreEnabledSkin(): Promise<{ skinId: string; report: SkinRunReport } | null> {
  const id = enabledSkinId();
  if (!id) {
    return null;
  }
  log(`启动恢复已启用的皮肤：${id}`);
  try {
    const { report } = await enableSkin(id);
    return { skinId: id, report };
  } catch (e) {
    const msg = `启动恢复失败：${(e as Error)?.message ?? String(e)}`;
    log(msg);
    return null;
  }
}
