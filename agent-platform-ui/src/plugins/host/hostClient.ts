/**
 * 插件 Host 能力的**渲染层客户端**（2026-10-19，P4）。
 *
 * <h3>调用链</h3>
 * <pre>
 * 插件脚本 ctx.host.fs.read(path)
 *   → hostCall(pluginId, 'fs', 'read', [path])
 *     → window.apHost.call(...)        （preload 暴露）
 *       → ipcMain 'ap:host-call'        （main.js）
 *         → hostBridge.js               （Node：真正的实现）
 * </pre>
 *
 * <h3>★ 首次授权：Promise 化的确认框</h3>
 * 主进程对未授权的能力返回 `{ needAuth: 'fs' }`（不报错）。这里把它转成一次
 * **界面确认**：把请求挂到队列 → {@code HostAuthDialog} 渲染 Modal →
 * 用户点「允许」后写授权并**自动重试**。用户全程只看到一次询问，插件代码里
 * 只是"调用稍微慢了一点"，不需要自己处理授权。
 *
 * <h3>浏览器里没有 apHost</h3>
 * `npm run dev` 直接开网页时没有 preload，`window.apHost` 是 undefined ——
 * 此时 `available=false`，所有调用抛"需要桌面版"。**明确报错**而不是静默返回
 * undefined：插件作者一眼能看出"不是我的 bug，是环境不支持"。
 */

import { http } from '../../api/http';

export interface HostResult {
  ok: boolean;
  data?: unknown;
  error?: string;
  /** 未授权时返回需要哪一类能力（`fs` / `http` / `exec` / `storage`）。 */
  needAuth?: string;
}

interface ApHostBridge {
  available: boolean;
  capabilities: string[];
  call: (pluginId: string, capability: string, method: string, args?: unknown[]) => Promise<HostResult>;
  authorize: (pluginId: string, capability: string) => Promise<{ ok: boolean; error?: string }>;
  revoke: (pluginId: string, capability: string) => Promise<{ ok: boolean; error?: string }>;
  status: (pluginId: string) => Promise<HostResult>;
  env: () => Promise<HostResult>;
  loadPlugin: (pluginId: string, dir: string, mainFile?: string) => Promise<HostResult>;
  unloadPlugin: (pluginId: string) => Promise<HostResult>;
  invokePlugin: (pluginId: string, method: string, args?: unknown) => Promise<HostResult>;
}

/** 能力的中文说明（授权弹窗与错误信息共用）。 */
export const CAPABILITY_LABELS: Record<string, { title: string; detail: string }> = {
  fs: {
    title: '读写本机文件',
    detail: '该插件可以读取和修改这台机器上的任意文件（包括工作区之外的位置）。',
  },
  http: {
    title: '访问网络',
    detail: '该插件可以向任意地址发起 HTTP 请求（不受浏览器跨域限制）。',
  },
  exec: {
    title: '执行本机命令',
    detail: '该插件可以在这台机器上运行任意命令（有超时与输出上限，但不限制命令内容）。',
  },
  storage: {
    title: '保存插件数据',
    detail: '该插件会在应用数据目录下保存自己的数据文件（只影响它自己的目录）。',
  },
};

function bridge(): ApHostBridge | undefined {
  return (window as unknown as { apHost?: ApHostBridge }).apHost;
}

/** 当前环境是否支持本机能力（只有桌面版支持）。 */
export function hostAvailable(): boolean {
  return !!bridge()?.available;
}

// ------------------------------------------------------------------ 授权队列

export interface AuthRequest {
  pluginId: string;
  capability: string;
  resolve: (allowed: boolean) => void;
}

let current: AuthRequest | null = null;
const queue: AuthRequest[] = [];
const listeners = new Set<() => void>();

function emit(): void {
  for (const fn of [...listeners]) {
    try {
      fn();
    } catch {
      /* 订阅者自己的错误不影响别人 */
    }
  }
}

