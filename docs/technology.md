# 技术选型与实现说明

> **本文回答两个问题**：每个已实现的功能**用什么技术做的**、**为什么选它**（以及放弃了哪些替代方案）。
>
> | 文档 | 回答的问题 |
> |---|---|
> | [status.md](status.md) | 已经实现了什么（功能/接口/开关/测试） |
> | **technology.md（本文）** | 用**什么技术**实现、**为什么**这样选 |
> | [design.md](design.md) | 设计方案与可行性结论（路线对比、归档方案） |
> | [backlog.md](backlog.md) | 还没做什么、契约与踩坑 |
> | [guides.md](guides.md) | 怎么扩展、部署、观测 |
>
> 最后核实：**2026-09-14**（版本号取自根 `pom.xml` 与 `agent-platform-ui/package.json`）。

---

## 一、技术栈总览

| 层 | 技术 | 版本 | 用途 |
|---|---|---|---|
| 语言/运行时 | **Java 21**（虚拟线程、`ScopedValue`、`StructuredTaskScope` 预览特性） | 21 | 后端全部 |
| 应用框架 | **Spring Boot** | 4.1.1 | 容器、Web、条件装配 |
| 持久化 | **Spring Data JPA** + Hibernate | 4.1.1 / 7.4.5 | 实体映射与仓储 |
| 迁移 | **Flyway**（mysql / h2 双目录） | 12.4.0 | 版本化 DDL |
| 数据库 | **MySQL 8.4**（默认）/ **H2** file 模式（`embedded`） | MySQL driver 8.4.0 / H2 2.3 | 主库 / 免装库分发 |
| 连接池 | HikariCP | 5.1.0 | 数据源 |
| 缓存 | **Redis**（可选，缺失降级内存） | — | 会话近期上下文、配额计数 |
| 向量库 | `VectorStore` 抽象：**in-memory**（默认）/ **Milvus**（REST v2） | — | RAG 稠密检索 |
| HTTP 客户端 | **OkHttp** | 4.12.0 | 模型调用、Embedding、余额查询、Milvus |
| AI 框架（可选通道） | **Spring AI**（`spring-ai-model` / `openai` / `anthropic` / `tika-document-reader`，非 starter；底层改用官方厂商 SDK） | 2.0.1 | 协议层 / 原生 tool-role / RAG 解析切分 / 观测桥接 |
| 文档解析 | **Apache Tika** | 2.9.x | PDF/Word/Excel/MD 等多格式文本抽取 |
| JSON | **Jackson 3**（`tools.jackson.*`） | 3.1.5 | 序列化与多态 parts[] |
| 鉴权 | JJWT | 0.12.6 | JWT 签发与校验 |
| 映射/工具 | Lombok 1.18.34、MapStruct 1.5.5、Guava 33.3、Commons | — | 样板代码与工具 |
| 前端 | **React 18.3** + **TypeScript 5.6** + **Vite 5.4** + **antd 5.21** + zustand 4.5 + react-router 6.28 | — | 仪表盘 SPA |
| 工作流画布 | **FlowGram**（`@flowgram.ai/fixed-layout-editor`，MIT）+ 官方内联物料包 `fixed-semi-materials` | 1.0.15 | 拖拽式工作流编排画布（与 Coze 工作流同源内核） |
| 桌面壳 | **Electron** | 33.3.1 | 桌面窗口与生命周期 |
| 桌面运行时 | **jlink** 精简 JRE + **electron-builder** | — | 免装 Java、**绿色版（zip / dir）** 打包 |
| 观测 | Micrometer + Prometheus + Loki + Tempo + Grafana | — | 指标 / 日志 / 链路 |
| 部署 | Docker 多阶段 + K8s（Kustomize/Helm）+ HPA/KEDA | Spring Cloud 2025.1.3 | 生产化 |
| 测试 | JUnit 5 + Mockito 5.12 + Spring Boot Test + 本地 `HttpServer` | — | 143 个测试 |

---

## 二、按功能的技术选型与理由

### 2.1 运行时：Java 21 的预览特性

**用什么**：虚拟线程处理模型/工具等 I/O 等待；`StructuredTaskScope` 做诊断扇出；`ScopedValue` 传递链路上下文。

**为什么**：

