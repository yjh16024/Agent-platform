package com.agentplatform.model.repository;

import com.agentplatform.model.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储。
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Optional<KnowledgeBase> findByTenantIdAndKbId(String tenantId, String kbId);

    List<KnowledgeBase> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}