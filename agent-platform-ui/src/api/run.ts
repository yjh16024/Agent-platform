import { http, getTenantId, getToken } from './http';

/** 一条消息的 content 类型：纯文本 或 parts[]（text + file/image 附件）。 */
export type MessagePart =
  | { type: 'text'; text: string }
  | { type: 'file'; fileId: string; fileName?: string }
  | { type: 'image'; fileId: string; fileName?: string; mimeType?: string };

export interface RunMessage {
  role: string;
  content: string | MessagePart[];
}

export interface RunReference {
  chunkId?: string;
  source?: string;
  page?: number | null;
  score?: number;
}

export interface RunResponse {
  runId?: string;
  sessionId?: string;
  mode?: string;
  output?: { role?: string; content?: string; audioUrl?: string };
  traceId?: string;
  usage?: { promptTokens?: number; completionTokens?: number; totalCostUsd?: number };
  references?: RunReference[];
  plugins?: unknown[];
}

/** RAG 请求配置：useRag 置 true 并指定知识库后，对话会自动检索并返回引用。 */
export interface RagRequest {
  useRag?: boolean;
  knowledgeBaseIds?: string[];
  topK?: number;
  scoreThreshold?: number;
}

/** 工具配置：enabled 置 true 后，对话启用 function calling（默认全部注册工具，allowed 可白名单）。 */
export interface ToolsRequest {
  enabled?: boolean;
  allowed?: string[];
}

// 非流式：/agent/run 返回裸 JSON（不套 ApiResponse），raw=true
export function runAgent(agentId: string, messages: RunMessage[], rag?: RagRequest, tools?: ToolsRequest) {
  return http.post<RunResponse>(
    '/api/v1/agent/run',
    {
      agentId,
      mode: 'agent',
      messages,
      context: rag ? { useRag: rag.useRag ?? true, rag: { knowledgeBaseIds: rag.knowledgeBaseIds ?? [], topK: rag.topK ?? 5, scoreThreshold: rag.scoreThreshold ?? 0.0 } } : undefined,
      tools: tools ? { enabled: tools.enabled ?? true, allowed: tools.allowed ?? [] } : undefined,
      metadata: { tenant_id: getTenantId(), user_id: 'demo-user' },
    },
    true,
  );
}

// 流式 SSE：EventSource 只支持 GET，这里用 fetch + ReadableStream 手写解析。
// 事件帧格式：event:run.delta / data:{...}，运行结束 run.completed，出错 run.error。
export async function runAgentStream(
  agentId: string,
  messages: RunMessage[],
  onDelta: (text: string) => void,
  rag?: RagRequest,
  tools?: ToolsRequest,
): Promise<void> {
  const h: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Tenant-Id': getTenantId(),
  };
  const token = getToken();
  if (token) h['Authorization'] = `Bearer ${token}`;

  const res = await fetch('/api/v1/agent/run', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      agentId,
      mode: 'agent',
      messages,
      stream: true,
      context: rag ? { useRag: rag.useRag ?? true, rag: { knowledgeBaseIds: rag.knowledgeBaseIds ?? [], topK: rag.topK ?? 5, scoreThreshold: rag.scoreThreshold ?? 0.0 } } : undefined,
      tools: tools ? { enabled: tools.enabled ?? true, allowed: tools.allowed ?? [] } : undefined,
      metadata: { tenant_id: getTenantId(), user_id: 'demo-user' },
    }),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (!res.body) throw new Error('无响应流');

  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let idx: number;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      let event = '';
      let data = '';
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (event === 'run.error') {
        let msg = data;
        try {
          msg = JSON.parse(data)?.message ?? data;
        } catch {
          /* 非 JSON 时保持原样 */
        }
        throw new Error(msg || '流式运行出错');
      }
      if (!data) continue;
      try {
        const obj = JSON.parse(data);
        const content =
          obj?.output?.content ?? obj?.content ?? obj?.delta ?? (typeof obj === 'string' ? obj : '');
        if (content) onDelta(String(content));
      } catch {
        /* 非 JSON 帧忽略 */
      }
    }
  }
}