/** 订阅"待确认的授权请求"变化（供弹窗组件用）。 */
export function subscribeAuth(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** 当前待确认的请求（没有则 null）。 */
export function currentAuthRequest(): AuthRequest | null {
  return current;
}

function pump(): void {
  if (current || queue.length === 0) {
    return;
  }
  current = queue.shift()!;
  emit();
}

/** 弹窗按钮的出口：允许 / 拒绝当前请求。 */
export function resolveAuthRequest(allowed: boolean): void {
  const req = current;
  if (!req) {
    return;
  }
  current = null;
  req.resolve(allowed);
  emit();
  pump();
}

function requestAuth(pluginId: string, capability: string): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    queue.push({ pluginId, capability, resolve });
    pump();
  });
}

// ------------------------------------------------------------------ 调用

/**
 * 调一次本机能力；未授权时先弹确认、允许后**自动重试**。
 *
 * @throws 环境不支持 / 用户拒绝 / 能力本身报错
 */
export async function hostCall(
  pluginId: string,
  capability: string,
  method: string,
  args?: unknown[],
): Promise<unknown> {
  const api = bridge();
  if (!api) {
    throw new Error('本机能力需要桌面版应用（当前是浏览器环境，没有 apHost 桥）');
  }

  let res = await api.call(pluginId, capability, method, args);
  if (res.ok) {
    return res.data;
  }

  if (res.needAuth) {
    const label = CAPABILITY_LABELS[res.needAuth]?.title ?? res.needAuth;
    const allowed = await requestAuth(pluginId, res.needAuth);
    if (!allowed) {
      throw new Error(`用户拒绝了「${label}」授权`);
    }
    const granted = await api.authorize(pluginId, res.needAuth);
    if (!granted.ok) {
      throw new Error(granted.error ?? '授权失败');
    }
    res = await api.call(pluginId, capability, method, args);
    if (res.ok) {
      return res.data;
    }
  }

  throw new Error(res.error ?? '本机能力调用失败');
}

// ------------------------------------------------------------------ 给插件用的 ctx.host

export interface PluginFsStat {
  size: number;
  mtime: number;
  dir: boolean;
}

/** 运行时环境信息（只读，不需要授权）。 */
export interface PluginEnvInfo {
  platform: string;
  arch: string;
  node: string;
  electron?: string | null;
  appVersion?: string | null;
  cwd: string;
  home?: string | null;
  tmp: string;
  eol: string;
  locale?: string | null;
}

export interface PluginHttpResponse {
  status: number;
  ok: boolean;
  headers: Record<string, string>;
  text: string;
  json: unknown;
}

export interface PluginExecResult {
  code: number;
  stdout: string;
  stderr: string;
  killed: boolean;
}

/** 插件脚本里的 `ctx.host`（四类能力 + 调自己 main.js 的方法）。 */
export interface PluginHostApi {
  /** 本环境是否支持（浏览器里为 false）。 */
  available: boolean;
  fs: {
    read: (path: string) => Promise<string>;
    /** `encoding='base64'` 可写二进制。 */
    write: (path: string, content: string, encoding?: 'utf8' | 'base64') => Promise<{ bytes: number }>;
    list: (path: string) => Promise<{ name: string; dir: boolean }[]>;
    exists: (path: string) => Promise<boolean>;
    stat: (path: string) => Promise<PluginFsStat>;
    mkdir: (path: string) => Promise<boolean>;
    remove: (path: string) => Promise<boolean>;
  };
  http: {
    request: (cfg: {
      url: string;
      method?: string;
      headers?: Record<string, string>;
      body?: unknown;
      timeoutMs?: number;
    }) => Promise<PluginHttpResponse>;
  };
  exec: {
    run: (cfg: {
      cmd: string;
      args?: string[];
      cwd?: string;
      timeoutMs?: number;
      env?: Record<string, string>;
    }) => Promise<PluginExecResult>;
  };
  storage: {
    get: <T = unknown>(key: string) => Promise<T>;
    set: (key: string, value: unknown) => Promise<boolean>;
    remove: (key: string) => Promise<boolean>;
    keys: () => Promise<string[]>;
    all: () => Promise<Record<string, unknown>>;
  };
  /**
   * 调**平台默认对话模型**（后端解析厂商与密钥）。
   *
   * <p>为什么它不走主进程：模型调用需要平台的密钥解密与路由策略，只在 Java 后端里有。
   * 这也是"能力在哪就放哪"的分层：本机能力在主进程，平台能力在后端。</p>
   */
  model: {
    chat: (opts: {
      message: string;
      system?: string;
      temperature?: number;
      maxTokens?: number;
    }) => Promise<{
      content: string;
      promptTokens: number;
      completionTokens: number;
      latencyMs: number;
      routing?: string | null;
    }>;
  };
  /** 调插件自己的 `main.js` 里 `methods[name]`（Node 侧逻辑）。 */
  invoke: <T = unknown>(method: string, args?: unknown) => Promise<T>;
  /** 运行时环境信息（平台 / 路径 / 版本），**不需要授权**。 */
  env: () => Promise<PluginEnvInfo>;
  /** 各类能力的授权状态。 */
  status: () => Promise<Record<string, boolean>>;
  /** 手动申请某类能力（一般不需要：调用时自动弹）。 */
  authorize: (capability: string) => Promise<boolean>;
}

