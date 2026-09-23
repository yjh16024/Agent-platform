package com.agentplatform.core.memory;

import com.agentplatform.core.rag.retriever.EmbeddingService;
import com.agentplatform.core.rag.retriever.VectorStore;
import com.agentplatform.model.entity.Message;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.repository.MessageRepository;
import com.agentplatform.model.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量记忆：历史对话的语义召回（多层级记忆的第四层）。
 *
 * <p>前三层都是"把最近/重要的内容固定塞进上下文"，本层不同 —— 它按**语义相关**挑：
 * 用户这次问"上次那个接口超时怎么解决的"，能召回几天前另一个会话里讨论过的结论，
 * 而短期缓存（24h）和会话摘要（同一会话内）都覆盖不到。</p>
 *
 * <h3>核心风险：无关历史污染上下文（方案里明确警告过）</h3>
 * 向量召回是"相似"而不是"相关"，阈值一松就会把八竿子打不着的旧对话拉进来，
 * 反而干扰当前回答。所以这里有四道闸：
 * <ol>
 *   <li>{@code min-score}（默认 0.75，比 RAG 检索的 0 高得多）；</li>
 *   <li>{@code top-k}（默认 3 条）；</li>
 *   <li>{@code max-chars}（拼进提示词的总字符上限）；</li>
 *   <li><b>排除当前会话</b> —— 当前会话的内容已经通过短期缓存/历史回放进了上下文，
 *       再召回一遍等于同一段话说两遍，白白挤占窗口。</li>
 * </ol>
 *
 * <h3>为什么用 id 前缀做归属判断，而不是 metadata 过滤</h3>
 * {@code VectorStore} 的两种实现在 metadata 上**能力不对等**：
 * in-memory 会保留完整的 metadata，而 {@code MilvusVectorStore} 的 collection schema
 * 只声明了 {@code kb_id}/{@code doc_id} 两个标量字段，其余键**不会落库**。
 * 若靠 metadata 判断"这条向量属于谁"，功能在 Milvus 部署下会**静默失效**
 * （in-memory 上测试全好、一上 Milvus 就召回空）。所以改为把归属信息编进**id**：
 * <pre>mem|{tenantId}|{userId}|{sessionId}|{messageId}</pre>
 * id 是两种实现都必然持久化的字段，按前缀过滤即可，行为完全一致。
 * 同理，召回后<b>回数据库取原文</b>而不是把文本塞进 metadata。
 *
 * <h3>写入不阻塞主链路、读取失败可降级</h3>
 * 索引走虚拟线程异步执行（方案要求"写入异步不阻塞主链路"）；
 * 召回挂在对话主链路上，任何异常都退化成"这轮没有向量记忆"，绝不让用户发不出消息。
 */
@Slf4j
@Service
public class ConversationMemoryService {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;

