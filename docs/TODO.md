# 未实现项与注意事项（Backlog & Notes）

> 本文记录项目当前**尚未实现 / 未接通**的能力，以及后续开发中**必须注意的坑**，供后续补充时对照。
> 维护约定：某项补齐后请相应更新本文与关联的 [技术文档](dashboard-technical-design.md)。
> 最后核实：2026-09-04（本机已装 Docker 并拉起全套基础设施，详见「环境与运维」）。

## 〇、本轮已补齐（2026-09-04）

- 会话持久化：`Session` / `Message` 实体 + `/api/v1/sessions` 系列接口，运行时按 `sessionId` 落库并回放历史（多轮上下文注入 `ModelAdapter.ChatRequest.history`）。
- 多租户配额：`QuotaService` 改 Redis INCR+TTL 为主、内存兜底、`tenant_quota` 表持久化；新增 `/api/v1/quotas`。
- 生产鉴权：core 侧 `JwtAuthFilter`（`agent-platform.security.enabled`，默认 false）。
- Kafka 事件总线：`EventBus`/`KafkaEventBus`/`KafkaTopicConfig`（`agent-platform.events.enabled`）；compose 加 Kafka(KRaft)。
- 文件存储：`StorageService` + 本地磁盘（默认）/ MinIO（`agent-platform.storage.type=minio`）；文件上传/列表/详情/下载/删除闭环。
- Milvus 向量存储：`MilvusVectorStore`（REST v2，`agent-platform.rag.vector-store=milvus`），`InMemoryVectorStore` 改条件装配默认生效。
- 前端补页：知识库 / 会话历史 / 工作流 / Skills / 文件 / 工具调试 / 租户配额。
- 真实模型路由切到 **DeepSeek**（见下）。
- **模型绑定（仪表盘选模型 + 填 API Key）**：`agent_def` 增 `model_binding` JSON 列（Flyway `V8`）；前端「新建/编辑智能体」可选 provider/model/baseUrl 并手输 API Key；后端 `ModelKeyCrypto`（AES-GCM）+ `ModelBindingService`（seal/resolve/view）加密落库、接口永回明文、运行时按智能体解析、`ModelAdapter.ChatRequest` 携带每请求 baseUrl/apiKey 直连厂商。历史全局 `.env`/LiteLLM 仍作为未配置时的兜底。
- **模型接入排错（2026-09-04）**：`ModelBindingService.resolve` 重构为三级路由（`DIRECT` 直连 > `GATEWAY` 网关 > `LOCAL` 仅显式）；`ModelRouter` 移除「上游 4xx/5xx 静默兜底 Mock」；`OpenAiCompatibleAdapter` 修复 baseUrl `/v1` 重复拼接与 `Bearer` 头；API Key 落库前 `trim`、编辑保留原密钥；直连 baseUrl + Key 必填校验（前端 + 后端 `AgentConfigValidator`）。上游报错现原样透出，**不再静默返回 Mock**。
- **云端嵌入直连（2026-09-04 加）**：新增平台级「嵌入模型绑定」（`model_config` 单行表 + `GET/PUT /api/v1/model-config`），仪表盘「模型设置」页像 ccswitch 一样填 provider/model/baseUrl/API Key（AES-GCM 加密落库、接口只回掩码）；`EmbeddingService` 三级路由（DIRECT 直连 `/v1/embeddings` > GATEWAY 网关 > LOCAL mock）。DeepSeek 无 embedding 时直连硅基流动 bge-m3 / OpenAI 等即可拿真实语义向量；换模型维度变化时 Milvus 自动 drop 重建 collection。

## 一、未实现 / 待补充（功能缺口）

### 1. 环境相关性缺口（Docker 已拉起，按开关启用）

| 能力 | 现状 | 启用方式 | 备注 |
|------|------|------|------|
| 真实模型路由 | ⚠️ 代码已接通，等待有效 Key | compose 起 `litellm` + 有效 `DEEPSEEK_API_KEY`，或仪表盘直连 baseUrl+Key | 默认模型 `deepseek-chat`；key 无效 → 401/402 原样透出（不再兜底 Mock） |
| RAG 向量检索 | ✅ 已接通（嵌入可直连云端） | `VECTOR_STORE=milvus` + `/model-config` 填嵌入绑定 | 默认仍 mock 8 维；配直连后真实语义（见 §2） |
| Kafka 事件总线 | ✅ 已接通 | `EVENTS_ENABLED=true` | 主题 `agent-config-events` / `log-events` |
| 文件对象存储 | ✅ 已接通 | `STORAGE_TYPE=minio` | 默认 `local` 本地磁盘 |
| 日志高阶存储 | ❌ 未接入 | ES / ClickHouse | 三级诊断高阶检索仍缺失（MySQL 索引 + 内存） |

### 2. 功能未完全落地（代码存在但为 MVP / 内存实现）

