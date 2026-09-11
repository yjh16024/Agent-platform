package com.agentplatform.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GenerationType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
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
 * 工作流定义实体。
 */
@Entity
@Table(name = "workflow_def")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Lob
    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, columnDefinition = "json")
    private Map<String, Object> definition;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "version", length = 32)
    private String version;

    /** 已发布快照（发布时写入，回滚时覆盖 definition）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "published_definition", columnDefinition = "json")
    private Map<String, Object> publishedDefinition;

    /** 已发布的版本号（如 v1.0.0）。 */
    @Column(name = "published_version", length = 32)
    private String publishedVersion;

    /** 最近一次发布时间。 */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}