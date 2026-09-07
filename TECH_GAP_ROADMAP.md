# 技术缺口清单与分阶段实施路线

> 生成时间：2026-09-07
> 适用项目：agent-platform（智能体平台）
> 说明：本清单仅记录**已通过代码/日志核实**的缺口，不含未经确认的推测。
> 状态图例：✅ 已完成 | ⏳ 待验证/待决定 | 🔲 未开始

---

## 一、已修复项（记录在此，避免重复处理）

| 问题 | 原因 | 修复位置 |
|---|---|---|
| 上传文档后 chunk 为 0（"暂无切分内容"） | 摄取管线把「向量化成功」当作 chunk 入库前置条件，embedding 失败时整段丢弃 | `agent-platform-core/src/main/java/com/agentplatform/core/rag/pipeline/RagPipelineService.java`（拆为 `buildChunk` 始终入库 + `embedChunk` 尽力而为） |
| 对话不检索知识库 | `AgentRunRequest.context.rag` 定义了但运行时从未消费 | `agent-platform-core/src/main/java/com/agentplatform/core/agent/runtime/AgentRuntimeService.java`（新增 `retrieveKnowledge()`，注入提示词 + 回填 references） |
| 运行时 NPE：`rag.systemBlock()` | `retrieveKnowledge()` 可能返回 null，同步分支漏判空 | `AgentRuntimeService.java`（`rag == null` 判空） |
| 检索候选命中数为 0 | native FULLTEXT 查询映射的实体 `@Lob content` 未填充，导致 `content.contains()` 恒 false | `agent-platform-core/src/main/java/com/agentplatform/core/rag/retriever/HybridRetriever.java`（`fullTextCandidates` 改为按 chunkId 用派生查询回查完整实体） |
| 稠密检索失败导致整体检索失败 | `embedQuery` 抛异常未降级 | `HybridRetriever.java`（dense 异常降级为空，保留关键词检索） |

---

## 二、缺口总览（当前未实现 / 未完成）

| # | 缺口 | 证据位置 | 性质 | 状态 |
|---|---|---|---|---|
| G1 | 前端修改未构建同步，新 UI 不可见 | `agent-platform-ui/src` 已改，core `static` 为旧产物 | 阻塞 | ✅ 已解决（已 build + 同步） |
| G2 | Maven 联网解析插件失败 `Connection reset` | `spring-boot-maven-plugin:3.4.0` 下载失败 | 阻塞 | ✅ 已解决（改用 `mvn -o` 离线） |
| G3 | RAG 检索修复未验证 | `HybridRetriever` 已改 | 待验证 | ⏳ 待用户验证 |
| G4 | **对话不会自动调用工具**（无 function calling） | `AgentRunRequest.ToolsConfig` 定义但**全项目无消费代码** | 功能缺口 | ✅ 已解决（P1 完成，同步链路；流式待 P2 后补） |
| G4a | 已有工具无法修改/删除 | 后端仅有删除接口、无来源标记；前端无编辑/删除入口 | 功能缺口 | ✅ 已解决（新增 PUT 修改 + 来源标记 + 前端编辑/删除，内置受保护） |
| G5 | 工作流工具节点租户/上下文硬编码 | `ToolNodeExecutor.java:47` → `ToolContext.of("default", null, null)` | 缺陷 | 🔲 未开始 |
| G6 | `SearchTool` 空 kbIds 时全库检索 | `SearchTool.java:45-50` kbIds 为空则不过滤 | 边界/越权 | 🔲 未开始 |
| G7 | 工具责任链只有审计，缺鉴权/校验/限流 | `core/tool/executor/` 仅 `AuditToolFilter` 一个实现 | 安全/健壮 | 🔲 未开始 |
| G8 | `Capabilities.toolsetIds` 字段存在但未实现 | 全项目搜索无实现逻辑 | 模型误导 | 🔲 未开始 |
| G9 | 项目体积 380MB（target 210MB + node_modules 131MB + jar 140MB） | 实测 | 工程 | 🔲 未开始 |
| G10 | embedding 网关 `localhost:4000` 不可用，语义检索降级 | 日志 `Failed to connect to localhost:4000` | 配置 | ⏳ 待用户决定 |

