# 智能体交互平台（Agent Platform）

基于 **Java 21 (LTS) · Spring Boot 3.4 · MySQL 8.4 · Milvus · Kafka** 的
智能体交互平台：可视化创建/配置/发布智能体，并以统一入口运行对话；围绕运行时可插拔挂载
多模型、会话记忆、RAG 知识库、工具（含 MCP）、工作流、插件、多模态、日志诊断与提示词优化等能力。

> 依据《智能体交互平台设计文档 v3.4》从零实现，Phase 1~7 已交付。

## 核心能力

| 能力 | 说明 |
|------|------|
| 多模型对接 | OpenAI / DeepSeek / Anthropic(Claude) / 通义 / 文心 / 混元等：**直连厂商**或经 LiteLLM 统一协议；新增 Anthropic Messages API 适配器；本地 Mock 兜底保证无外部依赖可跑 |
| 模型配置分层 | 平台级「默认对话模型」+「嵌入模型」绑定（模型设置页，AES-GCM 加密落库、只回掩码）；智能体未单独配置时自动回退，避免每个智能体重复填 Key |
| 会话与上下文 | Session 管理、对话历史持久化与回放、会话归档/删除；前端对话历史切页不丢，点「新对话」才保存进会话历史 |
| RAG | 知识库生命周期、文档摄取管线（解析→切分→向量化→索引）、稠密向量 + MySQL FULLTEXT 稀疏双路 RRF 融合、rerank、chunk 级引用溯源、内容浏览 |
| Skills（标准目录） | 遵循 **Agent Skills 开放标准**：`skills/<name>/SKILL.md`（YAML frontmatter + 提示词正文）+ `scripts/ references/ assets/`；下载的技能丢进目录后「扫描同步」即被识别，运行时注入系统提示词 |
| 工作流编排 | 自研 DAG 引擎（条件分支 + 虚拟线程并行）、节点 Schema 校验 |
| 多模态 | parts[] 统一消息模型、文件上传/下载、TTS/ASR 插件化 |
| 工具调用 | 工具注册中心、HTTP 工具热注册、**MCP(Streamable HTTP) 接入**、责任链鉴权 |
| 插件系统 | plugin-sdk SPI、内置插件 + **外部 jar 插件（ClassLoader 隔离）**、市场导入/删除、Attach/Detach 热插拔 |
| 运行日志与诊断 | 结构化日志 **MySQL 持久化**（重启不丢）、trace/瀑布图、三级诊断策略（规则→向量→LLM） |
| 提示词优化 | PromptEnhancer 策略链、规则双阶段、6 维评分 + LCS 有序 Diff |
| 智能体管理 | 配置即资产；**删除即物理删除（含版本/插件绑定级联）**，列表默认隐藏已归档；版本快照/发布/回滚/Diff |

## 技术栈

- **语言/框架**：Java 21（虚拟线程 + `ScopedValue`）、Spring Boot 3.4、Spring Cloud 2024
- **数据库**：MySQL 8.4（业务 + 日志）+ Milvus 2.x（向量库，可切 in-memory）
- **缓存**：Redis 7；**消息**：Kafka（可选开关）
- **模型网关**：LiteLLM Proxy（可选，直连模型时可不用）
- **前端**：React + Vite + antd 仪表盘（同源部署于 core）
- **构建**：Maven + Flyway（DB 迁移，当前至 V10）

## 模块结构

```
agent-platform/
├── agent-platform-common/        # 公共：ApiResponse/BizException/IdGenerator/JwtUtil/TraceContext(ScopedValue)
├── agent-platform-model/         # 领域：Entity/Record/Repository（含日志 LogIndex、仓储）
├── agent-platform-plugin-sdk/    # 插件 SDK（Plugin/AgentHook/HookPoint/PluginManifest）
├── agent-platform-infra/         # 基础设施占位
├── agent-platform-gateway/       # API 网关（JWT 过滤器；当前主要为占位，生产建议直连 core 鉴权）
├── agent-platform-core/          # 主应用：13 个功能子域（agent/rag/tool/workflow/skill/plugin/...）
├── agent-platform-deploy/        # Docker/K8s/Helm 部署
├── agent-platform-plugins/       # 独立插件工程示例（auto-reply / tts）
├── agent-platform-ui/            # React 仪表盘（build-ui.bat 构建进 core static）
└── docs/                         # 文档
```

## 快速启动（Windows，本机 MySQL 已运行）

```bat
start-core.bat rebuild     # 一键：定位 JDK21/Maven → 起本地 Redis → 打包 → 启动 8081
```

- 前端改过源码后务必先重新构建：`cd agent-platform-ui && npm run build:prod`（产物自动同步到
  `agent-platform-core/src/main/resources/static/`），否则仪表盘仍显示旧的编译版本。
- 浏览器访问 **http://localhost:8081/**。需强刷（Ctrl+F5）避免旧 JS 缓存。

### 用 Docker 拉起可选基础设施

```bash
docker compose up -d   # MySQL / Redis / Milvus(+etcd+minio) / MinIO / LiteLLM / Kafka
```

