package com.agentplatform.core.agent.service;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.Persona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置校验器单元测试（责任链）。
 */
class AgentConfigValidatorTest {

    private AgentConfigValidatorImpl validator;

    @BeforeEach
    void setUp() {
        validator = new AgentConfigValidatorImpl();
    }

    private AgentDefinition validDef() {
        return AgentDefinition.builder()
                .agentId("agent_test")
                .tenantId("t1")
                .name("test")
                .systemPrompt("你是{{role}}，语气{{tone}}")
                .persona(new Persona("friendly", "conversational", "客服", 0.8, 0.7, 0.5, null, null))
                .generationConfig(new GenerationConfig("gpt-4o-mini", "openai", 0.7, null, null,
                        2048, null, null, null, "text", 60000, null))
                .build();
    }

    @Test
    @DisplayName("合法配置通过校验")
    void validConfigPasses() {
        assertDoesNotThrow(() -> validator.validate(validDef()));
    }

    @Test
    @DisplayName("空提示词被拒绝")
    void emptyPromptRejected() {
        AgentDefinition def = validDef();
        def.setSystemPrompt("   ");
        BizException ex = assertThrows(BizException.class, () -> validator.validate(def));
        assertTrue(ex.getMessage().contains("system_prompt"));
    }

    @Test
    @DisplayName("temperature 超界被拒绝")
    void temperatureOutOfRangeRejected() {
        AgentDefinition def = validDef();
        def.setGenerationConfig(new GenerationConfig("gpt", "openai", 3.0, null, null,
                2048, null, null, null, "text", 60000, null));
        BizException ex = assertThrows(BizException.class, () -> validator.validate(def));
        assertTrue(ex.getMessage().contains("temperature"));
    }

    @Test
    @DisplayName("warmth 超界被拒绝")
    void warmthOutOfRangeRejected() {
        AgentDefinition def = validDef();
        def.setPersona(new Persona("friendly", null, null, 1.5, null, null, null, null));
        BizException ex = assertThrows(BizException.class, () -> validator.validate(def));
        assertTrue(ex.getMessage().contains("warmth"));
    }
}