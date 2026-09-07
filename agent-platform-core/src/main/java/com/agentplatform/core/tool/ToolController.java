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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * 列出全部已注册工具（含来源与 HTTP 工具的 endpoint/method，供前端编辑回显）。
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Tool t : registry.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", t.name());
            row.put("description", t.description() == null ? "" : t.description());
            row.put("source", registry.sourceOf(t.name()));
            if (t instanceof HttpApiTool http) {
                row.put("endpoint", http.getEndpoint());
                row.put("method", http.getMethod());
            }
            tools.add(row);
        }
        return ApiResponse.ok(tools);
    }

    /**
     * 修改自定义 HTTP API 工具（仅 source=http 的可修改 endpoint/method/description）。
     */
    @PutMapping("/{toolName}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String toolName,
            @RequestBody Map<String, Object> body) {
        Tool existing = registry.get(toolName);
        if (!(existing instanceof HttpApiTool http)) {
            return ApiResponse.error("BAD_REQUEST",
                    "only http-registered tools are editable (current source=" + registry.sourceOf(toolName) + ")");
        }
        String description = body.containsKey("description")
                ? (String) body.get("description") : http.getDescription();
        String endpoint = body.containsKey("endpoint")
                ? (String) body.get("endpoint") : http.getEndpoint();
        String method = body.containsKey("method")
                ? String.valueOf(body.get("method")) : http.getMethod();
        if (endpoint == null || endpoint.isBlank()) {
            return ApiResponse.error("BAD_REQUEST", "endpoint 不能为空");
        }
        HttpApiTool updated = new HttpApiTool(toolName, description, http.getInputSchema(), endpoint, method);
        registry.register(updated, "http");
        return ApiResponse.ok(Map.of("name", toolName, "updated", true));
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
        registry.register(tool, "http");
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
     * 卸载单个工具（从注册中心移除，MCP/HTTP 自定义工具均可；内置工具受保护）。
     */
    @DeleteMapping("/{toolName}")
    public ApiResponse<Void> unregister(@PathVariable String toolName) {
        if (!registry.contains(toolName)) {
            return ApiResponse.error("NOT_FOUND", "tool not found: " + toolName);
        }
        if ("builtin".equals(registry.sourceOf(toolName))) {
            return ApiResponse.error("BAD_REQUEST",
                    "内置工具（calc/search 等）由代码注册，不可删除；如确需移除请修改代码后重启");
        }
        registry.unregister(toolName);
        return ApiResponse.ok(null, "unregistered");
    }
}