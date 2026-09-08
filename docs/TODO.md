# 待办与注意事项（Backlog & Notes）

> 本文件**只登记当前仍未实现 / 未接通的能力**、API 契约与已踩过的坑。
> 已实现项与已决策不做的项不再保留（历史可查 git 提交记录）。
>
> - OOP 教学缺口组（原第四节）已关闭：M2 规则引擎 / M3 MCP 工厂+多态 / M4 Skill 命令模式 均已实现；M1 实体封装已决策不做。
> - 已归档项（桌面打包）见 `docs/desktop-packaging-*.md`，不再在此登记。
>
> 最后核实：2026-09-08。

## 一、仍未实现 / 待补充

| 事项 | 现状 | 说明 |
|------|------|------|
| 日志高阶存储 ES/ClickHouse | ❌ | 当前已用 MySQL `log_index` 持久化替代；超大规模检索/聚合仍需 ES/CH |
| Temporal 长流程 | ❌ | 工作流仅内存 DAG（短链路），长时/可恢复编排未接 |
| 多模态真实 ASR/TTS/OCR | ⚠️ | 接口/文件链路在，依赖外部服务未端到端验证 |
| gateway 模块 | ⚠️ 占位 | 只有 `JwtAuthFilter`，无路由/限流配置；生产鉴权实际靠 core 侧，建议明确唯一入口或补全网关 |
| Spring AI 依赖 | ⚠️ 未用 | 模型调用为自写 OkHttp 适配器，`spring-ai` 依赖可清理（降低歧义） |

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
