package com.agentplatform.core.plugin.controller;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.plugin.marketplace.PluginArtifactStore;
import com.agentplatform.core.plugin.marketplace.PluginService;
import com.agentplatform.core.plugin.runtime.PluginManifestLoader;
import com.agentplatform.core.plugin.web.PluginWebAssetService;
import com.agentplatform.model.entity.AgentPlugin;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 插件市场与管理接口（Plugin Admin API · RESTful，对应 §3.4）。
 * <p>覆盖 注册/导入 → 市场 → 详情 → 挂载/卸载 → 删除 全链路。</p>
 *
 * <h3>★ 本控制器<b>刻意不做权限校验</b>（2026-10-19 定）</h3>
 * 平台按"本地部署、单用户"定位：**插件体系（含可执行界面）对所有登录用户开放** ——
 * 任何角色的账号都能注册 / 挂载 / 卸载 / 删除插件。
 *
 * <p>RBAC 本身**没有关闭**，仍管其它域（用户 / 角色 / 字典 / 审计 / 平台设置等）；
 * 这里只是不加 {@code @RequiresPermission}。理由：单机场景下"能装插件、但装不了某类插件"
 * 这个中间态没有真实的隔离对象（部署者就是使用者），只会制造麻烦；
 * 而"插件"这一域的信任前提本来就是"装它的人已经拥有这台机器"。</p>
 */
@RestController
@RequestMapping("/api/v1/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;
    private final PluginManifestLoader manifestLoader;
    private final PluginArtifactStore artifactStore;
    private final PluginWebAssetService webAssetService;

    /**
     * 注册插件（manifest 文本，YAML 或 JSON）。
     * <p>可选 {@code artifactUri}：外部插件的 jar 地址，支持 {@code file:/绝对路径} 或 {@code http(s)://}。
     * 不传则只登记元数据（此时 attach 会提示"未配置制品"）。</p>
     */
    @PostMapping("/register")
    public ApiResponse<PluginDef> register(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String artifactUri,
            @RequestBody String manifestText) {
        PluginManifest manifest = manifestLoader.parse(manifestText);
        return ApiResponse.ok(pluginService.register(tenantId, manifest, artifactUri), "registered");
    }

    /** 导入插件（与 register 等价，语义更贴近「从文件/文本导入」；同样支持可选 artifactUri）。 */
    @PostMapping("/import")
    public ApiResponse<PluginDef> importPlugin(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String artifactUri,
            @RequestBody String manifestText) {
        PluginManifest manifest = manifestLoader.parse(manifestText);
        return ApiResponse.ok(pluginService.register(tenantId, manifest, artifactUri), "imported");
    }

    /**
     * 上传插件包：{@code multipart/form-data}，字段 {@code manifest}（YAML/JSON 文本）+ {@code jar}（插件制品）。
     * <p>jar 落到 {@code agent-platform.plugin.artifact-dir}（默认 {@code ./data/plugins}），并写入
     * {@code artifact_uri}，随后即可 attach 热加载 —— 这是外部插件「装得上」的完整链路。</p>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PluginDef> upload(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam("manifest") String manifestText,
            @RequestParam("jar") MultipartFile jar) throws IOException {
        PluginManifest manifest = manifestLoader.parse(manifestText);
        String artifactUri = artifactStore.save(manifest.id(), manifest.version(), jar.getInputStream());
        return ApiResponse.ok(pluginService.register(tenantId, manifest, artifactUri), "uploaded");
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

    /**
     * 卸载插件（<b>级联</b>：该插件在所有智能体上的挂载都会被一并取消）。
     * <p>前端会先调 {@code GET /{pluginId}/attachments} 拿到影响面、让用户确认后再走这里。</p>
     */
    @PostMapping("/{pluginId}/detach")
    public ApiResponse<Map<String, Object>> detach(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String pluginId,
            @RequestParam String agentId) {
        return ApiResponse.ok(pluginService.detach(tenantId, agentId, pluginId), "detached");
    }

    /** 查某插件被哪些智能体挂载（卸载前的影响面提示）。 */
    @GetMapping("/{pluginId}/attachments")
    public ApiResponse<List<Map<String, Object>>> attachmentsOfPlugin(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String pluginId) {
        return ApiResponse.ok(pluginService.attachmentsOfPlugin(tenantId, pluginId));
    }

    /** 查询某 Agent 已挂载插件。 */
    @GetMapping("/attachments")
    public ApiResponse<List<Map<String, Object>>> attachments(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam String agentId) {
        return ApiResponse.ok(pluginService.listAttached(tenantId, agentId));
    }

    /**
     * 查询某 Agent 上所有生效插件的**界面贡献**（前端插槽的数据源）。
     *
     * <p>前端在切智能体时拉一次，把结果按 {@code slot} 分发给各处的 {@code <SlotOutlet>}。
     * 详见 {@code PluginService.listUiContributions} 的说明（为什么单独开接口、校验了什么）。</p>
     */
    @GetMapping("/ui-contributions")
    public ApiResponse<List<Map<String, Object>>> uiContributions(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam String agentId) {
        return ApiResponse.ok(pluginService.listUiContributions(tenantId, agentId));
    }

    /**
     * 插件的前端资源（{@code web/} 下的脚本 / 样式 / 图片）—— **可执行界面**的加载入口。
     *
     * <p>前端把 manifest 里 {@code ui_scripts[].entry}（如 {@code web/pet.js}）拼成
     * {@code /api/v1/plugins/{id}/web/pet.js} 注入 {@code <script>}。两种来源由
     * {@link PluginWebAssetService} 决定：外部插件读 jar，内置插件读 classpath。</p>
     *
     * <p>响应带 {@code Cache-Control: no-store}：插件升级后脚本路径可能不变，
     * 被缓存住就等于"改了不生效"（桌面版尤其容易踩 —— Electron 会长期保留 HTTP 缓存，
     * 这也正是 application.yml 里静态资源统一 no-store 的原因）。</p>
     */
    @GetMapping("/{pluginId}/web/{*path}")
    public ResponseEntity<byte[]> webAsset(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String pluginId,
            @PathVariable String path) {
        PluginWebAssetService.WebAsset asset = webAssetService.load(tenantId, pluginId, path);
        if (asset == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .body(asset.bytes());
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
