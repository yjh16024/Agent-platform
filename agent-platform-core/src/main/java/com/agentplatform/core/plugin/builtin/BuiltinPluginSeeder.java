package com.agentplatform.core.plugin.builtin;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.repository.PluginRepository;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内置插件种子数据（启动时注册到 plugin_def，供市场展示与 Attach）。
 * <p>官方市场插件 tenant_id='__platform__'。首次启动插入，已存在则跳过（幂等）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinPluginSeeder {

    private static final String PLATFORM_TENANT = "__platform__";

    private final PluginRepository pluginRepository;

    @PostConstruct
    public void seed() {
        seedOne(
                new PluginManifest(
                        AutoReplyPlugin.PLUGIN_ID, "自动回复", "1.0.0",
                        "命中关键词时直接返回预设答复（不调 LLM）", PLATFORM_TENANT,
                        new PluginManifest.Entry("java", null),
                        new PluginManifest.Contributes(List.of(), List.of(
                                new PluginManifest.Contributes.HookDef("before_llm", null)), List.of()),
                        null, null, null));
        seedOne(
                new PluginManifest(
                        TtsPlugin.PLUGIN_ID, "文字转语音 (TTS)", "1.3.0",
                        "将大模型输出合成为语音（含 tts_synthesize 工具）", PLATFORM_TENANT,
                        new PluginManifest.Entry("java", null),
                        new PluginManifest.Contributes(List.of(
                                new PluginManifest.Contributes.ToolDef("tts_synthesize", "文本合成语音", null),
                                new PluginManifest.Contributes.ToolDef("tts_list_voices", "列出音色", null)),
                                List.of(new PluginManifest.Contributes.HookDef("after_llm", null)), List.of()),
                        null, null, null));
        seedOne(
                new PluginManifest(
                        com.agentplatform.core.multimodal.AsrPlugin.PLUGIN_ID, "语音识别 (ASR)", "1.0.0",
                        "将音频转写为文本（含 asr_transcribe 工具）", PLATFORM_TENANT,
                        new PluginManifest.Entry("java", null),
                        new PluginManifest.Contributes(List.of(
                                new PluginManifest.Contributes.ToolDef("asr_transcribe", "音频转写", null)), List.of(), List.of()),
                        null, null, null));
    }

    private void seedOne(PluginManifest manifest) {
        if (pluginRepository.findByTenantIdAndPluginId(PLATFORM_TENANT, manifest.id()).isPresent()) {
            return;
        }
        PluginDef def = PluginDef.builder()
                .pluginId(manifest.id())
                .tenantId(PLATFORM_TENANT)
                .name(manifest.name())
                .description(manifest.description())
                .author(PLATFORM_TENANT)
                .latestVersion(manifest.version())
                .manifest(JsonUtils.mapper().convertValue(manifest, Map.class))
                .status("published")
                .visibility("marketplace")
                .build();
        pluginRepository.save(def);
        log.info("Seeded builtin plugin: {}", manifest.id());
    }
}