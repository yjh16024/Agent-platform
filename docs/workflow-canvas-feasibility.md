# 拖拽式工作流画布 · 可行性分析

> 目标：把当前「贴 JSON 文本」的工作流页面重构为**拖拽式画布**，支持 LLM / 代码 / HTTP / 知识库 / 插件节点，
> 以及调试、发布、Agent 编排；复用平台已有的插件、Skills、知识库、模型能力。
> 最后核实：**2026-09-11**（代码逐项对照；Coze Studio / FlowGram 信息来自官方仓库与 npm registry）。
>
> **落地状态（2026-09-11 已实现并验证）**：阶段 1（契约统一 + 画布骨架）、阶段 2（11 类节点 + 6 个执行器）、
> 调试（节点级轨迹 + 画布调试面板）、发布（V13 + 版本递增 + 一键回滚）均已完成；
> **143 个测试全绿**，端到端验证通过（顺序链执行 / 调试轨迹 / 发布回滚 / HTTP 节点真实调用 wttr.in）。
> 仅「Loop 循环节点」与「子工作流节点」按计划未做（见 §七）。

---

## 一、结论先行

| 问题 | 结论 |
|---|---|
| 能不能 fork Coze Studio 前端达成目标？ | **技术上可以（Apache-2.0），但不建议**——它是一整套平台前端（Rush+PNPM monorepo + Rsbuild + 自研设计系统 `@coze-arch/coze-design`），且**强耦合 Coze 的 Golang 后端接口契约**，迁入后等于接手一个陌生大型前端工程，与现有 antd 体系冲突 |
| 能不能用 iframe 直接嵌 Coze Studio？ | **能嵌，但不能用**——Coze 需 **Docker + 11 个容器（MySQL/Redis/ES/MinIO/etcd/Milvus/NSQ×3 + web/server）**，最低 4C8G/50GB；且**它的插件、知识库、模型凭证与我方完全不通**，等于放弃平台自有资产（含"复用已有插件与 Skills"这一明确需求）。详见 §3.1 |
| 有没有更划算的路？ | **有**。Coze 的画布能力来自独立开源项目 **FlowGram**（`@flowgram.ai/*`，**MIT**，React ≥16.8），可直接引入现有 React 18 + Vite + antd 工程 |
| 真正的工作量在哪？ | **不在画布，在后端**：14 种节点类型中 **8 种没有执行器**、无节点级 trace/流式、无版本发布能力 |
| 总工作量估算 | **17–26 人日**（单人 3–5 周）；其中前端画布只占约 3–5 人日 |

**推荐路线（首选）**：引入 FlowGram 重写 `/workflows` 页面（保留 antd 外壳），同时补齐后端 5 个节点执行器 + 调试事件流 + 版本发布。

**iframe 的正确用法**：把 Coze Studio 部署在旁路端口，作为**交互对标与设计参考**（研究它的节点形态、变量面板、调试体验），**不进产品**。

---

## 二、事实核查

### 2.1 Coze Studio（`coze-dev/coze-studio`）真实情况

| 维度 | 事实 | 对我们的影响 |
|---|---|---|
| 许可证 | **Apache-2.0**（2025-07-25 开源） | 允许 fork 与商用，法务上无障碍 |
| 后端 | **Golang**（Hertz + DDD 微服务分层） | ✗ 与我们的 Spring Boot 完全不通用 |
| 前端 | React 18 + TypeScript + **Rsbuild** + **Rush + PNPM**（monorepo） + Zustand | ✗ 构建体系与 Vite 不同；workspace 依赖需整体迁入 |
| 设计系统 | **`@coze-arch/coze-design`**（自研）、`@coze-arch/i18n` 等私有包 | ✗ 与 antd 5 冲突；替换 UI 库等于重写全部界面 |
| 画布 | **FlowGram**（`@flowgram.ai/*`）——非自研 | ✓ 可独立取用（关键机会） |
| 目录规模 | `frontend/{apps/coze-studio, packages/agent-ide, packages/...}` 多包工程 | ✗ 无法跟随上游 rebase，维护成本高 |

**判断**：fork 的是"平台"，我们需要的只是"画布"。取 FlowGram 即可，绕开整仓迁移。

### 2.2 FlowGram（`bytedance/flowgram.ai`）真实情况

