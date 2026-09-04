package com.agentplatform.plugin.sdk;

import java.util.List;

/**
 * 工具贡献者 SPI。
 * <p>插件声明工具 → 自动加入 Agent 的工具列表 → LLM 按 function calling 触发。</p>
 */
public interface ToolProvider extends Plugin {

    /**
     * 插件贡献的工具列表（自动注册到 ExtensionRegistry）。
     */
    List<PluginTool> provideTools();
}