package com.agentplatform.core.agent.runtime;

import com.agentplatform.core.agent.dto.AgentRunRequest;
import com.agentplatform.core.agent.service.AgentService;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.Persona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent Runtime 单元测试（提示词组装 / 模板填充 / 参数合并）。
 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeServiceTest {

    @Mock
    private AgentService agentService;
    @Mock
    private ModelRouter modelRouter;

    private AgentRuntimeService service;

    @BeforeEach
    void setUp() {
        service = new AgentRuntimeService(agentService, modelRouter, null, null, null);
    }

    private AgentRunRequest req() {
        return new AgentRunRequest(
                null, "agent", "agent_abcd", null, null, null,
                new AgentRunRequest.ModelOverride("gpt-4o-mini", "openai", 0.9),
                List.of(new AgentRunRequest.Message("user", "帮我退个货")),
                "sess_1",
                new AgentRunRequest.ContextConfig(10, false, null),
                null, null, false,
                Map.of("tenant_id", "t1", "user_id", "u1"));
    }

    @Test
    @DisplayName("模板变量 fillTemplate 正确填充")
    void fillTemplate() {
        String filled = service.fillTemplate("你是{{role}}，语气{{tone}}，用户：{{user}}", req());
        assertEquals("你是，语气，用户：u1", filled);
    }

    @Test
    @DisplayName("系统提示词组装包含人格段与禁区")
    void assembleWithPersona() {
        Persona persona = new Persona(
                "friendly", "conversational", "客服专家", 0.9, 0.8, 0.6,
                null, List.of("泄露隐私", "虚假承诺"));

        String prompt = service.assembleSystemPrompt(persona, "处理用户咨询", req());
        assertTrue(prompt.contains("客服专家"));
        assertTrue(prompt.contains("绝不泄露隐私"));
        assertTrue(prompt.contains("处理用户咨询"));
    }

    @Test
    @DisplayName("★ 输出格式说明：追加而不覆盖原有提示词")
    void renderGuideAppendsNotReplaces() {
        String original = "你是资深客服，回答要简洁。";
        String out = service.withRenderGuide(original);

        assertTrue(out.startsWith(original), "原有提示词必须原样保留在最前面");
        assertTrue(out.contains("输出格式"), "要有一段格式说明");
        assertTrue(out.contains("```chart"), "要给出 chart 围栏的写法示例");
    }

    @Test
    @DisplayName("★ 格式说明里只提三种图：多写一种，模型就会输出渲染不出来的东西")
    void renderGuideMentionsOnlySupportedTypes() {
        String out = service.withRenderGuide("x");

        assertTrue(out.contains("bar"));
        assertTrue(out.contains("line"));
        assertTrue(out.contains("pie"));
        // 前端 ChartBlock 只认这三种；说明里若出现别的，模型就会照着写
        for (String unsupported : List.of("radar", "scatter", "gauge", "graph", "mermaid")) {
            assertFalse(out.contains(unsupported),
                    "说明里不该出现前端不支持的图型：" + unsupported);
        }
    }

    @Test
    @DisplayName("★ 格式说明要带上「不要滥用」的边界（否则模型每段都画图）")
    void renderGuideDiscouragesOveruse() {
        String out = service.withRenderGuide("x");
        assertTrue(out.contains("不要"), "必须写明使用边界：" + out.substring(0, Math.min(200, out.length())));
    }

    @Test
    @DisplayName("null / 空提示词安全（只产出格式说明，不 NPE）")
    void renderGuideHandlesNull() {
        assertTrue(service.withRenderGuide(null).contains("输出格式"));
        assertTrue(service.withRenderGuide("").contains("输出格式"));
    }

    @Test
    @DisplayName("请求级 temperature 覆盖 Agent 保存值")
    void mergeGenerationConfig() {
        GenerationConfig base = GenerationConfig.defaults(); // temperature=0.7
        GenerationConfig merged = service.mergeGenerationConfig(base,
                new AgentRunRequest.ModelOverride("openai", "gpt-4o-mini", 0.9));
        assertEquals(0.9, merged.temperature());
        assertEquals("gpt-4o-mini", merged.model());
        // 未覆盖字段保留
        assertEquals(2048, merged.maxTokens());
    }
}