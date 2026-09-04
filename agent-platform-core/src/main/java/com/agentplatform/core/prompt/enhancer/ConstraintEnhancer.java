package com.agentplatform.core.prompt.enhancer;

import com.agentplatform.core.prompt.EnhanceContext;
import com.agentplatform.core.prompt.PromptAnalysis;
import com.agentplatform.core.prompt.PromptEnhancer;
import com.agentplatform.model.record.OptimizationResult;
import org.springframework.stereotype.Component;

/**
 * 约束增强（策略）。
 * <p>补全「## 约束 (Constraints)」段，融合人格禁区（forbidden）等约束。</p>
 */
@Component
public class ConstraintEnhancer implements PromptEnhancer {

    @Override
    public boolean supports(PromptAnalysis analysis, EnhanceContext ctx) {
        return !analysis.hasConstraints();
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public OptimizationResult.DiffEntry enhance(PromptAnalysis analysis, EnhanceContext ctx) {
        StringBuilder sb = new StringBuilder("## 约束 (Constraints)\n");
        sb.append("1. 保持回答准确、客观，不编造信息；\n");
        sb.append("2. 无法确认时明确说明，不要臆测；\n");
        if (ctx.persona() != null && ctx.persona().forbidden() != null && !ctx.persona().forbidden().isEmpty()) {
            sb.append("3. 绝不").append(String.join("、", ctx.persona().forbidden())).append("；\n");
        }
        return new OptimizationResult.DiffEntry(
                OptimizationResult.DiffEntry.DiffType.added, "## 约束", null, sb.toString());
    }
}