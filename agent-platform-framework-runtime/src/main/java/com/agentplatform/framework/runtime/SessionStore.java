package com.agentplatform.framework.runtime;

import com.agentplatform.framework.api.ModelAdapter;

import java.util.List;

/**
 * 会话存储抽象。
 *
 * <p>框架只要求「能读历史、能追加消息、能删除」，不规定存哪：内存、MySQL、Redis、宿主自己的库都可以。
 * 平台侧的实现会落到 {@code message} 表；{@link InMemorySessionStore} 用于测试与 sample。
 *
 * <p><b>为什么需要 {@link #appendMessages}</b>：Spring AI 的 {@code ChatMemoryRepository.saveAll}
 * 是「全量覆盖」语义（每次传整个窗口），而会话表是「追加」语义（有 turn_no / trace_id）。
 * 接口必须能接收任意条消息，才能在那层做差量插入；只有 {@code append(user, assistant)}
 * 是不够的。
 */
public interface SessionStore {

    /** 读取会话历史（时间顺序）；不存在时返回空列表，**不返回 null**。 */
    List<ModelAdapter.ChatMessage> load(String sessionId);

    /** 追加一轮对话（user + assistant）：运行时自己落库时用。 */
    void append(String sessionId, String userText, String assistantText);

    /** 追加任意消息（差量写入）：{@code ChatMemoryRepository.saveAll} 的落点。 */
    void appendMessages(String sessionId, List<ModelAdapter.ChatMessage> messages);

    /** 列出全部会话 ID（为空的实现表示「不支持枚举」，不影响读写）。 */
    List<String> conversationIds();

    /** 删除整个会话。 */
    void clear(String sessionId);
}
