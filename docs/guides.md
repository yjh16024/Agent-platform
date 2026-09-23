# 使用与运维指南

> **职责**：怎么扩展、怎么部署、怎么演示、怎么观测。**不重复**已实现功能清单与技术设计
> （那两份属内部资料，不随本仓库发布）。
> 环境变量的**完整权威清单**在 [../README.md](../README.md) 的「配置」一节，本文只列部署相关补充。
> 最后核实：**2026-09-22**（09-22 更新钩子返回值表的"流式下"可用性一列）。

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

内置插件：实现 `ToolProvider` / `AgentHook`（或两者同时）并标 `@Component`。

- 内置插件启动时会由 `BuiltinPluginRegistrar` **自动同步进插件市场**（写入 `plugin_def`，`tenant_id=__platform__`），
  因此**能从界面直接挂载到智能体**，不需要 manifest 文件。
- 想让市场卡片显示得好看，再实现可选的 `PluginDescriptor`（声明 `name` / `description` / `author`）；
  不实现则展示名退化成插件 id。
- 该同步是**双向幂等**的：改了插件的名字/版本/贡献，重启即刷新；**删掉插件类则连带数据库记录与挂载绑定一起清理**，
  不会留下点了必然报错的僵尸条目。
- 注意 `ToolProvider.provideTools()` 会在启动时被调用以生成贡献清单，应当是**无副作用的纯声明**。

外部插件：改 `plugin-example/` 里的样板 → `mvn -pl plugin-example -am package` → 用
`plugin-example/src/main/resources/manifests/*.yaml` 作 manifest、`plugin-example-1.1.0.jar` 作制品，
走 `POST /api/v1/plugins/upload`（multipart：`manifest` 文本 + `jar` 文件），
再 `POST /api/v1/plugins/{id}/attach`（body `{"agent_id":"..."}`）挂到智能体上。

- **Hook 返回值约定（2026-09-22 补齐为 5 种可用语义）**：

  | 钩子点 | 返回 | 效果 | 流式下 |
  |---|---|---|---|
  | `before_llm` | `String` | **短路**：不调 LLM，直接作为最终回复 | ✅ |
  | `before_llm` | `Map{input}` | **改写输入**：改后文本传给 LLM，后续钩子看到的是新值 | ✅ |
  | `after_llm` | `Map` | **附加产物**（如 `audio_url`）。注意：**改不了回复正文** | ❌ |
  | `before_output` | `String` / `Map{output}` | **替换最终输出** —— 脱敏 / 合规改写 / 格式化的唯一落点 | ❌ |
  | `on_error` | `String` / `Map{reply}` | **兜底回复**：LLM 异常时用它收场、异常不再上抛；返回 `null` 则照常上抛 | ✅ |

  **⚠️ 最后一列是硬约束**：`after_llm` 与 `before_output` 在**流式**请求下不会执行 ——
  流式内容正在逐块推给前端，后端此时改写也改不动已经显示出去的文字。
  流式下确有输出治理需求时，请改用 `before_llm` **前置改写**（或要求走非流式）。
  宿主在流式请求上检测到这两个钩子会打 WARN，便于定位"本地测好、一开流式就失效"。

  `on_attach` / `on_detach` **不会触发**，生命周期回调请直接用 `Plugin.onAttach` / `onDetach`。
  Map 形式的键有容错：`input` 也接受 `message`/`prompt`，`output` 也接受 `reply`/`text`/`content`。
- **资源托管（`ResourceProvider`，2026-09-22 落地）**：插件用 `provideResources()` **纯声明**
  自己提供的外部依赖（密钥 / 连接 / 客户端，类型见 `ResourceTypes`），用 `provide(id)` 惰性给出实体；
  同一智能体下的其它插件通过 `ctx.registrar().resolveResource(id)` 取用。
  ⚠️ **必须在 `onAttach` 期间解析并缓存** —— 归属判定依赖 attach 作用域，工具执行期再解析拿不到；
  跨智能体不可见（挂到 A 的插件解析不到 B 的资源）。
  示例见 `plugin-example` 的 `PiiRedactionPlugin` / `GracefulFallbackPlugin` / `ResourceVaultPlugin`。
