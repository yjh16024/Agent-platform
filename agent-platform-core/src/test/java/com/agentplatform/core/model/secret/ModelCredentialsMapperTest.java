package com.agentplatform.core.model.secret;

import com.agentplatform.framework.api.ModelCredentials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证接缝：平台解析结果 → 框架凭证。
 */
class ModelCredentialsMapperTest {

    @Test
    void 字段应原样映射() {
        ModelBindingService.ResolvedModel resolved = new ModelBindingService.ResolvedModel(
                "deepseek", "deepseek-chat", "https://api.deepseek.com", "sk-abc",
                ModelBindingService.Routing.DIRECT);

        ModelCredentials credentials = ModelCredentialsMapper.of(resolved);

        assertEquals("deepseek", credentials.provider());
        assertEquals("https://api.deepseek.com", credentials.baseUrl());
        assertEquals("sk-abc", credentials.apiKey());
        assertTrue(credentials.hasEndpoint());
        assertTrue(credentials.hasApiKey());
    }

    @Test
    void 空解析结果返回空凭证而不是抛错() {
        ModelCredentials credentials = ModelCredentialsMapper.of(null);

        assertFalse(credentials.hasEndpoint());
        assertFalse(credentials.hasApiKey());
    }
}
