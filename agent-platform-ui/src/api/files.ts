import { http, getTenantId, getToken } from './http';
import { FileAsset } from './types';

export function listFiles() {
  return http.get<FileAsset[]>('/api/v1/files');
}

export function fileDetail(fileId: string) {
  return http.get<FileAsset>(`/api/v1/files/${fileId}`);
}

export async function uploadFile(file: File, type?: string) {
  const fd = new FormData();
  fd.append('file', file);
  if (type) fd.append('type', type);
  const h: Record<string, string> = { 'X-Tenant-Id': getTenantId() };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  const res = await fetch('/api/v1/files/upload', { method: 'POST', headers: h, body: fd });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error((body && (body as { message?: string }).message) || `HTTP ${res.status}`);
  return (body as { data?: FileAsset }).data;
}

// 下载文件内容（携带 X-Tenant-Id / Bearer），按 Content-Disposition 文件名落盘
export async function downloadFile(fileId: string) {
  const h: Record<string, string> = { 'X-Tenant-Id': getTenantId() };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  const res = await fetch(`/api/v1/files/${fileId}/download`, { method: 'GET', headers: h });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const blob = await res.blob();
  let fileName = decodeURIComponent(
    (res.headers.get('content-disposition') ?? '').split('filename=')[1]?.replace(/"/g, '') || fileId,
  );
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(a.href);
}

export function deleteFile(fileId: string) {
  return http.delete<void>(`/api/v1/files/${fileId}`);
}