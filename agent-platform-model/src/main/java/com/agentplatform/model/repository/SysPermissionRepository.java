package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 权限点仓储。
 * <p>
 * 表内容由 {@code RbacSeeder} 依据 {@code RbacPermission} 枚举同步，见该枚举说明。
 * </p>
 */
@Repository
public interface SysPermissionRepository extends JpaRepository<SysPermission, Long> {

    Optional<SysPermission> findByPermCode(String permCode);

    Optional<SysPermission> findByPermId(String permId);

    /** 权限树：按分组、编码排序（界面直接按返回顺序渲染父子节点）。 */
    List<SysPermission> findAllByOrderByPermGroupAscPermCodeAsc();

    List<SysPermission> findByPermIdIn(Collection<String> permIds);

    List<SysPermission> findByPermCodeIn(Collection<String> permCodes);
}
