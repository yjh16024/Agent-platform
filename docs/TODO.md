# 待办与注意事项（Backlog & Notes）

> 记录项目**当前仍未实现/未接通**的能力，以及开发中必须注意的坑。已实现且影响面较大的
> 变更会在此登记，避免文档与代码再次脱节。最后核实：2026-09-08。

## 〇、近期已补齐（与旧版 TODO 差异，均已合入代码）

- **删除语义统一为物理删除**：智能体（级联版本快照/插件绑定）、知识库（级联文档/chunk/向量）、
  工作流、Skill、会话（含消息）、租户插件均物理删除；列表默认过滤 archived，杜绝「删了还在」。
- **平台默认对话模型（V10）**：`model_config.chat_binding` + `PUT /model-config/chat`；
  `ModelBindingService` 回退链新增 PLATFORM 层（智能体未绑定 → 平台默认 → 全局网关 → Mock）。
- **运行日志 MySQL 持久化**：`log_index` 落库（V10 补 `stack_trace` 列/时间索引），
  重启不丢；`LogService` 双构造：`@Autowired` 主构造 + 无库内存构造（单测）。`/logs/purge` 清理。
- **Agent 运行时埋点**：`run.start / llm.call / llm.done / run.completed / run.failed`（agent/llm 类别）。
- **对话页历史语义**：前端 `chatStore`(zustand+persist) 保持当前对话；「新对话」经
  `POST /sessions/import` 把整轮保存进会话历史。
- **知识库内容浏览**：`GET /knowledge-bases/{id}`、`/{id}/documents`、`/{id}/chunks`、`DELETE /documents/{docId}`。
- **Skills 标准目录存储（Agent Skills 开放标准）**：`skills/<name>/SKILL.md`
  （YAML frontmatter + 提示词正文，可带 `scripts/ references/ assets/`）；
  新增 `SkillFileStore`，支持扫描同步 `/skills/sync`、上传 `.zip`/`SKILL.md`、指定目录导入、
  目录内文件浏览与读取、打开目录；目录型 Skill 的编辑会回写 SKILL.md，删除连带删除目录；
  运行时把已挂载 Skill 的正文注入系统提示词（`AgentRuntimeService.withSkillPrompts`）。
- **插件导入/删除**：`POST /plugins/import`；`DELETE /plugins/{id}`（`__platform__` 内置拒绝）。
- **外部 jar 插件加载**：`ExternalPluginLoader` 读 `plugin_def.artifact_uri`（file/http/裸路径）
  + `entry.main_class`，用 `PluginClassLoader` 隔离加载；未配置制品时给明确错误。
- **MCP 接入**：`core/tool/mcp`（Streamable HTTP / JSON-RPC）；`POST /tools/mcp` 发现并注册，
  `DELETE /tools/{name}` 卸载。
- **Anthropic 直连**：`AnthropicAdapter`（Messages API：`/v1/messages` + `x-api-key` + `anthropic-version`）。
- **RAG 稀疏检索提速**：优先 MySQL ngram FULLTEXT（`chunk` 表已有 `ft_content`），无命中回退顺序扫描。
- **版本 Diff 升级 LCS**：顺序正确、重复行不误判；超大文本退化为块级对比。
- **文件上传修复**：根因是 Spring Boot 默认 multipart 1MB——`application.yml` 已放宽 50MB
  + Tomcat `max-swallow-size`；MIME 白名单扩容并按扩展名兜底；超限异常友好化。
- **安全加固**：core `JwtAuthFilter` 校验 header 租户与 token 归属一致；`SecurityStartupGuard`
  在「开启鉴权但仍用 change-me-* 密钥」时拒绝启动；登录支持可选 `AUTH_USERNAME/AUTH_PASSWORD`。

## 一、仍未实现 / 待补充

| 事项 | 现状 | 说明 |
|------|------|------|
| 日志高阶存储 ES/ClickHouse | ❌ | 当前已用 MySQL `log_index` 持久化替代；超大规模检索/聚合仍需 ES/CH |
| Temporal 长流程 | ❌ | 工作流仅内存 DAG（短链路），长时/可恢复编排未接 |
| 多模态真实 ASR/TTS/OCR | ⚠️ | 接口/文件链路在，依赖外部服务未端到端验证 |
| gateway 模块 | ⚠️ 占位 | 只有 `JwtAuthFilter`，无路由/限流配置；生产鉴权实际靠 core 侧，建议明确唯一入口或补全网关 |
| Spring AI 依赖 | ⚠️ 未用 | 模型调用为自写 OkHttp 适配器，`spring-ai` 依赖可清理（降低歧义） |
| diff-match-patch | ✅ 已用 LCS 替代 | 字符级接入仍可进一步做（当前行级） |
| 桌面打包 | 存档 | 见 desktop-packaging 系列文档（可行性） |

## 二、API / 行为契约（改版后务必遵守）

- **删除即物理删除**，误删需自行恢复备份；归档仅存在于 Agent 状态（需显式传 `status=archived` 才能查到）。
- **命名**：强类型 record 序列化 camelCase；自由 Map 入参 snake_case。例外：
  `POST /agent/run` 返回裸 JSON；文件下载直出字节流；`POST /sessions/import`、`/skills`、`PUT /skills/{id}`、
  `PUT /model-config/chat`、`/tools/mcp`、`/plugins/import` 按 record/camelCase。