- **事件订阅（`EventSubscriber`，2026-09-22 落地）**：插件实现 `eventTypes()` 声明订阅哪些事件、
  实现 `onEvent(event)` 处理。与钩子的区别 —— **钩子拦管线的特定环节（能改写输入输出），
  事件订阅是"事已发生"的事后通知（改变不了已发生的事）**。

  可订阅的事件类型（见 `EventTypes`）：
  | 类型 | 含义 | 可被插件订阅 |
  |---|---|---|
  | `agent.run.completed` | 一次运行成功完成 | ✅ |
  | `agent.run.failed` | 一次运行失败 | ✅ |
  | `quota.exceeded` | 配额超限 | ❌ 租户级 |
  | `permission.denied` | 权限被拒 | ❌ 租户级 |

  ⚠️ **三条必须知道的约束**：
  1. **只能订阅 agent 级事件**。`quota.exceeded` / `permission.denied` 没有 agent 维度，
     派发给插件会**打破"插件按智能体隔离"**——那等于任意智能体上的插件都能监听全租户行为。
     声明了不可订阅的类型不会报错（否则插件作者会以为整个插件都挂了），但收不到，注册时记 warn。
  2. **只收到"挂载了本插件的那台智能体"的事件**：挂到 A 的插件收不到 B 的。
  3. **`onEvent` 在发布线程上同步调用**，别做重活（长耗时 HTTP / 大文件写入）；
     抛异常会被宿主捕获，不影响其它订阅者与主流程。

  ⚠️ **事件类型是插件契约**：一旦有插件订阅，**只增不改**；要改就新增类型、旧类型保留一段时间再废弃。
  `payload` 的 **key 同样是契约**，且应保持浅层（别塞实体对象）。

  示例见 `plugin-example` 的 `RunFailureAlertPlugin`（订阅运行失败做告警，并用一个工具让效果可验证）。
- **钩子与插件工具在流式链路上也已生效（2026-09-22 起）**：`runStream()` 此前只做裸模型调用，
  导致「一开流式开关，工具与插件双双静默失效」；现已与非流式对齐。
  **但流式下可用范围更小**：只有 `before_llm`（短路/改写）与 `on_error`（兜底）会触发，
  `after_llm` / `before_output` 刻意不生效（见上方返回值表的"流式下"一列）。
- 插件能力**按智能体隔离**：挂到 A 的插件只影响 A，不会波及其它智能体。
- 卸载是**级联**的：该插件在所有智能体上的挂载会被一并取消（界面会先提示影响面）。
- 插件经 `PluginClassLoader` 做类隔离，但 **`permissions` / `runtime` 目前仅是 manifest 里的声明字段，
  宿主未做任何权限校验** —— 外部插件代码与宿主同进程、同权限运行，只应加载可信 jar。

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

### 2.6 账号与权限（RBAC）

**首次启动**：当某租户下**一个用户都没有**时，`RbacSeeder` 会同步内置角色（`admin` / `operator` / `viewer`）
与 23 个权限点，并创建初始管理员 `admin`。密码来源见下 —— **桌面版把它写进了
`application-embedded.yml`**（`admin123456`），因为桌面用户看不到启动日志。

```bash
RBAC_ADMIN_PASSWORD=<初始密码>        # 仅"该租户下无任何用户"时生效，之后每次启动都跳过

RBAC_ADMIN_RESET_PASSWORD=<新密码>    # 已存在 admin 时强制重置密码（忘记密码用）
                                      # ⚠️ 用完必须移除，否则每次启动都会把它重置回同一个值
                                      #    用户在界面上改的新密码会被覆盖掉
```

| 开关 | 默认 | 说明 |
|---|---|---|
| `SECURITY_ENABLED` | `true` | core 侧是否校验 Bearer token |
| `RBAC_ENABLED` | `true` | 是否做角色→权限点授权校验（`@RequiresPermission`） |

两者**互相独立**：`SECURITY_ENABLED=true` + `RBAC_ENABLED=false` 表示"要登录，但不做细粒度授权"
（此时所有接口"登录即可访问"）。

**权限模型**：角色 → 权限点。权限码的唯一权威来源是 `RbacPermission` 枚举，
启动时同步进 `sys_permission` 表（所以界面看到的与库里生效的完全一致）。

**token 里只放角色码**，权限由服务端按角色推导并缓存 —— 因此：
**改「角色的权限」即时生效；改「用户的角色」需要重新登录**（界面上有提示）。

**内置角色**：`admin`（全部）/ `operator`（业务读写，不含系统管理）/ `viewer`（只读）。

**三条防自锁规则**（很久不碰管理界面时最容易踩）：
不能停用 / 删除 / 摘掉**最后一个启用状态的管理员**、不能删除自己、内置角色与被引用的角色不可删。
这些事故只能手工改数据库救回来，所以宁可在这里挡住。

