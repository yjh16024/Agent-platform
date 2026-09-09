package com.agentplatform.core.plugin.marketplace;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.agent.service.AgentService;
import com.agentplatform.core.plugin.runtime.ExtensionRegistry;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.entity.AgentPlugin;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.repository.AgentPluginRepository;
import com.agentplatform.model.repository.PluginRepository;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 插件服务（市场 + 插入 + 生命周期）。
 * <p>
 * 覆盖 开发→上传→审核→安装→插入→启用→卸载→升级 全链路。Attach/Enable 事件
 * 触发 PluginRuntime 实例化插件（独立 ClassLoader），Detach 反注册后卸载。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginService {

    /** 平台级插件所属租户（官方市场插件，对所有租户可见）。 */
    private static final String PLATFORM_TENANT = "__platform__";

    private final PluginRepository pluginRepository;
    private final AgentPluginRepository agentPluginRepository;
    private final AgentService agentService;
    private final PluginRuntime pluginRuntime;
    private final ExtensionRegistry extensionRegistry;

    /**
     * 注册插件（上传 / 安装）。
     */
    @Transactional
    public PluginDef register(String tenantId, PluginManifest manifest, String artifactUri) {
        String pluginId = manifest.id();
        PluginDef existing = pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .orElse(null);
        if (existing != null) {
            throw BizException.conflict(duplicateMessage(pluginId));
        }
        PluginDef def = PluginDef.builder()
                .pluginId(pluginId)
                .tenantId(tenantId)
                .name(manifest.name())
                .description(manifest.description())
                .author(manifest.author())
                .latestVersion(manifest.version())
                .manifest(JsonUtils.mapper().convertValue(manifest, Map.class))
                .artifactUri(artifactUri)
                .status("published")
                .visibility("private")
                .build();
        try {
            return pluginRepository.save(def);
        } catch (DataIntegrityViolationException e) {
            // check-then-insert 存在竞态：并发重复导入时唯一键冲突会穿透，这里统一转成 409
            log.warn("Duplicate plugin insert (race): {} [{}]", pluginId, e.getMessage());
            throw BizException.conflict(duplicateMessage(pluginId));
        }
    }

    private static String duplicateMessage(String pluginId) {
        return "插件已存在: " + pluginId + "（请勿重复导入；如需覆盖请先删除该插件）";
    }

    /**
     * 插件市场列表。
     */
    @Transactional(readOnly = true)
    public List<PluginDef> marketplace(String tenantId) {
        // 平台级插件（内置 auto_reply / tts / asr）对所有租户可见
        return pluginRepository.findByTenantIdInOrderByUpdatedAtDesc(List.of(tenantId, PLATFORM_TENANT));
    }

    /**
     * 插件详情。
     */
    @Transactional(readOnly = true)
    public PluginDef detail(String tenantId, String pluginId) {
        return pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .or(() -> pluginRepository.findByTenantIdAndPluginId(PLATFORM_TENANT, pluginId))
                .orElseThrow(() -> BizException.notFound("plugin", pluginId));
    }

    /**
     * 插入智能体（挂载插件）：持久化绑定 + 更新 capabilities + 热加载。
     */
    @Transactional
    public AgentPlugin attach(String tenantId, String agentId, String pluginId,
                              String version, Map<String, Object> config, Boolean enabled) {
        // 校验插件存在（内置或已注册）
        if (!pluginRuntime.isBuiltin(pluginId)
                && pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId).isEmpty()) {
            throw BizException.notFound("plugin", pluginId);
        }

        // 持久化绑定
        AgentPlugin binding = agentPluginRepository.findByAgentIdAndPluginId(agentId, pluginId)
                .map(existing -> {
                    existing.setVersion(version);
                    existing.setConfig(config);
                    existing.setEnabled(enabled == null || enabled);
                    return existing;
                })
                .orElseGet(() -> AgentPlugin.builder()
                        .agentId(agentId)
                        .pluginId(pluginId)
                        .version(version)
                        .config(config)
                        .enabled(enabled == null || enabled)
                        .attachedBy(tenantId)
                        .build());
        binding = agentPluginRepository.save(binding);

        // 更新 Agent capabilities.pluginIds
        AgentDefinition agent = agentService.getOrThrow(tenantId, agentId);
        Capabilities caps = agent.getCapabilities() == null ? Capabilities.empty() : agent.getCapabilities();
        List<String> pluginIds = new ArrayList<>(caps.pluginIds() == null ? List.of() : caps.pluginIds());
        if (!pluginIds.contains(pluginId)) {
            pluginIds.add(pluginId);
        }
        agentService.updateCapabilities(tenantId, agentId,
                new Capabilities(caps.knowledgeBaseIds(), caps.toolsetIds(), caps.skillIds(), pluginIds,
                        caps.workflowId(), caps.multimodal()));

        // 热加载
        if (enabled == null || enabled) {
            pluginRuntime.attach(pluginId, agentId, tenantId, config);
        }
        log.info("Attached plugin {} to agent {}", pluginId, agentId);
        return binding;
    }

    /**
     * 卸载插件。
     */
    @Transactional
    public void detach(String tenantId, String agentId, String pluginId) {
        agentPluginRepository.deleteByAgentIdAndPluginId(agentId, pluginId);
        pluginRuntime.detach(pluginId);

        AgentDefinition agent = agentService.getOrThrow(tenantId, agentId);
        Capabilities caps = agent.getCapabilities() == null ? Capabilities.empty() : agent.getCapabilities();
        List<String> pluginIds = new ArrayList<>(caps.pluginIds() == null ? List.of() : caps.pluginIds());
        pluginIds.remove(pluginId);
        agentService.updateCapabilities(tenantId, agentId,
                new Capabilities(caps.knowledgeBaseIds(), caps.toolsetIds(), caps.skillIds(), pluginIds,
                        caps.workflowId(), caps.multimodal()));
        log.info("Detached plugin {} from agent {}", pluginId, agentId);
    }

    /**
     * 查询某 Agent 已挂载插件及其贡献。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAttached(String tenantId, String agentId) {
        List<AgentPlugin> bindings = agentPluginRepository.findByAgentIdAndEnabledTrue(agentId);
        return bindings.stream().map(b -> Map.<String, Object>of(
                "plugin_id", b.getPluginId(),
                "version", b.getVersion() == null ? "" : b.getVersion(),
                "tools", extensionRegistry.toolNamesOf(b.getPluginId()),
                "enabled", b.getEnabled() != null && b.getEnabled()
        )).toList();
    }

    /**
     * 删除插件（物理删除 + 从所有智能体卸载）。
     * <p>
     * 仅允许删除租户自有插件；官方市场插件（{@code __platform__}）为内置能力，
     * 删除会破坏运行时，明确拒绝。删除前先卸载，避免留下指向已删插件的钩子与工具。
     * </p>
     */
    @Transactional
    public void delete(String tenantId, String pluginId) {
        PluginDef def = pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .orElseThrow(() -> BizException.notFound("plugin", pluginId));
        if (PLATFORM_TENANT.equals(def.getTenantId())) {
            throw BizException.forbidden("内置平台插件不可删除: " + pluginId);
        }
        // 先从所有挂载了它的智能体上卸载（反注册 Hook / 工具）
        for (AgentPlugin binding : agentPluginRepository.findByPluginId(pluginId)) {
            try {
                pluginRuntime.detach(pluginId);
            } catch (Exception e) {
                log.warn("Detach plugin {} before delete failed: {}", pluginId, e.getMessage());
            }
            agentPluginRepository.delete(binding);
        }
        // 兜底：即使没有绑定记录也尝试卸载（可能由运行时热挂载）
        try {
            pluginRuntime.detach(pluginId);
        } catch (Exception ignored) {
            // 未挂载时 detach 是幂等的
        }
        pluginRepository.delete(def);
        log.info("Deleted plugin {} [{}]", def.getName(), pluginId);
    }
}