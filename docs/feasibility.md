# 可行性分析与评估报告汇总

> **合并自**：`dashboard-feasibility-report.md`、`desktop-packaging-feasibility-report.md`、`portability-audit.md`，
> 以及原 `FEASIBILITY_ANALYSES.md` 中「引入 LangChain / LangGraph」与「应用化」两节。
> 最后核实：2026-09-08（结论已对照代码现状修订）。

---

## 一、Web 仪表盘可行性 —— ✅ 已实现

**原问题**：项目当时为纯后端形态，只能用 curl 调用，命名 camelCase/snake_case 混用，演示验收不便。

**结论与方案**：React 18 + Vite 5 + antd 5 的 SPA，构建产物由 core 同源托管（免 CORS、无额外中间件、不侵入后端）。v1 范围四项：智能体管理（F1）、对话运行（F2）、插件管理（F3）、运维工具（F4）。

**现状**：已实现，且超出 v1——新增知识库、工作流、Skills、会话、文件、设置等页面（见 `designs.md`）。

---

## 二、打包为可分发应用 —— ⛔ 已归档

**主要矛盾**：除 MySQL 外，其余中间件（Redis/Milvus/Kafka/MinIO/LiteLLM）均有内存或本地兜底，因此打包只剩两件事——**替换 MySQL** 与 **内嵌 JRE + 数据目录**。

**四条路线对比**（结论保留）：

| 路线 | 交付物 | 体积 | 结论 |
|---|---|---|---|
| A. Docker/OCI 单镜像 | 镜像 + `docker run` | 大（镜像层） | ✅ 最省力、立即可做（阶段 1） |
| B. jpackage 原生安装包 | `.exe/.msi/.dmg/.deb` | 中（~80–120MB 含 JRE） | ⚠️ 推荐作为桌面交付主线，需先解决 DB 内置化 |
| C. Electron/Tauri 桌面壳 | 桌面安装包 | 大（Electron）/ 小（Tauri ~10MB） | ⚠️ 主要工作在 DB 内嵌化，非壳本身 |
| D. GraalVM native-image | 单原生可执行文件 | 小（~40–80MB） | ❌ 与插件 SPI 反射/动态类加载冲突，风险高 |

**现状**：未实施，仅保留设计（见 `designs.md` 第三节）。应用化只完成了「一键启动交付」（`start-core.bat/.sh` + `warmup.bat`）。

---

## 三、可移植性审计（他人克隆后能否直接运行）—— ✅ 必修项已修复

**审计结论**：源码中**无**硬编码机器路径；存在 3 个必修项，均已处理：

| 编号 | 问题 | 状态 |
|---|---|---|
| P0-1 | `mvn spring-boot:run` 因未开 `--enable-preview` 失败 | ✅ `spring-boot-maven-plugin` 增加 `jvmArguments=--enable-preview` |
| P0-2 | 仓库内静态资源可能是旧版 | ⚠️ **每次改前端后必须 `npm run build:prod` 再提交**（持续注意项） |
| P0-3 | JDK 版本未强校验 | ✅ `start-core.sh` 含 JDK ≥21 强校验；`.bat` 给出 WARN |
| P1-1 | Skills「打开目录」默认开启、依赖桌面环境 | ✅ 默认改为 `false`；前端禁用时展示可复制路径 |
| P1-2 | 示例 Skill 不随源码分发（`data/` 被忽略） | ✅ 迁入 `resources/skills-sample/`，`SkillSampleSeeder` 启动时铺设 |
| P1-3 | 一键脚本仅 Windows | ✅ 已补 `start-core.sh`、`build-ui.sh` |
| P1-4 | 默认密钥裸奔 | ✅ README「从演示到生产」检查清单覆盖 |

**环境前提**：JDK 21（必需，ScopedValue 预览特性）、Maven 3.9+（必需）、MySQL 8.x（必需）、Redis/Milvus/Kafka/MinIO/LiteLLM（可选，均有降级）、Node 18+（仅改前端时需要）、Docker（可选）。

