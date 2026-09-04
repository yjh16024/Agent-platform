import { http } from './http';

export interface LoginResult {
  token?: string;
  expires_in?: number;
  tenant_id?: string;
  user_id?: string;
}

// 登录体是 snake_case：tenant_id / user_id
export function login(tenantId: string, userId = 'demo-user') {
  return http.post<LoginResult>('/api/v1/auth/login', { tenant_id: tenantId, user_id: userId });
}