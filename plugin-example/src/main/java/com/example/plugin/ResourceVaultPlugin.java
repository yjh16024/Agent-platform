package com.example.plugin;

import com.agentplatform.plugin.sdk.ExtensionRegistrar;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ResourceProvider;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.ResourceDescriptor;
import com.agentplatform.plugin.sdk.model.ResourceTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 类型六：<b>资源托管</b>（{@code ResourceProvider}）—— 把外部依赖登记给宿主，供同智能体的插件复用。
 *
 * <p>这是 2026-09-22 新落地的能力，此前 {@code ResourceProvider} 只是个空壳
 * （{@code ExtensionRegistry} 里打一行 warn 就完事）。</p>
 *
 * <h3>它解决什么</h3>
 * <p>没有它时，每个需要外部服务的插件都得在自己的 {@code config} 里塞一份明文凭据；
 * 多个插件用同一个服务就得多份拷贝、各自轮换。有了它：<b>一个插件声明、多方按 id 取用</b>，
 * 宿主站在中间，将来接统一的加密托管与轮换也有落点。</p>
 *
 * <h3>⚠️ 最重要的约束：必须在 onAttach 期间解析</h3>
 * <p>{@code resolveResource} 的归属取自 <b>attach 作用域</b>（宿主据此判断"你有没有资格拿这个资源"），
 * 而 attach 作用域只在挂载期间存在。所以：</p>
 * <pre>
 *   public void onAttach(PluginContext ctx) {
 *       this.client = (MyClient) ctx.registrar().resolveResource("example_vault:client");
 *       //  ^^^ 在这里解析并缓存；出了 onAttach 再调 resolveResource 会拿不到（记 warn 并返回 null）
 *   }
 * </pre>
 * <p>这其实与 Spring 的"启动时装配依赖"是同一个模式，之所以要显式缓存而不是每次用时再解析，
 * 就是为了不把作用域假设泄漏到工具执行期（工具跑在别的线程/时机，那时没有作用域）。</p>
 *
 * <h3>可见性</h3>
 * <p>只能解析到<b>同一智能体</b>上别人提供的资源 —— 跨智能体不可见，否则挂到 A 的插件
 * 足以把凭据泄露给 B。</p>
 *
 * <h3>本示例演示的技能点</h3>
 * <ol>
 *   <li>一个插件同时实现 {@code ResourceProvider} 与 {@code ToolProvider}（两类贡献并存）；</li>
 *   <li>{@code provideResources()} 是<b>纯声明</b>，{@code provide(id)} 才做实事（惰性）；</li>
 *   <li>在 {@code onAttach} 里解析资源并缓存，工具只读缓存 —— 不在工具里抛作用域错误。</li>
 * </ol>
 */
public class ResourceVaultPlugin implements ResourceProvider, ToolProvider {

    /** 必须与 manifests/resource-vault.yaml 里的 id 一致。 */
    public static final String PLUGIN_ID = "example_resource_vault";

    /** 本插件登记的资源 ID（全平台唯一，建议带插件前缀）。 */
    public static final String RES_VISITOR_ID = "example_vault:visitor_id";
    public static final String RES_PRICING = "example_vault:pricing_cache";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * attach 期间解析出来的资源缓存。
     *
     * <p>用 {@code volatile} 而非 final：字段在 {@code onAttach} 里赋值，
     * 而 {@code onDetach} 要能清掉。</p>
     */
    private volatile Object visitorId;
    private volatile Object pricingCache;

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    // ---------------- ResourceProvider ----------------

    /**
     * 纯声明：宿主在挂载与"列清单"时都会调用它，所以<b>不能在这里建连接或读密钥</b>。
     */
    @Override
    public List<ResourceDescriptor> provideResources() {
        return List.of(
                new ResourceDescriptor(RES_VISITOR_ID, ResourceTypes.SECRET,
                        "访客ID生成器", "示例：把「访客标识」这类弱凭据托管给宿主，供同智能体的其它插件复用"),
                ResourceDescriptor.of(RES_PRICING, ResourceTypes.DATASOURCE));
    }

    @Override
    public String resourceType() {
        return ResourceTypes.SECRET;
    }

    /**
     * 惰性给出资源本体 —— 只有真的被 resolve 时才走这里。
     *
     * <p>真实场景里这里应当返回已建好的连接/客户端/密钥包装对象；
     * 本例返回轻量字符串与 Map，保证示例自洽且可读。</p>
     */
    @Override
    public Object provide(String resourceId) {
        return switch (resourceId) {
            case RES_VISITOR_ID -> "visitor-" + Integer.toHexString(PLUGIN_ID.hashCode());
            case RES_PRICING -> {
                LinkedHashMap<String, Object> cache = new LinkedHashMap<>();
                cache.put("currency", "CNY");
                cache.put("updated_at", "示例占位");
                yield cache;
            }
            default -> null; // 未声明的 id 一律返回 null，宿主视为解析失败
        };
    }

    // ---------------- Plugin 生命周期 ----------------

    @Override
    public void onAttach(PluginContext ctx) {
        // ★ 关键：必须在 attach 作用域内解析（详见类注释）
        ExtensionRegistrar registrar = ctx.registrar();
        this.visitorId = registrar.resolveResource(RES_VISITOR_ID);
        this.pricingCache = registrar.resolveResource(RES_PRICING);
    }

    @Override
    public void onDetach(PluginContext ctx) {
        this.visitorId = null;
        this.pricingCache = null;
    }

    // ---------------- ToolProvider ----------------

    /**
     * 提供一个工具来证明"资源确实被拿到手了"。
     *
     * <p>工具是<b>由 LLM 决定调用</b>的，与钩子"每轮必跑"不同（见 {@link OutputEnrichPlugin} 的对比）。</p>
     */
    @Override
    public List<PluginTool> provideTools() {
        return List.of(new PluginTool(
                "example_vault_lookup",
                "查看示例资源库里的资源（演示资源托管：返回值来自 onAttach 期间解析并缓存的资源）",
                inputSchema(),
                this::lookup));
    }

    /** 工具实现：入参为 JSON、返回 JSON；失败直接抛异常（宿主会转成失败结果回给模型）。 */
    private JsonNode lookup(JsonNode args, PluginContext ctx) {
        String want = args == null || args.get("resource_id") == null
                ? "" : args.get("resource_id").asText("");

        ObjectNode out = mapper.createObjectNode();
        if (want.isBlank()) {
            out.put(RES_VISITOR_ID, String.valueOf(visitorId));
            out.put(RES_PRICING, String.valueOf(pricingCache));
            return out;
        }
        if (RES_VISITOR_ID.equals(want)) {
            out.put(RES_VISITOR_ID, String.valueOf(visitorId));
            return out;
        }
        if (RES_PRICING.equals(want)) {
            out.put(RES_PRICING, String.valueOf(pricingCache));
            return out;
        }
        throw new IllegalArgumentException("未登记的资源：" + want
                + "（本插件只登记了 " + RES_VISITOR_ID + " 与 " + RES_PRICING + "）");
    }

    /** 入参 JSON Schema —— 写给 LLM 看的，字段说明越清楚调用越准。 */
    private ObjectNode inputSchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode rid = props.putObject("resource_id");
        rid.put("type", "string");
        rid.put("description", "要查看的资源 ID，留空则列出本插件登记的全部资源");
        return root;
    }
}
