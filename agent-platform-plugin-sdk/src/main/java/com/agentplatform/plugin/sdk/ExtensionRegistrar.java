package com.agentplatform.plugin.sdk;

import com.agentplatform.plugin.sdk.model.HookPoint;
import com.agentplatform.plugin.sdk.model.ResourceDescriptor;

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
     * 声明本插件提供的资源（归属自动取当前 attach 作用域，插件无需也无法指定）。
     *
     * <p>通常在 {@code ResourceProvider} 实现里由宿主自动调用；手写插件也可直接调。</p>
     */
    void registerResources(List<ResourceDescriptor> resources);

    /**
     * 按 ID 解析资源 —— 拿到<b>同一智能体</b>上某个插件提供的资源对象。
     *
     * <p>跨智能体不可见（挂到 A 的插件解析不到 B 的资源），找不到或提供方返回 null 时返回 null。</p>
     *
     * @param resourceId 资源 ID（如 {@code my-vault:openai}）
     */
    Object resolveResource(String resourceId);

    /**
     * 反注册本插件全部贡献（Detach 时调用）。
     */
    void unregisterAll(String pluginId);
}