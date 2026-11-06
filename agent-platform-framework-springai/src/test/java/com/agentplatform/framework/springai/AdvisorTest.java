package com.agentplatform.framework.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvisorTest {

    private static ChatClientRequest request(String system, String user, String conversationId) {
        return new ChatClientRequest(
                new Prompt(List.of(new SystemMessage(system), new UserMessage(user))),
                Map.of(ChatMemory.CONVERSATION_ID, conversationId));
    }

    @Test
    void 超长提示词应被截断并留标记() {
        CapturingChain chain = new CapturingChain();
        SystemPromptGuardAdvisor advisor = new SystemPromptGuardAdvisor(10);

        advisor.adviseCall(request("0123456789ABCDEF", "hi", "c-1"), chain);

        String guarded = chain.captured.prompt().getSystemMessage().getText();
        assertTrue(guarded.startsWith("0123456789"));
        assertTrue(guarded.contains("截断"));
        assertFalse(guarded.contains("ABCDEF"));
    }

    @Test
    void 未超预算时原样放行且不新建请求对象() {
        CapturingChain chain = new CapturingChain();
        SystemPromptGuardAdvisor advisor = new SystemPromptGuardAdvisor(100);
        ChatClientRequest request = request("短的提示词", "hi", "c-1");

        advisor.adviseCall(request, chain);

        assertSame(request, chain.captured);
    }

    @Test
    void 上下文片段应追加到system提示词() {
        CapturingChain chain = new CapturingChain();
        ContextInjectAdvisor advisor = new ContextInjectAdvisor(
                (conversationId, userText) -> "用户画像：" + conversationId + " / " + userText);

        advisor.adviseCall(request("你是助手", "帮我退货", "c-9"), chain);

        String system = chain.captured.prompt().getSystemMessage().getText();

        // Spring AI 的 augmentSystemMessage 是替换语义，原人格必须由我们自己拼进去
        assertTrue(system.contains("你是助手"), "实际内容=" + system);
        assertTrue(system.contains("用户画像：c-9 / 帮我退货"), "实际内容=" + system);
        assertTrue(system.indexOf("你是助手") < system.indexOf("用户画像"), "注入内容应在原提示词之后");
    }

    @Test
    void 没有可注入内容时应原样放行() {
        CapturingChain chain = new CapturingChain();
        ContextInjectAdvisor advisor = new ContextInjectAdvisor((id, text) -> "  ");
        ChatClientRequest request = request("你是助手", "hi", "c-1");

        advisor.adviseCall(request, chain);

        assertSame(request, chain.captured);
    }

    @Test
    void 顺序常量必须满足守卫早于记忆早于插件() {
        assertTrue(AdvisorOrders.GUARD < AdvisorOrders.MEMORY);
        assertTrue(AdvisorOrders.MEMORY < AdvisorOrders.PLUGIN_HOOK);
        assertTrue(AdvisorOrders.PLUGIN_HOOK < AdvisorOrders.RAG);
        assertTrue(AdvisorOrders.RAG < AdvisorOrders.LOGGING);
    }

    @Test
    void 两个Advisor的order应取自常量而不是字面量() {
        assertEquals(AdvisorOrders.GUARD, new SystemPromptGuardAdvisor(10).getOrder());
        assertEquals(AdvisorOrders.MEMORY, new ContextInjectAdvisor((a, b) -> "").getOrder());
    }

    private static class CapturingChain implements CallAdvisorChain {

        private ChatClientRequest captured;

        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            this.captured = request;
            return new ChatClientResponse(null, Map.of());
        }

        @Override
        public List<CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(CallAdvisor advisor) {
            return this;
        }
    }
}
