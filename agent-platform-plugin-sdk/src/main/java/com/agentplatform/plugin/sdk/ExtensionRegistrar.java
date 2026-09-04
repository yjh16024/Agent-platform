package com.agentplatform.plugin.sdk;

import com.agentplatform.plugin.sdk.model.HookPoint;

import java.util.List;

/**
 * 扩展注册器（核心调度层提供给插件的回调，插件借此贡献能力）。
 * <p>
 * 是 {@code ExtensionRegistry} 面向插件的受限视图：插件只能注册/反注册
 * 自己的能力，不能访问其他插件与核心内部状态。
 * </p>
 */
public interface ExtensionRegistrar {

    /**
     * 注册一个运行钩子。
     */
    void registerHook(HookPoint point, AgentHook hook);

    /**
     * 注册工具列表。
     */
    void registerTools(List<PluginTool> tools);

    /**
     * 反注册本插件全部贡献（Detach 时调用）。
     */
    void unregisterAll(String pluginId);
}