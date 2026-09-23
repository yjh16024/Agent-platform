import { http } from './http';

/**
 * 工具审批：有副作用的工具调用（改文件等）**必须先经人放行**才执行。
 *
 * <p>为什么工具不直接执行，而要绕这一道：工作区约束能挡住"写到工作区外"，
 * 但**挡不住"把工作区里的东西改坏"** —— 后者只能靠人看一眼再放行。
 * 没有这道闸门，写工具就等于"一个能删任何文件的盒子"。</p>
 *
 * <p>审批人由后端从 token 注入的 {@code X-User-Id} 决定，前端**不传用户参数**，
 * 且后端硬校验"只能批自己提交的"。</p>
 */

export type ApprovalStatus = 'pending' | 'approved' | 'rejected' | 'expired';

export interface ApprovalItem {
  approvalId: string;
  toolName: string;
  /** 给用户看的影响摘要（不必读 JSON 去猜这次要改什么） */
  summary?: string | null;
  /** 待执行参数的 JSON 快照 */
  toolArgs?: string | null;
  status: ApprovalStatus;
  statusLabel: string;
  agentId?: string | null;
  sessionId?: string | null;
  /** 批准后的执行结果摘要（成功） */
  result?: string | null;
  /** 失败原因 / 拒绝理由 */
  errorMsg?: string | null;
  decidedBy?: string | null;
  decidedAt?: string | null;
  createdAt: string;
  /** 是否留有改前快照（没有快照就没有可回滚的内容） */
  hasSnapshot?: boolean;
  /** 回滚时间；有值表示已回滚过 */
  rolledBackAt?: string | null;
  /** 后端算好的判据：已批准 + 未回滚 + 执行成功 ⇒ 可回滚 */
  rollbackable?: boolean;
}

export interface ApprovalPage {
  items: ApprovalItem[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** 审批列表（status 为空表示全部）。 */
export function listApprovals(opts: { status?: ApprovalStatus; page?: number; size?: number } = {}) {
  const p = new URLSearchParams();
  if (opts.status) p.set('status', opts.status);
  p.set('page', String(opts.page ?? 0));
  p.set('size', String(opts.size ?? 20));
  return http.get<ApprovalPage>(`/api/v1/tool-approvals?${p.toString()}`);
}

/** 待审批条数（角标轮询用）。 */
export function pendingApprovalCount() {
  return http.get<{ count: number }>('/api/v1/tool-approvals/pending-count');
}

/** 批准并立即执行；返回更新后的记录（含执行结果或失败原因）。 */
export function approveToolCall(approvalId: string) {
  return http.post<ApprovalItem>(
    `/api/v1/tool-approvals/${encodeURIComponent(approvalId)}/approve`,
    {},
  );
}

/** 拒绝（不执行）。 */
export function rejectToolCall(approvalId: string, reason?: string) {
  return http.post<ApprovalItem>(
    `/api/v1/tool-approvals/${encodeURIComponent(approvalId)}/reject`,
    { reason },
  );
}

/**
 * 回滚：把文件恢复到这次操作**之前**的状态。
 *
 * <p>这是"敢让智能体改文件"的前提 —— 没有它，每次批准都是单向门。
 * 回滚是按**快照还原**（不是反向执行一次操作），所以在批准之后又手动改过文件时，
 * 结果依然是确定的：回到快照那一刻。</p>
 */
export function rollbackToolCall(approvalId: string) {
  return http.post<ApprovalItem>(
    `/api/v1/tool-approvals/${encodeURIComponent(approvalId)}/rollback`,
    {},
  );
}

/** 把超过保留期仍未处理的申请标记为过期（安全阀：防止陈旧申请被误批准）。 */
export function expireStaleApprovals() {
  return http.delete<{ expired: number }>('/api/v1/tool-approvals/expire-stale');
}
