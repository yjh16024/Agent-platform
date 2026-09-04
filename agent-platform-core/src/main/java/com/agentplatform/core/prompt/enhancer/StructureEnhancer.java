package com.agentplatform.core.prompt.enhancer;

import com.agentplatform.core.prompt.EnhanceContext;
import com.agentplatform.core.prompt.PromptAnalysis;
import com.agentplatform.core.prompt.PromptEnhancer;
import com.agentplatform.model.record.OptimizationResult;
import org.springframework.stereotype.Component;

/**
 * 结构增强（策略）。
 * <p>补全「## 任务 (Task)」段，将模糊指令补全为明确任务目标。</p>
 */
@Component
public class StructureEnhancer implements PromptEnhancer {

    @Override
    public boolean supports(PromptAnalysis analysis, EnhanceContext ctx) {
        return !analysis.hasTask();
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public OptimizationResult.DiffEntry enhance(PromptAnalysis analysis, EnhanceContext ctx) {
        String task = "## 任务 (Task)\n针对用户输入，给出准确、完整、可执行的回答，必要时分解为清晰步骤。\n";
        return new OptimizationResult.DiffEntry(
                OptimizationResult.DiffEntry.DiffType.added, "## 任务", null, task);
    }
}