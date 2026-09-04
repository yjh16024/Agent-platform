package com.agentplatform.core.tool;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.tool.executor.HttpApiTool;
import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.tool.mcp.McpToolRegistry;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工具注册中心接口（RESTful）。
 */
@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final McpToolRegistry mcpToolRegistry;

    /**
     * 列出全部已注册工具。
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> tools = registry.all().stream()
                .map(t -> Map.of(
                        "name", t.name(),
                        "description", (Object) (t.description() == null ? "" : t.description())))
                .toList();
        return ApiResponse.ok(tools);
    }

    /**
     * 执行工具（经责任链）。
     */
    @PostMapping("/{toolName}/invoke")
    public ApiResponse<ToolResult> invoke(
            @PathVariable String toolName,
            @RequestBody(required = false) JsonNode args,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        ToolContext ctx = ToolContext.of(tenantId, null, null);
        return ApiResponse.ok(executor.run(toolName, args, ctx));
    }

    /**
     * 注册自定义 HTTP API 工具（热注册）。
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> registerHttp(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String endpoint = (String) body.get("endpoint");
        String method = (String) body.getOrDefault("method", "POST");

        if (name == null || endpoint == null) {
            return ApiResponse.error("BAD_REQUEST", "name and endpoint are required");
        }

        HttpApiTool tool = new HttpApiTool(name, description, null, endpoint, method);
        registry.register(tool);
        return ApiResponse.ok(Map.of("name", name, "registered", true));
    }

    /**
     * 连接 MCP Server 并注册其全部工具（MCP 接入）。
     * <p>body：{@code server_url} 必填；{@code api_key} 可选（Bearer）；
     * {@code headers} 可选（额外请求头）。</p>
     */
    @PostMapping("/mcp")
    public ApiResponse<Map<String, Object>> connectMcp(@RequestBody Map<String, Object> body) {
        String serverUrl = (String) body.get("server_url");
        String apiKey = (String) body.get("api_key");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) body.get("headers");
        return ApiResponse.ok(mcpToolRegistry.connect(serverUrl, apiKey, headers), "MCP tools registered");
    }

    /**
     * 卸载单个工具（从注册中心移除，MCP/HTTP 自定义工具均可）。
     */
    @DeleteMapping("/{toolName}")
    public ApiResponse<Void> unregister(@PathVariable String toolName) {
        registry.unregister(toolName);
        return ApiResponse.ok(null, "unregistered");
    }
}