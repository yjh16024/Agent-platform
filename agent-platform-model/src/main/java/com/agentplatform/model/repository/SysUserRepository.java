package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysUser;
import com.agentplatform.model.enums.SysUserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户仓储。
 * <p>
 * 所有按「登录名」的查询都必须带 {@code tenantId}：用户名的唯一约束是
 * {@code (tenant_id, username)}，不同租户下同名用户是合法的两条记录。
 * </p>
 */
@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUserId(String userId);

    /** 登录用：同租户内按用户名取（跨租户同名不会被误命中）。 */
    Optional<SysUser> findByTenantIdAndUsername(String tenantId, String username);

    List<SysUser> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    boolean existsByTenantIdAndUsername(String tenantId, String username);

    long countByTenantIdAndStatus(String tenantId, SysUserStatus status);
}
