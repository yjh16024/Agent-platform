// 与后端 DTO 严格对齐的类型定义。
// 命名约定：强类型 record 序列化为 camelCase；自由 Map 入参为 snake_case（见各 API 封装）。

export interface Persona {
  tone?: string;
  style?: string;
  role?: string;
  warmth?: number;
  expertise?: number;
  proactiveness?: number;
  catchphrases?: string[];
  forbidden?: string[];
}

export interface GenerationConfig {
  model?: string;
  provider?: string;
  temperature?: number;
  topP?: number;
  topK?: number;
  maxTokens?: number;
  responseFormat?: string;
  timeoutMs?: number;
  retry?: { maxAttempts?: number; backoffMs?: number };
}

export interface ModelBinding {
  provider?: string;
  model?: string;
  baseUrl?: string;
  apiKey?: string;
}

export interface ModelBindingView {
  provider?: string;
  model?: string;
  baseUrl?: string;
  apiKeyMasked?: string;
  hasApiKey?: boolean;
}

export interface ModelConfigView {
  /** 嵌入模型绑定（RAG 向量化）。 */
  embedding?: ModelBindingView;
  /** 平台默认对话模型绑定（智能体未单独配置时回退）。 */
  chat?: ModelBindingView;
}

export interface Capabilities {
  knowledgeBaseIds?: string[];
  toolsetIds?: string[];
  skillIds?: string[];
  pluginIds?: string[];
  multimodal?: boolean;
}

export interface AgentResponse {
  agentId: string;
  tenantId?: string;
  name: string;
  avatar?: string;
  description?: string;
  persona?: Persona;
  systemPrompt?: string;
  generationConfig?: GenerationConfig;
  capabilities?: Capabilities;
  modelBinding?: ModelBindingView;
  status?: string;
  visibility?: string;
  currentVersion?: string | number | null;
  effectivePlugins?: Array<{ pluginId?: string; version?: string; contributedTools?: string[]; contributedHooks?: string[] }>;
  validation?: { ok?: boolean; warnings?: string[] };
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface AgentVersion {
  id?: string;
  agentId?: string;
  version?: string | number;
  snapshot?: Record<string, unknown>;
  promptHash?: string;
  releasedBy?: string;
  releasedAt?: string;
}

export interface PluginDef {
  id?: string;
  pluginId?: string;
  tenantId?: string;
  name?: string;
  description?: string;
  author?: string;
  latestVersion?: string;
  manifest?: Record<string, unknown>;
  artifactUri?: string;
  artifactHash?: string;
  status?: string;
  visibility?: string;
  scanResult?: unknown;
  createdAt?: string;
  updatedAt?: string;
}

export interface LogEvent {
  logId?: string;
  timestamp?: string | number | null;
  traceId?: string;
  runId?: string;
  tenantId?: string;
  agentId?: string;
  pluginId?: string;
  level?: string;
  category?: string;
  message?: string;
  context?: Record<string, unknown>;
  stackTrace?: string;
  fingerprint?: string;
}

export interface DiagnosticReport {
  reportId?: string;
  traceId?: string;
  fingerprint?: string;
  severity?: string;
  category?: string;
  rootCause?: { summary?: string; detail?: string; evidence?: unknown };
  solutions?: Array<{ title?: string; description?: string; type?: string; autoFixCommand?: string; confidence?: number }>;
  knowledgeBaseRef?: unknown;
  generatedAt?: string;
  source?: string;
}

export interface ScoreReport {
  overall?: number;
  clarity?: number;
  completeness?: number;
  structure?: number;
  constraints?: number;
  examples?: number;
  modelAlignment?: number;
}

export interface OptimizationResult {
  optimizedPrompt?: string;
  diff?: Array<{ type?: string; section?: string; before?: string; after?: string }>;
  score?: ScoreReport;
  suggestions?: string[];
  source?: string;
}

// ---- 会话 ----
export interface SessionSummary {
  sessionId?: string;
  tenantId?: string;
  agentId?: string;
  userId?: string;
  title?: string;
  status?: string;
  messageCount?: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface SessionMessage {
  messageId?: string;
  sessionId?: string;
  runId?: string;
  turnNo?: number;
  seqNo?: number;
  role?: string;
  content?: string;
  model?: string;
  createdAt?: string;
}

export interface SessionDetail {
  session?: SessionSummary;
  messages?: SessionMessage[];
}

// ---- 知识库 / RAG ----
export interface KnowledgeBase {
  kbId?: string;
  tenantId?: string;
  name?: string;
  description?: string;
  embeddingModel?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  chunkStrategy?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  /** 文档数（列表接口附带统计）。 */
  documentCount?: number;
  /** chunk 总数（列表接口附带统计）。 */
  chunkCount?: number;
}

export interface DocMeta {
  docId?: string;
  kbId?: string;
  title?: string;
  fileName?: string;
  fileType?: string;
  fileSize?: number;
  status?: string;
  chunkCount?: number;
  errorMsg?: string;
  createdAt?: string;
}

export interface ChunkMeta {
  chunkId?: string;
  docId?: string;
  seqNo?: number;
  content?: string;
  meta?: Record<string, unknown>;
}

export interface RetrievalResult {
  chunkId?: string;
  content?: string;
  score?: number;
  docId?: string;
  page?: number | null;
  meta?: Record<string, unknown>;
}

// ---- Skill / 工作流 / 文件 / 配额 / 工具 ----
export interface SkillDef {
  skillId?: string;
  tenantId?: string;
  name?: string;
  description?: string;
  version?: string;
  source?: string;
  tools?: string[] | unknown;
  promptTemplate?: string;
  manifest?: Record<string, unknown>;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface WorkflowDef {
  workflowId?: string;
  tenantId?: string;
  name?: string;
  description?: string;
  definition?: Record<string, unknown>;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface FileAsset {
  fileId?: string;
  tenantId?: string;
  bucket?: string;
  objectKey?: string;
  fileName?: string;
  fileType?: string;
  mimeType?: string;
  fileSize?: number;
  status?: string;
  createdAt?: string;
}

export interface QuotaStatus {
  quotaType?: string;
  period?: string;
  limit?: number;
  used?: number;
}

export interface ToolInfo {
  name?: string;
  description?: string;
}