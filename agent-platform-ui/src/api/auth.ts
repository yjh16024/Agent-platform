import { http } from './http';

/**
 * 登录结果。
 *
 * <p>字段保持 **snake_case** —— 这是后端 {@code AuthController} 一直以来的返回形态
 * （自由 Map 出参），不是笔误；改它就要同时动后端，而当前没人需要 camelCase。</p>
 */
export interface LoginResult {
  token?: string;
  expires_in?: number;
  tenant_id?: string;
  user_id?: string;
  /** RBAC 开启时返回：显示名与角色码（登录后可用于界面提示）。 */
  display_name?: string;
  roles?: string[];
}

/** 后端当前的鉴权模式。 */
export interface AuthMode {
  /** false = 后端不校验 token（桌面 embedded / 本地开发），前端**不应**强制登录。 */
  security_enabled: boolean;
  rbac_enabled: boolean;
}

/**
 * 登录换取 JWT。
 *
 * <p>RBAC 开启时后端按 {@code sys_user} 表校验 username/password；
 * 未开启时沿用「静态账号 或 演示模式」，此时这两个字段被忽略（传空串即可）。</p>
 */
export function login(username: string, password: string, tenantId = 'default') {
  return http.post<LoginResult>('/api/v1/auth/login', {
    username,
    password,
    tenant_id: tenantId,
  });
}

/**
 * 查后端鉴权模式。
 *
 * <p>该接口在后端白名单里可**匿名访问** —— 这是刻意的：登录页自己要靠它判断
 * "要不要显示登录表单"，如果它也需要 token，就会形成
 * 「没登录 → 拿不到模式 → 跳登录页 → 登录页又要先拿模式」的死锁。</p>
 */
export function getAuthMode() {
  return http.get<AuthMode>('/api/v1/auth/mode');
}

/** 当前登录用户的身份与权限。 */
export interface MeResult {
  user_id: string | null;
  tenant_id: string;
  /** token 里携带的角色码。 */
  roles: string[];
  /** 服务端按角色推导出的权限码 —— 前端的菜单显隐依据。 */
  perms: string[];
  security_enabled: boolean;
  rbac_enabled: boolean;
}

/**
 * 查当前用户信息（身份 + 角色 + 权限）。
 *
 * <p>与 {@link getAuthMode} 不同，它**不在匿名白名单里**，必须带 token ——
 * 因为权限集是后端过滤器解析 token 后写进请求属性的，匿名访问只会得到空集合。</p>
 */
export function getMe() {
  return http.get<MeResult>('/api/v1/auth/me');
}
