# E1：把对话历史落到自己的存储

**适用场景**：你在用 Spring AI 的 `ChatClient`，但 `InMemoryChatMemoryRepository` 一重启就丢历史；
或者你想把历史存进自己的库，又不想改业务代码。

## 最小代码

实现 `ChatMemoryRepository`（Spring AI 的接口，不是我们的），然后把 `SessionStore` 接进去：

```java
public class MyChatMemoryRepository implements ChatMemoryRepository {

    private final SessionStore store;   // 框架的存储抽象，你自己实现

    @Override
    public List<String> findConversationIds() {
        return store.conversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return store.load(conversationId).stream()
                .map(SpringAiMessageConverter::toSpringAi)   // 框架自带的双向转换
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // 见下方"必须注意"第 1 条：这里不能直接覆盖
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        store.clear(conversationId);
    }
}
```

## 必须注意的三条

### 1. `saveAll` 是「全量覆盖」语义，而你的表是「追加」语义

Spring AI 每次把**整个窗口**传进来，不是"新增几条"。如果你直接 `delete + insert`：

- 历史被窗口淘汰策略反向改写（用户昨天说的话可能消失）
- `turn_no` / `trace_id` / `usage` 这些字段全部丢失

正确做法是**差量插入**：算出"库里还没有的"，只追加它们。参考实现里的写法：

```java
Set<String> existing = store.load(id).stream()
        .map(SpringAiMessageConverter::keyOf).collect(toSet());

List<ChatMessage> fresh = messages.stream()
        .map(SpringAiMessageConverter::toFramework)
        .filter(m -> !existing.contains(SpringAiMessageConverter.keyOf(m)))
        .toList();

if (!fresh.isEmpty()) store.appendMessages(id, fresh);
```

`keyOf` 用 `role + 正文 + toolCallId` 组合，因为 Spring AI 的消息没有稳定 id。

### 2. 工具消息必须保留 `toolCallId`

Spring AI 的工具结果是 `ToolResponseMessage`（可含多条 response），框架侧是
「一条 `role=tool` 消息 + 一个 `toolCallId`」。**丢了 ID，上游会以「引用了不存在的调用」400**。

如果你自己实现转换，务必保留；平台侧为此专门给 `ap_message` 加了 `tool_call_id` 列。

### 3. `findConversationIds` 可以返回空列表

它不是主链路必需（只有"列会话"这类管理功能会用）。返回 `List.of()` 表示"不支持枚举"，
不影响读写。**不要为了它去建额外的索引表**。

## 接进 ChatClient

```java
ChatMemory memory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(new MyChatMemoryRepository(store))
        .maxMessages(20)
        .build();

ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
        .build();
```

## 参考与验证

- 实现：`agent-platform-framework-springai` 的 `SessionChatMemoryRepository`
- 转换：`SpringAiMessageConverter`（`toSpringAi` / `toFramework` / `keyOf`）
- 端到端：`agent-platform-framework-sample-springai` 的 `SpringAiIntegrationTest`
  （断言"第二轮请求里带着第一轮内容"且"历史落在框架存储里"）
