package com.agentplatform.core.model.adapter;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.model.ModelCapability;
import tools.jackson.databind.JsonNode;
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
    private final String userAgent;
    private final OkHttpClient httpClient;

    public AnthropicAdapter(String providerName, String baseUrl, String apiKey,
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
        try (Response response = postWithRetry(url, body, key)) {
            if (!response.isSuccessful()) {
                String err = readBody(response);
                log.warn("[model:{}] 上游 Anthropic 返回 HTTP {}: {}", providerName, response.code(), err);
                throw new BizException("MODEL_UPSTREAM_ERROR",
                        "上游模型调用失败 HTTP " + response.code() + ": " + err);
            }
            JsonNode node = JsonUtils.toJsonNode(response.body().string());
            String content = extractText(node);
            List<ModelAdapter.ToolCall> toolCalls = extractToolUses(node);
            int promptTokens = node.path("usage").path("input_tokens").asInt(0);
            int completionTokens = node.path("usage").path("output_tokens").asInt(0);
            return new ChatResponse(content, promptTokens, completionTokens, 0.0,
                    System.currentTimeMillis() - start, toolCalls);
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
            try (Response response = postWithRetry(url, body, effectiveApiKey(req))) {
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

    /** 提取响应中的 tool_use blocks（Anthropic 工具调用以 content block 返回）。 */
    private List<ModelAdapter.ToolCall> extractToolUses(JsonNode node) {
        List<ModelAdapter.ToolCall> calls = new ArrayList<>();
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    String id = block.path("id").asText(null);
                    String name = block.path("name").asText(null);
                    JsonNode input = block.path("input");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    calls.add(new ModelAdapter.ToolCall(id, name,
                            input == null || input.isMissingNode() ? null : input));
                }
            }
        }
        return calls;
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
        // 多轮历史：system 已在顶层，历史与当前用户消息归一为 user/assistant 消息序列。
        // 原生 function calling 的两跳在这里的形态与 OpenAI 不同（见 toAnthropicMessages）：
        // assistant 的 tool_use 是 content block；工具结果**不是** role=tool，
        // 而是塞进一条 role=user 消息的 tool_result block 里，且用 tool_use_id 对齐。
        List<Map<String, Object>> messages = toAnthropicMessages(req.history());
        // userMessage 允许为空：工具循环第二轮起，本轮全部消息已由 history 携带
        if (req.userMessage() != null && !req.userMessage().isBlank()) {
            messages.add(Map.of("role", "user", "content", req.userMessage()));
        }
        body.put("messages", messages);
        // 工具声明（function calling）——Anthropic 格式为平铺数组：name/description/input_schema
        if (req.tools() != null && !req.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ModelAdapter.ToolSpec spec : req.tools()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", spec.name());
                if (spec.description() != null && !spec.description().isBlank()) {
                    tool.put("description", spec.description());
                }
                if (spec.inputSchema() != null) {
                    tool.put("input_schema", spec.inputSchema());
                }
                tools.add(tool);
            }
            body.put("tools", tools);
            if (req.toolChoice() != null && !req.toolChoice().isBlank()) {
                body.put("tool_choice", Map.of("type", "auto"));
            }
        }
        if (req.extra() != null) {
            body.putAll(req.extra());
        }
        return body;
    }

    /**
     * 把统一消息序列转成 Anthropic 的 {@code messages[]}。
     *
     * <p>与 OpenAI 的三处关键差异（写错任何一处都会被上游 400 拒绝）：</p>
     * <ol>
     *   <li><b>没有 {@code role="tool"}</b> —— Anthropic 只有 user / assistant 两个角色。
     *       工具结果必须作为**一条 {@code role="user"} 消息**发送，其 content 为该消息的
     *       {@code tool_result} block，用 {@code tool_use_id} 指回上次调用；</li>
     *   <li><b>连续的 tool_result 必须合并进同一条 user 消息</b>（Anthropic 要求同一轮的
     *       多个工具结果成组出现），所以这里用 pending 列表攒着、遇到非 tool 消息才 flush；</li>
     *   <li><b>{@code input} 传对象而不是字符串</b>（与 OpenAI 的 {@code arguments} 恰好相反）。</li>
     * </ol>
     *
     * <p>（包级可见而非 private：这是协议正确性最容易出错的一环，需要单测覆盖。）</p>
     */
    List<Map<String, Object>> toAnthropicMessages(List<ChatMessage> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (history == null) {
            return messages;
        }
        List<Map<String, Object>> pendingToolResults = null;
        for (ChatMessage m : history) {
            if (m == null || "system".equalsIgnoreCase(m.role())) {
                continue; // Anthropic 的 system 在顶层字段，跳过消息里的 system
            }
            if (m.isToolResult()) {
                if (pendingToolResults == null) {
                    pendingToolResults = new ArrayList<>();
                }
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_result");
                block.put("tool_use_id", m.toolCallId() == null ? "" : m.toolCallId());
                block.put("content", m.content() == null ? "" : m.content());
                pendingToolResults.add(block);
                continue;
            }
            // 遇到非工具消息 → 先把挂起的工具结果冲出去（保证它们成组且顺序正确）
            if (pendingToolResults != null) {
                messages.add(Map.of("role", "user", "content", pendingToolResults));
                pendingToolResults = null;
            }
            messages.add(toAnthropicMessage(m));
        }
        if (pendingToolResults != null) {
            messages.add(Map.of("role", "user", "content", pendingToolResults));
        }
        return messages;
    }

    /** 单条消息 → Anthropic 形态（assistant 请求工具时 content 是 block 数组）。 */
    private Map<String, Object> toAnthropicMessage(ChatMessage m) {
        if (m.hasToolCalls()) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            if (m.content() != null && !m.content().isBlank()) {
                blocks.add(Map.of("type", "text", "text", m.content()));
            }
            for (ModelAdapter.ToolCall c : m.toolCalls()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", c.id() == null ? "" : c.id());
                block.put("name", c.name());
                // 注意：这里与 OpenAI 相反 —— input 必须是**对象**，不是 JSON 字符串
                block.put("input", c.arguments() == null ? Map.of() : c.arguments());
                blocks.add(block);
            }
            return Map.of("role", "assistant", "content", blocks);
        }
        return Map.of("role", m.role(), "content", m.content() == null ? "" : m.content());
    }

    private Response post(String url, Map<String, Object> body, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException("BAD_REQUEST", "缺少 Anthropic API Key，无法构造 x-api-key 头");
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JsonUtils.toJson(body), JSON))
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", ANTHROPIC_VERSION)
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
