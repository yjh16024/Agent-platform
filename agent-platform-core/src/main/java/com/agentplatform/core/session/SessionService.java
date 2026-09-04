package com.agentplatform.core.session;

import com.agentplatform.common.dto.PageResult;
import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.session.SessionDtos.CreateSessionRequest;
import com.agentplatform.core.session.SessionDtos.MessageDto;
import com.agentplatform.core.session.SessionDtos.SessionDetail;
import com.agentplatform.core.session.SessionDtos.SessionSummary;
import com.agentplatform.model.entity.Message;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话服务（会话历史与上下文管理）。
 * <p>
 * 落地 Session → Turn → Message 分层（Turn 由 message.turn_no/seq_no 表达）。
 * 供 REST 管理面与 AgentRuntime 运行时记忆复用：多轮上下文由本服务持久化并回放。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String STATUS_ACTIVE = "active";

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    // ---- 运行时记忆（供 AgentRuntimeService 调用）----

    /**
     * 解析会话：按 sessionId 查找，不存在则创建。跨租户访问拒绝。
     *
     * @return 会话实体；sessionId 为空返回 null（不持久化）
     */
    @Transactional
    public Session resolve(String tenantId, String agentId, String userId, String sessionId, String titleFallback) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Optional<Session> existing = sessionRepository.findBySessionId(sessionId);
        if (existing.isPresent()) {
            Session s = existing.get();
            if (!s.getTenantId().equals(tenantId)) {
                throw BizException.forbidden("Session " + sessionId + " does not belong to tenant " + tenantId);
            }
            return s;
        }
        Session created = Session.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .agentId(agentId)
                .userId(userId)
                .title(titleFallback == null ? null : truncate(titleFallback, 50))
                .status(STATUS_ACTIVE)
                .build();
        return sessionRepository.save(created);
    }

    /**
     * 取会话最近 N 条消息（时间顺序），供历史回放。
     */
    @Transactional(readOnly = true)
    public List<MessageDto> recentMessages(String tenantId, String sessionId, int max) {
        List<Message> all = messageRepository.findBySessionIdOrderByTurnNoAscSeqNoAsc(sessionId);
        int from = Math.max(0, all.size() - Math.max(max, 0));
        return all.subList(from, all.size()).stream().map(this::toDto).toList();
    }

    /**
     * 记录一轮对话（user + assistant 两条消息，同 turn_no）。
     */
    @Transactional
    public void recordExchange(String tenantId, String sessionId, String runId,
                               String userText, String assistantText, String model) {
        Session session = sessionRepository.findByTenantIdAndSessionId(tenantId, sessionId).orElse(null);
        if (session == null) {
            log.debug("Session {} not found, skip message persistence", sessionId);
            return;
        }
        int turnNo = messageRepository.maxTurnNo(sessionId) + 1;
        messageRepository.save(buildMessage(tenantId, sessionId, runId, turnNo, 1, "user", userText, null));
        messageRepository.save(buildMessage(tenantId, sessionId, runId, turnNo, 2, "assistant", assistantText, model));

        if (session.getTitle() == null || session.getTitle().isBlank()) {
            session.setTitle(truncate(userText, 50));
        }
        sessionRepository.save(session);
        sessionRepository.touch(sessionId); // 刷新 updated_at，保证「最近活跃」排序正确
    }

    // ---- 管理面（REST）----

    @Transactional
    public SessionSummary create(String tenantId, CreateSessionRequest req) {
        Session s = Session.builder()
                .sessionId(IdGenerator.generate("sess"))
                .tenantId(tenantId)
                .agentId(req.agentId())
                .userId(req.userId())
                .title(req.title() == null ? null : truncate(req.title(), 50))
                .status(STATUS_ACTIVE)
                .build();
        return toSummary(sessionRepository.save(s));
    }

    /**
     * 保存一轮「已完成的对话」到会话历史（新会话入口调用）。
     * <p>
     * 用于前端需求：对话页当前对话不落库，等用户点「新对话」时才把整轮消息
     * 导入会话历史。每对 user/assistant 消息分配递增轮次（单条则独占一轮）。
     * </p>
     *
     * @return 新建会话摘要（含 session_id）
     */
    @Transactional
    public SessionSummary importConversation(String tenantId, String agentId, String userId,
                                             String title, List<ImportMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw BizException.badRequest("messages 不能为空，无可保存的对话");
        }
        Session s = Session.builder()
                .sessionId(IdGenerator.generate("sess"))
                .tenantId(tenantId)
                .agentId(agentId)
                .userId(userId)
                .title(title == null || title.isBlank() ? null : truncate(title, 50))
                .status(STATUS_ACTIVE)
                .build();
        s = sessionRepository.save(s);

        int turn = 0;
        for (ImportMessage m : messages) {
            turn++;
            if (m.role() == null || m.role().isBlank() || m.content() == null) {
                continue;
            }
            messageRepository.save(buildMessage(tenantId, s.getSessionId(), null, turn, 1,
                    m.role(), m.content(), null));
        }
        return toSummary(s);
    }

    /** 待导入的一条历史消息。 */
    public record ImportMessage(String role, String content) {
    }

    @Transactional(readOnly = true)
    public PageResult<SessionSummary> list(String tenantId, String agentId, String userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Session> result;
        if (agentId != null && !agentId.isBlank() && userId != null && !userId.isBlank()) {
            result = sessionRepository.findByTenantIdAndAgentIdAndUserId(tenantId, agentId, userId, pageable);
        } else if (agentId != null && !agentId.isBlank()) {
            result = sessionRepository.findByTenantIdAndAgentId(tenantId, agentId, pageable);
        } else {
            result = sessionRepository.findByTenantId(tenantId, pageable);
        }
        return PageResult.from(result, this::toSummary);
    }

    @Transactional(readOnly = true)
    public SessionDetail get(String tenantId, String sessionId) {
        Session s = getOrThrow(tenantId, sessionId);
        List<MessageDto> messages = messageRepository.findBySessionIdOrderByTurnNoAscSeqNoAsc(sessionId)
                .stream().map(this::toDto).toList();
        return new SessionDetail(toSummary(s), messages);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> messages(String tenantId, String sessionId) {
        getOrThrow(tenantId, sessionId);
        return messageRepository.findBySessionIdOrderByTurnNoAscSeqNoAsc(sessionId)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public SessionSummary update(String tenantId, String sessionId, String title, String status) {
        Session s = getOrThrow(tenantId, sessionId);
        if (title != null) {
            s.setTitle(truncate(title, 50));
        }
        if (status != null && !status.isBlank()) {
            s.setStatus(status);
        }
        return toSummary(sessionRepository.save(s));
    }

    /**
     * 删除会话（物理删除，含其全部消息）。
     * <p>原先仅置 status=archived，而列表查询不过滤状态，导致「归档后仍显示在
     * 会话历史里、看起来没生效」。用户点归档/删除的预期是从列表中消失，
     * 因此改为物理删除；只想清空内容而保留会话条目时用 {@link #clear}。</p>
     */
    @Transactional
    public void archive(String tenantId, String sessionId) {
        Session s = getOrThrow(tenantId, sessionId);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(s);
        log.info("Deleted session {} (tenant {})", sessionId, tenantId);
    }

    @Transactional
    public void clear(String tenantId, String sessionId) {
        Session s = getOrThrow(tenantId, sessionId);
        messageRepository.deleteBySessionId(sessionId);
        s.setStatus("cleared");
        sessionRepository.save(s);
    }

    // ---- 内部 ----

    private Message buildMessage(String tenantId, String sessionId, String runId,
                                 int turnNo, int seqNo, String role, String text, String model) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text == null ? "" : text);
        return Message.builder()
                .messageId(IdGenerator.generate("msg"))
                .sessionId(sessionId)
                .tenantId(tenantId)
                .runId(runId)
                .turnNo(turnNo)
                .seqNo(seqNo)
                .role(role)
                .content(content)
                .model(model)
                .build();
    }

    private Session getOrThrow(String tenantId, String sessionId) {
        return sessionRepository.findByTenantIdAndSessionId(tenantId, sessionId)
                .orElseThrow(() -> BizException.notFound("session", sessionId));
    }

    private SessionSummary toSummary(Session s) {
        int count = Math.toIntExact(messageRepository.countBySessionId(s.getSessionId()));
        return new SessionSummary(
                s.getSessionId(), s.getTenantId(), s.getAgentId(), s.getUserId(),
                s.getTitle(), s.getStatus(), count, s.getCreatedAt(), s.getUpdatedAt());
    }

    private MessageDto toDto(Message m) {
        return new MessageDto(
                m.getMessageId(), m.getSessionId(), m.getRunId(), m.getTurnNo(), m.getSeqNo(),
                m.getRole(), m.textContent(), m.getModel(), m.getCreatedAt());
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}