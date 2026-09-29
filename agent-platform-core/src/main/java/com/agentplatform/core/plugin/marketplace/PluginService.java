package com.agentplatform.core.plugin.marketplace;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.agent.service.AgentService;
import com.agentplatform.core.plugin.runtime.ExtensionRegistry;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.entity.AgentPlugin;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.repository.AgentPluginRepository;
import com.agentplatform.model.repository.PluginRepository;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 插件服务（市场 + 插入 + 生命周期）。
 * <p>
 * 覆盖 开发→上传→审核→安装→插入→启用→卸载→升级 全链路。Attach/Enable 事件
 * 触发 PluginRuntime 实例化插件（独立 ClassLoader），Detach 反注册后卸载。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginService {

    /** 平台级插件所属租户（官方市场插件，对所有租户可见）。 */
    private static final String PLATFORM_TENANT = "__platform__";

    private final PluginRepository pluginRepository;
    private final AgentPluginRepository agentPluginRepository;
    private final AgentService agentService;
    private final PluginRuntime pluginRuntime;
    private final ExtensionRegistry extensionRegistry;
    /** 配置里密钥字段的加解密与掩码（TTS 这类插件需要用户填真实的付费 API Key）。 */
    private final PluginConfigSecrets pluginConfigSecrets;

    /**
     * 注册插件（上传 / 安装）。
     */
    @Transactional
    public PluginDef register(String tenantId, PluginManifest manifest, String artifactUri) {
        String pluginId = manifest.id();
        PluginDef existing = pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .orElse(null);
        if (existing != null) {
            throw BizException.conflict(duplicateMessage(pluginId));
        }
        PluginDef def = PluginDef.builder()
                .pluginId(pluginId)
                .tenantId(tenantId)
                .name(manifest.name())
                .description(manifest.description())
                .author(manifest.author())
                .latestVersion(manifest.version())
                .manifest(JsonUtils.mapper().convertValue(manifest, Map.class))
                .artifactUri(artifactUri)
                .status("published")
                .visibility("private")
                .build();
        try {
            return pluginRepository.save(def);
        } catch (DataIntegrityViolationException e) {
            // check-then-insert 存在竞态：并发重复导入时唯一键冲突会穿透，这里统一转成 409
            log.warn("Duplicate plugin insert (race): {} [{}]", pluginId, e.getMessage());
            throw BizException.conflict(duplicateMessage(pluginId));
        }
    }

    private static String duplicateMessage(String pluginId) {
        return "插件已存在: " + pluginId + "（请勿重复导入；如需覆盖请先删除该插件）";
    }

    /**
     * 插件市场列表。
     */
    @Transactional(readOnly = true)
    public List<PluginDef> marketplace(String tenantId) {
        // 平台级插件（内置 auto_reply / tts / asr）对所有租户可见
        return pluginRepository.findByTenantIdInOrderByUpdatedAtDesc(List.of(tenantId, PLATFORM_TENANT));
    }

    /**
     * 插件详情。
     */
    @Transactional(readOnly = true)
    public PluginDef detail(String tenantId, String pluginId) {
        return pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .or(() -> pluginRepository.findByTenantIdAndPluginId(PLATFORM_TENANT, pluginId))
                .orElseThrow(() -> BizException.notFound("plugin", pluginId));
    }

    /**
     * 插入智能体（挂载插件）：持久化绑定 + 更新 capabilities + 热加载。
     */
    @Transactional
    public AgentPlugin attach(String tenantId, String agentId, String pluginId,
                              String version, Map<String, Object> config, Boolean enabled) {
        // 校验插件存在（内置或已注册）
        if (!pluginRuntime.isBuiltin(pluginId)
                && pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId).isEmpty()) {
            throw BizException.notFound("plugin", pluginId);
        }

        // 持久化绑定。密钥类字段加密落库；若提交回来的是掩码或空值（= 用户没改这一栏），
        // 沿用库里已保存的密钥 —— 否则前端回显的 "sk-***abcd" 会被当成真密钥存下来，
        // 之后每次调用都 401，而界面上却仍显示"已配置"。
        Optional<AgentPlugin> existingBinding = agentPluginRepository.findByAgentIdAndPluginId(agentId, pluginId);
        Map<String, Object> previousConfig = existingBinding.map(AgentPlugin::getConfig).orElse(null);

        /*
         * ★ 合并「已有配置」与「本次提交」，而不是整体替换。
         *
         * 起因：界面改成"按插件声明生成表单"之后，表单只会提交**声明过的**字段。
         * 若这里直接替换，用户此前用自由 JSON 写的额外键（或插件新版新增、而界面尚未声明的键），
         * 会在一次普通的"改个开关"中**被静默抹掉** —— 这是最难查的一类数据丢失：
         * 用户没做错任何事，配置却少了。
         *
         * ⚠️ 合并必须用**解密后**的旧配置。previousConfig 里的敏感值是密文，
         * 若直接把它合进去，seal 会当成"新的密钥"再加密一次，库里就变成双层密文；
         * 插件的 reveal 只解一层，拿到的仍以 enc:v1: 开头 —— 表现为"密钥明明没动却失效了"。
         */
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> previousPlain = pluginConfigSecrets.reveal(previousConfig);
        if (previousPlain != null) {
            merged.putAll(previousPlain);
        }
        if (config != null) {
            merged.putAll(config);   // 本次提交的值覆盖旧的
        }
        Map<String, Object> sealedConfig = pluginConfigSecrets.seal(merged, previousConfig);

        AgentPlugin binding = existingBinding
                .map(existing -> {
                    existing.setVersion(version);
                    existing.setConfig(sealedConfig);
                    existing.setEnabled(enabled == null || enabled);
                    return existing;
                })
                .orElseGet(() -> AgentPlugin.builder()
                        .agentId(agentId)
                        .pluginId(pluginId)
                        .version(version)
                        .config(sealedConfig)
                        .enabled(enabled == null || enabled)
                        .attachedBy(tenantId)
                        .build());
        binding = agentPluginRepository.save(binding);

        // 更新 Agent capabilities.pluginIds
        AgentDefinition agent = agentService.getOrThrow(tenantId, agentId);
        Capabilities caps = agent.getCapabilities() == null ? Capabilities.empty() : agent.getCapabilities();
        List<String> pluginIds = new ArrayList<>(caps.pluginIds() == null ? List.of() : caps.pluginIds());
        if (!pluginIds.contains(pluginId)) {
            pluginIds.add(pluginId);
        }
        agentService.updateCapabilities(tenantId, agentId,
                new Capabilities(caps.knowledgeBaseIds(), caps.toolsetIds(), caps.skillIds(), pluginIds,
                        caps.workflowId(), caps.multimodal()));

        // 热加载：插件拿到的是**解密后**的配置 —— 它要用真实密钥去调厂商
        if (enabled == null || enabled) {
            pluginRuntime.attach(pluginId, agentId, tenantId, pluginConfigSecrets.reveal(sealedConfig));
        }
        log.info("Attached plugin {} to agent {}", pluginId, agentId);
        return binding;
    }

    /**
     * 卸载插件（<b>级联</b>：该插件在<b>所有</b>智能体上的挂载都会被取消）。
     *
     * <p><b>为什么必须是级联（2026-09-20 定案）</b>：插件能力是按智能体注册的，但运行时的
     * {@code PluginRuntime} 只持有插件实例，若这里只删掉"当前这一条"绑定，就会出现两边不一致 ——
     * 其它智能体界面上仍显示「已挂载」，而插件其实已经随本次卸载一起失效了。</p>
     *
     * <p>所以统一做级联卸载：清掉该插件的全部绑定、反注册各智能体上的运行时贡献、
     * 并同步移除各智能体 capabilities 里的 pluginIds。前端会在用户确认弹窗里
     * 明确列出"将被影响的智能体"，用户确认后才走到这里。</p>
     *
     * @param agentId 触发卸载的智能体（可为 null，表示"不针对特定智能体，清掉所有挂载"）
     * @return 卸载明细（含被取消挂载的智能体列表）
     */
    @Transactional
    public Map<String, Object> detach(String tenantId, String agentId, String pluginId) {
        Set<String> targets = new LinkedHashSet<>();
        if (agentId != null && !agentId.isBlank()) {
            targets.add(agentId);
        }
        // 找出所有挂着该插件的智能体（含触发方之外的）
        for (AgentPlugin binding : agentPluginRepository.findByPluginId(pluginId)) {
            targets.add(binding.getAgentId());
        }
        List<String> detached = new ArrayList<>();
        for (String aid : targets) {
            agentPluginRepository.deleteByAgentIdAndPluginId(aid, pluginId);
            pluginRuntime.detach(aid, pluginId);
            removeFromCapabilities(tenantId, aid, pluginId);
            detached.add(aid);
        }
        log.info("Detached plugin {} from {} agent(s): {}", pluginId, detached.size(), detached);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("plugin_id", pluginId);
        r.put("detached_agents", detached);
        r.put("count", detached.size());
        return r;
    }

    /**
     * 从某智能体的 {@code capabilities.pluginIds} 里摘掉一个插件。
     * <p>找不到 / 智能体已删除都只是跳过 —— 清理动作不能反过来打断卸载。</p>
     */
    private void removeFromCapabilities(String tenantId, String agentId, String pluginId) {
        try {
            AgentDefinition agent = agentService.getOrThrow(tenantId, agentId);
            Capabilities caps = agent.getCapabilities() == null ? Capabilities.empty() : agent.getCapabilities();
            List<String> pluginIds = new ArrayList<>(caps.pluginIds() == null ? List.of() : caps.pluginIds());
            if (!pluginIds.remove(pluginId)) {
                return;   // 本来就没有，无需回写
            }
            agentService.updateCapabilities(tenantId, agentId,
                    new Capabilities(caps.knowledgeBaseIds(), caps.toolsetIds(), caps.skillIds(), pluginIds,
                            caps.workflowId(), caps.multimodal()));
        } catch (Exception e) {
            log.warn("清理 agent {} 的 capabilities.pluginIds({}) 失败：{}", agentId, pluginId, e.getMessage());
        }
    }

    /**
     * 查某插件被哪些智能体挂载（卸载前的影响面提示）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> attachmentsOfPlugin(String tenantId, String pluginId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentPlugin b : agentPluginRepository.findByPluginId(pluginId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", b.getAgentId());
            m.put("pluginId", b.getPluginId());
            m.put("version", b.getVersion());
            m.put("enabled", b.getEnabled() != null && b.getEnabled());
            String name = b.getAgentId();
            try {
                name = agentService.getOrThrow(tenantId, b.getAgentId()).getName();
            } catch (Exception e) {
                // 智能体已被删除：退化成展示 id
            }
            m.put("agentName", name);
            out.add(m);
        }
        return out;
    }

    /**
     * 查询某 Agent 已挂载插件及其贡献。
     *
     * <p><b>回显 {@code config}</b>（密钥字段为掩码）：前端要据此把已填过的配置显示出来 ——
     * 否则用户每次打开挂载弹窗都只能看到一个空白表单，无法判断"到底配没配过"，
     * 只能凭记忆重填一遍（而重填时若他填了新的，旧的就真被覆盖了）。
     * 也正因为要回显，密钥字段必须是掩码而非明文。</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAttached(String tenantId, String agentId) {
        List<AgentPlugin> bindings = agentPluginRepository.findByAgentIdAndEnabledTrue(agentId);
        List<Map<String, Object>> out = new ArrayList<>(bindings.size());
        for (AgentPlugin b : bindings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plugin_id", b.getPluginId());
            m.put("version", b.getVersion() == null ? "" : b.getVersion());
            m.put("tools", extensionRegistry.toolNamesOf(agentId, b.getPluginId()));
            m.put("enabled", b.getEnabled() != null && b.getEnabled());
            m.put("config", pluginConfigSecrets.mask(b.getConfig()));
            out.add(m);
        }
        return out;
    }

    /**
     * 支持的界面组件类型白名单。
     *
     * <p><b>刻意是白名单，而不是"把插件写的东西原样交给前端"</b>：这是声明式 UI 的安全边界 ——
     * 只有宿主认识、且能安全渲染的组件才允许出现。插件写了个新类型时，
     * 后果应当是"这一项不显示"，而不是"界面出问题"。</p>
     *
     * <p>⚠️ 新增类型时要与前端 {@code src/slots/registry.ts} 的渲染器同步 ——
     * 两边不一致的表现是"声明了但永远不显示"，且没有任何报错。</p>
     */
    private static final Set<String> UI_TYPES = Set.of("button", "badge", "card", "list", "link");

    /**
     * 汇总某智能体上所有**生效插件**的界面贡献（前端插槽的数据源）。
     *
     * <h3>★ 为什么单独开一个接口，而不是让前端自己读 manifest</h3>
     * 前端确实能从 {@code pluginDetail} 拿到 manifest，但要靠它自己做三件事，每件都容易漏：
     * <ol>
     *   <li><b>确定"哪些插件在这台智能体上生效"</b> —— 要交叉 capabilities 与绑定表的 enabled 开关；</li>
     *   <li><b>校验引用的工具真的存在</b> —— 见下；</li>
     *   <li><b>按插件聚合与排序</b> —— 每个插件内部还有自己的 order。</li>
     * </ol>
     *
     * <h3>★ 校验 action / dataSource 引用的工具是否存在</h3>
     * 一个界面按钮声明了 {@code action.tool = "export_chat"}，而插件根本没这个工具 ——
     * 后果是<b>用户点了没反应，且没有任何错误提示</b>（最难排查的一类）。
     * 所以这里把引用了未声明工具的界面项<b>直接丢弃</b>，并记 WARN 让插件作者能发现自己的错。
     *
     * <p>校验依据是 manifest 的 {@code contributes.tools}：外部插件由作者声明、
     * 内置插件由 {@code BuiltinPluginRegistrar} 从 {@code provideTools()} 生成，结构一致，
     * 所以这里不必区分来源。</p>
     *
     * <p><b>槽位（slot）刻意不在这里校验</b> —— 槽位是前端的渲染位置，前端最清楚自己有哪些。
     * 认不出的槽位由前端忽略（这样后端加新槽位不必改插件，插件用新槽位也不会让旧前端报错）。</p>
     *
     * @return 扁平化的界面贡献（每条带 pluginId / pluginName，便于前端分组与做 React key）
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUiContributions(String tenantId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();

        for (AgentPlugin binding : agentPluginRepository.findByAgentIdAndEnabledTrue(agentId)) {
            Map<String, Object> manifest = manifestOfPlugin(tenantId, binding.getPluginId());
            if (manifest == null) {
                continue;
            }
            if (!(manifest.get("contributes") instanceof Map<?, ?> contributes)) {
                continue;
            }
            if (!(contributes.get("ui") instanceof List<?> uiList)) {
                continue;   // 该插件没有界面贡献：正常情况，不是错误
            }

            Set<String> declaredTools = declaredToolNames(contributes.get("tools"));
            String pluginName = asString(manifest.get("name"));

            for (Object item : uiList) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String type = asString(m.get("type"));
                if (type == null || !UI_TYPES.contains(type)) {
                    log.warn("[plugin-ui] 跳过插件 {} 的界面项：不支持的组件类型 {}（支持：{}）",
                            binding.getPluginId(), type, UI_TYPES);
                    continue;
                }
                Map<String, Object> action = asMap(m.get("action"));
                Map<String, Object> dataSource = asMap(m.get("dataSource"));
                if (!toolExists(action, declaredTools) || !toolExists(dataSource, declaredTools)) {
                    log.warn("[plugin-ui] 跳过插件 {} 的界面项「{}」：引用了未声明的工具"
                                    + "（action={} dataSource={} 已声明工具={}）",
                            binding.getPluginId(), asString(m.get("label")),
                            action == null ? null : action.get("tool"),
                            dataSource == null ? null : dataSource.get("tool"), declaredTools);
                    continue;
                }

                Map<String, Object> one = new LinkedHashMap<>();
                one.put("pluginId", binding.getPluginId());
                one.put("pluginName", pluginName);
                one.put("id", asString(m.get("id")));
                one.put("slot", asString(m.get("slot")));
                one.put("type", type);
                one.put("label", asString(m.get("label")));
                one.put("order", m.get("order") instanceof Number n ? n.intValue() : 0);
                one.put("href", asString(m.get("href")));
                one.put("action", action);
                one.put("dataSource", dataSource);
                out.add(one);
            }
        }
        return out;
    }

    /** 取插件 manifest（外部与内置统一从 {@code plugin_def} 读，两者结构一致）。 */
    private Map<String, Object> manifestOfPlugin(String tenantId, String pluginId) {
        return pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .or(() -> pluginRepository.findByTenantIdAndPluginId(PLATFORM_TENANT, pluginId))
                .map(PluginDef::getManifest)
                .orElse(null);
    }

    /** manifest 的 {@code contributes.tools[].name} 集合。 */
    private static Set<String> declaredToolNames(Object toolsRaw) {
        if (!(toolsRaw instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (Object t : list) {
            if (t instanceof Map<?, ?> m) {
                String n = asString(m.get("name"));
                if (n != null) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    /**
     * 检查一段声明里引用的工具是否存在。
     *
     * <p>声明为 null 视为合法 —— 它的含义是"这一项不调工具"（例如纯展示的 link）。</p>
     */
    private static boolean toolExists(Map<String, Object> decl, Set<String> declaredTools) {
        if (decl == null) {
            return true;
        }
        String tool = asString(decl.get("tool"));
        return tool == null || declaredTools.contains(tool);
    }

    private static Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String asString(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw);
        return s.isBlank() ? null : s;
    }

    /**
     * 删除插件（物理删除 + 从所有智能体卸载）。
     * <p>
     * 仅允许删除租户自有插件；官方市场插件（{@code __platform__}）为内置能力，
     * 删除会破坏运行时，明确拒绝。删除前先卸载，避免留下指向已删插件的钩子与工具。
     * </p>
     */
    @Transactional
    public void delete(String tenantId, String pluginId) {
        PluginDef def = pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .orElseThrow(() -> BizException.notFound("plugin", pluginId));
        if (PLATFORM_TENANT.equals(def.getTenantId())) {
            throw BizException.forbidden("内置平台插件不可删除: " + pluginId);
        }
        // 先从所有挂载了它的智能体上卸载（反注册 Hook / 工具，并同步 capabilities）
        detach(tenantId, null, pluginId);
        // 兜底：即使没有绑定记录也尝试卸掉运行时残留（可能由热挂载留下）
        try {
            pluginRuntime.detachEverywhere(pluginId);
        } catch (Exception ignored) {
            // 未挂载时 detach 是幂等的
        }
        pluginRepository.delete(def);
        log.info("Deleted plugin {} [{}]", def.getName(), pluginId);
    }
}