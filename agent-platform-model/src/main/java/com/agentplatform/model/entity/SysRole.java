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
 * 角色。
 * <p>
 * {@code roleCode} 是登录令牌里携带的**稳定标识**（如 {@code admin}）——
 * 一旦下发就不要再改，改了等于让已签发的 token 失效；展示名请改 {@code roleName}。
 * </p>
 * <p>
 * {@code builtin=true} 的内置角色（admin/operator/viewer）由 {@code RbacSeeder} 创建，
 * 界面上不允许删除，避免出现「一个管理员都没有」的锁死状态。
 * </p>
 */
@Entity
@Table(name = "sys_role")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "role_id", nullable = false, length = 64)
    private String roleId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 稳定标识，写进 JWT 的 roles；同租户内唯一。 */
    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 100)
    private String roleName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "builtin", nullable = false)
    @Builder.Default
    private Boolean builtin = false;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
