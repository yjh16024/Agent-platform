package com.agentplatform.core.plugin.web;

import com.agentplatform.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 界面插件（独立前端 JS 插件）接口（2026-10-19，D 档第 4 步）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/plugin-web} —— 已安装列表（含启用状态）；</li>
 *   <li>{@code GET /api/v1/plugin-web/contributions} —— 启用中的插件对槽位的声明（前端加载依据）；</li>
 *   <li>{@code POST /install} —— 上传 zip 安装（覆盖同名）；</li>
 *   <li>{@code POST /{id}/enabled} —— 启停（热切换，不重启）；</li>
 *   <li>{@code DELETE /{id}} —— 卸载；</li>
 *   <li>{@code GET /{id}/file/**} —— 插件文件（脚本 / 样式 / 图片）。</li>
 * </ul>
 *
 * <p><b>不做权限校验</b>：与 {@code PluginController} 同一取舍 —— 插件体系对所有登录用户开放
 * （本地单用户平台的定位）。</p>
 */
@RestController
@RequestMapping("/api/v1/plugin-web")
@RequiredArgsConstructor
public class PluginWebController {

    private final PluginWebService service;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.list());
    }

    /**
     * 界面插件对槽位的声明。
     *
     * @param agentId 当前智能体（**建议必传**）：用于按「挂载范围」过滤。
     *                不传 = 不做过滤（兼容旧前端/脚本），会把所有插件都返回。
     */
    @GetMapping("/contributions")
    public ApiResponse<List<Map<String, Object>>> contributions(
            @RequestParam(required = false) String agentId) {
        return ApiResponse.ok(service.contributions(agentId));
    }

    /** 设置挂载范围：`{ "agents": ["a1","a2"] }`，空数组 = 全局。 */
    @PostMapping("/{id}/agents")
    @SuppressWarnings("unchecked")
    public ApiResponse<Void> setAgents(@PathVariable String id, @RequestBody Map<String, Object> body) {
        List<String> agents = new ArrayList<>();
        if (body != null && body.get("agents") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    agents.add(String.valueOf(o));
                }
            }
        }
        service.setAgents(id, agents);
        return ApiResponse.ok(null, "agents-updated");
    }

    @PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> install(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.install(file), "installed");
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        service.setEnabled(id, enabled);
        return ApiResponse.ok(null, enabled ? "enabled" : "disabled");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(null, "deleted");
    }

    @GetMapping("/{id}/file/{*path}")
    public ResponseEntity<byte[]> file(@PathVariable String id, @PathVariable String path) {
        return service.file(id, path)
                .map(asset -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .contentType(MediaType.parseMediaType(asset.contentType()))
                        .body(asset.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
