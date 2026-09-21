package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SysDictType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 数据字典类型仓储。
 */
public interface SysDictTypeRepository extends JpaRepository<SysDictType, Long> {

    Optional<SysDictType> findByDictTypeId(String dictTypeId);

    Optional<SysDictType> findByTenantIdAndTypeCode(String tenantId, String typeCode);

    boolean existsByTenantIdAndTypeCode(String tenantId, String typeCode);

    /** 管理界面用：按编码升序，顺序稳定便于对照。 */
    List<SysDictType> findByTenantIdOrderByTypeCodeAsc(String tenantId);

    long countByTenantId(String tenantId);
}
