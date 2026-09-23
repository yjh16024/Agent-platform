package com.agentplatform.core.agent.dto;

import com.agentplatform.core.tool.executor.ToolCallRecord;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 统一 Agent 运行接口响应（非流式 / 首帧）。
 *
 * <p>{@code toolCalls} 是 2026-09-23 为**工具调用可视化**加的：此前用户只能看到最终文本，
 * 看不到 agent 在过程中调了哪些工具、参数是什么、拿到了什么结果。
 * 无工具调用时保持 null（{@code @JsonInclude(NON_NULL)} 会让它不出现在 JSON 里），
 * 前端据此判断"要不要渲染工具区块"。</p>
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
        List<PluginResult> plugins,
        /** 本轮工具调用记录；无调用时为 null。 */
        List<ToolCallRecord> toolCalls
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
                traceId, usage, List.of(), List.of(), null);
    }

    /**
     * 返回一个"带上工具调用记录"的新实例。
     *
     * <p>响应构造过程中已经有两处为了替换单个字段而整体 {@code new}（补 RAG 引用、
     * 补插件产出的 audio_url）。再让它们各自多背一个参数，会有"漏传某一处就丢记录"
     * 的风险；单独给一个方法，调用点就只有一个、且意图明确。</p>
     */
    public AgentRunResponse withToolCalls(List<ToolCallRecord> calls) {
        return new AgentRunResponse(runId, sessionId, mode, output, traceId, usage,
                references, plugins, calls == null || calls.isEmpty() ? null : calls);
    }
}
