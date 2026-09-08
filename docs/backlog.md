# 待办与路线（Backlog & Roadmap）

> **合并自**：原 `docs/TODO.md` + 根目录 `TECH_GAP_ROADMAP.md` + `FEASIBILITY_ANALYSES.md`（待实施部分）。
> **原则**：只登记**未实现**事项与必须遵守的契约/坑；已实现项不再保留（历史见 git 提交）。
> 最后核实：2026-09-08（已逐条对照代码现状修订）。

---

## 一、仍未实现 / 待补充（代码侧）

| 事项 | 现状 | 说明 |
|------|------|------|
| 日志高阶存储 ES/ClickHouse | ❌ | 已用 MySQL `log_index` 持久化替代；超大规模检索/聚合仍需 ES/CH |
| Temporal 长流程 | ❌ | 工作流仅内存 DAG（短链路），无持久化/断点恢复 |
| 多模态真实 ASR/TTS/OCR | ⚠️ | 接口与文件链路在（ASR/TTS 插件化），依赖外部服务未端到端验证 |
| gateway 模块 | ⚠️ 占位 | 仅 `JwtAuthFilter`，无路由/限流；生产鉴权实际在 core 侧。**注意**：早期阶段报告称"Spring Cloud Gateway 已交付"与现状不符，鉴权为 core 内 JWT 过滤器 |
| Spring AI 依赖 | ⚠️ 未用 | 模型调用为自写 OkHttp 适配器，`spring-ai` 依赖可清理以降低歧义 |

## 二、能力侧待办（源自可行性分析，尚未实施）

| 层级 / 事项 | 状态 | 说明 |
|---|---|---|
| 短期记忆（Redis 缓存最近 5–10 轮） | ❌ | `SessionService` 目前直接查 MySQL，**未接入 Redis**；收益为降低延迟 |
| 长期记忆（用户显式画像） | ❌ | 需 `user_fact` 表 + UI；建议先做"用户主动填写"，自动抽取后置 |
| 中期记忆（对话摘要） | ❌ | 需 `message.summary` + 定时任务 + 降级为保留原文 |
| 向量记忆（历史对话向量召回） | ❌ | 技术栈已具备（in-memory/Milvus），风险是无关历史污染上下文 |
| 应用化：一键启动 | ✅ 已完成 | `start-core.bat` / `start-core.sh`（在线优先、失败降级 `-o`）+ `warmup.bat` 预热 |
| 应用化：内嵌 DB（SQLite/H2） | ❌ | 桌面包前置，需 Flyway 方言分支（MySQL 特有子句如 `AFTER`） |
| 应用化：桌面壳（Tauri/Electron） | ⛔ 已归档 | 仅作参考，见 `feasibility.md`；不再排期 |

## 三、编排演进决策（已定，勿重复讨论）

- **不引入** LangChain4j / LangGraph4j 作为编排层。
- 依据：官方中央仓库直连不通，但阿里云镜像可取到构件（依赖层面**可行**）；真正障碍是成本高（12–20 人日）、与现有凭证绑定/插件 Hook/责任链/观测埋点体系不匹配、jar 体积再增。
- **采用方案 C**：借鉴 LangGraph 思想演进自研 `DagEngine` —— State + Checkpoint 落库 + 条件边/循环 + 执行可视化。零依赖、离线可编译。
- 详见 `feasibility.md`「引入 LangChain/LangGraph 评估」。

## 四、API / 行为契约（改版后务必遵守）

- **删除即物理删除**，误删需自行恢复备份；归档仅存在于 Agent 状态（需显式传 `status=archived` 才能查到）。
- **命名**：强类型 record 序列化 camelCase；自由 Map 入参 snake_case。例外：`POST /agent/run` 返回裸 JSON；文件下载直出字节流；`POST /sessions/import`、`/skills`、`PUT /skills/{id}`、`PUT /model-config/chat`、`/tools/mcp`、`/plugins/import` 按 record/camelCase。
- `GET /knowledge-bases` 返回 map（camelCase，含 documentCount/chunkCount）。
- 模型回退链：**智能体绑定 → 平台 chat_binding → LiteLLM/全局 → local**；绑定有 baseUrl+Key 且非 local 即直连。
- Skills 目录：默认 `./data/skills`（`SKILLS_DIR` 可覆盖）；目录型 Skill 删除会**连带删除该子目录**；`agent-platform.skills.open-folder-enabled` 默认 **false**（服务端/远程部署不要开）。
- `anthropic` provider 走 Messages API；若厂商实际是 OpenAI 兼容中转，需改用 `openai`/自建 provider。

## 五、注意事项（已踩过的坑）

1. **Bean 多构造必须标 `@Autowired`**：给 @Service 加测试用重载构造后若忘标注，Spring 报 "No default constructor found"（`LogService` 曾踩）。
2. **前端必须重建**：core 托管 `static/` 旧产物，改前端后要 `npm run build:prod` 同步再启动；浏览器 Ctrl+F5。
3. **Flyway 到 V10**：`model_config.chat_binding`、`log_index.stack_trace + idx_tenant_time`。
4. **`.env` 只被 docker-compose/LiteLLM 消费**；core（宿主机 java）不读 `.env`，需手动 export 或改造 `start-core.bat`。核心开关默认关：`security/events/vector-store/storage`。
5. **默认密钥**：`JWT_SECRET`、`MODEL_KEY_ENC_KEY` 有 `change-me-*` 默认值；生产必须覆盖，且 `SECURITY_ENABLED=true` + 默认 JWT 密钥会被启动守卫拒绝启动。
6. **数据库不可用时日志降级内存**：`LogService` 静默切到有界队列并打印一次告警；日志页空且服务正常时先查 DB 连接。
7. **文件上传上限**：改 `spring.servlet.multipart` 时同步 `server.tomcat.max-swallow-size`。
8. **record 派生 getter** 会参与 Jackson 序列化，派生方法需加 `@JsonIgnore`（曾致反序列化失败）。
9. **Redis/Quota**：计数以 Redis INCR+TTL 为主，缺失时内存兜底；重启丢计数属预期。
10. **Windows jar 锁**：`java -jar` 运行中无法 `mvn package`，先停进程。
11. **Windows bat 脚本必须纯 ASCII 注释**：UTF-8 中文注释会被 cmd 按 GBK 解析成乱码命令执行（`.sh` 无此问题）。
12. **构建/依赖**：官方中央仓库直连不通，全局 settings.xml 已配阿里云镜像，去掉 `-o` 即可在线构建；依赖已 `go-offline` 固化，加新依赖后需重跑 `warmup.bat`。
