package com.agentplatform.core.prompt.enhancer;

import com.agentplatform.core.prompt.EnhanceContext;
import com.agentplatform.core.prompt.PromptAnalysis;
import com.agentplatform.core.prompt.PromptEnhancer;
import com.agentplatform.model.record.OptimizationResult;
import org.springframework.stereotype.Component;

/**
 * 思维链注入（策略，按需）。
 * <p>当 options.injectCot=true 且无思维链标记时，注入「## 思考过程」段，
 * 引入「逐步推理」结构化框架。</p>
 */
@Component
public class CoTInjection implements PromptEnhancer {

    @Override
    public boolean supports(PromptAnalysis analysis, EnhanceContext ctx) {
        return ctx.options() != null && ctx.options().injectCot() && !analysis.hasCot();
    }

    @Override
    public int order() {
        return 6;
    }

    @Override
    public OptimizationResult.DiffEntry enhance(PromptAnalysis analysis, EnhanceContext ctx) {
        String section = "## 思考过程 (Chain-of-Thought)\n"
                + "请按以下步骤逐步分析：\n"
                + "1. 先理解问题核心；\n"
                + "2. 分解为子问题并逐一分析；\n"
                + "3. 综合得出最终结论。\n";
        return new OptimizationResult.DiffEntry(
                OptimizationResult.DiffEntry.DiffType.added, "## 思考过程", null, section);
    }
}