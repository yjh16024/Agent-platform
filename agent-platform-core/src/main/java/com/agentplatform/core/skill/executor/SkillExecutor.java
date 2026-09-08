package com.agentplatform.core.skill.executor;

/**
 * Skill 执行器抽象（命令模式：执行器 = 可插拔的命令实现）。
 * <p>
 * 一个 Skill 不只可以是「提示词」，还可以是脚本、HTTP 服务等；每种形态对应一个
 * {@link SkillExecutor} 实现，由 {@link SkillExecutorRegistry} 按命令的类型或
 * {@link #supports} 判定结果分发。新增形态只需新增实现类（自动被注册表收集），
 * 无需改动调用方——符合开闭原则。
 * </p>
 */
public interface SkillExecutor {

    /** 执行器类型标识（如 prompt / script / http）。 */
    String type();

    /** 简要说明（供前端/接口展示）。 */
    String description();

    /**
     * 是否支持该命令：默认按类型匹配，实现可覆盖以按命令内容判定（如命令以 scripts/ 开头）。
     */
    default boolean supports(SkillCommand command) {
        return command != null && command.type() != null
                && type().equalsIgnoreCase(command.type());
    }

    /**
     * 执行命令（实现须自行保证不抛异常，失败以 {@link SkillExecutionResult#fail} 返回）。
     */
    SkillExecutionResult execute(SkillCommand command);
}
