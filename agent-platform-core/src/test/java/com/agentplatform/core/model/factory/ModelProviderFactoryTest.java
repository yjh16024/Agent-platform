package com.agentplatform.core.model.factory;

import com.agentplatform.core.model.adapter.MockModelAdapter;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.adapter.OpenAiCompatibleAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型工厂单元测试（工厂模式）。
 */
class ModelProviderFactoryTest {

    private ModelProviderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ModelProviderFactory("http://localhost:4000", "sk-local");
    }

    @Test
    @DisplayName("local provider 返回 Mock 适配器")
    void localReturnsMock() {
        ModelAdapter adapter = factory.get("local");
        assertInstanceOf(MockModelAdapter.class, adapter);
    }

    @Test
    @DisplayName("主流云厂商归一化为 OpenAI 兼容适配器")
    void cloudNormalizesToOpenAi() {
        for (String p : new String[]{"openai", "qwen", "ernie", "hunyuan", "auto"}) {
            ModelAdapter adapter = factory.get(p);
            assertInstanceOf(OpenAiCompatibleAdapter.class, adapter, "provider " + p);
        }
    }

    @Test
    @DisplayName("anthropic 走 Anthropic Messages API 适配器（协议不同）")
    void anthropicUsesMessagesAdapter() {
        ModelAdapter adapter = factory.get("anthropic");
        assertInstanceOf(com.agentplatform.core.model.adapter.AnthropicAdapter.class, adapter);
    }

    @Test
    @DisplayName("适配器按 provider 缓存（单例）")
    void adapterCached() {
        assertSame(factory.get("openai"), factory.get("openai"));
    }

    @Test
    @DisplayName("supports 判断 provider 是否受支持")
    void supports() {
        assertTrue(factory.supports("openai"));
        assertTrue(factory.supports("Qwen"));
        assertFalse(factory.supports("unknown_provider"));
    }
}