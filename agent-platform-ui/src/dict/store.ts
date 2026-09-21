import { useSyncExternalStore } from 'react';
import { DICT, getDictBatch, type DictOption } from '../api/dict';

/**
 * 前端字典缓存。
 *
 * <h3>为什么是"启动预取 + 模块级缓存"而不是每个下拉各请求一次</h3>
 * 侧栏、筛选器、表单里到处都要用字典，一个页面同时出现十几个下拉很正常。
 * 如果每个下拉各自发请求，页面加载时会打出一串 HTTP —— 而这些数据**几乎不变**。
 * 所以启动时批量取一次（字典总量只有几十条），之后所有下拉都是同步读取。
 *
 * <h3>为什么用 useSyncExternalStore 而不是 Context</h3>
 * 字典是**组件树之外**的数据（跟着请求生命周期走，不属于任何一层 UI 状态），
 * 用 Context 会逼着我们把 Provider 塞进组件树、还得处理"Provider 重渲染带动全树"。
 * 订阅式的外部 store 更贴合它的性质，也让任何地方（含非组件代码）都能读。
 */

/** null = 还没加载；对象 = 已加载（可能是空对象，表示后端没这些字典）。 */
let cache: Record<string, DictOption[]> | null = null;
let inflight: Promise<void> | null = null;

const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((fn) => fn());
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** getSnapshot 必须返回**稳定引用**，否则会无限重渲染 —— 所以整体替换 cache，不原地改。 */
function getSnapshot(): Record<string, DictOption[]> | null {
  return cache;
}

/**
 * 预取全部内置字典。可重复调用（并发时共享同一个请求）。
 *
 * <p>失败时**静默**：字典拿不到不该让整个应用白屏，各下拉顶多是空的
 * （后端仍有权限校验，功能安全性不受影响）。</p>
 */
export function preloadDicts(): Promise<void> {
  if (cache) {
    return Promise.resolve();
  }
  if (inflight) {
    return inflight;
  }
  inflight = getDictBatch(Object.values(DICT))
    .then((data) => {
      cache = data ?? {};
      emit();
    })
    .catch(() => {
      // 标记成空对象而不是保留 null：否则每次 useDict 都会再触发一遍预取
      cache = cache ?? {};
      emit();
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/** 清空缓存（退出登录、切换租户后调用，避免看到上一个租户的字典）。 */
export function resetDicts() {
  cache = null;
  inflight = null;
  emit();
}

/** 读取某个字典；未加载完时返回空数组。 */
export function getDict(code: string): DictOption[] {
  return cache?.[code] ?? [];
}

/** React Hook：订阅某个字典。 */
export function useDict(code: string): DictOption[] {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  return snapshot?.[code] ?? [];
}

/** React Hook：字典是否已加载（用于给下拉显示 loading 态）。 */
export function useDictReady(): boolean {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  return snapshot !== null;
}
