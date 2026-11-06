package com.agentplatform.framework.api;

import tools.jackson.databind.JsonNode;

/**
 * 工具调用请求（责任链传递对象）。
 */
public record ToolInvocation(String toolName, JsonNode args, ToolContext ctx) {
}