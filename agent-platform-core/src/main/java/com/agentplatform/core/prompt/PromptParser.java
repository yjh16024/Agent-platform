package com.agentplatform.core.prompt;

import org.springframework.stereotype.Component;

/**
 * 提示词解析器（AST 解析 / 意图识别）。
 * <p>检测已有 Markdown 分节（## 角色 / ## 任务 / ## 约束 / ## 输出格式）、
 * Few-shot 示例、思维链标记，供缺失检测与评分使用。</p>
 */
@Component
public class PromptParser {

    /**
     * 解析原始提示词。
     */
    public PromptAnalysis parse(String rawPrompt) {
        String text = rawPrompt == null ? "" : rawPrompt;
        String lower = text.toLowerCase();
        return new PromptAnalysis(
                rawPrompt,
                contains(lower, "## 角色", "## role", "## 身份", "你是一名", "你是一位"),
                contains(lower, "## 任务", "## task", "## 目标", "## 职责"),
                contains(lower, "## 约束", "## constraint", "## 要求", "## 限制"),
                contains(lower, "## 输出格式", "## 输出", "## format", "输出格式", "返回 json", "返回json"),
                contains(lower, "## 示例", "## example", "## 例子", "few-shot"),
                contains(lower, "## 思考", "## cot", "思考过程", "逐步思考", "step by step"),
                text.length()
        );
    }

    private boolean contains(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}