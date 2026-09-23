package com.example.plugin;

import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 类型三：<b>工具</b>（{@link ToolProvider}）—— 贡献给 LLM 通过 function calling 调用。
 *
 * <h3>两个关键差异（与钩子相比）</h3>
 * <ol>
 *   <li>工具是<b>按需调用</b>的，由 LLM 自己决定；每次回复都会跑的是钩子。</li>
 *   <li>触发条件更苛刻：聊天页要打开「工具」开关（请求体 {@code tools.enabled=true}）。
 *       <b>（2026-09-22 更新）</b>流式链路也已支持工具调用 —— {@code runStream()} 会先以非流式
 *       跑完工具循环、再把最终答复分块推流，所以不再要求"必须关闭流式"。</li>
 * </ol>
 *
 * <h3>写工具时务必注意</h3>
 * <ul>
 *   <li><b>用完整的四参构造器</b> {@code new PluginTool(name, description, inputSchema, handler)}，
 *       不要图省事用 {@code PluginTool.of(...)} —— 它会把 {@code inputSchema} 传成 {@code null}，
 *       LLM 拿不到参数结构，基本调不准。</li>
 *   <li>参数从 {@code args.path("x").asText("默认值")} 取；{@code args} 可能为 null，先判空。</li>
 *   <li>校验失败直接<b>抛异常</b>：宿主 {@code PluginToolAdapter} 会把它转成
 *       {@code ToolResult.fail(message)} 回给模型，模型能看到失败原因并重试。</li>
 *   <li>返回 {@code JsonNode}（{@code ObjectNode} / {@code ArrayNode} 都行），别返回裸 String。</li>
 *   <li>handler 收到的 {@link PluginContext} 是 <b>attach 时那一份</b>，{@code agentId} 在多次运行之间是固定的 ——
 *       不要把它当"当前这次运行"的上下文用。</li>
 * </ul>
 */
public class TextStatsToolPlugin implements ToolProvider {

    /** 必须与 manifests/text-stats.yaml 里的 id 一致。 */
    public static final String PLUGIN_ID = "example_text_stats";

    private final ObjectMapper mapper = new ObjectMapper();

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
        // 无状态工具，不需要初始化
    }

    @Override
    public void onDetach(PluginContext ctx) {
        // 无状态工具，不需要清理
    }

    @Override
    public List<PluginTool> provideTools() {
        return List.of(new PluginTool(
                "text_stats",
                "统计一段文本的字符数、行数与词数。适合需要精确字数的场景。",
                inputSchema(),
                this::stats));
    }

    /** 工具实现：入参为 JSON，返回 JSON；失败就抛异常。 */
    private JsonNode stats(JsonNode args, PluginContext ctx) {
        if (args == null) {
            throw new IllegalArgumentException("缺少参数对象");
        }
        JsonNode textNode = args.get("text");
        String text = textNode == null || textNode.isNull() ? "" : textNode.asText();
        if (text.isBlank()) {
            throw new IllegalArgumentException("参数 text 不能为空");
        }
        String trimmed = text.trim();
        ObjectNode out = mapper.createObjectNode();
        out.put("chars", text.length());
        out.put("lines", text.lines().count());
        out.put("words", trimmed.split("\\s+").length);
        return out;
    }

    /** 入参 JSON Schema —— 写给 LLM 看的，字段说明越清楚调用越准。 */
    private ObjectNode inputSchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");
        ObjectNode text = properties.putObject("text");
        text.put("type", "string");
        text.put("description", "要统计的文本内容，不能为空");

        root.putArray("required").add("text");
        return root;
    }
}
