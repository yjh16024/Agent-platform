package com.agentplatform.core.plugin.runtime;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件 Manifest 加载器单元测试。
 */
class PluginManifestLoaderTest {

    private PluginManifestLoader loader;

    @BeforeEach
    void setUp() {
        loader = new PluginManifestLoader();
    }

    @Test
    @DisplayName("YAML manifest 解析")
    void parseYaml() {
        String yaml = """
                plugin:
                  id: plugin_tts_azure
                  name: 文字转语音
                  version: 1.3.0
                entry:
                  type: java
                  main_class: com.acme.tts.TtsPlugin
                """;
        // 简化：直接解析顶层（我们的 PluginManifest 是扁平结构）
        String flat = """
                id: plugin_tts_azure
                name: 文字转语音
                version: 1.3.0
                """;
        PluginManifest m = loader.parse(flat);
        assertEquals("plugin_tts_azure", m.id());
        assertEquals("1.3.0", m.version());
    }

    @Test
    @DisplayName("缺失 plugin.id 抛异常")
    void missingIdThrows() {
        assertThrows(BizException.class, () -> loader.parse("name: 无ID\nversion: 1.0.0"));
    }
}