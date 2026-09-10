# 技术设计与可行性结论

> **合并自**：`designs.md`（对话文件支持 / 仪表盘 / 桌面打包）+ `feasibility.md`
> （仪表盘可行性 / 打包路线 / 可移植性审计 / LangChain 评估 / 多层级记忆）。
> 目的：**设计意图与决策依据**集中一处，避免与现状描述重复。
> 现状（已实现与否、接口、开关）一律以 [status.md](status.md) 为准；未实现事项见 [backlog.md](backlog.md)；
> 具体技术选型与理由见 [technology.md](technology.md)。

---

## 一、已实现设计

### 1.1 对话支持文件拖入与读取 —— ✅ 已交付

**目标**：对话页拖入文件 → 智能体读取内容作为本轮上下文。

**消息结构约定**：历史轮次维持纯文本；**仅当次最新 user 消息**携带附件时用 `parts[]`：

```json
{"role":"user","content":[
  {"type":"text","text":"请总结这份合同的风险点"},
  {"type":"file","fileId":"fid_xxx","fileName":"合同.pdf"}
]}
```

**后端链路**：`AgentRuntimeService.extractUserMessage()` → text part 拼接 → file part 经
`FileUploadService.readText()`（复用 `DocumentParser`）解析 → 以 `[文件：<name>]` 区块注入上下文。

**上下文保护**：单文件截断 100k 字符、单次 ≤4 个文件、合计 200k；超限标注 `(已截断)`；
解析失败只影响该附件，不阻断整轮。

**后续演进（已由其它能力覆盖）**：图片走视觉模型（Spring AI 通道的 `image` part）；
大文件走 RAG（拖入自动摄取进「对话附件库」并入检索）。

---

### 1.2 仪表盘（Web UI）—— ✅ 已实现

**架构**：React 18 + Vite 5 + antd 5 + TypeScript，构建产物输出到
`agent-platform-core/src/main/resources/static/`，由 Spring Boot 同源托管（免 CORS）。
开发态 `npm run dev`（5173）经 Vite 代理 `/api`、`/actuator` 到 8081；路由用 HashRouter。

**关键契约**：

- 统一响应 `ApiResponse<T>`：`{success, code, message, data, traceId, timestamp}`；分页 `PageResult<T>`。
- **唯一例外**：`POST /api/v1/agent/run` 返回裸 `AgentRunResponse`（或 SSE），不套信封。
- 命名：强类型 record → camelCase；自由 Map 入参 → snake_case。
- SSE：`/agent/run` 不能用原生 `EventSource`（只支持 GET），前端用 `fetch` + `ReadableStream`
  手写按 `\n\n` 切帧解析。

**页面**：已超出 v1 设计范围（原设计把 RAG / 工作流 / Skills / 多模态列为扩展点，现均已落地）。
完整清单见 [status.md](status.md) 第三节。

**构建与启动**：`npm run build:prod`（= `vite build` + `scripts/sync-dist.mjs`）→
`mvn -pl agent-platform-core -am package -DskipTests` → `start-core.bat`。
**改前端必须重建并强刷（Ctrl+F5）**。

**修复记录**：`BuiltinPluginSeeder` 以 `tenantId="__platform__"` 建档，而
`PluginService.marketplace()` 只查当前租户 → 插件市场返回空；已改为「当前租户 + 平台租户」
并集，`detail()` 增加平台回退。

---

### 1.3 桌面应用 —— ✅ 已实现（原设计已归档，实际改用 Electron 落地）

**原设计（jpackage + jlink，⛔ 未按此实施）**：

```
agent-platform-<ver>/
├── agent-platform.exe                  # 启动器
├── runtime/                            # jlink 最小 JRE（含 --enable-preview）
├── app/agent-platform-core.jar + static/
└── lib/
```

**实际实现（可运行，见 `desktop/`）**：

