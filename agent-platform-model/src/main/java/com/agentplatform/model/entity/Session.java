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

import java.time.LocalDateTime;

/**
 * 会话实体（需求2：会话历史与上下文管理）。
 * <p>
 * 对应 {@code session_def} 表。Session → Turn → Message 分层中，本实体对应 Session；
 * Turn 由 {@link Message} 的 {@code turn_no} + {@code seq_no} 隐式表达（一轮对话 =
 * 同 turn_no 下的 user/assistant 消息）。
 * </p>
 */
@Entity
@Table(name = "session_def")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "title", length = 500)
    private String title;

    /** active / archived / cleared */
    @Column(name = "status", length = 16)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}