| 维度 | 事实 |
|---|---|
| 许可证 | **MIT**（Copyright (c) 2025 Bytedance Ltd.）——可商用/可改/可闭源，**仅需保留版权与许可声明** |
| 包与版本 | `@flowgram.ai/fixed-layout-editor` **1.0.15**、`@flowgram.ai/free-layout-editor` **1.0.15**、`@flowgram.ai/form-materials` **1.0.15**、`@flowgram.ai/editor` **1.0.15** |
| React 要求 | **peerDependencies: react >= 16.8** → 与项目 React **18.3.1** 兼容 ✓ |
| 能力 | 固定布局画布（Coze 工作流同款：节点顺序 + 分支/循环复合节点）、自由布局画布、**表单引擎**（节点配置增删改查/校验/联动）、**变量引擎**（作用域/类型推断）、**物料**（LLM / Condition / Code 等开箱节点）、运行时 |
| 采用方 | **Coze Studio**、NNDeploy、Certimate |

**判断**：FlowGram 正是把我们最缺的"画布 + 节点表单 + 变量系统"一次补齐，且与现有技术栈零冲突。

### 2.3 本项目工作流现状（缺口清单）

**后端模型已具备画布基础**（`WorkflowNode`/`WorkflowDefinition` 为 record，`${var.path}` 变量 + `next` 连线 + `branches` 分支，整体 JSON 落 `workflow_def.definition`）。

| 缺口 | 现状 | 影响 |
|---|---|---|
| 节点执行器 | `NodeType` 声明 **14 种**，仅 **6 种**实现（Start/End/LLM/Tool/Condition/Transform） | 画布若暴露 KnowledgeBase / Skill / Plugin / Agent / Http 节点，**运行必抛** `No executor for node type` |
| 调试 | 单请求内内存递归执行，只返回**最终变量快照**；无节点级 trace、无 SSE | 画布无法"运行高亮节点 / 单步 / 查看每节点输入输出" |
| 发布 | `workflow_def.version` 字段存在但**从未赋值**；无快照/发布/回滚 | 调试态与发布态无法隔离 |
| Schema 一致性 | 后端用 `next`，前端示例 JSON 用 `edges` | **链路实际断开**，重构必须先统一 |
| 校验 | 校验 ID 唯一/环/分支目标，但**不校验节点类型是否有执行器、必填 config** | 画布产出非法图要到运行期才炸 |
| 画布 | `WorkflowsPage.tsx` 为 Table + `Input.TextArea` 贴 JSON，**无任何画布/拖拽库** | 需从零引入（这正是引入 FlowGram 的理由） |

**可复用的平台能力（画布节点直接可用）**：

| 目标节点 | 复用入口 | 状态 |
|---|---|---|
| 知识库节点 | `HybridRetriever.search(kbIds, query, topK, threshold, filter, rerank)` | 需新建执行器（能力已在） |
| 代码节点 | `SkillExecutionService.run(tenantId, skillId, type, command, args)` + `ScriptSkillExecutor`（白名单 python/node/bash + 超时 + 输出截断） | 需新建执行器（沙箱已在） |
| HTTP 节点 | `HttpApiTool` / `HttpSkillExecutor`（POST JSON） | 需新建执行器（实现已在） |
| 插件节点 | `ToolExecutor.run(toolName, args, ctx)`——插件工具经 `PluginToolAdapter` 已注册进 `ToolRegistry` | 需新建执行器（桥接已在） |
| LLM 节点 | `ModelRouter.chat(provider, req)`（现有 `LlmNodeExecutor` 可扩参：当前写死 `provider="auto"`） | 已实现，需扩参 |
| Agent 编排节点 | `AgentRuntimeService` 跑子智能体 | 需新建执行器 |

---

## 三、方案对比

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. fork Coze Studio 前端** | 整仓（或多包）迁入，替换其控制台 | 界面即 Coze 体验、功能完整 | monorepo+Rsbuild+自研设计系统迁入；强耦合 Coze Golang 后端契约；所有数据层重写；无法跟随上游；与 antd 体系冲突 | ❌ 不采用 |
| **B. 引入 FlowGram 自建画布** | 保留 antd 外壳，`/workflows` 换成 FlowGram 画布；节点表单用 antd + FlowGram Form | 拿到 Coze 同款画布内核；MIT；React≥16.8 兼容；只改一个页面；**平台自有插件/Skills/知识库全部可用**；与桌面分发兼容 | 节点表单/变量映射需自研；FlowGram 1.x API 可能变动（锁版本） | ✅ **首选** |
| **C. 引入 React Flow 自建** | `@xyflow/react` 只做节点/边渲染，其余全自研 | 生态最大、文档最多、最灵活 | 需自研节点表单、变量系统、分支/循环复合节点、对齐吸附——即重做 FlowGram 已有的部分 | ⚠️ 备选（若需特殊交互） |
| **D. 纯自研画布** | 自己写拖拽/连线 | 无依赖 | 成本最高、体验难达 Coze 水准 | ❌ 不采用 |
| **E. iframe 嵌入 Coze Studio** | 旁路部署 Coze（Docker），在页面里 `<iframe src=":8888">` | **最快见效（零前端开发）**、功能最全、可用于交互对标 | 见 §3.1：与"复用自有插件/Skills/知识库"直接冲突；需 Docker+11 容器；认证与数据双割裂；桌面版无法承载 | ⚠️ 仅作**对标参考**，不进产品 |

