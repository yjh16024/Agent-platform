# 智能体交互平台 · 仪表盘技术设计文档

> 版本：v1.0 · 日期：2026-09-04 · 关联文档： [可行性分析报告](dashboard-feasibility-report.md)

## 1. 总体架构

```
                        浏览器（http://localhost:8081/ 生产 / localhost:5173 开发）
                                    │
        ┌───────────────────────────┼───────────────────────────────┐
        │ 静态 SPA（React + Vite 构建产物）                          │
        │ core 的 classpath:/static/（同源，spring-boot 自动托管）   │
        └───────────────────────────┬───────────────────────────────┘
                                    │ 相对路径 /api/v1/**、/actuator/**
                                    ▼
                    agent-platform-core（Spring Boot 3.4，端口 8081）
                    （控制器只读 X-Tenant-Id 头；无 Spring Security）
                                    │
            ┌───────────┬───────────┼──────────────┬────────────┐
            ▼           ▼           ▼              ▼            ▼
         MySQL       Redis        Mock 模型      日志/诊断/   提示词优化
         (3306)      (6379)      (local provider)  规则引擎     策略链
```

- **生产**：前端产物进入 `agent-platform-core/src/main/resources/static/`，与 API 同源，**无需 CORS、无需登录**（只认 `X-Tenant-Id`）。
- **开发**：`npm run dev` 起 Vite（5173），经 `server.proxy` 把 `/api`、`/actuator` 代理到 8081。
- **路由**：HashRouter（`#/agents`），零后端改动，刷新/深链不 404。

## 2. 目录结构

```
agent-platform-ui/                       # 独立 Vite 前端项目（非 Maven 模块）
├── package.json                         # 依赖 + dev/build/sync 脚本
├── vite.config.ts                       # react 插件 + /api、/actuator 代理
├── tsconfig.json / index.html           # TS 配置 / 入口（lang=zh-CN）
├── build-ui.bat                         # Windows 一键构建脚本
├── scripts/sync-dist.mjs                # 拷贝 dist/ → core static/
└── src/
    ├── main.tsx                         # 挂载 + HashRouter + antd zh_CN
    ├── App.tsx                          # 路由表（8 条）
    ├── api/
    │   ├── http.ts                      # fetch 封装：租户头/token/解包 ApiResponse
    │   ├── types.ts                     # 与后端 DTO 对齐的 TS interface
    │   ├── auth.ts / agents.ts / run.ts / plugins.ts / ops.ts
    ├── store/appStore.ts                # zustand：tenantId / token
    ├── components/
    │   ├── AppLayout.tsx                # 侧边栏 + 顶栏
    │   ├── TenantSelector.tsx           # 租户切换（写 X-Tenant-Id）
    │   └── StatusTag.tsx                # draft/published 状态徽标
    └── pages/
        ├── Overview.tsx
        ├── agents/{AgentList,AgentForm,AgentDetailDrawer}.tsx
        ├── chat/ChatPage.tsx
        ├── plugins/{PluginMarketplace,PluginDetailDrawer}.tsx
        └── ops/{LogsPage,DiagnosisPage,PromptOptimizePage}.tsx
```

## 3. 技术选型

| 关注点 | 选型 | 理由 |
|--------|------|------|
| 框架 | React 18 | 生态最丰富，antd 官方支持 |
| 构建 | Vite 5 | 快、内置代理、产物为纯静态 |
| 语言 | TypeScript | 用接口约束字段契约，减少拼写错误 |
| UI | antd 5（zh_CN） | 表格/表单/抽屉/评分开箱即用，中文后台事实标准 |
| 路由 | react-router-dom 6（HashRouter） | 免后端 SPA 回退 |
| 状态 | zustand | 极小（~1KB），够用即可，无需 Redux |

## 4. 静态托管与运行方式

