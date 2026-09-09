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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * HTTP 工具注册持久化实体（tool_registration）。
 * <p>保存动态注册的 HTTP 工具定义，启动时重建，避免重启/重构后丢失。</p>
 */
@Entity
@Table(name = "tool_registration")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "endpoint", nullable = false, length = 1000)
    private String endpoint;

    @Column(name = "method", nullable = false, length = 16)
    private String method;

    /** 入参 JSON Schema（可空）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters")
    private Map<String, Object> parameters;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