- ✅ **RAG 嵌入模型直连（2026-09-04 已接通）**：新增平台级嵌入绑定（`/api/v1/model-config` + 前端「模型设置」），像 ccswitch 一样填 provider/model/baseUrl/API Key 即直连云端嵌入模型（OpenAI 兼容 `/v1/embeddings`），**无需下载本地模型**。DeepSeek 无 embedding 接口，推荐直连硅基流动 `BAAI/bge-m3`（免费额度）或 OpenAI `text-embedding-*`。未配置时退回本地 Mock 8 维；换嵌入模型维度变化时 Milvus 自动重建 collection（已摄取文档需重新上传）。仅差一份有效 Key 即端到端跑真实语义。
- ✅ **会话历史持久化**：已落地（见 §〇）。
- ✅ **多租户配额**：已落地（Redis + `tenant_quota` 表）。
- ✅ **生产鉴权**：core 侧过滤器已落地（默认关）。
- ⚠️ **Anthropic 协议端点未接入（2026-09-04 巡检发现）**：真实模型直连目前仅支持 OpenAI 兼容协议（`/v1/chat/completions`）。Bailian「应用」端点（`.../apps/anthropic`，Anthropic Messages `/v1/messages` 协议，含 thinking/text 块）无法直连——`OpenAiCompatibleAdapter` 发过去返回 404 `Not support`。需新增 `AnthropicAdapter`（Messages API + `x-api-key`/`anthropic-version` 头），或改用 Bailian/DashScope 的 OpenAI 兼容模型端点。
- ⚠️ **多模态真实能力**：文件存储已落地，但 ASR / TTS / OCR 仍依赖外部服务，未端到端验证。
- ⚠️ **工作流 / Skills 端到端**：纯逻辑节点可跑；LLM / KnowledgeBase 节点依赖真实模型与向量库。
- ⚠️ **版本 Diff**：`AgentVersionService.diffPrompt` 仍为简单行级 Diff（MVP），可接 diff-match-patch。

### 3. 前端仪表盘覆盖

已覆盖 智能体 / 对话（含会话历史） / 插件 / 日志·诊断·提示词 / 知识库 / 工作流 / Skills / 文件 / 工具调试 / 租户配额。

## 二、注意事项（已踩过的坑，后续务必遵守）

### 1. 字段命名契约（最重要）

强类型 `record` 序列化为 **camelCase**，自由 `Map` 入参为 **snake_case**，混用极易出错：

| 命名 | 接口 | 关键字段 |
|------|------|----------|
| camelCase | `POST/PUT /agents` | `systemPrompt`、`persona{tone,role,...}`、`generationConfig` |
| camelCase | `POST /agent/run` | `agentId`、`messages[{role,content}]`、`metadata{tenant_id,user_id}` |
| camelCase | `GET/POST /logs` | 查询 `traceId/level/category`；采集 `LogEvent{logId,traceId,tenantId,agentId,...}` |
| camelCase | `GET/POST/PATCH /sessions`、`/quotas` 列表、`/files` | 强类型 record |
| camelCase | `GET/PUT /model-config` | PUT body/回显 `embedding{provider,model,baseUrl,apiKey}`；密钥回显 `apiKeyMasked`/`hasApiKey` |
| **snake_case** | `POST /auth/login` | `tenant_id`、`user_id` |
| **snake_case** | `POST /plugins/{id}/attach` | `agent_id`、`version`、`config`、`enabled` |
| **snake_case** | `POST /diagnosis/analyze` | `trace_id`、`message`、`category`、`fingerprint` |
| **snake_case** | `POST /prompt/optimize`、`/score` | `raw_prompt`、`context{use_case}`、`options{generate_examples}`、`prompt` |
| **snake_case** | `POST /quotas`（配置限额） | `quota_type`、`limit`、`period` |

### 2. 响应格式的特殊性

- 统一信封 `ApiResponse<T>` `{success,code,message,data,traceId,timestamp}`；分页 `PageResult<T>` `{items,total,page,size,totalPages}`。
- **唯一例外**：`POST /api/v1/agent/run` 返回**裸** `AgentRunResponse`（`stream:true` 时 `text/event-stream`），不套信封。
- `GET /api/v1/files/{fileId}/download` 直出字节流（非信封），前端用 fetch + blob。
- 网关 401 返回 `code:"UNAUTHORIZED"`。

### 3. 环境与运维（Docker）

