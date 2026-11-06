package com.agentplatform.core.agent.runtime;

import com.agentplatform.core.agent.dto.AgentRunRequest;
import com.agentplatform.core.agent.dto.AgentRunResponse;
import com.agentplatform.core.agent.service.AgentService;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.multimodal.QuotaService;
import com.agentplatform.core.plugin.runtime.AgentPipeline;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.core.session.SessionService;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.SessionHandle;
import com.agentplatform.model.entity.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D8 双引擎对拍：同一条请求，框架引擎启用时**配额只占一次、会话只写一次**。
 *
 * <p>这两条不变量是切换引擎时最容易破的——破了不会报错，只会账单翻倍、历史重复。
 * 所以它们必须由测试锁住，而不是靠注释提醒。</p>
 *
 * <p>legacy 路径的等价行为由既有 664 例覆盖（配额在 {@code prepare} 里占一次、
 * 会话由 {@code recordExchange} 写一次），这里只锁"框架路径不会重复做这两件事"。</p>
 */
class DualEngineDispatchTest {

    private final AgentService agentService = mock(AgentService.class);
    private final ModelRouter modelRouter = mock(ModelRouter.class);
    private final AgentPipeline agentPipeline = mock(AgentPipeline.class);
    private final PluginRuntime pluginRuntime = mock(PluginRuntime.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final FrameworkRunEngine engine = mock(FrameworkRunEngine.class);

    private AgentRuntimeService service;

    @BeforeEach
    void setUp() {
        AgentDefinition agent = AgentDefinition.builder()
                .agentId("agent-1")
                .tenantId("t-1")
                .name("测试智能体")
                .systemPrompt("你是助手")
                .build();
        when(agentService.getOrThrow("t-1", "agent-1")).thenReturn(agent);

        service = new AgentRuntimeService(agentService, modelRouter, agentPipeline, pluginRuntime, quotaService);
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "frameworkRunEngine", engine);
        ReflectionTestUtils.setField(service, "defaultModel", "deepseek-chat");
        ReflectionTestUtils.setField(service, "defaultProvider", "deepseek");
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(
                "r-1", "chat", "agent-1",
                null, null, null, null,
                List.of(new AgentRunRequest.Message("user", "你好")),
                "s-1", null, null, null,
                Boolean.FALSE,
                Map.of("tenant_id", "t-1", "user_id", "u-1"),
                null);
    }

    @Test
    void 框架引擎启用时配额不在服务层预占() {
        when(engine.isEnabled()).thenReturn(true);
        when(engine.run(any())).thenReturn(new RunResult("r-1", "trace-1", "agent-1", "s-1",
                "框架答复", "", "deepseek", "deepseek-chat",
                new RunResult.Usage(3, 4), List.of(), List.of()));
        when(sessionService.resolve(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new SessionHandle("s-1", "t-1", "agent-1", "u-1", null));

        AgentRunResponse resp = service.run(request());

        assertEquals("框架答复", resp.output().content());
        // 配额由框架的 QuotaPolicy 占一次；服务层不能再占，否则双计
        verify(quotaService, never()).checkAndIncrement(any(), any(), any());
    }

    @Test
    void 框架引擎启用时不由服务层重复写会话() {
        when(engine.isEnabled()).thenReturn(true);
        when(engine.run(any())).thenReturn(new RunResult("r-1", "trace-1", "agent-1", "s-1",
                "框架答复", "", "deepseek", "deepseek-chat",
                new RunResult.Usage(1, 1), List.of(), List.of()));
        when(sessionService.resolve(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new SessionHandle("s-1", "t-1", "agent-1", "u-1", null));

        service.run(request());

        // 框架的 SessionStore 已写同一张表，这里再写就是双写
        verify(sessionService, never()).recordExchange(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 引擎未启用时仍走legacy路径() {
        when(engine.isEnabled()).thenReturn(false);

        // legacy 路径会预占配额；这里只断言"确实没有走框架引擎"
        try {
            service.run(request());
        } catch (RuntimeException ignored) {
            // legacy 路径需要完整的模型/管线桩，本测试不关心它的结果
        }

        verify(engine, never()).run(any());
        verify(quotaService).checkAndIncrement("t-1", "model_calls", null);
    }
}
