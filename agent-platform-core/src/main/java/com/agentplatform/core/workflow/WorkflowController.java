package com.agentplatform.core.workflow;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.model.entity.WorkflowDef;
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
 * 工作流编排接口（RESTful）。
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
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

    /** 列表。 */
    @GetMapping
    public ApiResponse<List<WorkflowDef>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(workflowService.list(tenantId));
    }

    /** 详情。 */
    @GetMapping("/{workflowId}")
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

    /** 删除（归档）。 */
    @DeleteMapping("/{workflowId}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String workflowId) {
        workflowService.delete(tenantId, workflowId);
        return ApiResponse.ok(null, "archived");
    }
}