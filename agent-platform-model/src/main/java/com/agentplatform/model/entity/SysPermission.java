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
 * 权限点（系统全局清单，不带租户）。
 * <p>
 * 唯一的权威来源是代码里的 {@code RbacPermission} 枚举；本表由 {@code RbacSeeder}
 * 在启动时**同步**（新增的补、消失的删）—— 所以不要手工往这张表插数据，
 * 手工数据会在下次启动同步时被判定为「枚举里没有」而删除。
 * </p>
 */
@Entity
@Table(name = "sys_permission")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "perm_id", nullable = false, length = 64)
    private String permId;

    /** 权限码，如 {@code agent:write}；注解与校验都基于它。 */
    @Column(name = "perm_code", nullable = false, length = 100)
    private String permCode;

    @Column(name = "perm_name", nullable = false, length = 100)
    private String permName;

    /** 分组名（界面上权限树的父节点，如「智能体」「知识库」）。 */
    @Column(name = "perm_group", nullable = false, length = 64)
    private String permGroup;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
