# 使用与运维指南

> **职责**：怎么扩展、怎么部署、怎么演示、怎么观测。**不重复**已实现功能清单
> （见 [status.md](status.md)）与技术设计（见 [design.md](design.md)）。
> 环境变量的**完整权威清单**在 [../README.md](../README.md) 的「配置」一节，本文只列部署相关补充。
> 最后核实：**2026-09-18**。

---

## 一、扩展指南：如何新增能力

> 全平台能力均通过「SPI / 注册表 / 策略链」接入，新增实现 = 新增 Bean，核心代码趋近零修改。

| 扩展点 | 模式 | 接口 |
|---|---|---|
| 模型 | 策略 + 工厂 + 路由 | `ModelAdapter` |
| 工具 | 注册表 + 责任链 | `Tool` |
| MCP 传输 | 接口 + 多实现 + 工厂 | `McpClient` |
| Skill 执行 | 命令模式 + 注册表 | `SkillExecutor` |
| 切分算法 | 模板方法 + 策略 | `Chunker` |
| 节点执行器 | 策略分发 | `NodeExecutor` |
| 插件 | SPI + ClassLoader 隔离 | `Plugin` / `ToolProvider` / `AgentHook` |
| 诊断 | 策略 + 责任链 | `DiagnosisStrategy` |
| 提示词优化 | 策略链 + 组合 | `PromptEnhancer` |
| 日志外推 | Sink 派发 | `LogEventSink` |

### 1.1 新增模型

- **方式 A（推荐，零代码）**：在 `litellm-config.yaml` 的 `model_list` 增加模型，`ModelProviderFactory` 归一化后经 `OpenAiCompatibleAdapter` 以 OpenAI 兼容协议对接。
- **例外**：`anthropic` 走 `AnthropicAdapter`（Messages API）；`local`/`mock` 走 `MockModelAdapter`；`rule`/`rule-engine` 走 `RuleEngineModelAdapter`（正则规则确定性应答，零成本，**不参与 `auto` 路由**）。
- **方式 B（自定义适配器）**：实现 `ModelAdapter`（`provider()` / `capabilities()` / `chat()` / `stream()`），并把 provider 名加入 `ModelProviderFactory.SUPPORTED_PROVIDERS` 与 `normalize()`。
- 要点：`ModelCapability`（TEXT/VISION/AUDIO）决定多模态路由，`costWeight()` 决定成本路由，`isHealthy()` 决定降级。

### 1.2 新增工具

```java
@Component           // Bean 名 = 工具名
public class WeatherTool implements Tool {
    @Override public String name() { return "weather"; }
    @Override public String description() { return "查询指定城市天气"; }
    @Override public ToolResult execute(JsonNode args, ToolContext ctx) { /* ... */ }
}
```

启动时 `ToolRegistrationConfig` 自动收集注册，LLM 经 function calling 触发。

- **HTTP 工具**（动态注册，免重启）：`POST /api/v1/tools/register`，body `{"name":"get_weather","endpoint":"...","method":"GET"}`。
- **MCP 工具**（三种传输，均经 `McpClientFactory`）：
  - 远程 HTTP：`POST /api/v1/tools/mcp`（`server_url` 必填，`api_key`/`headers` 可选）→ `HttpMcpClient`
  - 本地进程内：`POST /api/v1/tools/mcp/local`（`dir` 可选）→ `LocalMcpClient`（echo / time / read_file）
  - 沙箱子进程：`POST /api/v1/tools/mcp/sandbox`（`dir`、`timeout_seconds` 可选）→ `SandboxMcpClient`（目录隔离 + 解释器白名单 + 超时 + 输出截断）
  - 卸载：`DELETE /api/v1/tools/{toolName}`。同名覆盖即热更新。

### 1.3 新增插件

实现 `ToolProvider` / `AgentHook` 并注册为 Bean（内置）或提供 `plugin.yaml` + 独立 jar（`POST /api/v1/plugins/register`），挂载 `POST /api/v1/plugins/{id}/attach`（body `{"agent_id":"..."}`）。

- Hook 约定：`before_llm` 返回 String = 短路；`after_llm` 返回 Map = 附加产物。
- 插件经 `PluginClassLoader` 类隔离，`permissions` 白名单限网络/文件。

### 1.4 新增诊断规则

- 代码：在 `BuiltinDiagnosisRules` 注册 `ErrorRule(fingerprint, category, severity, rootCause, ...)`，需与 `FingerprintGenerator` 生成的指纹一致。
- 数据库：直接 `INSERT INTO diagnosis_rule`（无需改代码）。
- 自定义策略：实现 `DiagnosisStrategy`，Spring 自动收集进 `DiagnosisEngine`。

### 1.5 新增提示词优化策略