- Spring Boot 3.4 默认将 `classpath:/static/` 自动映射到 `/`；core 无 `context-path`，无已存在的 `static/` 目录。
- **生产**：`npm run build:prod`（= `vite build` + `node scripts/sync-dist.mjs`），把 `dist/` 同步到 `agent-platform-core/src/main/resources/static/`；随后 `mvn -pl agent-platform-core -am package -DskipTests` 重新打 jar，`start-core.bat` 或 `java -jar` 启动后访问 `http://localhost:8081/`。
- **开发**：`npm run dev` → `http://localhost:5173/`，`vite.config.ts` 将 `/api`、`/actuator` 代理到 `http://localhost:8081`。

## 5. 接口对接与字段映射（核心契约）

### 5.1 统一响应

- 成功/失败信封 `ApiResponse<T>`：`{ success, code, message, data, traceId, timestamp }`（`success:false` 时为错误）。
- 分页 `PageResult<T>`：`{ items, total, page, size, totalPages }`。
- **唯一例外**：`POST /api/v1/agent/run` 返回**裸** `AgentRunResponse`（或 `stream:true` 时的 SSE），不套信封。

### 5.2 命名约定

- 强类型 `record` 序列化为 **camelCase**：智能体、运行、日志领域。
- 自由 `Map` 入参为 **snake_case**：登录、插件挂载、诊断、提示词。

### 5.3 字段映射表

| 接口 | 命名 | 关键字段（前端必须按此发送） |
|------|------|------------------------------|
| `POST /auth/login` | snake | `tenant_id` `user_id` → `data.token` |
| `POST/PUT /agents` | camel | `name` `persona{tone,role,...}` `systemPrompt` `generationConfig{temperature,maxTokens}` |
| `POST /agent/run` | camel | `agentId` `mode:"agent"` `messages[{role,content}]` `metadata{tenant_id,user_id}` `stream` |
| `POST /plugins/{id}/attach` | **snake** | `agent_id` `version` `config` `enabled` |
| `POST /plugins/{id}/detach` | query camel | `?agentId=` |
| `GET /logs` | query camel | `traceId` `level` `category` `keyword` `page` `size` |
| `POST /logs`（采集） | **camel** | `logId` `level` `category` `message` `traceId` `tenantId` `agentId` |
| `POST /diagnosis/analyze` | **snake** | `trace_id` `message` `category` `fingerprint` |
| `POST /prompt/optimize` | **snake** | `raw_prompt` `context{use_case,persona{tone}}` `options{generate_examples,inject_cot}` |
| `POST /prompt/score` | snake | `prompt` |

## 6. 前端分层

- **`api/http.ts`**：统一 `fetch` 封装——自动附加 `X-Tenant-Id`（持久化于 localStorage），可选 `Authorization: Bearer`；自动解包 `ApiResponse`（`success:false` 抛 `ApiError` 并带 `code`）；对 `/agent/run` 提供 `raw` 通道直取裸体；识别网关 401（`code==="UNAUTHORIZED"`）。
- **`api/*.ts`**：按域封装，内部完成 camel/snake 转换（关键字段写死成正确命名）。
- **`store/appStore.ts`**：zustand 管 `tenantId`/`token`，与 localStorage 双向同步；切换租户后各页 `useEffect` 依赖 `tenantId` 自动刷新。
- **`components/AppLayout.tsx`**：侧边栏 5 组菜单 + 顶栏（租户选择器、登录/退出、Mock 模型提示）。

## 7. 页面设计

