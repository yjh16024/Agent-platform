package com.agentplatform.model.entity;

import com.agentplatform.model.enums.SysUserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 平台用户。
 * <p>
 * 与既有实体的约定保持一致：{@code id} 是自增物理主键，{@code userId} 是业务 ID
 * （{@code IdGenerator.generate("user")} 生成），对外接口一律只暴露 {@code userId}。
 * </p>
 * <p>
 * 密码只存哈希（{@code PasswordHasher}，BCrypt），任何情况下都不回传、不参与序列化。
 * </p>
 */
@Entity
@Table(name = "sys_user")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 登录名，同租户内唯一（uk_tenant_username）。 */
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** BCrypt 哈希，绝不明文。 */
    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "email", length = 200)
    private String email;

    /** 停用后不允许登录，但历史数据与审计记录保留。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private SysUserStatus status = SysUserStatus.active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
