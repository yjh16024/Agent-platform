import { http } from './http';

/**
 * 操作日志（审计）接口。
 *
 * <p>与 `ops.ts` 的运行日志区分开：那边是系统运行日志，这边是**人的操作**。</p>
 *
 * <p>出入参 camelCase（强类型 record），与 system.ts / dict.ts 一致。</p>
 */

export interface AuditLogView {
  auditId: string;
  userId?: string | null;
  username?: string | null;
  /** 操作**当时**的角色快照（逗号分隔），不是现在的角色。 */
  roles?: string | null;
  action: string;
  targetType?: string | null;
  targetId?: string | null;
  method: string;
  uri: string;
  httpStatus?: number | null;
  success: boolean;
  errorMsg?: string | null;
  ip?: string | null;
  userAgent?: string | null;
  durationMs?: number | null;
  detail?: string | null;
  createdAt?: string | null;
}

export interface AuditPage {
  items: AuditLogView[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface AuditQuery {
  action?: string;
  userId?: string;
  success?: boolean;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

function qs(params: Record<string, unknown>): string {
  const parts: string[] = [];
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') {
      return;
    }
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  });
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 分页查询（全部条件可选）。 */
export function listAudits(query: AuditQuery = {}) {
  return http.get<AuditPage>(`/api/v1/audits${qs(query as Record<string, unknown>)}`);
}

/** 某个对象的操作历史 —— 「这个智能体被谁改过」。 */
export function auditTimeline(targetType: string, targetId: string) {
  return http.get<AuditLogView[]>(
    `/api/v1/audits/${encodeURIComponent(targetType)}/${encodeURIComponent(targetId)}/timeline`,
  );
}

export function countAudits() {
  return http.get<number>('/api/v1/audits/count');
}

/** 清理保留期之前的记录（要求 audit:manage，只有 admin 有）。 */
export function purgeAudits(keepDays: number) {
  return http.post<{ deleted: number; before: string; keepDays: number }>('/api/v1/audits/purge', {
    keepDays,
  });
}