| 页面 | 关键交互 | 调用接口 |
|------|----------|----------|
| 概览 | 健康卡片、智能体/插件计数、四域入口 | `GET /actuator/health`、`GET /agents?size=1`、`GET /plugins/marketplace` |
| 智能体列表 | 搜索/状态筛选/分页、新建/编辑/克隆/归档 | `GET/POST/DELETE /agents` 等 |
| 智能体详情 | 版本历史（快照/发布/回滚）、差异对比 | `GET /agents/{id}` `../../versions` `/publish` `/rollback` `/diff` |
| 对话 | 选智能体→发消息→展示回复，流式开关 | `POST /agent/run`（非流式 / SSE） |
| 插件 | 市场、详情、挂载（选 agent + 版本/config/enabled）、卸载 | `GET /plugins/marketplace`、`/{id}/attach`、`/{id}/detach`、`/attachments` |
| 日志 | 筛选 + 分页、上报测试日志 | `GET/POST /logs` |
| 诊断 | 错误信息分析、根因 + 方案（按置信度） | `POST /diagnosis/analyze` |
| 提示词 | 优化（六维评分 + Diff + 建议）、单独评分 | `POST /prompt/optimize`、`/prompt/score` |

## 8. 流式 SSE 实现

`/agent/run` 不支持用原生 `EventSource`（只能 GET），故在 `api/run.ts` 用 `fetch` + `ReadableStream` 手写解析：

1. `fetch('/api/v1/agent/run', { body: { stream:true, ... } })`；
2. `res.body.getReader()` 循环读取，`TextDecoder` 增量解码；
3. 按 `\n\n` 切帧，逐行解析 `event:` / `data:`；
4. `run.delta` 追加增量文本（`output.content` 或 `content`/`delta` 字段），`run.error` 抛错，`run.completed` 结束。

该实现约 40 行，无第三方依赖；Mock 模型为单段返回，真实模型下即为打字机效果。

## 9. 构建与一键启动整合

- `package.json` 脚本：`dev` / `build`（`vite build`）/ `sync:dist` / `build:prod`。
- **`agent-platform-ui/build-ui.bat`**：`npm install` → `npm run build:prod` → 同步产物，双击即完成前端交付。
- **`start-core.bat`**：沿用既有逻辑（自动拉起 Redis + 编译 + 启动 core）；只要 `static/` 存在产物，启动后即为完整「前端 + 后端」。Maven 构建对前端完全无感知，Node 不进 Maven。
- 交付时执行一次 `build:prod` 落盘 `static/`（含 `index.html`、`assets/`）。

## 10. 错误处理与边界

- 所有失败统一 `message.error`（antd）提示，`ApiError` 携带 `code`/`status`；
- `/actuator/health` 中对可能 `DOWN` 的组件容错显示「未启用」，不整体判红；
- 对话无可用智能体时提示先选择；插件 config 非法 JSON 时前端拦截；
- 中文全程 UTF-8（`index.html` 声明、antd `zh_CN`、MySQL 连接串 `characterEncoding=utf8`）。

## 11. 扩展点

- **RAG/知识库**：接入 `GET/POST /knowledge-bases`、`/search`，新增「知识库」页 + 检索调试面板；
- **工作流/Skills**：接入 `/workflows`（DAG 定义/执行）与 `/skills`（导入/列表）；
- **多模态**：接入 `/files/upload`，对话消息支持 `parts[]` 上传图片；
- **真实模型**：部署 LiteLLM 并设置 `LLM_BASE_URL`，界面零改动即换真实模型；
- **生产鉴权**：前端改走网关（8080）+ 登录 Token，`http.ts` 已预留 `Authorization` 注入。

## 12. 实施记录（伴随仪表盘的后端微调）

仪表盘本身零后端改动即可上线；实施过程中发现并修复一处既有 bug，保障「插件」域可视化可用：

- **问题**：内置插件（`plugin_auto_reply`/`plugin_tts_azure`/`plugin_asr`）由 `BuiltinPluginSeeder` 以 `tenantId="__platform__"` 建档，但 `PluginService.marketplace()` 只按「当前租户」查询，导致插件市场对任何租户都返回空；`detail()` 同样查不到平台插件。
- **修复**（`PluginService.java` + `PluginRepository.java`）：`marketplace()` 改为查询「当前租户 + 平台租户」并集；`detail()` 增加平台租户回退。