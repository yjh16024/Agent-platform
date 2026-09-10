package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * MCP 工具适配器：把 MCP Server 暴露的某个工具包装成平台统一的 {@link Tool}。
 * <p>一次注册会话（{@link McpToolRegistry#connect}）会为服务器上的每个远程工具
 * 创建一个适配器，执行时经 {@link McpClient#callTool} 转发 JSON-RPC。</p>
 */
@Slf4j
public class McpToolAdapter implements Tool {

    private final String name;
    private final String description;
    private final JsonNode inputSchema;
    private final String serverUrl;
    private final McpClient client;

    public McpToolAdapter(String name, String description, JsonNode inputSchema,
                          String serverUrl, McpClient client) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.serverUrl = serverUrl;
        this.client = client;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            Map<String, Object> arguments = new HashMap<>();
            if (args != null && args.isObject()) {
                // Jackson 3：JsonNode#fields() 已由 properties() 取代（返回 Set<Map.Entry>）
                for (Map.Entry<String, JsonNode> e : args.properties()) {
                    arguments.put(e.getKey(), JsonUtils.mapper().convertValue(e.getValue(), Object.class));
                }
            }
            String text = client.callTool(name, arguments);
            return ToolResult.ok(text);
        } catch (BizException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("MCP tool {} failed: {}", name, e.getMessage());
            return ToolResult.fail("MCP tool error: " + e.getMessage());
        }
    }
}
