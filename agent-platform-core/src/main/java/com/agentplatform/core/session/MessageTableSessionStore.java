package com.agentplatform.core.session;

import com.agentplatform.core.session.SessionDtos.MessageDto;
import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.Principal;
import com.agentplatform.framework.runtime.SessionStore;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 平台侧的会话存储实现：把框架的 {@link SessionStore} 落到 {@code message} 表。
 *
 * <p>这一层是 P2' 的关键接缝——它让「平台消息表」变成框架的**一个**后端，
 * 而不是唯一后端。换掉这个 Bean，同一份运行时就能跑到 Redis / 别的库上。</p>
 *
 * <h3>两个语义落差，必须显式处理</h3>
 * <ol>
 *   <li><b>框架不带租户</b>：{@link SessionStore} 只有 sessionId，租户要从会话记录反查。
 *       查不到时退回 {@code default}（与运行时的默认租户一致）。</li>
 *   <li><b>框架不带轮次</b>：消息只有 role/content。轮次由写入侧按
 *       「user 开新一轮、其余同轮递增 seq」分配，与平台原有 {user, assistant} 配对语义一致。</li>
 * </ol>
 *
 * <p><b>已知限制</b>：当前 {@code message.content} 只存文本 part，工具结果的
 * {@code toolCallId} 没有落库字段；框架侧读到的是纯文本工具消息。
 * 要支持原生 tool-role 回放，需要给消息表加列——这是后续独立的一步，
 * 不在本次接缝范围内。</p>
 */
@Component
public class MessageTableSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(MessageTableSessionStore.class);

    /** 单次回放上限：与平台「最近 N 条」的记忆窗口保持同一量级。 */
    private static final int MAX_LOAD = 200;

    private final SessionService sessionService;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;

    @Autowired(required = false)
    private SessionRecentCache recentCache;

    public MessageTableSessionStore(SessionService sessionService,
                                    MessageRepository messageRepository,
                                    SessionRepository sessionRepository) {
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public List<ModelAdapter.ChatMessage> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        String tenantId = tenantOf(sessionId);
        return sessionService.recentMessages(tenantId, sessionId, MAX_LOAD).stream()
                .map(this::toChatMessage)
                .toList();
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
        sessionService.appendMessages(tenantOf(sessionId), sessionId, null, messages);
    }

    @Override
    public List<String> conversationIds() {
        return messageRepository.findDistinctSessionIds();
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String tenantId = tenantOf(sessionId);
        messageRepository.deleteBySessionId(sessionId);
        if (recentCache != null) {
            recentCache.evict(tenantId, sessionId);
        }
        log.info("Cleared session messages: {} (tenant {})", sessionId, tenantId);
    }

    private ModelAdapter.ChatMessage toChatMessage(MessageDto dto) {
        String role = dto.role() == null ? "user" : dto.role();
        String text = dto.content() == null ? "" : dto.content();
        // R10：工具消息必须带回 toolCallId，否则模型侧无法把它和对应的调用配对
        if ("tool".equalsIgnoreCase(role) && dto.toolCallId() != null) {
            return ModelAdapter.ChatMessage.tool(dto.toolCallId(), text);
        }
        return new ModelAdapter.ChatMessage(role, text);
    }

    private String tenantOf(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .map(Session::getTenantId)
                .orElse(Principal.DEFAULT_TENANT);
    }
}
