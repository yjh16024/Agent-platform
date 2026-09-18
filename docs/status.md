# 功能与实现现状（Status）

> **单一事实来源**：本文件是「已经实现了什么」的权威清单，其它文档不再重复描述现状。
> 最后核实：**2026-09-18**（逐项对照代码；测试数为当日 `mvn test` 实测结果）。
>
> | 你想看什么 | 去哪 |
> |---|---|
> | 已经实现的功能、接口、页面、开关 | **本文件** |
> | 每个功能用什么技术实现、为什么选它 | [technology.md](technology.md) |
> | 未实现事项、路线决策、契约与踩坑 | [backlog.md](backlog.md) |
> | 技术设计与可行性结论（含归档方案） | [design.md](design.md) |
> | 扩展开发、部署、运维、演示 | [guides.md](guides.md) |
> | 历史阶段交付留档（快照） | [phase-reports.md](phase-reports.md) |
> | 安装与配置 | [../README.md](../README.md) |

---

## 一、需求清单与交付状态

| 需求域 | 需求 | 状态 | 落点 |
|---|---|---|---|
| 智能体 | 定义/CRUD/克隆/版本快照/发布/回滚/版本对比 | ✅ 已实现 | `AgentController`、`AgentDefinition` |
| 运行入口 | 一次运行按需装配模型/知识/工具/记忆，支持 JSON 与 SSE | ✅ 已实现 | `POST /api/v1/agent/run`、`AgentRuntimeService` |
| 模型 | 多厂商路由、四种适配器、三级凭证回退、AES-GCM 加密与掩码 | ✅ 已实现 | `ModelAdapter`/`ModelRouter`/`ModelBindingService`/`ModelKeyCrypto` |
| 模型（Spring AI 通道） | Spring AI 2.0.1 协议层（底层官方厂商 SDK）+ 原生 tool-role 工具循环 + RAG 解析切分 + 可观测桥接 | ✅ 已实现（**默认开启**，`SPRING_AI_ENABLED=false` 可回退自研） | `agent-platform.springai.enabled` |
| 模型额度 | **读取厂商开放平台剩余额度**（DeepSeek / 硅基流动 / Moonshot） | ✅ 已实现（2026-09-10 新增） | `GET /api/v1/model-balance`、`ModelBalanceService` |
| 提示词 | 人格 → 模板变量 → Skill 正文 → RAG 附文四层组装 | ✅ 已实现 | `PromptParser`、`AgentRuntimeService` |
| 记忆 | 短期（Redis 近期缓存） | ✅ 已实现 | `SessionRecentCache`（25 轮/50 条/24h，缺失降级 DB） |
| 记忆 | 中期（超长会话早期摘要） | ✅ 已实现 | `SessionSummaryService`、迁移 `V12` |
| 记忆 | 长期（用户画像）/ 向量记忆 | ❌ 未实现 | 见 backlog.md |
| RAG | 解析 → 切分 → 向量化 → 混合检索（向量+FULLTEXT）→ Rerank → 引用溯源 | ✅ 已实现 | `RagPipelineService`、`HybridRetriever` |
| RAG | 对话拖入文件自动进「对话附件库」并参与检索 | ✅ 已实现 | `__chat_attachments__` |
| 工具 | 内置工具（计算器、**天气** `wttr.in`）、HTTP 注册（**持久化**）、MCP 三传输 | ✅ 已实现 | `ToolRegistry`、`ToolController` |
| 工具 | 工具循环（结果回灌 ≤5 轮 / Spring AI 原生 tool-role） | ✅ 已实现 | `ToolExecutor`、`SpringAiToolBridge` |
| 插件 | SPI + ClassLoader 隔离 + before/after Hook + 市场 + 热插拔 | ✅ 已实现 | `AgentPipeline`、`PluginRuntime` |
| Skills | Agent Skills 标准目录、扫描同步、导入、三种执行器 | ✅ 已实现 | `SkillService`、`SkillExecutorRegistry` |
| 工作流 | 自研 DAG（条件分支 + 虚拟线程并行 + Schema 校验） | ✅ 已实现 | `DagEngine`、`WorkflowController` |
| 工作流画布 | **拖拽式编排画布**（FlowGram 固定布局，**Coze 式交互**：画布左键拖拽平移 / 滚轮缩放、节点整卡拖拽重排、内联「+」精确插入、分支折叠、撤销重做）+ 11 类节点（LLM/知识库/代码/HTTP/插件/工具/Agent/条件/转换）+ 节点级调试轨迹 + 发布/回滚；**创建工作流即直接进画布** | ✅ 已实现（2026-09-11，交互完善 09-12） | `pages/workflows/canvas/`、`node/executor/*` |
| 多模态 | `parts[]`（text / file / image），图片走视觉模型 | ✅ 已实现 | `MultimodalResolver`、`FileUploadService` |
| 日志与诊断 | 结构化运行日志 + 三级诊断（规则/向量/LLM）+ 6 维提示词评分 | ✅ 已实现 | `LogService`、`DiagnosisEngine`、`PromptOptimizer` |
| 可观测 | Micrometer 指标 + Loki 日志 + Tempo Span + Grafana 看板与告警 | ✅ 已实现 | `LogEventSink`、`docker-compose.observability.yml` |
| 租户配额 | 调用次数 / tokens / 文件 / 插件限额（Redis INCR+TTL，内存兜底） | ✅ 已实现 | `QuotaService`、`GET /api/v1/quotas` |
| 内置库 | 免装 MySQL：H2 file（MySQL 兼容模式）+ Flyway 双迁移目录 | ✅ 已实现 | `--spring.profiles.active=embedded` |
| 桌面应用 | Electron 33 + jlink 精简 JRE + electron-builder（**绿色版 zip / dir，portable 已弃用**）+ 启动页 + 单实例 + 后端回收 + **启动优化**（界面 ~1–2s 弹出、后端 ~13s 就绪） | ✅ 已实现（分发形态与启动优化 09-12） | `desktop/`、`desktop/main.js` |
| **皮肤（换肤）** | 皮肤市场（列表/安装/卸载/资源代理/bundle 读取）+ 运行时（注入第三方 JS + 契约钩子）+ 契约版本 | ✅ 已实现（2026-09-17） | `SkinController`、`SkinMarketService`、`ui/src/skin/` |
| 皮肤 | 设置面板（**DSH 自定义协议** v1/v2：皮肤声明设置项，宿主持久化 + 渲染面板 + 回调皮肤） | ✅ 已实现 | `skin/customization.ts`、`SkinSettingsPanel` |
| 皮肤 | 皮肤适配（DOM 层级照皮肤期望分层；覆盖层 z-index 让位于 antd 弹出层；运行时"让位样式"） | ✅ 已实现 | `SidebarSkinSettings`、`styles/global.css` |
| 界面 | 收敛（撤顶部 Header 与侧栏租户/登录入口，接口保留）+ 弹窗**全局可拖拽** + **窗口标题由宿主持有** | ✅ 已实现（2026-09-18） | `AppLayout`、`components/dialogDrag.ts`、`skin/titleGuard.ts` |
| 技能市场 | 多通道取件（ghproxy → jsDelivr → GitHub 直连）+ 任意仓库/文件地址直读 | ✅ 已实现 | `skill/market/`、`GET /skills/market` |
| MCP 市场 | 直连官方 Registry，**仅保留 `streamable-http`** | ✅ 已实现 | `McpMarketService`、`GET /tools/mcp/market` |
| HTTP 工具市场 | 免 Key 公开 API 清单，一键注册（清单在资源里，**新增条目不改代码**） | ✅ 已实现 | `resources/tool-market.json`、`GET /tools/market` |
| 鉴权 | core 内 JWT 过滤器（登录/静态账号）、生产密钥守卫 | ✅ 已实现 | `AuthController`、`SECURITY_ENABLED` |
| 独立网关 | 路由 / 限流 / 熔断 | ⚠️ 占位（仅 `JwtAuthFilter`） | `agent-platform-gateway` |
| 日志高阶存储 | ES / ClickHouse | ❌ 未实现（MySQL `log_index` 替代） | 见 backlog.md |
| 长流程编排 | Temporal 持久化与断点恢复 | ❌ 未实现（内存 DAG） | 见 backlog.md |
| 真实多模态推理 | ASR / TTS / OCR 真实服务 | ⚠️ 接口与链路在，默认 mock | 见 backlog.md |

