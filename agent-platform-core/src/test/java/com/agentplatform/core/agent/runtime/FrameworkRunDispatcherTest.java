package com.agentplatform.core.agent.runtime;

import com.agentplatform.core.agent.dto.AgentRunResponse;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.framework.api.InputPart;
import com.agentplatform.framework.api.Principal;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.ToolOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主链路分流的映射层：平台 DTO ↔ 框架契约。
 */
class FrameworkRunDispatcherTest {

    private static final ModelBindingService.ResolvedModel RESOLVED =
            new ModelBindingService.ResolvedModel("deepseek", "deepseek-chat",
                    "https://api.deepseek.com", "sk-1", ModelBindingService.Routing.DIRECT);

    @Test
    void 命令应带上提示词凭证与附件() {
        RunCommand command = FrameworkRunDispatcher.toCommand(
                "agent-1", "帮我看看", "s-1", "t-1",
                new Principal("u-1", "t-1", java.util.Set.of("operator")),
                null, RESOLVED, "你是客服助手",
                List.of(new InputPart.Ref("file", "f-1")),
                ToolOptions.all(3),
                java.util.Map.of("images", List.of("data:image/png;base64,AAA")));

        assertEquals("agent-1", command.agentId());
        assertEquals("你是客服助手", command.systemPrompt());
        assertEquals("deepseek-chat", command.model());
        assertEquals("sk-1", command.credentials().apiKey());
        assertEquals(1, command.refs().size());
        assertEquals("t-1", command.principal().tenantId());
        assertTrue(command.tools().enabled());
        assertEquals(1, ((List<?>) command.extra().get("images")).size());
    }

    @Test
    void 响应应映射正文用量与工具记录() {
        RunResult result = new RunResult("r-1", "trace-1", "agent-1", "s-1",
                "答复", "思考", "deepseek", "deepseek-chat",
                new RunResult.Usage(10, 20),
                List.of(new RunResult.ToolCall("c-1", "clock", true, 120L, null)),
                List.of());

        AgentRunResponse response = FrameworkRunDispatcher.toResponse(result, "chat", "trace-1");

        assertEquals("答复", response.output().content());
        assertEquals("思考", response.output().reasoning());
        assertEquals(10, response.usage().promptTokens());
        assertEquals("trace-1", response.traceId());
        assertNotNull(response.toolCalls());
        assertEquals(1, response.toolCalls().size());
        assertEquals("clock", response.toolCalls().get(0).name());
        assertEquals(120L, response.toolCalls().get(0).latencyMs());
    }

    @Test
    void 没有工具调用时不应出现空数组() {
        RunResult result = new RunResult("r-2", "t", "a", null, "答", null,
                "p", "m", new RunResult.Usage(1, 1), List.of(), List.of());

        AgentRunResponse response = FrameworkRunDispatcher.toResponse(result, "chat", "t");

        org.junit.jupiter.api.Assertions.assertNull(response.toolCalls());
        assertEquals(null, response.sessionId());
    }
}