- 平台几乎全是**阻塞式 I/O 等待**（等模型流式返回、等工具 HTTP），虚拟线程让"一线程一请求"的朴素写法
  不必手动维护线程池，也不用把全链路改成响应式（改造成本高、调试困难）。
- 诊断需要"多个策略并发、取最快有效结果"，`StructuredTaskScope.ShutdownOnSuccess` 天然匹配，
  比手写 `CountDownLatch` + 取消标志更可靠。
- 代价明确：**预览特性要求 `--enable-preview`**，因此 `pom.xml`（编译）、surefire、启动脚本与
  `desktop/main.js` 全部显式带上该参数（见 [backlog.md](backlog.md) 第 12 条）。

**放弃的方案**：响应式（WebFlux + Reactor 全链路）——模型/工具/RAG/DB 都要改，收益仅是吞吐，
而本项目瓶颈在上游模型而非自身线程；且响应式堆栈排查成本高。

### 2.2 应用框架与持久化：Spring Boot 4.1 + JPA + Flyway

**用什么**：Spring Boot 容器、`@ConditionalOnProperty` 条件装配、Spring Data JPA 仓储、Flyway 版本化迁移。

**为什么**：

- **"缺谁都能跑"是硬需求**：Redis / Milvus / Kafka / MinIO / LiteLLM 全为可选。
  Spring 的 `@Autowired(required = false)` + `@ConditionalOnProperty` 让"注入了就生效、没注入就跳过"
  成为框架级能力，而不是散落的 `if (x != null)`。
- **JPA 而非 MyBatis**：同一套实体要能同时跑 MySQL 与 H2（桌面内置库），JPA 的方言抽象让切换只改
  `spring.datasource.url`；Flyway 再提供 `db/migration/mysql` 与 `db/migration/h2` 两份 DDL 解决语法差异。
- **为什么不用 Spring AI 的 starter / 不引入 MyBatis-Plus 等**：保持依赖树可控，
  避免自动配置悄悄改变启动行为（Spring AI 只引三个非 starter 模块，即为此目的）。

### 2.3 模型适配层：自研 `ModelAdapter` + OkHttp，Spring AI 作为可选通道

**用什么**：

- 自研四个适配器：`OpenAiCompatibleAdapter`（OpenAI/DeepSeek/通义/混元/文心 + LiteLLM 中转）、
  `AnthropicAdapter`（Messages API）、`RuleEngineModelAdapter`（规则确定性应答）、`MockModelAdapter`（本地兜底）；
- `ModelRouter` 按 `provider` 选择，`ModelBindingService` 决定最终凭证（三级回退）；
- **可选通道**：`SPRING_AI_ENABLED=true` 时协议层换成 Spring AI 2.0.1 的 `ChatModel`，工具循环换成原生
  tool-role，可观测换成 `ChatModel` 自动 Observation。

**为什么自研为主**：

1. **每请求凭证**：用户自带 Key、Key 存库加密、按智能体/平台/全局三级回退——需要在"每次调用"粒度
   传入 baseUrl 与 apiKey，而不是启动时绑定一个全局客户端；Spring AI 早期版本无此模型。
2. **协议层之上还有自研资产**：提示词四层组装、插件 Hook 管线、工具责任链、观测埋点位置。
   换框架会把这套体系打散（见 [design.md](design.md) LangChain 评估的同类理由）。
3. **OkHttp 而非 WebClient**：调用是阻塞式（与虚拟线程配合）、需要精细的连接/读超时与自定义 UA；
   不需要完整响应式栈。SSE 手写按行解析，帧结构简单可控。

**为什么还要保留 Spring AI 通道**：把"厂商协议实现"这件易变的事交给官方维护（新厂商、协议变更、
重试与退避），同时保留**一键回退**（默认关闭）。两条链路都有测试守护，凭证层（AES-GCM / 掩码 / 三级回退）
**一行未改**。

### 2.4 凭证安全：AES-GCM + 掩码 + 三级回退

**用什么**：`ModelKeyCrypto`（AES-GCM，密钥来自 `MODEL_KEY_ENC_KEY`）、落库密文、接口只回掩码。

**为什么**：

- **GCM 是认证加密**：既保密又能发现密文被篡改，比 CBC 少一个"需要额外 MAC"的坑；
- **只回掩码**（`sk-****1234`）：即使前端或日志被截取，也拿不到明文；
- **解密失败按未配置处理**：主密钥变更不应导致整个 RAG 摄取/对话崩掉（`SafeDecrypt`），
  但智能体绑定场景用严格解密并透出错误，让用户知道要重新填 Key。