| 环节 | 实现 |
|---|---|
| 窗口壳 | **Electron 33.3.1**（`desktop/main.js`）承载窗口与生命周期，非 jpackage |
| 运行时 | `desktop/runtime/` 为 **jlink 精简 JRE**（保留 `--enable-preview`），随包分发，用户免装 Java |
| 后端启动 | 以 `embedded` profile 启动 `resources/backend/app.jar`（H2 file 库，无需 MySQL） |
| 交互细节 | 先出**浅色启动页**（"正在启动本地服务…约 10–25 秒"）→ 健康检查通过后跳转首页 |
| 稳定性 | 单实例锁；窗口关闭 / 退出时回收后端子进程；日志写 `userData/app.log` 便于排查 |
| 数据目录 | `userData/data`（H2 数据文件、skills、files 等） |
| 打包 | electron-builder 产出 **portable 单文件 exe** 与 **zip 绿色版** |

**改造点（原设计预判 → 实际处理）**：

1. **MySQL → 内嵌 H2（MODE=MySQL）**：✅ 以 Flyway **双迁移目录**（`db/migration/mysql` + `h2`）
   解决方言差异，无需改业务代码。
2. **Redis → 内存**：✅ 各 Service 早已是 `@Autowired(required=false)` 可选注入，自动退化。
3. **Milvus / Kafka / MinIO / LiteLLM**：✅ 默认全关或本地兜底，无需新开发。
4. **首次启动向导**：本版未做独立 `/api/v1/setup`，改为「模型设置」页填写平台默认绑定。
5. **签名**：未做代码签名（需 `signtool` 证书），企业分发前需补。

**已知代价**：portable 单文件每次运行都要解压（约 30–60s），**推荐分发 zip 绿色版**（解压一次，
之后每次启动约 20s，其中 Spring Boot 自身约 17s）。

**风险（实际遇到）**：Windows 上运行中的 jar 被占用会导致 `mvn package` 的 repackage 失败
（先停进程）；`--enable-preview` 必须显式传给运行时。

---

### 1.4 可移植性审计（他人克隆后能否直接运行）—— ✅ 必修项已修复

**审计结论**：源码中**无**硬编码机器路径；3 个必修项均已处理：

| 编号 | 问题 | 状态 |
|---|---|---|
| P0-1 | `mvn spring-boot:run` 因未开 `--enable-preview` 失败 | ✅ `spring-boot-maven-plugin` 增加 `jvmArguments=--enable-preview` |
| P0-2 | 仓库内静态资源可能是旧版 | ⚠️ **持续注意项**：每次改前端后必须 `npm run build:prod` 再提交 |
| P0-3 | JDK 版本未强校验 | ✅ `start-core.sh` 含 JDK ≥21 强校验；`.bat` 给出 WARN |
| P1-1 | Skills「打开目录」默认开启、依赖桌面环境 | ✅ 默认改为 `false`；前端禁用时展示可复制路径 |
| P1-2 | 示例 Skill 不随源码分发（`data/` 被忽略） | ✅ 迁入 `resources/skills-sample/`，`SkillSampleSeeder` 启动时铺设 |
| P1-3 | 一键脚本仅 Windows | ✅ 已补 `start-core.sh`、`build-ui.sh` |
| P1-4 | 默认密钥裸奔 | ✅ README「从演示到生产」检查清单覆盖 |

**环境前提**：JDK 21（必需，`ScopedValue` 预览特性）、Maven 3.9+（必需）；
数据库默认 MySQL 8.x，**可用 H2 内置库替代**（`DB_MODE=embedded`）；
Redis / Milvus / Kafka / MinIO / LiteLLM 可选（均有降级）；Node 18+ 仅改前端时需要；Docker 可选。

**克隆者验收路径**：`mvn -pl agent-platform-core -am package -DskipTests` →
`java --enable-preview -jar <jar>` → `/actuator/health` UP → 浏览器打开 `http://localhost:8081/`。

> 依赖已 `go-offline` 固化，可加 `-o` 离线构建；官方中央仓库直连不通，但阿里云镜像可用。

---

