package com.agentplatform.core.model.adapter;

import com.agentplatform.core.model.ModelCapability;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一模型接口（策略模式）。
 * <p>
 * 每种模型族（OpenAI / Anthropic / 通义 / 文心 / 混元 / 本地）一个适配器，
 * 由 {@code ModelProviderFactory} 按 provider 名称创建，运行时通过 {@code ModelRouter}
 * 按能力/成本/健康度选择具体实例。
 * </p>
 */
public interface ModelAdapter {

    /**
     * 服务商名称，如 openai / anthropic / qwen / ernie / hunyuan / local。
     */
    String provider();

    /**
     * 支持的能力标签。
     */
    Set<ModelCapability> capabilities();

    /**
     * 是否健康（供路由降级判断）。
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * 每百万 token 的相对成本权重（用于成本路由）。
     */
    default double costWeight() {
        return 1.0;
    }

    /**
     * 同步对话。
     *
     * @param request 统一请求
     * @return 统一响应（含文本 + token 用量）
     */
    ChatResponse chat(ChatRequest request);

    /**
     * 流式对话（SSE）。
     */
    Flux<ChatDelta> stream(ChatRequest request);

    /**
     * 文本嵌入（RAG / 诊断向量检索）。
     * <p>{@link EmbeddingRequest} 携带每请求的 model / baseUrl / apiKey，
     * 非空时覆盖适配器全局默认，实现「直连云端嵌入模型」。</p>
     */
    default float[] embed(EmbeddingRequest request) {
        throw new UnsupportedOperationException("embedding not supported by provider: " + provider());
    }

    /**
     * 统一对话请求。
     * <p>{@code history} 为多轮历史（按时间顺序的既往 user/assistant 消息），
     * 用于「会话有记忆」——无历史时为空列表（兼容单轮调用）。</p>
     * <p>{@code baseUrl}/{@code apiKey} 为每请求凭证（按智能体填写的模型绑定解析而来），
     * 非空时覆盖适配器的全局默认值，实现「直连厂商、无需改项目文件」。</p>
     */
    record ChatRequest(
            String model,
            String systemPrompt,
            String userMessage,
            Double temperature,
            Integer maxTokens,
            Map<String, Object> extra,
            List<ChatMessage> history,
            String baseUrl,
            String apiKey
    ) {
        /** 兼容旧调用：无历史、无每请求凭证。 */
        public ChatRequest(String model, String systemPrompt, String userMessage,
                           Double temperature, Integer maxTokens, Map<String, Object> extra, List<ChatMessage> history) {
            this(model, systemPrompt, userMessage, temperature, maxTokens, extra, history, null, null);
        }

        /** 兼容旧调用：无历史列表。 */
        public ChatRequest(String model, String systemPrompt, String userMessage,
                           Double temperature, Integer maxTokens, Map<String, Object> extra) {
            this(model, systemPrompt, userMessage, temperature, maxTokens, extra, List.of(), null, null);
        }

        public static ChatRequest of(String model, String userMessage) {
            return new ChatRequest(model, null, userMessage, 0.7, null, Map.of());
        }
    }

    /**
     * 通用对话消息（多轮历史项）。
     *
     * @param role    system / user / assistant
     * @param content 文本内容
     */
    record ChatMessage(String role, String content) {
    }

    /**
     * 文本嵌入请求（每请求凭证，供 {@link #embed(EmbeddingRequest)} 直连云端嵌入模型）。
     *
     * @param model   嵌入模型名（如 BAAI/bge-m3 / text-embedding-3-small）
     * @param input   待向量化文本
     * @param baseUrl 直连端点（非空则覆盖全局默认）
     * @param apiKey  凭证（非空则覆盖全局默认）
     */
    record EmbeddingRequest(String model, String input, String baseUrl, String apiKey) {
    }

    /**
     * 统一对话响应。
     */
    record ChatResponse(
            String content,
            int promptTokens,
            int completionTokens,
            double costUsd,
            long latencyMs
    ) {
    }

    /**
     * 流式增量。
     */
    record ChatDelta(
            String text,
            boolean finished,
            ChatResponse aggregate
    ) {
    }
}