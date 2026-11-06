package com.agentplatform.framework.api;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 一次运行的事件流（{@link AgentRuntime#runStream} 的元素）。
 *
 * <p><b>为什么先定它</b>：事件流同时被前端、SSE 适配层、插件三方消费，是契约里最难改的一处。
 * 一旦发布，增删字段都会破坏其中一方，所以先把它定下来，再让实现去适配。
 *
 * <p><b>契约（由 {@link RunEvents#validate} 强制）</b>：
 * <ol>
 *   <li>第一个事件必须是 {@link Started}</li>
 *   <li>{@code seq} 从 1 开始、步长 1、在流内严格递增</li>
 *   <li>有且仅有一个终止事件（{@link Completed} 或 {@link Failed}），且必为最后一个</li>
 *   <li>终止事件之后不得再有任何事件</li>
 * </ol>
 *
 * <p>终端事件不携带「是否成功」的布尔位——成功与失败是两种类型，
 * 用 {@code sealed} 让调用方的 switch 必须处理两种情况，避免忘记判错。
 */
public sealed interface RunEvent
        permits RunEvent.Started, RunEvent.TextDelta, RunEvent.ReasoningDelta,
        RunEvent.ToolStarted, RunEvent.ToolFinished, RunEvent.Completed, RunEvent.Failed {

    String runId();

    /** 流内序号，从 1 开始，步长 1（SSE 断线重连与去重靠它）。 */
    long seq();

    /** 事件产生时刻（实现方注入时钟，便于测试）。 */
    Instant at();

    default boolean terminal() {
        return this instanceof Completed || this instanceof Failed;
    }

    /** 运行开始：调用方据此拿到 traceId 与 sessionId（会话可能是本轮新建的）。 */
    record Started(String runId, long seq, Instant at,
                   String traceId, String agentId, String sessionId) implements RunEvent {
    }

    /** 正文增量（流式逐字推送的最小单位）。 */
    record TextDelta(String runId, long seq, Instant at, String delta) implements RunEvent {
    }

    /**
     * 思考过程增量。
     *
     * <p>单独一种事件而不是混进 {@link TextDelta}：思考过程**不进会话历史、不进向量索引**，
     * 混在一起会让下游误把它当正文落库。
     */
    record ReasoningDelta(String runId, long seq, Instant at, String delta) implements RunEvent {
    }

    /** 工具开始调用：{@code arguments} 为模型给出的原始参数（可能不合 schema，校验在下游）。 */
    record ToolStarted(String runId, long seq, Instant at,
                       String callId, String toolName, JsonNode arguments) implements RunEvent {
    }

    /** 工具调用结束：只带摘要，不带完整结果（见 {@link RunResult.ToolCall}）。 */
    record ToolFinished(String runId, long seq, Instant at,
                        String callId, String toolName, boolean ok, long elapsedMs,
                        String errorCode) implements RunEvent {
    }

    /** 正常结束：载荷与非流式返回值完全一致，保证两种调用方式可互换。 */
    record Completed(String runId, long seq, Instant at, RunResult result) implements RunEvent {
    }

    /**
     * 异常结束。
     *
     * @param retryable 调用方可否直接重试（如 429/5xx 为 true，参数错误为 false）
     */
    record Failed(String runId, long seq, Instant at,
                  String errorCode, String message, boolean retryable) implements RunEvent {
    }
}
