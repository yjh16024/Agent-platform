import { http } from './http';
import { WorkflowDef } from './types';

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

export function deleteWorkflow(workflowId: string) {
  return http.delete<void>(`/api/v1/workflows/${workflowId}`);
}