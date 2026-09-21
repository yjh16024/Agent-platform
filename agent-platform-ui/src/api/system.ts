import { http } from './http';

/*
 * 用户 / 角色 / 权限点管理接口封装。
 *
 * 出入参遵循后端契约：**强类型 record 用 camelCase**（与 agents / sessions 等一致）。
 * 注意与 auth.ts 的差异：那边是历史遗留的自由 Map 出参（snake_case），不要照抄到这里。
 */

export type UserStatus = 'active' | 'disabled';

export interface SystemUser {
  userId: string;
  username: string;
  displayName?: string | null;
  email?: string | null;
  status: UserStatus;
  roleCodes: string[];
  createdAt?: string | null;
}

export interface SystemRole {
  roleId: string;
  roleCode: string;
  roleName: string;
  description?: string | null;
  /** 内置角色（admin/operator/viewer）：不可删除。 */
  builtin: boolean;
  permCodes: string[];
}

export interface PermissionItem {
  code: string;
  name: string;
}

export interface PermissionGroup {
  group: string;
  items: PermissionItem[];
}

export interface CreateUserBody {
  username: string;
  password: string;
  displayName?: string;
  email?: string;
  roleCodes?: string[];
}

export interface UpdateUserBody {
  displayName?: string;
  email?: string;
  status?: UserStatus;
}

export interface SaveRoleBody {
  roleCode?: string;
  roleName?: string;
  description?: string;
  permCodes?: string[];
}

// ------------------------------------------------------------------ 用户

export function listUsers() {
  return http.get<SystemUser[]>('/api/v1/system/users');
}

export function createUser(body: CreateUserBody) {
  return http.post<SystemUser>('/api/v1/system/users', body);
}

export function updateUser(userId: string, body: UpdateUserBody) {
  return http.patch<SystemUser>(`/api/v1/system/users/${userId}`, body);
}

/** 覆盖式分配角色（传全量角色编码）。 */
export function assignRoles(userId: string, roleCodes: string[]) {
  return http.post<SystemUser>(`/api/v1/system/users/${userId}/roles`, { roleCodes });
}

export function resetPassword(userId: string, password: string) {
  return http.post<void>(`/api/v1/system/users/${userId}/password`, { password });
}

export function deleteUser(userId: string) {
  return http.delete<void>(`/api/v1/system/users/${userId}`);
}

// ------------------------------------------------------------------ 角色与权限

export function listRoles() {
  return http.get<SystemRole[]>('/api/v1/system/roles');
}

export function listPermissions() {
  return http.get<PermissionGroup[]>('/api/v1/system/permissions');
}

export function createRole(body: SaveRoleBody) {
  return http.post<SystemRole>('/api/v1/system/roles', body);
}

/** 更新角色（含权限集）。后端保存后会立即失效权限缓存，无需重启。 */
export function updateRole(roleId: string, body: SaveRoleBody) {
  return http.put<SystemRole>(`/api/v1/system/roles/${roleId}`, body);
}

export function deleteRole(roleId: string) {
  return http.delete<void>(`/api/v1/system/roles/${roleId}`);
}
