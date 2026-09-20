package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.ExtensionRegistrar;
import com.agentplatform.plugin.sdk.Plugin;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ResourceProvider;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.HookPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扩展注册表（服务定位器 + 观察者）—— <b>按「智能体」隔离</b>。
 *
 * <p>每次能力贡献都记下归属 {@code (agentId, pluginId)}：钩子按 agentId 过滤后交给
 * {@link AgentPipeline}，插件工具按 agentId 记账以便 {@code resolveToolSpecs} 只暴露
 * <b>属于当前智能体</b>的插件工具。</p>
 *
 * <p><b>为什么要做这件事（2026-09-20 修）</b>：此前 hooks 是
 * {@code Map<HookPoint, List<AgentHook>>}、工具按工具名直接进全局 {@link ToolRegistry}，
 * 都没有 agent 维度 —— 于是"把插件挂到智能体 A"实际会让<b>所有智能体</b>都生效，
 * 与界面/数据库展示的 per-agent 绑定完全不符。现在钩子与工具都带上了归属。</p>
 *
 * <p><b>attach 作用域</b>：SDK 的 {@link ExtensionRegistrar} 只传 hook/tool 本体，
 * 不带 agentId（插件也不该自己决定挂到哪个智能体）。所以宿主在 attach 期间用
 * {@link #runInAttachScope} 开一个作用域，注册动作从作用域里取归属；
 * 在作用域外注册会被拒绝（宁可报错，也不要悄悄退化成全局注册）。</p>
 *
 * <p><b>已知边界</b>：插件工具最终仍落在全局 {@link ToolRegistry} 里（改名注册不在本次范围），
 * 因此<b>不同智能体的插件若声明了同名工具，后者会覆盖前者</b>。工具名请加插件前缀规避。</p>
 */
@Slf4j
@Component
public class ExtensionRegistry implements ExtensionRegistrar {

    /** 复合键分隔符：agentId 与 pluginId 都是 {@code [A-Za-z0-9_-]}，不含它。 */
    private static final String SEP = "|";

    /** 一次能力贡献的归属。 */
    public record Owner(String agentId, String pluginId) {
    }

    /** 钩子注册记录：钩子本体 + 归属。 */
    private record HookRegistration(Owner owner, AgentHook hook) {
    }

    private final Map<HookPoint, List<HookRegistration>> hooks = new EnumMap<>(HookPoint.class);
    /** 复合键 → 已注册进 ToolRegistry 的适配器。 */
    private final Map<String, List<Tool>> pluginTools = new LinkedHashMap<>();
    /** 复合键 → 贡献的工具名（用于按 agent 过滤）。 */
    private final Map<String, List<String>> pluginToolNames = new LinkedHashMap<>();
    private final ToolRegistry toolRegistry;
    /** attach 作用域（attach 是同步调用，ThreadLocal 安全）。 */
    private final ThreadLocal<Owner> attachScope = new ThreadLocal<>();

    public ExtensionRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    // ---------------- attach 作用域 ----------------

    /**
     * 在「某智能体上的某插件」作用域内执行注册动作。
     * <p>嵌套调用会覆盖并在退出时恢复外层作用域，所以可安全地在
     * {@code PluginRuntime.attach} 与 {@link #registerPlugin} 里各包一层。</p>
     */
    public void runInAttachScope(String agentId, String pluginId, Runnable action) {
        Owner previous = attachScope.get();
        attachScope.set(new Owner(agentId == null ? "" : agentId, pluginId));
        try {
            action.run();
        } finally {
            if (previous == null) {
                attachScope.remove();
            } else {
                attachScope.set(previous);
            }
        }
    }

    private Owner requireScope(String what) {
        Owner owner = attachScope.get();
        if (owner == null) {
            throw new IllegalStateException(
                    "插件贡献（" + what + "）必须在 attach 作用域内注册。"
                            + "请通过宿主「挂载插件」入口调用，而不要在 onAttach 之外直接注册 —— "
                            + "否则贡献无法归属到某个智能体，会污染其它智能体。");
        }
        return owner;
    }

    // ---------------- 注册 ----------------

    /**
     * 注册插件（识别其实现的能力贡献接口）。
     * <p>归属取自 {@link PluginContext#agentId()}，无需调用方额外传参。</p>
     */
    public void registerPlugin(Plugin plugin, PluginContext ctx) {
        String agentId = ctx == null || ctx.agentId() == null ? "" : ctx.agentId();
        runInAttachScope(agentId, plugin.id(), () -> {
            if (plugin instanceof AgentHook hook) {
                registerHook(hook.point(), hook);
            }
            if (plugin instanceof ToolProvider provider) {
                registerTools(agentId, plugin.id(), provider.provideTools(), ctx);
            }
            if (plugin instanceof ResourceProvider rp) {
                // 资源托管尚未实现：宿主不消费它，这里如实记录但不假装注册成功
                log.warn("插件 {} 声明了 ResourceProvider({})，但宿主尚未实现资源托管，该贡献会被忽略",
                        plugin.id(), rp.resourceType());
            }
        });
    }

    /** 注册单个 AgentHook（归属取当前 attach 作用域）。 */
    public void registerHook(AgentHook hook) {
        registerHook(hook.point(), hook);
    }

    @Override
    public void registerHook(HookPoint point, AgentHook hook) {
        Owner owner = requireScope("hook " + hook.id());
        registerHook(owner, point, hook);
    }

    /** 带归属的钩子注册（宿主内部用）。 */
    public void registerHook(Owner owner, HookPoint point, AgentHook hook) {
        hooks.computeIfAbsent(point, k -> new ArrayList<>()).add(new HookRegistration(owner, hook));
        log.info("Registered hook {} at point {} for agent {}", hook.id(), point, owner.agentId());
    }

    @Override
    public void registerTools(List<PluginTool> tools) {
        Owner owner = requireScope("tools");
        registerTools(owner.agentId(), owner.pluginId(), tools, null);
    }

    /**
     * 注册插件工具列表（带归属）。
     *
     * @param ctx attach 时的插件上下文（工具适配器持有它；无上下文时传 null）
     */
    public void registerTools(String agentId, String pluginId, List<PluginTool> tools, PluginContext ctx) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        String key = key(agentId, pluginId);
        List<Tool> adapters = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (PluginTool pt : tools) {
            PluginToolAdapter adapter = new PluginToolAdapter(pt, ctx);
            toolRegistry.register(adapter);
            adapters.add(adapter);
            names.add(pt.name());
        }
        pluginTools.put(key, adapters);
        pluginToolNames.put(key, names);
        log.info("Registered {} plugin tool(s) {} for agent {}", names.size(), names, agentId);
    }

    @Override
    public void unregisterAll(String pluginId) {
        // SDK 受限视图：插件不知道自己在哪个智能体上，只能从 attach 作用域取
        Owner owner = attachScope.get();
        if (owner == null) {
            log.warn("插件在 attach 作用域外调用了 unregisterAll({})，已忽略；卸载请走宿主接口", pluginId);
            return;
        }
        unregisterAll(owner.agentId(), pluginId);
    }

    /** 反注册「某智能体上某插件」的全部贡献（宿主内部用）。 */
    public void unregisterAll(String agentId, String pluginId) {
        String aid = agentId == null ? "" : agentId;
        hooks.forEach((point, list) -> list.removeIf(r ->
                r.owner().agentId().equals(aid) && r.owner().pluginId().equals(pluginId)));
        String key = key(aid, pluginId);
        List<Tool> removed = pluginTools.remove(key);
        if (removed != null) {
            removed.forEach(t -> toolRegistry.unregister(t.name()));
        }
        pluginToolNames.remove(key);
        log.info("Unregistered contributions of plugin {} on agent {}", pluginId, aid);
    }

    // ---------------- 查询 ----------------

    /**
     * 获取某智能体在某钩子点上的全部 Hook。
     * <p><b>必须传 agentId</b>：不传就无法区分是哪台智能体的插件，也就退回了全局生效的老问题。</p>
     */
    public List<AgentHook> hooksAt(HookPoint point, String agentId) {
        String aid = agentId == null ? "" : agentId;
        return hooks.getOrDefault(point, List.of()).stream()
                .filter(r -> r.owner().agentId().equals(aid))
                .map(HookRegistration::hook)
                .toList();
    }

    /** 某智能体可见的插件工具名。 */
    public Set<String> pluginToolNamesOf(String agentId) {
        String aid = agentId == null ? "" : agentId;
        Set<String> out = new LinkedHashSet<>();
        pluginToolNames.forEach((k, names) -> {
            if (agentIdOf(k).equals(aid)) {
                out.addAll(names);
            }
        });
        return out;
    }

    /**
     * <b>不属于</b>该智能体的插件工具名。
     * <p>工具注册中心是全局的，所以解析某智能体可用工具时要先把这些名字剔掉，
     * 否则会把它人智能体的插件工具也暴露给模型。</p>
     */
    public Set<String> pluginToolNamesExcept(String agentId) {
        String aid = agentId == null ? "" : agentId;
        Set<String> out = new LinkedHashSet<>();
        pluginToolNames.forEach((k, names) -> {
            if (!agentIdOf(k).equals(aid)) {
                out.addAll(names);
            }
        });
        return out;
    }

    /** 某智能体上某插件贡献的工具名。 */
    public List<String> toolNamesOf(String agentId, String pluginId) {
        return pluginToolNames.getOrDefault(key(agentId, pluginId), List.of());
    }

    /** 该插件当前是否被任何智能体挂载。 */
    public boolean isRegistered(String agentId, String pluginId) {
        String aid = agentId == null ? "" : agentId;
        boolean hasHook = hooks.values().stream()
                .anyMatch(list -> list.stream().anyMatch(r ->
                        r.owner().agentId().equals(aid) && r.owner().pluginId().equals(pluginId)));
        return hasHook || pluginToolNames.containsKey(key(aid, pluginId));
    }

    private static String key(String agentId, String pluginId) {
        return (agentId == null ? "" : agentId) + SEP + pluginId;
    }

    private static String agentIdOf(String compositeKey) {
        int i = compositeKey.indexOf(SEP);
        return i < 0 ? compositeKey : compositeKey.substring(0, i);
    }
}
