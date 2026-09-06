package com.agentplatform.core.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Skill 包规范（Manifest）。
 * <p>
 * Skill = 一组可被 Agent 引用的能力单元（提示词 + 工具集 + 工作流 + 资源），
 * 导入后自动注册工具与提示词模板。
 * </p>
 * <p>
 * <b>兼容 Agent Skills 开放标准（SKILL.md）</b>：标准 Skill 是一个目录，内含
 * {@code SKILL.md}（YAML frontmatter 描述 name/description/version/license/allowed-tools，
 * 正文即提示词）以及可选的 {@code scripts/}、{@code references/}、{@code assets/} 附带资源。
 * 本 record 因此额外承载 {@code dir}、{@code license}、{@code allowedTools}、{@code metadata}。
 * </p>
 *
 * @param name         名称
 * @param version      语义化版本
 * @param description  描述
 * @param prompt       提示词模板（可含 {{var}} 占位符）
 * @param tools        工具集（工具名列表，导入时注册到 ToolRegistry）
 * @param workflow     绑定的工作流（可选）
 * @param params       参数 Schema（可选）
 * @param dir          Skill 目录名（skills 根目录下的子目录，标准存储布局）
 * @param license      许可声明（标准字段，可选）
 * @param allowedTools 标准字段 allowed-tools（工具白名单）
 * @param metadata     标准字段 metadata（作者/版本等自定义键值）
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
        Map<String, Object> params,
        String dir,
        String license,
        List<String> allowedTools,
        Map<String, Object> metadata
) {
    /**
     * 兼容旧构造（无标准扩展字段）。
     */
    public SkillManifest(String name, String version, String description, String prompt,
                         List<String> tools, Map<String, Object> workflow, Map<String, Object> params) {
        this(name, version, description, prompt, tools, workflow, params, null, null, null, null);
    }

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

    /** 标准工具白名单：优先 allowed-tools，回退 tools。 */
    public List<String> effectiveTools() {
        if (allowedTools != null && !allowedTools.isEmpty()) {
            return allowedTools;
        }
        return tools;
    }
}
