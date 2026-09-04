package com.agentplatform.core.prompt;

import com.agentplatform.model.record.OptimizationResult;

/**
 * 提示词增强策略（策略模式，对应 §5.6）。
 * <p>每种增强（角色/结构/约束/格式/示例/CoT）独立 Strategy，Spring 自动收集成责任链。</p>
 */
public interface PromptEnhancer {

    /**
     * 是否适用（检测是否缺失该项）。
     */
    boolean supports(PromptAnalysis analysis, EnhanceContext ctx);

    /**
     * 优先级（越小越靠前）。
     */
    default int order() {
        return 100;
    }

    /**
     * 执行增强，返回差异条目（新增/修改）。
     */
    OptimizationResult.DiffEntry enhance(PromptAnalysis analysis, EnhanceContext ctx);
}