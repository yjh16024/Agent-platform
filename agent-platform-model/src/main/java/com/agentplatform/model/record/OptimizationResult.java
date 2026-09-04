package com.agentplatform.model.record;

import java.util.List;

/**
 * 提示词优化结果。
 *
 * @param optimizedPrompt 优化后提示词
 * @param diff            差异（added/removed/modified）
 * @param score           优化后评分
 * @param suggestions     改进建议
 * @param source          来源（RULE / LLM / HYBRID）
 */
public record OptimizationResult(
        String optimizedPrompt,
        List<DiffEntry> diff,
        ScoreReport score,
        List<String> suggestions,
        OptimizationSource source
) {
    /** 差异条目。 */
    public record DiffEntry(DiffType type, String section, String before, String after) {
        /** 差异类型。 */
        public enum DiffType { added, removed, modified }
    }

    /** 6 维度评分报告。 */
    public record ScoreReport(
            double overall,
            double clarity,
            double completeness,
            double structure,
            double constraints,
            double examples,
            double modelAlignment
    ) {
    }
}