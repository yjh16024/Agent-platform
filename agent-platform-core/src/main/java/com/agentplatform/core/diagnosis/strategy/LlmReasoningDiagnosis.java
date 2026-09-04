package com.agentplatform.core.diagnosis.strategy;

import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.diagnosis.DiagnosisContext;
import com.agentplatform.core.diagnosis.DiagnosisStrategy;
import com.agentplatform.core.model.ModelCapability;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.model.enums.ErrorSeverity;
import com.agentplatform.model.enums.SolutionType;
import com.agentplatform.model.record.DiagnosisSource;
import com.agentplatform.model.record.DiagnosticReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * LLM 根因推理策略（三级，兜底，秒级高质量）。
 * <p>前两级未命中时，将异常堆栈 + 上下文组装为诊断 Prompt，由专用分析模型
 * 推理根因并生成可执行方案。</p>
 */
@Component
@RequiredArgsConstructor
public class LlmReasoningDiagnosis implements DiagnosisStrategy {

    private final ModelRouter modelRouter;

    @Override
    public boolean supports(DiagnosisContext ctx) {
        return true; // 永远兜底
    }

    @Override
    public DiagnosticReport analyze(DiagnosisContext ctx) {
        // 组装诊断 Prompt：堆栈 + 上下文 + 环境信息
        String prompt = buildDiagnosisPrompt(ctx);
        String reply;
        try {
            ModelAdapter.ChatResponse resp = modelRouter.route("auto", ModelCapability.TEXT)
                    .chat(new ModelAdapter.ChatRequest(null, null, prompt, 0.2, 1000, Map.of()));
            reply = resp.content();
        } catch (Exception e) {
            // 无可用模型时降级为静态兜底
            reply = "根因待人工排查 | 建议";
        }

        return new DiagnosticReport(
                IdGenerator.generate("diag"),
                ctx.traceId(),
                ctx.fingerprint(),
                ErrorSeverity.MINOR,
                ctx.category() == null ? "unknown" : ctx.category().name(),
                new DiagnosticReport.RootCause(
                        "LLM 推理：" + reply, "三级策略 LLM 兜底分析", Map.of("fingerprint", ctx.fingerprint() == null ? "" : ctx.fingerprint())),
                List.of(new DiagnosticReport.Solution(
                        "LLM 分析建议", reply, SolutionType.MANUAL, null, 0.6)),
                null, LocalDateTime.now(), DiagnosisSource.LLM_);
    }

    private String buildDiagnosisPrompt(DiagnosisContext ctx) {
        return "你是一名运维诊断专家，请分析以下错误并给出根因与解决步骤。\n"
                + "错误分类: " + (ctx.category() == null ? "unknown" : ctx.category()) + "\n"
                + "错误消息: " + (ctx.message() == null ? "" : ctx.message()) + "\n"
                + "异常堆栈: " + (ctx.stackTrace() == null ? "" : ctx.stackTrace()) + "\n"
                + "上下文: " + (ctx.context() == null ? "{}" : ctx.context()) + "\n"
                + "请简洁给出：1) 根因 2) 3 条可执行解决步骤。";
    }
}