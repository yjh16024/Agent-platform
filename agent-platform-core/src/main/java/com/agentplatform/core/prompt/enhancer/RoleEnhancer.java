package com.agentplatform.core.prompt.enhancer;

import com.agentplatform.core.prompt.EnhanceContext;
import com.agentplatform.core.prompt.PromptAnalysis;
import com.agentplatform.core.prompt.PromptEnhancer;
import com.agentplatform.model.record.OptimizationResult;
import org.springframework.stereotype.Component;

/**
 * 角色增强（策略）。
 * <p>无角色段时注入「## 角色 (Role)」段，融合人格维度（role/tone/style）自动生成。</p>
 */
@Component
public class RoleEnhancer implements PromptEnhancer {

    @Override
    public boolean supports(PromptAnalysis analysis, EnhanceContext ctx) {
        return !analysis.hasRole();
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public OptimizationResult.DiffEntry enhance(PromptAnalysis analysis, EnhanceContext ctx) {
        String role = renderRole(ctx);
        String section = "## 角色 (Role)\n" + role + "\n";
        return new OptimizationResult.DiffEntry(
                OptimizationResult.DiffEntry.DiffType.added, "## 角色", null, section);
    }

    private String renderRole(EnhanceContext ctx) {
        if (ctx.persona() != null && ctx.persona().role() != null && !ctx.persona().role().isBlank()) {
            return "你是一名" + ctx.persona().role() + "。";
        }
        if (ctx.useCase() != null) {
            return "你是一名经验丰富的" + useCaseLabel(ctx.useCase()) + "专家。";
        }
        return "你是一名资深领域专家，具备专业且可靠的回答能力。";
    }

    private String useCaseLabel(String useCase) {
        return switch (useCase) {
            case "customer_service" -> "电商客服";
            case "code_review" -> "代码评审";
            case "translation" -> "翻译";
            case "writing" -> "写作";
            default -> useCase;
        };
    }
}