package com.agentplatform.model.repository;

import com.agentplatform.model.entity.TenantQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 租户配额仓储。
 */
@Repository
public interface TenantQuotaRepository extends JpaRepository<TenantQuota, Long> {

    Optional<TenantQuota> findByTenantIdAndQuotaTypeAndPeriod(
            String tenantId, String quotaType, String period);

    List<TenantQuota> findByTenantId(String tenantId);
}