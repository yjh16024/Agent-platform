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

/**
 * 卸载插件。
 *
 * **注意：这是「级联卸载」** —— 该插件在**所有**智能体上的挂载都会被一并取消
 * （后端会同时清理绑定表与各智能体的 capabilities），避免出现"A 卸载成功、B 还显示已挂载
 * 但其实已经不生效"的错位状态。所以调用前务必先用 {@link pluginAttachments} 查影响面，
 * 若有其它智能体在用，要让用户明确确认。
 */
export function detachPlugin(pluginId: string, agentId: string) {
  return http.post<{ plugin_id: string; detached_agents: string[]; count: number }>(
    `/api/v1/plugins/${pluginId}/detach?agentId=${encodeURIComponent(agentId)}`,
    {},
  );
}

/** 一条插件挂载记录（卸载影响面提示用）。 */
export interface PluginAttachment {
  agentId: string;
  agentName?: string;
  enabled?: boolean;
}

/** 查某插件被哪些智能体挂载。 */
export function pluginAttachments(pluginId: string) {
  return http.get<PluginAttachment[]>(`/api/v1/plugins/${encodeURIComponent(pluginId)}/attachments`);
}

export function attachments(agentId: string) {
  return http.get<Record<string, unknown>[]>(
    `/api/v1/plugins/attachments?agentId=${encodeURIComponent(agentId)}`,
  );
}

/**
 * 导入插件：body 为原始 plugin manifest（YAML/JSON 文本），content-type text/plain。
 *
 * @param artifactUri 可选，插件制品地址（`file:/绝对路径` 或 `http(s)://` 直链）。
 *                    外部 Java 插件**必须**提供制品才能真正挂载；留空则只登记元数据。
 */
export async function importPlugin(manifestText: string, artifactUri?: string) {
  const qs = artifactUri ? `?artifactUri=${encodeURIComponent(artifactUri)}` : '';
  const h: Record<string, string> = {
    'Content-Type': 'text/plain',
    'X-Tenant-Id': getTenantId(),
  };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  const res = await fetch(`/api/v1/plugins/import${qs}`, { method: 'POST', headers: h, body: manifestText });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error((body && (body as { message?: string }).message) || `HTTP ${res.status}`);
  return (body as { data?: PluginDef }).data;
}

/**
 * 上传插件包（multipart）：manifest 作为文本字段 + jar 作为文件字段。
 *
 * jar 由后端落到 `agent-platform.plugin.artifact-dir`（默认 `./data/plugins`）并写入 `artifact_uri`，
 * 随后即可在详情里「挂载到智能体」热加载 —— 这是外部插件真正能用的完整链路。
 */
export async function uploadPlugin(manifestText: string, jar: File) {
  const fd = new FormData();
  // 注意：纯字符串 append 才是「普通文本字段」；带 filename 的 Blob 会变成文件 part，后端拿不到 String
  fd.append('manifest', manifestText);
  fd.append('jar', jar, jar.name);
  const h: Record<string, string> = { 'X-Tenant-Id': getTenantId() };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  const res = await fetch('/api/v1/plugins/upload', { method: 'POST', headers: h, body: fd });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error((body && (body as { message?: string }).message) || `HTTP ${res.status}`);
  return (body as { data?: PluginDef }).data;
}

/** 删除插件（仅租户自有插件，内置平台插件返回 403）。 */
export function deletePlugin(pluginId: string) {
  return http.delete<void>(`/api/v1/plugins/${pluginId}`);
}
