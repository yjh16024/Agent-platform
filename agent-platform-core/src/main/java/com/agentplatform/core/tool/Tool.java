package com.agentplatform.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具接口（统一 Tool 协议）。
 * <p>
 * JSON Schema 描述入参，模型按 function calling 触发。全部实现注册到
 * {@code ToolRegistry}（注册表模式），调用前经责任链鉴权/校验/限流/审计。
 * </p>
 */
public interface Tool {

    /**
     * 工具名（全局唯一，LLM function calling 触发名）。
     */
    String name();

    /**
     * 工具描述（供 LLM 理解何时调用）。
     */
    String description();

    /**
     * 入参 JSON Schema（可空）。
     */
    default JsonNode inputSchema() {
        return null;
    }

    /**
     * 执行工具。
     *
     * @param args 入参
     * @param ctx  执行上下文
     * @return 执行结果
     */
    ToolResult execute(JsonNode args, ToolContext ctx);
}