package com.agentplatform.model.entity;

import com.agentplatform.model.enums.AgentStatus;
import com.agentplatform.model.enums.Visibility;
import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.ModelBinding;
import com.agentplatform.model.record.Persona;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * 智能体定义实体（当前值表）。
 * <p>
 * JSON 字段通过 Hibernate {@code @JdbcTypeCode(SqlTypes.JSON)} 与
 * {@link Persona} / {@link GenerationConfig} / {@link Capabilities} Record 无缝映射。
 * </p>
 */
@Entity
@Table(name = "agent_def")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "avatar", length = 500)
    private String avatar;

    @Lob
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "persona", nullable = false, columnDefinition = "json")
    private Persona persona;

    @Lob
    @Column(name = "system_prompt", nullable = false, columnDefinition = "mediumtext")
    private String systemPrompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generation_config", nullable = false, columnDefinition = "json")
    private GenerationConfig generationConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities", columnDefinition = "json")
    private Capabilities capabilities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_binding", columnDefinition = "json")
    private ModelBinding modelBinding;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    @Builder.Default
    private AgentStatus status = AgentStatus.draft;

    @Column(name = "visibility", length = 16)
    @Builder.Default
    private Visibility visibility = Visibility.private_;

    @Column(name = "current_version", length = 32)
    private String currentVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}