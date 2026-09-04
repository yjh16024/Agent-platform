package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 插件工具适配器（桥接 plugin-sdk 的 {@link PluginTool} 与核心 {@link Tool}）。
 * <p>插件贡献的工具经此适配器注册进核心 ToolRegistry，可被 LLM function calling 触发。</p>
 */
public class PluginToolAdapter implements Tool {

    private final PluginTool pluginTool;
    private final PluginContext pluginContext;

    public PluginToolAdapter(PluginTool pluginTool, PluginContext pluginContext) {
        this.pluginTool = pluginTool;
        this.pluginContext = pluginContext;
    }

    @Override
    public String name() {
        return pluginTool.name();
    }

    @Override
    public String description() {
        return pluginTool.description();
    }

    @Override
    public JsonNode inputSchema() {
        return pluginTool.inputSchema();
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            JsonNode result = pluginTool.handler().execute(args, pluginContext);
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }
}