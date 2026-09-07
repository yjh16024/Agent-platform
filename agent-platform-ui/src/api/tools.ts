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
