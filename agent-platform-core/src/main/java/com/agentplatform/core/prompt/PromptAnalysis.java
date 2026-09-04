package com.agentplatform.core.prompt;

/**
 * 提示词解析结果（缺失检测依据）。
 * <p>识别已有段落与缺失项，供各项 Enhancer 判断是否需要增强。</p>
 */
public record PromptAnalysis(
        String rawPrompt,
        boolean hasRole,
        boolean hasTask,
        boolean hasConstraints,
        boolean hasFormat,
        boolean hasExamples,
        boolean hasCot,
        int length
) {
    /**
     * 是否需要语义级（LLM）增强：既无角色也无任务等核心语义结构时。
     */
    public boolean needsSemanticEnhancement() {
        return !hasRole && !hasTask;
    }

    /**
     * 是否已基本完整（角色+任务+约束+格式齐全）。
     */
    public boolean isComplete() {
        return hasRole && hasTask && hasConstraints && hasFormat;
    }
}