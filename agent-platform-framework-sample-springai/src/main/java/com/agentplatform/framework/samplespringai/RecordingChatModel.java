package com.agentplatform.framework.samplespringai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地桩模型：不联网，记录最后一次请求，便于断言「记忆与注入确实进了提示词」。
 *
 * <p>换成真实的 OpenAI 兼容模型只需换一个 Bean，示例其余部分不用改。</p>
 */
public class RecordingChatModel implements ChatModel {

    private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

    @Override
    public ChatResponse call(Prompt prompt) {
        lastPrompt.set(prompt);
        String reply = "应答：" + lastUserText(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    public Prompt lastPrompt() {
        return lastPrompt.get();
    }

    private static String lastUserText(Prompt prompt) {
        String text = "";
        for (Message message : prompt.getInstructions()) {
            if (message instanceof UserMessage user && user.getText() != null) {
                text = user.getText();
            }
        }
        return text;
    }
}
