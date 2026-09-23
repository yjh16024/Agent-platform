// 对话页全局状态：把「当前对话」提升到 store 并持久化到 localStorage，
// 这样切换页面/刷新都不会清空聊天历史，直到用户点「新对话」才归档进会话历史。
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { ToolCallInfo } from '../api/run';

export interface ChatMsg {
  role: 'user' | 'assistant';
  content: string;
  /** RAG 引用溯源（来源文档 + 页码），仅当该轮智能体检索到知识库时有值。 */
  refs?: Array<{ chunkId?: string; source?: string; page?: number | null; score?: number }>;
  /**
   * 本轮的工具调用（工具调用可视化），仅助手消息、且确实调过工具时才有。
   *
   * <p>它会被 persist 到 localStorage，这是有意的：切页或刷新后仍能看到
   * "上一轮是怎么查出来的"。体积可控 —— 入参与结果都由后端截断过。</p>
   */
  toolCalls?: ToolCallInfo[];
}

interface ChatState {
  // 按 agentId 分别保留「当前进行中的对话」。
  // 单条会话结构也兼容跨页：{ agentId: msg[] }
  byAgent: Record<string, ChatMsg[]>;
  /**
   * 按 agentId 记住**运行时会话 ID**（2026-09-23 新增）。
   *
   * <p>这个字段之前不存在，代价很大：前端从不把 sessionId 传给后端，
   * 而后端的 `SessionService.resolve()` 在 sessionId 为空时**直接返回 null**
   * （不落库、不建会话），于是连锁失效 ——
   * 消息不落库、历史不回放、短期缓存/中期摘要拿不到会话、
   * 向量记忆压根没索引过、工具审批也无法按会话关联。
   * 即"四层记忆"实际从未起作用。</p>
   *
   * <p>规则：每个 agent 首次发消息时生成一个 UUID 并复用，直到用户点「新对话」。
   * 这样后端会话才是连续的一条线，记忆与审批才能挂上去。</p>
   */
  sessionByAgent: Record<string, string>;
  /** 取该智能体的会话 ID；没有则**生成并记住**一个（首轮就带上去，后端据此建会话）。 */
  sessionOf: (agentId: string) => string;
  /** 追加一条消息（含用户消息与助手回复）。 */
  append: (agentId: string, msg: ChatMsg) => void;
  /** 覆盖某一智能体的消息列表（流式增量用）。 */
  replace: (agentId: string, msgs: ChatMsg[]) => void;
  /** 取某智能体的当前消息。 */
  msgsOf: (agentId: string | undefined) => ChatMsg[];
  /** 开始新对话：清空该智能体的本地历史，并换一个新的会话 ID。 */
  reset: (agentId: string) => void;
}

const EMPTY: ChatMsg[] = [];

/**
 * 生成运行时会话 ID。
 *
 * <p>刻意加 `sess-` 前缀：会话 ID 会出现在日志、审批记录、快照目录名里，
 * 一眼能认出"这是前端发起的对话"比 `b3f2c8a1-...` 好读。
 * 后端只要求 ≤64 字符，不校验格式。</p>
 *
 * <p>用 `crypto.randomUUID()`（浏览器原生，密码学随机）而不是时间戳 + 随机数：
 * 会话 ID 是数据隔离的依据之一，可预测的 ID 意味着别人能猜到你的会话。</p>
 */
function newSessionId(): string {
  const uuid =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  return `sess-${uuid}`;
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get) => ({
      byAgent: {},
      sessionByAgent: {},
      sessionOf: (agentId) => {
        const existing = get().sessionByAgent[agentId];
        if (existing) {
          return existing;
        }
        // 首次为该智能体对话：生成并记住。
        // 注意这里必须**立即写回 store**（而不是只返回新值）：
        // 同一次 send() 里可能被调用两次（组装 payload 与保存 sessionId），
        // 不落盘两次就会拿到两个不同的 ID，而后端已经按第一个建了会话。
        const created = newSessionId();
        set((s) => ({ sessionByAgent: { ...s.sessionByAgent, [agentId]: created } }));
        return created;
      },
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
          // 换一个新会话 ID：新对话理应是一条新的后端会话（旧的那条已由调用方归档）
          const nextSession = { ...s.sessionByAgent };
          delete nextSession[agentId];
          return { byAgent: next, sessionByAgent: nextSession };
        }),
    }),
    { name: 'ap_chat_history' },
  ),
);
