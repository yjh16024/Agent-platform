import { http, PageResult } from './http';
import { AgentResponse, AgentVersion } from './types';

export function listAgents(q?: string, status?: string, page = 0, size = 20) {
  const p = new URLSearchParams({ page: String(page), size: String(size) });
  if (q) p.set('q', q);
  if (status) p.set('status', status);
  return http.get<PageResult<AgentResponse>>(`/api/v1/agents?${p.toString()}`);
}

export function getAgent(id: string) {
  return http.get<AgentResponse>(`/api/v1/agents/${id}`);
}

// body 用 camelCase（systemPrompt 等）
export function createAgent(data: Record<string, unknown>) {
  return http.post<AgentResponse>('/api/v1/agents', data);
}

export function updateAgent(id: string, data: Record<string, unknown>) {
  return http.put<AgentResponse>(`/api/v1/agents/${id}`, data);
}

export function deleteAgent(id: string) {
  return http.delete<void>(`/api/v1/agents/${id}`);
}

export function cloneAgent(id: string) {
  return http.post<AgentResponse>(`/api/v1/agents/${id}/clone`, {});
}

export function listVersions(id: string) {
  return http.get<AgentVersion[]>(`/api/v1/agents/${id}/versions`);
}

export function snapshotVersion(id: string) {
  return http.post<AgentVersion>(`/api/v1/agents/${id}/versions`, {});
}

export function publishAgent(id: string) {
  return http.post<AgentVersion>(`/api/v1/agents/${id}/publish`, {});
}

export function rollbackAgent(id: string, version: string) {
  return http.post<AgentVersion>(`/api/v1/agents/${id}/rollback/${version}`, {});
}

export function diffVersions(id: string, from: string, to: string) {
  return http.get<Record<string, unknown>>(
    `/api/v1/agents/${id}/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  );
}