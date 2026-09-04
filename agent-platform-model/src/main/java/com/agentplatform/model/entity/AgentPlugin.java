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
 * Agent ↔ Plugin 绑定实体（"插入插件"关系，实例级配置）。
 */
@Entity
@Table(name = "agent_plugin")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlugin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "plugin_id", nullable = false, length = 64)
    private String pluginId;

    @Column(name = "version", length = 32)
    private String version;

    @Column(name = "enabled")
    private Boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "json")
    private Map<String, Object> config;

    @Column(name = "attached_at", insertable = false, updatable = false)
    private LocalDateTime attachedAt;

    @Column(name = "attached_by", length = 64)
    private String attachedBy;
}