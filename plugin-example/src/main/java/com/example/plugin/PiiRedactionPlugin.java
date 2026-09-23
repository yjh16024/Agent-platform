package com.example.plugin;

import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.HookPoint;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 类型四：<b>输出治理钩子</b>（{@code before_output}）—— 替换最终输出。
 *
 * <p>这是 2026-09-22 新补全的钩子点。它与 {@code after_llm} 的关键区别：
 * <b>{@code after_llm} 只能附加产物（如 audio_url），改不了回复正文；
 * 要改正文必须用 {@code before_output}</b>。此前这条能力是缺失的。</p>
 *
 * <h3>为什么放在 before_output 而不是 after_llm</h3>
 * <p>它在整条链路的最末端，拿到的就是<b>用户将看到的那一版</b>，所以：</p>
 * <ul>
 *   <li>脱敏结果会同时进入响应与会话记忆（{@code AgentRuntimeService} 存的是
 *       {@code pipeline.reply()}）—— 即"脱敏后的内容才落库"，符合合规预期；</li>
 *   <li>不会影响 {@code after_llm} 基于原文做的附加产物（例如语音合成仍念原文，
 *       若要"念脱敏版"应把本插件挂到更前置的位置或用 {@code after_llm} 处理）。</li>
 * </ul>
 *
 * <h3>返回值约定</h3>
 * <ul>
 *   <li>返回 {@code String} = <b>替换最终输出</b>（本插件即此用法）；</li>
 *   <li>也可返回 {@code Map{output: "..."}}（还接受 {@code reply}/{@code text}/{@code content} 作为键）；</li>
 *   <li>返回 {@code null} = 不改，保留 LLM 原输出。</li>
 * </ul>
 * <p>本插件在"没有命中任何敏感模式"时返回 {@code null} 而不是原文 —— 少一次无谓的替换，
 * 日志里也更容易看出哪些轮次真的被改写过。</p>
 *
 * <p>同样只在<b>非流式</b>链路触发（见 {@link KeywordReplyPlugin} 的说明）。</p>
 */
public class PiiRedactionPlugin implements AgentHook {

    /** 必须与 manifests/pii-redaction.yaml 里的 id 一致。 */
    public static final String PLUGIN_ID = "example_pii_redaction";

    /**
     * 敏感模式 → 掩码构造。
     *
     * <p>顺序有讲究：<b>身份证与银行卡都可能是长数字串，先匹配更具体的那类</b>，
     * 否则 18 位身份证会被"16~19 位数字"的银行卡规则先吃掉、掩码位置全错。</p>
     *
     * <p>每条都带边界断言（{@code (?<!\d)} / {@code (?!\d)}），避免把更长数字串
     * 的一部分误判成手机号 —— 例如订单号 {@code 20250921138000123456} 里含有
     * 形如手机号的片段。</p>
     */
    private static final Rule[] RULES = {
            // 身份证（18 位，最后一位可为 X）
            new Rule(Pattern.compile("(?<![0-9A-Za-z])([1-9]\\d{5})(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(\\d{3})([\\dXx])(?![0-9A-Za-z])"),
                    m -> m.group(1) + "**********" + m.group(6)),
            // 银行卡（16~19 位连续数字）
            new Rule(Pattern.compile("(?<!\\d)(\\d{4})\\d{8,13}(\\d{4})(?!\\d)"),
                    m -> m.group(1) + "********" + m.group(2)),
            // 手机号（中国大陆）
            new Rule(Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)"),
                    m -> m.group(1) + "****" + m.group(3)),
            // 邮箱：保留首字符与域名，中间打码
            new Rule(Pattern.compile("([\\w.+-])([\\w.+-]*)@([\\w-]+\\.[\\w.-]+)"),
                    m -> m.group(1) + "***@" + m.group(3)),
    };

    /** 是否启用。可由 config 关掉（演示"开关型配置"）。 */
    private volatile boolean enabled = true;

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
        var node = ctx.config();
        var flag = node == null ? null : node.get("enabled");
        this.enabled = flag == null || flag.asBoolean(true);
    }

    @Override
    public void onDetach(PluginContext ctx) {
        this.enabled = true;
    }

    @Override
    public HookPoint point() {
        return HookPoint.before_output;
    }

    @Override
    public Object invoke(HookContext ctx) {
        if (!enabled) {
            return null;
        }
        Object input = ctx.input();
        if (input == null) {
            return null;
        }
        String original = input.toString();
        String masked = redact(original);
        // 无变化就不返回，语义更清晰（宿主只在返回值非空时才替换）
        return masked.equals(original) ? null : masked;
    }

    /** 按规则表逐条脱敏。包内可见以便单测直接验。 */
    static String redact(String text) {
        String out = text;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(rule.mask().apply(m)));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    /** 一条脱敏规则。 */
    private record Rule(Pattern pattern, java.util.function.Function<Matcher, String> mask) {
    }
}
