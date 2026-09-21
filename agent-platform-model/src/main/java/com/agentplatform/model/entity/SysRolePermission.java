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
 * 角色-权限关联（多对多）。
 * <p>
 * 存 {@code permId}（业务 ID）而不是 {@code permCode}：权限码是给人看的、
 * 将来可能重命名，而校验时用的是稳定的 ID 关联，改名不会影响已配置的角色。
 * </p>
 */
@Entity
@Table(name = "sys_role_permission")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysRolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "role_id", nullable = false, length = 64)
    private String roleId;

    @Column(name = "perm_id", nullable = false, length = 64)
    private String permId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
