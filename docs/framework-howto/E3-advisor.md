# E3：拦截与注入（Advisor）

**适用场景**：你想在模型调用前后做点事——限制提示词长度、注入用户画像、改写输出、上报产物。

## 最小代码

实现 Spring AI 的 `CallAdvisor`（流式再加 `StreamAdvisor`）：

```java
public class MyAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 1) 改请求（如需）
        ChatClientRequest modified = request.mutate()
                .prompt(request.prompt().augmentSystemMessage("追加一段"))
                .build();

        // 2) 交给链上的下一个
        return chain.nextCall(modified);
    }

    @Override
    public String getName() {
        return "MyAdvisor";
    }

    @Override
    public int getOrder() {
        return AdvisorOrders.MEMORY;   // 别写字面量，用常量
    }
}
```

## 必须注意的三条

### 1. ⚠️ `augmentSystemMessage` 是「替换」语义，不是「追加」

这是本项目踩过的最贵的坑（已用单测钉住）：

```java
// 期望：在人格后面加一段
// 实际：system 消息只剩你传进去的那段，人格被整段覆盖
prompt.augmentSystemMessage(block);
```

实测 2.0.1 的实现是把原 system 消息**替换**成新的一条。所以必须自己拼：

```java
String existing = request.prompt().getSystemMessage() == null
        ? "" : request.prompt().getSystemMessage().getText();
String merged = existing.isBlank() ? block : existing + "\n\n" + block;
request.prompt().augmentSystemMessage(merged);
```

**症状**：模型突然"不听话"了，人格设定失效——而日志里看不出任何异常。

### 2. Order 不是随便填的，顺序本身就是语义

框架把顺序常量收在 `AdvisorOrders`：

| 常量 | 值 | 谁用 | 为什么在这个位置 |
|---|---|---|---|
| `GUARD` | 50 | 提示词长度守卫 | 必须最早：先看预算，再决定往里加什么 |
| `MEMORY` | 100 | 记忆 / 画像 / 召回注入 | 晚于守卫：加进来的内容也要被守卫看到 |
| `PLUGIN_HOOK` | 150 | 插件钩子 | 晚于记忆：插件能看见记忆；且它能短路，短路后不该再付 token |
| `RAG` | 200 | 检索增强 | 晚于插件：被短路时不必再去检索 |
| `LOGGING` | 500 | 埋点 | 最后：看到最终形态 |

把注入放在 `GUARD` 之前，等于让它绕过预算检查——长会话会一路涨到超窗。

### 3. 捕获异常会让整轮失败，这是故意的

框架不吞 Advisor 的异常：插件出错 → 整轮失败（流式下变成 `RunEvent.Failed`）。
**不要**为了"稳"在 Advisor 里 catch 一切然后返回空——那会让"插件没生效"变成最难查的问题。

如果你要兜底，用框架提供的 `RunInterceptor.onError` 那条通道，语义明确得多。

## 参考与验证

- 实现：`SystemPromptGuardAdvisor`（截断 + 标记）、`ContextInjectAdvisor`（拼接原人格）
- 顺序常量：`AdvisorOrders`
- 单测：`AdvisorTest`（含"注入后原人格仍在"这条断言）
- starter 自动装配：`FrameworkSpringAiAutoConfiguration`（`@ConditionalOnMissingBean`，宿主定义即覆盖）
