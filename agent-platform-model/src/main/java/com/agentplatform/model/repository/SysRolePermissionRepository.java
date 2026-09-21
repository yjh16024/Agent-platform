package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 角色-权限关联仓储。
 * <p>
 * 鉴权链路的性能关键点：{@link #findByRoleIdIn} 是"由角色码换权限集"的**唯一一次查库**
 * （结果进内存缓存），所以这里刻意用 roleId 批量查，避免逐角色 N 次查询。
 * </p>
 */
@Repository
public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, Long> {

    List<SysRolePermission> findByRoleId(String roleId);

    List<SysRolePermission> findByRoleIdIn(Collection<String> roleIds);

    /** 重配角色权限前的清理（与 insert 同事务）。 */
    void deleteByRoleId(String roleId);

    /** 权限点被移除时清理引用（RbacSeeder 同步时调用）。 */
    void deleteByPermId(String permId);
}