> ⚠️ **往 `RbacPermission` 新增权限点之后**：Seeder 会把它补进内置角色（幂等补齐），
> 但**界面上被手工删掉过的权限不会自动恢复**。新增受管控的接口后，
> 请确认 `admin` 仍持有新权限码，否则会出现"管理员被自己的系统拒绝"。

### 2.7 弹性

| 层级 | 指标 | 策略 |
|---|---|---|
| agent-core | CPU / 内存 / Kafka 堆积 | HPA + KEDA（堆积 >1000 扩容） |
| 诊断/优化 Worker | 队列长度 | KEDA 自定义指标 |

### 2.8 桌面分发（Windows 绿色版）

```bat
desktop\build.bat          REM 一键全流程：前端构建 → jlink JRE → 后端 jar（带 clean）→ electron-builder（dir）→ 改名 dist\green
desktop\build.bat fast     REM 快速：跳过前端重建 + 后端增量构建（未删/改名源文件时可用）
desktop\build.bat no-ui    REM 只重建后端与桌面壳，沿用当前前端产物
```

**为什么默认带 `clean`**：Maven 从不清除陈旧产物，而"跳过 clean 以保留增量编译"的代价是 ——
**被删除或改名的 Java 源文件会留下旧 `.class` 一起打进 jar，导致已删掉的功能在运行时"复活"**
（曾把三个已删除的内置插件重新注册回 `plugin_def`）。clean 约多花一分钟，但消灭这一整类问题；
确认只改了文件内容、没动文件结构时再用 `fast`。

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

### 2.8 桌面版开发模式（改代码免重打包）

日常改代码不要在 `desktop\build.bat` 上耗时间（jlink + 打包 + electron-builder 是分钟级）。改用：

```bat
desktop\dev.bat
```

它让打包好的绿色版指向**仓库里新构建的产物**（通过环境变量 `AP_DEV_JAR` / `AP_DEV_STATIC`），于是：

| 改了什么 | 你要执行的 | 然后在应用窗口里 |
|---|---|---|
| 前端（`agent-platform-ui`） | `cd agent-platform-ui && npm run build:prod` | 按 **Ctrl+R** 刷新 —— **后端不用重启** |
| 后端（Java） | `mvn -pl agent-platform-core -am package -DskipTests` | 按 **Ctrl+Shift+R** 热重启后端（窗口不关） |
| 桌面壳本身（`desktop/main.js`） | `desktop\build.bat`（一键全流程，已含前端构建，见 §2.7） | 重启应用 |

说明与开关：

- `AP_DEV=1` 是总开关；**不设置时行为与普通绿色版完全一致**（仍使用打包内的 jar 与静态资源）。
- `AP_DEV_STATIC` 会让 Spring 用源码目录**完全替换** jar 内的 `classpath:/static/`，
  所以前端产物改完刷新页面即生效 —— 这正是"改界面不用重启后端"的原因。
- `AP_REUSE_BACKEND=1`：若起始端口上已有就绪的后端（例如你自己 `mvn spring-boot:run`），
  桌面版直接复用、不再另起一个；关掉桌面版也不会连带关掉你的后端。
  配合 `spring-boot-devtools` 可把后端改动压到秒级（项目当前未引入该依赖）。
- **注意**：Java 代码改动必须重启 JVM 才能生效，没有"改完立刻生效"的方案；
  开发模式省掉的是"重打包 + 重启整个桌面程序"，而不是"重启进程"这件事本身。

## 三、端到端演示用例

前置：数据库二选一 —— `docker compose up -d`（MySQL）或 `--spring.profiles.active=embedded`（H2，免装库）；
其余中间件可选（缺失自动降级）→ `mvn -pl agent-platform-core -am package -DskipTests` →
`java --enable-preview -jar <jar>`（或 `start-core.bat rebuild` / `start-core.bat embedded rebuild`）。

| 步骤 | 命令要点 | 预期 |
|---|---|---|
| ① 创建智能体 | `POST /api/v1/agents`（带 `X-Tenant-Id`） | 返回 `agent_id`、`status=draft` |
| ② 挂载插件 | 按 1.3 节构建并上传 `plugin-example` 的 manifest + jar，再 attach | `status=attached` |
| ③ 运行对话 | `POST /api/v1/agent/run`，问「你们营业时间是几点？」 | 命中关键词 → **短路回复（不调 LLM）** |
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
