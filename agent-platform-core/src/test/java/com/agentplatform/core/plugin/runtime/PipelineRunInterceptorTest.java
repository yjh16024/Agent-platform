package com.agentplatform.core.plugin.runtime;

import com.agentplatform.framework.api.InterceptorContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 插件接缝：平台钩子 → 框架拦截器。
 */
class PipelineRunInterceptorTest {

    private final AgentPipeline pipeline = mock(AgentPipeline.class);
    private final PipelineRunInterceptor interceptor = new PipelineRunInterceptor(pipeline);

    private static InterceptorContext ctx(String runId, String input) {
        return new InterceptorContext(runId, "agent-1", "t-1", "s-1", input);
    }

    @Test
    void 改写输入应原样返回() {
        when(pipeline.beforeStream("agent-1", "r-1", "原始"))
                .thenReturn(new AgentPipeline.StreamHookResult("改写后", null));

        assertEquals("改写后", interceptor.beforeModel(ctx("r-1", "原始")));
        assertNull(interceptor.shortCircuit(ctx("r-1", "改写后")), "没有短路时不应返回内容");
    }

    @Test
    void 短路内容应被暂存并与取走() {
        when(pipeline.beforeStream("agent-1", "r-2", "你好"))
                .thenReturn(new AgentPipeline.StreamHookResult("你好", "固定回复"));

        assertEquals("你好", interceptor.beforeModel(ctx("r-2", "你好")));
        assertEquals("固定回复", interceptor.shortCircuit(ctx("r-2", "你好")));
        // 取即删：第二次不应再返回（避免跨轮串话）
        assertNull(interceptor.shortCircuit(ctx("r-2", "你好")));
    }

    @Test
    void 插件产物走artifacts通道而不是替换正文() {
        when(pipeline.afterStream("agent-1", "r-3", "模型原文"))
                .thenReturn(Map.of("audio_url", "http://a.mp3"));

        // afterModel 只负责"不改写正文"；产物由 artifacts 单独带出（R12）
        assertNull(interceptor.afterModel(ctx("r-3", "x"), "模型原文"));
        assertEquals("http://a.mp3", interceptor.artifacts(ctx("r-3", "x"), "模型原文").get("audio_url"));
        verify(pipeline).afterStream(eq("agent-1"), eq("r-3"), eq("模型原文"));
    }

    @Test
    void 没有产物时返回空Map() {
        when(pipeline.afterStream(any(), any(), any())).thenReturn(null);

        assertTrue(interceptor.artifacts(ctx("r-6", "x"), "答").isEmpty());
    }

    @Test
    void 失败时使用插件兜底话术() {
        when(pipeline.onStreamError(eq("agent-1"), eq("r-4"), eq("问"), any()))
                .thenReturn("稍后再试");

        assertEquals("稍后再试", interceptor.onError(ctx("r-4", "问"), new IllegalStateException("boom")));
    }

    @Test
    void 插件不兜底时返回null让异常继续上抛() {
        when(pipeline.onStreamError(any(), any(), any(), any())).thenReturn(null);

        assertNull(interceptor.onError(ctx("r-5", "问"), new IllegalStateException("boom")));
    }
}
