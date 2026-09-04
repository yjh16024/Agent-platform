package com.agentplatform.core.prompt.enhancer;

import com.agentplatform.core.prompt.EnhanceContext;
import com.agentplatform.core.prompt.PromptAnalysis;
import com.agentplatform.core.prompt.PromptEnhancer;
import com.agentplatform.model.record.OptimizationResult;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 格式增强（策略）。
 * <p>规范输出格式段，识别「返回 JSON」等意图并补全「## 输出格式 (Format)」段。</p>
 */
@Component
public class FormatEnhancer implements PromptEnhancer {

    private static final Pattern JSON_HINT = Pattern.compile("(?i)(json|json\\s+格式)");

    @Override
    public boolean supports(PromptAnalysis analysis, EnhanceContext ctx) {
        return !analysis.hasFormat();
    }

    @Override
    public int order() {
        return 4;
    }

    @Override
    public OptimizationResult.DiffEntry enhance(PromptAnalysis analysis, EnhanceContext ctx) {
        String section;
        if (JSON_HINT.matcher(analysis.rawPrompt()).find()) {
            section = "## 输出格式 (Format)\n严格输出 JSON，Schema 如下：\n"
                    + "{\"result\": \"...\"}\n禁止输出 JSON 以外的任何文本。\n";
        } else {
            section = "## 输出格式 (Format)\n先给结论，再给步骤说明，最后可附必要的补充信息。\n";
        }
        return new OptimizationResult.DiffEntry(
                OptimizationResult.DiffEntry.DiffType.added, "## 输出格式", null, section);
    }
}