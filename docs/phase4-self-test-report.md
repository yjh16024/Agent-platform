# Phase 4 · 插件系统 — 阶段自测报告

## 交付物清单

| # | 交付物 | 状态 | 说明 |
|---|--------|------|------|
| 1 | plugin-sdk SPI | ✅ | Plugin / ToolProvider / AgentHook / ResourceProvider / PluginContext / HookPoint / PluginTool / PluginManifest |
| 2 | ClassLoader 隔离 | ✅ | `PluginClassLoader`（核心类委派 + 导出包白名单 + 私有类隔离） |
| 3 | 插件运行时 | ✅ | `PluginRuntime`（attach/detach 幂等 + 生命周期） |
| 4 | 扩展注册表 | ✅ | `ExtensionRegistry`（hooks 按 HookPoint + 插件工具同步进 ToolRegistry） |
| 5 | Hook 管线织入 | ✅ | `AgentPipeline`（before_llm → LLM → after_llm，装饰器+责任链） |
| 6 | Agent 集成 | ✅ | `AgentRuntimeService.run()` 经管线执行 + 插件热挂载 |
| 7 | 插件市场 API | ✅ | `PluginService`/`Controller`（register/marketplace/attach/detach） |
| 8 | 示例插件 | ✅ | `AutoReplyPlugin`（before_llm 短路）+ `TtsPlugin`（after_llm + 工具） |
| 9 | 内置插件种子 | ✅ | `BuiltinPluginSeeder`（启动注册到 plugin_def） |
| 10 | 数据库迁移 | ✅ | `V4__plugin_system.sql`（plugin_def/plugin_version/agent_plugin） |
| 11 | 单元测试 | ✅ | 11 个新测试，总计 63 个全部通过 |

## 测试结果

```
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  Phase 4 新增:
    AgentPipelineTest        : 5  ✅  (自动回复短路/未命中调LLM/TTS注入/工具贡献/注册)
    PluginRuntimeTest        : 4  ✅  (attach/detach/幂等/异常)
    PluginManifestLoaderTest : 2  ✅  (YAML解析/缺ID)
```

## 关键设计决策

1. **示例插件编译进 core 模块**：`AutoReplyPlugin`/`TtsPlugin` 作为 Spring Bean（`List<Plugin>` 注入），
   完整演示 Hook 管线 + 工具贡献，无需外部 jar 编译即可端到端运行。生产环境插件为独立 jar，
   经 `PluginClassLoader`（已实现）加载。

2. **Hook 返回约定**（简化而明确）：
   - `before_llm` 返回 String → 短路（自动回复）；null → 透传
   - `after_llm` 返回 Map → 合并附加产物（如 audio_url）；null → 无附加

3. **attach 幂等**：`PluginRuntime.attach` 已 attach 则跳过，避免 Hook 重复注册（修正了早期重复
   注册导致的 Hook 列表膨胀问题）。

4. **plugin-sdk ↔ core 桥接**：`PluginToolAdapter` 将插件贡献的 `PluginTool`（sdk）适配为核心
   `Tool`，同步注册进 ToolRegistry 供 LLM function calling 触发，避免 sdk 反向依赖 core。

5. **修复 Javadoc `*/` 闭合问题**：`PluginClassLoader` 注释中 `java.*/javax.*` 的 `*/` 序列
   提前闭合了 Javadoc，改用「与」连接词规避。

## 未解决/已知风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| Hook 全局生效（非 per-agent 隔离） | 插件挂载到任意 Agent 后对全部 Agent 生效 | Phase 5 引入 per-agent 插件上下文 |
| 外部 jar 插件未端到端联调 | 仅内置插件验证 | `PluginClassLoader` 已实现，需真实 jar 联调 |
| 插件安全沙箱（权限白名单/独立容器）未落地 | 高风险插件无隔离 | Phase 5 接 GraalVM/容器沙箱 |
| 无依赖 SemVer 解析 | 插件间版本冲突未校验 | Phase 5 接依赖图 Device |

## 端到端演示（创建 Agent → 挂载插件 → 运行）

```bash
# 1. 创建 Agent
curl -X POST http://localhost:8081/api/v1/agents -H "X-Tenant-Id: t1" \
  -H "Content-Type: application/json" \
  -d '{"name":"客服","persona":{"role":"客服"},"system_prompt":"你是客服"}'

# 2. 挂载自动回复 + TTS 插件
curl -X POST http://localhost:8081/api/v1/plugins/plugin_auto_reply/attach \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"agent_id":"agent_xxx"}'
curl -X POST http://localhost:8081/api/v1/plugins/plugin_tts_azure/attach \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"agent_id":"agent_xxx","config":{"voice":"zh-CN-Xiaoxiao"}}'

# 3. 运行（命中「营业时间」→ 自动回复短路；否则正常回复 + audio_url）
curl -X POST http://localhost:8081/api/v1/agent/run -H "Content-Type: application/json" \
  -d '{"agent_id":"agent_xxx","messages":[{"role":"user","content":"你们营业时间？"}],"metadata":{"tenant_id":"t1"}}'
```