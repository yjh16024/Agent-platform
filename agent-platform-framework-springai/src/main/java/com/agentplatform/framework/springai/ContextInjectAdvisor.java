package com.agentplatform.framework.springai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * E3：把宿主的「上下文片段」注入 system prompt。
 *
 * <p>典型的注入内容：长期画像、早期摘要、向量召回片段。这些都是**平台语义**，
 * 框架不该知道它们从哪来——所以这里只收一个函数，产出什么由宿主决定。
 *
 * <p>顺序 {@link AdvisorOrders#MEMORY}：晚于长度守卫、早于插件钩子。
 * 这个顺序保证「插件能看到记忆」，同时「记忆不会被短路省掉」——
 * 短路是少数情况，为它牺牲插件可见性不划算（技术方案 §4.3.6）。
 */
public class ContextInjectAdvisor implements CallAdvisor {

    private final ContextContributor contributor;

    public ContextInjectAdvisor(ContextContributor contributor) {
        if (contributor == null) {
            throw new IllegalArgumentException("contributor 必填");
        }
        this.contributor = contributor;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String conversationId = conversationId(request);
        String userText = lastUserText(request);

        String block = contributor.contribute(conversationId, userText);
        if (block == null || block.isBlank()) {
            return chain.nextCall(request);
        }

        /*
         * ⚠️ Spring AI 2.0.1 的 Prompt.augmentSystemMessage(String) 是**替换**语义
         * （实测：传进去什么，system 消息就只剩什么），不是名字暗示的「追加」。
         * 所以这里必须自己把原文拼进去，否则人格提示词会被整个覆盖掉。
         */
        String existing = request.prompt().getSystemMessage() == null
                ? ""
                : request.prompt().getSystemMessage().getText();
        String merged = (existing == null || existing.isBlank()) ? block : existing + "\n\n" + block;

        return chain.nextCall(request.mutate()
                .prompt(request.prompt().augmentSystemMessage(merged))
                .build());
    }

    private static String conversationId(ChatClientRequest request) {
        Object value = request.context() == null ? null : request.context().get(ChatMemory.CONVERSATION_ID);
        return value == null ? "" : String.valueOf(value);
    }

    private static String lastUserText(ChatClientRequest request) {
        String text = "";
        for (Message message : request.prompt().getInstructions()) {
            if (message instanceof UserMessage user && user.getText() != null) {
                text = user.getText();
            }
        }
        return text;
    }

    @Override
    public String getName() {
        return "ContextInjectAdvisor";
    }

    @Override
    public int getOrder() {
        return AdvisorOrders.MEMORY;
    }
}