---

## 二、后端接口（17 个 Controller，统一前缀 `/api/v1`）

| 域 | 基础路径 | 主要端点 |
|---|---|---|
| 智能体 | `/agents` | CRUD、`/{id}/clone`、`/{id}/versions`、`/{id}/publish`、`/{id}/rollback/{v}`、`/{id}/diff` |
| 运行 | `/agent` | `POST /run`（JSON 或 SSE） |
| 鉴权 | `/auth` | `POST /login` |
| 会话 | `/sessions` | CRUD、`POST /import`、`/{id}/messages`（GET / DELETE） |
| 知识库 | `/knowledge-bases` | CRUD、`POST /{id}/documents`、`GET /{id}/documents`、`/{id}/chunks`、`POST /search`、`DELETE /documents/{docId}` |
| 工具 | `/tools` | 列表、`PUT /{name}`、`POST /{name}/invoke`、`POST /register`、`POST /mcp`、`/mcp/local`、`/mcp/sandbox`、`DELETE /{name}` |
| 插件 | `/plugins` | `register`、`import`、`marketplace`、详情、`attach`、`detach`、`attachments`、删除 |
| Skills | `/skills` | CRUD、`/sync`、`/upload`、`/import-folder`、`/open-folder`、`/dir`、`/{id}/files`、`/{id}/file`、`/{id}/execute`、`/executors` |
| 工作流 | `/workflows` | CRUD、`PUT /{id}`（画布保存）、`POST /{id}/execute`、`POST /{id}/debug`（节点级轨迹）、`POST /{id}/publish`、`POST /{id}/rollback` |
| 文件 | `/files` | 上传、下载、列表 |
| 模型配置 | `/model-config` | `GET /`、`PUT /embedding`、`PUT /chat` |
| **模型额度** | `/model-balance` | `GET /`（已配置绑定余额）、`GET /vendors`（支持厂商） |
| 日志 | `/logs` | 查询、导出、瀑布图、purge |
| 诊断 | `/diagnosis` | `POST /analyze` |
| 提示词 | `/prompt` | 优化、评分 |
| 配额 | `/quotas` | `GET /`（限额+用量）、`POST /`（配置限额） |
| **皮肤** | `/skins` | `/market/read`（市场列表）、`/market/install`、`/installed`、`/uninstall`、`/proxy`（资源代理）、`/bundle`（取皮肤 JS） |
| 可观测 | `/observability` | 指标/看板数据 |

