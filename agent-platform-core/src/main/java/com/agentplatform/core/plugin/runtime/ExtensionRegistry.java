package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.EventSubscriber;
import com.agentplatform.plugin.sdk.ExtensionRegistrar;
import com.agentplatform.plugin.sdk.Plugin;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ResourceProvider;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.EventTypes;
import com.agentplatform.plugin.sdk.model.HookPoint;
import com.agentplatform.plugin.sdk.model.ResourceDescriptor;
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
 * <b>属于当前智能体</b>的插件工具；资源与事件订阅同理按 agentId 隔离。</p>
 *
 * <p><b>为什么要做这件事（2026-09-20 修）</b>：此前 hooks 是
 * {@code Map<HookPoint, List<AgentHook>>}、工具按工具名直接进全局 {@link ToolRegistry}，
 * 都没有 agent 维度 —— 于是"把插件挂到智能体 A"实际会让<b>所有智能体</b>都生效，
 * 与界面/数据库展示的 per-agent 绑定完全不符。现在钩子、工具、资源、订阅都带上了归属。</p>
 *
 * <p><b>attach 作用域</b>：SDK 的 {@link ExtensionRegistrar} 只传 hook/tool/资源本体，
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

    /**
     * 资源注册记录。
     *
     * <p>{@code provider} 可能为 null —— 插件通过 {@code registrar.registerResources(...)}
     * 手工补充声明时会这样（那种情况下资源可见、但解析不出实体）。</p>
     */
    private record ResourceRegistration(Owner owner, ResourceDescriptor descriptor, ResourceProvider provider) {
    }

    /** 事件订阅记录：订阅者 + 归属。 */
    private record SubscriberRegistration(Owner owner, EventSubscriber subscriber) {
    }

    private final Map<HookPoint, List<HookRegistration>> hooks = new EnumMap<>(HookPoint.class);
    /** 复合键 → 已注册进 ToolRegistry 的适配器。 */
    private final Map<String, List<Tool>> pluginTools = new LinkedHashMap<>();
    /** 复合键 → 贡献的工具名（用于按 agent 过滤）。 */
    private final Map<String, List<String>> pluginToolNames = new LinkedHashMap<>();
    /**
     * resourceId → 各归属下的注册记录。
     *
     * <p>刻意<b>不</b>用 resourceId 当唯一键：同一个插件挂到智能体 A 与 B 时 ID 相同，
     * 全局唯一键会让后挂载的覆盖先挂载的、并让 A 解析到 B 的资源实例。
     * 与 hooks 的 {@code Map<点, List<记录>>} 结构保持一致，解析时再按 agentId 挑。</p>
     */
    private final Map<String, List<ResourceRegistration>> resources = new LinkedHashMap<>();
    /**
     * 事件类型 → 各归属下的订阅记录。
     *
     * <p>同样以「事件类型」为键而不是以插件为键 —— 派发时要按类型快速找到订阅者。</p>
     */
    private final Map<String, List<SubscriberRegistration>> subscribers = new LinkedHashMap<>();
    private final ToolRegistry toolRegistry;
    /** attach 作用域（attach 是同步调用，ThreadLocal 安全）。 */
    private final ThreadLocal<Owner> attachScope = new ThreadLocal<>();
    /**
     * 当前正在注册的插件实例。
     *
     * <p>只为 {@link #registerResources(List)} 这类"插件主动补充声明"的入口服务：
     * 那种调用只拿到了描述符、没有 provider，需要从这里回捞插件本体才能建立解析链路。</p>
     */
    private final ThreadLocal<Plugin> currentPlugin = new ThreadLocal<>();

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
            currentPlugin.set(plugin);
            try {
                if (plugin instanceof AgentHook hook) {
                    registerHook(hook.point(), hook);
                }
                if (plugin instanceof ToolProvider provider) {
                    registerTools(agentId, plugin.id(), provider.provideTools(), ctx);
                }
                if (plugin instanceof ResourceProvider rp) {
                    registerResources(rp);
                }
                if (plugin instanceof EventSubscriber subscriber) {
                    registerSubscriber(subscriber);
                }
            } finally {
                currentPlugin.remove();
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

    /**
     * 注册一个 {@link ResourceProvider} 声明的全部资源（宿主内部入口）。
     *
     * <p>声明失败只记日志、不影响插件挂载 —— 资源是"锦上添花"的能力，
     * 不该因为某个插件声明写错就让整个 attach 失败。</p>
     */
    public void registerResources(ResourceProvider provider) {
        Owner owner = requireScope("resources of " + provider.id());
        List<ResourceDescriptor> list;
        try {
            list = provider.provideResources();
        } catch (Exception e) {
            log.error("插件 {} 声明资源失败，跳过其资源贡献：{}", provider.id(), e.getMessage(), e);
            return;
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (ResourceDescriptor d : list) {
            if (d == null) {
                continue;
            }
            resources.computeIfAbsent(d.resourceId(), k -> new ArrayList<>())
                    .add(new ResourceRegistration(owner, d, provider));
            ids.add(d.resourceId());
        }
        if (!ids.isEmpty()) {
            log.info("Registered {} resource(s) {} for agent {}", ids.size(), ids, owner.agentId());
        }
    }

    /**
     * 插件主动补充声明资源（SDK 入口）。
     *
     * <p>正常情况下插件只需实现 {@link ResourceProvider}，宿主会在
     * {@link #registerPlugin} 时自动登记，<b>不必手写这个调用</b>。
     * 保留它是为了支持"资源在 attach 之后才确定"的少数场景。</p>
     *
     * <p>注意：经此入口声明的资源<b>只能被列出、无法被解析</b>（没有 provider 实例），
     * 所以它更适合"登记能力供界面展示"，而不是"给别的插件用"。</p>
     */
    @Override
    public void registerResources(List<ResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        Owner owner = requireScope("resources");
        Plugin self = currentPlugin.get();
        ResourceProvider provider = self instanceof ResourceProvider rp ? rp : null;
        if (provider == null) {
            log.warn("插件 {} 用 registerResources 声明了资源但自身未实现 ResourceProvider，"
                    + "这些资源将只能被列出、无法被解析", owner.pluginId());
        }
        for (ResourceDescriptor d : resources) {
            if (d == null) {
                continue;
            }
            this.resources.computeIfAbsent(d.resourceId(), k -> new ArrayList<>())
                    .add(new ResourceRegistration(owner, d, provider));
        }
    }

    /**
     * 注册一个事件订阅者（宿主内部入口，由 {@link #registerPlugin} 按 instanceof 自动调用）。
     *
     * <p>只接受 {@link EventTypes#PLUGIN_SUBSCRIBABLE} 里的事件类型：租户级事件
     * （配额、权限）没有 agent 维度，派发给插件会打破"插件按智能体隔离"的模型。
     * 声明了不可订阅的类型<b>不报错</b>（否则插件作者会以为整个插件都挂了），
     * 但如实记一条 warn 并跳过 —— 与 {@code ResourceProvider} 之前"如实记录但不假装注册成功"
     * 的处理保持一致。</p>
     */
    public void registerSubscriber(EventSubscriber subscriber) {
        Owner owner = requireScope("event subscriber " + subscriber.id());
        Set<String> types;
        try {
            types = subscriber.eventTypes();
        } catch (Exception e) {
            log.error("插件 {} 声明订阅事件失败，跳过其事件订阅：{}", subscriber.id(), e.getMessage(), e);
            return;
        }
        if (types == null || types.isEmpty()) {
            log.info("插件 {} 实现了 EventSubscriber 但未声明任何事件类型，跳过", subscriber.id());
            return;
        }
        List<String> accepted = new ArrayList<>();
        for (String type : types) {
            if (!EventTypes.isPluginSubscribable(type)) {
                log.warn("插件 {} 声明订阅事件 {}，但它不是可被插件订阅的类型（租户级事件没有 agent 维度，"
                        + "派发会打破按智能体隔离），已跳过。可用类型见 EventTypes.PLUGIN_SUBSCRIBABLE",
                        subscriber.id(), type);
                continue;
            }
            subscribers.computeIfAbsent(type, k -> new ArrayList<>())
                    .add(new SubscriberRegistration(owner, subscriber));
            accepted.add(type);
        }
        if (!accepted.isEmpty()) {
            log.info("Registered event subscriber {} for {} on agent {}",
                    subscriber.id(), accepted, owner.agentId());
        }
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
        // 资源同样要撤：否则插件已卸载、它声明的凭据却还能被别人解析出来
        resources.values().forEach(list -> list.removeIf(r ->
                r.owner().agentId().equals(aid) && r.owner().pluginId().equals(pluginId)));
        resources.entrySet().removeIf(e -> e.getValue().isEmpty());
        // 事件订阅也必须撤：否则卸载后的插件仍会被派发事件（它的对象虽在，但已"下线"）
        subscribers.values().forEach(list -> list.removeIf(r ->
                r.owner().agentId().equals(aid) && r.owner().pluginId().equals(pluginId)));
        subscribers.entrySet().removeIf(e -> e.getValue().isEmpty());
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

    /**
     * 获取某智能体上订阅了某事件类型的全部订阅者。
     *
     * <p><b>必须同时传事件类型与 agentId</b>：前者用来快速定位，后者用来做智能体隔离
     * （挂到 A 的插件不该收到 B 的事件）。</p>
     */
    public List<EventSubscriber> subscribersOf(String eventType, String agentId) {
        if (eventType == null) {
            return List.of();
        }
        String aid = agentId == null ? "" : agentId;
        return subscribers.getOrDefault(eventType, List.of()).stream()
                .filter(r -> r.owner().agentId().equals(aid))
                .map(SubscriberRegistration::subscriber)
                .toList();
    }

    /** 某智能体上登记的全部订阅（诊断/界面展示用，不关心具体事件类型）。 */
    public List<String> subscriberNamesOf(String agentId) {
        String aid = agentId == null ? "" : agentId;
        Set<String> out = new LinkedHashSet<>();
        subscribers.forEach((type, list) -> list.stream()
                .filter(r -> r.owner().agentId().equals(aid))
                .forEach(r -> out.add(r.subscriber().id() + " -> " + type)));
        return List.copyOf(out);
    }

    /**
     * 按 ID 解析资源（SDK 入口）。
     *
     * <p>归属取自当前 attach 作用域 —— 插件无法伪造成别的智能体去取资源。</p>
     */
    @Override
    public Object resolveResource(String resourceId) {
        Owner owner = attachScope.get();
        if (owner == null) {
            log.warn("插件在 attach 作用域外调用了 resolveResource({})，已忽略", resourceId);
            return null;
        }
        return resolveResource(owner.agentId(), resourceId);
    }

    /**
     * 按 ID 解析资源（宿主内部入口）。
     *
     * <p><b>只认同一智能体上的资源</b>：跨智能体不可见，否则挂到 A 的插件足以把
     * 自己的凭据泄露给 B。同名资源有多个提供者时按注册顺序取第一个能给出非空值的，
     * 单个提供者出错不影响其它候选。</p>
     *
     * @return 资源对象；找不到、或提供者都解析失败时返回 null
     */
    public Object resolveResource(String agentId, String resourceId) {
        List<ResourceRegistration> list = resources.get(resourceId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        String aid = agentId == null ? "" : agentId;
        for (ResourceRegistration r : list) {
            if (!r.owner().agentId().equals(aid) || r.provider() == null) {
                continue;
            }
            try {
                Object value = r.provider().provide(resourceId);
                if (value != null) {
                    return value;
                }
            } catch (Exception e) {
                log.warn("资源 {} 的提供者 {} 解析失败：{}", resourceId, r.owner().pluginId(), e.getMessage());
            }
        }
        return null;
    }

    /** 某智能体可见的资源描述（供诊断与界面展示；不触发资源初始化）。 */
    public List<ResourceDescriptor> resourcesOf(String agentId) {
        String aid = agentId == null ? "" : agentId;
        List<ResourceDescriptor> out = new ArrayList<>();
        resources.values().forEach(list -> list.stream()
                .filter(r -> r.owner().agentId().equals(aid))
                .forEach(r -> out.add(r.descriptor())));
        return out;
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

    /** 该插件当前是否被任何智能体挂载（钩子 / 工具 / 资源 / 订阅任一有贡献即算）。 */
    public boolean isRegistered(String agentId, String pluginId) {
        String aid = agentId == null ? "" : agentId;
        boolean hasHook = hooks.values().stream()
                .anyMatch(list -> list.stream().anyMatch(r ->
                        r.owner().agentId().equals(aid) && r.owner().pluginId().equals(pluginId)));
        boolean hasResource = resources.values().stream()
                .anyMatch(list -> list.stream().anyMatch(r ->
                        r.owner().agentId().equals(aid) && r.owner().pluginId().equals(pluginId)));
        boolean hasSubscriber = subscribers.values().stream()
                .anyMatch(list -> list.stream().anyMatch(r ->
                        r.owner().agentId().equals(aid) && r.owner().pluginId().equals(pluginId)));
        return hasHook || hasResource || hasSubscriber || pluginToolNames.containsKey(key(aid, pluginId));
    }

    private static String key(String agentId, String pluginId) {
        return (agentId == null ? "" : agentId) + SEP + pluginId;
    }

    private static String agentIdOf(String compositeKey) {
        int i = compositeKey.indexOf(SEP);
        return i < 0 ? compositeKey : compositeKey.substring(0, i);
    }
}
