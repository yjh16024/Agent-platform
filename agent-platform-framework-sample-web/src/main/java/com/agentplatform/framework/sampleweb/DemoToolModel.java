package com.agentplatform.framework.sampleweb;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.ModelCapability;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

/**
 * 示例模型适配器：不联网，只演示「模型请求工具 → 工具返回 → 模型作答」这条链路。
 *
 * <p>换成任意真实实现（OpenAI 兼容 / Anthropic / Spring AI 的 ChatModel 桥）即可接真实模型，
 * 运行时不需要任何改动——这就是 {@link ModelAdapter} 作为契约的意义。
 */
public class DemoToolModel implements ModelAdapter {

    private static final String CALL_ID = "call-1";

    @Override
    public String provider() {
        return "demo";
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        boolean toolAnswered = request.history().stream()
                .anyMatch(ModelAdapter.ChatMessage::isToolResult);

        if (!toolAnswered && request.tools() != null && !request.tools().isEmpty()) {
            // 第一跳：请求调用工具（工具名必须与注册名一致）
            return new ChatResponse("", 12, 6, 0.0, 1L,
                    List.of(new ToolCall(CALL_ID, "clock", null)), "");
        }

        String toolOutput = request.history().stream()
                .filter(ModelAdapter.ChatMessage::isToolResult)
                .map(ModelAdapter.ChatMessage::content)
                .filter(c -> c != null && !c.isBlank())
                .reduce((a, b) -> b)
                .orElse("未知");

        // 第二跳：带着工具结果作答
        return new ChatResponse("现在是 " + toolOutput, 18, 9, 0.0, 1L);
    }

    @Override
    public Flux<ChatDelta> stream(ChatRequest request) {
        return Flux.empty();
    }
}
