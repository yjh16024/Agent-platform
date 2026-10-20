package com.agentplatform.core.model.adapter;

import java.util.Locale;
import java.util.Map;

/**
 * 「思考模式」的请求体参数映射（2026-10-19）。
 *
 * <h3>为什么需要一张表</h3>
 * 各厂商**开启思考的字段名完全不同**，而且发错字段会被上游直接 400：
 * 之前只对 {@code deepseek} 发 {@code thinking}，代价是"换一个模型就再也看不到思考过程"。
 * 但反过来无条件给所有厂商发同样字段更糟 —— 那会让换模型直接报错。
 *
 * <table>
 *   <tr><th>厂商/模型</th><th>参数</th></tr>
 *   <tr><td>DeepSeek / 智谱 GLM / Moonshot Kimi / MiniMax</td>
 *       <td>{@code {"thinking":{"type":"enabled"}}}</td></tr>
 *   <tr><td>通义千问 Qwen（DashScope）</td><td>{@code {"enable_thinking": true}}</td></tr>
 *   <tr><td>OpenAI o 系列 / GPT-5</td><td>{@code {"reasoning_effort":"medium"}}</td></tr>
 * </table>
 *
 * <p><b>未知 provider 一律不发</b>：宁可没有思考过程，也不能让对话挂掉。
 * 即便命中白名单，上游仍可能因模型太老而 400 —— 适配器侧还有一层"去掉参数重试"的兜底
 * （见 {@code OpenAiCompatibleAdapter.postWithThinkingFallback}）。</p>
 */
public final class ThinkingParams {

    private ThinkingParams() {
    }

    /**
     * 返回要合并进请求体的思考参数（空 Map = 这个 provider 不发）。
     *
     * @param provider 平台里的 provider 标识（如 {@code deepseek} / {@code qwen} / {@code openai}）
     */
    public static Map<String, Object> forProvider(String provider) {
        String p = provider == null ? "" : provider.toLowerCase(Locale.ROOT).trim();
        if (p.isEmpty()) {
            return Map.of();
        }
        // 国内主流：OpenAI 兼容协议里的事实标准字段
        if (containsAny(p, "deepseek", "zhipu", "glm", "bigmodel", "moonshot", "kimi", "minimax", "baichuan")) {
            return Map.of("thinking", Map.of("type", "enabled"));
        }
        // 通义千问：DashScope 的专有开关名
        if (containsAny(p, "qwen", "dashscope", "aliyun", "tongyi")) {
            return Map.of("enable_thinking", true);
        }
        // OpenAI：只有 o 系列 / GPT-5 认 reasoning_effort，且**不返回思考原文**（只给摘要）
        if (containsAny(p, "openai", "azure", "gpt", "o1", "o3", "o4")) {
            return Map.of("reasoning_effort", "medium");
        }
        return Map.of();
    }

    /** 该 provider 是否会带思考参数（用于 400 降级判断与日志）。 */
    public static boolean hasParams(String provider) {
        return !forProvider(provider).isEmpty();
    }

    private static boolean containsAny(String p, String... keys) {
        for (String k : keys) {
            if (p.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
