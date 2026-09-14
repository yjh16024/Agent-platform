import { http } from './http';
import { ToolInfo } from './types';

export function listTools() {
  return http.get<ToolInfo[]>('/api/v1/tools');
}

export function invokeTool(name: string, args: Record<string, unknown>) {
  return http.post<Record<string, unknown>>(`/api/v1/tools/${name}/invoke`, args);
}

export function registerTool(body: {
  name: string;
  description?: string;
  endpoint: string;
  method?: string;
  /** 入参 JSON Schema（JSON 字符串或对象）。 */
  parameters?: string | Record<string, unknown>;
}) {
  return http.post<Record<string, unknown>>('/api/v1/tools/register', body);
}

/** 修改 HTTP 注册工具（name 不可变，改 description/endpoint/method/parameters）。 */
export function updateTool(
  name: string,
  body: { description?: string; endpoint?: string; method?: string; parameters?: string | Record<string, unknown> },
) {
  return http.put<Record<string, unknown>>(`/api/v1/tools/${encodeURIComponent(name)}`, body);
}

/** 删除自定义工具（内置工具受后端保护）。 */
export function unregisterTool(name: string) {
  return http.delete<void>(`/api/v1/tools/${encodeURIComponent(name)}`);
}

/** MCP 市场里的一个服务器（来自官方 MCP Registry，仅含远程 HTTP 型）。 */
export interface McpMarketItem {
  name: string;
  title?: string;
  description?: string;
  version?: string;
  /** 建议使用的接入地址 */
  server_url: string;
  /** 该服务器提供的全部 HTTP 接入地址 */
  remotes?: string[];
}

/** MCP 市场：列出官方 Registry 中支持远程 HTTP 的服务器。 */
export function mcpMarket(q?: string, limit?: number) {
  const params = new URLSearchParams();
  if (q) params.set('q', q);
  if (limit) params.set('limit', String(limit));
  const qs = params.toString();
  return http.get<McpMarketItem[]>(`/api/v1/tools/mcp/market${qs ? `?${qs}` : ''}`);
}

/** 注册 MCP 服务器（Remote HTTP / 本地进程内 / 沙箱）。 */
export function registerMcp(body: {
  server_url?: string;
  api_key?: string;
  headers?: Record<string, string>;
  name?: string;
}) {
  return http.post<Record<string, unknown>>('/api/v1/tools/mcp', body);
}

/** HTTP 工具市场里的一个条目（内置精选的免 Key 公开 API）。 */
export interface ToolMarketItem {
  id: string;
  name: string;
  title?: string;
  description?: string;
  endpoint: string;
  method?: string;
  tags?: string[];
  requiresKey?: boolean;
  installed?: boolean;
}

/** HTTP 工具市场：列出内置精选条目。 */
export function toolMarket() {
  return http.get<ToolMarketItem[]>('/api/v1/tools/market');
}

/** HTTP 工具市场：一键注册为平台 HTTP 工具。 */
export function installToolFromMarket(id: string) {
  return http.post<{ name: string; endpoint: string; method: string }>(
    `/api/v1/tools/market/${encodeURIComponent(id)}/install`,
    {},
  );
}
