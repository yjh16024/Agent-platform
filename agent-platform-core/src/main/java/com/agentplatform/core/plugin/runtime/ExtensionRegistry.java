package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.ExtensionRegistrar;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.model.HookPoint;
import com.agentplatform.plugin.sdk.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扩展注册表（服务定位器 + 观察者）。
 * <p>
 * 核心调度层从此获取插件贡献的能力：Hook（按 HookPoint 组织）+ 插件工具
 * （同步注册进 ToolRegistry）。插件通过 {@link ExtensionRegistrar} 受限视图注册。
 * </p>
 */
@Slf4j
@Component
public class ExtensionRegistry implements ExtensionRegistrar {

    private final Map<HookPoint, List<AgentHook>> hooks = new EnumMap<>(HookPoint.class);
    private final Map<String, List<Tool>> pluginTools = new LinkedHashMap<>(); // pluginId -> tools
    private final ToolRegistry toolRegistry;

    public ExtensionRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 注册插件（识别其实现的能力贡献接口）。
     */
    public void registerPlugin(com.agentplatform.plugin.sdk.Plugin plugin, PluginContext ctx) {
        if (plugin instanceof AgentHook hook) {
            registerHook(hook);
        }
        if (plugin instanceof ToolProvider provider) {
            registerTools(plugin.id(), provider.provideTools(), ctx);
        }
        if (plugin instanceof com.agentplatform.plugin.sdk.ResourceProvider rp) {
            log.info("Plugin {} contributed resource: {}", plugin.id(), rp.resourceType());
        }
    }

    // ---- ExtensionRegistrar 接口（插件受限回调）----

    @Override
    public void registerHook(HookPoint point, AgentHook hook) {
        hooks.computeIfAbsent(point, k -> new ArrayList<>()).add(hook);
        log.info("Registered hook {} at point {}", hook.id(), point);
    }

    @Override
    public void registerTools(List<PluginTool> tools) {
        // 无插件上下文时的兜底注册（不推荐直接调用）
        registerTools("anonymous", tools, null);
    }

    @Override
    public void unregisterAll(String pluginId) {
        // 移除 hooks
        hooks.forEach((point, list) -> list.removeIf(h -> pluginId.equals(h.id())));
        // 移除插件工具
        List<Tool> removed = pluginTools.remove(pluginId);
        if (removed != null) {
            removed.forEach(t -> toolRegistry.unregister(t.name()));
        }
        log.info("Unregistered all contributions of plugin {}", pluginId);
    }

    /**
     * 注册单个 AgentHook（插件实现 AgentHook 时）。
     */
    public void registerHook(AgentHook hook) {
        registerHook(hook.point(), hook);
    }

    /**
     * 注册插件工具列表。
     */
    public void registerTools(String pluginId, List<PluginTool> tools, PluginContext ctx) {
        if (tools == null) {
            return;
        }
        List<Tool> adapters = new ArrayList<>();
        for (PluginTool pt : tools) {
            PluginToolAdapter adapter = new PluginToolAdapter(pt, ctx);
            toolRegistry.register(adapter);
            adapters.add(adapter);
        }
        pluginTools.put(pluginId, adapters);
    }

    /**
     * 获取某钩子点的全部 Hook。
     */
    public List<AgentHook> hooksAt(HookPoint point) {
        return hooks.getOrDefault(point, List.of());
    }

    /**
     * 某插件贡献的工具名列表（影响分析/展示）。
     */
    public List<String> toolNamesOf(String pluginId) {
        return pluginTools.getOrDefault(pluginId, List.of()).stream().map(Tool::name).toList();
    }
}