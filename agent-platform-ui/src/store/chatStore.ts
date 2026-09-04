// 对话页全局状态：把「当前对话」提升到 store 并持久化到 localStorage，
// 这样切换页面/刷新都不会清空聊天历史，直到用户点「新对话」才归档进会话历史。
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface ChatMsg {
  role: 'user' | 'assistant';
  content: string;
}

interface ChatState {
  // 按 agentId 分别保留「当前进行中的对话」。
  // 单条会话结构也兼容跨页：{ agentId: msg[] }
  byAgent: Record<string, ChatMsg[]>;
  /** 追加一条消息（含用户消息与助手回复）。 */
  append: (agentId: string, msg: ChatMsg) => void;
  /** 覆盖某一智能体的消息列表（流式增量用）。 */
  replace: (agentId: string, msgs: ChatMsg[]) => void;
  /** 取某智能体的当前消息。 */
  msgsOf: (agentId: string | undefined) => ChatMsg[];
  /** 开始新对话：清空该智能体的本地历史（旧的已由调用方存入会话历史）。 */
  reset: (agentId: string) => void;
}

const EMPTY: ChatMsg[] = [];

export const useChatStore = create<ChatState>()(
  persist(
    (set, get) => ({
      byAgent: {},
      append: (agentId, msg) =>
        set((s) => ({
          byAgent: {
            ...s.byAgent,
            [agentId]: [...(s.byAgent[agentId] ?? []), msg],
          },
        })),
      replace: (agentId, msgs) =>
        set((s) => ({ byAgent: { ...s.byAgent, [agentId]: msgs } })),
      msgsOf: (agentId) => (agentId ? get().byAgent[agentId] ?? EMPTY : EMPTY),
      reset: (agentId) =>
        set((s) => {
          const next = { ...s.byAgent };
          delete next[agentId];
          return { byAgent: next };
        }),
    }),
    { name: 'ap_chat_history' },
  ),
);
