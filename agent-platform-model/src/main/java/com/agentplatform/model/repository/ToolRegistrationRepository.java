package com.agentplatform.model.repository;

import com.agentplatform.model.entity.ToolRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * HTTP 工具注册仓储。
 */
@Repository
public interface ToolRegistrationRepository extends JpaRepository<ToolRegistration, Long> {

    Optional<ToolRegistration> findByTenantIdAndToolName(String tenantId, String toolName);

    List<ToolRegistration> findByTenantIdAndEnabledTrue(String tenantId);

    List<ToolRegistration> findByEnabledTrue();
}
