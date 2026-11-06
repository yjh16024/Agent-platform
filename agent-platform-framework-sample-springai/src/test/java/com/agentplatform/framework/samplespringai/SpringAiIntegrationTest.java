package com.agentplatform.framework.samplespringai;

import com.agentplatform.framework.springai.ContextInjectAdvisor;
import com.agentplatform.framework.springai.SystemPromptGuardAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「集成」二字的实物证据。
 *
 * <p>这个测试写的是**纯 Spring AI 代码**（ChatClient + MessageWindowChatMemory），
 * 唯一的框架痕迹是构造器里注入的两个 Advisor 与一个 ChatMemoryRepository Bean
 * ——而它们全部由 starter 自动装配。
 */
@SpringBootTest
class SpringAiIntegrationTest {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ChatMemoryRepository memoryRepository;

    @Autowired
    private SystemPromptGuardAdvisor guardAdvisor;

    @Autowired
    private ContextInjectAdvisor contextInjectAdvisor;

    private ChatClient client() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryRepository)
                .maxMessages(20)
                .build();

        return ChatClient.builder(chatModel)
                .defaultSystem("你是客服助手")
                .defaultAdvisors(guardAdvisor, contextInjectAdvisor,
                        MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    @Test
    void 只加一个依赖就有了持久化记忆() {
        ChatClient client = client();

        client.prompt().user("我叫小明")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "c-1"))
                .call().content();
        client.prompt().user("我叫什么")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "c-1"))
                .call().content();

        // 第二轮请求里应当带着第一轮的内容
        assertTrue(allText(lastPrompt()).contains("小明"),
                "记忆没有进入第二轮请求：" + allText(lastPrompt()));
        // 并且它落在了框架的会话存储里（不是 Spring AI 的进程内 Map）
        assertFalse(memoryRepository.findByConversationId("c-1").isEmpty());
    }

    @Test
    void 宿主提供的上下文会被注入系统提示词() {
        client().prompt().user("你好")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "c-2"))
                .call().content();

        String system = systemText(lastPrompt());
        assertTrue(system.contains("你是客服助手"), "原人格提示词不能被覆盖：" + system);
        assertTrue(system.contains("用户画像"), "注入内容缺失：" + system);
    }

    @Test
    void 两个Advisor确实由starter装配() {
        assertTrue(guardAdvisor != null && contextInjectAdvisor != null);
    }

    private Prompt lastPrompt() {
        return ((RecordingChatModel) chatModel).lastPrompt();
    }

    private static String allText(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            sb.append(message.getText()).append('\n');
        }
        return sb.toString();
    }

    private static String systemText(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            if (message instanceof SystemMessage system && system.getText() != null) {
                sb.append(system.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
