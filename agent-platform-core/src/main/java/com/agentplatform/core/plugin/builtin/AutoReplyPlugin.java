package com.agentplatform.core.plugin.builtin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.HookPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 自动回复插件（示例）。
 * <p>
 * 注入 {@code before_llm} 钩子：当用户消息命中预设关键词（如「营业时间」「工作时间」），
 * 直接返回预设答复 —— <b>不调 LLM 即短路</b>，演示「插入即用」的自动回复能力。
 * </p>
 */
@Slf4j
@Component
public class AutoReplyPlugin implements AgentHook {

    public static final String PLUGIN_ID = "plugin_auto_reply";

    /** 关键词 → 预设答复 规则表。 */
    private static final Map<String, String> RULES = Map.of(
            "营业时间", "您好，我们的营业时间是周一至周五 9:00-18:00，节假日休息。",
            "工作时间", "工作时间为每天 9:00-18:00，欢迎随时咨询。",
            "退款政策", "退款政策：7 天内无理由退货，超过 7 天需提供质量问题证明。"
    );

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
        log.info("AutoReplyPlugin attached to agent {}", ctx.agentId());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        log.info("AutoReplyPlugin detached");
    }

    @Override
    public HookPoint point() {
        return HookPoint.before_llm;
    }

    @Override
    public Object invoke(HookContext ctx) {
        String message = ctx.input() == null ? "" : ctx.input().toString();
        for (Map.Entry<String, String> rule : RULES.entrySet()) {
            if (message.contains(rule.getKey())) {
                log.info("AutoReply hit keyword '{}'", rule.getKey());
                return rule.getValue(); // 返回 String → 短路
            }
        }
        return null; // 未命中 → 透传 LLM
    }
}