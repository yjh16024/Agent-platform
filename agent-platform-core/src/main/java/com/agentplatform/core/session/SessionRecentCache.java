package com.agentplatform.core.session;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.session.SessionDtos.MessageDto;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话短期记忆缓存（Redis List，最近 N 轮消息）。
 * <p>
 * 供 {@link SessionService#recentMessages} 优先读取，命中即免 DB 全量查询；
 * 每条消息以轻量 JSON 存（仅 role/content/turn_no/seq_no，不带 LocalDateTime，
 * 规避序列化配置差异）。Redis 不可用/异常时全部静默降级（返回 null → 走 DB），
 * 绝不阻断会话主流程——与 {@code QuotaService} 的可选注入范式一致。
 * </p>
 * <p>
 * 一致性策略：{@code recordExchange} 落库后同步 {@link #append} 追加最新一轮并裁剪；
 * 会话删除（archive/clear）时 {@link #evict}；首次读未命中（缓存不存在）则从 DB 回填
 * 最近 {@link #MAX_ITEMS} 条。缓存上限 25 轮（50 条），TTL 24h。
 * </p>
 */
@Slf4j
@Component
public class SessionRecentCache {

    /** 缓存最近消息条数上限（25 轮 × 2）。 */
    public static final int MAX_ITEMS = 50;

    /** 缓存条目 TTL。 */
    private static final Duration TTL = Duration.ofHours(24);

    private static final String PREFIX = "ap:session:recent:";

    /** 可选依赖：Redis 模板（不可用时为 null，自动降级）。 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * 读取最近 max 条（升序）。未命中 / Redis 不可用返回 null（调用方回 DB）。
     * 仅支持 {@code max <= MAX_ITEMS}；请求超过缓存容量视为未命中以保准确性。
     */
    public List<MessageDto> get(String tenantId, String sessionId, int max) {
        if (redisTemplate == null || max <= 0 || max > MAX_ITEMS) {
            return null;
        }
        try {
            List<String> items = redisTemplate.opsForList().range(key(tenantId, sessionId), 0, max - 1L);
            if (items == null || items.isEmpty()) {
                return null;   // key 不存在或为空 → miss
            }
            List<MessageDto> out = new ArrayList<>(items.size());
            for (String raw : items) {
                JsonNode n = JsonUtils.toJsonNode(raw);
                Integer turnNo = n.path("turn_no").isInt() ? n.path("turn_no").asInt() : null;
                Integer seqNo = n.path("seq_no").isInt() ? n.path("seq_no").asInt() : null;
                out.add(new MessageDto(
                        null, sessionId, null,
                        turnNo, seqNo,
                        n.path("role").asText("user"),
                        n.path("content").asText(""),
                        null, null));
            }
            return out;
        } catch (Exception e) {
            // Redis 不可用或数据损坏：整段降级，下一次读会从 DB 回填
            log.debug("[session-cache] read recent failed, fallback to DB: {}", e.getMessage());
            evict(tenantId, sessionId);
            return null;
        }
    }

    /** 回填最近若干条（首次未命中时由 DB 结果写入；整体覆盖）。 */
    public void put(String tenantId, String sessionId, List<MessageDto> messages) {
        if (redisTemplate == null || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            String k = key(tenantId, sessionId);
            redisTemplate.delete(k);
            messages.forEach(m -> redisTemplate.opsForList().rightPush(k, itemJson(m)));
            redisTemplate.expire(k, TTL);
        } catch (Exception e) {
            log.debug("[session-cache] write recent failed: {}", e.getMessage());
        }
    }

    /** 追加最新一轮（user + assistant），仅当缓存已存在；超出上限裁掉最旧。 */
    public void append(String tenantId, String sessionId, int turnNo,
                       String userText, String assistantText) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String k = key(tenantId, sessionId);
            Long size = redisTemplate.opsForList().size(k);
            if (size == null || size == 0) {
                return;   // 缓存不存在/被清理：交由下次读路径从 DB 重建，避免出现半截缓存
            }
            redisTemplate.opsForList().rightPush(k, itemJson(1, turnNo, "user", userText));
            redisTemplate.opsForList().rightPush(k, itemJson(2, turnNo, "assistant", assistantText));
            redisTemplate.opsForList().trim(k, -MAX_ITEMS, -1);   // 只保留尾部 MAX_ITEMS 条
            redisTemplate.expire(k, TTL);
        } catch (Exception e) {
            log.debug("[session-cache] append recent failed: {}", e.getMessage());
        }
    }

    /** 删除会话缓存（物理删除 / 清空会话时调用，保证与 DB 一致）。 */
    public void evict(String tenantId, String sessionId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(key(tenantId, sessionId));
        } catch (Exception e) {
            log.debug("[session-cache] evict failed: {}", e.getMessage());
        }
    }

    private static String key(String tenantId, String sessionId) {
        return PREFIX + tenantId + ":" + sessionId;
    }

    private static String itemJson(MessageDto m) {
        return itemJson(m.seqNo(), m.turnNo(), m.role(), m.content());
    }

    private static String itemJson(Integer seqNo, Integer turnNo, String role, String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", role == null ? "user" : role);
        item.put("content", content == null ? "" : content);
        item.put("turn_no", turnNo == null ? 0 : turnNo);
        item.put("seq_no", seqNo == null ? 0 : seqNo);
        return JsonUtils.toJson(item);
    }
}
