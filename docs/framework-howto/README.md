# 框架扩展点 how-to

面向「已经决定用这个框架，想知道某个点怎么接」的开发者。
每篇只讲三件事：**适用场景 → 最小代码 → 必须注意的坑**。

| 篇 | 扩展点 | 对应 Spring AI 接口 | 参考实现 |
|---|---|---|---|
| [E1 会话记忆](E1-memory.md) | 把对话历史落到自己的存储 | `ChatMemoryRepository` | `SessionChatMemoryRepository` |
| [E3 拦截与注入](E3-advisor.md) | 提示词守卫 / 上下文注入 | `CallAdvisor` | `SystemPromptGuardAdvisor`、`ContextInjectAdvisor` |
| [E4 向量检索](E4-vectorstore.md) | 用自研后端做 RAG | `VectorStore` | `InMemoryVectorStore` |
| [工具与策略](tool-and-policy.md) | 工具治理 / 配额 / 鉴权 / 观测 | `Tool`、`ToolFilter` 等 | `PlatformToolCatalog`、`PlatformQuotaPolicy` 等 |

## 阅读顺序（按依赖关系，不是按编号）

```
tool-and-policy ──┐  先读这篇：它决定"哪些东西是可选的、默认值是什么"
                  │  不懂默认值，后面三篇都会问"不实现行不行"
                  ▼
        E1 记忆 ──┴── E4 向量       这两篇互不依赖，各自独立
                  │                  （都是"存储/检索怎么接"）
                  ▼
              E3 拦截                  最后读：它要改请求，
                                       前置知识是"请求里已经有什么"（记忆 + 召回都在里面）
```

**为什么 E3 放最后**：Advisor 看到的是**已经装配好的请求**——记忆注入、RAG 召回都已就位。
先读 E3 容易误以为"Advisor 负责把历史塞进去"，实际上那是 E1 与 `MessageChatMemoryAdvisor` 的活。

## 交叉引用速查

| 你在做什么 | 还需要看 |
|---|---|
| 把历史存进自己的库（E1） | [E3](E3-advisor.md) 的"Order 表"——记忆注入的位置在那里定义 |
| 写一个 RAG 注入（E4） | [E3](E3-advisor.md) 的"`augmentSystemMessage` 是替换语义"——往提示词加东西必须先看这条 |
| 限制提示词长度（E3） | [E4](E4-vectorstore.md) 的"`Filter` 只支持 eq"——召回的量会直接影响预算 |
| 接工具治理（tool-and-policy） | [E3](E3-advisor.md) 末尾的"与 Advisor 分工表"——两张表容易混 |

## 通用前提

- JDK 21（**不需要** `--enable-preview`）
- 依赖坐标建议用 BOM 统一版本：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.agentplatform</groupId>
      <artifactId>agent-platform-framework-bom</artifactId>
      <version>1.5.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## 四条通用约定

1. **所有 SPI 都有默认实现**（`QuotaPolicy.NOOP`、`AuthorizationPolicy.PERMISSIVE`、`RunObserver.NOOP`）。
   不接也能跑，接了才生效——不要为了"完整"而强行实现全部接口。
2. **默认实现都可以用 `@ConditionalOnMissingBean` 覆盖**。宿主定义同名 Bean 即接管。
3. **失败要可见**。附件解析失败、拦截器异常这类情况，框架一律显式上报或抛出，
   **不做静默降级**——静默失败是最难排查的一类问题。
4. **别只信接口名**。E3 的 `augmentSystemMessage`、E1 的 `saveAll` 都属于"名字骗人"，
   真实语义以各篇「必须注意」为准。

## 四个 sample（可直接运行）

| sample | 证明什么 | 看它学什么 |
|---|---|---|
| `agent-platform-framework-sample-minimal` | 契约不绑容器 | 不用 Spring 也能跑一轮带工具的对话 |
| `agent-platform-framework-sample-web` | 加依赖即用 | starter 自动装配 + HTTP 入口 + 观测 |
| `agent-platform-framework-sample-springai` | 别人能集成 | 纯 Spring AI 项目只加一个依赖就拿到 E1+E3 |
| `agent-platform-framework-sample-rag` | 标准组件能用我们的实现 | 官方 `VectorStoreDocumentRetriever` 直接检索 |
