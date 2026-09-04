package com.agentplatform.model.repository;

import com.agentplatform.model.entity.WorkflowDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工作流仓储。
 */
@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowDef, Long> {

    Optional<WorkflowDef> findByTenantIdAndWorkflowId(String tenantId, String workflowId);

    List<WorkflowDef> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}