> 契约：强类型 record 出参 camelCase；自由 Map 入参 snake_case；`POST /agent/run` 返回裸 JSON（不套 `ApiResponse`）。

---

## 三、前端页面（React 18 + Vite + antd 5，HashRouter）

| 菜单 | 路由 | 页面文件 |
|---|---|---|
| 概览 | `/overview` | `pages/Overview.tsx` |
| 智能体 | `/agents` | `pages/agents/AgentList.tsx`（`AgentForm`、`AgentDetailDrawer`） |
| 对话 | `/chat` | `pages/chat/ChatPage.tsx` |
| 会话历史 | `/sessions` | `pages/sessions/SessionsPage.tsx` |
| 知识库 | `/knowledge-bases` | `pages/rag/KnowledgeBasePage.tsx` |
| 工作流 | `/workflows` | `pages/workflows/WorkflowsPage.tsx` |
| Skills | `/skills` | `pages/skills/SkillsPage.tsx` |
| 插件 | `/plugins` | `pages/plugins/PluginMarketplace.tsx` |
| 文件 | `/files` | `pages/files/FilesPage.tsx` |
| 模型设置 | `/settings` | `pages/settings/ModelSettingsPage.tsx` |
| 运维工具 · 运行日志 | `/logs` | `pages/ops/LogsPage.tsx` |
| 运维工具 · 智能体可观测性 | `/observability` | `pages/ops/ObservabilityPage.tsx` |
| 运维工具 · 智能诊断 | `/diagnosis` | `pages/ops/DiagnosisPage.tsx` |
| 运维工具 · 提示词优化 | `/prompt` | `pages/ops/PromptOptimizePage.tsx` |
| 运维工具 · 工具调试 | `/tools` | `pages/ops/ToolsPage.tsx` |
| 运维工具 · **用户配额** | `/quota` | `pages/ops/QuotaPage.tsx`（模型账户额度 + 平台用量限额两个 Tab） |
| **皮肤市场** | `/skins` | `pages/skins/SkinMarketPage.tsx`（市场列表 / 安装 / 卸载 / 运行 JS；设置入口在侧栏底部，面板见「皮肤」一节） |

> 改前端后必须 `npm run build:prod` 同步到 `agent-platform-core/src/main/resources/static/`，否则 core 仍托管旧产物。

---

## 四、数据迁移（Flyway，mysql 与 h2 双目录同名同序）

| 版本 | 内容 |
|---|---|
| V1 | 智能体基座（agent 定义/版本/会话/消息） |
| V2 | RAG 与工具（知识库/文档/chunk/tool_registration 雏形） |
| V3 | 工作流与 Skill |
| V4 | 插件系统 |
| V5 | 多模态（文件/上传）与配额 |
| V6 | 运行日志与诊断 |
| V7 | 提示词优化 |
| V8 | 模型绑定（智能体级） |
| V9 | 平台模型配置 |
| V10 | 平台默认对话模型绑定 + 日志栈与时间索引 |
| V11 | HTTP 工具注册持久化 |
| V12 | 会话早期摘要（`session_def.summary / summary_turn`） |
| V13 | 工作流发布（`workflow_def.published_definition / published_version / published_at`） |

