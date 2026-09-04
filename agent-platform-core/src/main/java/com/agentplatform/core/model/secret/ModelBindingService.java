package com.agentplatform.core.model.secret;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.model.config.ModelConfigService;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.ModelBinding;
import com.agentplatform.model.record.ModelBindingView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 模型绑定服务：写库加密、对外掩码、运行时解析完整回退链。
 * <p>
 * 智能体在仪表盘填写的模型选择（provider / model / baseUrl / apiKey）经本服务管理：
 * <ul>
 *   <li>{@link #seal} —— 写入前对 apiKey 做 trim 清洗后加密为密文，留空时保留原密文；</li>
 *   <li>{@link #view} —— 对外只回掩码，绝不泄露明文；</li>
 *   <li>{@link #resolve} —— 运行时按优先级解析出最终 provider/model/baseUrl/apiKey/路由策略。</li>
 * </ul>
 * 路由优先级（核心）：
 * <ol>
 *   <li>{@code DIRECT}：智能体绑定含有效 baseUrl 且 apiKey 解密成功、provider ≠ local → 直连厂商；</li>
 *   <li>{@code PLATFORM}：智能体未配置绑定时，回退到「模型设置」页配置的平台默认对话模型
 *       （V10）——这样新建智能体不必重复填写 provider/baseUrl/API Key；</li>
 *   <li>{@code GATEWAY}：仍未命中则走 LiteLLM / 全局环境变量；</li>
 *   <li>{@code LOCAL}：仅 provider 显式为 local / mock 时走本地 Mock。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class ModelBindingService {

    /** 最终路由策略。 */
    public enum Routing { DIRECT, PLATFORM, GATEWAY, LOCAL }

    private final ModelKeyCrypto crypto;
    private final String globalBaseUrl;
    private final String globalApiKey;
    private final String defaultProvider;
    private final String defaultModel;

    /** 可选：平台级默认对话模型（未注入时跳过 PLATFORM 回退层）。 */
    private ModelConfigService modelConfigService;

    public ModelBindingService(
            ModelKeyCrypto crypto,
            @Value("${spring.ai.openai.base-url:http://localhost:4000}") String globalBaseUrl,
            @Value("${spring.ai.openai.api-key:sk-local}") String globalApiKey,
            @Value("${agent-platform.model.default-provider:deepseek}") String defaultProvider,
            @Value("${agent-platform.model.default-name:deepseek-chat}") String defaultModel) {
        this(crypto, globalBaseUrl, globalApiKey, defaultProvider, defaultModel, null);
    }

    @Autowired
    public ModelBindingService(
            ModelKeyCrypto crypto,
            @Value("${spring.ai.openai.base-url:http://localhost:4000}") String globalBaseUrl,
            @Value("${spring.ai.openai.api-key:sk-local}") String globalApiKey,
            @Value("${agent-platform.model.default-provider:deepseek}") String defaultProvider,
            @Value("${agent-platform.model.default-name:deepseek-chat}") String defaultModel,
            @Nullable ModelConfigService modelConfigService) {
        this.crypto = crypto;
        this.globalBaseUrl = globalBaseUrl;
        this.globalApiKey = globalApiKey;
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.modelConfigService = modelConfigService;
    }

    /**
     * 写库前清洗 + 加密：复制绑定，apiKey 非空则 trim 后加密为密文。
     */
    public ModelBinding seal(ModelBinding in) {
        return seal(in, null);
    }

    /**
     * 写库前清洗 + 加密。
     * <p>apiKey 留空时<b>保留已有密文</b>（编辑智能体不清空原密钥），
     * 否则对明文做 trim 后加密；provider/model/baseUrl 统一 trim 去除首尾空格。</p>
     */
    public ModelBinding seal(ModelBinding in, ModelBinding existing) {
        if (in == null) {
            return existing;
        }
        String apiKey;
        if (in.apiKey() != null && !in.apiKey().isBlank()) {
            apiKey = crypto.encrypt(in.apiKey().trim());
        } else if (existing != null && existing.apiKey() != null && !existing.apiKey().isBlank()) {
            apiKey = existing.apiKey();   // 保留原密文，绝不复加密
        } else {
            apiKey = null;
        }
        return new ModelBinding(trim(in.provider()), trim(in.model()), trim(in.baseUrl()), apiKey);
    }

    /**
     * 对外视图：解密后仅回掩码，绝不含明文。
     */
    public ModelBindingView view(ModelBinding b) {
        if (b == null) {
            return null;
        }
        String plain = safeDecrypt(b.apiKey());
        boolean has = plain != null && !plain.isBlank();
        return new ModelBindingView(b.provider(), b.model(), b.baseUrl(), crypto.mask(plain), has);
    }

    /**
     * 解析最终生效的模型绑定（provider/model/baseUrl/apiKey + 路由策略）。
     */
    public ResolvedModel resolve(AgentDefinition def) {
        ModelBinding b = def == null ? null : def.getModelBinding();
        GenerationConfig gc = def == null ? null : def.getGenerationConfig();

        String bProvider = trim(b == null ? null : b.provider());
        String bModel = trim(b == null ? null : b.model());
        String bBaseUrl = trim(b == null ? null : b.baseUrl());
        String bApiKey = decryptStrict(b == null ? null : b.apiKey());

        boolean local = "local".equalsIgnoreCase(bProvider) || "mock".equalsIgnoreCase(bProvider);

        // 优先级1：智能体直连（有效 baseUrl + 有效 key 且 provider ≠ local）
        if (!local && !isBlank(bBaseUrl) && !isBlank(bApiKey)) {
            String provider = isBlank(bProvider) || "auto".equalsIgnoreCase(bProvider)
                    ? defaultProvider : bProvider;
            String model = isBlank(bModel) ? defaultModel : bModel;
            log.info("[model-binding] resolve=DIRECT provider={} model={} baseUrl={} hasKey=true",
                    provider, model, bBaseUrl);
            return new ResolvedModel(provider, model, bBaseUrl, bApiKey, Routing.DIRECT);
        }

        // 优先级3：智能体显式 local / mock
        if (local) {
            log.info("[model-binding] resolve=LOCAL provider=local");
            return new ResolvedModel("local", "mock", null, null, Routing.LOCAL);
        }

        // 优先级2：智能体未配置绑定 → 回退平台默认对话模型（模型设置页）
        if (b == null || !b.isConfigured()) {
            ModelConfigService.ChatBinding platform = platformChat();
            if (platform != null && platform.configured()) {
                String provider = normalizeCloudProvider(firstNonBlank(
                        platform.provider(), bProvider,
                        gc == null ? null : gc.provider(), defaultProvider, "auto"));
                String model = firstNonBlank(platform.model(), bModel,
                        gc == null ? null : gc.model(), defaultModel, "deepseek-chat");
                if (platform.direct()) {
                    log.info("[model-binding] resolve=PLATFORM(DIRECT) provider={} model={} baseUrl={}",
                            provider, model, platform.baseUrl());
                    return new ResolvedModel(provider, model, platform.baseUrl(), platform.apiKey(),
                            Routing.PLATFORM);
                }
                if (platform.local()) {
                    log.info("[model-binding] resolve=PLATFORM(LOCAL) provider=local");
                    return new ResolvedModel("local", "mock", null, null, Routing.LOCAL);
                }
                log.info("[model-binding] resolve=PLATFORM(GATEWAY) provider={} model={} baseUrl={}",
                        provider, model, globalBaseUrl);
                return new ResolvedModel(provider, model, globalBaseUrl, globalApiKey, Routing.PLATFORM);
            }
        }

        // 优先级4：LiteLLM 网关 / 全局环境变量（provider 归一化为具体云厂商，绝不回 auto 以防误落 Mock）
        String provider = normalizeCloudProvider(firstNonBlank(
                bProvider,
                gc == null ? null : gc.provider(),
                defaultProvider,
                "auto"));
        String model = firstNonBlank(bModel, gc == null ? null : gc.model(), defaultModel, "deepseek-chat");
        log.info("[model-binding] resolve=GATEWAY provider={} model={} baseUrl={} hasKey={}",
                provider, model, globalBaseUrl, !isBlank(globalApiKey));
        return new ResolvedModel(provider, model, globalBaseUrl, globalApiKey, Routing.GATEWAY);
    }

    /** 读取平台默认对话模型（未注入服务 / 读取失败时为 null）。 */
    private ModelConfigService.ChatBinding platformChat() {
        if (modelConfigService == null) {
            return null;
        }
        try {
            return modelConfigService.getChat();
        } catch (Exception e) {
            log.warn("[model-binding] 读取平台默认对话模型失败，跳过该回退层: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 网关分支下把 auto/local/mock/空归一化为具体云厂商默认值，避免 `auto` 被路由策略误判落到本地 Mock。
     */
    private String normalizeCloudProvider(String p) {
        if (p == null || p.isBlank()
                || "auto".equalsIgnoreCase(p) || "local".equalsIgnoreCase(p) || "mock".equalsIgnoreCase(p)) {
            return defaultProvider;
        }
        return p;
    }

    /**
     * 严格解密：绑定里填了密文却解不开（主密钥变更 / 密文损坏）时，不能静默丢弃走全局，
     * 必须把错误透出，让用户知晓并提供修复动作。
     */
    private String decryptStrict(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return crypto.decrypt(enc);
        } catch (Exception e) {
            log.error("[model-binding] 模型密钥解密失败，主密钥可能与保存时不一致", e);
            throw BizException.internal("模型密钥解密失败（主密钥可能与保存时不一致），请重新填写该智能体的 API Key");
        }
    }

    private String safeDecrypt(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return crypto.decrypt(enc);
        } catch (Exception e) {
            // 仅用于对外 view 展示：解密失败时按「未配置」处理，不回明文也不阻断
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 解析结果（最终发往模型适配层的凭证 + 模型 + 路由策略）。 */
    public record ResolvedModel(String provider, String model, String baseUrl, String apiKey, Routing routing) {
        public boolean direct() {
            return routing == Routing.DIRECT || routing == Routing.PLATFORM;
        }

        public boolean local() {
            return routing == Routing.LOCAL;
        }
    }
}
