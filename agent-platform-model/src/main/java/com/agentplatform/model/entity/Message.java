package com.agentplatform.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GenerationType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 会话消息实体（原文落 JSON 列，轮次 + 序列）。
 * <p>
 * 对应 {@code message} 表。{@code content} 存统一消息模型 parts[] 结构的 JSON——
 * 单条文本统一为 {@code {"type":"text","text":...}}，多模态为 {@code {"type":"parts","parts":[...]}}，
 * 与 {@code /agent/run} 的 {@code messages[{role,content}]} 一一对应。
 * </p>
 */
@Entity
@Table(name = "message")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "message_id", nullable = false, length = 64)
    private String messageId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "run_id", length = 64)
    private String runId;

    /** 轮次。 */
    @Column(name = "turn_no", nullable = false)
    @Builder.Default
    private Integer turnNo = 1;

    /** 轮次内序号。 */
    @Column(name = "seq_no", nullable = false)
    @Builder.Default
    private Integer seqNo = 1;

    /** system/user/assistant/tool */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    /** parts[] 统一消息内容（JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "json")
    private Map<String, Object> content;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 提取纯文本内容（兼容 {"type":"text"} 与 {"type":"parts"} 两种结构）。
     */
    public String textContent() {
        if (content == null) {
            return "";
        }
        Object text = content.get("text");
        if (text != null) {
            return String.valueOf(text);
        }
        return content.toString();
    }
}