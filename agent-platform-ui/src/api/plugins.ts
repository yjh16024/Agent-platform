import { http, getTenantId, getToken } from './http';
import { PluginDef } from './types';

export function marketplace() {
  return http.get<PluginDef[]>('/api/v1/plugins/marketplace');
}

export function pluginDetail(id: string) {
  return http.get<PluginDef>(`/api/v1/plugins/${id}`);
}

// attach 的 body 是 snake_case：agent_id
export function attachPlugin(
  pluginId: string,
  agentId: string,
  extra?: { version?: string; config?: Record<string, unknown>; enabled?: boolean },
) {
  return http.post<unknown>(`/api/v1/plugins/${pluginId}/attach`, { agent_id: agentId, ...extra });
}

export function detachPlugin(pluginId: string, agentId: string) {
  return http.post<void>(`/api/v1/plugins/${pluginId}/detach?agentId=${encodeURIComponent(agentId)}`, {});
}

export function attachments(agentId: string) {
  return http.get<Record<string, unknown>[]>(
    `/api/v1/plugins/attachments?agentId=${encodeURIComponent(agentId)}`,
  );
}

/**
 * 导入插件：body 为原始 plugin manifest（YAML/JSON 文本），content-type text/plain。
 */
export async function importPlugin(manifestText: string) {
  const h: Record<string, string> = {
    'Content-Type': 'text/plain',
    'X-Tenant-Id': getTenantId(),
  };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  const res = await fetch('/api/v1/plugins/import', { method: 'POST', headers: h, body: manifestText });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error((body && (body as { message?: string }).message) || `HTTP ${res.status}`);
  return (body as { data?: PluginDef }).data;
}

/** 删除插件（仅租户自有插件，内置平台插件返回 403）。 */
export function deletePlugin(pluginId: string) {
  return http.delete<void>(`/api/v1/plugins/${pluginId}`);
}
