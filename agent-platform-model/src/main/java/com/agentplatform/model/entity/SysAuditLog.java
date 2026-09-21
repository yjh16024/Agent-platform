package com.agentplatform.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 操作日志 / 审计记录。
 *
 * <p>与 {@code LogIndex}（运行日志）的区别见 V17 迁移文件的说明：本表记录**人的操作**。</p>
 *
 * <p><b>不可变</b>：只插入、不更新。因此实体上没有任何 setter 之外的修改语义，
 * 表也没有 {@code updated_at}。清理只能按时间整段删除。</p>
 */
@Entity
@Table(name = "sys_audit_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "audit_id", nullable = false, length = 64)
    private String auditId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "username", length = 64)
    private String username;

    /**
     * 操作当时的角色快照（逗号分隔）。
     * <p>刻意不关联 {@code sys_user_role}：审计要的是"**当时**是谁"，而不是"现在是谁"。</p>
     */
    @Column(name = "roles", length = 200)
    private String roles;

    /** 业务动作名，如「删除用户」；没有注解时是 {@code "POST /api/v1/..."}。 */
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "uri", nullable = false, length = 500)
    private String uri;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "success", nullable = false)
    @Builder.Default
    private Boolean success = true;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** 补充描述（由 {@code @AuditLog} 注解提供）。**绝不记录请求体**，见 AuditAspect 的说明。 */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
