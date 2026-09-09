# 智能体交互平台（Agent Platform）

基于 **Java 21 · Spring Boot 3.4 · MySQL · React** 的 AI 智能体平台：可视化创建/配置/发布
智能体，以统一入口运行对话；围绕运行时可插拔挂载多模型、会话记忆、RAG 知识库、工具（含 MCP）、
工作流、Skills、插件、多模态、日志诊断与提示词优化。

---

## 功能概览

| 能力 | 说明 |
|------|------|
| 多模型 | OpenAI / DeepSeek / Anthropic(Claude) / 通义 / 文心 / 混元等：直连厂商或经 LiteLLM；本地 Mock 兜底（无外部 Key 也能跑） |
| 模型配置分层 | 平台「默认对话模型」+「嵌入模型」绑定（AES-GCM 加密落库、只回掩码）；智能体未单独配置时自动回退，无需每个智能体重填 Key |
| 会话 | Session 管理、历史持久化与回放、删除/归档；前端对话历史切页不丢，「新对话」才写入会话历史 |
| RAG | 知识库生命周期、文档摄取（解析→切分→向量化→索引）、向量 + FULLTEXT 混合检索、rerank、引用溯源、内容浏览 |
| 工作流 | 自研 DAG 引擎（条件分支 + 虚拟线程并行）、节点 Schema 校验 |
| Skills | **Agent Skills 开放标准目录** `skills/<name>/SKILL.md`：下载的技能放进目录 → 扫描同步即识别，运行时注入提示词 |
| 插件 | SPI + 内置/外部 jar（ClassLoader 隔离）、市场导入/删除、Attach/Detach 热插拔 |
| 工具 | 注册中心、HTTP 工具热注册、MCP(Streamable HTTP) 接入 |
| 多模态 | parts[] 消息模型、文件上传/下载、TTS/ASR 插件化 |
| 运维 | 运行日志落 MySQL（重启不丢）、trace 瀑布图、三级诊断（规则→向量→LLM）、提示词 6 维评分优化 |
| 智能体 | 版本快照/发布/回滚/Diff；删除为物理删除（列表默认隐藏归档） |

技术栈：Java 21（虚拟线程 + ScopedValue）、Spring Boot 3.4、Spring Data JPA + Flyway、MySQL 8、
Redis（可选）、Milvus（可选）、React + Vite + antd。

---

## 环境要求（克隆前请先准备）

| 依赖 | 版本 | 是否必需 | 说明 |
|------|------|----------|------|
| JDK | **21 或更高** | ✅ 必需 | 项目使用预览特性 `ScopedValue`，编译/运行均需 `--enable-preview`（脚本已自动带） |
| Maven | 3.9+ | ✅ 必需 | 构建依赖；首次构建需联网下载 |
| MySQL | 8.x | ✅ 必需 | Flyway 启动时自动建表，连不上则启动失败 |
| Redis | 7 | ❌ 可选 | 缺失时仅健康检查 DOWN，配额走内存兜底 |
| Node / npm | 18+ | ❌ 可选 | 仅修改前端源码并重建时需要 |
| Docker | — | ❌ 可选 | 便捷拉起 MySQL/Redis 等；不使用则手动装 MySQL |
| Milvus/Kafka/MinIO/LiteLLM | — | ❌ 可选 | 全部有开关与降级，默认不开 |

> 首次构建 `mvn package` 需要能访问 Maven Central；首次前端构建需要能访问 npm registry。

---

## 快速开始

### 1. 克隆

```bash
git clone https://gitee.com/<你的用户名>/agent-platform.git
cd agent-platform
```

### 2. 准备 MySQL（二选一）

**方式 A：用 Docker 起 MySQL（推荐）**

```bash
docker compose up -d mysql redis
```

**方式 B：手动创建**（用你本地 MySQL 的 root 执行）

```sql
CREATE DATABASE IF NOT EXISTS agent_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'agent'@'%' IDENTIFIED BY 'agent123456';
GRANT ALL PRIVILEGES ON agent_platform.* TO 'agent'@'%';
FLUSH PRIVILEGES;
```

> 库名/账号密码与默认配置一致；想改走「配置」一节的环境变量。

### 3. 启动

**Windows**（双击或命令行）：

```bat
start-core.bat rebuild        REM 首次/改动后端后用 rebuild；之后可直接 start-core.bat
```

