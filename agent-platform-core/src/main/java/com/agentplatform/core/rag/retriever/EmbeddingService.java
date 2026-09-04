package com.agentplatform.core.rag.retriever;

import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.config.ModelConfigService;
import com.agentplatform.core.model.config.ModelConfigService.EmbeddingBinding;
import com.agentplatform.core.model.factory.ModelProviderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 文本嵌入服务（三级路由）。
 * <p>
 * 按平台级「嵌入模型绑定」（见 {@link ModelConfigService}）决定向量化去向：
 * <ol>
 *   <li>{@code DIRECT}：绑定含有效 baseUrl + apiKey 且 provider≠local → 直连云端厂商
 *       （OpenAI 兼容 {@code /v1/embeddings}），模型名由绑定指定；</li>
 *   <li>{@code LOCAL}：provider 显式 local/mock，或未配置任何绑定 → 本地 Mock（8 维伪向量，默认态）；</li>
 *   <li>{@code GATEWAY}：仅配置 provider/model 而无 baseUrl → 走全局 LiteLLM 网关（需 litellm 放开对应嵌入模型）。</li>
 * </ol>
 * 上游错误原样透出，不做静默兜底（与非本文对话一致）。
 * </p>
 */
@Slf4j
@Service
public class EmbeddingService {

    private final ModelProviderFactory factory;
    private final ModelConfigService modelConfig;
    private final String defaultEmbeddingModel;

    public EmbeddingService(
            ModelProviderFactory factory,
            ModelConfigService modelConfig,
            @Value("${agent-platform.embedding.default-model:text-embedding-3-small}") String defaultEmbeddingModel) {
        this.factory = factory;
        this.modelConfig = modelConfig;
        this.defaultEmbeddingModel = defaultEmbeddingModel;
    }

    /**
     * 生成文本嵌入向量。
     */
    public float[] embed(String text) {
        EmbeddingBinding b = modelConfig.getEmbedding();

        // ① 直连云端厂商
        if (b.direct()) {
            log.info("[embedding] routing=DIRECT provider={} model={}", b.provider(), b.model());
            ModelAdapter adapter = factory.get(b.provider());
            return adapter.embed(new ModelAdapter.EmbeddingRequest(
                    b.model(), text, b.baseUrl(), b.apiKey()));
        }

        // ② 未配置 / 显式 local → 本地 Mock（保证无外部依赖可跑）
        if (b.local() || !b.configured()) {
            log.info("[embedding] routing=LOCAL(mock) provider={}", b.provider());
            return factory.get("local").embed(new ModelAdapter.EmbeddingRequest(b.model(), text, null, null));
        }

        // ③ 仅配置 provider/model → 全局 LiteLLM 网关（凭证走适配器全局默认）
        String model = b.modelOrDefault(defaultEmbeddingModel);
        log.info("[embedding] routing=GATEWAY provider={} model={}", b.provider(), model);
        ModelAdapter adapter = factory.get(b.provider());
        return adapter.embed(new ModelAdapter.EmbeddingRequest(model, text, null, null));
    }

    /**
     * 生成查询向量（与文档共用同一嵌入模型，保证空间一致）。
     */
    public float[] embedQuery(String query) {
        return embed(query);
    }
}