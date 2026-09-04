package com.agentplatform.core.agent.service;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.agent.dto.AgentCreateRequest;
import com.agentplatform.core.agent.dto.AgentResponse;
import com.agentplatform.core.agent.runtime.AgentRuntimeService;
import com.agentplatform.core.model.adapter.MockModelAdapter;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.core.model.secret.ModelKeyCrypto;
import com.agentplatform.core.plugin.runtime.AgentPipeline;
import com.agentplatform.core.plugin.runtime.ExtensionRegistry;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.Persona;
import com.agentplatform.model.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 端到端集成测试（Mock 依赖，验证「创建 → 运行」完整链路）。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceIntegrationTest {

    @Mock
    private AgentDefinitionRepository repository;

    private AgentService agentService;
    private AgentRuntimeService runtimeService;

    @BeforeEach
    void setUp() {
        AgentConfigValidatorImpl validator = new AgentConfigValidatorImpl();
        ModelBindingService bindingService = new ModelBindingService(new ModelKeyCrypto("test-key"),
                "http://localhost:4000", "sk-local", "deepseek", "deepseek-chat");
        agentService = new AgentService(repository, validator, bindingService);

        // 使用 Mock 模型 + 手工路由，无需真实 LLM
        ModelRouter router = mock(ModelRouter.class);
        when(router.chat(any(), any())).thenAnswer(inv -> {
            var req = inv.getArgument(1, com.agentplatform.core.model.adapter.ModelAdapter.ChatRequest.class);
            return new MockModelAdapter().chat(req);
        });
        ExtensionRegistry registry = new ExtensionRegistry(new ToolRegistry());
        runtimeService = new AgentRuntimeService(agentService, router,
                new AgentPipeline(registry), new PluginRuntime(registry, List.of()),
                new com.agentplatform.core.multimodal.QuotaService());
    }

    @Test
    @DisplayName("端到端：创建 Agent → 运行对话返回响应")
    void endToEndCreateAndRun() {
        AtomicReference<com.agentplatform.model.entity.AgentDefinition> saved = new AtomicReference<>();
        when(repository.save(any(com.agentplatform.model.entity.AgentDefinition.class))).thenAnswer(inv -> {
            var d = inv.getArgument(0, com.agentplatform.model.entity.AgentDefinition.class);
            if (d.getAgentId() == null) {
                d.setAgentId("agent_e2e");
            }
            if (d.getId() == null) {
                d.setId(1L);
            }
            saved.set(d);
            return d;
        });

        // 创建
        AgentResponse created = agentService.create(new AgentCreateRequest(
                "端到端助手", null, null,
                new Persona("friendly", null, "助手", null, null, null, null, null),
                "你是{{role}}",
                GenerationConfig.defaults(),
                Capabilities.empty(),
                null,
                com.agentplatform.model.enums.Visibility.private_,
                "t1"), "t1");

        // 运行
        when(repository.findByTenantIdAndAgentId("t1", created.agentId())).thenReturn(
                java.util.Optional.of(saved.get()));

        var runReq = new com.agentplatform.core.agent.dto.AgentRunRequest(
                null, "agent", created.agentId(), null, null, null,
                new com.agentplatform.core.agent.dto.AgentRunRequest.ModelOverride("gpt-4o-mini", "openai", null),
                List.of(new com.agentplatform.core.agent.dto.AgentRunRequest.Message("user", "你好")),
                "sess_1", null, null, null, false,
                java.util.Map.of("tenant_id", "t1"));

        var response = runtimeService.run(runReq);
        assertNotNull(response.runId());
        assertEquals("assistant", response.output().role());
        assertFalse(response.output().content().isEmpty());
    }
}