    public ConversationMemoryService(VectorStore vectorStore,
                                     EmbeddingService embeddingService,
                                     MessageRepository messageRepository,
                                     SessionRepository sessionRepository) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
    }

    /** 总开关（未配置 embedding 的部署可关掉，避免每轮对话都白跑一次 embed）。 */
    @Value("${agent-platform.memory.vector.enabled:true}")
    private boolean enabled;

    /**
     * 相似度下限。默认 0.75 —— 比 RAG 检索的 0 高得多。
     * 这个值偏高是刻意的：召回错了比召回不到更糟（会把无关旧对话灌进上下文）。
     */
    @Value("${agent-platform.memory.vector.min-score:0.75}")
    private double minScore;

    /** 召回条数上限。 */
    @Value("${agent-platform.memory.vector.top-k:3}")
    private int topK;

    /** 拼进提示词的总字符上限。 */
    @Value("${agent-platform.memory.vector.max-chars:1200}")
    private int maxChars;

    /** 过短的文本不索引（"好的"「嗯」这类没有语义价值，索引了只会增加噪声）。 */
    @Value("${agent-platform.memory.vector.min-text-chars:8}")
    private int minTextChars;

    /** 单条索引文本的截断长度（长文只取前段，主要语义通常在开头）。 */
    @Value("${agent-platform.memory.vector.index-max-chars:500}")
    private int indexMaxChars;

    /** 索引扫描用户历史会话的条数上限（重建时防止一次拉爆内存）。 */
    @Value("${agent-platform.memory.vector.max-sessions:200}")
    private int maxSessions;

    private static final String ID_NS = "mem";
    private static final String ID_SEP = "|";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ------------------------------------------------------------------ 索引（写入）

    /**
     * 异步索引一条用户消息（**不阻塞主链路**）。
     *
     * <p>为什么只索引 user 消息而不索引 assistant 回复：回复通常长得多（一次可能上千字），
     * 全都向量化会让存储与检索成本翻倍，而"用户当时问了什么"才是召回时最有效的语义锚点。
     * 召回到 user 消息后，其所在会话的上下文本来就还能经会话继续查。</p>
     */
    public void indexAsync(String tenantId, String userId, String sessionId, String text) {
        if (!enabled || isBlank(userId) || isBlank(sessionId) || isBlank(text)) {
            return;
        }
        if (text.trim().length() < minTextChars) {
            return;
        }
        final String tenant = normalizeTenant(tenantId);
        String trimmed = text.trim();
        if (trimmed.length() > indexMaxChars) {
            trimmed = trimmed.substring(0, indexMaxChars);
        }
        final String indexed = trimmed;

        // 虚拟线程：索引失败绝不影响对话（用户消息早就返回了）
        Thread.startVirtualThread(() -> {
            try {
                // 回查刚写入的那条消息拿 messageId。为什么不在调用点直接传：
                // messageId 由 SessionService.recordExchange 内部生成，调用方拿不到；
                // 而 id 里必须用 messageId —— 清除与重建都以"数据库里的消息"为权威清单，
                // 若这里自造一个 id，那两个操作就再也删不掉这条向量。
                // 本方法是索引前脚刚写完之后调用的，所以"该会话最新的 user 消息"就是它。
                String messageId = latestUserMessageId(sessionId);
                if (messageId == null) {
                    return;
                }
                float[] vec = embeddingService.embedQuery(indexed);
                if (vec == null || vec.length == 0) {
                    return;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("type", "conversation");
                metadata.put("tenant_id", tenant);
                metadata.put("user_id", userId);
                metadata.put("session_id", sessionId);
                metadata.put("message_id", messageId);
                // metadata 只为在 in-memory 实现下便于调试；归属判断一律走 id 前缀
                //（Milvus 不会持久化这些键，见类注释）
                vectorStore.upsert(docId(tenant, userId, sessionId, messageId), vec, metadata);
            } catch (Exception e) {
                log.debug("[memory] 索引对话记忆失败（已忽略）：{}", e.getMessage());
            }
        });
    }

    /** 取会话里最新一条 user 消息的业务 ID（取不到返回 null）。 */
    private String latestUserMessageId(String sessionId) {
        try {
            var page = messageRepository.findBySessionIdOrderByTurnNoDescSeqNoDesc(
                    sessionId, org.springframework.data.domain.PageRequest.of(0, 4));
            for (Message m : page.getContent()) {
                if ("user".equalsIgnoreCase(m.getRole())) {
                    return m.getMessageId();
                }
            }
        } catch (Exception e) {
            log.debug("[memory] 回查 messageId 失败：{}", e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------ 召回（读取）

    /**
     * 按语义召回该用户的历史对话片段；无命中或出错时返回 {@code null}。
     *
     * @param excludeSessionId 当前会话 ID：**其内容已在上下文里，必须排除**（见类注释第 4 条闸门）
     */
    public String recall(String tenantId, String userId, String query,
                         String excludeSessionId) {
        if (!enabled || isBlank(userId) || isBlank(query)) {
            return null;
        }
        String tenant = normalizeTenant(tenantId);
        try {
            float[] vec = embeddingService.embedQuery(query.trim());
            if (vec == null || vec.length == 0) {
                return null;
            }
            // 多取一些再过滤：前缀过滤与去重都会淘汰一部分，按 topK 直接取容易不够
            List<VectorStore.VectorMatch> matches =
                    vectorStore.similaritySearch(vec, Math.max(topK * 4, 12), minScore);
            if (matches == null || matches.isEmpty()) {
                return null;
            }

            String prefix = idPrefix(tenant, userId);
            Map<String, String> messageIds = new LinkedHashMap<>();   // messageId → sessionId
            for (VectorStore.VectorMatch m : matches) {
                String id = m.id();
                if (id == null || !id.startsWith(prefix)) {
                    continue;   // 别的租户/用户的向量，或知识库 chunk
                }
                String[] parts = id.split("\\" + ID_SEP);
                // mem|tenant|user|session|messageId
                if (parts.length < 5) {
                    continue;
                }
                String sessionId = parts[3];
                String messageId = parts[4];
                if (sessionId.equals(excludeSessionId)) {
                    continue;   // 当前会话，排除
                }
                if (messageIds.size() >= topK) {
                    break;
                }
                messageIds.putIfAbsent(messageId, sessionId);
            }
            if (messageIds.isEmpty()) {
                return null;
            }

            // 回库取原文（不依赖 metadata，见类注释）
            List<Message> messages = messageRepository.findByMessageIdIn(new ArrayList<>(messageIds.keySet()));
            if (messages == null || messages.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n\n## 相关历史对话（按语义从更早的会话中召回，仅供参考）\n")
                    .append("以下内容来自该用户**过去**的对话，可能与当前话题相关。")
                    .append("若与当前话题无关请忽略；不要把它当作当前会话的上下文来复述。\n");

            int used = 0;
            for (Message msg : messages) {
                String text = messageText(msg);
                if (text == null || text.isBlank()) {
                    continue;
                }
                String when = msg.getCreatedAt() == null ? "" : msg.getCreatedAt().toLocalDate().format(DAY) + " ";
                String line = "- " + when + text.trim() + "\n";
                if (used + line.length() > maxChars) {
                    break;
                }
                sb.append(line);
                used += line.length();
            }
            if (used == 0) {
                return null;
            }
            log.debug("[memory] 召回历史对话 {} 条（agent 维度无关，按用户跨会话）", messageIds.size());
            return sb.toString();
        } catch (Exception e) {
            // embedding 未配置、上游报错、向量库不可用……一律当成"这轮没有向量记忆"
            log.warn("[memory] 召回历史对话失败（本轮跳过）：{}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 清除 / 重建

    /**
     * 一键清除该用户的全部向量记忆，返回处理的会话数。
     *
     * <p>实现上"反向"做：先由会话查出该用户的所有 user 消息 ID，再按 id 逐个删除。
     * 之所以不遍历向量库删除，是因为 {@code VectorStore} **没有"列出全部"的接口**
     * （只有相似度检索）—— 对一个只支持"按向量查"的抽象，反向枚举是不成立的。
     * 顺带这样也更稳：数据库里的消息才是权威清单。</p>
     */
    public int clearAll(String tenantId, String userId) {
        if (isBlank(userId)) {
            return 0;
        }
        String tenant = normalizeTenant(tenantId);
        try {
            List<Session> sessions = sessionRepository.findByTenantIdAndUserId(tenant, userId);
            if (sessions == null || sessions.isEmpty()) {
                return 0;
            }
            List<String> sessionIds = sessions.stream().map(Session::getSessionId).toList();
            List<Message> messages = messageRepository.findBySessionIdInAndRole(sessionIds, "user");
            for (Message m : messages) {
                vectorStore.delete(docId(tenant, userId, m.getSessionId(), m.getMessageId()));
            }
            log.info("[memory] 用户 {} 清除了向量记忆 {} 条", userId, messages.size());
            return messages.size();
        } catch (Exception e) {
            log.warn("[memory] 清除向量记忆失败：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 重建该用户的向量索引，返回索引条数。
     *
     * <p><b>为什么需要这个入口</b>：默认的 in-memory 向量库是**进程内 Map**，
     * 桌面版每次重启都会清空 —— 没有重建入口的话，"向量记忆"在桌面版上永远是空的。
     * （Milvus 部署下数据是持久的，本方法只在索引丢失时才需要。）</p>
     */
    public int rebuild(String tenantId, String userId) {
        if (!enabled || isBlank(userId)) {
            return 0;
        }
        String tenant = normalizeTenant(tenantId);
        List<Session> sessions;
        try {
            sessions = sessionRepository.findByTenantIdAndUserId(tenant, userId);
        } catch (Exception e) {
            log.warn("[memory] 重建索引失败（查会话出错）：{}", e.getMessage());
            return 0;
        }
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        if (sessions.size() > maxSessions) {
            // 只取最近活跃的一部分：一次性索引全部历史会打爆内存与 embedding 配额
            sessions = sessions.stream()
                    .sorted((a, b) -> {
                        var ta = a.getUpdatedAt();
                        var tb = b.getUpdatedAt();
                        if (ta == null || tb == null) {
                            return 0;
                        }
                        return tb.compareTo(ta);
                    })
                    .limit(maxSessions)
                    .toList();
        }

        List<String> sessionIds = sessions.stream().map(Session::getSessionId).toList();
        List<Message> messages;
        try {
            messages = messageRepository.findBySessionIdInAndRole(sessionIds, "user");
        } catch (Exception e) {
            log.warn("[memory] 重建索引失败（查消息出错）：{}", e.getMessage());
            return 0;
        }

        int indexed = 0;
        for (Message m : messages) {
            String text = messageText(m);
            if (text == null || text.trim().length() < minTextChars) {
                continue;
            }
            String trimmed = text.trim();
            if (trimmed.length() > indexMaxChars) {
                trimmed = trimmed.substring(0, indexMaxChars);
            }
            try {
                float[] vec = embeddingService.embedQuery(trimmed);
                if (vec == null || vec.length == 0) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("type", "conversation");
                metadata.put("tenant_id", tenant);
                metadata.put("user_id", userId);
                metadata.put("session_id", m.getSessionId());
                metadata.put("message_id", m.getMessageId());
                vectorStore.upsert(docId(tenant, userId, m.getSessionId(), m.getMessageId()), vec, metadata);
                indexed++;
            } catch (Exception e) {
                // 单条失败不中断整体（否则一条脏数据就让重建永远做不完）
                log.debug("[memory] 重建时跳过一条（{}）：{}", m.getMessageId(), e.getMessage());
            }
        }
        log.info("[memory] 用户 {} 重建向量索引完成：{} 条", userId, indexed);
        return indexed;
    }

    // ------------------------------------------------------------------ 小工具

    /** 向量 ID（归属信息编进 ID，见类注释）。 */
    private static String docId(String tenant, String userId, String sessionId, String messageId) {
        return idPrefix(tenant, userId) + sessionId + ID_SEP + messageId;
    }

    private static String idPrefix(String tenant, String userId) {
        return ID_NS + ID_SEP + tenant + ID_SEP + userId + ID_SEP;
    }

    /**
     * 取消息文本。
     *
     * <p>{@code Message.content} 是 parts[] 形式的 JSON（{@code {"type":"text","text":...}}），
     * 不是纯字符串；实体上已有便捷方法 {@code textContent()}，这里优先用它，
     * 取不到再退回读 map，避免依赖实体实现的细节变化。</p>
     */
    private static String messageText(Message m) {
        if (m == null) {
            return null;
        }
        try {
            String t = m.textContent();
            if (t != null && !t.isBlank()) {
                return t;
            }
        } catch (Exception ignored) {
            // 继续尝试下面的兜底
        }
        Map<String, Object> content = m.getContent();
        if (content == null) {
            return null;
        }
        Object text = content.get("text");
        return text == null ? null : String.valueOf(text);
    }

    private static String normalizeTenant(String tenantId) {
        return isBlank(tenantId) ? "default" : tenantId.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