**克隆者验收**：`mvn -pl agent-platform-core -am package -DskipTests` → `java --enable-preview -jar <jar>` → `/actuator/health` UP → 浏览器打开 `http://localhost:8081/`。
> 补充（2026-09-08）：依赖已 `go-offline` 固化，可加 `-o` 离线构建；官方中央仓库直连不通，但阿里云镜像可用。

---

## 四、引入 LangChain / LangGraph 评估 —— ⚠️ 可行但**不采用**

**前提**：LangChain/LangGraph 官方生态为 Python，Java 侧仅社区移植（LangChain4j、LangGraph4j，非官方且较新）。

**概念映射**（本项目 ↔ 框架）：`AgentRuntimeService`↔Chain/AgentExecutor、`Tool`+`ToolRegistry`+function calling↔@Tool、`RagPipelineService`+`HybridRetriever`+`VectorStore`↔Retriever/VectorStore、`AgentPipeline` Hook↔中间件、`Workflow` DAG+NodeExecutor↔LangGraph Graph、`SessionService`↔Checkpointer、`SkillService`↔Prompt 模板。

**三方案对比**：
- A 全量迁移 Python：❌ ROI 极低，全部重写。
- **B 引入 LangChain4j/LangGraph4j 作编排层**：⚠️ 可行但非必需——需适配 Java 数据与插件体系，不省落地代码。
- **C 借鉴 LangGraph 思想演进现有编排**：✅ **采用**。

**依赖可行性实测（2026-09-08）**：官方中央仓库 `repo1.maven.org`/`repo.maven.apache.org` 直连不通（HTTP 000），但**阿里云镜像可取到 `dev/langchain4j/langchain4j-core`（200）**，去掉 `-o` 的在线编译实测成功。因此「拉不到依赖」不成立，真正的障碍是成本与冲突。

**不采用 B 的理由**：
1. 工作量 12–20 人日（工具循环 2–3、DAG→LangGraph 5–10、RAG 5–10、观测重埋 1–2、回归 2–3）。
2. 与现有体系不匹配：每请求凭证 + 回退链（`ModelBindingService`）、插件 Hook 管线（`AgentPipeline`）、工具责任链（鉴权/限流/审计）、观测埋点位置。
3. RAG 换成框架会丢 MySQL ngram FULLTEXT 混合检索能力。
4. 版本仲裁风险（Jackson/OkHttp/Reactor）、jar 体积再增 20–50MB（当前已 145MB）。
5. LangGraph4j 非官方、生态较新、Checkpoint 序列化需自接 MySQL。

**采用 C 的收益**（对应 LangGraph 强处）：有状态多智能体协作（子图/回环）、Checkpointer/断点恢复/时间旅行、条件边与循环、图执行可视化——用现有 `DagEngine` 演进（State + Checkpoint 落库 + 条件边/循环 + 可视化）即可覆盖，零依赖、离线可编译。

---

## 五、多层级记忆可行性（结论保留，均**未实施**）

| 层级 | 结论 | 现状 |
|---|---|---|
| 短期（Redis，最近 5–10 轮） | ✅ 已实现（2026-09-08） | `SessionRecentCache` + `SessionService` 集成，miss 回 DB 全量兜底 |
| 长期（用户显式画像） | ⚠️ 建议先做"显式填写"，自动抽取后置 | ❌ 未实现 |
| 中期（对话摘要，7–30 天） | ⚠️ 按需 | ❌ 未实现 |
| 向量（历史对话召回） | ⚠️ 最后做，注意上下文污染 | ❌ 未实现 |

**共同设计点**：写入异步不阻塞主链路；每层失败均可降级；按 `tenant_id + user_id` 隔离；长期/向量记忆支持用户级一键清除；召回顺序 短期 → 中期 → 长期 → 向量。
