package com.agentplatform.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 统一 Agent 运行接口请求（POST /agent/run）。
 * <p>基于 OpenAI Chat Completions 规范扩展，支持 agent/workflow/skill/plugin 四种模式。</p>
 *
 * @param runId      幂等键（可选）
 * @param mode       运行模式：agent / workflow / skill / plugin
 * @param agentId    智能体 ID
 * @param workflowId 工作流 ID（mode=workflow）
 * @param skillId    Skill ID（mode=skill）
 * @param pluginId   插件 ID（mode=plugin）
 * @param model      模型请求级覆盖
 * @param messages   消息列表（parts 多模态）
 * @param sessionId  会话 ID
 * @param context    上下文控制
 * @param tools      工具控制
 * @param plugins    请求级插件开关
 * @param stream     是否流式
 * @param metadata   元数据（tenant_id/user_id）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunRequest(
        String runId,
        String mode,
        String agentId,
        String workflowId,
        String skillId,
        String pluginId,
        ModelOverride model,
        List<Message> messages,
        String sessionId,
        ContextConfig context,
        ToolsConfig tools,
        PluginsConfig plugins,
        Boolean stream,
        Map<String, Object> metadata
) {
    /** 模型请求级覆盖。 */
    public record ModelOverride(String provider, String name, Double temperature) {
    }

    /** 消息（parts 多模态内容块）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String role, Object content) {
    }

    /** 上下文控制。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextConfig(
            Integer maxHistoryTurns,
            Boolean useRag,
            RagConfig rag
    ) {
    }

    /** RAG 检索配置。 */
    public record RagConfig(List<String> knowledgeBaseIds, Integer topK, Double scoreThreshold) {
    }

    /** 工具控制。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolsConfig(
            Boolean enabled,
            List<String> allowed,
            List<String> skillIds,
            List<String> pluginIds
    ) {
    }

    /** 请求级插件开关（覆盖 Agent 保存值）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PluginsConfig(
            Boolean enabled,
            List<String> active,
            Map<String, Object> config
    ) {
    }

    /** 从 metadata 提取 tenant_id。 */
    public String tenantId() {
        return metadata == null ? "default" : String.valueOf(metadata.getOrDefault("tenant_id", "default"));
    }

    /** 从 metadata 提取 user_id。 */
    public String userId() {
        return metadata == null ? null : (String) metadata.get("user_id");
    }
}