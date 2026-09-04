package com.agentplatform.core.log;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错误指纹生成器单元测试。
 */
class FingerprintGeneratorTest {

    private FingerprintGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new FingerprintGenerator();
    }

    @Test
    @DisplayName("插件类加载错误 → plugin#classloader_error")
    void pluginClassloaderError() {
        assertEquals("plugin#classloader_error",
                generator.generate(LogCategory.plugin, "ClassNotFoundException: com.azure.ai.TtsClient", null));
    }

    @Test
    @DisplayName("插件超时 → plugin#timeout")
    void pluginTimeout() {
        assertEquals("plugin#timeout",
                generator.generate(LogCategory.plugin, "execution timed out after 30s", null));
    }

    @Test
    @DisplayName("模型连接错误 → model#connection_refused")
    void modelConnectionError() {
        assertEquals("model#connection_refused",
                generator.generate(LogCategory.llm, "Connection refused to model gateway", null));
    }

    @Test
    @DisplayName("Skill 导入失败 → skill_import#manifest_invalid")
    void skillImportError() {
        assertEquals("skill_import#manifest_invalid",
                generator.generate(LogCategory.skill, "manifest yaml parse error", null));
    }

    @Test
    @DisplayName("API 错误 → api#状态码")
    void apiError() {
        assertEquals("api#401", generator.generate(LogCategory.api, "HTTP 401 error", null));
        assertEquals("api#429", generator.generate(LogCategory.api, "status=429 rate limit", null));
    }
}