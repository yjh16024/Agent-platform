import { useSyncExternalStore } from 'react';
import type { ReactNode } from 'react';

/**
 * 「插件往宿主指定区域渲染 React 组件」的注册表（2026-10-19，P1）。
 *
 * <h3>它解决什么</h3>
 * 之前插件只能拿到**自己那块 DOM 容器**（`ctx.container`）—— 想在右侧面板、覆盖层之类
 * 宿主区域里放东西，只能靠 `ctx.mountTo(选择器)` 去改宿主的 DOM，既脆弱又容易越界。
 * 现在插件可以声明式地"往某个区域注册一个 React 元素"，由宿主负责挂载与卸载。
 *
 * <h3>为什么不直接复用 {@code SlotOutlet}</h3>
 * `SlotOutlet` 读的是**后端下发的声明**（`ui-contributions`），是"数据驱动的白名单组件"；
 * 这里是**运行期插件自己调用的注册 API**（`ctx.slots.register`），没有后端参与。
 * 两者最终都落到槽位上，但生命周期来源不同：一个随请求刷新，一个随 `apply/卸载` 走。
 * 硬合成一条链会让"插件的元素在下次拉取贡献时消失"这类问题变得难以解释。
 *
 * <h3>快照必须稳定</h3>
 * `useSyncExternalStore` 的 `getSnapshot` 每次返回**同一个数组引用**，否则 React 会无限重渲染。
 * 所以删除到空时返回模块级常量 {@link EMPTY}，而不是 `[]`。
 */
export interface PluginSlotEntry {
  pluginId: string;
  node: ReactNode;
  order: number;
}

const EMPTY: PluginSlotEntry[] = [];
const slots = new Map<string, PluginSlotEntry[]>();
const listeners = new Set<() => void>();

function emit(): void {
  for (const fn of [...listeners]) {
    try {
      fn();
    } catch (e) {
      // 订阅者（React）自己的错误不能打断其它订阅者
      console.warn('[plugin] 槽位订阅者抛错：', e);
    }
  }
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/**
 * 注册一个元素到宿主区域；返回**注销函数**（插件卸载时必须调用）。
 *
 * @param slot  区域 id（见 `slots/registry.ts` 的 `SLOT_IDS`）
 * @param order 同区域内排序，小的在前
 */
export function registerPluginSlot(
  slot: string,
  pluginId: string,
  node: ReactNode,
  order = 0,
): () => void {
  const entry: PluginSlotEntry = { pluginId, node, order };
  const next = [...(slots.get(slot) ?? []), entry].sort((a, b) => a.order - b.order);
  slots.set(slot, next);
  emit();

  return () => {
    const current = slots.get(slot);
    if (!current) {
      return;
    }
    const rest = current.filter((e) => e !== entry);
    if (rest.length === 0) {
      slots.delete(slot);
    } else {
      slots.set(slot, rest);
    }
    emit();
  };
}

/** 某区域当前的注册项（稳定引用）。 */
export function pluginSlotEntries(slot: string): PluginSlotEntry[] {
  return slots.get(slot) ?? EMPTY;
}

// ------------------------------------------------------------------ 侧栏一级入口（P1）

/**
 * 插件注册的**侧栏一级入口**（2026-10-19，P1）。
 *
 * <p>解决什么：之前插件的大面板只能塞进右侧 380px 或全屏覆盖层 ——
 * 像 dsh-engram 那种"顶层侧栏入口 + 主列满宽渲染"的形态做不到。
 * 现在插件可以声明一个菜单项，点开后主内容区整块交给它。</p>
 *
 * <p>与 {@link registerPluginSlot} 的区别：那个往**已有区域**里塞内容，
 * 这个**新增一个入口**（菜单项 + 独立视图）。因此它必须挂在侧栏菜单上，
 * 且点其它菜单项时要退出该视图（见 AppLayout 的 activePlugin 状态）。</p>
 */
export interface PluginNavEntry {
  pluginId: string;
  label: string;
  node: ReactNode;
}

const navEntries = new Map<string, PluginNavEntry>();
/** 稳定快照：useSyncExternalStore 的 getSnapshot 必须返回同一引用，否则无限重渲染。 */
let navCache: PluginNavEntry[] = [];

function rebuildNav(): void {
  navCache = [...navEntries.values()];
}

/** 注册一个侧栏入口；返回注销函数。 */
export function registerPluginNav(pluginId: string, label: string, node: ReactNode): () => void {
  navEntries.set(pluginId, { pluginId, label, node });
  rebuildNav();
  emit();
  return () => {
    navEntries.delete(pluginId);
    rebuildNav();
    emit();
  };
}

/** 当前所有插件侧栏入口（稳定引用）。 */
export function pluginNavEntries(): PluginNavEntry[] {
  return navCache;
}

/** React 侧订阅侧栏入口列表。 */
export function usePluginNav(): PluginNavEntry[] {
  return useSyncExternalStore(subscribe, pluginNavEntries, pluginNavEntries);
}

/** React 侧订阅某区域。 */
export function usePluginSlotEntries(slot: string): PluginSlotEntry[] {
  return useSyncExternalStore(
    subscribe,
    () => pluginSlotEntries(slot),
    () => EMPTY,
  );
}
