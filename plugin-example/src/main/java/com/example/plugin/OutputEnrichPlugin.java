package com.example.plugin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.HookPoint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 类型二：<b>后置钩子</b>（{@code after_llm}）—— 给本轮回复附加产物，不改变回复文本。
 *
 * <p>典型用途是把「回复之外」的产物一起带给前端，如合成音频的 {@code audio_url}、
 * 结构化标签、统计信息等。</p>
 *
 * <h3>触发约定</h3>
 * <ul>
 *   <li>返回 {@code Map} = 合并进本轮响应的<b>附加产物</b>（extras）；
 *       返回 {@code null} = 不附加任何东西。</li>
 *   <li>{@link HookContext#input()} 是 LLM 的回复文本；<b>返回值里的东西不会自动出现在回复正文中</b>，
 *       它走的是响应里的 extras 通道。</li>
 *   <li>同样只在<b>非流式</b>链路触发（见 {@link KeywordReplyPlugin} 里的说明）。</li>
 *   <li>这里抛异常不会打断主流程：宿主 {@code AgentPipeline.safelyInvoke} 会捕获并降级跳过。</li>
 * </ul>
 *
 * <h3>与「工具」的区别</h3>
 * 后置钩子是<b>每轮必跑</b>的（自动执行），工具是<b>由 LLM 决定是否调用</b>的。要"每次回复都做"就用钩子，
 * 要"按需调用"就用工具。
 */
public class OutputEnrichPlugin implements AgentHook {

    /** 必须与 manifests/output-enrich.yaml 里的 id 一致。 */
    public static final String PLUGIN_ID = "example_output_enrich";

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
        // 本插件无状态，不需要初始化
    }

    @Override
    public void onDetach(PluginContext ctx) {
        // 本插件无状态，不需要清理
    }

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
        String reply = input.toString();

        // 返回 Map → 合并进本轮响应的附加产物。
        // 附加值只能是可 JSON 序列化的简单类型，别塞对象。
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("example_reply_chars", reply.length());
        extras.put("example_reply_lines", reply.lines().count());
        extras.put("example_plugin", PLUGIN_ID);
        return extras;
    }
}
