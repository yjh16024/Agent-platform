import { getTenantId, getToken } from '../../api/http';
import { getSession, listSessions } from '../../api/sessions';
import type { PluginConfigField } from '../../api/pluginWeb';
import { useChatStore } from '../../store/chatStore';
import { useUiSlotStore } from '../../store/uiSlotStore';

/**
 * 插件可用的**宿主服务**（2026-10-19，P2）。
 *
 * <h3>为什么要有这一层</h3>
 * 之前插件只有 `invokeTool` 一个后端入口 —— 想存一个开关、读一下会话列表、调个接口，
 * 都得逼作者在插件里加一个"什么都干的工具"。服务是更自然的抽象：
 * 插件声明 `inject: ['storage']`，宿主注入一个语义明确的对象。
 *
 * <h3>★ 关于 `http` 的安全边界（必须限制）</h3>
 * 它带 `Authorization`（用户 token）与租户头 —— 如果能请求任意 URL，
 * 插件就等于拿到了"把 token 发到外部服务器"的能力。所以**只允许本平台的 `/api/` 相对路径**：
 * 拒绝 `http(s)://`、拒绝 `//` 开头（协议相对 URL）、拒绝其它路径。
 * 这是服务层唯一的硬性拦截，其余能力（读写文件、发网络请求）由插件自己负责。
 *
 * <h3>存储落在哪</h3>
 * `localStorage`，键名 `ap.plugin.<pluginId>.<key>`。单机场景够用，且**不需要新增后端表**；
 * 代价是换机器不同步、清缓存会丢 —— 已在 {@link PluginStorage} 的注释里写明。
 * 将来若要跨设备，把这里的实现换成后端 KV 接口即可（插件代码不用改）。
 */

// ------------------------------------------------------------------ 类型

export interface PluginStorageBox<T> {
  get: () => T;
  set: (value: T) => void;
  remove: () => void;
  /** 订阅**本页内**的变化（不跨标签页）。 */
  subscribe: (fn: (value: T) => void) => () => void;
}

export interface PluginStorage {
  /**
   * 声明一块存储；`key` 在本插件内唯一。
   *
   * <p>⚠️ 数据在**本机浏览器**（localStorage）：换机器不同步、清缓存会丢。
   * 适合"开关 / 上次选择 / 缓存"，不适合当业务主数据。</p>
   */
  define: <T>(opts: { key: string; default?: T }) => PluginStorageBox<T>;
}

export interface PluginHttp {
  get: <T = unknown>(path: string) => Promise<T>;
  post: <T = unknown>(path: string, body?: unknown) => Promise<T>;
  put: <T = unknown>(path: string, body?: unknown) => Promise<T>;
  delete: <T = unknown>(path: string) => Promise<T>;
}

export interface PluginSessionService {
  /** 当前对话的会话 id（没有对话时 null）。 */
  current: () => string | null;
  /** 最近会话列表（默认 20 条）。 */
  list: (opts?: { limit?: number }) => Promise<unknown>;
  /** 某会话的详情（含消息）。 */
  get: (sessionId: string) => Promise<unknown>;
  /**
   * **当前会话的消息**（只含 user / assistant），默认最近 20 条 —— 2026-10-19，P0。
   *
   * <p>这是"记忆 / 摘要 / 观测"类插件的基础：没有它，插件只能等 LLM 主动调工具，
   * 做不到"自动摄取上一轮对话"。dsh-engram 那类插件的自动摄取就建立在这个能力上。</p>
   *
   * <p>⚠️ 它能读到**用户与智能体的完整对话内容** —— 本地单机可以接受（装插件即信任），
   * 但插件作者应在 README 里明说；需要更细的控制时用 {@code limit} 收窄。</p>
   */
  history: (limit?: number) => Promise<{ role: 'user' | 'assistant'; content: string }[]>;
}

export interface PluginLogger {
  info: (...args: unknown[]) => void;
  warn: (...args: unknown[]) => void;
  error: (...args: unknown[]) => void;
}

