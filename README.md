# 智能体交互平台（Agent Platform）

基于 **Java 21 (LTS) · Spring Boot 3.4 · MySQL 8.4 LTS · Milvus · Kafka** 的
智能体交互平台，实现多模型对接、会话上下文、RAG、工作流编排、多模态、工具调用（含 MCP）、
Skills、智能体管理、插件系统、运行日志诊断、提示词优化等核心能力。

> 依据《智能体交互平台设计文档 v3.4》从零实现。

## 核心能力

| 能力 | 说明 |
|------|------|
| 多模型对接 | OpenAI / Anthropic / 通义 / 文心 / 混元 / 本地模型（经 LiteLLM 统一 OpenAI 兼容协议） |
| 会话与上下文 | Session → Turn → Message 分层，滑动窗口 + 摘要 + RAG 长期记忆 |
| RAG | 知识库生命周期、切分管线、MySQL + Milvus 混合检索、Rerank + 引用 |
| 工作流编排 | DAG + Temporal 双层引擎，节点 Schema 校验 |
| 多模态 | parts[] 统一消息模型、OCR / TTS / ASR 插件化、模型路由 + Fallback |
| 工具调用 | 工具注册中心、MCP、执行沙箱、责任链鉴权 |
| Skills | Manifest 打包、多方式导入、自动注册 |
| 智能体管理 | 配置即资产、人格/提示词/参数、版本快照、发布回滚 Diff |
| 插件系统 | SPI + ClassLoader 隔离、插件市场、Attach/Detach 热插拔 |
| 运行日志诊断 | Logback + Kafka + ES/ClickHouse、三级诊断策略（规则→向量→LLM） |
| 提示词优化 | PromptEnhancer 策略链、规则 + LLM 双阶段、6 维评分 + Diff |

## 技术栈

- **语言/框架**：Java 21、Spring Boot 3.4、Spring Cloud 2024
- **数据库**：MySQL 8.4 LTS（业务库）+ Milvus 2.x（向量库）
- **缓存**：Redis 7 Cluster
- **消息**：Apache Kafka
- **模型网关**：LiteLLM Proxy（OpenAI 兼容协议）
- **构建**：Maven 3.9 + Flyway 10（DB 迁移）

## 模块结构

```
agent-platform/
├── agent-platform-common/        # 公共模块：DTO、异常、工具类
├── agent-platform-model/         # 领域模型：Entity、Record、Repository
├── agent-platform-plugin-sdk/    # 插件 SDK（SPI 契约）
├── agent-platform-infra/         # 基础设施：Kafka、Redis 客户端
├── agent-platform-gateway/       # API 网关（鉴权、限流、路由）
├── agent-platform-core/          # 核心调度层（主应用）
├── agent-platform-deploy/        # 部署：Docker、K8s、Flyway
└── docs/                         # 文档
```

## 快速启动

### 前置条件

- **JDK 21**（Eclipse Temurin 或 Oracle JDK）。⚠️ 本机若仅有 JDK 17，需先安装 JDK 21
- **Maven 3.9+**
- **Docker**（可选，用于基础设施；无 Docker 时可用本机 MySQL + 便携 Redis，见下文）

> 💡 本机已下载便携版到 `~/tools/`，可这样启用：
> ```bash
> export JAVA_HOME="$HOME/tools/jdk21/jdk-21.0.12.1+1"
> export PATH="$JAVA_HOME/bin:$HOME/tools/maven/apache-maven-3.9.9/bin:$PATH"
> ```
> （注：本项目使用 `ScopedValue`，在 JDK 21 为预览特性，已全局开启 `--enable-preview`；
> 若改用 JDK 23+ 运行则无需该标志。）

### 0. 一键启动（Windows 推荐，无需 Docker）

在仓库根目录运行 `start-core.bat`（双击即可）：

```bat
start-core.bat
```

