package com.agentplatform.framework.springai;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.runtime.InMemorySessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionChatMemoryRepositoryTest {

    private final InMemorySessionStore store = new InMemorySessionStore();
    private final SessionChatMemoryRepository repository = new SessionChatMemoryRepository(store);

    @Test
    void 写入后能按顺序读回() {
        repository.saveAll("c-1", List.of(
                new SystemMessage("你是助手"),
                new UserMessage("你好"),
                new AssistantMessage("你好，有什么可以帮你")));

        List<Message> loaded = repository.findByConversationId("c-1");

        assertEquals(3, loaded.size());
        assertEquals("你是助手", loaded.get(0).getText());
        assertEquals("你好", loaded.get(1).getText());
        assertEquals("你好，有什么可以帮你", loaded.get(2).getText());
    }

    @Test
    void 重复saveAll不应产生重复行() {
        List<Message> window = List.of(new UserMessage("第一问"), new AssistantMessage("第一答"));

        repository.saveAll("c-1", window);
        repository.saveAll("c-1", window);

        assertEquals(2, repository.findByConversationId("c-1").size());
    }

    @Test
    void 窗口变大时只追加新消息() {
        repository.saveAll("c-1", List.of(new UserMessage("Q1"), new AssistantMessage("A1")));
        repository.saveAll("c-1", List.of(
                new UserMessage("Q1"), new AssistantMessage("A1"),
                new UserMessage("Q2"), new AssistantMessage("A2")));

        assertEquals(4, repository.findByConversationId("c-1").size());
    }

    @Test
    void 工具结果消息必须保留toolCallId() {
        repository.saveAll("c-1", List.of(
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-7", "clock", "12:00")))
                        .build()));

        ModelAdapter.ChatMessage message = store.load("c-1").get(0);

        assertTrue(message.isToolResult());
        assertEquals("call-7", message.toolCallId());
        assertEquals("12:00", message.content());
    }

    @Test
    void 删除会话后历史为空() {
        repository.saveAll("c-1", List.of(new UserMessage("hi")));
        repository.deleteByConversationId("c-1");

        assertTrue(repository.findByConversationId("c-1").isEmpty());
        assertNull(store.load("c-1").stream().findFirst().orElse(null));
    }

    @Test
    void 能枚举会话ID() {
        repository.saveAll("c-1", List.of(new UserMessage("a")));
        repository.saveAll("c-2", List.of(new UserMessage("b")));

        assertEquals(2, repository.findConversationIds().size());
    }

    @Test
    void 空列表不应写入任何东西() {
        repository.saveAll("c-1", List.of());
        assertTrue(repository.findByConversationId("c-1").isEmpty());
    }
}
