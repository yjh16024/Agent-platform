package com.agentplatform.core.prompt.enhancer;

import com.agentplatform.core.prompt.EnhanceContext;
import com.agentplatform.core.prompt.PromptAnalysis;
import com.agentplatform.core.prompt.PromptEnhancer;
import com.agentplatform.model.record.OptimizationResult;
import org.springframework.stereotype.Component;

/**
 * 示例生成（策略，按需）。
 * <p>当 options.generateExamples=true 且无示例时，注入「## 示例 (Examples)」段
 * （MVP 生成确定性模板，生产由优化模型按场景生成 Few-shot）。</p>
 */
@Component
public class ExampleGenerator implements PromptEnhancer {

    @Override
    public boolean supports(PromptAnalysis analysis, EnhanceContext ctx) {
        return ctx.options() != null && ctx.options().generateExamples() && !analysis.hasExamples();
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public OptimizationResult.DiffEntry enhance(PromptAnalysis analysis, EnhanceContext ctx) {
        String section = "## 示例 (Examples)\n"
                + "输入：...\n"
                + "输出：...（请按实际场景补全 2-3 个示例以提升输出稳定性）\n";
        return new OptimizationResult.DiffEntry(
                OptimizationResult.DiffEntry.DiffType.added, "## 示例", null, section);
    }
}