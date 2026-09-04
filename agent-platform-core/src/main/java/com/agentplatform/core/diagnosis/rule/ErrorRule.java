package com.agentplatform.core.diagnosis.rule;

import com.agentplatform.model.enums.ErrorSeverity;
import com.agentplatform.model.enums.SolutionType;
import com.agentplatform.model.record.DiagnosticReport;

import java.util.List;
import java.util.Map;

/**
 * 诊断规则（错误指纹 → 根因 + 解决建议）。
 *
 * @param fingerprint 错误指纹
 * @param category    分类
 * @param severity    严重级别
 * @param rootCauseSummary 根因摘要
 * @param rootCauseDetail  根因详细说明
 * @param solutions   解决建议（按置信度）
 * @param confidence  规则置信度
 */
public record ErrorRule(
        String fingerprint,
        String category,
        ErrorSeverity severity,
        String rootCauseSummary,
        String rootCauseDetail,
        List<DiagnosticReport.Solution> solutions,
        double confidence
) {
    /**
     * 便捷构造解决建议。
     */
    public static DiagnosticReport.Solution manual(String title, String description, double confidence) {
        return new DiagnosticReport.Solution(title, description, SolutionType.MANUAL, null, confidence);
    }
}