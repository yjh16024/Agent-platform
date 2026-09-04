package com.agentplatform.model.record;

/**
 * 提示词优化来源（密封接口 + 记录实现，JDK 21）。
 */
public sealed interface OptimizationSource
        permits OptimizationSource.Rule, OptimizationSource.LLM, OptimizationSource.Hybrid {

    /** 仅规则引擎（毫秒级、确定性） */
    record Rule() implements OptimizationSource {
    }

    /** 仅 LLM 增强 */
    record LLM() implements OptimizationSource {
    }

    /** 规则 + LLM 混合 */
    record Hybrid() implements OptimizationSource {
    }

    OptimizationSource RULE = new Rule();
    OptimizationSource LLM_ = new LLM();
    OptimizationSource HYBRID = new Hybrid();

    static String toDb(OptimizationSource source) {
        return switch (source) {
            case Rule r -> "RULE";
            case LLM l -> "LLM";
            case Hybrid h -> "HYBRID";
        };
    }

    static OptimizationSource fromDb(String v) {
        return switch (v == null ? "RULE" : v.toUpperCase()) {
            case "LLM" -> LLM_;
            case "HYBRID" -> HYBRID;
            default -> RULE;
        };
    }
}