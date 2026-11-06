package com.agentplatform.framework.springai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * E3：系统提示词长度守卫。
 *
 * <p>排在最前（{@link AdvisorOrders#GUARD}），先看预算再往里加东西。
 * 记忆注入、RAG 附文都是「往 system prompt 里加内容」，没有这个守卫时，
 * 长会话会让提示词一路涨到超窗——那时上游报的是 400，而不是「你提示词太长了」。
 *
 * <p>截断而不是丢弃：保留开头能保住人格与硬性约束；末尾追加一句说明，
 * 免得排查时误以为提示词本来就这么短。
 */
public class SystemPromptGuardAdvisor implements CallAdvisor {

    private static final String TRUNCATED_MARK = "\n…（提示词已按预算截断）";

    private final int maxChars;

    public SystemPromptGuardAdvisor(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须为正数");
        }
        this.maxChars = maxChars;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        SystemMessage system = request.prompt().getSystemMessage();
        if (system == null) {
            return chain.nextCall(request);
        }
        String text = system.getText();
        if (text == null || text.length() <= maxChars) {
            return chain.nextCall(request);
        }

        String guarded = text.substring(0, maxChars) + TRUNCATED_MARK;
        return chain.nextCall(request.mutate()
                .prompt(request.prompt().augmentSystemMessage(guarded))
                .build());
    }

    @Override
    public String getName() {
        return "SystemPromptGuardAdvisor";
    }

    @Override
    public int getOrder() {
        return AdvisorOrders.GUARD;
    }
}
