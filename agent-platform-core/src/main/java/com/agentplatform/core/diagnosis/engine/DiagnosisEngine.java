package com.agentplatform.core.diagnosis.engine;

import com.agentplatform.core.diagnosis.DiagnosisContext;
import com.agentplatform.core.diagnosis.DiagnosisStrategy;
import com.agentplatform.model.record.DiagnosticReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 诊断引擎（策略 + 责任链 + 结构化并发，对应 §5.5）。
 * <p>
 * 三级策略按优先级串联：规则精确匹配（秒级）→ 向量语义检索（亚秒级）→ LLM 根因推理（兜底）。
 * </p>
 * <ul>
 *   <li>{@link #diagnose}：责任链顺序执行（确定性、成本最优——仅未知错误调 LLM）</li>
 *   <li>{@link #diagnoseConcurrent}：并发扇出（虚拟线程 + {@code invokeAny}），
 *       任一路成功即返回并取消其余分支（§4.5.2）</li>
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
     * 并发诊断：三级策略扇出，最快返回有效结果的一路胜出，其余自动取消。
     *
     * <p>2026-11-06 把 {@code StructuredTaskScope.ShutdownOnSuccess} 换成了
     * 虚拟线程 + {@link ExecutorService#invokeAny}：语义一致（首个成功即返回、
     * 其余取消、全部失败抛 {@code ExecutionException}），但去掉了 JDK 预览特性依赖。
     * 原因是框架要能被第三方嵌入，不能要求宿主构建流水线开启预览特性。</p>
     */
    public DiagnosticReport diagnoseConcurrent(DiagnosisContext ctx) {
        if (strategies.isEmpty()) {
            return DiagnosticReport.unknown(ctx.traceId(), ctx.fingerprint());
        }
        List<Callable<DiagnosticReport>> tasks = strategies.stream()
                .<Callable<DiagnosticReport>>map(strategy -> () -> runStrategy(strategy, ctx))
                .toList();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.invokeAny(tasks);
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