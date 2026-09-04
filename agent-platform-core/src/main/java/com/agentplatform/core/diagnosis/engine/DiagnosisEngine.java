package com.agentplatform.core.diagnosis.engine;

import com.agentplatform.core.diagnosis.DiagnosisContext;
import com.agentplatform.core.diagnosis.DiagnosisStrategy;
import com.agentplatform.model.record.DiagnosticReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;

/**
 * 诊断引擎（策略 + 责任链 + 结构化并发，对应 §5.5）。
 * <p>
 * 三级策略按优先级串联：规则精确匹配（秒级）→ 向量语义检索（亚秒级）→ LLM 根因推理（兜底）。
 * </p>
 * <ul>
 *   <li>{@link #diagnose}：责任链顺序执行（确定性、成本最优——仅未知错误调 LLM）</li>
 *   <li>{@link #diagnoseConcurrent}：结构化并发（StructuredTaskScope.ShutdownOnSuccess），
 *       三级策略扇出，任一路成功即返回并取消其余分支（JDK 21 特性，§4.5.2）</li>
 * </ul>
 */
@Slf4j
@Component
public class DiagnosisEngine {

    private static final double MIN_CONFIDENCE = 0.5;

    private final List<DiagnosisStrategy> strategies;

    /**
     * Spring 自动注入全部 DiagnosisStrategy Bean（按 @Order，默认注入顺序）。
     */
    public DiagnosisEngine(List<DiagnosisStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 责任链顺序诊断（主方法，确定性）。
     */
    public DiagnosticReport diagnose(DiagnosisContext ctx) {
        for (DiagnosisStrategy strategy : strategies) {
            try {
                if (strategy.supports(ctx)) {
                    DiagnosticReport report = strategy.analyze(ctx);
                    if (report != null && report.confidence() >= MIN_CONFIDENCE) {
                        return report;
                    }
                }
            } catch (Exception e) {
                log.warn("Diagnosis strategy {} failed: {}", strategy.getClass().getSimpleName(), e.getMessage());
            }
        }
        return DiagnosticReport.unknown(ctx.traceId(), ctx.fingerprint());
    }

    /**
     * 结构化并发诊断（JDK 21 特性展示）。
     * <p>三级策略并发扇出，最快返回有效结果的一路胜出，其余自动取消。</p>
     */
    public DiagnosticReport diagnoseConcurrent(DiagnosisContext ctx) {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<DiagnosticReport>()) {
            for (DiagnosisStrategy strategy : strategies) {
                scope.fork(() -> runStrategy(strategy, ctx));
            }
            scope.join();
            return scope.result();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DiagnosticReport.unknown(ctx.traceId(), ctx.fingerprint());
        } catch (ExecutionException e) {
            // 全部策略未命中
            return DiagnosticReport.unknown(ctx.traceId(), ctx.fingerprint());
        }
    }

    /**
     * 单策略执行：不适用或结果不足置信度时抛异常（使 ShutdownOnSuccess 跳过该路）。
     */
    private DiagnosticReport runStrategy(DiagnosisStrategy strategy, DiagnosisContext ctx) {
        if (!strategy.supports(ctx)) {
            throw new NoDiagnosisException();
        }
        DiagnosticReport report = strategy.analyze(ctx);
        if (report == null || report.confidence() < MIN_CONFIDENCE) {
            throw new NoDiagnosisException();
        }
        return report;
    }

    /** 内部标记：该策略未产出有效诊断。 */
    private static final class NoDiagnosisException extends RuntimeException {
    }
}