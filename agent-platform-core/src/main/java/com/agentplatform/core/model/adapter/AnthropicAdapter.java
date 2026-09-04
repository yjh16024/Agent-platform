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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Messages API 适配器（对接 Claude 直连 / Bailian「应用」等
 * Anthropic 协议端点，修复 README 遗留的「Anthropic 端点未接入」缺口）。
 * <p>
 * 协议要点（与 OpenAI 兼容协议不同）：
 * <ul>
 *   <li>端点：{@code POST {baseUrl}/v1/messages}；</li>
 *   <li>鉴权：{@code x-api-key} 头 + {@code anthropic-version: 2023-06-01}；</li>
 *   <li>system 提示词走顶层字段，用户消息为 {@code messages:[{role,content}]}；</li>
 *   <li>SSE 事件：{@code content_block_delta / message_stop}。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class AnthropicAdapter implements ModelAdapter {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String providerName;
    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient httpClient;

    public AnthropicAdapter(String providerName, String baseUrl, String apiKey, OkHttpClient httpClient) {
        this.providerName = providerName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    @Override
    public String provider() {
        return providerName;
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL, ModelCapability.VISION);
    }

    @Override
    public ChatResponse chat(ChatRequest req) {
        long start = System.currentTimeMillis();
        String base = effectiveBaseUrl(req);
        String key = effectiveApiKey(req);
        String url = buildUrl(base, "/v1/messages");
        Map<String, Object> body = buildBody(req, false);
        log.info("[model:{}] anthropic chat baseUrl={} url={} hasKey={}",
                providerName, base, url, key != null && !key.isBlank());
        try (Response response = post(url, body, key)) {
            if (!response.isSuccessful()) {
                String err = readBody(response);
                log.warn("[model:{}] 上游 Anthropic 返回 HTTP {}: {}", providerName, response.code(), err);
                throw new BizException("MODEL_UPSTREAM_ERROR",
                        "上游模型调用失败 HTTP " + response.code() + ": " + err);
            }
            JsonNode node = JsonUtils.toJsonNode(response.body().string());
            String content = extractText(node);
            int promptTokens = node.path("usage").path("input_tokens").asInt(0);
            int completionTokens = node.path("usage").path("output_tokens").asInt(0);
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
            Map<String, Object> body = buildBody(req, true);
            String url = buildUrl(effectiveBaseUrl(req), "/v1/messages");
            try (Response response = post(url, body, effectiveApiKey(req))) {
                if (!response.isSuccessful()) {
                    String err = readBody(response);
                    log.warn("[model:{}] 上游 Anthropic 流式返回 HTTP {}: {}", providerName, response.code(), err);
                    sink.error(new BizException("MODEL_UPSTREAM_ERROR",
                            "上游模型流式调用失败 HTTP " + response.code() + ": " + err));
                    return;
                }
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

    /**
     * 解析 Anthropic SSE 增量：
     * {@code event: content_block_delta} → {@code data:{"delta":{"type":"text_delta","text":...}}}。
     */
    private void handleSseLine(String line, FluxSink<ChatDelta> sink) {
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty()) {
            return;
        }
        try {
            JsonNode node = JsonUtils.toJsonNode(data);
            String type = node.path("type").asText("");
            if ("content_block_delta".equals(type)) {
                String text = node.path("delta").path("text").asText("");
                if (!text.isEmpty()) {
                    sink.next(new ChatDelta(text, false, null));
                }
            } else if ("message_stop".equals(type)) {
                sink.next(new ChatDelta("", true, null));
            }
        } catch (Exception e) {
            log.warn("[model:{}] 跳过无法解析的 SSE 帧: {}", providerName, data);
        }
    }

    /** 提取响应文本（兼容 text block 与 thinking block 混合）。 */
    private String extractText(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Object> buildBody(ChatRequest req, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("max_tokens", req.maxTokens() == null ? 2048 : req.maxTokens());
        body.put("stream", stream);
        if (req.temperature() != null) {
            body.put("temperature", req.temperature());
        }
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            body.put("system", req.systemPrompt());
        }
        // 多轮历史：system 已在顶层，历史与当前用户消息归一为 user/assistant 消息序列
        List<Map<String, String>> messages = new ArrayList<>();
        if (req.history() != null) {
            for (ChatMessage m : req.history()) {
                if ("system".equals(m.role())) {
                    continue; // Anthropic 的 system 在顶层字段，跳过消息里的 system
                }
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
            throw new BizException("BAD_REQUEST", "缺少 Anthropic API Key，无法构造 x-api-key 头");
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JsonUtils.toJson(body), JSON))
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", ANTHROPIC_VERSION);
        return httpClient.newCall(builder.build()).execute();
    }

    /**
     * 拼接 URL：baseUrl 若已含 {@code /v1} 则不再重复（与 OpenAI 适配器同语义）。
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
            p = p.substring(3);
        }
        return b + p;
    }

    private String readBody(Response response) {
        try {
            if (response.body() != null) {
                return response.body().string();
            }
        } catch (IOException ignored) {
            // 已在上层 catch 处理，这里退化为状态行
        }
        return response.message();
    }

    private String effectiveBaseUrl(ChatRequest req) {
        return req.baseUrl() != null && !req.baseUrl().isBlank() ? req.baseUrl().trim() : this.baseUrl;
    }

    private String effectiveApiKey(ChatRequest req) {
        return req.apiKey() != null && !req.apiKey().isBlank() ? req.apiKey().trim() : this.apiKey;
    }
}
