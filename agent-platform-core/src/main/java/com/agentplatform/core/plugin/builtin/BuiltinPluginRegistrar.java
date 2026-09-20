package com.agentplatform.core.plugin.builtin;

import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.model.entity.AgentPlugin;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.repository.AgentPluginRepository;
import com.agentplatform.model.repository.PluginRepository;
import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.Plugin;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置插件注册器：把「代码里的 {@code @Component} 插件」同步进插件市场。
 *
 * <p><b>解决什么问题</b>：{@link PluginRuntime} 能发现所有实现 {@code Plugin} 的 Spring Bean，
 * 但那是<b>运行时</b>视角；而插件市场页与「挂载到智能体」入口读的是 {@code plugin_def} 表。
 * 内置插件没有 manifest 文件、也没有上传动作，于是它在库中不存在 —— 表现为
 * <b>代码里明明有插件，界面却看不到、也挂不上</b>。本类补上这一环。</p>
 *
 * <p><b>双向同步（幂等，每次启动执行）</b>：</p>
 * <ol>
 *   <li><b>多退</b>：代码里有、库里没有 → 新建（{@code tenant_id = __platform__}，无制品）；</li>
 *   <li><b>少补</b>：两边都有 → 用最新代码里的元信息覆盖（改了名字/版本/贡献后重启即生效）；</li>
 *   <li><b>清理</b>：库里标着 {@code __platform__} 但代码里已不存在 → 删除该记录，
 *       并解除它在所有智能体上的绑定（否则市场页会留下一条"点了必然报错"的僵尸条目）。</li>
 * </ol>
 *
 * <p><b>写内置插件的方式</b>：实现 {@code AgentHook} / {@code ToolProvider}（或两者）+ 标 {@code @Component}；
 * 想让市场页显示得好看，再实现 {@link PluginDescriptor} 声明展示名与说明，
 * 否则展示名会退化成插件 id。</p>
 *
 * <p><b>注意</b>：注册时会调用 {@code ToolProvider.provideTools()} 来生成市场展示用的贡献清单，
 * 所以该方法应当是<b>无副作用的纯声明</b>（不要在里面建连接、读文件）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinPluginRegistrar {

    /** 内置插件所属租户（与 {@code PluginService} 的约定一致：内置插件对所有租户可见）。 */
    private static final String PLATFORM_TENANT = "__platform__";

    private final PluginRuntime pluginRuntime;
    private final PluginRepository pluginRepository;
    private final AgentPluginRepository agentPluginRepository;

    /** 启动时同步一次（此时所有 Plugin Bean 已被 {@link PluginRuntime} 收集完毕）。 */
    @PostConstruct
    public void sync() {
        Map<String, Plugin> builtins = pluginRuntime.builtinPlugins();
        int created = 0;
        int updated = 0;
        for (Plugin plugin : builtins.values()) {
            try {
                if (upsert(plugin)) {
                    created++;
                } else {
                    updated++;
                }
            } catch (Exception e) {
                log.warn("[plugin] 内置插件 {} 的元数据注册失败：{}", plugin.id(), e.getMessage());
            }
        }
        int removed = pruneMissing(builtins);
        if (created + updated + removed > 0) {
            log.info("[plugin] 内置插件已同步到插件市场：新增 {} / 更新 {} / 清理 {}（当前共 {} 个内置插件）",
                    created, updated, removed, builtins.size());
        } else {
            log.info("[plugin] 内置插件无需变更（共 {} 个）", builtins.size());
        }
    }

    /**
     * 写入或更新一个内置插件的元数据。
     *
     * @return true = 新建，false = 覆盖更新
     */
    private boolean upsert(Plugin plugin) {
        String pluginId = plugin.id();
        String name = displayNameOf(plugin);
        String description = plugin instanceof PluginDescriptor d && d.description() != null
                ? d.description() : "";
        String author = plugin instanceof PluginDescriptor d && d.author() != null && !d.author().isBlank()
                ? d.author() : PLATFORM_TENANT;
        String version = plugin.version() == null || plugin.version().isBlank() ? "1.0.0" : plugin.version();
        Map<String, Object> manifest = manifestOf(plugin, name, description, author, version);

        PluginDef existing = pluginRepository.findByTenantIdAndPluginId(PLATFORM_TENANT, pluginId).orElse(null);
        if (existing != null) {
            existing.setName(name);
            existing.setDescription(description);
            existing.setAuthor(author);
            existing.setLatestVersion(version);
            existing.setManifest(manifest);
            existing.setStatus("published");
            existing.setVisibility("marketplace");
            pluginRepository.save(existing);
            return false;
        }
        PluginDef def = PluginDef.builder()
                .pluginId(pluginId)
                .tenantId(PLATFORM_TENANT)
                .name(name)
                .description(description)
                .author(author)
                .latestVersion(version)
                .manifest(manifest)
                // 内置插件没有制品：运行时由 PluginRuntime 从 Spring 容器直接取实例，
                // 不会走 ExternalPluginLoader，所以 artifact_uri 必须留空
                .status("published")
                .visibility("marketplace")
                .build();
        pluginRepository.save(def);
        return true;
    }

    /**
     * 清理「库里标着内置、代码里却已不存在」的残留记录（例如内置插件类被删除后）。
     */
    private int pruneMissing(Map<String, Plugin> builtins) {
        int removed = 0;
        for (PluginDef def : pluginRepository.findByTenantIdOrderByUpdatedAtDesc(PLATFORM_TENANT)) {
            if (builtins.containsKey(def.getPluginId())) {
                continue;
            }
            try {
                // 先解除该插件在所有智能体上的绑定，避免界面留下"已挂载但必失败"的状态。
                // agent_def.capabilities 里的残留 id 由运行时兜底静默跳过（找不到插件只记 debug）。
                for (AgentPlugin binding : agentPluginRepository.findByPluginId(def.getPluginId())) {
                    agentPluginRepository.delete(binding);
                }
                pluginRepository.delete(def);
                removed++;
                log.info("[plugin] 已清理失效的内置插件记录：{}（{}）", def.getPluginId(), def.getName());
            } catch (Exception e) {
                log.warn("[plugin] 清理内置插件记录 {} 失败：{}", def.getPluginId(), e.getMessage());
            }
        }
        return removed;
    }

    private static String displayNameOf(Plugin plugin) {
        if (plugin instanceof PluginDescriptor d && d.name() != null && !d.name().isBlank()) {
            return d.name();
        }
        return plugin.id();
    }

    /** 生成市场展示用的贡献清单（结构对齐外部插件的 manifest，便于市场页统一渲染）。 */
    private Map<String, Object> manifestOf(Plugin plugin, String name, String description,
                                           String author, String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", plugin.id());
        m.put("name", name);
        m.put("version", version);
        m.put("description", description);
        m.put("author", author);
        m.put("entry", Map.of("type", "builtin"));

        Map<String, Object> contributes = new LinkedHashMap<>();
        if (plugin instanceof AgentHook hook) {
            contributes.put("hooks", List.of(Map.of("point", String.valueOf(hook.point()))));
        }
        if (plugin instanceof ToolProvider provider) {
            List<Map<String, Object>> tools = new ArrayList<>();
            try {
                for (PluginTool t : provider.provideTools()) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("name", t.name());
                    one.put("description", t.description() == null ? "" : t.description());
                    tools.add(one);
                }
            } catch (Exception e) {
                log.warn("[plugin] 内置插件 {} 的 provideTools() 执行失败，贡献清单将缺少工具：{}",
                        plugin.id(), e.getMessage());
            }
            contributes.put("tools", tools);
        }
        m.put("contributes", contributes);
        return m;
    }
}
