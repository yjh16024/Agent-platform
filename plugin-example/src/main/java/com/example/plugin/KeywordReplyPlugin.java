package com.example.plugin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.HookPoint;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 类型一：<b>前置钩子</b>（{@code before_llm}）—— 命中即短路，不调用 LLM。
 *
 * <p>适合「关键词直答 / 请求改写 / 敏感词拦截」。宿主自己写内置插件时也是这个形态
 * （实现接口 + 标 {@code @Component}），区别只在于内置插件不需要 jar 与 manifest。</p>
 *
 * <p><b>能力按智能体隔离</b>：本插件只会对「挂载了它的那个智能体」生效，
 * 挂在 A 上不会影响 B。</p>
 *
 * <h3>外部 jar 插件与内置插件的三点区别</h3>
 * <ol>
 *   <li><b>不能加 {@code @Component}</b>:它不在 Spring 扫描范围里，由 {@code ExternalPluginLoader}
 *       反射实例化，Bean 注解不会生效。</li>
 *   <li>必须保留<b>公开无参构造器</b>（加载器用 {@code getDeclaredConstructor().newInstance()}）。</li>
 *   <li>不在宿主源码树里：改完要重打 jar 并<b>重启后端</b>才能覆盖 —— 宿主
 *       {@code ExternalPluginLoader.unload()} 只从缓存里移除实例、不 close ClassLoader，
 *       Windows 下 jar 句柄仍被占用。</li>
 * </ol>
 *
 * <h3>触发约定（务必记住）</h3>
 * <ul>
 *   <li>{@code before_llm} 返回 {@code String} = <b>短路</b>（这一轮不再调 LLM，直接用它当回复）；
 *       返回 {@code null} = 透传继续。</li>
 *   <li><b>只有非流式链路会触发</b>：宿主的 {@code AgentPipeline} 仅在
 *       {@code AgentRuntimeService.run()} 里被调用，{@code runStream()} 完全不经过它。
 *       也就是说聊天页的「流式」开关一旦打开，本插件的钩子会静默失效。</li>
 *   <li>{@link HookContext#agentId()} / {@link HookContext#runId()} 目前宿主恒传 {@code null}，
 *       metadata 恒为空 map —— 不要依赖它们。</li>
 *   <li>{@link #id()} 必须与 manifest 的 {@code id} 完全一致，否则 detach 时钩子反注册不掉
 *       （宿主按 {@code hook.id()} 匹配 pluginId）。</li>
 * </ul>
 *
 * <h3>配置</h3>
 * 关键词表从 attach 时传入的 config 读取，形如：
 * <pre>
 * {"rules": {"营业时间": "我们的营业时间是周一至周五 9:00-18:00", "退款": "7 天内无理由退货"}}
 * </pre>
 * 不传 config 时退化为 {@link #DEFAULT_RULES}，用来演示「可空配置的降级」写法。
 */
public class KeywordReplyPlugin implements AgentHook {

    /** 必须与 manifests/keyword-reply.yaml 里的 id 一致。 */
    public static final String PLUGIN_ID = "example_keyword_reply";

    private static final Map<String, String> DEFAULT_RULES = Map.of(
            "营业时间", "【示例插件】营业时间：周一至周五 9:00-18:00，节假日休息。",
            "退款政策", "【示例插件】7 天内无理由退货，超过 7 天需提供质量问题证明。");

    /** 关键词 → 预设答复。onAttach 时装载、onDetach 时清空（演示生命周期）。 */
    private volatile Map<String, String> rules = Map.of();

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
        Map<String, String> parsed = new LinkedHashMap<>();
        JsonNode node = ctx.config();
        JsonNode configured = node == null ? null : node.get("rules");
        if (configured != null && configured.isObject()) {
            // Jackson 3 的遍历入口是 properties()（Jackson 2 里叫 fields()）
            configured.properties().forEach(e -> parsed.put(e.getKey(), e.getValue().asText("")));
        }
        this.rules = parsed.isEmpty() ? DEFAULT_RULES : Map.copyOf(parsed);
    }

    @Override
    public void onDetach(PluginContext ctx) {
        this.rules = Map.of();
    }

    @Override
    public HookPoint point() {
        return HookPoint.before_llm;
    }

    @Override
    public Object invoke(HookContext ctx) {
        Object input = ctx.input();
        if (input == null) {
            return null;
        }
        String message = input.toString();
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            if (message.contains(rule.getKey())) {
                return rule.getValue();   // 返回 String → 短路
            }
        }
        return null;                      // 未命中 → 透传给 LLM
    }
}
