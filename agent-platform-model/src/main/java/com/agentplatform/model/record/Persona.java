package com.agentplatform.model.record;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 智能体人格/性格结构化维度。
 * <p>
 * 以 {@code record} 建模保证不可变与线程安全，与 JPA {@code @JdbcTypeCode(SqlTypes.JSON)}
 * 无缝映射。运行时与自由文本人格描述合并为完整人格画像注入系统提示词。
 * </p>
 *
 * @param tone          语气：formal / friendly / humorous / concise / empathetic
 * @param style         风格：academic / conversational / bullet / storytelling
 * @param role          角色设定，如「资深电商客服专家」
 * @param warmth        情感温度 0.0~1.0
 * @param expertise     专业度 0.0~1.0
 * @param proactiveness 主动性 0.0~1.0
 * @param catchphrases  口头禅
 * @param forbidden     禁区/绝不行为
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Persona(
        String tone,
        String style,
        String role,
        Double warmth,
        Double expertise,
        Double proactiveness,
        List<String> catchphrases,
        List<String> forbidden
) {
    /** 便捷构造：仅角色。 */
    public static Persona ofRole(String role) {
        return new Persona(null, null, role, null, null, null, null, null);
    }

    /**
     * 将人格维度翻译为提示词段（供提示词优化引擎与运行时合并使用）。
     */
    public Map<String, String> toPromptSegments() {
        return Map.of(
                "tone", tone == null ? "" : tone,
                "style", style == null ? "" : style,
                "role", role == null ? "" : role
        );
    }
}