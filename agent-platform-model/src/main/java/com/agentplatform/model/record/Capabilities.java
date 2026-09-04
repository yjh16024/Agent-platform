package com.agentplatform.model.record;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 智能体的能力装配（能力绑定）。
 * <p>包含默认知识库、工具集、Skills、插件（Plugin）、工作流、多模态开关。</p>
 *
 * @param knowledgeBaseIds 默认知识库 ID 列表
 * @param toolsetIds       工具集 ID 列表
 * @param skillIds         Skill ID 列表
 * @param pluginIds        挂载插件 ID 列表（插入插件）
 * @param workflowId       绑定默认工作流 ID
 * @param multimodal       多模态开关
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Capabilities(
        List<String> knowledgeBaseIds,
        List<String> toolsetIds,
        List<String> skillIds,
        List<String> pluginIds,
        String workflowId,
        Boolean multimodal
) {
    public static Capabilities empty() {
        return new Capabilities(List.of(), List.of(), List.of(), List.of(), null, false);
    }
}