实现 `PromptEnhancer`（`supports()` / `order()` / `enhance()`）并注册为 Bean，`PromptOptimizer` 自动按 `order()` 排序进链。

### 1.6 新增 Skill 执行器

实现 `SkillExecutor`（`type()` / `description()` / `supports()` / `execute()`）并注册为 Bean，`SkillExecutorRegistry` 自动收集；调用入口 `POST /api/v1/skills/{skillId}/execute`（body：`type`、`command`、`args`），可用执行器列表 `GET /api/v1/skills/executors`。
内置三种：`prompt`（渲染 `{{var}}` 模板）、`script`（执行 `scripts/` 下脚本，子进程 + 白名单 + 超时）、`http`（POST 到端点）。

---

## 二、部署

### 2.1 构建镜像

```bash
docker build -f agent-platform-deploy/docker/Dockerfile -t agent-platform/agent-platform-core:1.1.0 .
docker push agent-platform/agent-platform-core:1.1.0
```

### 2.2 K8s / Helm

```bash
kubectl apply -k agent-platform-deploy/k8s/base            # kustomize（开发）
kubectl apply -k agent-platform-deploy/k8s/overlays/prod   # 生产 overlay

helm template agent-platform agent-platform-deploy/helm/agent-platform --debug
helm install agent-platform agent-platform-deploy/helm/agent-platform \
  --set services.mysql.host=mysql.prod.svc.cluster.local \
  --set secrets.jwtSecret="$(openssl rand -hex 32)" \
  --set core.image.tag=1.1.0
```

### 2.3 环境变量（core）

> **完整清单与默认值见 [../README.md](../README.md) 的「配置」一节**（单一来源，避免两处口径不一致）。
> 部署时额外需要关注的覆盖项如下：

| 变量 | 部署关注点 |
|---|---|
| `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` | 指向集群内 MySQL；单机分发可改用 `--spring.profiles.active=embedded`（H2，免装库） |
| `REDIS_HOST` / `REDIS_PORT` | 生产建议接集群 Redis（会话缓存与配额计数）；缺失时走内存，重启丢计数 |
| `MILVUS_URL` | 需与 `VECTOR_STORE=milvus` 配套；切换嵌入模型后旧向量需重新摄取 |
| `JWT_SECRET` / `MODEL_KEY_ENC_KEY` | **生产必须覆盖**；换主密钥会导致已存的模型 Key 与 JWT 失效 |
| `SECURITY_ENABLED` | 生产置 `true`；仅本地/内网演示可保持 `false` |
| `OBS_LOKI_URL` / `OBS_TEMPO_URL` | 空 = exporter 完全静默；接观测栈时填 Loki/Tempo 地址 |
| `OBS_EXPORT_ENABLED` / `OBS_METRICS_ENABLED` | 日志/链路外推总开关、业务指标开关 |

### 2.4 JVM 参数

```bash
-XX:+UseZGC -XX:+ZGenerational
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75
--enable-preview                             # JDK 21 预览特性（ScopedValue / StructuredTaskScope）
--add-opens java.base/java.lang=ALL-UNNAMED  # 插件 SPI 反射
```

### 2.5 安全加固

- ✅ 非 root 运行、`runAsNonRoot` + `fsGroup`、NetworkPolicy 最小权限、JWT 无状态鉴权、ResourceQuota/LimitRange。
- 🔶 生产建议：Istio mTLS、cert-manager、Vault 管密钥、镜像签名（cosign）。
- ⚠️ 默认 `JWT_SECRET` / `MODEL_KEY_ENC_KEY` 为 `change-me-*`，`SECURITY_ENABLED=true` 且未覆盖时**启动守卫会拒绝启动**。

### 2.6 弹性

| 层级 | 指标 | 策略 |
|---|---|---|
| agent-core | CPU / 内存 / Kafka 堆积 | HPA + KEDA（堆积 >1000 扩容） |
| 诊断/优化 Worker | 队列长度 | KEDA 自定义指标 |

### 2.7 桌面分发（Windows 绿色版）

```bat
desktop\build.bat    REM 一键：jlink JRE → 后端 jar → electron-builder（dir，不归档）→ 改名 dist\green
```

> **这是推荐的日常运行方式**：自带 jlink 精简 JRE 与内嵌 H2，免装 JDK / MySQL，双击即用；
> 从源码启动（`start-core.bat`）仅用于开发联调、跑测试与服务器部署。

