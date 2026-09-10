package com.agentplatform.plugin.sdk;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 插件上下文（insert 到 Agent 时的运行时上下文）。
 *
 * @param agentId   所属 Agent ID
 * @param tenantId  租户 ID
 * @param config    实例级配置（如 {voice: "zh-CN-Xiaoxiao"}）
 * @param registrar 扩展注册器（插件通过它贡献 tools/hooks）
 */
public record PluginContext(
        String agentId,
        String tenantId,
        JsonNode config,
        ExtensionRegistrar registrar
) {
    /**
     * 便捷取配置值。
     */
    public String configAsString(String key, String defaultValue) {
        if (config == null || !config.has(key)) {
            return defaultValue;
        }
        return config.get(key).asText(defaultValue);
    }
}