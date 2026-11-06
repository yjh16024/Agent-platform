package com.agentplatform.core.session;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.model.entity.Message;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 追加消息的轮次分配。
 *
 * <p>这是 P2' 接缝最容易错的地方：框架侧只给 role/content，而平台的
 * {@code turn_no} / {@code seq_no} 是「一问一答同一轮」的语义。
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceAppendMessagesTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private MessageRepository messageRepository;

    private SessionService newService() {
        return new SessionService(sessionRepository, messageRepository);
    }

    @Test
    void user开新一轮且assistant同轮递增seq() {
        SessionService service = newService();
        when(sessionRepository.findByTenantIdAndSessionId("t-1", "s-1"))
                .thenReturn(Optional.of(Session.builder().sessionId("s-1").tenantId("t-1").build()));
        when(messageRepository.maxTurnNo("s-1")).thenReturn(1);

        service.appendMessages("t-1", "s-1", null, List.of(
                new ModelAdapter.ChatMessage("user", "第一问"),
                new ModelAdapter.ChatMessage("assistant", "第一答"),
                new ModelAdapter.ChatMessage("user", "第二问"),
                new ModelAdapter.ChatMessage("assistant", "第二答")));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(4)).save(captor.capture());
        List<Message> saved = captor.getAllValues();

        assertEquals(List.of(2, 2, 3, 3), saved.stream().map(Message::getTurnNo).toList());
        assertEquals(List.of(1, 2, 1, 2), saved.stream().map(Message::getSeqNo).toList());
        assertEquals(List.of("user", "assistant", "user", "assistant"),
                saved.stream().map(Message::getRole).toList());
        assertEquals("第一问", saved.get(0).textContent());
    }

    @Test
    void 会话不存在时静默跳过而不是抛错() {
        SessionService service = newService();
        when(sessionRepository.findByTenantIdAndSessionId("t-1", "ghost")).thenReturn(Optional.empty());

        service.appendMessages("t-1", "ghost", null,
                List.of(new ModelAdapter.ChatMessage("user", "hi")));

        verify(messageRepository, times(0)).save(any());
        verify(messageRepository, times(0)).maxTurnNo(anyString());
    }

    @Test
    void 工具消息必须保存toolCallId() {
        SessionService service = newService();
        when(sessionRepository.findByTenantIdAndSessionId("t-1", "s-1"))
                .thenReturn(Optional.of(Session.builder().sessionId("s-1").tenantId("t-1").build()));
        when(messageRepository.maxTurnNo("s-1")).thenReturn(1);

        service.appendMessages("t-1", "s-1", null,
                List.of(new ModelAdapter.ChatMessage("tool", "12:00"),
                        ModelAdapter.ChatMessage.tool("call-9", "12:00")));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(captor.capture());

        List<Message> saved = captor.getAllValues();
        assertEquals("tool", saved.get(0).getRole());
        assertEquals("call-9", saved.get(1).getToolCallId(), "工具结果的调用 ID 必须落库，否则回放对不上");
    }

    @Test
    void 空列表不触发任何数据库操作() {
        newService().appendMessages("t-1", "s-1", null, List.of());

        verify(messageRepository, times(0)).save(any());
    }

    @Test
    void 首条就是assistant时仍落在第一轮() {
        SessionService service = newService();
        when(sessionRepository.findByTenantIdAndSessionId("t-1", "s-1"))
                .thenReturn(Optional.of(Session.builder().sessionId("s-1").tenantId("t-1").build()));
        when(messageRepository.maxTurnNo("s-1")).thenReturn(0);

        service.appendMessages("t-1", "s-1", null,
                List.of(new ModelAdapter.ChatMessage("assistant", "我先说")));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getTurnNo());
        assertEquals(1, captor.getValue().getSeqNo());
    }
}
