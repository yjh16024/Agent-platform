package com.agentplatform.framework.springai;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.runtime.SessionStore;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * E1：把 Spring AI 的对话历史落到框架的 {@link SessionStore}。
 *
 * <p>Spring AI 官方只提供 {@code InMemoryChatMemoryRepository}（进程内 Map，重启即丢）。
 * 本实现让 {@code ChatClient + MessageWindowChatMemory} 的历史可以直接落在宿主的会话表上，
 * 并且不要求宿主使用任何特定数据库——它只认 {@link SessionStore}。
 *
 * <h3>核心难点：{@code saveAll} 的语义翻译</h3>
 * Spring AI 每次把**整个窗口**传进 {@code saveAll}（全量覆盖语义）；
 * 而会话表是**追加**语义（有 turn_no / trace_id / usage，覆盖会丢）。
 * 所以这里不做覆盖，也不做删除，只把「库里还没有的」追加进去：
 *
 * <pre>
 * existing = load(id).map(keyOf)        // 已有消息的幂等键
 * fresh    = incoming.filter(k -&gt; !existing.contains(k))
 * appendMessages(id, fresh)
 * </pre>
 *
 * <p>代价是「模型改写的旧消息」不会被更新——但对会话历史而言这是正确取舍：
 * 历史是事实记录，不应该被窗口淘汰策略反向修改。
 */
public class SessionChatMemoryRepository implements ChatMemoryRepository {

    private final SessionStore store;

    public SessionChatMemoryRepository(SessionStore store) {
        if (store == null) {
            throw new IllegalArgumentException("SessionStore 必填");
        }
        this.store = store;
    }

    @Override
    public List<String> findConversationIds() {
        return store.conversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return store.load(conversationId).stream()
                .map(SpringAiMessageConverter::toSpringAi)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (ModelAdapter.ChatMessage message : store.load(conversationId)) {
            existing.add(SpringAiMessageConverter.keyOf(message));
        }

        List<ModelAdapter.ChatMessage> fresh = messages.stream()
                .map(SpringAiMessageConverter::toFramework)
                .filter(message -> !existing.contains(SpringAiMessageConverter.keyOf(message)))
                .toList();

        if (!fresh.isEmpty()) {
            store.appendMessages(conversationId, fresh);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        store.clear(conversationId);
    }
}
