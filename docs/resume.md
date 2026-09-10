# 简历项目经历（Agent Platform）

> **数据都可在仓库里复算**，被追问时可当场打开：主代码 245 个 Java 文件 / 18,013 行、
> 测试 36 类 / 140 例（全绿）、前端 43 个文件 / 4,694 行、Flyway 迁移 V1–V12、REST 控制器 17 个。
> 核对于 2026-09-10。**不要写仓库里没有的东西**（并发量、用户数、营收等一律不编）。

---

## 版本一：标准版（推荐，填满 8–12 行）

**智能体交互平台（Agent Platform）** ｜ 个人项目 ｜ Java 21 / Spring Boot 3.4 / React 18

> 面向多租户的 AI 智能体运行平台：在仪表盘上定义智能体（人格、提示词、模型、知识库、工具、
> 插件、技能），运行时按需装配并完成一次可追溯的对话。

**技术栈**：Java 21（虚拟线程 / 结构化并发）、Spring Boot 3.4、Spring Data JPA + Flyway、MySQL / H2、
Redis、Milvus、OkHttp、Spring AI 1.1.8（可选通道）、React 18 + TypeScript + Vite + antd、Electron、Docker / K8s

**核心工作**：

1. **设计多模型适配层**：以 `ModelAdapter` 抽象屏蔽厂商差异，实现 OpenAI 兼容 / Anthropic 原生协议 /
   规则引擎 / 本地 Mock 四类适配器，按 provider 路由；凭证支持「智能体绑定 → 平台默认 → 全局」
   三级回退，API Key 以 AES-GCM 加密落库、接口只回掩码；另接入 Spring AI 作为**可选协议通道**
   （默认关闭、可一键回退），迁移期间凭证与回退逻辑零改动。
2. **实现 RAG 全链路**：Tika 多格式解析 + 三种切分策略（递归 / 语义 / 结构）→ 嵌入向量化 →
   **混合检索**（稠密向量余弦 + MySQL ngram FULLTEXT 关键词，RRF 融合后 Rerank）→ 引用溯源到文档页码；
   `VectorStore` 抽象支持内存与 Milvus 热切换，无外部依赖时仍可完整演示。
3. **构建"全链路可降级"架构**：Redis / Milvus / Kafka / MinIO / LiteLLM 全部可选，
   通过条件装配（`@ConditionalOnProperty` + 可选注入）在缺失时自动退化为内存或本地磁盘，
   **不阻断启动**；并提供 H2 内置库模式（Flyway mysql / h2 双迁移目录），实现免装数据库直跑。
4. **落地可观测与智能诊断**：以 trace / run / tenant 三维串联运行日志，经 Sink 派发到
   Micrometer 指标、Loki 日志与 Tempo 链路；故障诊断采用三级策略链（规则 → 向量相似 → LLM 推理），
   用 `StructuredTaskScope.ShutdownOnSuccess` 并发取最快有效结论。
5. **实现插件热插拔与 Skills 标准目录**：插件走 SPI + 独立 ClassLoader 实现依赖隔离，
   经 before / after 双 Hook 管线支持短路与产物附加，Attach / Detach 热插拔；
   Skills 采用 Agent Skills 开放标准（`SKILL.md` 目录即交付），并提供 prompt / script / http 三种执行器。
6. **自研 DAG 工作流引擎与工具生态**：条件分支 + 虚拟线程并行 + Schema 校验（环检测），
   采用运行时递归遍历以支持运行期决定的分支走向；工具体系统一注册，支持内置工具、HTTP 工具
   （持久化，重启不丢）与 MCP 三种传输（远程 / 进程内 / 沙箱）。
7. **交付桌面应用**：Electron 壳 + jlink 精简 JRE + electron-builder，内嵌后端（H2）实现双击即用、
   用户免装 Java 与数据库；zip 绿色版启动约 20 秒，数据落在用户目录。

**成果**：单仓交付 6 个 Maven 模块、17 个 REST 控制器、12 个数据库迁移版本、**140 个自动化测试全绿**
（覆盖真实事务、H2 迁移、Spring AI 通道、检索事务隔离、模型额度查询等），并提供 Docker / K8s / Helm
部署清单与完整技术文档体系。

---

## 版本二：精简版（简历空间紧张时用，5 行）

**智能体交互平台** ｜ 个人项目 ｜ Java 21 / Spring Boot 3.4 / React / Electron

