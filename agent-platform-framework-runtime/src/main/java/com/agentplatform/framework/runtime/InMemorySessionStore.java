package com.agentplatform.framework.runtime;

import com.agentplatform.framework.api.ModelAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话存储（测试 / sample 用）。
 *
 * <p>刻意不做过期淘汰：它是「最小可运行」的一部分，不是生产实现。
 * 生产场景请宿主自己实现 {@link SessionStore}。
 */
public class InMemorySessionStore implements SessionStore {

    private final Map<String, List<ModelAdapter.ChatMessage>> sessions = new ConcurrentHashMap<>();

    @Override
    public List<ModelAdapter.ChatMessage> load(String sessionId) {
        List<ModelAdapter.ChatMessage> messages = sessions.get(sessionId);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public void append(String sessionId, String userText, String assistantText) {
        appendMessages(sessionId, List.of(
                new ModelAdapter.ChatMessage("user", userText),
                new ModelAdapter.ChatMessage("assistant", assistantText)));
    }

    @Override
    public void appendMessages(String sessionId, List<ModelAdapter.ChatMessage> messages) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        List<ModelAdapter.ChatMessage> target =
                sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (target) {
            target.addAll(messages);
        }
    }

    @Override
    public List<String> conversationIds() {
        return List.copyOf(sessions.keySet());
    }

    @Override
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    public int size(String sessionId) {
        List<ModelAdapter.ChatMessage> messages = sessions.get(sessionId);
        return messages == null ? 0 : messages.size();
    }
}
