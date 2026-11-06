package com.agentplatform.core.session;

import com.agentplatform.core.session.SessionDtos.MessageDto;
import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台会话存储：框架语义 ↔ 平台表的映射。
 */
@ExtendWith(MockitoExtension.class)
class MessageTableSessionStoreTest {

    @Mock
    private SessionService sessionService;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SessionRepository sessionRepository;

    private MessageTableSessionStore store;

    @BeforeEach
    void setUp() {
        store = new MessageTableSessionStore(sessionService, messageRepository, sessionRepository);
    }

    @Test
    void 读取时应补齐租户并按角色映射() {
        when(sessionRepository.findBySessionId("s-1"))
                .thenReturn(Optional.of(Session.builder().sessionId("s-1").tenantId("t-9").build()));
        when(sessionService.recentMessages("t-9", "s-1", 200)).thenReturn(List.of(
                new MessageDto("m1", "s-1", null, 1, 1, "user", "你好", null, null),
                new MessageDto("m2", "s-1", null, 1, 2, "assistant", "在的", null, null)));

        List<ModelAdapter.ChatMessage> messages = store.load("s-1");

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("你好", messages.get(0).content());
        assertEquals("assistant", messages.get(1).role());
    }

    @Test
    void 工具消息回放应带回toolCallId() {
        when(sessionRepository.findBySessionId("s-1"))
                .thenReturn(Optional.of(Session.builder().sessionId("s-1").tenantId("t-9").build()));
        when(sessionService.recentMessages("t-9", "s-1", 200)).thenReturn(List.of(
                new MessageDto("m1", "s-1", null, 1, 2, "tool", "12:00", null, null, "call-9")));

        List<ModelAdapter.ChatMessage> messages = store.load("s-1");

        assertTrue(messages.get(0).isToolResult());
        assertEquals("call-9", messages.get(0).toolCallId(),
                "丢了 toolCallId，上游会判「引用了不存在的工具调用」");
    }

    @Test
    void 会话不存在时租户退回default() {
        when(sessionRepository.findBySessionId("s-x")).thenReturn(Optional.empty());
        when(sessionService.recentMessages(eq("default"), eq("s-x"), eq(200))).thenReturn(List.of());

        assertTrue(store.load("s-x").isEmpty());
    }

    @Test
    void 空sessionId不查库() {
        assertTrue(store.load(null).isEmpty());
        assertTrue(store.load("  ").isEmpty());
        verify(sessionRepository, org.mockito.Mockito.never()).findBySessionId(any());
    }

    @Test
    void 写入应带上租户委托给会话服务() {
        when(sessionRepository.findBySessionId("s-1"))
                .thenReturn(Optional.of(Session.builder().sessionId("s-1").tenantId("t-9").build()));

        store.appendMessages("s-1", List.of(new ModelAdapter.ChatMessage("user", "hi")));

        verify(sessionService).appendMessages(eq("t-9"), eq("s-1"), eq(null), any());
    }

    @Test
    void 清除会话只删消息并清缓存() {
        when(sessionRepository.findBySessionId("s-1"))
                .thenReturn(Optional.of(Session.builder().sessionId("s-1").tenantId("t-9").build()));

        store.clear("s-1");

        verify(messageRepository).deleteBySessionId("s-1");
    }

    @Test
    void 会话ID列表来自消息表去重查询() {
        when(messageRepository.findDistinctSessionIds()).thenReturn(List.of("s-1", "s-2"));
        assertEquals(List.of("s-1", "s-2"), store.conversationIds());
    }
}
