# 智能体交互平台 · 仪表盘可行性分析报告

> 版本：v1.0 · 日期：2026-09-04 · 关联文档： [技术设计文档](dashboard-technical-design.md)

## 1. 引言

### 1.1 背景

`agent-platform`（智能体交互平台）是一个基于 **Java 21 · Spring Boot 3.4** 的多模块后端项目，已实现多模型对接、会话上下文、RAG、工作流编排、多模态、工具调用、Skills 管理、插件系统、运行日志诊断与提示词优化等核心能力。

然而项目**当前为纯后端形态，没有任何前端页面，也未集成 Swagger**，使用方只能通过 `curl` 手工调用 REST 接口。这带来三个明显问题：

1. 上手门槛高——需要记忆端点路径与字段名（且字段命名存在 camelCase / snake_case 混用）；
2. 演示与验收不便——难以直观展示「创建智能体 → 运行对话 → 日志诊断 → 提示词优化」的完整链路；
3. 操作效率低——CRUD、版本发布/回滚等高频操作无图形界面。

### 1.2 目的

本报告评估为该项目**设计并实现一个 Web 仪表盘**的可行性，回答三个问题：

- 技术上能否在不侵入后端、不引入额外中间件的前提下实现？
- 成本与运维代价是否可控（尤其在本机「无 Docker」的约束下）？
- 应采用哪种实现方案，风险点在哪里？

### 1.3 术语

| 术语 | 说明 |
|------|------|
| SPA | 单页应用（Single Page Application），路由在浏览器内完成 |
| Vite | 现代前端构建工具，提供开发热更新与生产打包 |
| antd | Ant Design，蚂蚁集团开源的企业级 React 组件库 |
| SSE | Server-Sent Events，服务端单向推送的流式响应 |
| 同源 | 页面与其调用的 API 处于同一 协议+域名+端口，浏览器不拦截 |
| CORS | 跨域资源共享，不同源前端调 API 时需服务端显式放开 |
| 租户（Tenant） | 数据隔离维度，后端通过 `X-Tenant-Id` 请求头识别 |

## 2. 需求分析

### 2.1 功能需求（v1 范围，四项已确认）

| 编号 | 功能域 | 覆盖能力 |
|------|--------|----------|
| F1 | 智能体管理 | 列表/搜索/筛选、新建/编辑/克隆/归档、版本快照、发布、回滚、差异对比 |
| F2 | 对话运行 | 选择智能体发消息，展示回复（含 usage/traceId），支持流式（SSE） |
| F3 | 插件管理 | 插件市场、详情、挂载到智能体 / 卸载、查看当前挂载状态 |
| F4 | 运维工具 | 运行日志查询/上报、智能诊断（展示根因与方案）、提示词优化（评分+Diff） |

**明确不在 v1**（预留扩展点）：RAG/知识库、工作流编排、Skills、多模态文件、租户配额。

### 2.2 非功能需求

- **一键启动**：沿用现有 `start-core.bat`，浏览器打开 `http://localhost:8081/` 即用；
- **无需 Docker**：本机无 Docker，完全依赖本机 MySQL + 便携 Redis + Mock 模型；
- **中文界面**：面向中文用户，使用 antd `zh_CN` 语言包；
- **低耦合**：不修改后端业务代码即可上线（零后端改动）。

## 3. 现状分析

### 3.1 后端能力盘点

经全量盘点，后端共 **12 个 `@RestController`**，均位于 `agent-platform-core`；网关（8080）仅做 `/api/v1/**` 到 core（8081）的转发与 JWT 鉴权。v1 所需的四类接口**均已实现且可用**（智能体 CRUD/版本、`/agent/run`、插件、日志/诊断/提示词优化）。

### 3.2 认证与租户现状（关键结论）

- **core 自身无 Spring Security**，控制器只读 `@RequestHeader("X-Tenant-Id", defaultValue="default")`；
- JWT 校验仅在网关 `JwtAuthFilter`（除白名单 `/actuator/health`、`/api/v1/auth/login` 等）；
- 结论：仪表盘若由 core 同源托管、直连 core，**只需携带 `X-Tenant-Id` 头即可完成租户隔离，无需登录即可用**；登录/JWT 作为可选项保留，为将来切换网关预留。

### 3.3 环境约束

| 项 | 现况 | 影响 |
|----|------|------|
| Docker | 未安装 | 无法 `docker compose` 起 Milvus/LiteLLM |
| Node | v24.13.1 / npm 11.8.0 | 前端构建可行 |
| JDK / Maven | 便携 `~/tools`（21 + 3.9.9） | 后端构建可行 |
| MySQL | 本机 8.0.23 原生运行 | 数据层就绪 |
| Redis | 已部署便携版（8.10.1，6379） | 健康检查全绿 |
| 大模型 | Mock（`local` provider） | 对话返回确定性示例文本，**演示无须外部服务** |

## 4. 技术可行性

### 4.1 前端技术栈适配性

**React 18 + Vite 5 + TypeScript + antd 5 + react-router-dom 6 + zustand**：

