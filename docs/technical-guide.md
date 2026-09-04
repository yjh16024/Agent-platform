# 技术文档 · 扩展指南

> 本平台所有能力均通过「SPI / 注册表 / 策略链」接入，新增能力**零核心代码修改**。
> 本文档说明如何添加：新模型、新工具、新插件、新诊断规则、新提示词优化策略。

---

## 1. 添加新模型

模型接入走 **ModelAdapter 策略 + 工厂 + 路由**（见 `core/model/adapter`）。

### 方式 A：走 LiteLLM 网关（推荐，零代码）

LiteLLM 已统一 100+ 模型，只需在 `litellm-config.yaml` 添加模型：

```yaml
model_list:
  - model_name: qwen-max
    litellm_params:
      model: openai/qwen-max
      api_base: https://dashscope.aliyuncs.com/compatible-mode/v1
      api_key: os.environ/QWEN_API_KEY
```

Java 侧无需改动——`ModelProviderFactory` 归一化 provider 后经 `OpenAiCompatibleAdapter`
以 OpenAI 兼容协议对接网关。

### 方式 B：新增自定义适配器（深度定制）

```java
// core/model/adapter/MyProviderAdapter.java
@Component  // 或由工厂手动装配
public class MyProviderAdapter implements ModelAdapter {
    @Override public String provider() { return "myprovider"; }
    @Override public Set<ModelCapability> capabilities() {
        return Set.of(ModelCapability.TEXT, ModelCapability.VISION);
    }
    @Override public ChatResponse chat(ChatRequest request) {
        // 对接厂商 SDK/协议
        return new ChatResponse("...", 100, 50, 0.01, 30L);
    }
    @Override public Flux<ChatDelta> stream(ChatRequest request) { /* ... */ }
}
```

然后在 `ModelProviderFactory` 注册该 provider 名：

```java
public static final String[] SUPPORTED_PROVIDERS = { "auto", "openai", "myprovider", ... };
```

**要点**：`ModelCapability` 标签（TEXT/VISION/AUDIO）决定多模态路由，`costWeight()`
决定成本路由，`isHealthy()` 决定降级。

---

## 2. 添加新工具

工具接入走 **Tool 接口 + ToolRegistry 注册表 + 责任链执行**（见 `core/tool`）。

```java
// core/tool/builtin/WeatherTool.java
@Component("weather")                       // Bean 名 = 工具名
public class WeatherTool implements Tool {
    @Override public String name() { return "weather"; }
    @Override public String description() { return "查询指定城市天气"; }
    @Override public ToolResult execute(JsonNode args, ToolContext ctx) {
        String city = args.path("city").asText();
        return ToolResult.ok("{\"city\":\"" + city + "\",\"temp\":25}");
    }
}
```

启动时 `ToolRegistrationConfig` 自动收集所有 `Tool` Bean 注册进 `ToolRegistry`，
LLM 即可经 function calling 触发。**零其他改动**。

**HTTP 工具**（动态注册，不需重启）：`POST /api/v1/tools/register`
```json
{"name":"get_weather","endpoint":"https://api.weather.com","method":"GET"}
```

---

## 3. 添加新插件

插件接入走 **plugin-sdk SPI + PluginRuntime + ExtensionRegistry**（见 `core/plugin`）。

### 内置插件（核心模块内，重启生效）

```java
@Component
public class MyEmailPlugin implements ToolProvider, AgentHook {
    @Override public String id() { return "plugin_email"; }
    @Override public String version() { return "1.0.0"; }
    @Override public void onAttach(PluginContext ctx) { /* 初始化 */ }
    @Override public void onDetach(PluginContext ctx) { /* 清理 */ }

    // 工具贡献
    @Override public List<PluginTool> provideTools() {
        return List.of(PluginTool.of("send_email", "发送邮件", (args, ctx) -> {
            // 执行逻辑，返回 JsonNode
            return JsonNodeFactory.instance.objectNode().put("ok", true);
        }));
    }
    // 钩子贡献
    @Override public HookPoint point() { return HookPoint.after_llm; }
    @Override public Object invoke(HookContext ctx) {
        return Map.of("email_sent", true);   // 附加产物
    }
}
```

### 插件 Manifest（plugin.yaml · 独立 jar 插件）

