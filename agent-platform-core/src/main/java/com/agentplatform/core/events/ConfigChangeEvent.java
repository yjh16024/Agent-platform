package com.agentplatform.core.events;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 配置变更事件体（智能体配置变更时广播）。
 *
 * @param event     事件类型：created / updated / deleted / published / rolled_back
 * @param tenantId  租户 ID
 * @param agentId   智能体 ID
 * @param name      智能体名称
 * @param version   关联版本号（发布/回滚时）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigChangeEvent(
        String event,
        String tenantId,
        String agentId,
        String name,
        String version
) {
    public static ConfigChangeEvent of(String event, String tenantId, String agentId, String name) {
        return new ConfigChangeEvent(event, tenantId, agentId, name, null);
    }
}