- 设计多模型适配层（4 类适配器 + provider 路由 + 三级凭证回退 + AES-GCM 加密），
  并接入 Spring AI 作可选协议通道、默认关闭可回退；
- 实现 RAG 全链路（Tika 解析 → 三种切分策略 → 向量 + FULLTEXT 混合检索 + RRF 融合 + 引用溯源），
  向量库支持内存 / Milvus 切换；
- 构建全链路可降级架构（Redis / Milvus / Kafka / MinIO 全可选，缺失自动退化不阻断启动），
  并提供 H2 内置库模式实现免装数据库运行；
- 实现插件热插拔（SPI + ClassLoader 隔离 + 双 Hook 管线）、Skills 标准目录与自研 DAG 工作流引擎；
- 交付 Electron + jlink 桌面应用实现双击即用；**140 个自动化测试全绿**，含 Docker / K8s 部署清单。

---

## 版本三：一句话版（放技能栏或自我评价）

> 独立设计并实现多租户 AI 智能体平台：多模型适配与三级凭证回退、混合检索 RAG、
> 插件热插拔、DAG 工作流与桌面化交付，含 140 个自动化测试。

---

## 技术关键词（可直接复用到技能栏）

Java 21、虚拟线程、结构化并发、Spring Boot、Spring Data JPA、Flyway、MySQL、H2、Redis、
Milvus、OkHttp、SSE 流式、Spring AI、RAG（混合检索 / RRF / Rerank / 引用溯源）、
Function Calling、MCP、插件 SPI 与类加载隔离、DAG 编排、React 18、TypeScript、Vite、antd、
Electron、jlink、Docker、Kubernetes、Helm、Micrometer / Prometheus / Loki / Tempo / Grafana

---

## 面试官最可能追问的 7 个点（提前准备答案）

| 追问 | 答题要点（都能在仓库里找到证据） |
|---|---|
| 为什么要自研适配层，不直接用 Spring AI / LangChain4j？ | 需要**每请求凭证**（用户自带 Key、按智能体绑定）与三级回退，框架早期无此模型；且提示词组装、插件 Hook、工具责任链都在协议层之上。故采用「自研为主 + Spring AI 可选通道」双通道 |
| 混合检索为什么这样设计？ | 纯向量对专有名词/编号召回差、纯关键词对同义改写差；用 RRF 融合免调权重；FULLTEXT 用 ngram 索引避免全表扫描，小库回退顺序扫描 |
| 三级回退与密钥安全怎么做的？ | 智能体绑定 → 平台默认 → 全局；AES-GCM 加密落库、接口只回掩码；解密失败分「安全降级」与「严格报错」两种策略 |
| 怎么保证"缺依赖也能跑"？ | 条件装配 + 可选注入；健康检查如实报 DOWN 但不阻断；每个外部能力都有内存/磁盘兜底 |
| 遇到的最难的问题是什么？ | 例：H2 不支持 MySQL 的 `MATCH..AGAINST`，native SQL 失败会让 Hibernate 把外层事务标记 rollback-only，导致"检索结果算出来了却提交失败"。解法：把该查询隔离到独立事务（`REQUIRES_NEW`）并仅在 MySQL 下启用，另补回归测试锁住 |
| 测试怎么做的？ | 关键链路不 mock：真实 Spring 容器 + 真实事务 + H2 迁移；外部厂商响应用本地 `HttpServer` 模拟（含 401、不可达、不支持厂商），保证离线可跑且稳定 |
| 为什么做桌面版，而不是只给 Web？ | 目标是"下载即可用"：内嵌 jlink JRE 与 H2，用户免装 Java 与数据库；用 Electron 复用已有 Web UI，成本最低 |

---

## 写作注意事项

- **不要写**并发量、日活、用户数、营收、降本比例等无法验证的数字 —— 会被追问穿。
- 可以写并**当场验证**的数字：代码行数、测试数量、模块/控制器/迁移版本数量、桌面包体积、启动耗时。
- 若岗位偏后端：把「模型适配 + 凭证安全 + RAG 混合检索 + 可降级架构」放前两条。
- 若岗位偏全栈：把「React 仪表盘 + Electron 桌面交付 + 一键脚本」提前。
- 若岗位偏 AI 应用：把「Spring AI 通道 + 原生 tool-role 工具循环 + RAG 引用溯源 + 三级诊断」提前。
