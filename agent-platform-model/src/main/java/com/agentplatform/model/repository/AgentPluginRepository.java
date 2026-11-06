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

    /**
     * 全库处于启用状态的绑定（2026-10-22）。
     *
     * <p>启动时用它恢复插件工具注册 —— 插件工具的注册表在内存里，重启即空，
     * 而挂载关系只存在这张表。详见 {@code BuiltinPluginRegistrar#restoreAttachments}。</p>
     */
    List<AgentPlugin> findByEnabledTrue();

    Optional<AgentPlugin> findByAgentIdAndPluginId(String agentId, String pluginId);

    void deleteByAgentIdAndPluginId(String agentId, String pluginId);

    List<AgentPlugin> findByPluginId(String pluginId);

    /**
     * 删除指定 Agent 的全部插件绑定（智能体被删除时级联清理）。
     */
    void deleteByAgentId(String agentId);
}