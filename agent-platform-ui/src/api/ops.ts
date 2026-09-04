import { http, PageResult } from './http';
import { LogEvent, DiagnosticReport, OptimizationResult, ScoreReport } from './types';

// 日志查询参数与 body 均为 camelCase
export function listLogs(q?: {
  traceId?: string;
  level?: string;
  category?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  const p = new URLSearchParams();
  if (q?.traceId) p.set('traceId', q.traceId);
  if (q?.level) p.set('level', q.level);
  if (q?.category) p.set('category', q.category);
  if (q?.keyword) p.set('keyword', q.keyword);
  p.set('page', String(q?.page ?? 0));
  p.set('size', String(q?.size ?? 20));
  return http.get<PageResult<LogEvent>>(`/api/v1/logs?${p.toString()}`);
}

// body = LogEvent（camelCase）
export function collectLog(log: LogEvent) {
  return http.post<LogEvent>('/api/v1/logs', log);
}

// diagnosis 是 snake_case：trace_id / message / category
export function analyzeDiagnosis(body: {
  trace_id?: string;
  message?: string;
  category?: string;
  fingerprint?: string;
}) {
  return http.post<DiagnosticReport>('/api/v1/diagnosis/analyze', body);
}

// prompt optimize 是 snake_case：raw_prompt / context / options
export function optimizePrompt(body: Record<string, unknown>) {
  return http.post<OptimizationResult>('/api/v1/prompt/optimize', body);
}

export function scorePrompt(prompt: string) {
  return http.post<ScoreReport>('/api/v1/prompt/score', { prompt });
}