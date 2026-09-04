package com.agentplatform.core.diagnosis.strategy;

import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.diagnosis.DiagnosisContext;
import com.agentplatform.core.diagnosis.DiagnosisStrategy;
import com.agentplatform.core.diagnosis.rule.BuiltinDiagnosisRules;
import com.agentplatform.core.diagnosis.rule.ErrorRule;
import com.agentplatform.model.record.DiagnosisSource;
import com.agentplatform.model.record.DiagnosticReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 规则精确匹配策略（一级，秒级，零成本）。
 * <p>基于错误指纹匹配预置规则库，命中即返回（confidence ≥ 阈值）。</p>
 */
@Component
@RequiredArgsConstructor
public class RuleBasedDiagnosis implements DiagnosisStrategy {

    private final BuiltinDiagnosisRules rules;

    @Override
    public boolean supports(DiagnosisContext ctx) {
        return ctx.fingerprint() != null && rules.find(ctx.fingerprint()) != null;
    }

    @Override
    public DiagnosticReport analyze(DiagnosisContext ctx) {
        ErrorRule rule = rules.find(ctx.fingerprint());
        if (rule == null) {
            return null;
        }
        return new DiagnosticReport(
                IdGenerator.generate("diag"),
                ctx.traceId(),
                ctx.fingerprint(),
                rule.severity(),
                rule.category(),
                new DiagnosticReport.RootCause(
                        rule.rootCauseSummary(), rule.rootCauseDetail(),
                        Map.of("fingerprint", ctx.fingerprint())),
                rule.solutions(),
                null,
                LocalDateTime.now(),
                DiagnosisSource.RULE);
    }
}