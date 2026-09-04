package com.agentplatform.model.repository;

import com.agentplatform.model.entity.AgentPlugin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent ↔ Plugin 绑定仓储。
 */
@Repository
public interface AgentPluginRepository extends JpaRepository<AgentPlugin, Long> {

    List<AgentPlugin> findByAgentIdAndEnabledTrue(String agentId);

    Optional<AgentPlugin> findByAgentIdAndPluginId(String agentId, String pluginId);

    void deleteByAgentIdAndPluginId(String agentId, String pluginId);

    List<AgentPlugin> findByPluginId(String pluginId);

    /**
     * 删除指定 Agent 的全部插件绑定（智能体被删除时级联清理）。
     */
    void deleteByAgentId(String agentId);
}