> 注：FlowGram 官方专门有一节「对比 ReactFlow」，定位差异为：ReactFlow 是**渲染引擎**（Node/Edge/Handle 底层），FlowGram 是**开箱的流程编辑方案**（含表单、变量、复合节点、物料）。

### 3.1 专论：iframe 嵌入 Coze Studio 为什么不能进产品

Coze Studio 的部署形态（据官方部署手册，2026-09 核实）：

| 项 | 事实 |
|---|---|
| 容器 | **11 个**：`coze-web`、`coze-server`、MySQL、Redis、Elasticsearch、MinIO、etcd、Milvus、nsqlookupd、nsqd、nsqadmin |
| 硬件 | 最低 **4 核 8GB / 50GB 磁盘**，推荐 16GB；需 `vm.max_map_count=262144` |
| 前置 | **必须 Docker + Docker Compose**；启动需按 5 步顺序（存储层就绪需 60–120s） |
| 对外 | 仅 `8888` 一个端口（Nginx 静态资源 + API 反代） |

据此，与我们的现实有 **6 处硬冲突**：

1. **与核心需求直接冲突**：需求是"可以使用平台已有的插件和 skills"。iframe 里跑的是 Coze 自己的插件体系与知识库，
   **我方 `ToolRegistry` / `PluginRuntime` / `SkillExecutionService` / `HybridRetriever` 一个都用不上**——
   等于把平台降级成"Coze 的启动器"。
2. **与桌面分发根本冲突**：桌面版是 Electron + jlink 精简 JRE + H2（**免装、双击即用、zip 345MB**）。
   iframe 方案要求用户先装 Docker Desktop 并跑 11 个容器（8–16GB 内存）——**桌面版无法内置，也没法分发**。
3. **认证割裂**：我方是 core 内 JWT（`SECURITY_ENABLED` + 静态账号），Coze 有自己完整的注册/登录体系；
   没有 SSO，要么让用户登录两次，要么改 Coze 源码（又回到 fork 的坑）。
4. **数据双割裂**：知识库（我方 MySQL/FULLTEXT + Milvus；Coze 用 ES + Milvus）、模型凭证
   （我方 AES-GCM + 三级回退；Coze 用 `MODEL_*_N` 环境变量）、会话与运行日志，**互不可见**。
   用户在"我们平台"上传的文档，在画布里检索不到。
5. **体验割裂**：iframe 内是 Coze 的**完整控制台**（它自己的顶部导航、Agent 列表、插件市场），
   与本平台导航并存，用户会直接看到"两个产品套在一起"。
6. **不可定制**：想在嵌入的画布上加一个"知识库节点"指向我方数据，仍要改 Coze 前端——
   即回到方案 A 的全部代价，却多背了一套 Docker 依赖。

**技术上能绕过的部分**（非阻塞）：`X-Frame-Options` / CSP 可通过自建 Nginx 配置去掉；跨域用同源反代解决。
但上面 6 条是**架构级的**，绕不过去。

**iframe 的正确用途**：并行部署一套 Coze（旁路端口，不进产品），作为**交互对标样本**——
看它的节点形态、变量引用方式、调试面板信息层级，用来校准我们自建画布的交互设计。这是低成本高价值的用法。


---

## 四、推荐方案设计（方案 B）

### 4.1 前端架构

