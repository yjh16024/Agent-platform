import { http, getTenantId, getToken } from './http';
import { KnowledgeBase, RetrievalResult } from './types';

export function listKbs() {
  return http.get<KnowledgeBase[]>('/api/v1/knowledge-bases');
}

export function kbDetail(kbId: string) {
  return http.get<KnowledgeBase>(`/api/v1/knowledge-bases/${kbId}`);
}

// 创建知识库：后端用 @RequestParam（query 参数）
export function createKb(body: {
  name: string;
  description?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  chunkStrategy?: string;
}) {
  const p = new URLSearchParams({ name: body.name });
  if (body.description) p.set('description', body.description);
  if (body.chunkSize) p.set('chunkSize', String(body.chunkSize));
  if (body.chunkOverlap) p.set('chunkOverlap', String(body.chunkOverlap));
  if (body.chunkStrategy) p.set('chunkStrategy', body.chunkStrategy);
  return http.post<{ kbId?: string; name?: string }>(`/api/v1/knowledge-bases?${p.toString()}`, {});
}

/** 知识库下文档列表。 */
export function listDocuments(kbId: string) {
  return http.get<Array<Record<string, unknown>>>(
    `/api/v1/knowledge-bases/${encodeURIComponent(kbId)}/documents`,
  );
}

/** 浏览切分块（可按文档过滤）。 */
export function listChunks(kbId: string, docId?: string) {
  const p = new URLSearchParams();
  if (docId) p.set('docId', docId);
  const qs = p.toString();
  return http.get<Array<Record<string, unknown>>>(
    `/api/v1/knowledge-bases/${encodeURIComponent(kbId)}/chunks${qs ? `?${qs}` : ''}`,
  );
}

/** 删除单个文档（含 chunk 与向量）。 */
export function deleteDocument(docId: string) {
  return http.delete<void>(`/api/v1/knowledge-bases/documents/${encodeURIComponent(docId)}`);
}

// 上传文档：multipart（需要原始 fetch）
export async function uploadDoc(kbId: string, file: File) {
  const fd = new FormData();
  fd.append('file', file);
  const res = await fetch(`/api/v1/knowledge-bases/${kbId}/documents`, {
    method: 'POST',
    headers: buildHeaders(false),
    body: fd,
  });
  const body = await res.json().catch(() => null);
  if (!res.ok) throw new Error((body && (body as { message?: string }).message) || `HTTP ${res.status}`);
  return (body as { data?: number }).data;
}

export function searchKb(kbIds: string[], query: string, topK = 5) {
  const p = new URLSearchParams();
  kbIds.forEach((id) => p.append('kbIds', id));
  p.set('query', query);
  p.set('topK', String(topK));
  p.set('scoreThreshold', '0.0');
  p.set('rerank', 'true');
  return http.post<RetrievalResult[]>(`/api/v1/knowledge-bases/search?${p.toString()}`, {});
}

export function deleteKb(kbId: string) {
  return http.delete<void>(`/api/v1/knowledge-bases/${kbId}`);
}

function buildHeaders(json: boolean): Record<string, string> {
  const h: Record<string, string> = { 'X-Tenant-Id': getTenantId() };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  if (json) h['Content-Type'] = 'application/json';
  return h;
}
