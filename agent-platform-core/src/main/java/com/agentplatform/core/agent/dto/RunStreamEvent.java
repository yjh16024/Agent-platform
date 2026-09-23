package com.agentplatform.core.agent.dto;

import com.agentplatform.core.tool.executor.ToolCallRecord;

import java.util.List;

/**
 * 流式运行的**事件**（SSE 帧的载荷）。
 *
 * <h3>为什么要把流元素从 {@code Output} 换成本类型</h3>
 * 此前 {@code runStream()} 返回 {@code Flux<AgentRunResponse.Output>}，即"流里只能是文本增量"。
 * 但工具调用可视化需要额外传一轮的**工具调用记录**（它不属于任何一段文本）。
 * 若硬塞进 {@code Output}（比如借 {@code role} 字段做标记），会留下"某个字段被借用作事件类型"
 * 的陷阱 —— 后来改的人无法从类型上看出这件事。
 *
 * <p>而 SSE 本来就是**多事件类型**的协议，用一个带 {@code type} 的事件模型表达是更自然的：
 * 边界清晰（{@code delta} 只带文本、{@code completed} 只带记录），类型系统也会在
 * 漏改构造处时直接报错。</p>
 *
 * @param type      {@link #TYPE_DELTA} 或 {@link #TYPE_COMPLETED}
 * @param text      增量文本（仅 {@code delta}）
 * @param toolCalls 本轮的工具调用记录（仅 {@code completed}；无调用时为 null）
 */
public record RunStreamEvent(String type, String text, List<ToolCallRecord> toolCalls) {

    /** 增量文本。 */
    public static final String TYPE_DELTA = "delta";

    /**
     * 运行结束。
     *
     * <p>工具调用记录挂在这一帧上，而不是做成实时事件：有工具的链路是
     * "先同步跑完工具往返、再把最终答复分块推流"（见 {@code runStream} 的注释），
     * 工具执行期间这条 Flux **还没有发出任何元素**，做不出实时推送。
     * 与其造一个"看起来实时、实际是事后补发"的机制，不如诚实地一次性给出。</p>
     */
    public static final String TYPE_COMPLETED = "completed";

    public static RunStreamEvent delta(String text) {
        return new RunStreamEvent(TYPE_DELTA, text, null);
    }

    /** 结束帧：没有工具调用时 {@code toolCalls} 收敛为 null（少序列化一个空数组）。 */
    public static RunStreamEvent completed(List<ToolCallRecord> toolCalls) {
        return new RunStreamEvent(TYPE_COMPLETED, null,
                toolCalls == null || toolCalls.isEmpty() ? null : toolCalls);
    }

    public boolean isCompleted() {
        return TYPE_COMPLETED.equals(type);
    }
}
