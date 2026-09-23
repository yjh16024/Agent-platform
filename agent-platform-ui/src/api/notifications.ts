import { http } from './http';

/**
 * 站内通知：**面向人的提醒**（与"运行日志"记系统行为、"操作日志"记人的操作区分开）。
 *
 * <p>收件人由后端从 token 注入的 {@code X-User-Id} 决定，前端<b>不传用户参数</b> ——
 * 通知是私信，能不能看自己以外的通知不该由调用方说了算。</p>
 */

/** 通知类型（对应后端 NotificationType）。 */
export type NotificationType = 'system' | 'task' | 'quota' | 'security';

/** 通知级别（对应后端 NotificationLevel）。 */
export type NotificationLevel = 'info' | 'warn' | 'error';

export interface NotificationItem {
  notification_id: string;
  type: NotificationType;
  level: NotificationLevel;
  title: string;
  content?: string | null;
  /** 点击跳转的前端路径；空则不可点 */
  link?: string | null;
  /** 是否已读（后端由 read_at 是否为空推导） */
  read: boolean;
  read_at?: string | null;
  created_at: string;
}

export interface NotificationPage {
  items: NotificationItem[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** 收件箱分页。 */
export function listNotifications(
  opts: { unreadOnly?: boolean; page?: number; size?: number } = {},
) {
  const p = new URLSearchParams();
  if (opts.unreadOnly) p.set('unreadOnly', 'true');
  p.set('page', String(opts.page ?? 0));
  p.set('size', String(opts.size ?? 20));
  return http.get<NotificationPage>(`/api/v1/notifications?${p.toString()}`);
}

/**
 * 未读数 —— 侧栏角标用。
 *
 * <p>刻意与列表分开：角标只需要一个数字，让轮询走 count 而不是拉整页数据。
 * 调用方应做节流与"页面不可见时跳过"（见 AppLayout 的轮询实现）。</p>
 */
export function unreadCount() {
  return http.get<{ count: number }>('/api/v1/notifications/unread-count');
}

/** 标记单条已读。 */
export function markRead(notificationId: string) {
  return http.post<{ updated: boolean }>(
    `/api/v1/notifications/${encodeURIComponent(notificationId)}/read`,
    {},
  );
}

/** 全部标记已读。返回实际改动的条数。 */
export function markAllRead() {
  return http.post<{ updated: number }>('/api/v1/notifications/read-all', {});
}

/** 删除单条。 */
export function deleteNotification(notificationId: string) {
  return http.delete<{ removed: boolean }>(
    `/api/v1/notifications/${encodeURIComponent(notificationId)}`,
  );
}

/** 按保留期清理（需 notice:manage，界面上只在有该权限时提供入口）。 */
export function purgeNotifications(retentionDays = 30) {
  return http.delete<{ deleted: number; retention_days: number }>(
    `/api/v1/notifications/purge?retentionDays=${retentionDays}`,
  );
}
