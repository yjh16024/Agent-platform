package com.agentplatform.model.repository;

import com.agentplatform.model.entity.SkillDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Skill 仓储。
 */
@Repository
public interface SkillRepository extends JpaRepository<SkillDef, Long> {

    Optional<SkillDef> findByTenantIdAndSkillId(String tenantId, String skillId);

    List<SkillDef> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}