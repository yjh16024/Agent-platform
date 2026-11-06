package com.agentplatform.framework.springai;

import com.agentplatform.framework.api.ModelAdapter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 框架消息 ↔ Spring AI 消息的双向转换。
 *
 * <p>这是 E1 的技术难点所在：两套消息模型看起来同构，但**字段语义不同**——
 * Spring AI 的工具结果是 {@code ToolResponseMessage}（可含多条 response），
 * 框架侧是「一条 tool 消息 + 一个 toolCallId」；转换必须保证 toolCallId 不丢，
 * 否则上游会以「引用了不存在的调用」400。
 */
public final class SpringAiMessageConverter {

    private SpringAiMessageConverter() {
    }

    public static Message toSpringAi(ModelAdapter.ChatMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String content = message.content() == null ? "" : message.content();
        String role = message.role() == null ? "user" : message.role().toLowerCase();

        return switch (role) {
            case "system" -> new SystemMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "tool" -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            message.toolCallId() == null ? "" : message.toolCallId(),
                            "",
                            content)))
                    .build();
            default -> new UserMessage(content);
        };
    }

    public static ModelAdapter.ChatMessage toFramework(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (message instanceof ToolResponseMessage tool) {
            String text = tool.getResponses() == null ? "" : tool.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .filter(data -> data != null)
                    .collect(Collectors.joining("\n"));
            String callId = (tool.getResponses() == null || tool.getResponses().isEmpty())
                    ? null
                    : tool.getResponses().get(0).id();
            return ModelAdapter.ChatMessage.tool(callId, text);
        }

        String text = message.getText();
        MessageType type = message.getMessageType();
        String role = switch (type) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
            default -> "user";
        };
        return new ModelAdapter.ChatMessage(role, text);
    }

    /**
     * 幂等键：用于 {@code saveAll} 的「全量覆盖 → 差量追加」翻译。
     *
     * <p>Spring AI 的消息没有稳定 id，所以用 role + 正文 + toolCallId 组合。
     * 同一条消息重复出现（窗口重发）时，这个键相同，据此跳过。
     */
    public static String keyOf(ModelAdapter.ChatMessage message) {
        String role = message.role() == null ? "" : message.role();
        String content = message.content() == null ? "" : message.content();
        String callId = message.toolCallId() == null ? "" : message.toolCallId();
        return role + "|" + content + "|" + callId;
    }
}