/** 为某插件构造 `ctx.host`。 */
export function createHostApi(pluginId: string): PluginHostApi {
  const call = (capability: string, method: string, ...args: unknown[]) =>
    hostCall(pluginId, capability, method, args);

  return {
    available: hostAvailable(),
    fs: {
      read: (p) => call('fs', 'read', p) as Promise<string>,
      write: (p, content, encoding) => call('fs', 'write', p, content, encoding) as Promise<{ bytes: number }>,
      list: (p) => call('fs', 'list', p) as Promise<{ name: string; dir: boolean }[]>,
      exists: (p) => call('fs', 'exists', p) as Promise<boolean>,
      stat: (p) => call('fs', 'stat', p) as Promise<PluginFsStat>,
      mkdir: (p) => call('fs', 'mkdir', p) as Promise<boolean>,
      remove: (p) => call('fs', 'remove', p) as Promise<boolean>,
    },
    http: {
      request: (cfg) => call('http', 'request', cfg) as Promise<PluginHttpResponse>,
    },
    exec: {
      run: (cfg) => call('exec', 'run', cfg) as Promise<PluginExecResult>,
    },
    storage: {
      get: <T,>(key: string) => call('storage', 'get', key) as Promise<T>,
      set: (key, value) => call('storage', 'set', key, value) as Promise<boolean>,
      remove: (key) => call('storage', 'remove', key) as Promise<boolean>,
      keys: () => call('storage', 'keys') as Promise<string[]>,
      all: () => call('storage', 'all') as Promise<Record<string, unknown>>,
    },
    model: {
      chat: (opts) => http.post('/api/v1/plugin-host/model/chat', opts),
    },
    invoke: async <T,>(method: string, args?: unknown): Promise<T> => {
      const api = bridge();
      if (!api) {
        throw new Error('插件 Node 侧逻辑需要桌面版应用');
      }
      const res = await api.invokePlugin(pluginId, method, args);
      if (!res.ok) {
        throw new Error(res.error ?? '插件 main.js 调用失败');
      }
      return res.data as T;
    },
    env: async () => {
      const api = bridge();
      if (!api) {
        throw new Error('环境信息需要桌面版应用');
      }
      const res = await api.env();
      if (!res.ok) {
        throw new Error(res.error ?? '读取环境信息失败');
      }
      return res.data as PluginEnvInfo;
    },
    status: async () => {
      const api = bridge();
      if (!api) {
        return {};
      }
      const res = await api.status(pluginId);
      return (res.data as Record<string, boolean>) ?? {};
    },
    authorize: async (capability) => {
      const api = bridge();
      if (!api) {
        return false;
      }
      const res = await api.authorize(pluginId, capability);
      return res.ok;
    },
  };
}