**Linux / macOS**：

```bash
chmod +x start-core.sh
./start-core.sh rebuild
```

**或手动方式（任意系统）**：

```bash
mvn -pl agent-platform-core -am package -DskipTests
java --enable-preview -jar agent-platform-core/target/agent-platform-core-1.0.0-SNAPSHOT.jar
```

启动成功日志含 `Tomcat started on port 8081`，随后浏览器打开 **http://localhost:8081/**。

> - 若 **8081 被占用**，脚本会自动杀掉旧进程（Windows）或提示（Linux/macOS）。
> - Redis 没有也不影响核心功能（日志会提示 redis DOWN）。

### 4. 验证

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"}, ... ,"redis":{"status":"DOWN"}}}  ← redis 可为 DOWN
```

仪表盘首屏应能：新建智能体 → 在「对话运行」发消息 → 未配置任何模型 Key 时会自动返回
**本地 Mock 回复**（可先跑通全流程）。

---

## 配置

平台开箱即用，绝大多数能力可用环境变量开关控制，**无需改任何代码/配置文件**：

| 环境变量 | 作用 | 默认 |
|----------|------|------|
| `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` | 数据源 | `localhost:3306/agent_platform` / `agent` / `agent123456` |
| `SKILLS_DIR` | Skills 目录（标准 SKILL.md 布局） | `./data/skills` |
| `SKILLS_OPEN_FOLDER` | 是否允许仪表盘打开系统文件管理器 | `false`（本地桌面可设 `true`） |
| `STORAGE_TYPE` | 文件存储：`local` / `minio` | `local`（本地磁盘 `./data/files`） |
| `VECTOR_STORE` | 向量库：`in-memory` / `milvus` | `in-memory` |
| `EVENTS_ENABLED` | Kafka 事件总线开关 | `false` |
| `SECURITY_ENABLED` | core 侧 JWT 鉴权开关 | `false` |
| `JWT_SECRET` / `MODEL_KEY_ENC_KEY` | JWT 密钥 / 模型 Key 加密主密钥 | `change-me-*`（**生产务必覆盖**） |
| `AUTH_USERNAME` / `AUTH_PASSWORD` | 登录静态账号（配置后登录需校验） | 空（演示模式签发） |
| `DEFAULT_PROVIDER` / `DEFAULT_MODEL` | 未配置时的模型厂商/型号 | `deepseek` / `deepseek-chat` |

> 说明：`data/` 下目录运行期自动生成；所有外部能力默认关闭、本地 Mock/内存/磁盘兜底，保证「克隆即可跑」；
> 开启真实能力的方式在下方「接入真实模型与可选能力」。

### 接入真实模型（可选，推荐先跑通 Mock 再配）

全部在仪表盘 **「模型设置」** 页完成，仅需配置一次：

1. **默认对话模型**（供对话智能体回退使用）：选服务商（deepseek/openai/qwen/anthropic…）→
   填模型、接口地址 baseUrl、API Key → 保存；
2. **嵌入模型**（RAG 向量化）：选服务商 → 填模型（如 `BAAI/bge-m3`）、baseUrl、API Key → 保存。

未配置时：对话走本地 Mock（确定性文本），RAG 走内存 8 维伪向量——均可本地演示。

单个智能体也可覆盖平台默认：编辑智能体时勾选「为该智能体自定义模型 / API Key」。

### 可选能力接线（Docker）

```bash
docker compose up -d                      # 全部可选设施
export VECTOR_STORE=milvus STORAGE_TYPE=minio EVENTS_ENABLED=true SECURITY_ENABLED=true
start-core.sh rebuild                     # 或手动 java --enable-preview -jar ...
```

| 能力 | 接线方式 |
|------|----------|
| 真实对话/嵌入 | 仪表盘「模型设置」填 Key（直连，不经 LiteLLM）；或起 LiteLLM + `.env` 填 `DEEPSEEK_API_KEY` 等 |
| Milvus 向量 | `VECTOR_STORE=milvus`（维度变化自动重建 collection，旧文档需重新上传） |
| MinIO 文件 | `STORAGE_TYPE=minio`（默认 `MINIO_ENDPOINT/ACCESS/SECRET` 见 `application.yml`） |
| Kafka 事件 | `EVENTS_ENABLED=true` |
| 生产鉴权 | `SECURITY_ENABLED=true` + 覆盖 `JWT_SECRET` + 配置 `AUTH_USERNAME/AUTH_PASSWORD` |

---

## 前端（仪表盘）

生产版：core 同源托管 `src/main/resources/static/`（已随仓库提交）。**改过前端源码后必须重建：**

```bash
# Windows
agent-platform-ui\build-ui.bat            # 或: cd agent-platform-ui && npm install && npm run build:prod
# Linux / macOS
./build-ui.sh                             # 同上等价
```

开发模式（热更新，代理 /api 到 8081）：

```bash
cd agent-platform-ui && npm install && npm run dev   # 访问 http://localhost:5173/
```

> 改动后端后需重启 core；改动前端后需重新 `npm run build:prod` 再重启（或直接访问 5173）。

---

## 数据目录（运行时自动创建，无需预先存在）

| 目录 | 内容 |
|------|------|
| `./data/skills/` | Skills 目录（Agent Skills 标准：`skills/<name>/SKILL.md` + 可选 `scripts/ references/ assets/`）；首次启动自动铺示例，下载的 Skill 放进目录后点「扫描同步」即可识别 |
| `./data/files/` | 文件上传的本地存储（`STORAGE_TYPE=local`） |
| `./data/plugins/` | 外部插件 jar 制品（可选） |

---

## API 概览（前缀 `/api/v1`）

| 域 | 端点 |
|----|------|
| 智能体 | `/agents` CRUD / 版本 / 发布 / 回滚 / diff |
| 会话 | `/sessions` CRUD、`/sessions/import`、`/sessions/{id}/messages` |
| 对话运行 | `/agent/run`（JSON 或 SSE） |
| 知识库 | `/knowledge-bases`、`/{id}/documents`、`/{id}/chunks`、`/documents/{docId}`、`/search` |
| Skills | `/skills` CRUD、`/sync`、`/upload`、`/import-folder`、`/open-folder`、`/{id}/files`、`/{id}/file` |
| 插件 | `/plugins` 市场 / 导入 / 删除 / attach / detach |
| 工具 | `/tools` 列表 / invoke / HTTP 注册 / MCP / 卸载 |
| 模型配置 | `/model-config`（`/embedding`、`/chat`） |
| 日志/诊断 | `/logs`（查询/导出/瀑布图/purge）、`/diagnosis` |
| 文件/配额/提示词 | `/files`、`/quotas`、`/prompt` |

---

## 从演示到生产（检查清单）

- [ ] 覆盖 `JWT_SECRET`、`MODEL_KEY_ENC_KEY`（启动守卫会在「开启鉴权但仍用默认密钥」时拒绝启动）
- [ ] 配置 `AUTH_USERNAME` / `AUTH_PASSWORD`（或接入 OAuth2/LDAP）
- [ ] 开启 `SECURITY_ENABLED=true`
- [ ] 服务端部署设置 `SKILLS_OPEN_FOLDER=false`（默认已关）、`STORAGE_TYPE=minio`、`VECTOR_STORE=milvus`
- [ ] 把 `data/` 挂到持久化卷，日志定期 `DELETE /api/v1/logs/purge`

---

## 测试

```bash
mvn test      # 104 个单元测试（服务/诊断/日志/RAG/工作流/插件/提示词等）
```

## 文档

> `docs/` 下文档已按主题合并归类，只保留有长期价值的内容（已实现/已归档项见 git 历史）。

| 文档 | 内容 |
|------|------|
| [backlog.md](docs/backlog.md) | 未实现事项、能力侧待办与路线决策、API 契约、已踩坑清单 |
| [guides.md](docs/guides.md) | 扩展指南（新模型/工具/MCP/Skill 执行器/插件/诊断/优化策略）、K8s/Helm 部署、端到端演示（可执行脚本 `docs/demo-script.sh`）、可观测性运维（Loki/Tempo/Prometheus/Grafana） |
| [designs.md](docs/designs.md) | 技术设计：对话文件支持（A 已交付，B/C 待做）、仪表盘、桌面打包（已归档） |
| [feasibility.md](docs/feasibility.md) | 可行性分析：仪表盘、桌面打包、可移植性审计、LangChain4j/LangGraph 评估、多层级记忆 |
| [phase-reports.md](docs/phase-reports.md) | 阶段验收报告汇总（Phase 1–7，含与当前代码不一致的修正说明） |
