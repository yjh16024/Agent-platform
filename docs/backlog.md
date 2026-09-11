# 待办与路线（Backlog & Roadmap）

> **职责**：只登记**未实现**事项、路线决策与必须遵守的契约/坑。
> **已实现的功能一律不在这里描述** —— 见 [status.md](status.md)（现状权威来源）。
> 设计意图与可行性依据见 [design.md](design.md)。
>
> 合并来源：原 `docs/TODO.md`（空文件，已删除）+ `TECH_GAP_ROADMAP.md` + `FEASIBILITY_ANALYSES.md`（待实施部分）。
> 最后核实：**2026-09-10**。

---

## 一、仍未实现 / 待补充（代码侧）

| 事项 | 现状 | 说明 |
|------|------|------|
| 日志高阶存储 ES/ClickHouse | ❌ | 已用 MySQL `log_index` 持久化替代；超大规模检索/聚合仍需 ES/CH |
| Temporal 长流程 | ❌ | 工作流仅内存 DAG（短链路），无持久化/断点恢复 |
| 多模态真实 ASR/TTS/OCR | ⚠️ | 接口与文件链路在（ASR/TTS 插件化），依赖外部服务未端到端验证（TTS/ASR 均为 mock，OCR 无实现） |
| gateway 模块 | ⚠️ 占位 | 仅 `JwtAuthFilter`，无路由/限流/熔断；生产鉴权实际在 core 侧。**注意**：早期阶段报告称「Spring Cloud Gateway 已交付」与现状不符 |
| 长期记忆（用户显式画像） | ❌ | 需 `user_fact` 表 + UI；建议先做「用户主动填写」，自动抽取后置 |
| 向量记忆（历史对话向量召回） | ❌ | 技术栈已具备（in-memory/Milvus），风险是无关历史污染上下文 |
| 桌面应用代码签名 | ❌ | 当前未签名（需 `signtool` / `codesign` 证书），企业分发前需补 |
| 桌面自动更新 | ❌ | 无 updater，升级需重新分发 zip / exe |
> 已完成项不在此列出，例如 **框架大版本升级（Spring Boot 4.1.1 + Spring AI 2.0.1 + Jackson 3，2026-09-10 完成）**、
> **工作流拖拽画布（FlowGram + 11 类节点 + 节点级调试 + 发布/回滚，2026-09-11 完成）**、
> Spring AI 通道集成、H2 内置库、对话早期摘要（中期记忆）、
> 图片视觉、HTTP 工具持久化、内置天气工具、**Electron 桌面应用**、**模型账户额度查询**
> —— 全部见 [status.md](status.md)；升级过程中的踩坑见本文第四节。

## 二、编排演进决策（已定，勿重复讨论）

- **不引入** LangChain4j / LangGraph4j 作为编排层。依据：阿里云镜像可取到构件（依赖层面可行），
  真正障碍是成本高（12–20 人日）、与现有凭证绑定/插件 Hook/责任链/观测埋点体系不匹配、jar 体积再增。
- **采用方案 C**：借鉴 LangGraph 思想演进自研 `DagEngine` —— State + Checkpoint 落库 + 条件边/循环 + 执行可视化。
  零依赖、离线可编译。
- 完整对比见 [design.md](design.md) 「引入 LangChain / LangGraph 评估」。

## 三、API / 行为契约（改版后务必遵守）

- **删除即物理删除**，误删需自行恢复备份；归档仅存在于 Agent 状态（需显式传 `status=archived` 才能查到）。
- **命名**：强类型 record 序列化 camelCase；自由 Map 入参 snake_case。例外：`POST /agent/run` 返回裸 JSON；
  文件下载直出字节流；`POST /sessions/import`、`/skills`、`PUT /skills/{id}`、`PUT /model-config/chat`、
  `/tools/mcp`、`/plugins/import` 按 record/camelCase。
- `GET /knowledge-bases` 返回 map（camelCase，含 documentCount/chunkCount）。
- 模型回退链：**智能体绑定 → 平台 chat_binding → LiteLLM/全局 → local**；绑定有 baseUrl+Key 且非 local 即直连。
- 新增 REST 控制器时，`@RequestParam Map` 会收集**全部**查询参数——若用作元数据过滤，必须剔除保留键
  （`kbIds/query/topK/...`），否则过滤条件会命中不存在字段导致结果恒为空（`KnowledgeBaseController` 已踩）。
- Skills 目录：默认 `./data/skills`（`SKILLS_DIR` 可覆盖）；目录型 Skill 删除会**连带删除该子目录**；
  `agent-platform.skills.open-folder-enabled` 默认 **false**（服务端/远程部署不要开）。
- `anthropic` provider 走 Messages API；若厂商实际是 OpenAI 兼容中转，需改用 `openai`/自建 provider。