---

## 三、分阶段实施路线

### P0：打通与验证（前提阶段）

**目标**：让现有功能可被看到、构建、验证。

| 任务 | 内容 | 状态 |
|---|---|---|
| P0-1 | 前端构建同步：`npm run build:prod` | ✅ 已完成 |
| P0-2 | 解决 Maven 联网失败：离线 `-o` 或配置镜像 | ✅ 已完成（离线） |
| P0-3 | 重新打包并重启 core | ✅ 已完成 |
| P0-4 | 验证 RAG 闭环（检索命中 + 对话引用） | ⏳ **交用户验证** |
| P0-5 | 明确 embedding 配置方案 | ⏳ **待用户决定** |
| P0-6 | 清理临时日志文件 | ✅ 已完成 |

**P0-4 验证步骤（用户执行）**：
1. 浏览器打开 `http://localhost:8081/`，`Ctrl + F5` 强刷；
2. 智能体页 → 编辑/新建 → 找「能力绑定 capabilities」→ 勾选知识库 → 保存；
3. 知识库页 → 选库 → 检索词 `光源` 或 `SG-Vision` → 「RAG 检索」→ 期望有命中；
4. 对话页 → 问「SG-Vision Pro 参考售价是多少？」→ 期望答出 `8.8万/13.5万` 并显示来源标签。

---

### P1：对话自动工具调用（最高价值功能缺口）

**目标**：让 LLM 在对话中自主决定调用 `calc` / `search` / HTTP / MCP 工具。

| 任务 | 内容 | 涉及文件 | 状态 |
|---|---|---|---|
| P1-1 | 把 `ToolRegistry` 可用工具（按 `ToolsConfig.allowed` 过滤）转为 function-calling schema 注入模型请求 | `AgentRuntimeService`、`ModelAdapter` | ✅ 已完成 |
| P1-2 | 实现工具调用循环：LLM 返回 tool_calls → `ToolExecutor.run()` → 结果回灌 → 再次请求（限制最大轮次） | `AgentRuntimeService` | ✅ 已完成（run 同步链路） |
| P1-3 | 消费 `ToolsConfig`（`enabled` 开关、`allowed` 白名单），使 G4 定义生效 | `AgentRunRequest.java:68` | ✅ 已完成 |
| P1-4 | 工具调用写入运行日志，结果/引用可展示 | 复用 `LogService` | ✅ 已完成（新增 LogCategory.tool） |

**P1 实现说明**：
- `AgentRuntimeService`：`resolveToolSpecs()` 按 `ToolsConfig.enabled + allowed` 解析；`runToolLoop()` 实现「注入 tools → 模型返回 tool_calls → 逐个执行 → 结果文本回灌 → 再次请求」闭环，`MAX_TOOL_ROUNDS=5`；未启用工具时保持原单轮路径。
- 适配器解析的 `toolCalls` 由 `ToolExecutor` 经 `ToolContext`（含 tenant/agent/runId）执行，结果写 `LogCategory.tool` 日志。
- 前端：`run.ts` 支持 `ToolsRequest`；对话页顶部新增「工具」开关（仅建议支持 function calling 的模型开启）。
- **已知限制**：`runStream()`（流式）暂未接入工具调用循环，仅同步 `run()` 支持。

**风险项（协议扩展）** ✅ 已完成
- `ModelAdapter.ChatRequest` 已新增 `tools`（`List<ToolSpec>`）与 `toolChoice` 字段；新增 `ToolSpec` / `ToolCall` record；`ChatResponse` 新增 `toolCalls`。
- OpenAI 兼容适配器：请求注入 `tools[{type:function,...}]`、`tool_choice`；响应解析 `message.tool_calls`。
- Anthropic 适配器：请求注入平铺 `tools` 数组；响应解析 `content[].tool_use` blocks。
- 旧调用点通过便捷构造器（默认无工具）完全兼容；离线编译已通过。
- 涉及：`ModelAdapter.java`、`OpenAiCompatibleAdapter.java`、`AnthropicAdapter.java`

