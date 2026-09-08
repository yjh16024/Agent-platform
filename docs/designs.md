# 技术设计文档汇总

> **合并自**：`chat-file-support-design.md`、`dashboard-technical-design.md`、`desktop-packaging-technical-design.md`。
> 每节均标注**当前实现状态**（2026-09-08 核实），未实现部分保留设计原文以便后续推进。

---

## 一、对话支持文件拖入与读取（方案 A ✅ 已交付）

**目标**：对话页拖入文件 → 智能体读取内容作为本轮上下文。

**消息结构约定**：历史轮次维持纯文本；**仅当次最新 user 消息**携带附件时用 parts[]：

```json
{"role":"user","content":[
  {"type":"text","text":"请总结这份合同的风险点"},
  {"type":"file","fileId":"fid_xxx","fileName":"合同.pdf"}
]}
```

**后端链路**：`AgentRuntimeService.extractUserMessage()` → text part 拼接 → file part 经 `FileUploadService.readText()`（复用 `DocumentParser`）解析 → 以 `[文件：<name>]` 区块注入上下文。

**实现状态**：
- [x] `FileUploadService.readText(tenantId, fileId[, maxChars])`：Tika 解析 + 类型白名单 + 截断 + 租户隔离
- [x] `AgentRuntimeService`：parts[] 支持，单次 ≤4 个文件，超限跳过，失败回退占位说明不阻断
- [x] 前端：对话页 `Upload.Dragger` → `/api/v1/files/upload` → 附件标签 → 发送时组装 parts[]
- [x] 单测：`FileUploadReadTextTest`（txt 解析 / 截断 / 图片拒绝 / 跨租户拒绝）4 例

**上下文保护**：单文件截断 100k 字符、单次 ≤4 个、合计 200k；超限标注 `(已截断)`。

**未做（二期/三期）**：
- **方案 B（图片走视觉模型）**：`ImagePart` 已有；需扩展适配器请求体支持多模态 content 数组，并按 VISION 能力校验路由。
- **方案 C（大文件走 RAG）**：拖入时归入知识库 → `context.rag.knowledgeBaseIds` 检索 → 回答带引用。

**边界**：附件仅在发送当轮注入上下文（跨轮记忆需方案 C）；畸形/加密 PDF 解析失败返回明确错误；严格租户隔离，不执行上传内容中的脚本。

---

## 二、仪表盘（Web UI）技术设计 —— ✅ 已实现

**架构**：React 18 + Vite 5 + antd 5 + TypeScript，构建产物进 `agent-platform-core/src/main/resources/static/`，由 Spring Boot 同源托管（免 CORS）。开发态 `npm run dev`（5173）经 Vite 代理转发 `/api`、`/actuator` 到 8081。路由用 HashRouter。

**关键契约**：
- 统一响应 `ApiResponse<T>`：`{success, code, message, data, traceId, timestamp}`；分页 `PageResult<T>`。
- **唯一例外**：`POST /api/v1/agent/run` 返回裸 `AgentRunResponse`（或 SSE），不套信封。
- 命名：强类型 record → camelCase；自由 Map 入参 → snake_case（登录、插件挂载、诊断、提示词）。
- SSE：`/agent/run` 不能用原生 `EventSource`（只支持 GET），前端用 `fetch` + `ReadableStream` 手写按 `\n\n` 切帧解析。

**页面现状**（已超出 v1 设计范围）：`Overview`、`agents`、`chat`、`plugins`、`ops`（日志/诊断/提示词）、`rag`（知识库）、`sessions`、`skills`、`workflows`、`files`、`settings`。
> 原设计把「RAG/工作流/Skills/多模态」列为扩展点，现均已落地为页面。

**构建与启动**：`npm run build:prod`（= `vite build` + `scripts/sync-dist.mjs`）→ `mvn -pl agent-platform-core -am package -DskipTests` → `start-core.bat`。**改前端必须重建并强刷（Ctrl+F5）**。

**修复记录**：`BuiltinPluginSeeder` 以 `tenantId="__platform__"` 建档，而 `PluginService.marketplace()` 只查当前租户 → 插件市场返回空；已改为「当前租户 + 平台租户」并集，`detail()` 增加平台回退。

---

## 三、桌面打包技术设计 —— ⛔ 已归档（未实施）

> 仅作技术参考保留，**当前不排期**。背景与路线对比见 `feasibility.md`。

**目标产物**（jpackage + jlink）：

```
agent-platform-<ver>/
├── agent-platform.exe|agent-platform   # 启动器
├── runtime/                            # jlink 最小 JRE（含 --enable-preview）
├── app/
│   ├── agent-platform-core.jar
│   └── static/                         # 前端产物
└── lib/
```

**关键改造点**：
1. **MySQL → 内嵌 H2（MODE=MySQL）**：`V1~V10` 中 MySQL 专属写法需适配——`MEDIUMTEXT`→`TEXT`、`DATETIME(3)`→`TIMESTAMP(3)`、`ALTER TABLE ... AFTER ...` 在 H2 不支持需方言分支；`@JdbcTypeCode(SqlTypes.JSON)` 不依赖 MySQL 原生 JSON。备选 SQLite。
2. **Redis → 内存**：置空 `spring.data.redis`，各 Service 已普遍采用 `@Autowired(required=false)` 可选注入，自动退化。
3. **Milvus/Kafka/MinIO/LiteLLM**：默认全关或本地，无需新开发。
4. **首次启动向导**：设置 `MODEL_KEY_ENC_KEY`（OS 凭据库/数据目录），可选填默认模型，初始化 DB 与数据目录；建议新增 `GET/POST /api/v1/setup`。
5. **打包命令**：`jlink --add-modules ... --output runtime` → `jpackage --type msi --runtime-image runtime --main-jar ... --java-options "--enable-preview"`；生产需 `signtool`/`codesign` 签名。

**风险**：Flyway 方言适配（最高优先）、密钥存管、数据目录重定向（`APP_DATA_DIR`）、端口冲突与单实例锁、插件目录应指向数据目录。