### 2.5 记忆：Redis List 做短期缓存 + 懒压缩做中期摘要

**用什么**：`SessionRecentCache`（Redis List，25 轮/50 条/24h TTL）；`SessionSummaryService`
（会话超 40 轮后把即将被截断的早期轮次 rollup 成要点，写 `session_def.summary`）。

**为什么**：

- **Redis List 而非 String/Hash**：`RPUSH` + `LTRIM` 天然表达"最近 N 条"，TTL 自动过期，
  读优先缓存、miss 回 DB 并回填，逻辑最短。
- **懒压缩而非每轮摘要**：摘要要花模型调用，只在"即将丢历史"时触发（40 轮阈值），
  把成本花在真正需要的地方；摘要幂等（`summary_turn` 记录进度），重复触发不会重复计算。
- **Redis 缺失全程降级 DB**：功能不减，只少一层加速——这是"外部依赖可退化"原则的直接体现。

### 2.6 RAG：Tika 解析 + 策略切分 + 混合检索 + 向量库抽象

| 环节 | 用什么 | 为什么 |
|---|---|---|
| 解析 | Apache Tika | 格式极多（PDF/Word/Excel/PPT/MD/TXT），自研不现实；Tika 统一输出文本，代价是 jar 增量（曾 +45MB） |
| 切分 | 模板方法 + 策略（递归 / 语义 / 结构），可选 Spring AI `TokenTextSplitter` | 不同文档适配不同切法：按标点递归最通用，按标题结构适合技术文档，按语义适合长议论文 |
| 检索 | **混合检索**：稠密（向量余弦）+ 稀疏（关键词）按 **RRF** 融合，再 Rerank | 纯向量对**专有名词/编号/型号**召回差，纯关键词对**同义改写**召回差；两者互补，RRF 免调权重 |
| 向量库 | `VectorStore` 接口：in-memory（8 维伪向量，默认）/ Milvus（REST v2） | 无外部依赖时必须能跑（演示/离线）；接真实检索时切 Milvus 即可，业务代码不改 |
| 稀疏检索 | MySQL **ngram FULLTEXT** 索引取候选，无命中回退顺序扫描 | FULLTEXT 避免全表扫描；小知识库与演示场景靠回退保证仍有结果 |
| H2 场景 | 跳过 FULLTEXT（`rag.fulltext.enabled=false`），并把该查询放进**独立事务** | H2 不支持 `MATCH..AGAINST`；且 native SQL 失败会让 Hibernate 把外层事务标记 rollback-only，导致"结果算出来了却提交失败"（已修，见 [status.md](status.md)） |

**为什么 embedding 与 chat 分开配置**：查询向量必须与文档向量同空间，所以嵌入模型是平台级唯一一份；
而对话模型可按智能体覆盖。两者用途不同、成本量级不同，混在一起会互相牵制。

### 2.7 工具与 MCP：注册中心 + 责任链 + 持久化 + 三种 MCP 传输

**用什么**：`ToolRegistry` 统一登记（内置 / HTTP 注册 / MCP）；`ToolExecutor` 责任链（鉴权/限流/审计）；
`tool_registration` 表持久化 HTTP 工具；MCP 走 `McpClient` 接口 + 工厂（Http / Local / Sandbox）；
内置天气工具查 `wttr.in`。

**为什么**：

- **唯一注册中心**：模型声明、执行、白名单、审计都从一处取，避免"工具有三个来源、行为各不相同"。
- **HTTP 工具必须持久化**：动态注册本该免重启，但重启即丢会让用户白配；落到 `tool_registration`（V11）
  后重启自动恢复。
- **责任链而非散落校验**：鉴权、限流、审计、超时、结果截断集中一处，新增策略即插入节点。
- **MCP 三种传输各有用途**：远程 HTTP 接外部服务；进程内 Local 用于轻量内置；**Sandbox** 用子进程 +
  目录隔离 + 解释器白名单 + 超时 + 输出截断，把"执行不可信脚本"的风险关进笼子。
