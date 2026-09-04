package com.agentplatform.core.prompt;

import com.agentplatform.model.record.OptimizationResult;
import org.springframework.stereotype.Component;

/**
 * 质量评分器（6 维度量化）。
 * <p>
 * 规则评分：清晰性/完整性/结构化/约束明确性/示例质量/模型适配性，
 * 输出 0~100 总分 + 逐维度得分。MVP 为确定性规则评分；生产叠加 LLM 校准。
 * </p>
 */
@Component
public class QualityScorer {

    /**
     * 对提示词评分（重新解析 + 维度打分）。
     */
    public OptimizationResult.ScoreReport score(String prompt, PromptParser parser) {
        PromptAnalysis a = parser.parse(prompt);
        return score(a);
    }

    /**
     * 基于解析结果评分。
     */
    public OptimizationResult.ScoreReport score(PromptAnalysis a) {
        double clarity = a.rawPrompt() == null || a.rawPrompt().isBlank() ? 0
                : clamp(40 + Math.min(50, a.rawPrompt().length() / 40.0) + (a.hasTask() ? 10 : 0));
        double completeness = dim(a.hasRole(), a.hasTask(), a.hasConstraints(), a.hasFormat());
        double structure = a.hasRole() && a.hasTask() && a.hasFormat() ? 90 : 50;
        double constraints = a.hasConstraints() ? 85 : 40;
        double examples = a.hasExamples() ? 80 : 50;
        double modelAlignment = a.hasFormat() ? 85 : 60;

        double overall = Math.round((clarity * 0.2 + completeness * 0.25 + structure * 0.2
                + constraints * 0.15 + examples * 0.1 + modelAlignment * 0.1));
        return new OptimizationResult.ScoreReport(
                clamp(overall), round(clarity), round(completeness), round(structure),
                round(constraints), round(examples), round(modelAlignment));
    }

    /**
     * 完整性：四个核心段是否齐全。
     */
    private double dim(boolean role, boolean task, boolean constraints, boolean format) {
        int count = (role ? 1 : 0) + (task ? 1 : 0) + (constraints ? 1 : 0) + (format ? 1 : 0);
        return switch (count) {
            case 4 -> 95;
            case 3 -> 75;
            case 2 -> 55;
            case 1 -> 35;
            default -> 15;
        };
    }

    private double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}