package com.agentplatform.core.prompt;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.model.record.OptimizationResult;
import com.agentplatform.model.record.Persona;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提示词优化接口（优化 / 评分）。
 */
@RestController
@RequestMapping("/api/v1/prompt")
@RequiredArgsConstructor
public class PromptController {

    private final PromptOptimizer optimizer;

    /**
     * 优化（核心接口）。
     */
    @PostMapping("/optimize")
    public ApiResponse<OptimizationResult> optimize(@RequestBody Map<String, Object> body) {
        String rawPrompt = (String) body.get("raw_prompt");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("context");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");

        EnhanceContext ctx = buildContext(context, options);
        return ApiResponse.ok(optimizer.optimize(rawPrompt, ctx));
    }

    /**
     * 评分（不改写）。
     */
    @PostMapping("/score")
    public ApiResponse<OptimizationResult.ScoreReport> score(@RequestBody Map<String, Object> body) {
        String prompt = (String) body.get("prompt");
        return ApiResponse.ok(optimizer.score(prompt));
    }

    private EnhanceContext buildContext(Map<String, Object> context, Map<String, Object> options) {
        Persona persona = null;
        if (context != null && context.get("persona") instanceof Map<?, ?> p) {
            persona = new Persona(
                    (String) p.get("tone"), (String) p.get("style"), (String) p.get("role"),
                    (Double) p.get("warmth"), (Double) p.get("expertise"), (Double) p.get("proactiveness"),
                    null, null);
        }
        String targetModel = context == null ? null : (String) context.get("target_model");
        String useCase = context == null ? null : (String) context.get("use_case");

        EnhanceContext.EnhanceOptions opts = new EnhanceContext.EnhanceOptions(
                options == null || !Boolean.FALSE.equals(options.get("auto_fix")),
                options != null && Boolean.TRUE.equals(options.get("generate_examples")),
                options != null && Boolean.TRUE.equals(options.get("inject_cot")),
                options == null ? 2000 : ((Number) options.getOrDefault("max_output_tokens", 2000)).intValue());
        return new EnhanceContext(persona, targetModel, useCase, opts);
    }
}