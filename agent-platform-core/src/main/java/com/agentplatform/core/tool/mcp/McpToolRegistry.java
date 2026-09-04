package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具接入服务：连接远程 MCP Server → 发现工具 → 注册进平台工具注册中心。
 * <p>
 * 通过 {@code POST /api/v1/tools/mcp} 注册：body 含 {@code server_url} 与可选
 * {@code api_key}（用于常见 Bearer 鉴权）或 {@code headers}。同名工具已存在时
 * 覆盖（热更新）；返回本次注册成功的工具清单。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolRegistry {

    private final ToolRegistry toolRegistry;

    /**
     * 连接 MCP 服务器并注册其全部工具。
     *
     * @param serverUrl MCP Server 地址（Streamable HTTP 端点）
     * @param apiKey    可选（透传为 {@code Authorization: Bearer <apiKey>}）
     * @param headers   额外请求头（键值）
     * @return 注册结果（serverUrl + 工具清单）
     */
    public Map<String, Object> connect(String serverUrl, String apiKey, Map<String, Object> headers) {
        Map<String, String> requestHeaders = new HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null) {
                    requestHeaders.put(k, String.valueOf(v));
                }
            });
        }
        if (apiKey != null && !apiKey.isBlank()) {
            requestHeaders.putIfAbsent("Authorization", "Bearer " + apiKey.trim());
        }

        McpClient client = new McpClient(serverUrl, requestHeaders);
        List<Map<String, Object>> discovered = client.initializeAndListTools();
        if (discovered.isEmpty()) {
            throw new com.agentplatform.common.exception.BizException("BAD_REQUEST",
                    "MCP 服务器未暴露任何工具（tools/list 为空）");
        }

        List<Map<String, Object>> registered = new ArrayList<>();
        for (Map<String, Object> t : discovered) {
            String name = (String) t.get("name");
            String description = (String) t.getOrDefault("description", "");
            JsonNode schema = t.get("input_schema") instanceof JsonNode n
                    ? n : (t.get("input_schema") == null ? null : JsonUtils.mapper().valueToTree(t.get("input_schema")));
            toolRegistry.register(new McpToolAdapter(name, description, schema, serverUrl, client));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("description", description);
            registered.add(row);
        }
        log.info("Registered {} MCP tools from {}", registered.size(), serverUrl);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("server_url", serverUrl);
        result.put("registered", registered.size());
        result.put("tools", registered);
        return result;
    }
}