- **基础设施现由 Docker 提供**：`docker compose up -d` 起 MySQL 8.4 / Redis 7 / Milvus 2.x(+etcd+minio) / MinIO / LiteLLM / Kafka(KRaft)。MySQL 账号已改为 compose 内 `MYSQL_USER=agent`/`agent123456`（与 application.yml 默认一致）。
- **`.env` 文件**：必须是 `.env` 这个**精确文件名**（`1.env`/`1.evn` 不会被加载）。且变量分属两个进程：
  - `DEEPSEEK_API_KEY`（及 OPENAI/ANTHROPIC 等）→ 被 **docker-compose 的 LiteLLM 容器**消费（`os.environ/...`）；
  - `VECTOR_STORE` / `STORAGE_TYPE` / `EVENTS_ENABLED` / `SECURITY_ENABLED` / `DEFAULT_MODEL` → 被**宿主机 core（java 进程）**消费。`.env` 不会自动传给它，启动 core 时需手动 `export`（或改造 `start-core.bat` 读 `.env`）。
- **核心开关默认关闭**（保证无 Docker 本地可跑）：`security.enabled=false`、`events.enabled=false`、`rag.vector-store=in-memory`、`storage.type=local`。
- **litellm-config.yaml 三个已修复的坑**（勿回退）：① `database_url: postgresql://_unused` 会导致 Prisma 迁移失败——已删；② `fallbacks: [- gpt-4o-mini]` 旧格式（新版要求 dict）——已删 `fallbacks`；③ `cache: true + cache_params(redis)` 会卡在「Setting Cache on Proxy」——已 `cache: false`。
- **DeepSeek 走 LiteLLM（OpenAI 兼容）**：litellm 中 `model: deepseek/deepseek-chat`、`api_base: https://api.deepseek.com/v1`、`api_key: os.environ/DEEPSEEK_API_KEY`；默认模型由 `agent-platform.model.default-provider/name`（`deepseek`/`deepseek-chat`）控制。
- **模型路由三级优先级（勿回退）**：① 智能体 `model_binding` 有有效 baseUrl+Key 且 provider≠local → 直连 `OpenAiCompatibleAdapter`；② 未直连 → LiteLLM 网关/全局环境变量（provider 归一化为具体云厂商，绝不回 `auto` 防误落 Mock）；③ 仅 provider 显式 `local` → 本地 Mock。上游 4xx/5xx **原样透出**，禁止 catch 后兜底 Mock。
- **Windows 下 jar 被锁**：`java -jar` 运行中无法 `mvn package`，先 `taskkill` 残留 java 再编译/启动。
- **前端产物**必须构建进 `agent-platform-core/src/main/resources/static/`（`agent-platform-ui\build-ui.bat`）后再打 jar。
- 端口：core 8081、gateway 8080、MySQL 3306、Redis 6379、LiteLLM 4000、Milvus 19530、MinIO 9000/9001、Kafka 9092。
- 便携工具链在 `~/tools/`（JDK21 / Maven / Redis），不在全局 PATH，跑前设 `JAVA_HOME`/`PATH`；core 需 `--enable-preview`（JDK21 ScopedValue）。

### 4. 前端实现细节

- **HashRouter**（`#/agents`）规避无 SPA 回退 404。
- 直连 core 同源；`api/http.ts` 已预置 `Authorization: Bearer`。
- SSE 用 `fetch` + `ReadableStream` 手写解析。
- 文件上传/下载、Skill 文本导入走原始 `fetch`（multipart / text/plain / blob），未复用信封解包。

### 5. 其他

- **record 派生 boolean getter 会被 Jackson 序列化**：record 里的 `isXxx()`/`getXxx()` 实例方法会被当作属性序列化（如 `ModelBinding.isConfigured()` 落成 `"configured":true`），读回时破坏 record 反序列化，报 `Could not deserialize string to ...`（曾导致 `GET /agents` 500）。已给 `ModelBinding.isConfigured()` 加 `@JsonIgnore` 并给 record 加 `@JsonIgnoreProperties(ignoreUnknown=true)`；后续新增 record 的派生 getter 务必 `@JsonIgnore`。
- `local` provider 返回确定性 mock 文本（兜底）；诊断/提示词优化无真实 LLM 时也借 mock 走通。
- 插件市场查询「当前租户 ∪ `__platform__`」；新增平台插件用 `__platform__` 租户并登记 `BuiltinPluginSeeder`。
- 事件总线/Kafka 依赖注入均为可选（`@Autowired(required=false)`），无 broker 静默跳过；保留各 Service 既有构造签名（单测 `new` 不破）。

## 三、建议的后续优先级

1. **换有效嵌入 Key + 有效对话 Key**：在「模型设置」页直连硅基流动/OpenAI 嵌入（填 Key 即真实语义），`.env` 填 `DEEPSEEK_API_KEY`（对话）——代码均已接通，只差填 Key。
2. **日志高阶存储**：ES / ClickHouse 接入 + Logback JSON → Kafka → Worker → ES/CH。
3. **多模态真实能力**：ASR / TTS / OCR 接外部服务并端到端验证。
4. **版本 Diff** 接 diff-match-patch（字符级）。
5. **生产化加固**：core 侧鉴权默认策略、限流、审计。