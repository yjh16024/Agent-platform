package com.agentplatform.core.prompt;

import com.agentplatform.model.record.Persona;

/**
 * 提示词优化上下文。
 *
 * @param persona      人格维度（可选，自动融合）
 * @param targetModel  目标模型（可选，风格适配）
 * @param useCase      用途场景标签
 * @param options      优化选项
 */
public record EnhanceContext(
        Persona persona,
        String targetModel,
        String useCase,
        EnhanceOptions options
) {
    public static EnhanceContext defaults() {
        return new EnhanceContext(null, null, null, EnhanceOptions.defaults());
    }

    /**
     * 优化选项。
     *
     * @param autoFix           自动应用确定性改写
     * @param generateExamples  是否生成 Few-shot 示例
     * @param injectCot         是否注入思维链
     * @param maxOutputTokens   最大输出 Token
     */
    public record EnhanceOptions(boolean autoFix, boolean generateExamples, boolean injectCot, int maxOutputTokens) {
        public static EnhanceOptions defaults() {
            return new EnhanceOptions(true, false, false, 2000);
        }
    }
}