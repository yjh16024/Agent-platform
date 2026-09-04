package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.plugin.builtin.AutoReplyPlugin;
import com.agentplatform.core.plugin.builtin.TtsPlugin;
import com.agentplatform.core.tool.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 管线（Hook 织入）单元测试：自动回复短路 + TTS after_llm。
 */
class AgentPipelineTest {

    private ExtensionRegistry registry;
    private AgentPipeline pipeline;
    private AutoReplyPlugin autoReply;
    private TtsPlugin tts;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry(new ToolRegistry());
        pipeline = new AgentPipeline(registry);
        autoReply = new AutoReplyPlugin();
        tts = new TtsPlugin();
    }

    @Test
    @DisplayName("自动回复：命中关键词短路（不调 LLM）")
    void autoReplyShortCircuits() {
        registry.registerHook(autoReply);

        AtomicBoolean llmCalled = new AtomicBoolean(false);
        PipelineResult result = pipeline.run("你们营业时间是什么？", msg -> {
            llmCalled.set(true);
            return "LLM 回复";
        });

        assertTrue(result.shortCircuited());
        assertFalse(llmCalled.get(), "命中自动回复时不应调 LLM");
        assertTrue(result.reply().contains("营业时间"));
    }

    @Test
    @DisplayName("未命中关键词时正常调 LLM")
    void noKeywordCallsLlm() {
        registry.registerHook(autoReply);

        AtomicBoolean llmCalled = new AtomicBoolean(false);
        PipelineResult result = pipeline.run("你好", msg -> {
            llmCalled.set(true);
            return "LLM 回复";
        });

        assertFalse(result.shortCircuited());
        assertTrue(llmCalled.get());
        assertEquals("LLM 回复", result.reply());
    }

    @Test
    @DisplayName("TTS 插件：after_llm 注入 audio_url")
    void ttsInjectsAudioUrl() {
        registry.registerHook(tts);

        PipelineResult result = pipeline.run("你好", msg -> "这是回复");
        assertFalse(result.shortCircuited());
        assertNotNull(result.extras().get("audio_url"));
        assertTrue(String.valueOf(result.extras().get("audio_url")).contains("tts"));
    }

    @Test
    @DisplayName("TTS 插件贡献 tts_synthesize 工具")
    void ttsProvidesTools() {
        assertEquals(2, tts.provideTools().size());
        assertEquals("tts_synthesize", tts.provideTools().get(0).name());
        assertEquals("tts_list_voices", tts.provideTools().get(1).name());
    }

    @Test
    @DisplayName("注册插件贡献的能力（工具同步进 ToolRegistry）")
    void extensionRegistryRegistersPluginTools() {
        ToolRegistry toolRegistry = new ToolRegistry();
        ExtensionRegistry reg = new ExtensionRegistry(toolRegistry);
        com.agentplatform.plugin.sdk.PluginContext ctx = new com.agentplatform.plugin.sdk.PluginContext(
                "agent_1", "t1", null, reg);

        reg.registerPlugin(tts, ctx);
        // tts_synthesize 应已注册进核心 ToolRegistry
        assertTrue(toolRegistry.contains("tts_synthesize"));
    }
}