脚本会自动完成：定位便携 JDK 21 + Maven → 拉起本地 Redis（`~/tools/redis`，若 6379 未监听）→ 离线编译 → 启动核心服务（8081）。
唯一前提是**本机 MySQL 已运行**（库 `agent_platform` + 账号 `agent`）。启动成功后浏览器打开 **http://localhost:8081/** 即是仪表盘界面（无需手敲 curl）。

> 前端仪表盘需先构建一次产物（把 `agent-platform-ui/dist` 同步进 core 的 `static/`）；首次使用或改动前端后，运行 `agent-platform-ui\build-ui.bat` 即可。

### 1. 启动基础设施（Docker 方式，可选）

```bash
docker compose up -d
# 启动 MySQL 8.4 / Redis 7 / Milvus 2.x(+etcd/minio) / LiteLLM Proxy / Kafka(KRaft)
```

装好 Docker 后，可按需通过环境变量接通对应能力（默认关闭以保证无 Docker 也可跑）：

| 能力 | 环境变量 |
|------|----------|
| 真实模型路由 | 起 LiteLLM 后填真实 `DEEPSEEK_API_KEY`（供 docker-compose/LiteLLM 消费，见下文）；或直接在仪表盘新建智能体时填 baseUrl + API Key **直连厂商**（无需 LiteLLM） |
| RAG 向量检索 | `VECTOR_STORE=milvus`（默认 `in-memory` 兜底） |
| Kafka 事件总线 | `EVENTS_ENABLED=true`（默认关） |
| 文件对象存储 | `STORAGE_TYPE=minio`（默认 `local` 本地磁盘） |
| core 侧 JWT 兜底鉴权 | `SECURITY_ENABLED=true`（默认关） |

> **无需 Docker 的替代**：本机 MySQL 已运行时直接用它（8.0 亦可）；Redis 用便携版
> `~/tools/redis/.../redis-server.exe --port 6379`；Milvus / LiteLLM / Kafka 缺失不影响演示
> （本地 Mock 模型兜底，RAG 走内存向量库、文件走本地磁盘）。

### 2. 编译

```bash
mvn clean package -DskipTests
```

### 3. 启动核心服务

```bash
cd agent-platform-core
mvn spring-boot:run
# 或
java --enable-preview -jar target/agent-platform-core-1.0.0-SNAPSHOT.jar
```

### 4. 验证

```bash
# 健康检查
curl http://localhost:8081/actuator/health

# 登录获取 JWT
curl -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenant_id":"t1","user_id":"u1"}'

# 创建智能体
curl -X POST http://localhost:8081/api/v1/agents \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: t1" \
  -d '{"name":"客服助手","persona":{"tone":"friendly","role":"客服专家"},"systemPrompt":"你是一名客服专家"}'

# 运行对话
curl -X POST http://localhost:8081/api/v1/agent/run \
  -H "Content-Type: application/json" \
  -d '{"agentId":"agent_xxx","messages":[{"role":"user","content":"你好"}],"metadata":{"tenant_id":"t1"}}'
```

### 5. 直连 DeepSeek / 模型接入排错

**推荐方式：仪表盘直连** —— 新建/编辑智能体时选定 `provider=deepseek`、`model`（可选 `deepseek-v4-flash` / `deepseek-v4-pro` / 手输任意新模型名）、`baseUrl=https://api.deepseek.com/v1`、`API Key`。核心服务会**直连厂商**，不经过 LiteLLM：
- 最终请求路径为 `{baseUrl}/chat/completions`（自动去重 `v1`，不会出现 `/v1/v1/...`）；
- 请求头严格 `Authorization: Bearer <你的Key>`（Key 落库前已 `trim`，避免复制带入空格）。

**验证直连是否通**（替换为你的 Key，应返回含 `choices[0].message.content` 的 JSON，而非 `401/402`）：

```bash
curl https://api.deepseek.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-你的真实Key" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"}]}'
```

