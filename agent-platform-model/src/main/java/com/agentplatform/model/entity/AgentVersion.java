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
 * 智能体不可变版本快照（发布时插入，永不变更）。
 * <p>支持回滚与 Diff 对比。快照包含全量配置（含提示词哈希）。</p>
 */
@Entity
@Table(name = "agent_version")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "json")
    private Map<String, Object> snapshot;

    @Column(name = "prompt_hash", length = 64)
    private String promptHash;

    @Column(name = "released_by", length = 64)
    private String releasedBy;

    @Column(name = "released_at", insertable = false, updatable = false)
    private LocalDateTime releasedAt;
}