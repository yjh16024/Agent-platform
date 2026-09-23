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

/**
 * 一次工具调用的记录（对应后端 ToolCallRecord）。
 *
 * <p>它解决的是"用户看不见过程"：在此之前 agent 在后台 grep 了 200 个文件、
 * 读了 3 个、改写了 1 个，界面上只是"想了一会儿然后给出答案" ——
 * 用户无法判断它是查过了才回答、还是压根没查就编。</p>
 *
 * <p>`arguments` / `output` 都已由**后端**截断（入参 2000 字符、结果 4000 字符），
 * 前端不需要再处理体积。</p>
 */
export interface ToolCallInfo {
  name: string;
  /** 入参 JSON 文本（无参时为 "{}"）。 */
  arguments?: string;
  success?: boolean;
  output?: string;
  error?: string;
  latencyMs?: number;
}

/** 流式运行的收尾信息。 */
export interface StreamOutcome {
  /** 本轮的工具调用记录；无调用时为空数组（便于调用方直接 length 判断）。 */
  toolCalls: ToolCallInfo[];
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
  /** 本轮工具调用记录（工具调用可视化）；无调用时不出现。 */
  toolCalls?: ToolCallInfo[];
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
export function runAgent(
  agentId: string,
  messages: RunMessage[],
  rag?: RagRequest,
  tools?: ToolsRequest,
  /**
   * 运行时会话 ID（**必须传**）。
   *
   * <p>后端 `SessionService.resolve()` 在 sessionId 为空时**直接返回 null**（不建会话），
   * 于是消息不落库、历史不回放、短期缓存/中期摘要拿不到会话、向量记忆不索引、
   * 工具审批无法按会话关联 —— 整个记忆体系都会失效。
   * 该值由 `chatStore.sessionOf(agentId)` 生成并跨轮复用，点「新对话」时更换。</p>
   */
  sessionId?: string,
) {
  return http.post<RunResponse>(
    '/api/v1/agent/run',
    {
      agentId,
      sessionId,
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
  /** 运行时会话 ID（理由同 {@link runAgent}，必须传，否则记忆体系整条失效）。 */
  sessionId?: string,
): Promise<StreamOutcome> {
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
      sessionId,
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
  /** 本轮的工具调用记录：从 run.completed 帧取；无调用时保持空数组。 */
  let toolCalls: ToolCallInfo[] = [];

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
      // 结束帧：后端在工具往返跑完后一次性给出本轮的工具调用记录。
      // 注意 continue —— 它的 data 不是文本增量，不能落进下面的 onDelta。
      if (event === 'run.completed') {
        try {
          const obj = JSON.parse(data || '{}');
          if (Array.isArray(obj?.toolCalls)) {
            toolCalls = obj.toolCalls as ToolCallInfo[];
          }
        } catch {
          /* 结束帧解析失败不影响已收到的文本 */
        }
        continue;
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
  return { toolCalls };
}