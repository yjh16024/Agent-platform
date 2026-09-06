import { http, getTenantId, getToken } from './http';
import { SkillDef } from './types';

export function listSkills() {
  return http.get<SkillDef[]>('/api/v1/skills');
}

export function getSkill(skillId: string) {
  return http.get<SkillDef>(`/api/v1/skills/${skillId}`);
}

export interface SkillBody {
  name?: string;
  version?: string;
  description?: string;
  prompt?: string;
  tools?: string[];
  source?: string;
}

/** 按字段新建 Skill（写入标准目录 skills/<name>/SKILL.md）。 */
export function createSkill(body: SkillBody) {
  return http.post<SkillDef>('/api/v1/skills', body);
}

/** 更新 Skill（编辑，目录型同步回写 SKILL.md）。 */
export function updateSkill(skillId: string, body: SkillBody) {
  return http.put<SkillDef>(`/api/v1/skills/${skillId}`, body);
}

/** skills 根目录绝对路径。 */
export function getSkillsDir() {
  return http.get<{ path: string }>('/api/v1/skills/dir');
}

/** 在系统文件管理器中打开 skills 目录。 */
export function openSkillsFolder() {
  return http.post<{ path: string; opened: boolean; enabled: boolean }>('/api/v1/skills/open-folder', {});
}

/** 扫描 skills 目录并同步入库。 */
export function syncSkills() {
  return http.post<{
    dir: string; total: number; created: number; updated: number;
    missing_in_folder: number; names: string[];
  }>('/api/v1/skills/sync', {});
}

/** 导入 skills 目录下某个子目录。 */
export function importSkillFolder(dir: string) {
  return http.post<SkillDef>('/api/v1/skills/import-folder', { dir });
}

/** 上传 .zip Skill 包 或 单个 SKILL.md（multipart）。 */
export async function uploadSkill(file: File) {
  const fd = new FormData();
  fd.append('file', file);
  const h: Record<string, string> = { 'X-Tenant-Id': getTenantId() };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  const res = await fetch('/api/v1/skills/upload', { method: 'POST', headers: h, body: fd });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error((body && (body as { message?: string }).message) || `HTTP ${res.status}`);
  return (body as { data?: SkillDef[] }).data ?? [];
}

/** Skill 目录内文件列表（相对路径）。 */
export function skillFiles(skillId: string) {
  return http.get<string[]>(`/api/v1/skills/${skillId}/files`);
}

/** 读取 Skill 目录内某文件内容。 */
export function readSkillFile(skillId: string, path: string) {
  return http.get<{ path: string; content: string }>(
    `/api/v1/skills/${skillId}/file?path=${encodeURIComponent(path)}`,
  );
}

// 导入：body 为原始 manifest 文本（@RequestBody String），需原始 POST
export async function importSkill(manifestText: string, source = 'local') {
  const h: Record<string, string> = {
    'Content-Type': 'text/plain',
    'X-Tenant-Id': getTenantId(),
  };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  const res = await fetch(`/api/v1/skills/import?source=${encodeURIComponent(source)}`, {
    method: 'POST',
    headers: h,
    body: manifestText,
  });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error((body && (body as { message?: string }).message) || `HTTP ${res.status}`);
  return (body as { data?: SkillDef }).data;
}

export function deleteSkill(skillId: string) {
  return http.delete<void>(`/api/v1/skills/${skillId}`);
}
