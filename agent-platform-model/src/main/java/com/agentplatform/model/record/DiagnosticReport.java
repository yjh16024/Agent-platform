package com.agentplatform.model.record;

import com.agentplatform.model.enums.ErrorSeverity;
import com.agentplatform.model.enums.SolutionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 诊断报告（DiagnosticReport）。
 * <p>
 * 诊断引擎输出结果：根因 + 解决建议（按置信度排序）+ 来源 + 严重级别。
 * </p>
 *
 * @param reportId        报告 ID
 * @param traceId         关联 Trace
 * @param fingerprint     错误指纹
 * @param severity        严重级别
 * @param category        分类：skill_import / plugin / api / model / workflow / rag
 * @param rootCause       根因
 * @param solutions       解决建议（按置信度排序）
 * @param knowledgeBaseRef 关联文档链接
 * @param generatedAt     生成时间
 * @param source          来源：RULE / VECTOR / LLM
 */
public record DiagnosticReport(
        String reportId,
        String traceId,
        String fingerprint,
        ErrorSeverity severity,
        String category,
        RootCause rootCause,
        List<Solution> solutions,
        String knowledgeBaseRef,
        LocalDateTime generatedAt,
        DiagnosisSource source
) {
    /** 根因。 */
    public record RootCause(String summary, String detail, Map<String, Object> evidence) {
    }

    /** 解决建议。 */
    public record Solution(
            String title,
            String description,
            SolutionType type,
            String autoFixCommand,
            double confidence
    ) {
    }

    /**
     * 最高置信度（判断诊断结果是否可采纳）。
     */
    public double confidence() {
        return solutions == null || solutions.isEmpty()
                ? 0.0
                : solutions.stream().mapToDouble(Solution::confidence).max().orElse(0.0);
    }

    /**
     * 未知错误兜底报告。
     */
    public static DiagnosticReport unknown(String traceId, String fingerprint) {
        return new DiagnosticReport(
                "diag_" + System.nanoTime(), traceId, fingerprint,
                ErrorSeverity.MINOR, "unknown",
                new RootCause("未能定位根因", "该错误暂时无法匹配到已知模式", Map.of()),
                List.of(),
                null, LocalDateTime.now(), DiagnosisSource.LLM_);
    }
}