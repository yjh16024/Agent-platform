# 阶段验收报告汇总（Phase 1 – 7）

> **合并自**：`docs/phase1~phase7`（含 phase45）8 份自测报告。
> 用途：历史交付物与决策留档 —— **以下数字均为当时快照，不代表当前状态**。
> 当前功能现状与测试规模见 [status.md](status.md)（2026-09-10：140 个测试全绿）。
> 最后核实：2026-09-10。

---

## ⚠️ 阅读前必读：与当前代码不一致的点

| 报告原文 | 现状修正 |
|---|---|
| Phase 2「MCP 接入 🔶 骨架」 | ✅ 已完整实现：`McpClient` 接口 + `HttpMcpClient`/`LocalMcpClient`/`SandboxMcpClient` + `McpClientFactory`；接口 `POST /tools/mcp`、`/tools/mcp/local`、`/tools/mcp/sandbox` |
| Phase 2「稀疏检索为内存实现」 | ✅ 已改为优先 MySQL ngram FULLTEXT，无命中回退顺序扫描 |
| Phase 4.5「配额内存计数」 | ✅ 已改为 Redis INCR+TTL 为主，缺失内存兜底 |
| Phase 6「日志为内存存储、重启丢失」 | ✅ 已 MySQL 持久化（`log_index`），V10 补 `stack_trace` 与时间索引 |
| Phase 5「可观测性：Micrometer + ServiceMonitor + OTel」 | ✅ 已扩展：业务指标（run/llm tokens/latency/tool/log_events）+ Loki 日志推送 + Tempo Span（Zipkin v2）+ Grafana 看板与告警 |
| Phase 1「API 网关 + JWT 鉴权 ✅ Spring Cloud Gateway」 | ⚠️ 与现状不符：独立网关模块未落地（仅 `JwtAuthFilter`），生产鉴权在 core 侧；见 `backlog.md` |
| 累计测试数 5→39→52→63→68→82→88 | 历史快照；当前规模见 [status.md](status.md) |

---

## Phase 1 · 基座

- **交付**：Maven 多模块（6 模块 + 父 POM，Java 21）、MySQL DDL（Flyway V1）、统一 Agent API（`/agent/run` 非流式 + SSE）、Agent CRUD/版本（快照/发布/回滚/Diff）、多模型对接（ModelAdapter 策略 + 工厂 + 路由）、Docker Compose（MySQL/Redis/Milvus/LiteLLM）。
- **关键决策**：模型走 LiteLLM OpenAI 兼容协议（自写 OkHttp 适配器，非 Spring AI）；`local` Mock 兜底；DDL 去掉分区（分区键与主键冲突）。
- **遗留**：网关实际未独立成模块（见上表）。

## Phase 2 · RAG + 工具

- **交付**：知识库 CRUD、`DocumentParser`（Tika 2.9.2）、`Chunker`（模板方法 + 递归/语义/结构策略 + 工厂）、`HybridRetriever`（稠密+稀疏 RRF 融合）、Rerank 与引用溯源、`ToolRegistry` + `ToolExecutor` 责任链 + 内置 calc/search、V2 迁移。
- **关键决策**：`VectorStore` 抽象（默认 in-memory，可换 Milvus）；计算器自研递归下降求值器（JDK 15+ 无 Nashorn，避免注入）。
- **遗留**：Tika 依赖使 jar +45MB（可拆独立解析服务）；中文分词为 2-gram 近似；embedding 未接真实模型时相似度不真实。

## Phase 3 · 编排 + Skills

- **交付**：`NodeType`/`WorkflowNode` 模型、`WorkflowSchemaValidator`（ID 唯一/环检测）、`DagEngine`（运行时遍历 + 条件分支 + 虚拟线程并行）、六类节点执行器、`WorkflowContext` 变量作用域、编排画布 API、Skills 导入（YAML/JSON/URL）、V3 迁移。
- **关键决策**：运行时递归遍历（非静态拓扑排序，因条件分支运行时决定）；`EnumMap<NodeType, NodeExecutor>` 策略分发（新增节点零侵入）。
- **遗留**：Temporal 长流程未落地；Condition 求值器为简化实现；并行分支共享上下文存在竞态（业务上避免同名 output_var）。

