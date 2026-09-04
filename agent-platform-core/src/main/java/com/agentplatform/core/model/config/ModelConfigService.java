package com.agentplatform.core.model.config;

import com.agentplatform.core.model.secret.ModelKeyCrypto;
import com.agentplatform.model.entity.ModelConfig;
import com.agentplatform.model.record.ModelBinding;
import com.agentplatform.model.record.ModelBindingView;
import com.agentplatform.model.record.ModelConfigView;
import com.agentplatform.model.repository.ModelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台级模型配置服务（单行，id=1）。
 * <p>
 * 承载两类平台级默认绑定，均做 trim + 加密 + 留空保留原密钥，对外仅回掩码视图：
 * <ul>
 *   <li><b>嵌入模型绑定</b>（{@code embedding_binding}）—— RAG 向量化；</li>
 *   <li><b>默认对话模型绑定</b>（{@code chat_binding}，V10）—— 智能体未单独配置模型时
 *       回退到此，避免「每个新建智能体都重复填一遍 provider/baseUrl/API Key」。</li>
 * </ul>
 * 单行配置内存缓存，保存时失效，避免 RAG 摄取每 chunk 都查库。
 * </p>
 * <p>
 * <b>依赖方向</b>：本服务只依赖 {@link ModelKeyCrypto}，不依赖
 * {@code ModelBindingService}——后者会反向依赖本服务读取平台默认对话模型，
 * 如此可避免两者形成循环依赖。加解密语义与 {@code ModelBindingService.seal} 保持一致。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelConfigRepository repository;
    private final ModelKeyCrypto crypto;

    /** 单行配置内存缓存（volatile，保存后失效重载）。 */
    private volatile ModelConfig cached;

    // ---- 嵌入模型 ----

    /**
     * 解析嵌入模型绑定（apiKey 解密为明文）。
     * <p>未配置时四个字段均为 null，由调用方走本地 Mock 兜底。</p>
     */
    public EmbeddingBinding getEmbedding() {
        ModelBinding b = config().getEmbeddingBinding();
        if (b == null) {
            return EmbeddingBinding.empty();
        }
        return new EmbeddingBinding(
                trim(b.provider()),
                trim(b.model()),
                trim(b.baseUrl()),
                safeDecrypt(b.apiKey()));
    }

    /**
     * 写入嵌入模型绑定：trim + 加密，保存后回掩码视图。
     */
    @Transactional
    public ModelConfigView saveEmbedding(ModelBinding in) {
        ModelConfig cfg = config();
        ModelBinding sealed = seal(in, cfg.getEmbeddingBinding());
        cfg.setId(1L);
        cfg.setEmbeddingBinding(sealed);
        cached = repository.save(cfg);
        log.info("[model-config] embedding binding saved provider={} model={}",
                trim(sealed.provider()), trim(sealed.model()));
        return view();
    }

    // ---- 默认对话模型 ----

    /**
     * 解析平台默认对话模型绑定（apiKey 解密为明文）。
     * <p>未配置时四字段均为 null，由 {@code ModelBindingService} 回退到全局 LiteLLM。</p>
     */
    public ChatBinding getChat() {
        ModelBinding b = config().getChatBinding();
        if (b == null) {
            return ChatBinding.empty();
        }
        return new ChatBinding(
                trim(b.provider()),
                trim(b.model()),
                trim(b.baseUrl()),
                safeDecrypt(b.apiKey()));
    }

    /**
     * 写入平台默认对话模型绑定。
     */
    @Transactional
    public ModelConfigView saveChat(ModelBinding in) {
        ModelConfig cfg = config();
        ModelBinding sealed = seal(in, cfg.getChatBinding());
        cfg.setId(1L);
        cfg.setChatBinding(sealed);
        cached = repository.save(cfg);
        log.info("[model-config] chat binding saved provider={} model={}",
                trim(sealed.provider()), trim(sealed.model()));
        return view();
    }

    // ---- 对外视图 ----

    /**
     * 对外掩码视图（绝不回明文）。
     */
    public ModelConfigView view() {
        ModelConfig cfg = config();
        return new ModelConfigView(
                view(cfg.getEmbeddingBinding()),
                view(cfg.getChatBinding()));
    }

    /**
     * 写库前清洗 + 加密（与 {@code ModelBindingService.seal} 同语义）。
     * <p>apiKey 非空 → trim 后加密；留空 → 保留已有密文（编辑时不清空原密钥）。</p>
     */
    private ModelBinding seal(ModelBinding in, ModelBinding existing) {
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

    /** 对外掩码视图（解密后仅回掩码，绝不含明文）。 */
    private ModelBindingView view(ModelBinding b) {
        if (b == null) {
            return emptyView();
        }
        String plain = safeDecrypt(b.apiKey());
        boolean has = plain != null && !plain.isBlank();
        return new ModelBindingView(trim(b.provider()), trim(b.model()), trim(b.baseUrl()),
                crypto.mask(plain), has);
    }

    /** 取单行配置（懒加载 + 缓存）。 */
    private ModelConfig config() {
        ModelConfig c = cached;
        if (c == null) {
            synchronized (this) {
                c = cached;
                if (c == null) {
                    c = repository.findById(1L).orElseGet(() -> {
                        ModelConfig fresh = new ModelConfig();
                        fresh.setId(1L);
                        return repository.save(fresh);
                    });
                    cached = c;
                }
            }
        }
        return c;
    }

    /** 安全解密：失败按「未配置」处理，避免坏密文阻断整个 RAG 摄取 / 对话。 */
    private String safeDecrypt(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return crypto.decrypt(enc);
        } catch (Exception e) {
            log.warn("[model-config] API Key 解密失败（主密钥可能与保存时不一致）: {}", e.getMessage());
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** 全空绑定视图（未配置）。 */
    private static ModelBindingView emptyView() {
        return new ModelBindingView(null, null, null, null, false);
    }

    /**
     * 嵌入模型绑定（运行时明文，供三级路由使用）。
     *
     * @param provider 服务商（openai/qwen/ernie/hunyuan/deepseek/local/auto/null）
     * @param model    嵌入模型名（BAAI/bge-m3 / text-embedding-3-small 等）
     * @param baseUrl  直连端点（非空且 apiKey 非空时直连）
     * @param apiKey   解密后的明文凭证
     */
    public record EmbeddingBinding(String provider, String model, String baseUrl, String apiKey) {

        public static EmbeddingBinding empty() {
            return new EmbeddingBinding(null, null, null, null);
        }

        /** 是否具备直连条件（有效 baseUrl + 有效 key 且 provider ≠ local）。 */
        public boolean direct() {
            return provider != null && !provider.isBlank()
                    && !"local".equalsIgnoreCase(provider) && !"mock".equalsIgnoreCase(provider)
                    && baseUrl != null && !baseUrl.isBlank()
                    && apiKey != null && !apiKey.isBlank();
        }

        /** 是否显式本地 Mock。 */
        public boolean local() {
            return "local".equalsIgnoreCase(provider == null ? "" : provider)
                    || "mock".equalsIgnoreCase(provider == null ? "" : provider);
        }

        /** 是否配置了任一字段（否则视为未配置 → 本地 Mock 兜底）。 */
        public boolean configured() {
            return (provider != null && !provider.isBlank())
                    || (model != null && !model.isBlank())
                    || (baseUrl != null && !baseUrl.isBlank())
                    || (apiKey != null && !apiKey.isBlank());
        }

        /** 是否配置了嵌入模型名（供 GATEWAY 分支使用）。 */
        public String modelOrDefault(String fallback) {
            return model == null || model.isBlank() ? fallback : model;
        }
    }

    /**
     * 平台默认对话模型绑定（运行时明文）。
     * <p>智能体自身未配置 model_binding 时，{@code ModelBindingService} 会回退到此；
     * 仍为空才退到全局 LiteLLM / 环境变量。这样新建智能体无需重复填写凭证。</p>
     */
    public record ChatBinding(String provider, String model, String baseUrl, String apiKey) {

        public static ChatBinding empty() {
            return new ChatBinding(null, null, null, null);
        }

        /** 是否具备直连条件（有效 baseUrl + 有效 key 且 provider ≠ local）。 */
        public boolean direct() {
            return provider != null && !provider.isBlank()
                    && !"local".equalsIgnoreCase(provider) && !"mock".equalsIgnoreCase(provider)
                    && baseUrl != null && !baseUrl.isBlank()
                    && apiKey != null && !apiKey.isBlank();
        }

        /** 是否显式本地 Mock。 */
        public boolean local() {
            return "local".equalsIgnoreCase(provider == null ? "" : provider)
                    || "mock".equalsIgnoreCase(provider == null ? "" : provider);
        }

        /** 是否配置了任一字段。 */
        public boolean configured() {
            return (provider != null && !provider.isBlank())
                    || (model != null && !model.isBlank())
                    || (baseUrl != null && !baseUrl.isBlank())
                    || (apiKey != null && !apiKey.isBlank());
        }
    }
}