- antd 提供表格/表单/抽屉/弹窗/评分/进度条等，几乎是「中文后台系统」的事实标准，**大幅降低表单类与表格类页面开发量**；
- Vite 构建产物为纯静态资源（`index.html + assets/*`），天然可被 Spring Boot 托管；
- TypeScript 用与后端 DTO 对齐的 interface 定义字段契约，减少字段拼写错误。

### 4.2 同源托管消除 CORS

Spring Boot 3.4 自动把 `classpath:/static/` 映射到 `/`。将前端构建产物同步到 `agent-platform-core/src/main/resources/static/` 后，页面与 API 同源（`http://localhost:8081/` 与 `/api/v1/**`），**完全规避 CORS 配置**。开发期则用 Vite 的 `server.proxy` 把 `/api`、`/actuator` 代理到 8081，同样无跨域。

### 4.3 认证与租户

仪表盘顶栏提供租户切换（写入 `X-Tenant-Id`）与可选登录（调 `/auth/login` 存 JWT 并附加 `Authorization: Bearer`）。v1 直连 core 时该头可选，未来切换网关时前端无需改动（相对路径 + 统一 `http` 封装）。

### 4.4 流式 SSE

`/agent/run` 支持 `stream:true` 返回 `text/event-stream`（事件 `run.delta`/`run.completed`/`run.error`）。浏览器原生 `EventSource` 不支持 POST，故用 `fetch` + `ReadableStream` 手写解析帧（约 40 行），**无额外依赖**即可实现打字机式流式展示。

### 4.5 字段命名契约（实现前提）

后端 JSON 存在命名混用，前端必须严格对齐（详见技术文档 §5.3 映射表）：强类型 `record`（智能体、运行、日志）为 **camelCase**；自由 `Map` 入参（登录、插件挂载、诊断、提示词）为 **snake_case**。这是本项目最大的「坑」，已在实现中逐字段核对。

## 5. 经济与运维可行性

- **成本**：零新增软件许可、零云服务；前端依赖均为开源（antd/zustand 等），本机 npm 即可安装；
- **构建**：新增 `agent-platform-ui/build-ui.bat`（双击：`npm install` → `npm run build:prod` → 同步产物），一次性构建后产物落盘，`start-core.bat` 无需改动即可服务仪表盘；
- **运维**：开发用 `npm run dev`（热更新 + 代理），生产用构建产物 + `java -jar`，两条路径清晰且互不干扰。

## 6. 方案对比

| 维度 | A：独立 Vite + 同步 static（**选定**） | B：Maven 子模块 + frontend-maven-plugin | C：独立 Node 服务分发 8082 |
|------|-----------------------------------|----------------------------------------|----------------------------|
| 前端构建 | npm 独立 | Maven 内联 Node 下载 | npm 独立 |
| 后端耦合 | 无（仅资源文件） | 较强（构建耦合） | 无 |
| CORS | 同源免 CORS | 同源免 CORS | **跨域，需后端加 CORS** |
| 一键启动 | 简单（一次构建 + start-core.bat） | 首跑要下载 Node | 需同时维护两个进程 |
| 维护成本 | 低 | 中 | 中 |

结论：**方案 A** 在本机无 Docker、追求「一键即用」的约束下最优。

## 7. 风险与对策

| # | 风险 | 影响 | 对策 |
|---|------|------|------|
| 1 | 字段命名混用（camel/snake） | 页面功能失效 | 前端集中维护 `api/types.ts` 契约，逐字段核对 |
| 2 | `/agent/run` 返回裸 JSON/SSE（不套 `ApiResponse`） | 通用解包失效 | 该接口走独立 raw 通道 |
| 3 | 同源直连 core 绕开网关 JWT | 多租户仅靠 `X-Tenant-Id`，生产不安全 | 文档标明生产应走网关；顶栏已预留登录/Token |
| 4 | 前端深链/刷新 404（无 SPA 回退） | 刷新丢页面 | 用 HashRouter（`#/...`）零后端改动规避 |
| 5 | antd 打包体积 ~374KB(gzip) | 首屏稍慢 | 内网工具可接受；后续可 `manualChunks` 拆包 |
| 6 | 无真实模型（Mock 兜底） | 对话为确定性示例 | 部署 LiteLLM 并将 `LLM_BASE_URL` 指向即可换真实模型，界面无需改 |
| 7 | 诊断/提示词优化依赖特定服务策略 | 部分交互无真实 AI 效果 | 规则策略（RULE）本地可用，接口已稳定 |

## 8. 结论与建议

1. **结论：可行。** 后端接口齐备、前端栈成熟、同源托管免 CORS、零后端改动即可上线，本机无 Docker 的约束下依然可完整演示全链路。
2. **建议方案**：采用「独立 Vite + 构建产物同步到 core `static/`」方案，分四域交付，预留 RAG/工作流/Skill/多模态/租户配额扩展点。
3. **建议下一步**：v1 上线后，按需接入真实模型（LiteLLM）与生产鉴权（网关 + 真 Token），并逐步补齐 RAG 等扩展域的可视化。