> 当前最新版本 **V13**；新增迁移必须同时提供 `mysql` 与 `h2` 两份。

---

## 五、运行时开关（`@ConditionalOnProperty`）

| 配置项 | 取值与默认 | 作用 |
|---|---|---|
| `agent-platform.storage.type` | `local`（默认）/ `minio` | 文件存储实现 |
| `agent-platform.rag.vector-store` | `in-memory`（默认）/ `milvus` | 向量库实现 |
| `agent-platform.rag.fulltext.enabled` | `true`（默认）；**embedded profile 覆盖为 `false`** | 稀疏检索是否走 MySQL FULLTEXT（H2 不支持） |
| `agent-platform.springai.enabled` | `true`（**默认开启**） | 模型调用/工具循环/可观测走 Spring AI（底层官方 SDK） |
| `agent-platform.springai.rag.enabled` | `true`（**默认开启**） | RAG 解析与切分走 Spring AI |
| `agent-platform.events.enabled` | `true`（**默认开启**；embedded 覆盖为 `false`） | Kafka 事件总线 |
| `agent-platform.skills.open-folder-enabled` | `false` | 是否允许服务端打开文件管理器 |
| `SECURITY_ENABLED` | `false` | core 侧 JWT 鉴权 |

---

## 六、测试规模

- 测试类：**37** 个（`agent-platform-core/src/test`）
- 测试方法：**143** 个，`mvn test` 实测 **全绿**（2026-09-18 复核：143 用例全过，6 个模块 BUILD SUCCESS）
- 覆盖重点：Spring AI 通道与原生 tool-role 循环、H2 内置库启动与迁移、RAG 检索事务与 FULLTEXT 失败隔离、
  模型额度查询（含 401 / 不可达 / 不支持厂商）、记忆与摘要、工具与 MCP、插件运行时、DAG 引擎。

---

## 七、已知限制与降级行为（现状，不是待办）

| 限制 | 表现 |
|---|---|
| 内置向量库为 8 维伪向量 | 无真实嵌入模型时相似度不具参考性，仅供链路演示 |
| H2 无 FULLTEXT 全文索引 | 稀疏检索退化为顺序扫描：功能可用，文档量大时变慢 |
| Redis 缺失 | 会话缓存与配额计数回落内存，重启丢计数（预期行为） |
| Milvus / Kafka / MinIO / LiteLLM 缺失 | 各自降级或跳过，不阻断启动（Kafka 发送失败仅记 debug；桌面 embedded 直接关闭事件总线） |
| 未开启 `SECURITY_ENABLED` | 所有接口无鉴权，仅适合本地/内网 |
| 默认 `JWT_SECRET` / `MODEL_KEY_ENC_KEY` 为占位 | 开启鉴权但仍用默认密钥时启动守卫拒绝启动 |
| 图片视觉 | 依赖 `SPRING_AI_ENABLED=true` 且模型支持视觉；未开启时图片不发送、文本对话正常 |
| 桌面分发形态 | **只用绿色版**（portable 已弃用）：解压一次后直接运行 `dist/green/Agent Platform.exe`；界面 1–2s 弹出、后端约 13s 就绪 |
| ASR / TTS / OCR | 接口与插件链路在，默认 mock，未接真实服务 |
| **皮肤是第三方 JS，无沙箱** | 皮肤代码与宿主同上下文执行；只有用户**显式启用**过的皮肤才会在下次启动时自动加载（记录在 `ap.skin.enabled`） |
| **工具循环只在非流式路径** | `runStream()` 未接工具链路：需要工具调用时必须走非流式 `POST /agent/run` |
| **工具责任链只实现了审计** | 注释里写的「鉴权 / 参数校验 / 限流」未实现；也没有统一超时，超时分散在各工具实现内部 |
| **无工具调用可视化与审批** | 前端只渲染最终文本，工具调用过程与结果对用户不可见；聊天链路没有审批交互 |
| **MCP 仅支持 streamable-http** | 没有 stdio transport：靠 npx/uvx 拉起的 MCP server（含官方 filesystem / git）目前挂不上 |
| 弹窗拖拽不含 Drawer | antd Drawer 是贴边全高面板，拖动无语义，未纳入全局拖拽 |