/**
 * 插件的**自身配置**（2026-10-19）。
 *
 * <p>与 {@code storage} 的区别：storage 是"插件想存什么就存什么"的抽屉，
 * config 是"manifest 里声明过的字段" —— 宿主据此渲染设置表单，插件只管读值。
 * **字段与默认值属于插件，渲染与存储属于宿主**，这就是"插件自带配置"的正确分工。</p>
 */
export interface PluginConfigApi {
  /** 读配置：manifest 默认值 + 用户已保存值（后者覆盖）。值类型由插件按自己的声明断言。 */
  get: () => Record<string, unknown>;
  /** 保存（浅合并）。宿主设置表单与插件自身都可以调。 */
  set: (patch: Record<string, unknown>) => void;
  /** 订阅变更（同窗口内 set 后立即触发）。返回退订函数。 */
  onChange: (fn: (cfg: Record<string, unknown>) => void) => () => void;
}

export interface PluginServices {
  storage: PluginStorage;
  http: PluginHttp;
  session: PluginSessionService;
  logger: PluginLogger;
  config: PluginConfigApi;
}

/** 本平台**实际提供**的服务名 —— 插件 `inject` 里出现其它名字会被明确拒绝（不静默兜底）。 */
export const SERVICE_NAMES = ['storage', 'http', 'session', 'logger', 'config'] as const;

// ------------------------------------------------------------------ 实现

function createStorage(pluginId: string): PluginStorage {
  return {
    define: <T,>({ key, default: fallback }: { key: string; default?: T }): PluginStorageBox<T> => {
      const fullKey = `ap.plugin.${pluginId}.${key}`;
      const listeners = new Set<(value: T) => void>();

      const read = (): T => {
        try {
          const raw = localStorage.getItem(fullKey);
          return raw == null ? (fallback as T) : (JSON.parse(raw) as T);
        } catch {
          // 值被手工改坏 / 不是 JSON：退回默认值，而不是让插件崩在启动阶段
          return fallback as T;
        }
      };

      return {
        get: read,
        set: (value) => {
          try {
            localStorage.setItem(fullKey, JSON.stringify(value));
          } catch {
            // 配额满 / 隐私模式：静默失败（插件不该因为存不下一个开关就整个不工作）
          }
          for (const fn of [...listeners]) {
            try {
              fn(value);
            } catch (e) {
              console.warn(`[plugin:${pluginId}] storage 订阅者抛错：`, e);
            }
          }
        },
        remove: () => {
          try {
            localStorage.removeItem(fullKey);
          } catch {
            /* 同上 */
          }
        },
        subscribe: (fn) => {
          listeners.add(fn);
          return () => {
            listeners.delete(fn);
          };
        },
      };
    },
  };
}