## Phase 4 · 插件系统

- **交付**：plugin-sdk SPI、`PluginClassLoader` 类隔离、`PluginRuntime`（attach/detach 幂等）、`ExtensionRegistry`、`AgentPipeline`（before_llm → LLM → after_llm）、插件市场 API、示例插件（AutoReply/Tts）、`BuiltinPluginSeeder`、V4 迁移。
- **关键决策**：示例插件编译进 core 演示端到端；`PluginToolAdapter` 桥接 sdk 与 core 的 `Tool`；Hook 返回约定（before_llm 返回 String=短路，after_llm 返回 Map=附加产物）。
- **遗留**：Hook 全局生效（非 per-agent 隔离）；外部 jar 插件未端到端联调；插件安全沙箱未落地；无依赖 SemVer 解析。

## Phase 4.5 · 多模态

- **交付**：`ContentPart` 密封接口（text/image/audio/video/file/tool_result）、`MultimodalResolver` 能力探测、`FileUploadService` + Controller、ASR 插件化（与 TTS 对称）、`ModelRouter` 能力/成本/健康度路由、`QuotaService` 多租户配额、V5 迁移。
- **关键决策**：sealed interface + Switch 模式匹配（编译期穷尽检查）；`@JsonTypeInfo` 多态反序列化 parts[]。
- **遗留**：真实 OCR/DJL 推理未接（图像/音频为模拟）；多模态模型调用未用真实视觉模型验证。

## Phase 5 · 生产化

- **交付**：多阶段 Dockerfile、K8s 清单（namespace/HPA/KEDA/PDB/NetworkPolicy）、ServiceMonitor + OTel Instrumentation、Helm Chart、Kustomize、部署文档。
- **关键决策**：构建层 Maven-JDK21 / 运行层 JRE-minimal；双轨伸缩（HPA 资源型 + KEDA 事件型）。
- **遗留**：本环境无 Docker，镜像未实际构建；K8s 清单未实操部署；KEDA/OTel 需装 Operator；LangFuse 未集成。

## Phase 6 · 运行日志与智能诊断

- **交付**：`LogEvent`（trace/run/tenant 三维串联）、`FingerprintGenerator`、`LogService`（采集/查询/导出/瀑布图）、三级诊断策略（规则 / 向量相似 / LLM 推理）、`DiagnosisEngine`（责任链 + StructuredTaskScope）、内置规则库、V6 迁移。
- **关键决策**：`ShutdownOnSuccess` 结构化并发扇出（最快有效结果胜出）；指纹规则对齐高频错误（插件类加载/超时、模型连接、Skill manifest、API 状态码）。
- **遗留**：向量检索走 in-memory（历史案例不足）；诊断结果反馈闭环未完整。

## Phase 7 · 提示词智能优化

- **交付**：`OptimizationResult`/`DiffEntry`/`ScoreReport` 模型、`PromptParser`、六种 `PromptEnhancer` 策略链、双阶段优化（规则 + LLM 标记 HYBRID）、6 维质量评分、人格自动融合、V7 迁移。
- **关键决策**：策略链按 `order()` 排序（新增维度 = 新增 Bean）；解析器收紧为显式分节标记（「## 角色」/「你是一名」），避免模糊「你是」误判。
- **遗留**：LLM 语义增强未真实调模型（规则模拟）；优化历史未落库；评分未做 LLM 校准。

---

## 后续（Phase 8+，非阶段报告，记录于此以便衔接）

- 观测阶段 B/C：日志→Loki、Span→Tempo、业务指标→Prometheus + Grafana 统一看板与告警（见 `guides.md`）。
- OOP 教学缺口补齐：MCP 接口化 + 工厂/多态、Skill 命令模式执行器、规则引擎模型适配器（`rule`/`rule-engine` provider）。
- 构建体系：`warmup.bat` 预热 + 在线/离线双模构建。
