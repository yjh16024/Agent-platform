package com.agentplatform.core.model.adapter;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.model.ModelCapability;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容协议适配器（对接 LiteLLM Proxy 或直连 DeepSeek 等厂商）。
 * <p>
 * 通过 OpenAI 兼容的 Chat Completions 协议屏蔽协议差异。Java 侧以标准
 * Chat Completions 协议对接：baseUrl 为用户直连端点（如
 * {@code https://api.deepseek.com/v1}），未配置时回退到 LiteLLM 全局网关。
 * </p>
 */
@Slf4j
public class OpenAiCompatibleAdapter implements ModelAdapter {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String providerName;
    private final String baseUrl;
    private final String apiKey;
    private final String userAgent;
    private final OkHttpClient httpClient;

    public OpenAiCompatibleAdapter(String providerName, String baseUrl, String apiKey,
                                   String userAgent, OkHttpClient httpClient) {
        this.providerName = providerName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.userAgent = userAgent == null || userAgent.isBlank() ? "agent-platform/1.0" : userAgent;
        this.httpClient = httpClient;
    }

    @Override
    public String provider() {
        return providerName;
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL, ModelCapability.VISION, ModelCapability.EMBEDDING);
    }

    @Override
    public ChatResponse chat(ChatRequest req) {
        long start = System.currentTimeMillis();
        String base = effectiveBaseUrl(req);
        String key = effectiveApiKey(req);
        String url = buildUrl(base, "/v1/chat/completions");
        Map<String, Object> body = buildChatBody(req, false);
        log.info("[model:{}] chat 请求 baseUrl={} url={} bearer={}",
                providerName, base, url, key != null && !key.isBlank());
        try (Response response = postWithRetry(url, body, key)) {
            if (!response.isSuccessful()) {
                String err = readBody(response);
                log.warn("[model:{}] 上游返回 HTTP {}: {}", providerName, response.code(), err);
                throw new BizException("MODEL_UPSTREAM_ERROR",
                        "上游模型调用失败 HTTP " + response.code() + ": " + err);
            }
            JsonNode node = JsonUtils.toJsonNode(response.body().string());
            String content = node.path("choices").path(0).path("message").path("content").asText("");
            int promptTokens = node.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = node.path("usage").path("completion_tokens").asInt(0);
            log.info("[model:{}] chat 完成 来源=上游 latencyMs={}", providerName, System.currentTimeMillis() - start);
            return new ChatResponse(content, promptTokens, completionTokens, 0.0,
                    System.currentTimeMillis() - start);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("MODEL_UPSTREAM_ERROR", "模型调用网络异常: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatDelta> stream(ChatRequest req) {
        return Flux.create(sink -> {
            Map<String, Object> body = buildChatBody(req, true);
            String url = buildUrl(effectiveBaseUrl(req), "/v1/chat/completions");
            try (Response response = postWithRetry(url, body, effectiveApiKey(req))) {
                if (!response.isSuccessful()) {
                    String err = readBody(response);
                    log.warn("[model:{}] 上游流式返回 HTTP {}: {}", providerName, response.code(), err);
                    sink.error(new BizException("MODEL_UPSTREAM_ERROR",
                            "上游模型流式调用失败 HTTP " + response.code() + ": " + err));
                    return;
                }
                // 逐行解析 SSE
                assert response.body() != null;
                var reader = response.body().charStream();
                StringBuilder line = new StringBuilder();
                int ch;
                while ((ch = reader.read()) != -1) {
                    if (ch == '\n') {
                        handleSseLine(line.toString(), sink);
                        line.setLength(0);
                    } else {
                        line.append((char) ch);
                    }
                }
                sink.complete();
            } catch (IOException e) {
                sink.error(new BizException("MODEL_UPSTREAM_ERROR", "模型流式网络异常: " + e.getMessage(), e));
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void handleSseLine(String line, FluxSink<ChatDelta> sink) {
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) {
            sink.complete();
            return;
        }
        JsonNode node = JsonUtils.toJsonNode(data);
        JsonNode delta = node.path("choices").path(0).path("delta");
        String content = delta.has("content") ? delta.path("content").asText() : "";
        if (!content.isEmpty()) {
            sink.next(new ChatDelta(content, false, null));
        }
        // 结束帧
        if (node.path("choices").path(0).path("finish_reason").asText() != null
                && !node.path("choices").path(0).path("finish_reason").asText().isEmpty()) {
            sink.next(new ChatDelta("", true, null));
        }
    }

    @Override
    public float[] embed(EmbeddingRequest req) {
        String base = req.baseUrl() != null && !req.baseUrl().isBlank() ? req.baseUrl().trim() : this.baseUrl;
        String key = req.apiKey() != null && !req.apiKey().isBlank() ? req.apiKey().trim() : this.apiKey;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("input", req.input());
        String url = buildUrl(base, "/v1/embeddings");
        log.info("[model:{}] embed 请求 model={} baseUrl={} bearer={}",
                providerName, req.model(), base, key != null && !key.isBlank());
        try (Response response = postWithRetry(url, body, key)) {
            if (!response.isSuccessful()) {
                String err = readBody(response);
                throw new BizException("MODEL_UPSTREAM_ERROR",
                        "Embedding 调用失败 HTTP " + response.code() + ": " + err);
            }
            JsonNode node = JsonUtils.toJsonNode(response.body().string());
            JsonNode embedding = node.path("data").path(0).path("embedding");
            float[] vec = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vec[i] = embedding.get(i).floatValue();
            }
            return vec;
        } catch (IOException e) {
            throw new BizException("MODEL_UPSTREAM_ERROR", "Embedding 网络异常: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildChatBody(ChatRequest req, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("stream", stream);
        if (req.temperature() != null) {
            body.put("temperature", req.temperature());
        }
        if (req.maxTokens() != null) {
            body.put("max_tokens", req.maxTokens());
        }
        java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", req.systemPrompt()));
        }
        // 多轮历史（既有 user/assistant 消息，按时间顺序）
        if (req.history() != null) {
            for (ModelAdapter.ChatMessage m : req.history()) {
                messages.add(Map.of("role", m.role(), "content", m.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", req.userMessage()));
        body.put("messages", messages);
        if (req.extra() != null) {
            body.putAll(req.extra());
        }
        return body;
    }

    private Response post(String url, Map<String, Object> body, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException("BAD_REQUEST", "缺少模型 API Key，无法构造 Authorization Bearer 头");
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JsonUtils.toJson(body), JSON))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("User-Agent", userAgent);
        return httpClient.newCall(builder.build()).execute();
    }

    /**
     * 对瞬时错误（429 限流 / 5xx）做短退避重试（1s、2s，最多 2 次），
     * 其他状态码（400/401/402/403/404…）原样返回，不吞错误。
     */
    private Response postWithRetry(String url, Map<String, Object> body, String apiKey) throws IOException {
        int attempt = 0;
        while (true) {
            Response response = post(url, body, apiKey);
            if (response.isSuccessful() || attempt >= 2 || !isTransient(response.code())) {
                return response;
            }
            String err = readBody(response);
            response.close();
            long waitMs = 1000L << attempt; // 1s → 2s
            log.warn("[model:{}] 上游瞬时错误 HTTP {}，{}ms 后重试（第 {} 次）: {}",
                    providerName, response.code(), waitMs, attempt + 1,
                    err == null ? "" : (err.length() > 300 ? err.substring(0, 300) : err));
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("retry interrupted", ie);
            }
            attempt++;
        }
    }

    private static boolean isTransient(int code) {
        return code == 429 || code >= 500;
    }

    /**
     * 拼接请求 URL：去除 baseUrl 尾部斜杠，避免重复 {@code /v1}。
     * <p>baseUrl={@code https://api.deepseek.com/v1} + path={@code /v1/chat/completions}
     * → {@code https://api.deepseek.com/v1/chat/completions}；baseUrl 不含版本段时保留
     * {@code /v1} 前缀，兼容 OpenAI/LiteLLM 端点。</p>
     */
    private String buildUrl(String baseUrl, String path) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.isBlank()) {
            throw new BizException("BAD_REQUEST", "模型 baseUrl 未配置，无法发起请求");
        }
        String p = path == null ? "" : path;
        if (p.startsWith("/v1/") && b.toLowerCase().endsWith("/v1")) {
            p = p.substring(3); // 去掉会重复的 /v1
        }
        return b + p;
    }

    private String readBody(Response response) {
        try {
            if (response.body() != null) {
                return response.body().string();
            }
        } catch (IOException ignored) {
            // 已在上层 catch 处理网络异常，这里退化为状态行
        }
        return response.message();
    }

    /** 每请求生效的 baseUrl：请求显式指定优先，否则用构造时的全局默认。 */
    private String effectiveBaseUrl(ChatRequest req) {
        return req.baseUrl() != null && !req.baseUrl().isBlank() ? req.baseUrl().trim() : this.baseUrl;
    }

    /** 每请求生效的 apiKey：请求显式指定优先，否则用构造时的全局默认。 */
    private String effectiveApiKey(ChatRequest req) {
        return req.apiKey() != null && !req.apiKey().isBlank() ? req.apiKey().trim() : this.apiKey;
    }
}