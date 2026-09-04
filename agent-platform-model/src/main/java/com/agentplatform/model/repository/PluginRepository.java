package com.agentplatform.model.repository;

import com.agentplatform.model.entity.PluginDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 插件仓储。
 */
@Repository
public interface PluginRepository extends JpaRepository<PluginDef, Long> {

    Optional<PluginDef> findByTenantIdAndPluginId(String tenantId, String pluginId);

    List<PluginDef> findByTenantIdOrderByUpdatedAtDesc(String tenantId);

    List<PluginDef> findByTenantIdInOrderByUpdatedAtDesc(List<String> tenantIds);
}