- **天气工具选 `wttr.in`**：公开、免 Key、纯文本返回，适合作为"HTTP 工具 + 参数转换"的最小可验证样例
  （城市名做 URL 编码后拼接）。

### 2.8 插件与 Skills：SPI + 类隔离；标准目录 + 命令模式

**用什么**：plugin-sdk 定义 `Plugin` / `ToolProvider` / `AgentHook` SPI；`PluginClassLoader` 隔离依赖；
`AgentPipeline` 提供 `before_llm` / `after_llm` 两个钩子点（可短路、可附加产物）；
Skills 采用 Agent Skills 开放标准目录 `skills/<name>/SKILL.md`，执行器为命令模式 + 注册表。

**为什么**：

- **独立 ClassLoader**：插件自带依赖版本，避免与主程序的 Jackson/OkHttp 冲突；
  卸载时反注册钩子与工具，实现真热插拔。
- **Hook 只留两个点**：`before_llm` 能覆盖"自动回复/缓存命中/内容审核"，`after_llm` 能覆盖
  "TTS/格式化/敏感词"，两个点即可覆盖绝大多数诉求，且管线语义简单（返回 String = 短路，返回 Map = 附加产物）。
- **Skills 用开放标准而非自研格式**：目录即交付（`SKILL.md` + 可选 `scripts/ references/ assets/`），
  下载放进去、点「扫描同步」即可用，不需要打包、不需要改代码；这也让第三方技能可复用。
- **三种执行器（prompt / script / http）**：覆盖"纯提示词""本地脚本""远程接口"三类技能形态，
  新增形态只需实现 `SkillExecutor`。

### 2.9 工作流：拖拽画布（FlowGram）+ 自研 DAG 引擎

**用什么**：

- **前端画布**：**FlowGram 固定布局**（`@flowgram.ai/fixed-layout-editor` **1.0.15**，**MIT**，与 Coze 工作流同源内核）。
  固定布局的含义是**「顺序即执行顺序」**，所以交互形态是「节点排队 + 拖拽重排 + 内联加号插入」，
  **没有连线**（这是与自由画布的本质区别，也正是 Coze 的形态）。
- **内联交互直接用官方物料包**：`@flowgram.ai/fixed-semi-materials` 的 `defaultFixedSemiMaterials`
  （含 collapse / branch-adder / drag-node / draggable-adder / 拖拽高亮等 **10 个 render key**）。
  **为什么必须这样**：FlowGram 画布是 **headless** 的 —— 内核只注册 `node-render`，而渲染服务会无兜底地
  索取其它 key，缺任何一个就抛 `Unknown render key` 打崩整页；自研整套内联 UI 成本极高，官方这套最全最稳。
  只把「+」加号覆盖成平台自研（要弹自己的 11 类节点库）。代价是多带一套 Semi UI 依赖
  （`@douyinfe/semi-ui` + `semi-icons`；其 CSS 经 `vite.config.ts` 的 `resolve.alias` 引入，
  因为 semi 的 `exports` 未暴露 `dist/css/*`）；**节点卡片与配置表单仍用 antd 自绘**，视觉风格统一。
- **拖拽交互的两个关键点**（详见 [backlog.md](backlog.md) 第 19–21 条）：**节点拖拽必须由节点组件显式调用**
  `useStartDragNode().startDrag(e, { dragStartEntity: node })`（FlowGram 内核**不会自动绑定**）；
  **画布平移/缩放**用 `ineractiveType: 'MOUSE'`（左键拖空白平移 + 滚轮缩放），可靠入口是运行时
  `usePlaygroundTools().setInteractiveType('MOUSE')`。
- **后端引擎**：`WorkflowSchemaValidator`（ID 唯一 / 环检测 / **类型必须有执行器**）+ `DagEngine`
  （运行时递归遍历、条件分支、虚拟线程并行、**节点级执行轨迹**）+
  `EnumMap<NodeType, NodeExecutor>` 策略分发（画布可拖出的 11 类节点都有对应执行器）。

**为什么这样选**（画布不 fork Coze、也不自研）：

- **不 fork Coze Studio 前端**：它是 Rush+PNPM monorepo + Rsbuild + 自研设计系统，且强耦合其 Golang 后端契约。
- **不自己画**：FlowGram 官方说明其存在理由就是"用 ReactFlow 这类通用图库解决不了**节点数据管理、动态表单、
  数据校验、变量作用域链**"——恰好是自研画布最耗时的部分。