```
pages/workflows/
├── WorkflowsPage.tsx          # 列表页（保留 antd Table）+ 「打开画布」入口
├── canvas/
│   ├── WorkflowCanvas.tsx     # FlowGram FixedLayoutEditor 容器
│   ├── nodes/                 # 每种节点一个渲染组件（复用 antd 组件做表单）
│   │   ├── LlmNode.tsx  CodeNode.tsx  HttpNode.tsx
│   │   ├── KnowledgeNode.tsx  PluginNode.tsx  AgentNode.tsx
│   │   ├── ConditionNode.tsx  StartEndNode.tsx
│   ├── panel/                 # 右侧属性面板 / 底部调试面板
│   └── adapters/
│       ├── toBackend.ts       # 画布图 → 后端 WorkflowDefinition（next/branches 格式）
│       └── toCanvas.ts        # 后端 Definition → 画布图
```

- **画布内核**：`@flowgram.ai/fixed-layout-editor`（Coze 工作流同款：顺序流 + 分支/循环复合节点）。
- **节点表单**：FlowGram Form 引擎 + antd 控件（`@flowgram.ai/form-materials` 提供基础控件）。
- **变量**：沿用后端 `${var.path}` 约定；画布变量面板展示上游可见变量（FlowGram 变量引擎的作用域链正好匹配）。
- **契约统一（必须先做）**：**以 `next`/`branches` 为准**改写前端示例与适配器，废弃示例里的 `edges`。

### 4.2 节点清单（本期）

| 节点 | 后端执行器 | 配置项（画布表单） |
|---|---|---|
| Start / End | ✅ 已有 | 输入输出变量声明 |
| LLM | ✅ 已有（需扩 `provider`/`model`/`temperature`/`maxTokens`） | 模型、系统提示词、用户提示词（支持 `${var}`） |
| Condition | ✅ 已有 | 分支表达式 + 目标节点 |
| Transform | ✅ 已有 | 字段映射 |
| Tool | ✅ 已有 | 工具名（下拉：从 `GET /tools` 拉）+ 参数映射 |
| **知识库** | 🆕 新建 `KnowledgeBaseNodeExecutor` | 知识库选择（多选）、query 模板、topK、阈值 |
| **代码** | 🆕 新建 `SkillNodeExecutor`（复用 `SkillExecutionService`） | Skill 选择 + 执行类型（prompt/script/http）+ 参数 |
| **HTTP** | 🆕 新建 `HttpNodeExecutor`（复用 `HttpApiTool`） | URL / Method / Headers / Body 模板 |
| **插件** | 🆕 新建 `PluginNodeExecutor`（走 `ToolExecutor` 调插件工具） | 插件工具选择 + 参数 |
| **Agent 编排** | 🆕 新建 `AgentNodeExecutor`（调 `AgentRuntimeService`） | 目标 Agent + 输入映射 + 输出变量 |

> `Loop` 节点**暂不做**：后端无实现，且 FlowGram 的循环复合节点需要后端配合（工作量大，列入后续）。

### 4.3 调试与发布

| 能力 | 实现方式 |
|---|---|
| 逐节点运行态 | 后端在 `DagEngine` 增加**节点级事件回调**，经 SSE 推送 `node.started / node.finished / node.failed`（含每节点输入输出与耗时）；前端按事件给节点染色 + 显示耗时节 |
| 单步 / 断点 | 基于同一事件流：前端发 `continue` 信号，引擎在节点间等待（用 `CompletableFuture` + 阻塞队列实现，超时兜底） |
| 单节点试跑 | 复用 `POST /workflows/{id}/execute` 加 `fromNode`/`onlyNode` 参数（只跑子图） |
| 发布 | 移植 `AgentVersionService` 模式：`POST /workflows/{id}/versions`（快照）、`/publish`、`/rollback/{version}`、`GET /versions`、`/diff`；`workflow_def.version` 字段启用；**调试用 draft，线上用 published** |

### 4.4 校验补强

在 `WorkflowSchemaValidator` 增加：节点类型是否有对应执行器、必填 `config`（如 LLM 的 prompt）、`outputVar` 冲突检测、变量引用可解析性——**让错误在保存时就暴露，而不是运行期**。

### 4.5 方案 B 的最终效果（做完长什么样）

**一句话**：能做到与 Coze 工作流**同级**的可视化拖拽编排——因为 Coze 的画布用的就是 FlowGram；差别在于
**节点库是我们自己的能力**（平台自有 LLM 路由 / 知识库 / 插件 / Skills / Agent），而不是 Coze 那些节点。

