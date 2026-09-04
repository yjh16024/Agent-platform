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
 * 插件元数据实体（plugin_def）。
 */
@Entity
@Table(name = "plugin_def")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "plugin_id", nullable = false, length = 64)
    private String pluginId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Lob
    @Column(name = "description")
    private String description;

    @Column(name = "author", length = 128)
    private String author;

    @Column(name = "latest_version", length = 32)
    private String latestVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest", nullable = false, columnDefinition = "json")
    private Map<String, Object> manifest;

    @Column(name = "artifact_uri", length = 500)
    private String artifactUri;

    @Column(name = "artifact_hash", length = 128)
    private String artifactHash;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "visibility", length = 16)
    private String visibility;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scan_result", columnDefinition = "json")
    private Map<String, Object> scanResult;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}