```yaml
plugin:
  id: plugin_email
  name: 邮件发送
  version: 1.0.0
entry:
  type: java
  main_class: com.acme.EmailPlugin
contributes:
  tools:
    - name: send_email
  hooks:
    - point: after_llm
      handler: com.acme.EmailHook
permissions:
  network: ["smtp.example.com"]
```

上传注册：`POST /api/v1/plugins/register`（body = manifest 文本）。
插入智能体：`POST /api/v1/plugins/plugin_email/attach`（body `{"agent_id":"agent_xxx"}`）。

**要点**：插件经 `PluginClassLoader` 独立加载（类隔离），`permissions` 白名单限网络/文件，
Hook 返回约定（before_llm 返回 String=短路，after_llm 返回 Map=附加产物）。

---

## 4. 添加新诊断规则

诊断接入走 **DiagnosisStrategy 三级策略**（见 `core/diagnosis`）。规则只需注册进
`BuiltinDiagnosisRules`。

### 方式 A：内置规则（代码）

```java
// 在 BuiltinDiagnosisRules 构造函数追加
register(new ErrorRule(
    "weather#timeout",                    // 错误指纹（FingerprintGenerator 生成）
    "weather", ErrorSeverity.MAJOR,
    "第三方天气服务超时",
    "外部 API 响应超过阈值",
    List.of(ErrorRule.manual("检查天气服务健康状态", "访问其 /health", 0.9),
            ErrorRule.manual("增加重试+退避", "改为指数退避重试", 0.8)),
    0.9));
```

**要点**：错误指纹生成规则在 `FingerprintGenerator.generate()`，需确认新类别能生成
对应的 fingerprint；也可自定义 `:category#xxx`。

### 方式 B：数据库规则（无需改代码，`diagnosis_rule` 表）

```sql
INSERT INTO diagnosis_rule (rule_id, fingerprint, category, root_cause, solutions, confidence)
VALUES ('weather_timeout', 'weather#timeout', 'weather',
        '第三方天气服务超时',
        '[{"title":"检查服务","type":"MANUAL","confidence":0.9}]',
        0.9);
```

### 诊断策略自定义（替换/新增一级）

```java
@Component
public class CustomDiagnosis implements DiagnosisStrategy {
    @Override public boolean supports(DiagnosisContext ctx) { return /* 判断 */; }
    @Override public DiagnosticReport analyze(DiagnosisContext ctx) { return /* 报告 */; }
}
```

Spring 自动收集进 `DiagnosisEngine` 责任链 / 结构化并发扇出。

---

## 5. 添加新提示词优化策略

优化接入走 **PromptEnhancer 策略链**（见 `core/prompt/enhancer`）。

```java
@Component
public class StyleEnhancer implements PromptEnhancer {
    @Override public boolean supports(PromptAnalysis analysis, EnhanceContext ctx) {
        return ctx.persona() != null && ctx.persona().tone() != null && !analysis.rawPrompt().contains("语气");
    }
    @Override public int order() { return 7; }   // 插入到 CoT 之后
    @Override public DiffEntry enhance(PromptAnalysis analysis, EnhanceContext ctx) {
        String section = "## 语气\n回复语气请保持" + ctx.persona().tone() + "。\n";
        return new DiffEntry(DiffType.added, "## 语气", null, section);
    }
}
```

零其他改动——`PromptOptimizer` 自动按 `order()` 排序进策略链。

**评分维度扩展**：`QualityScorer.score()` 增加维度，`ScoreReport` record 增字段即可
（密封/Record 编译器强制更新所有 switch 分支）。

---

## 通用扩展原则（贯穿全平台）

| 扩展点 | 模式 | 接口 |
|--------|------|------|
| 模型 | 策略 + 工厂 + 路由 | `ModelAdapter` |
| 工具 | 注册表 + 责任链 | `Tool` |
| 切分算法 | 模板方法 + 策略 | `Chunker` |
| 节点执行器 | 策略分发 | `NodeExecutor` |
| 插件 | SPI + ClassLoader 隔离 | `Plugin`/`ToolProvider`/`AgentHook` |
| 诊断 | 策略 + 责任链 | `DiagnosisStrategy` |
| 优化 | 策略链 + 组合 | `PromptEnhancer` |

**核心机制**：Spring 的 `List<Interface>` / `Map<String, Interface>` 自动收集机制——
新增实现 = 新增 Bean，核心代码趋近零修改，落实开闭原则。