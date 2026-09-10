package com.agentplatform.core.tool;

import tools.jackson.databind.JsonNode;

/**
 * 工具执行结果。
 *
 * @param success 是否成功
 * @param output  输出（工具返回值，回传 LLM）
 * @param error   错误信息（success=false 时）
 */
public record ToolResult(boolean success, JsonNode output, String error) {

    public static ToolResult ok(JsonNode output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult ok(String text) {
        return new ToolResult(true, tools.jackson.databind.node.StringNode.valueOf(text), null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error);
    }
}