预期：HTTP 200 + `"choices":[{"message":{"content":"..."}}]`。若返回 `401`（Key 无效/被截断）、`402`（余额不足）、`400`（模型名/参数错误），错误会**原样透出**到前端弹窗，不再被静默 Mock 吞掉。

**环境变量归属（易踩坑）**：根目录 `.env` 只被 **docker-compose 的 LiteLLM 容器**消费（`DEEPSEEK_API_KEY` 经 `os.environ/...` 注入）。宿主机运行的 **core（java 进程）不会自动读 `.env`**，如需让 core 使用环境变量（如 `MODEL_KEY_ENC_KEY`、`DEFAULT_MODEL`），必须：
- 手动 `export DEEPSEEK_API_KEY=...`（及 `DEFAULT_MODEL=...` 等）后再启动；或
- 改造 `start-core.bat`，在 `java -jar` 前 `for /f "eol=# tokens=1,* delims==" %%a in (.env) do set "%%a=%%b"`。

**其他排查项**：
- 确认服务器/主机能访问 DeepSeek 的 **443 端口**（防火墙/代理放行 `https://api.deepseek.com`）；
- 确认 DeepSeek 账号**余额**充足（`402` 是欠费/额度耗尽，不是代码问题）；
- 直连密钥由 `MODEL_KEY_ENC_KEY` 主密钥 AES-GCM 加密；若该主密钥变更，旧智能体的 Key 无法解密，会明确报错而不是静默走 Mock——此时重新编辑该智能体填入 Key 即可。

### 6. 直连嵌入模型（RAG 向量化，ccswitch 式）

DeepSeek 没有 `/embeddings` 接口，做真实语义 RAG 需另接嵌入模型。**无需下载本地模型**——在仪表盘左侧「**模型设置**」页（或 `PUT /api/v1/model-config/embedding`）像 ccswitch 添加供应商一样填：

- `provider`：`siliconflow`（硅基流动，`BAAI/bge-m3` 有免费额度）/ `openai` / `qwen` / `ernie` / `hunyuan` / `zhipu`（手输任意 OpenAI 兼容厂商亦可）；
- `model`：如 `BAAI/bge-m3`、`text-embedding-3-small`（支持手输最新名）；
- `baseUrl`：如 `https://api.siliconflow.cn/v1`；
- `API Key`：直连厂商凭证（AES-GCM 加密落库，接口只回掩码，留空保持原 Key）。

保存后 RAG 摄取即走 `{baseUrl}/embeddings` 直连（`EmbeddingService` 三级路由：**直连 > LiteLLM 网关 > 本地 Mock**）。未配置时退化为本地 Mock 8 维。切换嵌入模型会改变向量维度，Milvus 会自动重建 collection（旧文档需重新上传）。查询 `GET /api/v1/model-config` 只看得到掩码视图。

## 仪表盘（前端 UI）

基于 React + Vite + antd 的 Web 仪表盘，源码在 `agent-platform-ui/`，覆盖智能体管理、对话运行、插件管理、日志 / 诊断 / 提示词优化四大功能域。

- **生产**：`agent-platform-ui\build-ui.bat`（或 `npm run build:prod`）→ 产物同步到 `agent-platform-core/src/main/resources/static/` → `start-core.bat` 启动后访问 `http://localhost:8081/`。
- **开发**：`cd agent-platform-ui && npm run dev` → `http://localhost:5173/`（已代理 `/api` 与 `/actuator` 到 8081，热更新）。
- 设计文档：[可行性分析报告](docs/dashboard-feasibility-report.md) · [技术设计文档](docs/dashboard-technical-design.md)。

## 单元测试

```bash
mvn test
```

## 关键设计决策

