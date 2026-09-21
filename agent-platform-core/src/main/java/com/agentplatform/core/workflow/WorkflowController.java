package com.agentplatform.core.workflow;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.model.entity.WorkflowDef;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 工作流编排接口（RESTful）。
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@RequiresPermission("workflow:write")
public class WorkflowController {

    private final WorkflowService workflowService;

    /** 创建/保存工作流定义。 */
    @PostMapping
    public ApiResponse<WorkflowDef> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestBody WorkflowDefinition definition) {
        return ApiResponse.ok(workflowService.create(tenantId, name, description, definition));
    }

    /** 更新工作流定义（画布保存：覆盖 definition，workflowId 不变）。 */
    @PutMapping("/{workflowId}")
    public ApiResponse<WorkflowDef> update(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String workflowId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestBody WorkflowDefinition definition) {
        return ApiResponse.ok(workflowService.update(tenantId, workflowId, name, description, definition), "updated");
    }

    /** 列表。 */
    @GetMapping
    @RequiresPermission("workflow:read")
    public ApiResponse<List<WorkflowDef>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(workflowService.list(tenantId));
    }

    /** 详情。 */
    @GetMapping("/{workflowId}")
    @RequiresPermission("workflow:read")
    public ApiResponse<WorkflowDefinition> get(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String workflowId) {
        return ApiResponse.ok(workflowService.get(tenantId, workflowId));
    }

    /** 执行工作流。 */
    @PostMapping("/{workflowId}/execute")
    public ApiResponse<Map<String, Object>> execute(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String workflowId,
            @RequestBody(required = false) Map<String, Object> input) {
        return ApiResponse.ok(workflowService.execute(tenantId, workflowId, input == null ? Map.of() : input));
    }

    /** 发布：把当前草稿存为已发布快照并递增版本号。 */
    @PostMapping("/{workflowId}/publish")
    public ApiResponse<WorkflowDef> publish(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String workflowId) {
        return ApiResponse.ok(workflowService.publish(tenantId, workflowId), "published");
    }

    /** 回滚：definition 恢复为已发布快照。 */
    @PostMapping("/{workflowId}/rollback")
    public ApiResponse<WorkflowDef> rollback(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String workflowId) {
        return ApiResponse.ok(workflowService.rollback(tenantId, workflowId), "rolled back");
    }

    /** 调试执行：返回最终变量 + 每节点执行轨迹（画布调试面板）。 */
    @PostMapping("/{workflowId}/debug")
    public ApiResponse<Map<String, Object>> debug(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String workflowId,
            @RequestBody(required = false) Map<String, Object> input) {
        return ApiResponse.ok(workflowService.debug(tenantId, workflowId, input == null ? Map.of() : input));
    }

    /** 删除（归档）。 */
    @DeleteMapping("/{workflowId}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String workflowId) {
        workflowService.delete(tenantId, workflowId);
        return ApiResponse.ok(null, "archived");
    }
}