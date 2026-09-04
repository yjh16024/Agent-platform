package com.agentplatform.plugin.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 插件贡献的工具（可被 LLM function calling 触发）。
 *
 * @param name        工具名
 * @param description 工具描述（供 LLM 理解）
 * @param inputSchema 入参 JSON Schema
 * @param handler     执行函数
 */
public record PluginTool(
        String name,
        String description,
        ObjectNode inputSchema,
        ToolHandler handler
) {
    /**
     * 便捷构造：无 Schema 的简单工具。
     */
    public static PluginTool of(String name, String description, ToolHandler handler) {
        return new PluginTool(name, description, null, handler);
    }

    /**
     * 工具执行函数。
     */
    @FunctionalInterface
    public interface ToolHandler {
        JsonNode execute(JsonNode args, PluginContext ctx);
    }
}