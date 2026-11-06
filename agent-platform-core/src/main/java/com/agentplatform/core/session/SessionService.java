package com.agentplatform.core.session;

import com.agentplatform.common.dto.PageResult;
import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.session.SessionDtos.CreateSessionRequest;
import com.agentplatform.core.session.SessionDtos.MessageDto;
import com.agentplatform.core.session.SessionDtos.SessionDetail;
import com.agentplatform.core.session.SessionDtos.SessionSummary;
import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.SessionHandle;
import com.agentplatform.model.entity.Message;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 可选依赖：会话短期记忆缓存（Redis；缺失/不可用时自动回 DB，不影响单测与最小环境）。 */
    @Autowired(required = false)
    private SessionRecentCache recentCache;

    // ---- 运行时记忆（供 AgentRuntimeService 调用）----

    /**
     * 解析会话：按 sessionId 查找，不存在则创建。跨租户访问拒绝。
     *
     * <p>返回 {@link SessionHandle} 而不是 JPA 实体（2026-11-06）：
     * 运行时不应当把持久化对象当数据载体用，否则「换一种存储」就等于「改运行时」。</p>
     *
     * @return 会话句柄；sessionId 为空返回 null（不持久化）
     */
    @Transactional
    public SessionHandle resolve(String tenantId, String agentId, String userId, String sessionId, String titleFallback) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Optional<Session> existing = sessionRepository.findBySessionId(sessionId);
        if (existing.isPresent()) {
            Session s = existing.get();
            if (!s.getTenantId().equals(tenantId)) {
                throw BizException.forbidden("Session " + sessionId + " does not belong to tenant " + tenantId);
            }
            return toHandle(s);
        }
        Session created = Session.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .agentId(agentId)
                .userId(userId)
                .title(titleFallback == null ? null : truncate(titleFallback, 50))
                .status(STATUS_ACTIVE)
                .build();
        return toHandle(sessionRepository.save(created));
    }

    private SessionHandle toHandle(Session s) {
        return new SessionHandle(s.getSessionId(), s.getTenantId(), s.getAgentId(),
                s.getUserId(), s.getTitle());
    }

    /**
     * 取会话最近 N 条消息（时间顺序），供历史回放。
     */
    @Transactional(readOnly = true)
    public List<MessageDto> recentMessages(String tenantId, String sessionId, int max) {
        int n = Math.max(max, 0);
        // ① 短期记忆：优先读 Redis 缓存（缓存不存在/不可用返回 null → 走 DB）
        if (recentCache != null) {
            List<MessageDto> cached = recentCache.get(tenantId, sessionId, n);
            if (cached != null) {
                return cached;
            }
        }
        // ② 未命中 → DB；为缓存完整性拉最近 MAX_ITEMS 条（≤25 轮），返回其中最近 n 条
        List<Message> all = messageRepository.findBySessionIdOrderByTurnNoAscSeqNoAsc(sessionId);
        int fetch = Math.max(n, SessionRecentCache.MAX_ITEMS);
        int from = Math.max(0, all.size() - fetch);
        List<MessageDto> fetched = all.subList(from, all.size()).stream().map(this::toDto).toList();
        if (recentCache != null) {
            recentCache.put(tenantId, sessionId, fetched);
        }
        int back = Math.max(0, fetched.size() - n);
        return fetched.subList(back, fetched.size());
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
        /*
         * 落库前剥掉混进正文的元推理（2026-11-02）。
         *
         * <p><b>为什么必须在这里做</b>：插件侧已经剥过了（发给 QQ 的是干净的），
         * 但**库里存的是模型原始输出**。下一轮读回最近 40 条时，
         * 那段"按规则，他在附和…可以回短一句"又进了上下文 ——
         * 模型看到自己"想过"的话，下一轮更容易继续这样写，形成恶性循环。</p>
         *
         * <p>实测原文（message 表 id=3760）：</p>
         * <pre>
         *   嗯
         *
         *   （话题聊完了，他附和）
         *
         *   按规则，他在附和"对呀"，可以回短一句然后停。…一句短的就行。&lt;/think&gt;那你喜欢她哪一点啊
         * </pre>
         *
         * <p>剥完只剩「嗯」和「那你喜欢她哪一点啊」。</p>
         */
        String cleanAssistant = stripMetaThinking(assistantText);
        messageRepository.save(buildMessage(tenantId, sessionId, runId, turnNo, 1, "user", userText, null));
        messageRepository.save(buildMessage(tenantId, sessionId, runId, turnNo, 2, "assistant", cleanAssistant, model));
        // 短期记忆：同步追加最新一轮到 Redis 缓存（缓存不存在时留待读路径重建）
        if (recentCache != null) {
            recentCache.append(tenantId, sessionId, turnNo, userText, cleanAssistant);
        }

        if (session.getTitle() == null || session.getTitle().isBlank()) {
            session.setTitle(truncate(userText, 50));
        }
        sessionRepository.save(session);
        sessionRepository.touch(sessionId); // 刷新 updated_at，保证「最近活跃」排序正确
    }

    /**
     * 追加任意消息（框架 {@code SessionStore.appendMessages} 的落点）。
     *
     * <p>轮次分配规则：{@code user} 开新一轮、其余角色同轮递增 {@code seq}——
     * 与 {@link #recordExchange} 的 {user, assistant} 配对语义一致。
     * 幂等由调用方（框架的差量写入）保证，这里只负责落库。</p>
     */
    @Transactional
    public void appendMessages(String tenantId, String sessionId, String runId,
                               List<ModelAdapter.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Session session = sessionRepository.findByTenantIdAndSessionId(tenantId, sessionId).orElse(null);
        if (session == null) {
            log.debug("Session {} not found, skip message append", sessionId);
            return;
        }
        int turnNo = messageRepository.maxTurnNo(sessionId);
        int seq = 0;
        for (ModelAdapter.ChatMessage message : messages) {
            String role = message.role() == null ? "user" : message.role();
            if ("user".equalsIgnoreCase(role)) {
                turnNo++;
                seq = 1;
            } else {
                if (turnNo == 0) {
                    turnNo = 1;
                }
                seq++;
            }
            messageRepository.save(buildMessage(tenantId, sessionId, runId, turnNo, seq,
                    role, message.content(), null, message.toolCallId()));
        }
        if (recentCache != null) {
            recentCache.evict(tenantId, sessionId);
        }
        sessionRepository.touch(sessionId);
    }

    /**
     * 剥掉混进正文的**中文元推理**，以及残留的 think 标签（2026-11-02）。
     *
     * <p><b>为什么不放在插件里就够了</b>：插件只影响"发给 QQ 的那条"，
     * 而 {@code message} 表存的是模型原始输出。下一轮读回历史时，
     * 那段独白又进了上下文 —— 模型看到自己"想过"的话，会更容易继续这样写。</p>
     *
     * <p><b>规则和插件侧保持一致</b>（{@code stripMetaThinking}）：</p>
     * <ol>
     *   <li>先删 {@code <!-- --></think>} 标签本身；</li>
     *   <li>按空行分段，整段只有括号的（旁白）丢弃；</li>
     *   <li>段内按句切，含**元描述词**的那句丢弃，其余保留。</li>
     * </ol>
     *
     * <p>元描述词都是"我该怎么回"的说法（按规则 / 他在附和 / 可以回短 /
     * 一句短的就行），刻意避开「应该」「可以」这种泛词 —— 她平时就说
     * 「你应该早点睡」「可以啊你来呗」，那些不能删。</p>
     *
     * @param text 模型原始回复；可能为 null
     * @return 剥完的文本；可能为空串（整条都是独白）
     */
    public static String stripMetaThinking(String text) {
        if (text == null || text.isBlank() || text.length() < 6) {
            return text;
        }
        String s = text.replaceAll("(?i)</?think>", "");
        String[] parts = s.split("\\n{2,}");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            // ① 整段只有括号 → 旁白
            if (t.matches("^[\uFF08(][^\uFF09)]*[\uFF09)]$")) {
                continue;
            }
            // ② 段内按句丢
            String[] sents = t.split("(?<=[\u3002\uFF01\uFF1F!?~\uFF5E])");
            StringBuilder seg = new StringBuilder();
            boolean segDropped = false;
            for (String raw : sents) {
                String sent = raw.trim();
                if (sent.isEmpty()) {
                    continue;
                }
                if (META_THINKING.matcher(sent).find()) {
                    segDropped = true;
                    continue;
                }
                seg.append(sent);
            }
            if (seg.length() == 0) {
                // 整段都被丢了；如果本来就没内容也无所谓
                if (segDropped) {
                    continue;
                }
                continue;
            }
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(seg);
        }
        return out.toString().trim();
    }

    /** 元描述词：都在描述"我该怎么回"，而不是"回什么"（2026-11-02）。 */
    private static final java.util.regex.Pattern META_THINKING = java.util.regex.Pattern.compile(
            "(按规则|根据规则|按照规则|规则要求|规则说|他这是在|她这是在|他在附和|她在附和|"
            + "可以回短|可以回一句|可以只回|应该回短|我应该回|这轮|本轮|一句短的就行|"
            + "也可以再补|这样回|这么回|符合人设|保持人设|不接话|沉默就行|可以沉默)");

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
        if (recentCache != null) {
            recentCache.evict(tenantId, sessionId);
        }
        log.info("Deleted session {} (tenant {})", sessionId, tenantId);
    }

    @Transactional
    public void clear(String tenantId, String sessionId) {
        Session s = getOrThrow(tenantId, sessionId);
        messageRepository.deleteBySessionId(sessionId);
        s.setStatus("cleared");
        sessionRepository.save(s);
        if (recentCache != null) {
            recentCache.evict(tenantId, sessionId);
        }
    }

    // ---- 内部 ----

    private Message buildMessage(String tenantId, String sessionId, String runId,
                                 int turnNo, int seqNo, String role, String text, String model) {
        return buildMessage(tenantId, sessionId, runId, turnNo, seqNo, role, text, model, null);
    }

    /**
     * 带工具调用 ID 的版本（R10）：{@code role=tool} 时必须带上，
     * 否则读回的历史里工具结果与调用对不上，上游会判非法。
     */
    private Message buildMessage(String tenantId, String sessionId, String runId,
                                 int turnNo, int seqNo, String role, String text, String model,
                                 String toolCallId) {
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
                .toolCallId(toolCallId)
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
                m.getRole(), m.textContent(), m.getModel(), m.getCreatedAt(), m.getToolCallId());
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}