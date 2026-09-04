import { http } from './http';
import { QuotaStatus } from './types';

export function listQuotas() {
  return http.get<QuotaStatus[]>('/api/v1/quotas');
}

// 配置限额：body 为自由 Map，遵循 snake_case（quota_type / limit / period）
export function setQuota(body: { quota_type: string; limit: number; period?: string }) {
  return http.post<Record<string, unknown>>('/api/v1/quotas', body);
}