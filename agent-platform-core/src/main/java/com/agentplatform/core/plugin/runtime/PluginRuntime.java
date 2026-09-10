package com.agentplatform.core.plugin.runtime;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.plugin.classloader.ExternalPluginLoader;
import com.agentplatform.plugin.sdk.Plugin;
import com.agentplatform.plugin.sdk.PluginContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件运行时（工厂 + 服务定位器 + 生命周期）。
 * <p>
 * 插件生命周期：install → enable → attach → disable → uninstall。
 * Attach 时实例化插件（内置或 ClassLoader 加载），调用 {@code Plugin#onAttach} 注册
 * Tools/Hooks；Detach 调用 {@code onDetach} 后反注册并卸载 ClassLoader，无需重启 Runtime。
 * </p>
 */
@Slf4j
@Component
public class PluginRuntime {

    /** 内置插件注册表（Spring 注入，pluginId → Plugin 实例）。 */
    private final Map<String, Plugin> builtinPlugins;
    /** 已 attach 的插件实例（pluginId → Plugin）。 */
    private final Map<String, Plugin> attached = new ConcurrentHashMap<>();
    private final ExtensionRegistry extensions;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 可选：外部 jar 插件加载器（未注入时只支持内置插件）。 */
    private ExternalPluginLoader externalPluginLoader;

    @Autowired(required = false)
    public void setExternalPluginLoader(ExternalPluginLoader externalPluginLoader) {
        this.externalPluginLoader = externalPluginLoader;
    }

    public PluginRuntime(ExtensionRegistry extensions, java.util.List<Plugin> plugins) {
        this.extensions = extensions;
        this.builtinPlugins = new ConcurrentHashMap<>();
        for (Plugin p : plugins) {
            builtinPlugins.put(p.id(), p);
            log.info("Discovered builtin plugin: {}", p.id());
        }
    }

    /**
     * 插入智能体：加载/复用插件实例并注册其贡献能力。
     *
     * @param pluginId 插件 ID
     * @param agentId  智能体 ID
     * @param tenantId 租户 ID
     * @param config   实例级配置
     */
    public void attach(String pluginId, String agentId, String tenantId, Map<String, Object> config) {
        // 幂等：已 attach 则跳过（避免 Hook 重复注册）
        if (attached.containsKey(pluginId)) {
            return;
        }
        Plugin plugin = resolve(pluginId, tenantId);
        JsonNode configNode = config == null ? null : mapper.valueToTree(config);
        PluginContext ctx = new PluginContext(agentId, tenantId, configNode, extensions);

        plugin.onAttach(ctx);
        extensions.registerPlugin(plugin, ctx);
        attached.put(pluginId, plugin);
        log.info("Attached plugin {} to agent {}", pluginId, agentId);
    }

    /**
     * 卸载插件：反注册 + 释放资源。
     */
    public void detach(String pluginId) {
        Plugin plugin = attached.remove(pluginId);
        if (plugin == null) {
            return;
        }
        extensions.unregisterAll(pluginId);
        try {
            plugin.onDetach(null);
        } catch (Exception e) {
            log.warn("Plugin {} onDetach error: {}", pluginId, e.getMessage());
        }
        if (externalPluginLoader != null) {
            externalPluginLoader.unload(pluginId);   // 外部插件实例随之卸载，下次 attach 重新加载
        }
        log.info("Detached plugin {}", pluginId);
    }

    /**
     * 解析插件实例：内置优先，其次尝试外部 jar 加载（见 {@link ExternalPluginLoader}）。
     */
    private Plugin resolve(String pluginId, String tenantId) {
        Plugin builtin = builtinPlugins.get(pluginId);
        if (builtin != null) {
            return builtin;
        }
        if (externalPluginLoader != null) {
            return externalPluginLoader.load(pluginId, tenantId);
        }
        throw BizException.notFound("plugin", pluginId);
    }

    /**
     * 是否有内置插件。
     */
    public boolean isBuiltin(String pluginId) {
        return builtinPlugins.containsKey(pluginId);
    }

    /**
     * 是否已 attach。
     */
    public boolean isAttached(String pluginId) {
        return attached.containsKey(pluginId);
    }
}