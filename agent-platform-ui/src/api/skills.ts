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

/** 技能市场里的一个技能（来自官方 Agent Skills 仓库）。 */
export interface MarketSkill {
  name: string;
  title?: string;
  description?: string;
  /** 是否已装到本地 skills 目录 */
  installed?: boolean;
  source?: string;
  dir?: string;
  /** 该技能在仓库中的目录（安装 / 预览时要用） */
  path?: string;
  /** 所属分支 */
  branch?: string;
  /** SKILL.md 的仓库内路径 */
  skillFile?: string;
}

/** 技能市场：列出官方仓库（github.com/anthropics/skills）的可安装技能。 */
export function marketSkills() {
  return http.get<MarketSkill[]>('/api/v1/skills/market');
}

/** 技能市场：一键安装 —— 下载到本地 skills 目录并自动同步入库。 */
export function installMarketSkill(name: string) {
  return http.post<{
    skill: string;
    dir: string;
    files: number;
    sync: Record<string, unknown>;
  }>(`/api/v1/skills/market/${encodeURIComponent(name)}/install`, {});
}

/** 一条取件通道的探测结果。 */
export interface ChannelProbe {
  id: string;
  group?: string;
  label: string;
  ok: boolean;
  preferred?: boolean;
  ms?: number;
  error?: string;
}

/** 网络诊断：并行探测 jsDelivr / 各加速代理 / 直连哪条通。 */
export function diagnoseSkillChannels() {
  return http.get<ChannelProbe[]>('/api/v1/skills/market/diagnose');
}

/** 读取任意仓库地址下的技能清单（只扫描，不安装）。 */
export function scanSkillRepo(url: string, refresh = false) {
  return http.post<{
    repo: string;
    branch: string;
    subPath?: string;
    count: number;
    skills: MarketSkill[];
    channel?: string;
  }>('/api/v1/skills/market/repo/scan', { url, refresh });
}

/** 从任意仓库安装其中一个技能。 */
export function installSkillFromRepo(url: string, path: string, name?: string) {
  return http.post<{
    skill: string;
    dir: string;
    files: number;
    source: string;
    branch: string;
    path: string;
    sync: Record<string, unknown>;
  }>('/api/v1/skills/market/repo/install', { url, path, name });
}

/** 预览任意仓库里的文件内容（通常用来先看 SKILL.md）。 */
export function readSkillRepoFile(url: string, path: string) {
  return http.post<{
    repo: string;
    branch: string;
    path: string;
    size: number;
    binary: boolean;
    truncated: boolean;
    content: string;
  }>('/api/v1/skills/market/repo/file', { url, path });
}
