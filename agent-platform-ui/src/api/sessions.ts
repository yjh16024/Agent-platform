import { http, PageResult } from './http';
import { SessionSummary, SessionDetail, SessionMessage } from './types';

export function createSession(body: { agentId?: string; userId?: string; title?: string }) {
  return http.post<SessionSummary>('/api/v1/sessions', body);
}

export function listSessions(page = 0, size = 20, agentId?: string, userId?: string) {
  const p = new URLSearchParams({ page: String(page), size: String(size) });
  if (agentId) p.set('agentId', agentId);
  if (userId) p.set('userId', userId);
  return http.get<PageResult<SessionSummary>>(`/api/v1/sessions?${p.toString()}`);
}

export function getSession(sessionId: string) {
  return http.get<SessionDetail>(`/api/v1/sessions/${sessionId}`);
}

export function sessionMessages(sessionId: string) {
  return http.get<SessionMessage[]>(`/api/v1/sessions/${sessionId}/messages`);
}

export function updateSession(sessionId: string, body: { title?: string; status?: string }) {
  return http.patch<SessionSummary>(`/api/v1/sessions/${sessionId}`, body);
}

/** 删除会话（后端物理删除，含其全部消息）。 */
export function archiveSession(sessionId: string) {
  return http.delete<void>(`/api/v1/sessions/${sessionId}`);
}

export function clearSession(sessionId: string) {
  return http.delete<void>(`/api/v1/sessions/${sessionId}/messages`);
}

export interface ImportConversationBody {
  agentId?: string;
  userId?: string;
  title?: string;
  messages: Array<{ role: string; content: string }>;
}

/**
 * 把整轮对话保存进会话历史（用户点「新对话」时调用）。
 */
export function importConversation(body: ImportConversationBody) {
  return http.post<SessionSummary>('/api/v1/sessions/import', body);
}