| 能力 | 环境变量 | 默认 |
|------|----------|------|
| RAG 向量存储 | `VECTOR_STORE=milvus` | `in-memory` |
| 文件对象存储 | `STORAGE_TYPE=minio` | `local` 本地磁盘 |
| Kafka 事件总线 | `EVENTS_ENABLED=true` | 关 |
| core 侧 JWT 鉴权 | `SECURITY_ENABLED=true` | 关（默认密钥会给出告警） |

> 全部默认关闭，保证无 Docker/无真实模型时仍可本地演示（本地 Mock 兜底、内存向量、本地磁盘）。

## 快速验证

```bash
# 健康检查
curl http://localhost:8081/actuator/health

# 登录获取 JWT（未配置 AUTH_USERNAME/PASSWORD 时演示签发）
curl -X POST http://localhost:8081/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"tenant_id":"t1","user_id":"u1"}'

# 创建智能体（不传 modelBinding → 自动回退「模型设置」里的默认对话模型）
curl -X POST http://localhost:8081/api/v1/agents -H "X-Tenant-Id: t1" \
  -H "Content-Type: application/json" \
  -d '{"name":"客服助手","systemPrompt":"你是一名客服专家"}'

# 运行对话（可带 sessionId 触发会话持久化；stream:true 走 SSE）
curl -X POST http://localhost:8081/api/v1/agent/run -H "Content-Type: application/json" \
  -d '{"agentId":"agent_xxx","messages":[{"role":"user","content":"你好"}],"metadata":{"tenant_id":"t1"}}'
```

## 仪表盘功能域

智能体管理（新建/编辑/版本/发布回滚/删除）· 对话运行（历史常驻 + 新对话归档）· 会话历史 ·
知识库（上传/浏览内容/删除）· 工作流 · Skills（打开目录/扫描同步/上传导入/查看编辑/删除）·
插件市场（导入/删除/挂载）· 工具调试（含 MCP 接入）· 文件 · 模型设置（默认对话 + 嵌入绑定）·
租户配额 · 运行日志 · 诊断 · 提示词优化。

## API 概览（主要管理面）

| 分组 | 关键端点（新增标注 ✚） |
|------|------|
| 智能体 | `/agents` CRUD/克隆/版本/发布/回滚/`diff` |
| 会话 | `/sessions` CRUD；`/sessions/import` ✚ 整轮对话入库；`/sessions/{id}/messages` |
| 知识库 | `/knowledge-bases`；`/{id}/documents`、`/{id}/chunks` ✚ 内容浏览；`/documents/{docId}` ✚ 删文档 |
| Skills | `POST /skills` 新建；`PUT /skills/{id}` 编辑；`/import` 文本导入；`/sync` ✚ 扫描目录同步；`/upload` ✚ 上传 zip/SKILL.md；`/import-folder` ✚；`/open-folder` ✚；`/dir` ✚；`/{id}/files`、`/{id}/file` ✚ 浏览资源；DELETE |
| 插件 | `/plugins/import` ✚、`/plugins/{id}` ✚ 删除（平台内置除外）、attach/detach、市场 |
| 工具 | `/tools/mcp` ✚ MCP 接入、`/tools/{name}` ✚ 卸载、HTTP 注册、invoke |
| 模型配置 | `/model-config`（`/embedding`、`/chat` ✚ 默认对话模型） |
| 日志 | `/logs` 查询/导出/瀑布图/采集；`/logs/purge` ✚ 清理 |
| 其他 | `/files`、`/quotas`、`/diagnosis`、`/prompt` |

> 命名契约：强类型 record 走 camelCase；自由 Map 入参走 snake_case（详见 docs/TODO.md）。

## 单元测试

```bash
mvn test    # 100 个用例：服务层 + 诊断/日志/RAG/工作流/插件/提示词等
```

## 关键设计决策

1. **模型接入走直连 / 网关双通道**：智能体绑定直连厂商（OpenAI 兼容或 Anthropic Messages），
   否则回退平台默认对话模型 → LiteLLM 网关 → 本地 Mock；上游错误原样透出，不静默 Mock。
2. **Record + sealed interface**：领域模型（Persona/GenerationConfig/ContentPart/…）用 JDK 21
   Record/密封接口建模，switch 模式匹配获得编译期穷尽检查。
3. **ScopedValue 替代 ThreadLocal**：全链路 trace 上下文传递，根治虚拟线程下上下文串号。
4. **删除语义 = 物理删除**：智能体/知识库/工作流/Skill/会话/插件删除即物理清理并级联子资源，
   列表默认过滤已归档，杜绝「删了还在」的悬空态。
5. **日志落 MySQL**：运行日志持久化（log_index），重启不丢；DB 不可用自动降级有界内存队列，
   保证日志采集永不拖垮主流程。

## 技术文档

| 文档 | 说明 |
|------|------|
| [technical-guide.md](docs/technical-guide.md) | 如何添加新模型/工具/插件/诊断规则/优化策略 |
| [deployment.md](docs/deployment.md) | K8s 部署、环境变量、弹性策略 |
| [demo.md](docs/demo.md) / [demo-script.sh](docs/demo-script.sh) | 端到端演示 |
| [TODO.md](docs/TODO.md) | 当前待办与已踩坑清单（含 API 契约/环境注意） |
| 其余 `docs/` | 各阶段自测报告与可行性/技术设计（历史存档） |
