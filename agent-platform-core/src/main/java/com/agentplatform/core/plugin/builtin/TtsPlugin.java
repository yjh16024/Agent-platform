package com.agentplatform.core.plugin.builtin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.HookPoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文字转语音（TTS）插件（示例）。
 * <p>
 * 同时贡献 <b>after_llm 钩子</b>（自动合成语音返回 audio_url）与 <b>tts_synthesize 工具</b>
 * （可被 LLM function calling 触发）。演示一个插件打包「Hook + Tool」两类能力。
 * </p>
 */
@Slf4j
@Component
public class TtsPlugin implements AgentHook, ToolProvider {

    public static final String PLUGIN_ID = "plugin_tts_azure";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.3.0";
    }

    @Override
    public void onAttach(PluginContext ctx) {
        log.info("TtsPlugin attached to agent {}", ctx.agentId());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        log.info("TtsPlugin detached");
    }

    // ---- AgentHook ----

    @Override
    public HookPoint point() {
        return HookPoint.after_llm;
    }

    @Override
    public Object invoke(HookContext ctx) {
        String text = ctx.input() == null ? "" : ctx.input().toString();
        // 模拟合成，返回 audio_url 作为附加产物
        String audioUrl = mockSynthesize(text);
        return Map.of("audio_url", audioUrl);
    }

    // ---- ToolProvider ----

    @Override
    public List<PluginTool> provideTools() {
        return List.of(
                PluginTool.of("tts_synthesize", "将文本合成为语音并返回 audio_url",
                        (args, ctx) -> {
                            String text = args.path("text").asText("你好");
                            String voice = args.path("voice").asText("zh-CN-Xiaoxiao");
                            ObjectNode out = mapper.createObjectNode();
                            out.put("audio_url", mockSynthesize(text));
                            out.put("voice", voice);
                            return out;
                        }),
                PluginTool.of("tts_list_voices", "列出可用音色",
                        (args, ctx) -> mapper.valueToTree(List.of("zh-CN-Xiaoxiao", "zh-CN-Yunxi", "en-US-Aria")))
        );
    }

    private String mockSynthesize(String text) {
        return "https://tts.example.com/audio/" + Integer.toHexString(text.hashCode()) + ".mp3";
    }
}