- `GET /knowledge-bases` 返回 map（camelCase，含 documentCount/chunkCount）。
- 模型回退链：**智能体绑定 → 平台 chat_binding → LiteLLM/全局 → local**；绑定有 baseUrl+Key 且非 local 即直连。
- Skills 目录：默认 `./data/skills`（`SKILLS_DIR` 可覆盖）；目录型 Skill 删除会**连带删除该子目录**；
  `agent-platform.skills.open-folder-enabled`（默认 true）控制能否从仪表盘打开目录——
  服务端/远程部署建议设为 false（该能力会在服务器上启动文件管理器）。
- `anthropic` provider 走 Messages API；若厂商实际是 OpenAI 兼容中转，需改用 `openai`/自建 provider。

## 三、注意事项（已踩过的坑）

1. **Bean 多构造必须标 `@Autowired`**：给 @Service 加测试用重载构造后若忘标注，
   Spring 报 "No default constructor found"（LogService 曾踩）。
2. **前端必须重建**：core 托管 `static/` 旧产物，改前端后要 `npm run build:prod` 同步再启动；
   浏览器 Ctrl+F5。
3. **Flyway 到 V10**：`model_config.chat_binding`、`log_index.stack_trace + idx_tenant_time`。
4. **`.env` 只被 docker-compose/LiteLLM 消费**；core（宿主机 java）不读 `.env`，需手动 export
   或改造 `start-core.bat`。核心开关默认关：`security/events/vector-store/storage`。
5. **默认密钥**：`JWT_SECRET`、`MODEL_KEY_ENC_KEY` 有 `change-me-*` 默认值；
   生产必须覆盖，且 `SECURITY_ENABLED=true` + 默认 JWT 密钥会被启动守卫直接拒启。
6. **数据库不可用时日志降级内存**：`LogService` 会静默切到有界队列并打印一次告警；
   若日志页空且服务正常，请先查 DB 连接与告警。
7. **文件上传上限**：改 `spring.servlet.multipart` 时记得同步 `server.tomcat.max-swallow-size`。
8. **record 派生 getter**：会参与 Jackson 序列化，派生方法加 `@JsonIgnore`（曾致反序列化失败）。
9. **Redis/Quota**：计数以 Redis INCR+TTL 为主，缺失时内存兜底；重启会丢计数属预期。
10. **Windows jar 锁**：`java -jar` 运行中无法 `mvn package`，先杀进程。

## 四、OOP 课程设计要求对照（独立缺口清单，2026-09-08 建档）

> 说明：本组缺口来自 OOP 教学设计规范（Entity 封装 / 策略 / 工厂+多态 / 命令模式），
> 与上文「一、仍未实现/待补充」的功能缺口**相互独立**——两者判断标准不同（教学规范 vs 业务完备性），
> 后续各自维护、各自勾销，不要合并。状态图例：✅ 已实现 | ⚠️ 部分实现 | ❌ 未实现
>
> **进度**：2026-09-08 已完成 M2 / M3 / M4（零新增依赖，离线 `mvn -o` 编译 + 104 个测试全绿）；
> M1（实体封装）暂未处理——JPA 实体 setter 全开属持久化框架约束，需权衡后再改。

| 编号 | 设计要求 | 需求点 | 项目现状 | 证据位置 / 预期改造 |
|------|----------|--------|----------|----------------------|
| M1 | Entity 数据载体 + 封装 | 智能体 `AgentTemplate`、技能 `Skill` 数据载体体现封装 | ⚠️ | 载体已在：`AgentDefinition`/`SkillDef`（含 record 子结构 `Persona/Capabilities/GenerationConfig`）。但 JPA 实体 Lombok setter 全开、无不变量/业务方法，封装不深 |
| M1 | Entity 数据载体 + 封装 | 智能体 `AgentTemplate`、技能 `Skill` 数据载体体现封装 | ⚠️ | 载体已在（`AgentDefinition`/`SkillDef` + record 子结构），但 JPA setter 全开、无业务方法，封装不深（本轮未处理） |
| M2 | 策略模式（抽象 + 两实现） | `ChatClient` 抽象，规则引擎/真实大模型两实现 | ✅ | 已补 `RuleEngineModelAdapter`（`core/model/adapter/`）：正则规则集 + 兜底文案，provider=`rule`/`rule-engine`；已注册进 `ModelProviderFactory`（不进 `auto` 路由，避免抢占真模型）。真模型实现沿用 `OpenAiCompatibleAdapter`/`AnthropicAdapter` |
| M3 | 工厂 + 多态（MCP） | `McpClient` 接口 + 本地/沙箱两实现 + 工厂 | ✅ | `McpClient` 已抽象为接口；实现：`HttpMcpClient`（远程 JSON-RPC）、`LocalMcpClient`（进程内 echo/time/read_file，限 baseDir）、`SandboxMcpClient`（子进程脚本：目录隔离+解释器白名单+超时+输出截断）；`McpClientFactory` 按 `McpEndpoint` 创建。接口：`POST /tools/mcp/local`、`/tools/mcp/sandbox` |
| M4 | 命令模式（Skill 执行器） | `SkillExecutor` 接口 + 注册表 + 多命令实现 | ✅ | 新增 `core/skill/executor/`：`SkillExecutor` 接口 + `SkillExecutorRegistry`（自动收集实现、按 type/supports 分发、prompt 兜底）+ 三个命令实现 `PromptSkillExecutor`/`ScriptSkillExecutor`/`HttpSkillExecutor`；门面 `SkillExecutionService`；接口 `GET /skills/executors`、`POST /skills/{id}/execute` |
