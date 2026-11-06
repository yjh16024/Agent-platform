# 工具与策略：治理、配额、鉴权、观测

**适用场景**：你想让智能体能调工具，并且要控制"谁能调、调多少、出问题怎么观测"。

框架的立场：**治理是宿主的选择，不是框架的前提**。所以五个 SPI 全部有默认实现，不接也能跑。

## 一、工具：写一个 `Tool`

```java
public class ClockTool implements Tool {

    @Override
    public String name() { return "clock"; }

    @Override
    public String description() { return "返回当前时间，格式 HH:mm"; }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        return ToolResult.ok(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
    }
}
```

Spring 环境下**声明 Bean 即可**，starter 会自动注册（`ObjectProvider<Tool>` 收集）。

### 工具目录：读与写要分开

| 类型 | 谁实现 | 用途 |
|---|---|---|
| `ToolCatalog`（只读） | 宿主 | 运行时只需要"有哪些、按名取一个" |
| `ToolRegistry`（可写） | 框架自带，或宿主自己维护 | 注册 / 反注册 / 热更新 / MCP 动态发现 |

平台侧用 `PlatformToolCatalog` 把既有注册中心适配成只读视图——**注册中心不需要实现框架接口**。

> ⚠️ 实现细节坑：`ToolRegistry implements ToolCatalog`，所以 `ObjectProvider<ToolCatalog>`
> 会把框架自己的注册表也当成"宿主提供的目录"。判断"外部目录"时必须排除框架自带类型，
> 否则表现为「模型说要调工具，但工具列表是空的」。

## 二、五环治理：实现 `ToolFilter`

```java
@Component
@Order(50)
public class MyAuthFilter implements ToolFilter {
    @Override
    public ToolResult doFilter(ToolInvocation invocation, ToolChain chain) {
        if (!allowed(invocation)) {
            return ToolResult.fail("无权限调用该工具");
        }
        return chain.apply(invocation);
    }
}
```

顺序建议（与平台一致）：**鉴权 → 参数校验 → 限流 → 审计 → 超时**。

两条约定：

1. **拒绝要返回 `ToolResult.fail(...)`，不要抛异常**。抛异常框架会兜住并回灌给模型，
   但错误码会变成通用值，前端无法区分"被限流"和"工具内部报错"。
2. **链尾才是真正执行**。过滤器负责"要不要放行、要不要改写"，不负责实现工具。

## 三、配额与鉴权

```java
// 每轮运行前占一次（默认 NOOP：不限额）
public interface QuotaPolicy { void reserve(String tenantId, String metric, long amount); }

// 服务层鉴权（默认 PERMISSIVE：放行）
public interface AuthorizationPolicy { void check(Principal principal, String action); }
```

要点：

- **配额是运营者的概念**，不是运行时前提。嵌入到别人系统里，凭什么替宿主限流？
- **鉴权必须 fail-closed**：启用鉴权却没给 `principal` 时直接拒绝。
  放过匿名请求等于鉴权形同虚设（平台侧 `PlatformAuthorizationPolicy` 就是这么做的）。
- 顺序是**先鉴权、后配额**：反了会"未授权的请求先消耗配额"。

## 四、观测

```java
// 默认 NOOP；starter 检测到 ObservationRegistry 时自动换成 Micrometer 实现
public interface RunObserver {
    default void runStarted(String runId, String traceId, String agentId, String tenantId) {}
    default void runCompleted(String runId, long elapsedMs, RunResult.Usage usage) {}
    default void runFailed(String runId, RuntimeException error) {}
}
```

- 观测名 `agent.run`，高基数标签 `agent.id` / `tenant.id` / `trace.id`，低基数只有 `outcome`。
  **别把高基数标签放 lowCardinality**，那会把指标维度打爆。
- 观测实现抛异常会被框架吞掉——观测失败不能让用户拿不到回复。

## 五、拦截器（比 Advisor 更靠上的一层）

`RunInterceptor` 管的是「一次运行」的四段：改写输入 / 短路 / 替换输出 / 兜底 + 产物上报。
与 Advisor 的分工：

| | `RunInterceptor` | `Advisor` |
|---|---|---|
| 作用范围 | 一次运行（含工具循环） | 一次模型调用 |
| 能短路模型调用 | ✅ | ❌（只能改请求） |
| 能带出产物 | ✅（`artifacts`） | ❌ |
| 依赖 Spring AI | ❌（纯框架契约） | ✅ |

## 参考与验证

| 主题 | 参考实现 | 测试 |
|---|---|---|
| 工具目录适配 | `PlatformToolCatalog` | `PlatformToolCatalogTest` |
| 五环 | `AuthToolFilter` 等五个 | `ToolFiltersTest` |
| 配额 | `PlatformQuotaPolicy` | `PlatformQuotaPolicyTest` |
| 鉴权 | `PlatformAuthorizationPolicy` | `PlatformAuthorizationPolicyTest` |
| 观测 | `MicrometerRunObserver` | `MicrometerRunObserverTest`、`ObservationTest` |
| 拦截器 | `PipelineRunInterceptor` | `PipelineRunInterceptorTest` |
