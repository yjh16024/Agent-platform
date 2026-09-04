package com.agentplatform.core.diagnosis.strategy;

import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.diagnosis.DiagnosisContext;
import com.agentplatform.core.diagnosis.DiagnosisStrategy;
import com.agentplatform.core.rag.retriever.EmbeddingService;
import com.agentplatform.core.rag.retriever.VectorStore;
import com.agentplatform.model.enums.ErrorSeverity;
import com.agentplatform.model.enums.SolutionType;
import com.agentplatform.model.record.DiagnosisSource;
import com.agentplatform.model.record.DiagnosticReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 向量语义检索策略（二级，亚秒级）。
 * <p>对未匹配规则错误做 embedding，在「历史错误-根因-方案」向量库中相似检索。
 * MVP：检索 demo 场景，若命中知识库返回相似案例，否则返回 null 交给 LLM 兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorSimilarityDiagnosis implements DiagnosisStrategy {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;

    @Override
    public boolean supports(DiagnosisContext ctx) {
        return ctx.hasErrorMessage();
    }

    @Override
    public DiagnosticReport analyze(DiagnosisContext ctx) {
        float[] embedding = embeddingService.embed(ctx.message());
        List<VectorStore.VectorMatch> matches = vectorStore.similaritySearch(embedding, 3, 0.0);
        // MVP：向量库无历史案例时返回 null（交给 LLM 兜底）
        if (matches.isEmpty()) {
            return null;
        }
        VectorStore.VectorMatch top = matches.get(0);
        return new DiagnosticReport(
                IdGenerator.generate("diag"),
                ctx.traceId(),
                ctx.fingerprint(),
                ErrorSeverity.MAJOR,
                "unknown",
                new DiagnosticReport.RootCause(
                        "历史相似案例：" + top.metadata().getOrDefault("summary", "无"),
                        "向量语义相似检索命中历史错误案例", top.metadata()),
                List.of(new DiagnosticReport.Solution(
                        "参考历史案例解决方案",
                        String.valueOf(top.metadata().getOrDefault("solution", "无记录")),
                        SolutionType.MANUAL, null, top.score())),
                null, LocalDateTime.now(), DiagnosisSource.VECTOR);
    }
}