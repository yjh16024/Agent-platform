package com.agentplatform.core.skill.executor;

import java.util.Map;

/**
 * Skill 命令（命令模式的「命令对象」）。
 * <p>
 * 把一次技能执行所需的一切上下文封装成对象：目标 Skill、期望的执行器类型、
 * 命令（脚本路径 / 端点 / 提示词变量）与入参。执行器只依赖本对象，
 * 与 HTTP 层、持久化解耦，便于测试与扩展。
 * </p>
 *
 * @param tenantId  租户
 * @param skillId   Skill ID
 * @param skillName Skill 名称（日志用）
 * @param dir       Skill 目录名（标准目录布局，可空）
 * @param type      期望的执行器类型（prompt / script / http，空则由注册表推断）
 * @param command   命令（脚本相对路径 / HTTP 端点 / 提示词变量名等）
 * @param args      入参
 * @param prompt    Skill 提示词正文（SKILL.md body，prompt 执行器使用）
 */
public record SkillCommand(
        String tenantId,
        String skillId,
        String skillName,
        String dir,
        String type,
        String command,
        Map<String, Object> args,
        String prompt
) {
    public static SkillCommand of(String tenantId, String skillId, String skillName, String dir,
                                 String type, String command, Map<String, Object> args, String prompt) {
        return new SkillCommand(tenantId, skillId, skillName, dir, type, command,
                args == null ? Map.of() : args, prompt);
    }
}
