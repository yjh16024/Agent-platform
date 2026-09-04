package com.agentplatform.model.repository;

import com.agentplatform.model.entity.AgentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 智能体版本快照仓储。
 */
@Repository
public interface AgentVersionRepository extends JpaRepository<AgentVersion, Long> {

    /**
     * 查询指定 Agent 的全部版本（按发布时间倒序）。
     */
    List<AgentVersion> findByAgentIdOrderByReleasedAtDesc(String agentId);

    /**
     * 查询指定 Agent 的指定版本。
     */
    Optional<AgentVersion> findByAgentIdAndVersion(String agentId, String version);

    /**
     * 统计 Agent 版本数。
     */
    long countByAgentId(String agentId);

    /**
     * 删除指定 Agent 的全部版本快照（智能体被删除时级联清理）。
     */
    void deleteByAgentId(String agentId);
}