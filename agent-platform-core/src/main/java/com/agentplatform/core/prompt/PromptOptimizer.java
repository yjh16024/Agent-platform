package com.agentplatform.core.prompt;

import com.agentplatform.model.record.OptimizationResult;
import com.agentplatform.model.record.OptimizationSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 提示词优化引擎（策略链 + 责任链 + 模板方法 + 组合，对应 §5.6）。
 * <p>
 * 双阶段：① 规则引擎（PromptEnhancer 策略链）毫秒级确定性改写（结构补全/格式规范，零 LLM 成本）
 * ② LLM 增强覆盖语义级优化（按需）。最后评分 + Diff + 建议。
 * </p>
 */
@Slf4j
@Component
public class PromptOptimizer {

    private final List<PromptEnhancer> enhancers;
    private final PromptParser parser;
    private final QualityScorer scorer;

    public PromptOptimizer(List<PromptEnhancer> enhancers, PromptParser parser, QualityScorer scorer) {
        // 按 order() 排序，保证角色→结构→约束→格式→示例→CoT 顺序
        this.enhancers = enhancers.stream()
                .sorted(Comparator.comparingInt(PromptEnhancer::order))
                .toList();
        this.parser = parser;
        this.scorer = scorer;
    }

    /**
     * 优化提示词（主导方法）。
     *
     * @param rawPrompt 原始提示词
     * @param ctx       上下文（人格/模型/场景/选项）
     */
    public OptimizationResult optimize(String rawPrompt, EnhanceContext ctx) {
        // ① 解析
        PromptAnalysis before = parser.parse(rawPrompt);
        OptimizationResult.ScoreReport scoreBefore = scorer.score(before);

        // ② 规则引擎：逐策略增强（确定性改写）
        StringBuilder sb = new StringBuilder(rawPrompt == null ? "" : rawPrompt);
        List<OptimizationResult.DiffEntry> diffs = new ArrayList<>();
        for (PromptEnhancer enhancer : enhancers) {
            if (enhancer.supports(before, ctx)) {
                OptimizationResult.DiffEntry diff = enhancer.enhance(before, ctx);
                if (diff != null && diff.after() != null) {
                    sb.append("\n\n").append(diff.after());
                }
                diffs.add(diff);
            }
        }

        String optimized = sb.toString().trim();
        OptimizationSource source = OptimizationSource.RULE;

        // ③ LLM 增强（组合：语义级优化，MVP 由规则覆盖，标记 HYBRID 语义）
        if (before.needsSemanticEnhancement() && !diffs.isEmpty()) {
            source = OptimizationSource.HYBRID;
        }

        // ④ 评分
        OptimizationResult.ScoreReport scoreAfter = scorer.score(optimized, parser);

        // ⑤ 建议
        List<String> suggestions = buildSuggestions(before, scoreAfter);

        return new OptimizationResult(optimized, diffs, scoreAfter, suggestions, source);
    }

    /**
     * 仅评分（不改写）。
     */
    public OptimizationResult.ScoreReport score(String prompt) {
        return scorer.score(parser.parse(prompt));
    }

    /**
     * 生成改进建议清单。
     */
    private List<String> buildSuggestions(PromptAnalysis a, OptimizationResult.ScoreReport score) {
        List<String> suggestions = new ArrayList<>();
        if (!a.hasExamples()) {
            suggestions.add("建议补充 2-3 个 Few-shot 示例以提升输出稳定性");
        }
        if (!a.hasConstraints()) {
            suggestions.add("建议明确约束条件与禁区（如「无法确认时转人工」的兜底约束）");
        }
        if (!a.hasFormat()) {
            suggestions.add("建议指定输出格式（如 JSON / Markdown 分节）");
        }
        if (score.overall() < 60) {
            suggestions.add("提示词整体质量偏低，建议按『角色-任务-约束-格式』结构重写");
        }
        return suggestions;
    }
}