- **分发形态只有绿色版**：把 `desktop\dist\green\` 整个目录交给用户，双击其中的 `Agent Platform.exe` 即可。
- **构建目标用 `dir` 而不是 `zip`**：electron-builder 恒为「先铺出 `win-unpacked` 再归档」，
  所以任何归档型 target 都会额外留下一个解包副本，且 7za 以 `-mx=7` 压约 600MB 是整条链最慢的一步。
  改用 `dir` 后由构建脚本把 `win-unpacked` **改名**为 `dist/green`（同盘 rename，瞬间），
  实测打包阶段 **75.9s → 7.2s**。需要对外压缩包时手工压一次：
  `Compress-Archive -Path dist\green -DestinationPath dist\green.zip`。
- **构建前必须先关闭应用**：`dist\green` 在应用运行时被占用，脚本会检测到并直接报错退出。
- **不要用 portable 单文件**：它每次运行都要把约 640MB 解压到临时目录，冷启动要多等 20–30s（期间无窗口）。
- 运行时表现：**界面 1–2s 弹出**、后端约 13s 就绪；数据落在 `%APPDATA%\Agent Platform\data`
  （与源码方式的 `./data` **互相独立**，切换运行方式等于换库），日志在 `%APPDATA%\Agent Platform\app.log`。
- 桌面包以 `embedded`（H2）profile 运行，**免装 MySQL / Redis**。

---

## 三、端到端演示用例

前置：数据库二选一 —— `docker compose up -d`（MySQL）或 `--spring.profiles.active=embedded`（H2，免装库）；
其余中间件可选（缺失自动降级）→ `mvn -pl agent-platform-core -am package -DskipTests` →
`java --enable-preview -jar <jar>`（或 `start-core.bat rebuild` / `start-core.bat embedded rebuild`）。

| 步骤 | 命令要点 | 预期 |
|---|---|---|
| ① 创建智能体 | `POST /api/v1/agents`（带 `X-Tenant-Id`） | 返回 `agent_id`、`status=draft` |
| ② 挂载插件 | `POST /api/v1/plugins/plugin_auto_reply/attach`、`plugin_tts_azure/attach` | `status=attached` |
| ③ 运行对话 | `POST /api/v1/agent/run`，问「你们营业时间是几点？」 | 命中关键词 → **自动回复短路（不调 LLM）** + `audio_url` |
| ④ 查看日志 | `GET /api/v1/logs?level=INFO` | 结构化日志（`trace_id`/`run_id`/`category`/`fingerprint`），已落 MySQL |
| ⑤ 触发诊断 | 先 `POST /api/v1/logs` 上报错误，再 `POST /api/v1/diagnosis/analyze` | `source=RULE` + 根因 + 解决方案 |
| ⑥ 优化提示词 | `POST /api/v1/prompt/optimize` | 补全「## 角色/任务/约束/输出格式/示例」+ 评分提升 + Diff |

> 完整可执行脚本见 `docs/demo-script.sh`。

---

## 四、可观测性运维（阶段 B/C）

### 4.1 启动观测栈

```bash
docker compose -f docker-compose.observability.yml up -d
# Grafana http://localhost:3000 (admin/admin) · Prometheus 9090 · Loki 3100 · Tempo 3200
```

> 需先启动 Docker Desktop（默认不自启）。

### 4.2 数据来源

| 信号 | 链路 | 说明 |
|---|---|---|
| 指标 | Micrometer → `/actuator/prometheus` → Prometheus | 默认开启 |
| 日志 | `LogService` → `LogEventSink` → `LokiLogExporter` | JSON push `/loki/api/v1/push`，需配 `OBS_LOKI_URL` |
| Trace | `LogService` → `TempoSpanExporter` | 由 run/llm/tool 标记日志合成 `agent.run → llm.chat / tool.call`，Zipkin v2 推 Tempo，需配 `OBS_TEMPO_URL` |
| 看板 | Grafana provisioning | 3 数据源联动（日志行 `trace_id` 可跳 Trace）+ 统一看板 + 3 条告警规则 |

### 4.3 业务指标清单

- `agent_run_total{status=ok|failed|aborted, agent}`
- `agent_run_latency_seconds{status, agent}`
- `agent_llm_calls_total{provider, model}`、`agent_llm_tokens_total{provider, model}`
- `agent_llm_latency_seconds_sum/count/max{provider, model}`
- `agent_tool_calls_total{name, success}`
- `log_events_total{level, category}`

### 4.4 告警阈值（已预置）

| 规则 | 条件 | 持续 |
|---|---|---|
| Agent 运行错误率过高 | 5m 失败率 > 10% | 5m |
| LLM 平均延迟过高 | 5m 平均延迟 > 15000ms | 5m |
| 持续出现运行失败 | 5m 失败速率 > 0 | 5m |

### 4.5 零配置时的行为

未配置 `OBS_LOKI_URL` / `OBS_TEMPO_URL` 时，两个 exporter **完全静默**（不启动线程、不发包），业务指标仍照常输出到 `/actuator/prometheus`。
