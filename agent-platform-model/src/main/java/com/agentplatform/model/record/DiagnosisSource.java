package com.agentplatform.model.record;

/**
 * 诊断来源（密封接口 + 记录实现，JDK 21）。
 * <p>
 * 使用 {@code sealed interface} + {@code record} 实现，配合 Switch 模式匹配
 * 获得编译期穷尽检查：新增来源类型时编译器强制处理所有分支。
 * </p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * String label = switch (source) {
 *     case DiagnosisSource.Rule r   -> "精确规则命中";
 *     case DiagnosisSource.Vector v -> "历史案例语义相似";
 *     case DiagnosisSource.LLM l    -> "LLM 推理兜底";
 * };
 * }</pre>
 */
public sealed interface DiagnosisSource
        permits DiagnosisSource.Rule, DiagnosisSource.Vector, DiagnosisSource.LLM {

    /** 精确规则命中（一级策略，秒级） */
    record Rule() implements DiagnosisSource {
    }

    /** 历史案例语义相似（二级策略，亚秒级） */
    record Vector() implements DiagnosisSource {
    }

    /** LLM 推理兜底（三级策略，秒级高质量） */
    record LLM() implements DiagnosisSource {
    }

    /** 常用单例。 */
    DiagnosisSource RULE = new Rule();
    DiagnosisSource VECTOR = new Vector();
    DiagnosisSource LLM_ = new LLM();

    /**
     * 转换为数据库存储字符串。
     */
    static String toDb(DiagnosisSource source) {
        return switch (source) {
            case Rule r -> "RULE";
            case Vector v -> "VECTOR";
            case LLM l -> "LLM";
        };
    }

    /**
     * 从数据库字符串反向解析。
     */
    static DiagnosisSource fromDb(String v) {
        return switch (v == null ? "RULE" : v.toUpperCase()) {
            case "VECTOR" -> VECTOR;
            case "LLM" -> LLM_;
            default -> RULE;
        };
    }
}