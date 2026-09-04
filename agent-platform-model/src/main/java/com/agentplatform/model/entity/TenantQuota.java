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
 * 租户配额实体。
 * <p>
 * 对应 {@code tenant_quota} 表，持久化各租户按配额类型（模型调用 / 文件上传 / 插件 等）
 * 的限额与累计用量。当前周期内的实时计数以 Redis INCR + TTL 为准，本表承载限额与
 * 周期末累计值（跨重启可审计）。
 * </p>
 */
@Entity
@Table(name = "tenant_quota")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** model_calls / tokens / files / plugins */
    @Column(name = "quota_type", nullable = false, length = 32)
    private String quotaType;

    /** daily / monthly */
    @Column(name = "period", length = 16)
    @Builder.Default
    private String period = "daily";

    @Column(name = "quota_limit", nullable = false)
    @Builder.Default
    private Long quotaLimit = 0L;

    @Column(name = "used", nullable = false)
    @Builder.Default
    private Long used = 0L;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}