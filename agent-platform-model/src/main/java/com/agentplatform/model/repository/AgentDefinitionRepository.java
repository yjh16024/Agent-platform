package com.agentplatform.model.repository;

import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.enums.AgentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 智能体定义仓储。
 */
@Repository
public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, Long> {

    /**
     * 按业务键（tenant_id + agent_id）查询。
     */
    Optional<AgentDefinition> findByTenantIdAndAgentId(String tenantId, String agentId);

    /**
     * 分页查询（按租户 + 名称模糊 + 状态过滤）。
     * <p>未显式指定状态时<b>默认排除 archived</b>，避免「删除/归档后仍出现在列表」；
     * 如需查看归档需显式传 status=archived。</p>
     */
    @Query("""
            SELECT a FROM AgentDefinition a
            WHERE a.tenantId = :tenantId
              AND (:q IS NULL OR :q = '' OR a.name LIKE CONCAT('%', :q, '%'))
              AND (:status IS NOT NULL OR a.status <> 'archived')
              AND (:status IS NULL OR a.status = :status)
            ORDER BY a.updatedAt DESC
            """)
    Page<AgentDefinition> search(
            @Param("tenantId") String tenantId,
            @Param("q") String q,
            @Param("status") AgentStatus status,
            Pageable pageable);

    /**
     * 是否存在同名 Agent。
     */
    boolean existsByTenantIdAndName(String tenantId, String name);

    /** 统计报表用：租户下的智能体总数。 */
    long countByTenantId(String tenantId);
}