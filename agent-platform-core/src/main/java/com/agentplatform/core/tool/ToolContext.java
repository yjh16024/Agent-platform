package com.agentplatform.core.tool;

/**
 * 工具执行上下文。
 *
 * @param tenantId 租户 ID
 * @param agentId  智能体 ID
 * @param runId    运行 ID
 */
public record ToolContext(String tenantId, String agentId, String runId) {

    public static ToolContext of(String tenantId, String agentId, String runId) {
        return new ToolContext(tenantId, agentId, runId);
    }
}