package com.agentplatform.core.session;

import com.agentplatform.model.entity.Message;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中期记忆（会话早期摘要）服务测试。
 */
class SessionSummaryServiceTest {

    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final SessionSummaryService service =
            new SessionSummaryService(sessionRepository, messageRepository);

    private Session session(int latestTurn, Integer summaryTurn, String summary) {
        return Session.builder()
                .sessionId("s1")
                .tenantId("t1")
                .summary(summary)
                .summaryTurn(summaryTurn)
                .build();
    }

    private List<Message> earlyUserMessages(int turns) {
        List<Message> list = new ArrayList<>();
        for (int i = 1; i <= turns; i++) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", "第 " + i + " 轮用户提问内容，用于摘要验证");
            list.add(Message.builder()
                    .messageId("m" + i).sessionId("s1").tenantId("t1")
                    .turnNo(i).seqNo(1).role("user").content(content).build());
        }
        return list;
    }

    @Test
    void shortSessionDoesNotRollup() {
        when(messageRepository.maxTurnNo("s1")).thenReturn(10); // < 40
        assertNull(service.summaryFor(session(10, 0, null)));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void longSessionRollsUpOnceAndPersists() {
        when(messageRepository.maxTurnNo("s1")).thenReturn(50);
        List<Message> early = earlyUserMessages(30); // turn 1..30 <= 50-20
        when(messageRepository.findBySessionIdOrderByTurnNoAscSeqNoAsc("s1")).thenReturn(early);

        String summary = service.summaryFor(session(50, 0, null));

        assertNotNull(summary, "超阈值应生成摘要");
        assertTrue(summary.contains("早期会话要点"), "摘要应是要点式文本");
        verify(sessionRepository).save(any(Session.class));
    }

    @Test
    void existingCoverageReusesSummaryWithoutSave() {
        when(messageRepository.maxTurnNo("s1")).thenReturn(60);
        Session s = session(60, 40, "已有摘要内容");
        String summary = service.summaryFor(s);
        assertNotNull(summary);
        assertTrue(summary.contains("已有摘要内容"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void nullSessionReturnsNull() {
        assertNull(service.summaryFor(null));
    }
}
