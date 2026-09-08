package com.agentplatform.core.skill.executor;

/**
 * Skill 执行结果（与执行器实现无关的统一下游契约）。
 *
 * @param success    是否成功
 * @param output     输出（文本）
 * @param error      错误信息（失败时非空）
 * @param executor   实际使用的执行器类型
 * @param durationMs 耗时（毫秒）
 */
public record SkillExecutionResult(
        boolean success,
        String output,
        String error,
        String executor,
        long durationMs
) {
    public static SkillExecutionResult ok(String output, String executor, long durationMs) {
        return new SkillExecutionResult(true, output, null, executor, durationMs);
    }

    public static SkillExecutionResult fail(String error, String executor, long durationMs) {
        return new SkillExecutionResult(false, null, error, executor, durationMs);
    }
}
