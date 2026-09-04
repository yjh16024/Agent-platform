package com.agentplatform.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 统一 Agent 运行接口响应（非流式 / 首帧）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunResponse(
        String runId,
        String sessionId,
        String mode,
        Output output,
        String traceId,
        Usage usage,
        List<Reference> references,
        List<PluginResult> plugins
) {
    /** 输出内容。 */
    public record Output(String role, String content, String audioUrl) {
    }

    /** Token / 费用计量。 */
    public record Usage(int promptTokens, int completionTokens, double totalCostUsd) {
    }

    /** RAG 引用。 */
    public record Reference(String chunkId, String source, Integer page, Double score) {
    }

    /** 插件执行结果。 */
    public record PluginResult(String pluginId, String hook, Map<String, Object> result) {
    }

    public static AgentRunResponse of(String runId, String sessionId, String mode,
                                      String content, String traceId, Usage usage) {
        return new AgentRunResponse(
                runId, sessionId, mode,
                new Output("assistant", content, null),
                traceId, usage, List.of(), List.of());
    }
}