package com.agentplatform.core.model.adapter;

import com.agentplatform.core.model.ModelCapability;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规则引擎模型适配器（策略模式的「规则实现」）。
 * <p>
 * 与 {@link OpenAiCompatibleAdapter}（真实大模型）、{@link MockModelAdapter}（本地兜底）
 * 并列为 {@link ModelAdapter} 的一种实现：按预置规则（正则匹配）给出**确定性**应答，
 * 零网络、零成本、零 token 消耗。适用于：
 * </p>
 * <ul>
 *   <li>离线 / 无 Key 环境的确定性应答；</li>
 *   <li>高频固定话术（问候、帮助） bypass 大模型，省成本；</li>
 *   <li>敏感/固定流程的规则拦截（作为大模型前置层）。</li>
 * </ul>
 * <p><b>启用方式</b>：provider 显式指定 {@code rule} / {@code rule-engine}
 * （不会进入 {@code auto} 路由，避免抢占真实模型）。</p>
 */
@Slf4j
public class RuleEngineModelAdapter implements ModelAdapter {

    /** 规则：名称 + 匹配正则 + 应答模板。 */
    public record Rule(String name, String pattern, String reply) {
    }

    private static final String FALLBACK =
            "[rule-engine] 未匹配到任何规则。当前 provider=rule 仅按预置规则应答；"
                    + "需要自然语言能力请将 provider 切换为 openai / deepseek 等真实模型。";

    /** 默认规则集（命中第一条即返回）。 */
    public static final List<Rule> DEFAULT_RULES = List.of(
            new Rule("greeting", "(?i)^\\s*(你好|您好|hi|hello|hey|嗨)\\b?.*",
                    "您好！我是 Agent Platform 的规则引擎应答器（provider=rule），目前按预置规则回复。"),
            new Rule("identity", "(?i).*(你是谁|你叫什么|what is your name|who are you).*",
                    "我是规则引擎适配器（RuleEngineModelAdapter），零成本、确定性应答，不使用大模型。"),
            new Rule("help", "(?i).*(帮助|help|怎么用|能做什么).*",
                    "可用能力：① 问候/身份等固定话术；② 按正则规则匹配的确定性应答；"
                            + "③ 作为大模型前置拦截层。需要开放式对话请切换 provider 为真实模型。"),
            new Rule("capability", "(?i).*(支持哪些模型|支持什么模型|which models).*",
                    "当前平台支持：openai / anthropic / qwen / ernie / hunyuan / deepseek（真实模型）、"
                            + "local（Mock 兜底）、rule（规则引擎，即当前适配器）。")
    );

    private final List<Rule> rules;

    public RuleEngineModelAdapter() {
        this(DEFAULT_RULES);
    }

    public RuleEngineModelAdapter(List<Rule> rules) {
        this.rules = (rules == null || rules.isEmpty()) ? DEFAULT_RULES : rules;
    }

    @Override
    public String provider() {
        return "rule";
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return Set.of(ModelCapability.TEXT);
    }

    /** 规则引擎零成本，成本权重最低。 */
    @Override
    public double costWeight() {
        return 0.0;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long t0 = System.currentTimeMillis();
        String input = request.userMessage() == null ? "" : request.userMessage();
        String reply = match(input);
        log.info("[model:rule] 来源=规则引擎，命中规则对输入 len={} 返回 len={}", input.length(), reply.length());
        return new ChatResponse(reply, approxTokens(input), approxTokens(reply), 0.0,
                System.currentTimeMillis() - t0, List.of());
    }

    @Override
    public Flux<ChatDelta> stream(ChatRequest request) {
        // 规则引擎应答是一次性确定文本：直接以单个增量返回
        String reply = chat(request).content();
        return Flux.just(new ChatDelta(reply, false, null), new ChatDelta("", true, null));
    }

    /** 按规则顺序匹配（大小写不敏感），未命中返回兜底文案。 */
    private String match(String input) {
        for (Rule r : rules) {
            try {
                if (Pattern.matches(r.pattern(), input)) {
                    return r.reply();
                }
            } catch (Exception e) {
                log.warn("[model:rule] 规则 {} 正则非法，已跳过: {}", r.name(), e.getMessage());
            }
        }
        return FALLBACK;
    }

    /** 粗略 token 估算（规则引擎不产生真实 token，仅用于计量展示）。 */
    private static int approxTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }
}
