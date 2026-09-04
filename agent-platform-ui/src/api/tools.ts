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
}) {
  return http.post<Record<string, unknown>>('/api/v1/tools/register', body);
}