**界面布局**（`/workflows` → 点某个工作流进入画布）：

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ ← 返回   智能体客服编排   [已保存 ✓]        [试运行 ▶] [单步 ⏭] [发布 🚀] [版本] │
├────────────┬────────────────────────────────────────────────┬────────────────┤
│ 节点库      │                                                │ 节点配置        │
│ ─────────  │      ┌─────────┐                               │ ─────────────  │
│ 🔹 开始     │      │ 开始    │                               │ 节点：LLM 生成  │
│ 🤖 LLM      │      └────┬────┘                               │ 模型  [deepseek▾]│
│ 📚 知识库   │           ▼                                    │ 系统提示词      │
│ 🌐 HTTP     │      ┌─────────┐    ┌──────────┐               │ ┌────────────┐ │
│ 💻 代码     │      │ LLM 生成 │───▶│ 知识库检索│              │ │你是客服助手 │ │
│ 🧩 插件     │      └────┬────┘    └────┬─────┘               │ └────────────┘ │
│ 🔀 条件分支 │           │              ▼                     │ 用户提示词      │
│ 🎯 Agent    │           ▼        ┌──────────┐               │ ┌────────────┐ │
│ 🔚 结束     │      ┌─────────┐   │ 条件分支  ├─ 需要转人工 ─▶ │ │{{input}}   │ │
│            │      │  结束   │◀──┤         ├─ 否 ─▶ ...     │ └────────────┘ │
│ （拖到画布） │      └─────────┘   └──────────┘               │ ＋ 插入变量      │
├────────────┴────────────────────────────────────────────────┴────────────────┤
│ 变量面板：input · kb_result.chunks[] · llm_output.text                        │
├──────────────────────────────────────────────────────────────────────────────┤
│ 调试面板  [● 运行成功 1.8s]                                                   │
│  开始          12ms   out: {input:"你们营业时间?"}                            │
│  LLM 生成      1.4s   out: {text:"...", tokens:120}          ← 点击节点看详情  │
│  知识库检索    380ms  out: {chunks:[3 条], scores:[0.92,...]}                 │
│  条件分支      2ms    → 命中「需要转人工」                                     │
└──────────────────────────────────────────────────────────────────────────────┘
```

**交互闭环**：左侧拖节点进画布 → 连线成流程 → 右侧配每个节点（表单校验实时提示）→ 点「试运行」→
逐节点亮起并写入调试面板 → 出问题点开某节点看入参/出参 → 满意后「发布」生成版本快照 → 线上跑的是已发布版本，
草稿可继续改。

**哪些是开箱的、哪些要我们做**：

| 能力 | 来源 | 说明 |
|---|---|---|
| 画布引擎（节点渲染、拖拽、连线、缩放、小地图、快捷键、撤销重做） | ✅ **FlowGram 开箱** | 固定布局：框选拖拽、水平/垂直布局切换、**分支折叠**、分组 |
| 节点配置表单（渲染/校验/联动/副作用/错误捕获） | ✅ **FlowGram 开箱** | 表单引擎，无需自己写表单框架 |
| 变量引擎（作用域约束、结构透视、类型推导） | ✅ **FlowGram 开箱** | 变量面板只做"展示 + 插入"，唯一真相仍是后端 `${var.path}` |
| 附加插件（背景、小地图、导出、面板管理） | ✅ **FlowGram 开箱** | `background/minimap/export/panel-manager-plugin` |
| 8 类节点的**外观与表单内容** | 🛠 我们做 | LLM / 知识库 / 代码 / HTTP / 插件 / Agent / 条件 / 开始结束 |
| 与后端 `next`/`branches` 的双向适配 | 🛠 我们做 | 含双向单测（图→JSON→图 幂等） |
| **节点级调试可视化**（运行态染色 + 每节点耗时/入出参） | 🛠 我们做 | 依赖后端新增 SSE 事件流 |
| **发布 / 版本 / 回滚 UI** | 🛠 我们做 | 依赖后端新增版本能力 |
| 循环复合节点、子工作流节点 | ⛔ 本期不做 | 后端 `Loop` 无实现；FlowGram 支持复合节点，等后端补齐再接 |

**与 Coze 的差异（诚实说明）**：

1. **节点库不同**：Coze 的插件市场/知识库/模型是它的；我们是平台自有的（这也是选方案 B 的原因）。
2. **调试面板的丰富度**：Coze 调试面板做了多年打磨，我们的第一版会更简（运行态染色 + 入出参 + 耗时），
   后续按需加"单步 / 断点 / 变量快照对比"。
3. **本期不支持循环/子流程**：Coze 有 Loop 与子工作流节点，我们等后端补齐再做（画布会拦截这类类型，避免画出跑不通的图）。
4. **视觉风格**：画布外观由 FlowGram 决定（与 Coze 同源，相似度高），外围仍是本平台的 antd 控制台。

**验收清单**（阶段 1–2 做完，满足以下 8 条即达到"可用"）：

- [ ] 能从节点库拖出节点、连线、删除、撤销重做，刷新后布局保持
- [ ] LLM / 知识库 / 代码 / HTTP / 插件 / Agent 六类节点可配置并保存
- [ ] 变量面板能看到上游可见变量并可一键插入到输入框
- [ ] 条件分支可配表达式并正确分流（含分支折叠展示）
- [ ] 未连到「结束」的图、引用不存在的变量，**保存时就被拦下**
- [ ] 点「试运行」后逐节点亮起，每个节点可查看入参/出参/耗时
- [ ] 「发布」生成版本快照，草稿修改不影响已发布版本，可回滚
- [ ] 画出的流程能真实调起平台模型 / 知识库 / 插件 / Skills（数据从我方库来）

---

## 五、分阶段落地计划与工作量

| 阶段 | 内容 | 交付验证 | 估算 |
|---|---|---|---|
| **1. 契约统一 + 画布骨架** | 统一 `next` 格式；接入 FlowGram；Start/End/LLM/Condition 四类节点可拖拽、可保存、可加载、可执行 | 画布画一条 LLM 链 → 保存 → 执行 → 返回结果 | **3–5 人日** |
| **2. 节点类型补齐** | 后端 5 个执行器（知识库/代码/HTTP/插件/Agent）+ 前端对应节点表单；Tool 节点下拉接 `/tools` | 每条节点单独执行通过 + 组合流跑通 | **5–8 人日** |
| **3. 调试** | 节点级 trace + SSE 事件流（含单步）+ 画布运行态高亮 + 节点输入输出面板 | 画布点「运行」逐节点亮起，能看到每节点入参/出参 | **4–6 人日** |
| **4. 发布** | workflow 版本快照/发布/回滚/diff（移植 Agent 实现）+ 草稿/已发布隔离 | 发布后线上版本不受草稿编辑影响 | **2–3 人日** |
| **5. Agent 编排增强** | Agent 节点 + 子工作流节点 + 并行分支可视化（后端当前靠 `next[]` 多元素隐式并行） | 主流程编排两个子 Agent 并汇总 | **3–4 人日** |
| | | **合计** | **17–26 人日** |

---

## 六、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| FlowGram 处于 1.0.x 早期，API 可能变动 | 升级时需改画布代码 | 锁定 `1.0.15`；画布相关代码集中在 `pages/workflows/canvas/`，隔离升级面 |
| 画布图 ↔ 后端 `next`/`branches` 双向映射易错 | 保存后链路断裂 | 适配器 `toBackend/toCanvas` 写**双向单测**（图→JSON→图 幂等） |
| 变量系统两套（FlowGram 变量引擎 vs 后端 `${var}`） | 引用对不上 | 约定"唯一真相是后端 `${var.path}`"；画布变量面板只做展示与插入，不自行求值 |
| 后端引擎是"单请求内存递归"，加单步需改造 | 引擎复杂度上升 | 事件回调 + 可选阻塞点，默认非阻塞（正常执行零开销）；单步仅在调试模式开启 |
| 循环节点缺失 | 画布若暴露 Loop 会运行失败 | 本期**不暴露** Loop；Validator 拦截"无执行器的类型" |
| 版本快照存 definition 全量 JSON | 表体积增长 | 与 Agent 相同策略（快照 + 哈希去重），必要时只存 diff |

---

## 七、明确不做（本期）

- ❌ fork 或迁入 Coze Studio 前端任何代码（仅将其作为交互参考）。
- ❌ 替换 antd 设计体系（画布外仍用 antd，画布内 FlowGram 自带主题）。
- ❌ Loop / Parallel 显式节点（后端无实现；并行仍由 `next[]` 隐式承担）。
- ❌ 画布内直接编辑 Skill 脚本 / 插件 jar（沿用现有 Skills、插件页面，画布只做引用）。

---

## 八、参考

- Coze Studio：`coze-dev/coze-studio`（Apache-2.0；Golang 后端 + React 前端）
- FlowGram：`bytedance/flowgram.ai`（**MIT**；`@flowgram.ai/fixed-layout-editor@1.0.15`，react >= 16.8）
- 本项目现有能力：[status.md](status.md)（工作流现状） / [technology.md](technology.md)（技术选型） / [backlog.md](backlog.md)（缺口登记）
