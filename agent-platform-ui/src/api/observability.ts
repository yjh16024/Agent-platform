import { http } from './http';

export interface RunSummary {
  traceId?: string;
  runId?: string;
  agentId?: string;
  model?: string;
  status?: 'ok' | 'failed';
  shortCircuit?: boolean;
  startedAt?: string;
  latencyMs?: number;
  llmCalls?: number;
  toolCalls?: number;
  toolFailures?: number;
  ragCalls?: number;
  tokens?: number;
}

export interface TimelineEvent {
  time?: string;
  category?: string;
  level?: string;
  agentId?: string;
  message?: string;
}

/** 最近运行摘要（可按 agent 过滤）。 */
export function listRunSummaries(agentId?: string, limit = 20) {
  const p = new URLSearchParams({ limit: String(limit) });
  if (agentId) p.set('agentId', agentId);
  return http.get<RunSummary[]>(`/api/v1/observability/runs?${p.toString()}`);
}

/** 某次运行的阶段时间线。 */
export function runTimeline(traceId: string) {
  return http.get<TimelineEvent[]>(
    `/api/v1/observability/runs/${encodeURIComponent(traceId)}/timeline`,
  );
}
