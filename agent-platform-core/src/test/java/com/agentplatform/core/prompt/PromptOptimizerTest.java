package com.agentplatform.core.prompt;

import com.agentplatform.core.prompt.enhancer.CoTInjection;
import com.agentplatform.core.prompt.enhancer.ConstraintEnhancer;
import com.agentplatform.core.prompt.enhancer.ExampleGenerator;
import com.agentplatform.core.prompt.enhancer.FormatEnhancer;
import com.agentplatform.core.prompt.enhancer.RoleEnhancer;
import com.agentplatform.core.prompt.enhancer.StructureEnhancer;
import com.agentplatform.model.record.OptimizationResult;
import com.agentplatform.model.record.Persona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提示词优化引擎单元测试（策略链 + 双阶段 + 评分）。
 */
class PromptOptimizerTest {

    private PromptOptimizer optimizer;
    private PromptParser parser;
    private QualityScorer scorer;

    @BeforeEach
    void setUp() {
        parser = new PromptParser();
        scorer = new QualityScorer();
        optimizer = new PromptOptimizer(
                List.of(new RoleEnhancer(), new StructureEnhancer(), new ConstraintEnhancer(),
                        new FormatEnhancer(), new ExampleGenerator(), new CoTInjection()),
                parser, scorer);
    }

    @Test
    @DisplayName("简单提示词优化后补全角色/任务/约束/格式")
    void optimizeSimplePrompt() {
        String raw = "你是一个客服，帮我回答用户问题";
        OptimizationResult result = optimizer.optimize(raw, EnhanceContext.defaults());

        assertTrue(result.optimizedPrompt().contains("## 角色"));
        assertTrue(result.optimizedPrompt().contains("## 任务"));
        assertTrue(result.optimizedPrompt().contains("## 约束"));
        assertFalse(result.diff().isEmpty());
    }

    @Test
    @DisplayName("优化后评分高于优化前")
    void scoreImprovesAfterOptimize() {
        String raw = "帮我写个客服";
        OptimizationResult.ScoreReport before = optimizer.score(raw);
        OptimizationResult result = optimizer.optimize(raw, EnhanceContext.defaults());
        assertTrue(result.score().overall() > before.overall(),
                "优化后评分应提升: " + before.overall() + " -> " + result.score().overall());
    }

    @Test
    @DisplayName("人格融合：role 注入角色段")
    void personaFusedIntoRole() {
        String raw = "回答问题";
        Persona persona = new Persona("friendly", null, "资深电商客服专家", 0.8, null, null, null, null);
        EnhanceContext ctx = new EnhanceContext(persona, null, "customer_service", EnhanceContext.EnhanceOptions.defaults());

        OptimizationResult result = optimizer.optimize(raw, ctx);
        assertTrue(result.optimizedPrompt().contains("资深电商客服专家"));
    }

    @Test
    @DisplayName("已完整提示词不重复增强")
    void completePromptNotEnhanced() {
        String complete = "## 角色\n你是专家\n## 任务\n回答问题\n## 约束\n1. 准确\n## 输出格式\nMarkdown\n";
        OptimizationResult result = optimizer.optimize(complete, EnhanceContext.defaults());
        // 已完整，各段已存在，几乎无新增 diff
        assertTrue(result.diff().size() <= 1, "完整提示词应几乎无 diff");
    }

    @Test
    @DisplayName("CoT 注入仅当 injectCot=true")
    void cotOnlyWhenEnabled() {
        EnhanceContext.EnhanceOptions withCot = new EnhanceContext.EnhanceOptions(true, false, true, 2000);
        EnhanceContext ctx = new EnhanceContext(null, null, null, withCot);
        OptimizationResult result = optimizer.optimize("分析数据", ctx);
        assertTrue(result.optimizedPrompt().contains("## 思考过程"));
    }

    @Test
    @DisplayName("评分各维度在 0~100 区间")
    void scoreInRange() {
        OptimizationResult.ScoreReport score = optimizer.score("随便写点");
        assertTrue(score.overall() >= 0 && score.overall() <= 100);
        assertTrue(score.clarity() >= 0 && score.clarity() <= 100);
        assertTrue(score.structure() >= 0 && score.structure() <= 100);
    }
}