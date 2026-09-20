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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件运行时（工厂 + 服务定位器 + 生命周期）。
 * <p>
 * 插件生命周期：install → enable → attach → disable → uninstall。
 * Attach 时实例化插件（内置或 ClassLoader 加载），调用 {@code Plugin#onAttach} 注册
 * Tools/Hooks；Detach 调用 {@code Plugin#onDetach} 后反注册并卸载 ClassLoader，无需重启 Runtime。
 * </p>
 *
 * <p><b>2026-09-20 改为按「智能体」记账</b>：此前 {@code attached} 是
 * {@code Map<pluginId, Plugin>}，于是「同一插件挂到多个智能体」会被幂等短路掉第二个 ——
 * 数据库写了两条绑定，运行时却只认第一台的上下文，而 detach 又按 pluginId 全局反注册，
 * 卸 A 连带废掉 B。现在键是 {@code agentId|pluginId}：
 * </p>
 * <ul>
 *   <li>同一插件可以分别挂到多个智能体，各自 attach / detach，互不影响；</li>
 *   <li>插件<b>实例</b>仍然复用（内置单例 / 外部插件由 {@link ExternalPluginLoader} 缓存），
 *       但每次 attach 都会带着各自的 {@link PluginContext} 注册一套<b>归属该智能体</b>的贡献；</li>
 *   <li>只有当最后一个使用它的智能体也 detach 后，才真正卸载实例与 ClassLoader。</li>
 * </ul>
 */
@Slf4j
@Component
public class PluginRuntime {

    /** 复合键分隔符：agentId 与 pluginId 都是 {@code [A-Za-z0-9_-]}，不含它。 */
    private static final String SEP = "|";

    /** 内置插件注册表（Spring 注入，pluginId → Plugin 实例）。 */
    private final Map<String, Plugin> builtinPlugins;
    /** 已 attach 的插件实例：key = {@code agentId|pluginId}。 */
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
     * @param agentId  目标智能体 ID
     * @param tenantId 租户 ID
     * @param config   实例级配置（挂载时传入并已落库；重启后由调用方从绑定里回填）
     */
    public void attach(String pluginId, String agentId, String tenantId, Map<String, Object> config) {
        String key = key(agentId, pluginId);
        // 幂等：同一智能体上重复 attach 则跳过（避免 Hook 重复注册）
        if (attached.containsKey(key)) {
            return;
        }
        Plugin plugin = resolve(pluginId, tenantId);
        JsonNode configNode = config == null ? null : mapper.valueToTree(config);
        PluginContext ctx = new PluginContext(agentId, tenantId, configNode, extensions);

        // 在 attach 作用域内执行：插件若在 onAttach 里手动调 registrar，也能正确归属
        extensions.runInAttachScope(agentId, pluginId, () -> {
            plugin.onAttach(ctx);
            extensions.registerPlugin(plugin, ctx);
        });
        attached.put(key, plugin);
        log.info("Attached plugin {} to agent {}", pluginId, agentId);
    }

    /**
     * 卸载「某智能体上的某插件」：反注册它的贡献并释放资源。
     * <p>只影响这一台智能体；仍有其它智能体在用同一插件时，插件实例与 ClassLoader 会保留。</p>
     */
    public void detach(String agentId, String pluginId) {
        String key = key(agentId, pluginId);
        Plugin plugin = attached.remove(key);
        if (plugin == null) {
            return;
        }
        extensions.unregisterAll(agentId, pluginId);
        try {
            plugin.onDetach(null);   // 注意：宿主传的是 null，插件不要在这里使用 ctx
        } catch (Exception e) {
            log.warn("Plugin {} onDetach error: {}", pluginId, e.getMessage());
        }
        // 仅当没有任何智能体再使用它时，才卸载实例（外部插件随之释放 ClassLoader）
        if (!isAttached(pluginId) && externalPluginLoader != null) {
            externalPluginLoader.unload(pluginId);
        }
        log.info("Detached plugin {} from agent {}", pluginId, agentId);
    }

    /**
     * 卸载该插件在<b>所有</b>智能体上的挂载（插件被删除 / 级联卸载时使用）。
     *
     * @return 被卸载的智能体 ID 列表
     */
    public List<String> detachEverywhere(String pluginId) {
        List<String> agents = new ArrayList<>();
        for (String k : attached.keySet()) {
            String aid = agentIdOf(k);
            if (k.equals(key(aid, pluginId))) {
                agents.add(aid);
            }
        }
        for (String aid : agents) {
            detach(aid, pluginId);
        }
        return agents;
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
     * 已发现的内置插件（pluginId → 实例）。
     * <p>供 {@code BuiltinPluginRegistrar} 把「代码里的 @Component 插件」同步进 plugin_def，
     * 使其出现在插件市场并能从界面挂载 —— 内置插件没有 manifest 文件，库里的记录只能这样补。</p>
     */
    public Map<String, Plugin> builtinPlugins() {
        return Map.copyOf(builtinPlugins);
    }

    /**
     * 该插件是否还被任何智能体挂载（用于判断能否卸载实例）。
     */
    public boolean isAttached(String pluginId) {
        for (String k : attached.keySet()) {
            if (k.endsWith(SEP + pluginId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该插件是否已挂载到指定智能体。
     */
    public boolean isAttached(String agentId, String pluginId) {
        return attached.containsKey(key(agentId, pluginId));
    }

    /**
     * 当前挂着该插件的智能体列表。
     */
    public List<String> attachedAgents(String pluginId) {
        List<String> out = new ArrayList<>();
        for (String k : attached.keySet()) {
            if (k.endsWith(SEP + pluginId)) {
                out.add(agentIdOf(k));
            }
        }
        return out;
    }

    private static String key(String agentId, String pluginId) {
        return (agentId == null ? "" : agentId) + SEP + pluginId;
    }

    private static String agentIdOf(String compositeKey) {
        int i = compositeKey.indexOf(SEP);
        return i < 0 ? compositeKey : compositeKey.substring(0, i);
    }
}
