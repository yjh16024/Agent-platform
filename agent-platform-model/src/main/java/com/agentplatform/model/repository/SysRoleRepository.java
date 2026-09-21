package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 角色仓储。
 */
@Repository
public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    Optional<SysRole> findByRoleId(String roleId);

    Optional<SysRole> findByTenantIdAndRoleCode(String tenantId, String roleCode);

    /** 列表展示：内置角色排前面，其余按编码排序，保证每次打开顺序一致。 */
    List<SysRole> findByTenantIdOrderByBuiltinDescRoleCodeAsc(String tenantId);

    /** 由 token 里的角色码批量取角色（鉴权链路用）。 */
    List<SysRole> findByTenantIdAndRoleCodeIn(String tenantId, Collection<String> roleCodes);

    List<SysRole> findByRoleIdIn(Collection<String> roleIds);

    boolean existsByTenantIdAndRoleCode(String tenantId, String roleCode);

    long countByTenantIdAndBuiltinTrue(String tenantId);
}
