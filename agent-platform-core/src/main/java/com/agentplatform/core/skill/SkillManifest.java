package com.agentplatform.core.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Skill 包规范（Manifest）。
 * <p>Skill = 一组可被 Agent 引用的能力单元（提示词 + 工具集 + 工作流 + 资源），
 * 导入后自动注册工具与提示词模板。</p>
 *
 * @param name        名称
 * @param version     语义化版本
 * @param description 描述
 * @param prompt      提示词模板（可含 {{var}} 占位符）
 * @param tools       工具集（工具名列表，导入时注册到 ToolRegistry）
 * @param workflow    绑定的工作流（可选）
 * @param params      参数 Schema（可选）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillManifest(
        String name,
        String version,
        String description,
        String prompt,
        List<String> tools,
        Map<String, Object> workflow,
        Map<String, Object> params
) {
    /**
     * 校验必填字段。
     */
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill.name is required");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("skill.version is required");
        }
    }
}