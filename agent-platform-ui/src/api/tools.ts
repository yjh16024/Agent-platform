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

/** MCP 命令模板里的一个可填参数。 */
export interface McpTemplateParam {
  name: string;
  label: string;
  placeholder?: string;
  required?: boolean;
  hint?: string;
}

/**
 * MCP 命令模板（stdio 型常用 server 的启动命令）。
 *
 * 与 MCP 市场的区别：市场面向**远程 HTTP** 型；模板面向**需要本地拉起进程**的 stdio 型 ——
 * 后者在官方 registry 里只给包名、不给怎么运行，所以后台直接给出**人写好并核对过包名**的命令。
 */
export interface McpTemplateItem {
  id: string;
  title: string;
  description?: string;
  tags?: string[];
  /** 含 `{{param}}` 占位符的启动命令 */
  commandTemplate: string[];
  parameters?: McpTemplateParam[];
}

/** MCP 命令模板清单。 */
export function mcpTemplates() {
  return http.get<McpTemplateItem[]>('/api/v1/tools/mcp/templates');
}

/**
 * 注册 stdio MCP server（把一条本地命令作为 MCP server 拉起）。
 *
 * ⚠️ 这会在**本机执行命令** —— 平台里权限最敏感的操作之一。
 * 白名单（`agent-platform.mcp.stdio-allowed-commands`）只防手滑、**挡不住参数**
 * （`npx -y <任意包>` 照样执行），真正的约束是该端点继承的 `tool:write` 权限：
 * **模型无法自行发起，只有管理员能在界面上点**。
 */
export function registerStdioMcp(body: {
  command: string[];
  env?: Record<string, string>;
  dir?: string;
  timeout_seconds?: number;
}) {
  return http.post<Record<string, unknown>>('/api/v1/tools/mcp/stdio', body);
}
