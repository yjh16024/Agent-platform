package com.agentplatform.core.skill.executor;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词执行器（Skill 的最小语义）。
 * <p>
 * 把 SKILL.md 正文按 {@code {{var}}} 模板变量渲染后输出——等价于「把 Skill 当作
 * 可执行命令」的默认形态：输出即注入上下文的提示词。无脚本、无网络的 Skill 均走此实现。
 * </p>
 */
@Component
public class PromptSkillExecutor implements SkillExecutor {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_\\-\\.]+)\\s*}}");

    @Override
    public String type() {
        return "prompt";
    }

    @Override
    public String description() {
        return "渲染 SKILL.md 提示词模板（{{var}} 变量替换）";
    }

    /**
     * 不按命令内容抢匹配：仅当显式指定 type=prompt 时命中。
     * 未指定类型的命令由 {@link SkillExecutorRegistry#resolve} 按「http/script 优先、
     * prompt 兜底」的顺序分发，避免提示词执行器吃掉脚本命令。
     */
    @Override
    public SkillExecutionResult execute(SkillCommand command) {
        long t0 = System.currentTimeMillis();
        String prompt = command.prompt() == null ? "" : command.prompt();
        if (prompt.isBlank()) {
            return SkillExecutionResult.fail("该 Skill 无提示词正文（SKILL.md 为空）", type(),
                    System.currentTimeMillis() - t0);
        }
        String rendered = render(prompt, command.args());
        return SkillExecutionResult.ok(rendered, type(), System.currentTimeMillis() - t0);
    }

    /** 用入参替换 {{var}} 占位符（未提供的变量替换为空串）。 */
    private String render(String template, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return template;
        }
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = args.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