- **取 FlowGram 而非 React Flow**：FlowGram 是"开箱的流程编辑方案"（含表单引擎 / 变量引擎 / 分支复合节点），
  React Flow 只是渲染引擎，用它等于把前者已做的工作重做一遍。
- 完整方案对比（含 iframe 嵌入 Coze 的否决理由）见 [workflow-canvas-feasibility.md](workflow-canvas-feasibility.md)。

**为什么**：

- **运行时递归遍历而非静态拓扑排序**：条件分支走哪条边是**运行期**才能决定的，静态排序无法处理。
- **`EnumMap` 策略分发**：新增节点类型 = 新增一个 Bean，零侵入既有代码。
- **不引入 Temporal**：需要独立服务与持久化，对"短链路编排"过重（已登记为未实现，见 [backlog.md](backlog.md)）。
- **不引入 LangGraph4j**：见 [design.md](design.md)，成本与体系冲突大于收益，改为借鉴其思想演进 `DagEngine`。

### 2.10 多模态：sealed interface + Jackson 多态

**用什么**：`ContentPart` 密封接口（text / image / audio / video / file / tool_result）；
Jackson `@JsonTypeInfo` 反序列化 `parts[]`；`MultimodalResolver` 做模型能力探测。

**为什么**：

- **sealed + switch 模式匹配**：新增一种 part 类型时**编译期**就会提示所有未覆盖的分支，
  避免"运行时才发现漏处理"。
- **parts[] 而非纯字符串**：一次消息可能同时含文本与附件，结构化表达才能分别路由
  （文本拼接、文件抽文本、图片交给视觉模型）。

### 2.11 日志与诊断：事件化日志 + Sink 派发 + 结构化并发

**用什么**：`LogEvent`（trace / run / tenant 三维串联）→ `LogService` 落库并派发到
`LogEventSink`（Micrometer 指标 / Loki 日志 / Tempo Span）；三级诊断（规则 → 向量 → LLM）
经 `DiagnosisEngine` 责任链，`StructuredTaskScope.ShutdownOnSuccess` 取最快有效结果。

**为什么**：

- **Sink 派发**：一个日志出口对多个目标，新增观测后端 = 新增 Sink；未配置 Loki/Tempo 时**完全静默**
  （不启线程、不发包），保证零配置可跑。
- **三级诊断而非直接上 LLM**：规则能命中的就别花模型钱与时间；向量找相似历史案例；LLM 兜底做推理。
- **`ShutdownOnSuccess`**：多策略并发，谁先给出有效结论就取消其余，比串行快且比手写取消简单。

### 2.12 可观测：Micrometer + Loki + Tempo + Grafana

**用什么**：Micrometer 暴露 `/actuator/prometheus`；`LogEventSink` 推 Loki；由 run/llm/tool 标记日志
合成 `agent.run → llm.chat / tool.call` 的 Zipkin v2 结构推 Tempo；Grafana 预置 3 数据源联动与告警规则。

**为什么**：选"每个信号最轻的那个"而不是 ELK/Jaeger 全家桶——日志用 Loki（只索引标签，
存储便宜）、链路用 Tempo（与 Loki 天然关联）、指标用 Prometheus，`docker-compose.observability.yml`
一键起，单机可跑。

### 2.13 配额与额度：Redis INCR + TTL；厂商余额 API

**用什么**：`QuotaService` 以 Redis `INCR` + 周期 TTL 计数（内存兜底），限额与累计用量落 `tenant_quota`；
新增 `ModelBalanceService` 用 OkHttp 调各厂商余额接口。

**为什么**：

- **INCR + TTL**：原子（多实例安全）、按周期键自然过期、无需定时任务清理；每 100 次才回写 DB，
  避免"每次调用写一次库"。
- **每 100 次沉淀**：可接受少量精度损失换取写放大骤降，重启后以 DB 值为准。
- **额度查询按厂商各自实现**：业界**没有统一标准**——DeepSeek `/user/balance`、硅基流动
  `/v1/user/info`、Moonshot `/v1/users/me/balance` 字段各异；OpenAI / 通义等**官方未开放**，
  因此明确回"该厂商未提供余额查询接口"而不是伪造结果。
