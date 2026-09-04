package com.agentplatform.core.session;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话相关 DTO（强类型 record，序列化 camelCase）。
 */
public final class SessionDtos {

    private SessionDtos() {
    }

    /** 会话摘要（列表项）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionSummary(
            String sessionId,
            String tenantId,
            String agentId,
            String userId,
            String title,
            String status,
            int messageCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    /** 消息（历史项）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageDto(
            String messageId,
            String sessionId,
            String runId,
            Integer turnNo,
            Integer seqNo,
            String role,
            String content,
            String model,
            LocalDateTime createdAt
    ) {
    }

    /** 会话详情（会话元数据 + 全部消息）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionDetail(
            SessionSummary session,
            List<MessageDto> messages
    ) {
    }

    /** 创建会话请求。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateSessionRequest(
            String agentId,
            String userId,
            String title
    ) {
    }

    /** 更新会话请求（标题 / 状态）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateSessionRequest(
            String title,
            String status
    ) {
    }
}