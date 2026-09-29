import { create } from 'zustand';
import { uiContributions, type UiContribution } from '../api/uiSlots';
import { SUPPORTED_SLOTS, SUPPORTED_TYPES, type SlotId } from '../slots/registry';

/**
 * 界面贡献的全局缓存。
 *
 * <h3>为什么用全局 store 而不是每个 {@code <SlotOutlet>} 自己拉</h3>
 * 一台智能体上会有 4 个槽位（会话头、侧栏底、会话上方、输入工具条），
 * 若各自 useEffect 拉一次，切一次智能体就是 4 个并发请求、且四份数据可能不一致。
 * 按 agentId 收在这里后：<b>一次请求、各槽位读同一份</b>。
 *
 * <h3>★ 失败时返回空列表，绝不抛也不报错</h3>
 * 界面贡献是**装饰性**的 —— 插件坏了、接口 500 了，最坏的结果应该是"界面上少几个按钮"，
 * 而不是主界面报错或白屏。所以这里吞掉所有异常并留一个计数（便于诊断），
 * 唯一的例外是"权限不足"也不提示：那属于配置问题，让它在日志里，
 * 而不是在用户每次切智能体时弹一个红条。
 */
interface UiSlotState {
  /** 按智能体缓存。 */
  byAgent: Record<string, UiContribution[]>;
  /** 正在拉取的智能体集合（避免并发重复拉）。 */
  pending: Record<string, boolean>;
  /** 最近一次失败的智能体 → 原因（仅用于诊断，不用于界面提示）。 */
  lastError: Record<string, string>;

  /**
   * **当前正在对话的智能体**。
   *
   * <p>存在的理由：侧栏与页面级槽位<b>不在对话页里，拿不到它的 agentId 状态</b>，
   * 但插件是**按智能体挂载**的 —— 没有 agentId 就不知道该显示谁的界面元素。
   * 所以由对话页在切换时上报，全局槽位读它。</p>
   *
   * <p>放在这个 store 而不是 `appStore`：它是**槽位专用的概念**，
   * 别处不关心；放一起会让"当前智能体"这个语义在无关场景里显得突兀。</p>
   */
  currentAgentId: string | null;
  setCurrentAgent: (agentId: string | null) => void;

  /** 拉取（若已有缓存且非 force 则跳过）。 */
  load: (agentId: string, force?: boolean) => Promise<void>;
  /** 取某智能体某槽位下**可渲染**的贡献（已按 order 排序、已过滤认不出的槽位与类型）。 */
  at: (agentId: string | undefined, slot: SlotId) => UiContribution[];
  /** 清空（退出登录/切换租户时用）。 */
  clear: () => void;
}

export const useUiSlotStore = create<UiSlotState>()((set, get) => ({
  byAgent: {},
  pending: {},
  lastError: {},
  currentAgentId: null,

  /** 切换当前智能体：记下来并**顺手拉它的界面贡献**（全局槽位随即可用）。 */
  setCurrentAgent: (agentId) => {
    if (get().currentAgentId === agentId) {
      return;
    }
    set({ currentAgentId: agentId });
    if (agentId) {
      void get().load(agentId);
    }
  },

  load: async (agentId, force) => {
    if (!agentId) {
      return;
    }
    const s = get();
    if (s.pending[agentId]) {
      return;   // 已在拉，别重复发
    }
    if (!force && s.byAgent[agentId]) {
      return;   // 有缓存
    }
    set((st) => ({ pending: { ...st.pending, [agentId]: true } }));
    try {
      const list = await uiContributions(agentId);
      set((st) => ({
        byAgent: { ...st.byAgent, [agentId]: list ?? [] },
        lastError: { ...st.lastError, [agentId]: '' },
      }));
    } catch (e) {
      /*
       * 静默降级为空列表：界面少几个插件元素，不影响任何核心功能。
       * 保留原因字符串是为了排查（"为什么插件的按钮没出现"）。
       */
      set((st) => ({
        byAgent: { ...st.byAgent, [agentId]: [] },
        lastError: { ...st.lastError, [agentId]: (e as Error).message || 'unknown' },
      }));
    } finally {
      set((st) => {
        const next = { ...st.pending };
        delete next[agentId];
        return { pending: next };
      });
    }
  },

  at: (agentId, slot) => {
    if (!agentId) {
      return EMPTY;
    }
    const list = get().byAgent[agentId];
    if (!list || list.length === 0) {
      return EMPTY;
    }
    const hit = list.filter(
      (c) => c.slot === slot && c.type && SUPPORTED_TYPES.has(c.type) && SUPPORTED_SLOTS.has(slot),
    );
    if (hit.length === 0) {
      return EMPTY;
    }
    // 稳定排序：order 小的在前，同 order 按 pluginId 保证多次渲染顺序一致（否则会跳动）
    return [...hit].sort((a, b) => {
      const d = (a.order ?? 0) - (b.order ?? 0);
      return d !== 0 ? d : a.pluginId.localeCompare(b.pluginId);
    });
  },

  clear: () => set({ byAgent: {}, pending: {}, lastError: {} }),
}));

/** 稳定的空数组引用：避免每次返回新数组导致组件无谓重渲染。 */
const EMPTY: UiContribution[] = [];
