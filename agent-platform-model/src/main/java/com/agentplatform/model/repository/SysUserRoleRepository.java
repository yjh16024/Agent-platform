package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 用户-角色关联仓储。
 */
@Repository
public interface SysUserRoleRepository extends JpaRepository<SysUserRole, Long> {

    List<SysUserRole> findByUserId(String userId);

    List<SysUserRole> findByUserIdIn(Collection<String> userIds);

    List<SysUserRole> findByRoleId(String roleId);

    /** 重新分配用户角色前的清理（与下面的 insert 在同一个事务里）。 */
    void deleteByUserId(String userId);

    /** 删除角色前解除引用，避免留下悬空的 user_id → role_id。 */
    void deleteByRoleId(String roleId);
}