function createHttp(pluginId: string): PluginHttp {
  const request = async <T,>(method: string, path: string, body?: unknown): Promise<T> => {
    if (!path.startsWith('/api/') || path.startsWith('//') || path.includes('://')) {
      // ★ 安全边界：带 token 的请求只能打本平台 —— 见文件头注释
      throw new Error(`[plugin:${pluginId}] http 只允许本平台相对路径（以 /api/ 开头），收到：${path}`);
    }
    const token = getToken();
    const res = await fetch(path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'X-Tenant-Id': getTenantId(),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const payload = (await res.json().catch(() => null)) as
      | { success?: boolean; message?: string; data?: T }
      | null;
    if (!res.ok || payload?.success === false) {
      throw new Error(payload?.message || `请求失败（HTTP ${res.status}）`);
    }
    if (payload == null) {
      throw new Error(`响应不是 JSON（HTTP ${res.status}），插件 http 只支持平台接口`);
    }
    return payload.data as T;
  };

  return {
    get: (path) => request('GET', path),
    post: (path, body) => request('POST', path, body),
    put: (path, body) => request('PUT', path, body),
    delete: (path) => request('DELETE', path),
  };
}

/** 当前会话 id（uiSlotStore 记着"正在对话的智能体"，chatStore 记着它的 sessionId）。 */
function currentSessionId(): string | null {
  const agentId = useUiSlotStore.getState().currentAgentId;
  if (!agentId) {
    return null;
  }
  return useChatStore.getState().sessionByAgent[agentId] ?? null;
}

function createSession(): PluginSessionService {
  return {
    current: currentSessionId,
    list: (opts) => listSessions(0, opts?.limit ?? 20),
    get: (sessionId) => getSession(sessionId),
    history: async (limit = 20) => {
      const sid = currentSessionId();
      if (!sid) {
        return [];
      }
      const detail = await getSession(sid);
      return (detail.messages ?? [])
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .slice(-Math.max(1, limit))
        .map((m) => ({ role: m.role as 'user' | 'assistant', content: m.content ?? '' }));
    },
  };
}

function createLogger(pluginId: string): PluginLogger {
  const prefix = `[plugin:${pluginId}]`;
  return {
    info: (...args) => console.info(prefix, ...args),
    warn: (...args) => console.warn(prefix, ...args),
    error: (...args) => console.error(prefix, ...args),
  };
}

// ------------------------------------------------------------------ 配置（manifest 声明驱动）

/** pluginId → manifest 里声明的默认值（由槽位组件在挂载前登记）。 */
const configDefaults = new Map<string, Record<string, unknown>>();
/** pluginId → 变更订阅者。 */
const configListeners = new Map<string, Set<(cfg: Record<string, unknown>) => void>>();

const configStorageKey = (pluginId: string) => `ap.plugin.${pluginId}.config`;

/**
 * 登记 manifest 里声明的默认值。
 *
 * <p>为什么需要这一步：插件脚本执行时**看不到 manifest**（它只拿到 `ctx`），
 * 而"默认值"属于 manifest。所以由宿主的槽位组件在 `apply` 之前登记一次，
 * `ctx.config.get()` 才能把"没保存过的字段"填成默认值。</p>
 */
export function registerPluginConfigDefaults(
  pluginId: string,
  fields?: PluginConfigField[] | null,
): void {
  const defaults: Record<string, unknown> = {};
  for (const f of fields ?? []) {
    if (f && typeof f.key === 'string' && f.key) {
      defaults[f.key] = f.default;
    }
  }
  configDefaults.set(pluginId, defaults);
}

function readPluginConfig(pluginId: string): Record<string, unknown> {
  const defaults = configDefaults.get(pluginId) ?? {};
  let saved: Record<string, unknown> = {};
  try {
    const raw = localStorage.getItem(configStorageKey(pluginId));
    if (raw) {
      saved = JSON.parse(raw) as Record<string, unknown>;
    }
  } catch {
    // 配置被手工改坏 / 不是 JSON：当没保存过（回落默认值），而不是让插件崩在启动阶段
  }
  return { ...defaults, ...saved };
}

/** 为某插件创建配置读写器（宿主设置表单与插件自身共用同一个实现）。 */
export function createPluginConfig(pluginId: string): PluginConfigApi {
  return {
    get: () => readPluginConfig(pluginId),
    set: (patch) => {
      const next = { ...readPluginConfig(pluginId), ...patch };
      try {
        localStorage.setItem(configStorageKey(pluginId), JSON.stringify(next));
      } catch {
        // 配额满 / 隐私模式：静默失败，插件下次读到旧值（不抛错到插件主流程）
      }
      for (const fn of configListeners.get(pluginId) ?? []) {
        try {
          fn(next);
        } catch {
          /* 一个订阅者抛错不影响其它订阅者 */
        }
      }
    },
    onChange: (fn) => {
      const set = configListeners.get(pluginId) ?? new Set();
      set.add(fn);
      configListeners.set(pluginId, set);
      return () => {
        set.delete(fn);
      };
    },
  };
}

/** 为某插件创建服务集合（每次挂载一份，`storage` / `config` 按插件 id 隔离）。 */
export function createServices(pluginId: string): PluginServices {
  return {
    storage: createStorage(pluginId),
    http: createHttp(pluginId),
    session: createSession(),
    logger: createLogger(pluginId),
    config: createPluginConfig(pluginId),
  };
}
