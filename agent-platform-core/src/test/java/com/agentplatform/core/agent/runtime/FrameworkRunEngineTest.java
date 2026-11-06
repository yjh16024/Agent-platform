package com.agentplatform.core.agent.runtime;

import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.multimodal.PlatformAttachmentResolver;
import com.agentplatform.core.multimodal.PlatformQuotaPolicy;
import com.agentplatform.core.plugin.runtime.PipelineRunInterceptor;
import com.agentplatform.core.security.rbac.PlatformAuthorizationPolicy;
import com.agentplatform.core.session.MessageTableSessionStore;
import com.agentplatform.core.tool.registry.PlatformToolCatalog;
import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.ModelCapability;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 框架引擎装配点：默认关闭，开启后通过平台接缝跑一轮。
 */
class FrameworkRunEngineTest {

    private final ModelRouter router = mock(ModelRouter.class);
    private final MessageTableSessionStore sessions = mock(MessageTableSessionStore.class);
    private final PlatformToolCatalog tools = mock(PlatformToolCatalog.class);
    private final PlatformQuotaPolicy quota = mock(PlatformQuotaPolicy.class);
    private final PlatformAuthorizationPolicy authorization = mock(PlatformAuthorizationPolicy.class);
    private final PipelineRunInterceptor interceptor = mock(PipelineRunInterceptor.class);
    private final PlatformAttachmentResolver attachments = mock(PlatformAttachmentResolver.class);

    private FrameworkRunEngine engine(String mode) {
        when(tools.all()).thenReturn(java.util.List.of());
        return new FrameworkRunEngine(mode, router, "deepseek", sessions, tools, quota, authorization,
                interceptor, attachments);
    }

    @Test
    void 默认legacy不启用且调用即报错() {
        FrameworkRunEngine engine = engine("legacy");

        assertFalse(engine.isEnabled());
        assertThrows(IllegalStateException.class,
                () -> engine.run(RunCommand.ofText("a", "hi")));
    }

    @Test
    void 开启framework后走平台路由跑完一轮() {
        when(router.chat(anyString(), any())).thenReturn(
                new ModelAdapter.ChatResponse("来自平台的模型", 3, 4, 0.0, 1L));

        FrameworkRunEngine engine = engine("framework");
        assertTrue(engine.isEnabled());

        RunResult result = engine.run(RunCommand.builder("agent-1").text("hi").tenant("t-1").build());

        assertEquals("来自平台的模型", result.reply());
        assertEquals(3, result.usage().inputTokens());
        assertTrue(engine.capabilities().contains(ModelCapability.TOOL));
    }
}
