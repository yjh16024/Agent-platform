# 已可行性分析 / 待实现功能汇总文档

> 文档时间：2026-09-07
> 用途：汇总「已完成可行性分析、但尚未实施」的功能方案，统一归档以便后续按优先级推进。
> 说明：本文档中的图片内容无法被 AI 读取，记忆模块按对话中确认的四级记忆体系整理。
> 状态图例：✅ 可实现且建议做｜⚠️ 可行但需评估/有条件｜❌ 不建议

---

## 目录
1. [多层级记忆体系（短期 / 中期 / 长期 / 向量记忆）](#一多层级记忆体系)
2. [引入 LangChain + LangGraph 的可行性](#二引入-langchain--langgraph)
3. [项目应用化（桌面/一键启动/移动/SaaS）](#三项目应用化)
4. [实施优先级与依赖](#四实施优先级与依赖)

---

## 一、多层级记忆体系

### 1.1 现状对照

| 现状能力 | 实现 |
|---|---|
| 短期上下文（运行窗口） | `recentMessages()` 取最近 N 条回放入 ChatRequest.history |
| 持久化会话 | `recordExchange` 按 turn_no 存 user/assistant 到 MySQL `message` 表 |
| 过期/清理策略 | 无（永久保留，前端"新对话"按钮触发导入） |
| 摘要压缩 / 关键事实抽取 | 无 |
| 用户偏好 / 画像 | 无 |
| 向量召回相关历史 | 无（RAG 仅针对知识库文档） |

**结论**：短期与会话持久化已有；中期/长期/向量三层为空白。

### 1.2 逐层可行性

| 层级 | 存储介质 | 保留内容 | 保留时长 | 访问速度 | 核心作用 | 可行性 |
|---|---|---|---|---|---|---|
| 短期 | Redis | 最近 5–10 轮原始对话 | 1–24 小时 | 极快 | 上下文连贯 | ✅ |
| 中期 | PostgreSQL/MySQL | 对话摘要、关键信息点 | 7–30 天 | 快 | 压缩历史、拉长上下文 | ⚠️ |
| 长期 | PostgreSQL/MySQL | 用户偏好、核心事实、固定信息 | 永久 | 中 | 个性化 | ⚠️（建议显式画像先行） |
| 向量 | 向量库 | 全量历史对话向量化 | 永久 | 中 | 按需召回相关历史 | ⚠️（防无关污染） |

#### 层级 1：短期记忆（Redis，最近 5–10 轮）—— ✅ 建议先做
- **改造**：给 `SessionService` 加可选缓存抽象：会话激活时同步写 Redis（`session:{id}:recent`），`recentMessages` 优先读 Redis，不可用时透明回退 MySQL。
- **依赖**：已具备（Spring Data Redis，且 Redis DOWN 时可降级）。
- **工作量**：低（3–5 个文件）。
- **风险**：低；注意 tenant/session key 命名，避免跨租户串数据。
- **结论**：✅ 容易做、收益明显（响应延迟改善）。

#### 层级 2：中期记忆（对话摘要，7–30 天）—— ⚠️ 按需
- **改造**：`message` 表加 `summary` 字段；`@Scheduled` 每日滚动生成过去 24h 摘要；提供 `summariesBetween(...)` 取某时间段摘要。
- **摘要生成**：复用现有 deepseek/OpenAI 模型 + 固定 prompt（保留关键事实/决策/未决问题）。
- **工作量**：中等（schema 迁移 + SummaryJob + 接口 + 前端）。
- **风险**：中（摘要模型失败需降级为保留原文；token 成本）。
- **结论**：⚠️ 仅在"用户对话会跨多日回看"的场景下值得做。

#### 层级 3：长期记忆（用户画像/事实）—— ⚠️ 建议"显式画像"先行
- **改造**：新增 `user_fact` 表（tenant_id + user_id + fact_key + fact_value + confidence + source_message_id + updated_at）；`FactExtractor`（异步抽取）；组装 system prompt 时追加「用户画像」段。
- **工作量**：中高（抽取 prompt + 置信度 + UI 查看/编辑/删除）。
- **风险**：中高（抽取准确性与隐私）。建议先做**用户显式画像**（主动填写），自动抽取后置。
- **结论**：⚠️ 有价值但默认不要全自动。

#### 层级 4：向量记忆（全量历史向量化召回）—— ⚠️ 最后做
- **改造**：新增 `memory_chunk`（与知识库 chunk 隔离）；`recordExchange` 异步向量化入 `VectorStore`；`retrieveMemory(query, sessionId, userId, k)` 召回历史。
- **依赖**：现有 `VectorStore`（in-memory / Milvus）可复用。
- **工作量**：中（实体 + Repository + upsert + 检索接口）。
- **风险**：中（召回无关历史会造成上下文污染；需按租户/会话过滤 + 降级）。
- **结论**：⚠️ 技术栈已具备，收益取决于能否压住"无关干扰"。

### 1.3 共同设计点（跨层级）
1. **写入时机**：异步（不阻塞主链路）。
2. **降级策略**：每层均可无降级不挂（Redis/向量/摘要失败都不阻断）。
3. **租户/用户隔离**：新表均以 `tenant_id + user_id` 复合查询为前置；向量带租户过滤元数据。
4. **隐私与删除**：长期/向量记忆需支持用户级一键清除，删除时同步清向量。
5. **召回顺序**：短期 → 中期 → 长期 → 向量，运行时要有明确优先级。

### 1.4 建议实施顺序
1. 短期 Redis 缓存（低风险、收益明显）
2. 长期记忆（用户显式画像：纯 UI + 存表，不做自动抽取）
3. 中期摘要（按需）
4. 向量记忆（最后，注意上下文污染）

---

## 二、引入 LangChain + LangGraph

### 2.0 前提修正
- LangChain / LangGraph 官方生态为 Python（LangGraph 另有 JS/TS），**无官方 Java 版**。
- Java 侧仅社区移植：**LangChain4j**、**LangGraph4j**（非官方、较年轻）。

### 2.1 项目现状 vs 框架概念映射

| 本项目 | LangChain/LangGraph 概念 |
|---|---|
| `AgentRuntimeService` | Chain / Runnable / Agent Executor |
| `Tool` + `ToolRegistry` + function calling | @Tool + Tool Registry |
| `RagPipelineService` + `HybridRetriever` + `VectorStore` | Retriever / VectorStore |
| `AgentPipeline` Hook（before/after LLM） | 中间件 / 装饰器 |
| `Workflow` DAG + `NodeExecutor` | LangGraph Graph + Node |
| `SessionService` 会话回放 | Checkpointer / Memory |
| `SkillService`（SKILL.md） | Skills / Prompt 模板 |

### 2.2 三种引入方式对比

| 方案 | 结论 |
|---|---|
| A. 全量迁移 Python（官方 LangChain/LangGraph） | ❌ 不推荐。全部服务/数据/插件/Skill 重写，ROI 极低 |
| B. 引入 LangChain4j / LangGraph4j 作编排层 | ⚠️ 可行但非必需。需适配 Java 数据与插件体系，不省落地代码；LangGraph4j 生态较新 |
| C. 借鉴 LangGraph 思想演进现有编排 | ✅ 推荐。引入 State/Checkpoint、条件边/循环、节点单一职责、可视化 |

### 2.3 现有编排可能缺失点（LangGraph 强处）
1. 有状态多智能体协作（子图/回环）——当前为单 Agent + 插件。
2. Checkpointer / 时间旅行 / 断点恢复——当前仅文本回放。
3. Workflow 条件逻辑与循环——若为静态 DAG 则缺条件边。
4. 图执行可视化调试。

### 2.4 建议
- 明确真实痛点：多 Agent 复杂协作/断点/图可视化（→走 C 或 B）；"觉得框架更标准"（→维持现状）。
- 若走 C：先产出《Workflow 现状 vs LangGraph State/条件边 差距清单》。
- 若走 B：先做 LangGraph4j 小范围 spike，验证 Java 成熟度。

---

## 三、项目应用化

> 本节内容原为独立文档 `APP_DELIVERY_ANALYSIS.md`，已并入本汇总。

### 3.1 现状（起点）
- 前端由 core 托管（`static`），访问 8081 即完整 UI。
- 后端单体 `java -jar`，gateway 非必需。
- 数据可本地化（`storage.type=local` → `./data/files`、`./data/skills`）。
- 配置环境变量化。
- **结论**：已是"单体 Web 应用"，距"应用"只差一键启动壳 + 外部依赖免安装化。

### 3.2 四种形态可行性

| 形态 | 方案要点 | 可行性 |
|---|---|---|
| A 桌面应用 | Tauri（~10MB）或 Electron；内嵌数据库 | ✅ 推荐（主要工作在 DB 内嵌化） |
| B 单机一键启动 | 脚本化交付（jar + data + 一键脚本 + 自动开浏览器） | ✅ 最省力、立即可做 |
| C 移动 App | 前端壳（PWA/Capacitor）+ 远程服务 | ❌ 不建议把后端搬移动端 |
| D SaaS | 鉴权/HTTPS/网关/多租户/对象存储 | ⚠️ 属部署交付 |

### 3.3 技术阻碍
| 阻碍 | 程度 | 说明 |
|---|---|---|
| 强依赖 MySQL 8.4 | 高 | Flyway、ngram FULLTEXT、JSON 字段绑定 MySQL |
| Redis（可降级） | 低 | 已容忍 DOWN |
| Milvus（可选） | 低 | 默认 in-memory |
| 模型联网 + Key | 不可消除 | 纯离线不可行 |
| 默认密钥 | 中 | 分发前须覆盖 JWT_SECRET / MODEL_KEY_ENC_KEY |

### 3.4 建议路线
1. 一键启动交付（低风险）
2. 数据库可移植化（SQLite/FTS5；Flyway 两套迁移）
3. 桌面壳（Tauri/Electron）
4. 可选：PWA / SaaS 部署

---

## 四、实施优先级与依赖

| 排序 | 事项 | 风险 | 依赖 | 状态 |
|---|---|---|---|---|
| 1 | 短期 Redis 记忆 | 低 | Redis（可降级） | 待实施 |
| 2 | 应用化：一键启动交付 | 低 | 无 | 待实施 |
| 3 | 长期记忆（显式画像） | 中 | UI + 存表 | 待实施 |
| 4 | 编排演进（借鉴 LangGraph 思想 / 差距清单） | 中 | 方案 C 或 B 决策 | 待决策 |
| 5 | 中期摘要 / 向量记忆 | 中高 | 场景确认 | 待决策 |
| 6 | 应用化：SQLite 化 / 桌面壳 | 中 | 路线确认 | 待决策 |

> 注：技术缺口相关既有跟踪见 `TECH_GAP_ROADMAP.md`（本文档与其互补，不重复维护）。

---

## 更新记录

| 日期 | 变更 |
|---|---|
| 2026-09-07 | 初版：合并多层级记忆、LangChain/LangGraph、应用化三份可行性分析；原 `APP_DELIVERY_ANALYSIS.md` 并入后删除 |
