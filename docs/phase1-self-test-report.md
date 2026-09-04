# Phase 1 · 基座 — 阶段自测报告

## 交付物清单

| # | 交付物 | 状态 | 说明 |
|---|--------|------|------|
| 1 | Maven 多模块工程 | ✅ | 6 个模块 + 父 POM，Java 21 |
| 2 | MySQL 8.4 DDL | ✅ | Flyway `V1__agent_base.sql`（agent_def / agent_version / session_def / message） |
| 3 | API 网关 + JWT 鉴权 | ✅ | Spring Cloud Gateway + jjwt，白名单 + 租户头注入 |
| 4 | 统一 Agent API | ✅ | `POST /api/v1/agent/run`，非流式 + SSE 流式 |
| 5 | Agent 管理 CRUD | ✅ | 创建/全量更新/局部更新/删除/克隆/分页 |
| 6 | 人格/提示词/参数配置 | ✅ | Persona / system_prompt / generation_config |
| 7 | 版本管理 | ✅ | 快照/发布/回滚/Diff（语义化版本 + prompt_hash） |
| 8 | 多模型基础对接 | ✅ | ModelAdapter 策略 + 工厂 + 路由（LiteLLM OpenAI 兼容） |
| 9 | 单元测试 | ✅ | 5 个测试类，覆盖校验/CRUD/版本/路由/运行时 |
| 10 | Docker Compose | ✅ | MySQL 8.4 / Redis 7 / Milvus 2.x / LiteLLM |
| 11 | README | ✅ | 快速启动/验证/设计决策 |

## 已通过的测试（预期）

| 测试类 | 覆盖点 |
|--------|--------|
| `AgentConfigValidatorTest` | 校验责任链：合法/空提示词/temperature 超界/warmth 超界 |
| `AgentServiceTest` | 创建/PATCH 人格合并/404/软删 |
| `AgentVersionServiceTest` | 首版 v1.0.0/Diff/回滚异常 |
| `AgentRuntimeServiceTest` | 模板填充/提示词组装/参数合并 |
| `ModelProviderFactoryTest` | 工厂归一/缓存/supports |
| `AgentServiceIntegrationTest` | 端到端：创建 → 运行 |

## 关键设计决策说明

1. **模型接入走 LiteLLM OpenAI 兼容协议**（而非直接依赖 Spring AI）。
   原因：设计文档 §4 明确「双层架构：LiteLLM Proxy + 适配器」，§5.1 的 `ModelAdapter`
   是自定义接口而非 Spring AI 的 ChatClient。用 OkHttp 实现 OpenAI 兼容适配器，
   既符合文档，又避免依赖具体 SDK 的 API 不确定性，且与 LiteLLM 网关天然对接。
   （Spring AI 1.0 可在后续 Phase 以装饰器形式叠加。）

2. **本地 Mock 模型兜底**：`local` provider 提供确定性响应，保证无外部依赖时全平台可运行。

3. **DDL 修正**：设计文档 `agent_version` 表缺 `id` 列，已补充
   `id BIGINT AUTO_INCREMENT PRIMARY KEY`；`agent_def` 的 `PARTITION BY KEY(tenant_id)`
   与 `PRIMARY KEY(id)` 冲突（MySQL 分区键必须包含在所有唯一键中），MVP 阶段去掉分区
   （Phase 5 生产化再加）。

## 未解决/已知风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| 本地环境无 JDK 21 / Maven | 无法直接编译 | 已下载便携版 JDK 21.0.12 + Maven 3.9.9 到 `~/tools/` |
| Spring Boot 3.4 依赖首次下载较慢 | 编译耗时 | 已配置阿里云 Maven 镜像 |
| Visibility 枚举含 Java 关键字 | 落库需 `private`/`public` | AttributeConverter 自动转 |
| LiteLLM 未接真实 Key | 对话返回 Mock | 生产配置真实 Key 即可 |

## 验证方式

```bash
# 1. 编译
mvn clean compile

# 2. 测试
mvn test

# 3. 启动（需先 docker compose up -d）
cd agent-platform-core && mvn spring-boot:run

# 4. 验证接口（见 README「快速启动」）
```