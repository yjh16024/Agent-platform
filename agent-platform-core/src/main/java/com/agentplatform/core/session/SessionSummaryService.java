package com.agentplatform.core.session;

import com.agentplatform.model.entity.Message;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 中期记忆：会话早期摘要（rollup）。
 * <p>
 * 上下文窗口只回放最近 N 轮；更早的消息会被截断丢弃。本服务把这些「即将被截断」的早期轮次
 * 压缩成一段摘要存到 {@code session_def.summary}，运行入口在历史较长时把它拼进系统提示词，
 * 使跨长对话的早期信息仍可被模型感知（不丢"记忆"，只丢逐字细节）。
 * </p>
 * <p>
 * <b>摘要策略</b>：当前为<b>离线要点式</b>（抽取早期 user 消息要点，无 Key 也可用、零网络）；
 * 语义层调用 LLM 可后续在同一方法替换，不改变调用方。若某轮 LLM 不可用，回退本策略即可。
 * </p>
 * <p><b>触发</b>：懒生成——仅当轮次超过 {@code ROLLUP_MIN_TURNS} 且未被现有摘要覆盖时才 rollup，
 * 幂等（{@code summary_turn} 记录已覆盖到的轮次）。</p>
 */
@Slf4j
@Service
public class SessionSummaryService {

    /** 超过该轮次才考虑摘要（此时最近窗口才会开始截断早期内容）。 */
    static final int ROLLUP_MIN_TURNS = 40;

    /** 保留最近轮次不进入摘要（与运行时历史窗口保持一致量级）。 */
    static final int ROLLUP_KEEP_TURNS = 20;

    /** 参与摘要的最多早期消息条数（避免超长会话全量压缩）。 */
    static final int EARLY_MAX_MSGS = 60;

    /** 摘要最多列出要点条数与每点字数。 */
    static final int POINT_MAX = 14;
    static final int POINT_CHARS = 120;

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public SessionSummaryService(SessionRepository sessionRepository, MessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 返回会话的早期摘要文本；无（或会话太短/无需）返回 {@code null}。
     * <p>已有可用摘要则直接返回；未覆盖到最新截断点时懒生成并持久化。</p>
     */
    public String summaryFor(Session session) {
        if (session == null) {
            return null;
        }
        try {
            int latestTurn = messageRepository.maxTurnNo(session.getSessionId());
            if (latestTurn < ROLLUP_MIN_TURNS) {
                // 历史还不够长，早期内容仍在窗口内 → 直接用（可能 null）
                return existing(session);
            }
            int covered = session.getSummaryTurn() == null ? 0 : session.getSummaryTurn();
            int targetTurn = latestTurn - ROLLUP_KEEP_TURNS;
            if (covered >= targetTurn) {
                return existing(session);
            }
            return rollup(session, targetTurn);
        } catch (Exception e) {
            log.warn("Session summary skipped for {}: {}", session.getSessionId(), e.getMessage());
            return null;
        }
    }

    private String existing(Session session) {
        String summary = session.getSummary();
        return summary == null || summary.isBlank() ? null : summary;
    }

    private String rollup(Session session, int targetTurn) {
        List<Message> early = messageRepository.findBySessionIdOrderByTurnNoAscSeqNoAsc(session.getSessionId())
                .stream()
                .filter(m -> m.getTurnNo() != null && m.getTurnNo() <= targetTurn)
                .limit(EARLY_MAX_MSGS)
                .toList();
        if (early.isEmpty()) {
            return existing(session);
        }
        String text = pointwiseSummary(early);
        if (text == null || text.isBlank()) {
            return existing(session);
        }
        session.setSummary(text);
        session.setSummaryTurn(targetTurn);
        sessionRepository.save(session);
        log.info("Rolled up session {} early history to turn {} ({} msgs -> summary)",
                session.getSessionId(), targetTurn, early.size());
        return text;
    }

    /** 离线要点式摘要：抽取早期 user 消息首段，逐点列出。 */
    private String pointwiseSummary(List<Message> early) {
        StringBuilder sb = new StringBuilder("【早期会话要点】");
        int shown = 0;
        for (Message m : early) {
            if (m.getRole() == null || !"user".equalsIgnoreCase(m.getRole())) {
                continue;
            }
            String content = m.textContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String point = collapse(content);
            if (point.isEmpty()) {
                continue;
            }
            if (shown > 0) {
                sb.append('\n');
            }
            sb.append("· ").append(point);
            if (++shown >= POINT_MAX) {
                break;
            }
        }
        return shown == 0 ? null : sb.toString();
    }

    private String collapse(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= POINT_CHARS ? t : t.substring(0, POINT_CHARS) + "…";
    }
}
