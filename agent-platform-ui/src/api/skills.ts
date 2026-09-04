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

/** 按字段新建 Skill（后端自动生成 skillId 与 manifest）。 */
export function createSkill(body: SkillBody) {
  return http.post<SkillDef>('/api/v1/skills', body);
}

/** 更新 Skill（编辑）。 */
export function updateSkill(skillId: string, body: SkillBody) {
  return http.put<SkillDef>(`/api/v1/skills/${skillId}`, body);
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
