package com.agentplatform.core.multimodal;

import com.agentplatform.plugin.sdk.Plugin;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语音识别（ASR）插件（示例）。
 * <p>将语音/音频转写为文本，作为与 {@code TtsPlugin} 对称的多模态插件：
 * 贡献 {@code asr_transcribe} 工具（可被 LLM function calling 触发）。</p>
 */
@Slf4j
@Component
public class AsrPlugin implements ToolProvider {

    public static final String PLUGIN_ID = "plugin_asr";

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onAttach(PluginContext ctx) {
        log.info("AsrPlugin attached to agent {}", ctx.agentId());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        log.info("AsrPlugin detached");
    }

    @Override
    public List<PluginTool> provideTools() {
        return List.of(
                PluginTool.of("asr_transcribe", "将音频转写为文本（语音识别）",
                        (args, ctx) -> {
                            String fileId = args.path("file_id").asText("");
                            ObjectNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                            // 模拟转写结果
                            out.put("transcript", "这是音频「" + fileId + "」的模拟转写结果。");
                            out.put("language", "zh-CN");
                            return out;
                        })
        );
    }
}