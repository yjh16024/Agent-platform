package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具调用请求（责任链传递对象）。
 */
public record ToolInvocation(String toolName, JsonNode args, ToolContext ctx) {
}