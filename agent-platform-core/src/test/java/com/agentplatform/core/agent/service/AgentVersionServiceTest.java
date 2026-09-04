package com.agentplatform.core.agent.service;

import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.entity.AgentVersion;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.Persona;
import com.agentplatform.model.repository.AgentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 版本管理服务单元测试（快照 / 发布 / 回滚 / Diff）。
 */
@ExtendWith(MockitoExtension.class)
class AgentVersionServiceTest {

    @Mock
    private AgentVersionRepository versionRepository;
    @Mock
    private AgentService agentService;

    private AgentVersionService service;

    @BeforeEach
    void setUp() {
        service = new AgentVersionService(versionRepository, agentService);
    }

    private AgentDefinition def() {
        return AgentDefinition.builder()
                .id(1L)
                .agentId("agent_abcd")
                .tenantId("t1")
                .name("客服")
                .persona(new Persona("friendly", null, "客服", null, null, null, null, null))
                .systemPrompt("你是科服")
                .generationConfig(GenerationConfig.defaults())
                .build();
    }

    @Test
    @DisplayName("首次发布版本号为 v1.0.0")
    void firstPublishIsV100() {
        when(agentService.getOrThrow("t1", "agent_abcd")).thenReturn(def());
        when(versionRepository.findByAgentIdOrderByReleasedAtDesc("agent_abcd")).thenReturn(List.of());
        when(versionRepository.save(any(AgentVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentVersion v = service.publish("agent_abcd", "t1");
        assertEquals("v1.0.0", v.getVersion());
        assertNotNull(v.getPromptHash());
    }

    @Test
    @DisplayName("Diff 返回提示词差异")
    void diffReturnsPromptDiff() {
        AgentVersion v1 = AgentVersion.builder()
                .agentId("agent_abcd").version("v1.0.0")
                .snapshot(Map.of("system_prompt", "你是客服", "name", "客服"))
                .build();
        AgentVersion v2 = AgentVersion.builder()
                .agentId("agent_abcd").version("v1.1.0")
                .snapshot(Map.of("system_prompt", "你是资深客服专家", "name", "客服"))
                .build();

        when(versionRepository.findByAgentIdAndVersion("agent_abcd", "v1.0.0")).thenReturn(Optional.of(v1));
        when(versionRepository.findByAgentIdAndVersion("agent_abcd", "v1.1.0")).thenReturn(Optional.of(v2));

        Map<String, Object> diff = service.diff("agent_abcd", "v1.0.0", "v1.1.0", "t1");
        assertNotNull(diff.get("prompt_diff"));
        assertEquals("v1.0.0", diff.get("from"));
    }

    @Test
    @DisplayName("回滚到不存在版本抛出异常")
    void rollbackMissingVersionThrows() {
        when(versionRepository.findByAgentIdAndVersion("agent_abcd", "v99.0.0")).thenReturn(Optional.empty());
        assertThrows(Exception.class, () -> service.rollback("agent_abcd", "v99.0.0", "t1"));
    }
}