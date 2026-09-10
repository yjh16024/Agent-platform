package com.agentplatform.core.tool.mcp;

import tools.jackson.databind.JsonNode;

/**
 * MCP 工具声明（与传输方式无关的统一描述）。
 * <p>
 * 各类 {@link McpClient} 实现（HTTP 远程 / 本地进程内 / 沙箱子进程）发现工具后
 * 统一以本结构返回，供 {@code McpToolRegistry} 包装成平台 {@code Tool}。
 * </p>
 *
 * @param name        工具名
 * @param description 工具描述（供 LLM 判断何时调用）
 * @param inputSchema 入参 JSON Schema（可空）
 */
public record McpToolSpec(String name, String description, JsonNode inputSchema) {

    public static McpToolSpec of(String name, String description) {
        return new McpToolSpec(name, description, null);
    }
}
