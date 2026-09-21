package com.agentplatform.core.agent.controller;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.dto.PageResult;
import com.agentplatform.core.agent.dto.AgentCreateRequest;
import com.agentplatform.core.agent.dto.AgentPatchRequest;
import com.agentplatform.core.agent.dto.AgentResponse;
import com.agentplatform.core.agent.service.AgentService;
import com.agentplatform.core.agent.service.AgentVersionService;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.model.entity.AgentVersion;
import com.agentplatform.model.enums.AgentStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体管理接口（Agent Admin API · RESTful）。
 * <p>统一管理面，与运行面 /agent/run 分离，带 tenant_id 隔离。</p>
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
@RequiresPermission("agent:write")
public class AgentController {

    private final AgentService agentService;
    private final AgentVersionService versionService;

    /** 创建智能体。 */
    @PostMapping
    public ApiResponse<AgentResponse> create(
            @Valid @RequestBody AgentCreateRequest req,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(agentService.create(req, tenantId));
    }

    /** 全量更新。 */
    @PutMapping("/{agentId}")
    public ApiResponse<AgentResponse> update(
            @PathVariable String agentId,
            @RequestBody AgentCreateRequest req,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(agentService.update(agentId, req, tenantId));
    }

    /** 局部更新。 */
    @PatchMapping("/{agentId}")
    public ApiResponse<AgentResponse> patch(
            @PathVariable String agentId,
            @RequestBody AgentPatchRequest req,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(agentService.patch(agentId, req, tenantId));
    }

    /** 软删除。 */
    @DeleteMapping("/{agentId}")
    public ApiResponse<Void> delete(
            @PathVariable String agentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        agentService.delete(agentId, tenantId);
        return ApiResponse.ok(null, "Agent archived");
    }

    /** 分页查询。 */
    @GetMapping
    @RequiresPermission("agent:read")
    public ApiResponse<PageResult<AgentResponse>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AgentStatus statusEnum = status == null ? null : AgentStatus.valueOf(status);
        return ApiResponse.ok(agentService.list(tenantId, q, statusEnum, page, size));
    }

    /** 详情。 */
    @GetMapping("/{agentId}")
    @RequiresPermission("agent:read")
    public ApiResponse<AgentResponse> get(
            @PathVariable String agentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(agentService.get(agentId, tenantId));
    }

    /** 克隆。 */
    @PostMapping("/{agentId}/clone")
    public ApiResponse<AgentResponse> clone(
            @PathVariable String agentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(agentService.clone(agentId, tenantId));
    }

    /** 创建版本快照（draft 存盘）。 */
    @PostMapping("/{agentId}/versions")
    public ApiResponse<AgentVersion> createVersion(
            @PathVariable String agentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(versionService.createVersionSnapshot(agentId, tenantId));
    }

    /** 发布。 */
    @PostMapping("/{agentId}/publish")
    public ApiResponse<AgentVersion> publish(
            @PathVariable String agentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(versionService.publish(agentId, tenantId));
    }

    /** 回滚。 */
    @PostMapping("/{agentId}/rollback/{version}")
    public ApiResponse<AgentVersion> rollback(
            @PathVariable String agentId,
            @PathVariable String version,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(versionService.rollback(agentId, version, tenantId));
    }

    /** 版本列表。 */
    @GetMapping("/{agentId}/versions")
    @RequiresPermission("agent:read")
    public ApiResponse<List<AgentVersion>> versions(
            @PathVariable String agentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(versionService.listVersions(agentId, tenantId));
    }

    /** 版本差异对比。 */
    @GetMapping("/{agentId}/diff")
    @RequiresPermission("agent:read")
    public ApiResponse<Map<String, Object>> diff(
            @PathVariable String agentId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(versionService.diff(agentId, from, to, tenantId));
    }
}