## 四、注意事项（已踩过的坑）

1. **Bean 多构造必须标 `@Autowired`**：给 @Service 加测试用重载构造后若忘标注，Spring 报
   "No default constructor found"（`LogService` 曾踩）。
2. **前端必须重建**：core 托管 `static/` 旧产物，改前端后要 `npm run build:prod` 同步再启动；浏览器 Ctrl+F5。
3. **新增迁移要写两份**：Flyway 目前到 **V12**，`db/migration/mysql` 与 `db/migration/h2` 必须同名同序；H2 不支持 MySQL 的
   `MATCH..AGAINST`（由 `agent-platform.rag.fulltext.enabled=false` 在 embedded 下跳过）。
4. **`.env` 只被 docker-compose/LiteLLM 消费**；core（宿主机 java）不读 `.env`，需手动 export 或改造 `start-core.bat`。
   核心开关默认关：`security/events/vector-store/storage/springai`。
5. **默认密钥**：`JWT_SECRET`、`MODEL_KEY_ENC_KEY` 有 `change-me-*` 默认值；生产必须覆盖，且
   `SECURITY_ENABLED=true` + 默认 JWT 密钥会被启动守卫拒绝启动。
6. **数据库不可用时日志降级内存**：`LogService` 静默切到有界队列并打印一次告警；日志页空且服务正常时先查 DB 连接。
7. **文件上传上限**：改 `spring.servlet.multipart` 时同步 `server.tomcat.max-swallow-size`。
8. **record 派生 getter** 会参与 Jackson 序列化，派生方法需加 `@JsonIgnore`（曾致反序列化失败）。
9. **Redis/Quota**：计数以 Redis INCR+TTL 为主，缺失时内存兜底；重启丢计数属预期。
10. **Windows jar 锁**：`java -jar` 运行中无法 `mvn package`（repackage 无法重命名），先停进程。
11. **Windows bat 脚本必须纯 ASCII 注释**：UTF-8 中文注释会被 cmd 按 GBK 解析成乱码命令执行（`.sh` 无此问题）。
12. **`--enable-preview` 必须显式传**：代码按预览特性编译（class version 65.65535），直接 `java -jar` 会报
    UnsupportedClassVersionError；桌面壳 `desktop/main.js` 已带该参数。
13. **构建/依赖**：官方中央仓库直连不通，全局 settings.xml 已配阿里云镜像；若本地 `.mvn/maven.config`
    含注释会导致 Maven 3.8.x 解析失败（该文件必须只放参数）。
14. **Spring Boot 4 自动配置模块化（2026-09-10 升级踩坑）**：Flyway / Kafka 的自动配置拆到独立模块
    （`spring-boot-flyway` / `spring-boot-kafka`），只引第三方 `flyway-core` / `spring-kafka` 时
    **不会执行迁移、不会自动装配 `KafkaTemplate`**（表现为「表不存在」启动失败）。`@EntityScan` 迁到
    `spring-boot-persistence`（`org.springframework.boot.persistence.autoconfigure`）。
15. **Spring Cloud Gateway 在 2025.1 拆了坐标**：`spring-cloud-starter-gateway` →
    `spring-cloud-starter-gateway-server-webflux`（或 `...-server-webmvc`）。
16. **Jackson 3 迁移要点（39 个文件）**：databind/core/dataformat 的 Maven 坐标与包名迁至 `tools.jackson.*`
    （**注解包 `com.fasterxml.jackson.annotation.*` 保留，DTO 注解无需改**）；`TextNode`→`StringNode`、
    `JsonNode#fields()`→`properties()`、异常变 unchecked（`JacksonException`）、
    `ObjectMapper#configure()` 移除（改 `JsonMapper.builder()`）。
17. **Spring AI 2.0 迁移要点**：openai/anthropic 改用官方厂商 SDK（`com.openai:openai-java-core` /
    `com.anthropic:anthropic-java-core`），`OpenAiApi`/`AnthropicApi` 移除 → 改用 `OpenAiSetup`/
    `AnthropicSetup.setupSyncClient|setupAsyncClient`；**ChatModel 必须同时提供同步与异步客户端**
    （只给同步时 Builder 会用默认凭证自建异步客户端并抛 `At least one credential source...`）；
    工具循环上移 Advisor 链后 `internalToolExecutionEnabled` 移除；baseUrl 需含版本路径（如 `/v1`）。
18. **桌面精简 JRE 必须含 `jdk.net`**：Boot 4 带来的 Lettuce 7.x 在初始化时引用
    `jdk.net.ExtendedSocketOptions`，jlink runtime 缺该模块会导致后端启动即失败
    （`NoClassDefFoundError`）——`desktop/build.bat|sh` 的模块列表已补上。
