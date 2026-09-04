package com.agentplatform.core.agent.service;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.agent.dto.AgentCreateRequest;
import com.agentplatform.core.agent.dto.AgentPatchRequest;
import com.agentplatform.core.agent.dto.AgentResponse;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.core.model.secret.ModelKeyCrypto;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.enums.AgentStatus;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 智能体管理服务单元测试（CRUD + PATCH 合并）。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private AgentDefinitionRepository repository;

    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService(repository, new AgentConfigValidatorImpl(), modelBindingService());
    }

    private ModelBindingService modelBindingService() {
        return new ModelBindingService(new ModelKeyCrypto("test-key"),
                "http://localhost:4000", "sk-local", "deepseek", "deepseek-chat");
    }

    private AgentCreateRequest createReq() {
        return new AgentCreateRequest(
                "客服助手", null, "描述",
                new Persona("friendly", "conversational", "客服", 0.8, 0.7, 0.5, List.of("您好"), List.of("泄露隐私")),
                "你是{{role}}",
                GenerationConfig.defaults(),
                Capabilities.empty(),
                null,
                com.agentplatform.model.enums.Visibility.private_,
                "t1");
    }

    private AgentDefinition savedDef() {
        return AgentDefinition.builder()
                .id(1L)
                .agentId("agent_abcd")
                .tenantId("t1")
                .name("客服助手")
                .persona(new Persona("friendly", "conversational", "客服", 0.8, 0.7, 0.5, List.of("您好"), List.of("泄露隐私")))
                .systemPrompt("你是{{role}}")
                .generationConfig(GenerationConfig.defaults())
                .capabilities(Capabilities.empty())
                .status(AgentStatus.draft)
                .build();
    }

    @Test
    @DisplayName("创建智能体成功")
    void createAgent() {
        when(repository.save(any(AgentDefinition.class))).thenAnswer(inv -> {
            AgentDefinition d = inv.getArgument(0);
            d.setId(1L);
            if (d.getAgentId() == null) {
                d.setAgentId("agent_abcd");
            }
            return d;
        });
        AgentResponse resp = service.create(createReq(), "t1");
        assertNotNull(resp.agentId());
        assertEquals("客服助手", resp.name());
        assertEquals(AgentStatus.draft.name(), resp.status());
        verify(repository, times(1)).save(any(AgentDefinition.class));
    }

    @Test
    @DisplayName("PATCH 局部更新合并人格字段")
    void patchMergesPersona() {
        when(repository.findByTenantIdAndAgentId("t1", "agent_abcd")).thenReturn(Optional.of(savedDef()));
        when(repository.save(any(AgentDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentPatchRequest patch = new AgentPatchRequest(
                null, null, null,
                new Persona("humorous", null, null, null, null, null, null, null),
                null, null, null, null, null, null);

        AgentResponse resp = service.patch("agent_abcd", patch, "t1");
        assertEquals("humorous", resp.persona().tone());
        // 未传字段保留原值
        assertEquals("客服", resp.persona().role());
    }

    @Test
    @DisplayName("查询不存在的 Agent 抛出 404")
    void getNotFoundThrows() {
        when(repository.findByTenantIdAndAgentId("t1", "agent_missing")).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.get("agent_missing", "t1"));
    }

    @Test
    @DisplayName("删除为物理删除（级联仓储缺省时仍删除主记录）")
    void deleteRemoves() {
        when(repository.findByTenantIdAndAgentId("t1", "agent_abcd")).thenReturn(Optional.of(savedDef()));
        service.delete("agent_abcd", "t1");
        // 级联仓储未注入时应跳过（不 NPE），主记录被物理删除
        verify(repository).delete(any(AgentDefinition.class));
        verify(repository, never()).save(any(AgentDefinition.class));
    }
}