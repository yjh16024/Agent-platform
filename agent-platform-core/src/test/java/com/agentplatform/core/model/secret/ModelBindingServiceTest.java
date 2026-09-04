package com.agentplatform.core.model.secret;

import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.ModelBinding;
import com.agentplatform.model.record.ModelBindingView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型绑定服务测试（回退链 / 密封 / 掩码视图）。
 */
class ModelBindingServiceTest {

    private ModelBindingService service;

    @BeforeEach
    void setUp() {
        service = new ModelBindingService(new ModelKeyCrypto("test-key"),
                "http://localhost:4000", "sk-global",
                "deepseek", "deepseek-chat");
    }

    private AgentDefinition def(ModelBinding binding, GenerationConfig gc) {
        return AgentDefinition.builder()
                .agentId("agent_t1")
                .tenantId("t1")
                .name("助手")
                .modelBinding(binding)
                .generationConfig(gc == null ? GenerationConfig.defaults() : gc)
                .build();
    }

    @Test
    @DisplayName("seal 把明文 apiKey 加密，view 只回掩码不回明文")
    void sealEncryptsAndViewMasks() {
        ModelBinding sealed = service.seal(new ModelBinding("deepseek", "deepseek-chat", "https://api.deepseek.com/v1", "sk-plain-api-key"));
        assertNotEquals("sk-plain-api-key", sealed.apiKey(), "seal 后应存密文");

        ModelBindingView view = service.view(sealed);
        assertEquals("deepseek", view.provider());
        assertTrue(view.hasApiKey());
        assertEquals("sk-***-key", view.apiKeyMasked());
        assertNotEquals("sk-plain-api-key", view.apiKeyMasked());
    }

    @Test
    @DisplayName("view 对空绑定返回 null")
    void viewNullBinding() {
        assertNull(service.view(null));
    }

    @Test
    @DisplayName("resolve：绑定显式配置优先于 generationConfig 与全局默认")
    void resolvePrefersBinding() {
        GenerationConfig gc = new GenerationConfig("gc-model", "gc-provider", null, null, null, null, null, null, null, null, null, null);
        ModelBinding persisted = service.seal(new ModelBinding("openai", "gpt-4o-mini", "https://api.openai.com/v1", "sk-openai-key"));

        ModelBindingService.ResolvedModel r = service.resolve(def(persisted, gc));
        assertEquals("openai", r.provider());
        assertEquals("gpt-4o-mini", r.model());
        assertEquals("https://api.openai.com/v1", r.baseUrl());
        assertEquals("sk-openai-key", r.apiKey(), "resolve 应解密为明文以发往厂商");
    }

    @Test
    @DisplayName("resolve：无绑定时回退 generationConfig，再回退全局默认/baseUrl/apiKey")
    void resolveFallsBackToDefaults() {
        ModelBindingService.ResolvedModel r = service.resolve(def(null, null));
        assertEquals("deepseek", r.provider());
        assertEquals("deepseek-chat", r.model());
        assertEquals("http://localhost:4000", r.baseUrl());
        assertEquals("sk-global", r.apiKey());
    }

    @Test
    @DisplayName("resolve：generationConfig 的 model/provider 作为中间回退层")
    void resolveFallsBackToGenerationConfig() {
        GenerationConfig gc = new GenerationConfig("gc-model", "gc-provider", null, null, null, null, null, null, null, null, null, null);
        ModelBindingService.ResolvedModel r = service.resolve(def(null, gc));
        assertEquals("gc-provider", r.provider());
        assertEquals("gc-model", r.model());
        assertEquals("http://localhost:4000", r.baseUrl(), "baseUrl 无绑定应回退全局");
    }
}