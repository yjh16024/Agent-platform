package com.example.plugin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.HookPoint;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 类型四：<b>混合插件</b> —— 同时实现 {@link AgentHook} 与 {@link ToolProvider}。
 *
 * <p>典型组合是「自动做某事」（钩子，每轮必跑）+「按需做某事」（工具，模型决定调不调）。
 * 一个 jar 一次挂载即可贡献两类能力。</p>
 *
 * <h3>为什么可以同时实现两个接口</h3>
 * 宿主 {@code ExtensionRegistry.registerPlugin()} 会按 {@code instanceof} 逐个识别贡献：
 * <pre>
 * if (plugin instanceof AgentHook hook)        registerHook(hook);
 * if (plugin instanceof ToolProvider provider) registerTools(plugin.id(), provider.provideTools(), ctx);
 * </pre>
 * 因此<b>不要</b>再在 {@code onAttach} 里手动调 {@code ctx.registrar().registerHook(...)} ——
 * 那会让同一个钩子被注册两遍、每轮执行两次。
 *
 * <h3>本示例额外演示：生命周期里维护状态</h3>
 * {@link #callCount} 在 attach 时归零、detach 时清空。这既是"插件可以是有状态的"的示范，
 * 也提醒一件事：插件实例被宿主按 pluginId 缓存，<b>detach 后不会自动销毁</b>，
 * 自己持有的资源要在这里释放。
 */
public class HookAndToolPlugin implements AgentHook, ToolProvider {

    /** 必须与 manifests/hook-and-tool.yaml 里的 id 一致。 */
    public static final String PLUGIN_ID = "example_hook_and_tool";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger callCount = new AtomicInteger();

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
        callCount.set(0);
    }

    @Override
    public void onDetach(PluginContext ctx) {
        callCount.set(0);
    }

    // ---------------- 贡献一：after_llm 钩子 ----------------

    @Override
    public HookPoint point() {
        return HookPoint.after_llm;
    }

    @Override
    public Object invoke(HookContext ctx) {
        Object input = ctx.input();
        if (input == null) {
            return null;
        }
        int n = callCount.incrementAndGet();
        return Map.of(
                "example_plugin", PLUGIN_ID,
                "example_round", n);
    }

    // ---------------- 贡献二：pipeline_round 工具 ----------------

    @Override
    public List<PluginTool> provideTools() {
        return List.of(new PluginTool(
                "example_round_no",
                "返回本插件自 attach 以来被触发过的钩子轮次，用于验证插件确实在生效。无参数。",
                emptySchema(),
                (args, ctx) -> {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("plugin", PLUGIN_ID);
                    out.put("round", callCount.get());
                    return out;
                }));
    }

    /** 无参工具的 Schema：仍要给出 {@code type=object}，别传 null。 */
    private ObjectNode emptySchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.putObject("properties");
        return root;
    }
}
