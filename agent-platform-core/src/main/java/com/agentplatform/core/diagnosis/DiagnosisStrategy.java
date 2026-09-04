package com.agentplatform.core.diagnosis;

import com.agentplatform.model.record.DiagnosticReport;

/**
 * 诊断策略（策略模式，对应 §5.5）。
 * <p>三种分析方式统一接口：规则精确匹配（秒级）、向量语义检索（亚秒级）、
 * LLM 根因推理（秒级兜底），引擎按优先级串联命中即返回。</p>
 */
public interface DiagnosisStrategy {

    /**
     * 是否适用该策略（规则/向量/LLM 各有判断逻辑）。
     */
    boolean supports(DiagnosisContext ctx);

    /**
     * 执行分析，返回诊断报告（不适用/无结果时返回 null）。
     */
    DiagnosticReport analyze(DiagnosisContext ctx);
}