## 二、可行性结论（决策依据）

### 2.1 桌面分发路线对比（归档决策依据）

**主要矛盾**：除数据库外，其余中间件均有内存或本地兜底，因此分发只剩两件事——
**替换 MySQL** 与 **内嵌 JRE + 数据目录**。

| 路线 | 交付物 | 体积 | 结论 |
|---|---|---|---|
| A. Docker/OCI 单镜像 | 镜像 + `docker run` | 大（镜像层） | ✅ 最省力、立即可做 |
| B. jpackage 原生安装包 | `.exe/.msi/.dmg/.deb` | 中（~80–120MB 含 JRE） | ⚠️ 曾推荐为主线，实际未采用 |
| C. Electron/Tauri 桌面壳 | 桌面安装包 | 大（Electron）/ 小（Tauri ~10MB） | ✅ **实际采用 Electron**：主要工作量在 DB 内嵌化与运行时精简，不在壳本身 |
| D. GraalVM native-image | 单原生可执行文件 | 小（~40–80MB） | ❌ 与插件 SPI 反射/动态类加载冲突，风险高 |

### 2.2 引入 LangChain / LangGraph 评估 —— ⚠️ 可行但**不采用**

**前提**：LangChain/LangGraph 官方生态为 Python，Java 侧仅社区移植（LangChain4j、LangGraph4j）。

**概念映射**：`AgentRuntimeService`↔Chain/AgentExecutor、`Tool`+`ToolRegistry`↔@Tool、
`RagPipelineService`+`HybridRetriever`+`VectorStore`↔Retriever/VectorStore、`AgentPipeline`↔中间件、
`Workflow` DAG↔LangGraph Graph、`SessionService`↔Checkpointer、`SkillService`↔Prompt 模板。

**三方案**：

- A 全量迁移 Python：❌ ROI 极低，全部重写。
- B 引入 LangChain4j/LangGraph4j 作编排层：⚠️ 可行但非必需。
- **C 借鉴 LangGraph 思想演进现有编排：✅ 采用。**

**不采用 B 的理由**：

1. 工作量 12–20 人日（工具循环、DAG 迁移、RAG 迁移、观测重埋、回归）。
2. 与现有体系不匹配：每请求凭证 + 回退链、插件 Hook 管线、工具责任链（鉴权/限流/审计）、观测埋点位置。
3. RAG 换成框架会丢 MySQL ngram FULLTEXT 混合检索能力。
4. 版本仲裁风险（Jackson/OkHttp/Reactor）、jar 体积再增 20–50MB。
5. LangGraph4j 非官方、生态较新、Checkpoint 序列化需自接 MySQL。

**采用 C 的收益**：有状态编排（子图/回环）、Checkpoint 与断点恢复、条件边与循环、执行可视化——
用现有 `DagEngine` 演进即可覆盖，零依赖、离线可编译。

> 依赖可行性实测：官方中央仓库直连不通（HTTP 000），阿里云镜像可取到 `langchain4j-core`（200），
> 因此「拉不到依赖」不成立，真正障碍是成本与体系冲突。

### 2.3 多层级记忆可行性（部分已落地）

| 层级 | 结论 | 现状 |
|---|---|---|
| 短期（Redis，最近 5–10 轮） | ✅ 已实现 | `SessionRecentCache` + `SessionService`，miss 回 DB 兜底 |
| 中期（对话摘要） | ✅ 已实现 | `SessionSummaryService`（40 轮后懒 rollup）+ 迁移 `V12` |
| 长期（用户显式画像） | ⚠️ 建议先做「用户主动填写」，自动抽取后置 | ❌ 未实现 |
| 向量（历史对话召回） | ⚠️ 最后做，注意无关历史污染上下文 | ❌ 未实现 |

**共同设计点**：写入异步不阻塞主链路；每层失败均可降级；按 `tenant_id + user_id` 隔离；
长期/向量记忆支持用户级一键清除；召回顺序 短期 → 中期 → 长期 → 向量。