- **异常一律转结构化结果**：网络/401/路径不存在都回 `ok=false` + 原因，绝不抛 500 拖垮接口。

### 2.14 内置库与桌面分发：H2 + jlink + Electron

**用什么**：`--spring.profiles.active=embedded` 切 H2 file（MySQL 兼容模式）；`desktop/main.js`
用 Electron 拉起后端并托管窗口；jlink 生成精简 JRE；electron-builder 出 **zip 绿色版**
（dir target 再镜像到 `dist/green`）。

**为什么**：

- **H2 而非 SQLite**：项目已用 Flyway + JPA，H2 的 MySQL 兼容模式让大部分既有 DDL 与 SQL 可复用，
  只需补一份 h2 迁移目录；SQLite 则要重写方言与部分 SQL。
- **Electron 而非 jpackage**：窗口、生命周期、托盘、打包与更新生态成熟，且前端已有完整 Web UI 可**直接复用**；
  jpackage 需自行解决窗口与启动页体验。代价是体积（含 Electron 与内嵌 JRE）。
- **弃用 portable 单文件、只出绿色版（2026-09-12）**：portable 每次运行都要把约 640MB 解压到 `%TEMP%`，
  且解压期间无窗口（冷启动多等 20–30s）。绿色版解压一次后 **界面 1–2s 弹出、后端 `Started in ~12.8s`**；
  优化手段：JVM `-XX:TieredStopAtLevel=1` / `-XX:+UseSerialGC` / `-Dspring.main.lazy-initialization=true`、
  embedded 排除 Kafka/Redis 自动配置、健康探测间隔 1500ms→300ms、桌面日志降 INFO。
- **jlink 而非要求用户装 JRE**：桌面分发的底线是"双击就能用"，内嵌精简 JRE 是唯一可靠做法；
  同时保留 `--enable-preview`（与后端预览特性配套）。

### 2.15 前端：React + Vite + antd，同源托管

**用什么**：React 18 + TypeScript + Vite 5 + antd 5 + zustand（全局租户/令牌）+ HashRouter；
构建产物同步到 `agent-platform-core/src/main/resources/static/`；SSE 用 `fetch` + `ReadableStream` 手写切帧。

**为什么**：

- **同源托管而非独立部署**：免 CORS、少一个部署单元、桌面壳里也只需拉起一个后端，运维与分发都更简单。
- **HashRouter 而非 BrowserRouter**：前端由 Spring Boot 静态托管，没有服务端路由回退配置，
  Hash 路由刷新不会 404。
- **手写 SSE 解析而非 `EventSource`**：`EventSource` 只支持 GET，而 `/agent/run` 需要 POST 传大 body，
  只能用 `fetch` 流式读取并按 `\n\n` 切帧。
- **Vite 而非 webpack**：构建快、配置少，`build:prod` 里一步 `sync-dist.mjs` 就能把产物送进后端静态目录。

### 2.16 测试策略：真实链路优先，本地 HTTP 服务替代外部依赖

**用什么**：JUnit 5 + Mockito + Spring Boot Test（`embedded` + H2 内存库）；模型/额度相关用 JDK 自带
`com.sun.net.httpserver.HttpServer` 起本地服务模拟厂商响应。

**为什么**：

- **不 mock 掉关键链路**：Spring AI 通道、检索事务、H2 迁移这些"最容易出问题的地方"用真实容器/真实事务验证，
  避免"单测全绿、一跑就崩"。
- **本地 `HttpServer` 而非 WireMock**：零新增依赖即可模拟指定路径、状态码（含 401）与响应体，
  测试可离线、稳定。
- **回归测试锁住踩过的坑**：例如"FULLTEXT 失败不得污染外层事务"、"检索不得返回空"都已有专门测试，
  防止后续重构再次引入。

---

## 三、关键技术取舍一览