1. **模型接入走 LiteLLM OpenAPI 兼容协议**：屏蔽多模型厂商差异，Java 侧以统一 HTTP 适配器对接（`OpenAiCompatibleAdapter`），与设计文档「双层架构（LiteLLM + 适配器）」一致，且避免依赖具体 SDK。
2. **本地 Mock 模型兜底**：`local` provider 提供确定性响应，保证无外部依赖时全平台可运行（开发/测试/演示）。
3. **Record + sealed interface**：领域模型（Persona / GenerationConfig / DiagnosticReport 等）全部用 JDK 21 Record 建模，诊断来源用 sealed interface 实现编译期穷尽检查。
4. **ScopedValue 替代 ThreadLocal**：全链路 trace 上下文传递，根治虚拟线程环境下的上下文串号。
5. **智能体管理 = 配置中心 + 注册中心**：MySQL 单一事实源 + Redis 缓存 + Kafka 变更事件广播，实现秒级热更新。

## 阶段进度

- [x] **Phase 1 · 基座**：项目骨架、MySQL 建表、网关鉴权、统一 Agent API、Agent 管理 CRUD + 版本
- [x] **Phase 2 · RAG + 工具**：知识库、切分管线、混合检索、工具注册中心、MCP 接入
- [x] **Phase 3 · 编排 + Skills**：自研 DAG 引擎、编排画布、节点 Schema 校验、Skills 导入
- [x] **Phase 4 · 插件系统**：plugin-sdk SPI、Plugin Runtime（ClassLoader 隔离）、插件市场、Attach/Detach 热加载、Hook 管线、自动回复 + TTS 示例插件
- [x] **Phase 4.5 · 多模态**：多模态 parts、文件管线、TTS/ASR 插件化、模型路由 + Fallback、租户配额
- [x] **Phase 6 · 运行日志与诊断**：结构化日志、错误指纹、三级诊断策略（规则→向量→LLM）、StructuredTaskScope
- [x] **Phase 7 · 提示词优化**：PromptEnhancer 策略链、规则 + LLM 双阶段、6 维评分 + Diff、人格融合
- [x] **Phase 5 · 生产化**：K8s 部署、HPA/KEDA 弹性、可观测性（Prometheus/OTel）、安全加固、Helm Chart

## 技术文档

| 文档 | 说明 |
|------|------|
| [technical-guide.md](docs/technical-guide.md) | 如何添加新模型/工具/插件/诊断规则/优化策略 |
| [deployment.md](docs/deployment.md) | K8s 部署、环境变量清单、配置中心、弹性策略 |
| [demo.md](docs/demo.md) / [demo-script.sh](docs/demo-script.sh) | 端到端演示（创建 Agent→插件→对话→日志→诊断→优化） |
| [TODO.md](docs/TODO.md) | 未实现项与注意事项（功能缺口 + 常见坑，后续补充对照） |
| [dashboard-feasibility-report.md](docs/dashboard-feasibility-report.md) / [dashboard-technical-design.md](docs/dashboard-technical-design.md) | 仪表盘可行性分析与技术设计 |
| [desktop-packaging-feasibility-report.md](docs/desktop-packaging-feasibility-report.md) / [desktop-packaging-technical-design.md](docs/desktop-packaging-technical-design.md) | 打包为可分发应用（Docker/jpackage/桌面壳/native-image）的可行性分析与技术设计 |
| [phase1~7 自测报告](docs/) | 各阶段交付物与测试结果 |

## 架构速览

```
前端(React) → API 网关(Gateway+JWT) → 核心调度层(Spring Boot 3.4)
    ├─ Agent Runtime（/agent/run 统一入口，插件 Hook 管线）
    ├─ 模型适配层（ModelAdapter 策略+工厂+路由，LiteLLM 兼容）
    ├─ RAG（切分管线 + VectorStore 抽象 + 混合检索）
    ├─ 工作流（自研 DAG 引擎 + 节点 Schema 校验）
    ├─ 插件系统（plugin-sdk SPI + PluginClassLoader 隔离 + 市场）
    ├─ 日志诊断（三级策略：规则→向量→LLM，StructuredTaskScope）
    └─ 提示词优化（PromptEnhancer 策略链 + 6 维评分）
    → 基础设施：MySQL 8.4 / Milvus / Redis 7 / Kafka / LiteLLM