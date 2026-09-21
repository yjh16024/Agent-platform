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
 * 用户-角色关联（多对多）。
 * <p>
 * 刻意做成独立实体而不是 {@code @ManyToMany}：本项目既有实体都是"平坦"的
 * （不建实体间导航关系），保持一致能避免懒加载在无 Session 场景下抛
 * {@code LazyInitializationException} —— 这个项目里多处是手动拼 DTO 的。
 * </p>
 */
@Entity
@Table(name = "sys_user_role")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "role_id", nullable = false, length = 64)
    private String roleId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