| 决策点 | 采用 | 放弃 | 主要原因 |
|---|---|---|---|
| 模型协议层 | 自研适配器为主 + Spring AI 可选通道 | 只用 Spring AI / 只用自研 | 需要每请求凭证与可回退；自研易维护但厂商协议变动成本高，故双通道 |
| 编排框架 | 自研 `DagEngine` + 借鉴 LangGraph 思想 | LangChain4j / LangGraph4j | 12–20 人日改造、与凭证/插件/责任链体系冲突、jar 再增 |
| 并发热模型 | 虚拟线程 + 结构化并发 | 全链路响应式 | 瓶颈在上游模型，响应式改造成本与排查成本不划算 |
| 长流程持久化 | 内存 DAG（短链路） | Temporal | 需独立服务与持久化，当前场景过重 |
| 日志存储 | MySQL `log_index` + Loki | ES / ClickHouse | 中等规模够用；超大规模检索聚合仍是缺口（已登记） |
| 向量库 | `VectorStore` 抽象（in-memory / Milvus） | 直接绑死 Milvus | 必须支持"无外部依赖可跑" |
| 检索策略 | 向量 + FULLTEXT 混合（RRF） | 纯向量 / 纯关键词 | 互补覆盖专有名词与同义改写 |
| 数据库 | MySQL（默认）+ H2（内置分发） | 仅 MySQL / 仅 SQLite | 桌面必须免装库；H2 兼容模式可复用既有 DDL |
| 桌面方案 | Electron + jlink | jpackage / Tauri / GraalVM | 前端复用度最高、窗口体验成熟；GraalVM 与插件反射冲突 |
| 前端路由 | HashRouter | BrowserRouter | 无服务端路由，刷新不 404 |
| 配置来源 | 环境变量 + UI 绑定 | `/api/v1/setup` 向导 | 少一个未验证的新接口，复用既有「模型设置」页 |
| 快速计算 | 自研递归下降求值器 | Nashorn / 表达式注入 | JDK 15+ 已移除 Nashorn，自研可避免代码注入 |

---

## 四、版本矩阵（关键依赖）

```text
Java            21（--enable-preview）
Spring Boot     4.1.1          Spring Cloud  2025.1.3      Spring AI  2.0.1（可选通道）
MySQL driver    8.4.0          Flyway 12.4.0  HikariCP 5.1.0
Hibernate       7.4.5          Tomcat 11.0.24 Lettuce 7.5.2
OkHttp          4.12.0         Jackson 3.1.5  JJWT 0.12.6
Lombok          1.18.34        MapStruct 1.5.5 Guava 33.3.0-jre
Mockito         5.12.0         Testcontainers 1.20.1
React           18.3.1         TypeScript 5.6.3  Vite 5.4.11  antd 5.21.6
zustand         4.5.5          react-router-dom 6.28.0
FlowGram        1.0.15         Semi UI       2.103
Electron        33.3.1
```

> **版本策略（2026-09-10 已升级到最新线）**：本项目的框架已从 Spring Boot 3.4 + Spring AI 1.1.8 一次性升级到
> **Spring Boot 4.1.1 + Spring Cloud 2025.1.3 + Spring AI 2.0.1 + Jackson 3.1.5**（实测：143 个测试全绿、端到端检索与
> 额度查询正常、桌面版启动优化后约 13s）。这次跨代变更的要点：
>
> - **Jackson 2→3**：databind/core 坐标与包名迁至 `tools.jackson.*`（注解包 `com.fasterxml.jackson.annotation.*` 保留）；
>   `TextNode`→`StringNode`、`JsonNode#fields()`→`properties()`、异常变 unchecked、
>   `ObjectMapper#configure()` 改为 `JsonMapper.builder()`。
> - **Spring AI 2.0**：openai/anthropic 模块改为基于**官方厂商 SDK**（`com.openai:openai-java-core` /
>   `com.anthropic:anthropic-java-core`），原 `OpenAiApi`/`AnthropicApi` 移除，改由
>   `OpenAiSetup`/`AnthropicSetup` 构建；工具调用循环从 `ChatModel` 内部上移到 Advisor 链，
>   `internalToolExecutionEnabled(false)` 随之移除（本平台仍由 `SpringAiToolBridge` 手动驱动 tool-role 往返）。
> - **Boot 4 模块化**：Flyway、Kafka 等自动配置拆到独立模块（`spring-boot-flyway` / `spring-boot-kafka`），
>   只引第三方 `flyway-core` 不再触发迁移；`@EntityScan` 迁至 `spring-boot-persistence`；
>   Spring Cloud Gateway 拆分为 `spring-cloud-starter-gateway-server-webflux`。
> - **桌面运行时**：精简 JRE 需补 `jdk.net` 模块（Lettuce 7.x 需要 `jdk.net.ExtendedSocketOptions`）。
