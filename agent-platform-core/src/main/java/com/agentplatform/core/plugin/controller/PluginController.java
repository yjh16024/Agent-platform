package com.agentplatform.core.plugin.controller;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.plugin.marketplace.PluginService;
import com.agentplatform.core.plugin.runtime.PluginManifestLoader;
import com.agentplatform.model.entity.AgentPlugin;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 插件市场与管理接口（Plugin Admin API · RESTful，对应 §3.4）。
 * <p>覆盖 注册/导入 → 市场 → 详情 → 挂载/卸载 → 删除 全链路。</p>
 */
@RestController
@RequestMapping("/api/v1/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;
    private final PluginManifestLoader manifestLoader;

    /** 注册插件（manifest 文本，YAML 或 JSON）。 */
    @PostMapping("/register")
    public ApiResponse<PluginDef> register(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody String manifestText) {
        PluginManifest manifest = manifestLoader.parse(manifestText);
        return ApiResponse.ok(pluginService.register(tenantId, manifest, null), "registered");
    }

    /** 导入插件（与 register 等价，语义更贴近「从文件/文本导入」）。 */
    @PostMapping("/import")
    public ApiResponse<PluginDef> importPlugin(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody String manifestText) {
        PluginManifest manifest = manifestLoader.parse(manifestText);
        return ApiResponse.ok(pluginService.register(tenantId, manifest, null), "imported");
    }

    /** 插件市场列表（当前租户 ∪ 平台内置）。 */
    @GetMapping("/marketplace")
    public ApiResponse<List<PluginDef>> marketplace(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(pluginService.marketplace(tenantId));
    }

    /** 插件详情。 */
    @GetMapping("/{pluginId}")
    public ApiResponse<PluginDef> detail(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String pluginId) {
        return ApiResponse.ok(pluginService.detail(tenantId, pluginId));
    }

    /** 插入智能体（挂载）。 */
    @PostMapping("/{pluginId}/attach")
    public ApiResponse<AgentPlugin> attach(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String pluginId,
            @RequestBody Map<String, Object> body) {
        String agentId = (String) body.get("agent_id");
        String version = (String) body.get("version");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        Boolean enabled = body.get("enabled") == null ? null : Boolean.valueOf(String.valueOf(body.get("enabled")));
        return ApiResponse.ok(pluginService.attach(tenantId, agentId, pluginId, version, config, enabled));
    }

    /** 卸载插件。 */
    @PostMapping("/{pluginId}/detach")
    public ApiResponse<Void> detach(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String pluginId,
            @RequestParam String agentId) {
        pluginService.detach(tenantId, agentId, pluginId);
        return ApiResponse.ok(null, "detached");
    }

    /** 查询某 Agent 已挂载插件。 */
    @GetMapping("/attachments")
    public ApiResponse<List<Map<String, Object>>> attachments(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam String agentId) {
        return ApiResponse.ok(pluginService.listAttached(tenantId, agentId));
    }

    /**
     * 删除插件（物理删除 + 从所有智能体卸载）。
     * <p>仅租户自有插件可删；内置平台插件（{@code __platform__}）返回 403。</p>
     */
    @DeleteMapping("/{pluginId}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String pluginId) {
        pluginService.delete(tenantId, pluginId);
        return ApiResponse.ok(null, "deleted");
    }
}
