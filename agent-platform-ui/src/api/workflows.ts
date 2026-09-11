import { http } from './http';
import { WorkflowDef } from './types';

/** 更新工作流定义（画布保存：覆盖 definition，workflowId 不变）。 */
export function updateWorkflow(
  workflowId: string,
  definition: Record<string, unknown>,
  name?: string,
  description?: string,
) {
  const p = new URLSearchParams();
  if (name) p.set('name', name);
  if (description) p.set('description', description);
  return http.put<WorkflowDef>(`/api/v1/workflows/${workflowId}?${p.toString()}`, definition);
}

export function listWorkflows() {
  return http.get<WorkflowDef[]>('/api/v1/workflows');
}

export function getWorkflow(workflowId: string) {
  return http.get<Record<string, unknown>>(`/api/v1/workflows/${workflowId}`);
}

// 创建：name/description 走 @RequestParam，definition 走 JSON body
export function createWorkflow(name: string, definition: Record<string, unknown>, description?: string) {
  const p = new URLSearchParams({ name });
  if (description) p.set('description', description);
  return http.post<WorkflowDef>(`/api/v1/workflows?${p.toString()}`, definition);
}

export function executeWorkflow(workflowId: string, input: Record<string, unknown>) {
  return http.post<Record<string, unknown>>(`/api/v1/workflows/${workflowId}/execute`, input);
}

/** 调试执行：返回最终变量 + 每个节点的执行轨迹（画布调试面板用）。 */
export function debugWorkflow(workflowId: string, input: Record<string, unknown>) {
  return http.post<{ variables?: Record<string, unknown>; steps?: WorkflowStep[] }>(
    `/api/v1/workflows/${workflowId}/debug`,
    input,
  );
}

/** 单节点执行轨迹。 */
export interface WorkflowStep {
  nodeId?: string;
  type?: string;
  name?: string;
  status?: string;
  durationMs?: number;
  output?: unknown;
  error?: string;
}

/** 发布：把当前草稿存为已发布快照并递增版本号。 */
export function publishWorkflow(workflowId: string) {
  return http.post<WorkflowDef>(`/api/v1/workflows/${workflowId}/publish`, {});
}

/** 回滚：definition 恢复为已发布快照。 */
export function rollbackWorkflow(workflowId: string) {
  return http.post<WorkflowDef>(`/api/v1/workflows/${workflowId}/rollback`, {});
}

export function deleteWorkflow(workflowId: string) {
  return http.delete<void>(`/api/v1/workflows/${workflowId}`);
}