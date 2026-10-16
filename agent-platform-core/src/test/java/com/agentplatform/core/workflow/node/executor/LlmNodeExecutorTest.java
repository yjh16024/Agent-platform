package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LLM 节点执行器 —— 守住「模型绑定要被真正用上」这一条。
 *
 * <h3>★ 为什么专门为它写测试（2026-10-12）</h3>
 * 此前这里构造 {@code ChatRequest} 时用的是**不带 baseUrl/apiKey 的旧重载**，
 * 于是运行时会回退到全局网关（{@code spring.ai.openai.base-url}，默认 {@code http://localhost:4000}）——
 * <b>在「模型设置」页配好的直连完全没被用上</b>，而报错长这样：
 *
 * <pre>
 * OpenAIIoException: Request failed
 * Caused by: ConnectException: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4000
 * </pre>
 *
 * 它发生在**连接层**，半点看不出跟"模型设置"有关 —— 用户排查时根本不会往那儿想。
 * 所以这个行为必须由测试钉住：**节点执行时必须把解析出来的 baseUrl/apiKey 传下去**。
 */
class LlmNodeExecutorTest {

    private static WorkflowNode llmNode(Map<String, Object> config) {
        return new WorkflowNode("reply", NodeType.LLM, "组织回复", "end",
                null, "answer", null, config);
    }

    private static WorkflowContext ctx() {
        return new WorkflowContext(Map.of("city", "合肥"));
    }

    /** 让 ModelRouter 返回一个固定响应，并捕获实际传进去的请求。 */
    private static ModelRouter capturingRouter(ArgumentCaptor<ModelAdapter.ChatRequest> captor) {
        ModelRouter router = Mockito.mock(ModelRouter.class);
        Mockito.when(router.chat(Mockito.anyString(), captor.capture()))
                .thenReturn(new ModelAdapter.ChatResponse("ok", 3, 5, 0.0, 12L));
        return router;
    }

    private static ModelBindingService binding(String provider, String model, String baseUrl, String key) {
        ModelBindingService svc = Mockito.mock(ModelBindingService.class);
        Mockito.when(svc.resolve(null)).thenReturn(new ModelBindingService.ResolvedModel(
                provider, model, baseUrl, key, ModelBindingService.Routing.PLATFORM));
        return svc;
    }

    @Test
    @DisplayName("★ 必须把平台绑定的 baseUrl/apiKey 传下去（否则会去连 localhost:4000）")
    void passesResolvedBaseUrlAndKey() {
        ArgumentCaptor<ModelAdapter.ChatRequest> captor = ArgumentCaptor.forClass(ModelAdapter.ChatRequest.class);
        LlmNodeExecutor exec = new LlmNodeExecutor(capturingRouter(captor));
        ReflectionTestUtils.setField(exec, "modelBindingService",
                binding("deepseek", "deepseek-v4-flash", "https://api.deepseek.com", "sk-real"));

        exec.execute(llmNode(Map.of("prompt", "你好")), ctx());

        ModelAdapter.ChatRequest sent = captor.getValue();
        assertEquals("https://api.deepseek.com", sent.baseUrl(),
                "baseUrl 为空时适配器会回退到全局网关（localhost:4000）—— 那正是故障现场");
        assertEquals("sk-real", sent.apiKey());
    }

    @Test
    @DisplayName("★ provider/model 未填时采用平台绑定（用户只写提示词也能跑）")
    void fallsBackToPlatformProviderAndModel() {
        ArgumentCaptor<ModelAdapter.ChatRequest> captor = ArgumentCaptor.forClass(ModelAdapter.ChatRequest.class);
        LlmNodeExecutor exec = new LlmNodeExecutor(capturingRouter(captor));
        ReflectionTestUtils.setField(exec, "modelBindingService",
                binding("deepseek", "deepseek-v4-flash", "https://api.deepseek.com", "sk-real"));

        // auto 也应当被视为"没指定"—— 它本来就表示跟随平台
        exec.execute(llmNode(Map.of("prompt", "你好", "provider", "auto")), ctx());

        assertEquals("deepseek-v4-flash", captor.getValue().model());
    }

    @Test
    @DisplayName("★ 节点显式填的 provider/model 优先于平台绑定（节点上填了就该听它的）")
    void nodeConfigWinsOverPlatform() {
        ArgumentCaptor<ModelAdapter.ChatRequest> captor = ArgumentCaptor.forClass(ModelAdapter.ChatRequest.class);
        LlmNodeExecutor exec = new LlmNodeExecutor(capturingRouter(captor));
        ReflectionTestUtils.setField(exec, "modelBindingService",
                binding("deepseek", "deepseek-v4-flash", "https://api.deepseek.com", "sk-real"));

        exec.execute(llmNode(Map.of("prompt", "你好",
                "provider", "openai", "model", "gpt-4o-mini")), ctx());

        ModelAdapter.ChatRequest sent = captor.getValue();
        assertEquals("gpt-4o-mini", sent.model());
        // baseUrl 仍来自平台绑定：节点只填 provider/model 时，用户期望的是"用平台配好的那个地址"
        assertEquals("https://api.deepseek.com", sent.baseUrl());
    }

    @Test
    @DisplayName("没有绑定服务时不崩（单测直接 new 的场景）")
    void worksWithoutBindingService() {
        ArgumentCaptor<ModelAdapter.ChatRequest> captor = ArgumentCaptor.forClass(ModelAdapter.ChatRequest.class);
        LlmNodeExecutor exec = new LlmNodeExecutor(capturingRouter(captor));
        // 刻意不注入 modelBindingService

        exec.execute(llmNode(Map.of("prompt", "你好", "model", "deepseek-chat")), ctx());

        assertNull(captor.getValue().baseUrl(), "拿不到绑定时保持原行为（回退全局网关），不应抛异常");
    }

    @Test
    @DisplayName("系统提示词与用户提示词都做变量替换")
    void resolvesVariablesInBothPrompts() {
        ArgumentCaptor<ModelAdapter.ChatRequest> captor = ArgumentCaptor.forClass(ModelAdapter.ChatRequest.class);
        LlmNodeExecutor exec = new LlmNodeExecutor(capturingRouter(captor));
        ReflectionTestUtils.setField(exec, "modelBindingService",
                binding("deepseek", "m", "https://api.deepseek.com", "k"));

        exec.execute(llmNode(Map.of(
                "system_prompt", "用户在问 ${city}",
                "prompt", "请回答 ${city} 的天气")), ctx());

        ModelAdapter.ChatRequest sent = captor.getValue();
        assertEquals("用户在问 合肥", sent.systemPrompt());
        assertEquals("请回答 合肥 的天气", sent.userMessage());
    }
}