**验收**：问「123*456 等于多少」，AI 自动调用 `calc` 给出正确结果，日志可见工具调用记录。

---

### P2：正确性、安全与边界修复

| 任务 | 内容 | 优先级 | 涉及文件 |
|---|---|---|---|
| P2-1 | 工具调用传递真实 tenantId / agentId / runId | 高（多租户正确性） | `ToolNodeExecutor.java:47` |
| P2-2 | `SearchTool` 空 kbIds 时拒绝或限定租户，避免全库检索 | 高（越权风险） | `SearchTool.java:45-50` |
| P2-3 | 责任链补齐：`AuthToolFilter`（鉴权）、`ValidationToolFilter`（校验）、`RateLimitToolFilter`（限流） | 中 | `core/tool/executor/` |
| P2-4 | 统一工具执行超时与重试策略 | 中 | `HttpApiTool`、`McpClient` |

**验收**：多租户下上下文正确；空 kbIds 不再全库检索；责任链日志可见鉴权/限流环节。

---

### P3：模型澄清与工程瘦身

| 任务 | 内容 | 涉及 |
|---|---|---|
| P3-1 | `toolsetIds` 二选一：实现工具集实体与管理，**或** 从 `Capabilities` 移除该字段 | `Capabilities.java` |
| P3-2 | 清理构建产物：`core` / `gateway` 的 `target`（约 210MB） | 需先停服务 |
| P3-3 | 前端依赖：不常改前端则删 `node_modules`（131MB），需要时 `npm install` | `agent-platform-ui/` |
| P3-4 | jar 瘦身：`tika-parsers-standard-package` → 按需依赖（PDFBox + POI），140MB → 约 60-80MB | `agent-platform-core/pom.xml:117-121`、`DocumentParser.java` |
| P3-5 | README 补「构建 / 离线构建 / 前端同步」说明 | `README.md` |

---

## 四、待用户决定事项

1. **embedding 配置（G10 / P0-5）**
   - A：模型设置页配置真实可用的嵌入服务（推荐，恢复语义检索）；
   - B：保持现状，接受关键词检索（提问用词需贴近文档原文）；
   - C：改代码让嵌入默认走本地 Mock（不推荐，语义质量差）。

2. **Redis 当前 DOWN**：仅缓存用途，不影响知识库与对话核心功能；需要完整环境时用 `start-core.bat` 启动（会自动拉起 Redis）。

---

## 五、更新记录

| 日期 | 变更 |
|---|---|
| 2026-09-07 | 初版：记录 10 项缺口，划分 P0–P3 四阶段；P0 中构建/打包/重启/清理已完成，P0-4、P0-5 待用户处理 |
| 2026-09-07 | P1 风险项完成：模型协议扩展（ChatRequest.tools / ToolCall / ChatResponse.toolCalls），OpenAI 与 Anthropic 适配器均已支持 |
| 2026-09-07 | P1 主任务完成：对话工具调用（resolveToolSpecs + runToolLoop，MAX_TOOL_ROUNDS=5，LogCategory.tool）；前端对话页加「工具」开关；工具管理补全（来源标记 builtin/http/mcp + PUT 修改 + 前端编辑/删除，内置受保护）。G4/G4a 标记解决 |
| 2026-09-07 | 观测阶段 B+C 完成：`LogService` 增加统一 `LogEventSink` 出口 → `LogMetricsRecorder`（agent_run/llm/tool/log 业务指标进 Micrometer）、`LokiLogExporter`（同一份事件 JSON push Loki）、`TempoSpanExporter`（run→llm/tool 父子 Span 经 Zipkin v2 推 Tempo）；agent 运行埋点 `tool.call` 补充 latency 以便时长追踪。观测栈编排 `docker-compose.observability.yml`（Prometheus/Loki/Tempo/Grafana provisioning：3 数据源联动 + 统一看板 + 3 条告警规则）。全程零新增 Maven 依赖（离线可编译，已 `mvn -o` 验证） |
