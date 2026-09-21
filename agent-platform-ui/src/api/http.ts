// 统一 fetch 封装：相对路径 + X-Tenant-Id + 可选 Bearer；解包 ApiResponse 信封。
// 注意：/agent/run 返回裸 JSON（raw=true）；gateway 401 code=UNAUTHORIZED。

const TENANT_KEY = 'ap_tenant_id';
const TOKEN_KEY = 'ap_token';

export interface ApiResponse<T> {
  success: boolean;
  code: string;
  message?: string | null;
  data: T;
  traceId?: string;
  timestamp?: number;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export class ApiError extends Error {
  code: string;
  status: number;
  constructor(msg: string, code = 'ERROR', status = 0) {
    super(msg);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }
}

export function getTenantId(): string {
  return localStorage.getItem(TENANT_KEY) ?? 'default';
}
export function setTenantId(t: string) {
  localStorage.setItem(TENANT_KEY, t || 'default');
}
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}
export function setToken(t: string | null) {
  if (t) localStorage.setItem(TOKEN_KEY, t);
  else localStorage.removeItem(TOKEN_KEY);
}

function headers(): Record<string, string> {
  const h: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Tenant-Id': getTenantId(),
  };
  const t = getToken();
  if (t) h['Authorization'] = `Bearer ${t}`;
  return h;
}

async function unwrap<T>(res: Response, raw = false): Promise<T> {
  if (res.status === 401) {
    /*
     * token 失效（过期 / 服务端换了 JWT_SECRET / 账号被停用）时，除了报错还要**把用户送回登录页**。
     * 否则界面会停在"每个请求都失败但没人说明原因"的状态，用户只能靠刷新去猜。
     *
     * 这里用 location.hash 而不是 react-router 的 navigate：本文件是纯 fetch 封装，
     * 刻意不依赖 React（也被非组件代码调用），而项目用的是 HashRouter，改 hash 即完成跳转。
     * 已经在登录页时不再重复赋值，避免把用户正在输入的密码清掉。
     */
    setToken(null);
    if (!window.location.hash.startsWith('#/login')) {
      window.location.hash = '#/login';
    }
    /*
     * 但**登录接口自己的 401 不是 token 失效**，而是"用户名或密码错误"。
     * 那种情况必须放行到下面的通用错误分支，让用户看到后端原话
     * —— 否则会被改写成"登录已失效，请重新登录"，正好把最该看到的信息盖掉，
     * 用户只能对着正确的账号反复试（后端日志里能看到几十次重复的 401）。
     */
    if (!res.url.includes('/auth/login')) {
      throw new ApiError('登录已失效，请重新登录', 'UNAUTHORIZED', 401);
    }
  }
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const msg = (body && (body as ApiResponse<unknown>).message) || `HTTP ${res.status}`;
    const code = (body && (body as ApiResponse<unknown>).code) || 'HTTP_ERROR';
    throw new ApiError(msg, code, res.status);
  }
  if (raw) return body as T; // /agent/run 直接返回裸响应体
  // 统一信封解包
  if (body && typeof body === 'object' && 'success' in body) {
    const api = body as ApiResponse<T>;
    if (!api.success) throw new ApiError(api.message ?? api.code ?? '服务错误', api.code, res.status);
    return api.data;
  }
  return body as T;
}

export const http = {
  get: <T>(url: string) => fetch(url, { headers: headers() }).then((r) => unwrap<T>(r)),
  post: <T>(url: string, data?: unknown, raw = false) =>
    fetch(url, { method: 'POST', headers: headers(), body: JSON.stringify(data ?? {}) }).then((r) =>
      unwrap<T>(r, raw),
    ),
  put: <T>(url: string, data?: unknown) =>
    fetch(url, { method: 'PUT', headers: headers(), body: JSON.stringify(data ?? {}) }).then((r) =>
      unwrap<T>(r),
    ),
  patch: <T>(url: string, data?: unknown) =>
    fetch(url, { method: 'PATCH', headers: headers(), body: JSON.stringify(data ?? {}) }).then((r) =>
      unwrap<T>(r),
    ),
  delete: <T>(url: string) => fetch(url, { method: 'DELETE', headers: headers() }).then((r) => unwrap<T>(r)),
};