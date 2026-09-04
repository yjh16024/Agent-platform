package com.agentplatform.plugin.sdk;

/**
 * 插件 SPI 契约（plugin-sdk 中定义）。
 * <p>
 * 平台规定的扩展点接口，所有插件实现此接口。插入智能体时：
 * {@link #onAttach(PluginContext)} 注册 tools/hooks；卸载时：
 * {@link #onDetach(PluginContext)} 反注册并释放资源。
 * </p>
 */
public interface Plugin {

    /**
     * 插件全局唯一 ID（对应 plugin.yaml 的 plugin.id）。
     */
    String id();

    /**
     * 语义化版本。
     */
    String version();

    /**
     * 插入智能体时回调：注册 tools/hooks/resources。
     */
    void onAttach(PluginContext ctx);

    /**
     * 卸载时回调：反注册、释放资源。
     */
    void onDetach(PluginContext ctx);
}