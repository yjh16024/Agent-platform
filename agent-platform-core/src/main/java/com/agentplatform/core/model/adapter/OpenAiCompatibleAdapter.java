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
import java.util.LinkedHashMap;
import java.util.List;
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
    /** 是否请求「深度思考」（仅对 deepseek 生效，见 buildChatBody）。 */
    private final boolean thinkingEnabled;

    /** 兼容旧调用：默认请求思考模式。 */
    public OpenAiCompatibleAdapter(String providerName, String baseUrl, String apiKey,
                                   String userAgent, OkHttpClient httpClient) {
        this(providerName, baseUrl, apiKey, userAgent, httpClient, true);
    }

    public OpenAiCompatibleAdapter(String providerName, String baseUrl, String apiKey,
                                   String userAgent, OkHttpClient httpClient, boolean thinkingEnabled) {
        this.providerName = providerName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.userAgent = userAgent == null || userAgent.isBlank() ? "agent-platform/1.0" : userAgent;
        this.httpClient = httpClient;
        this.thinkingEnabled = thinkingEnabled;
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
        log.info("[model:{}] chat 请求 baseUrl={} url={} bearer={}",
                providerName, base, url, key != null && !key.isBlank());
        try (Response response = postWithThinkingFallback(url, req, key, false)) {
            if (!response.isSuccessful()) {
                String err = readBody(response);
                log.warn("[model:{}] 上游返回 HTTP {}: {}", providerName, response.code(), err);
                throw new BizException("MODEL_UPSTREAM_ERROR",
                        "上游模型调用失败 HTTP " + response.code() + ": " + err);
            }
            JsonNode node = JsonUtils.toJsonNode(response.body().string());
            JsonNode message = node.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            // 思考过程：各家字段名不同，统一归一化（见 extractReasoning）
            String reasoning = extractReasoning(message);
            int promptTokens = node.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = node.path("usage").path("completion_tokens").asInt(0);
            List<ModelAdapter.ToolCall> toolCalls = parseToolCalls(message);
            log.info("[model:{}] chat 完成 来源=上游 latencyMs={} toolCalls={}",
                    providerName, System.currentTimeMillis() - start, toolCalls.size());
            return new ChatResponse(content, promptTokens, completionTokens, 0.0,
                    System.currentTimeMillis() - start, toolCalls, reasoning);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("MODEL_UPSTREAM_ERROR", "模型调用网络异常: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatDelta> stream(ChatRequest req) {
        return Flux.create(sink -> {
            String url = buildUrl(effectiveBaseUrl(req), "/v1/chat/completions");
            try (Response response = postWithThinkingFallback(url, req, effectiveApiKey(req), true)) {
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
        if (data.isEmpty()) {
            // 空的 data 帧（部分网关的心跳会发 `data:`）。它不是错误，但**不能送去解析** ——
            // Jackson 对空串抛 MismatchedInputException，会顺着 Flux 冒到调用方，
            // 用户看到的是一条 "Failed to parse JSON"，而正文可能已经流完一大半了。
            return;
        }
        if ("[DONE]".equals(data)) {
            sink.complete();
            return;
        }
        JsonNode node;
        try {
            node = JsonUtils.toJsonNode(data);
        } catch (Exception e) {
            /*
             * 上游偶发非 JSON 帧（心跳、乱入的纯文本、被截断的半帧）。
             *
             * 这里**必须兜住**：一帧解析失败不该炸掉整个流 —— 那会让用户看到
             * "Failed to parse JSON" 这种完全指不出问题的提示，而且已经流出的内容也白费了。
             * 留痕后跳过这一帧继续读，是唯一合理的处理（与 AnthropicAdapter 保持一致）。
             * 2026-09-28 实际踩到：用户测试改文件功能时报的就是这条。
             */
            log.warn("[model:{}] 忽略非 JSON 的 SSE 数据帧：{}",
                    providerName, data.length() > 200 ? data.substring(0, 200) + "…" : data);
            return;
        }
        /*
         * 用量帧（2026-10-19）：开了 `stream_options.include_usage` 之后，上游会在最后一帧
         * 单独返回 `{choices: [], usage: {...}}` —— **choices 是空数组**，
         * 所以它既不是正文帧也不是思考帧，必须单独处理。
         *
         * 这里发一个**带 aggregate 的终结帧**：`AgentRuntimeService` 的 streamUsage
         * 正是从 `ChatDelta.aggregate` 里取的（见其 doOnNext）。没有这一步，
         * 流式链路的 token 用量永远是 0。
         */
        JsonNode usageNode = node.path("usage");
        if (usageNode.isObject() && usageNode.has("prompt_tokens")) {
            sink.next(new ChatDelta("", true, new ChatResponse(
                    "",
                    usageNode.path("prompt_tokens").asInt(0),
                    usageNode.path("completion_tokens").asInt(0),
                    0.0, 0L, java.util.List.of())));
            return;
        }

        JsonNode delta = node.path("choices").path(0).path("delta");
        // 思考增量先发（与正文互斥：同一帧只有一个非空），字段名归一化见 extractReasoning
        String reasoning = extractReasoning(delta);
        if (!reasoning.isEmpty()) {
            sink.next(new ChatDelta("", false, null, reasoning));
        }
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

    /** 默认带上思考参数。 */
    private Map<String, Object> buildChatBody(ChatRequest req, boolean stream) {
        return buildChatBody(req, stream, true);
    }

    /**
     * @param withThinking 是否带厂商的思考参数。上游 400 时会被降级为 false 重试一次
     *                     （见 {@link #postWithThinkingFallback}）。
     */
    private Map<String, Object> buildChatBody(ChatRequest req, boolean stream, boolean withThinking) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("stream", stream);
        if (stream) {
            /*
             * OpenAI 兼容协议：**流式默认不返回 usage**，必须显式要求。
             *
             * 不加这一行的后果非常隐蔽：回答一切正常、token 统计恒为 0，
             * 于是"本轮消耗"类界面与插件的数字永远是 0（或者干脆不显示）。
             * 2026-10-19 做「本轮消耗」插件时才发现平台一直没要求过它。
             */
            body.put("stream_options", java.util.Map.of("include_usage", true));
        }
        if (req.temperature() != null) {
            body.put("temperature", req.temperature());
        }
        if (req.maxTokens() != null) {
            body.put("max_tokens", req.maxTokens());
        }
        /*
         * 思考模式（2026-10-19）。
         *
         * 不传厂商的思考参数时，模型**根本不思考** → 响应里没有 `reasoning_content`
         * → 界面上的「思考过程」永远是空的，而对话本身一切正常（所以一直没被发现）。
         * 但字段名各厂商不同（DeepSeek 是 `thinking`、Qwen 是 `enable_thinking`、
         * OpenAI 是 `reasoning_effort`），发错会 400 —— 统一交给 `ThinkingParams` 按 provider 映射，
         * 未知 provider 不发。真的被 400 拒绝时还有 `postWithThinkingFallback` 兜底。
         */
        if (thinkingEnabled && withThinking) {
            body.putAll(ThinkingParams.forProvider(providerName));
        }
        java.util.List<Map<String, Object>> messages = new java.util.ArrayList<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", req.systemPrompt()));
        }
        // 多轮历史（既有 user/assistant 消息，按时间顺序；含原生 function calling 的两跳消息）
        if (req.history() != null) {
            for (ModelAdapter.ChatMessage m : req.history()) {
                Map<String, Object> msg = toOpenAiMessage(m);
                if (msg != null) {
                    messages.add(msg);
                }
            }
        }
        // userMessage 允许为空：工具循环的第二轮起，本轮消息已全部通过 history 传入
        // （模型请求工具 → 我们把 assistant(tool_calls) + tool 结果放进 history，
        //  此时不该再补一条空的 user 消息，否则重发用户提问会把上下文搞乱）。
        if (req.userMessage() != null && !req.userMessage().isBlank()) {
            /*
             * 多模态（2026-10-20 修复）。
             *
             * 图片原先被塞进 `extra.images`，而 `extra` 是**原样合并进请求体**的通用口袋 ——
             * 厂商不认识 `images` 这个字段，直接忽略。表现就是"图上传成功、模型说看不到"。
             * 这里改成标准的 OpenAI 多模态消息：
             *   content: [{type:"text"},{type:"image_url", image_url:{url:"data:<mime>;base64,..."}}]
             */
            java.util.List<Map<String, String>> images = imagesOf(req.extra());
            Object content = images.isEmpty()
                    ? req.userMessage()
                    : multimodalContent(req.userMessage(), images);
            messages.add(Map.of("role", "user", "content", content));
        }
        body.put("messages", messages);
        // 工具声明（function calling）——tools 为空时不启用，保证旧行为不变
        if (req.tools() != null && !req.tools().isEmpty()) {
            java.util.List<Map<String, Object>> tools = new java.util.ArrayList<>();
            for (ModelAdapter.ToolSpec spec : req.tools()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", spec.name());
                if (spec.description() != null && !spec.description().isBlank()) {
                    fn.put("description", spec.description());
                }
                if (spec.inputSchema() != null) {
                    fn.put("parameters", spec.inputSchema());
                }
                tools.add(Map.of("type", "function", "function", fn));
            }
            body.put("tools", tools);
            if (req.toolChoice() != null && !req.toolChoice().isBlank()) {
                body.put("tool_choice", req.toolChoice());
            }
        }
        if (req.extra() != null) {
            /*
             * `images` 是**平台内部键**（多模态图片，`{mimeType, base64}` 列表），
             * 上面已转成标准 image_url 内容块，这里必须**排除** ——
             * 原样下发只会让厂商收到一个不认识的字段（多数实现静默忽略，少数报 400）。
             */
            for (Map.Entry<String, Object> e : req.extra().entrySet()) {
                if (!"images".equals(e.getKey())) {
                    body.put(e.getKey(), e.getValue());
                }
            }
        }
        return body;
    }

    /** 从 `extra` 里取出图片数据（`{mimeType, base64}` 列表）；无图返回空。 */
    private static java.util.List<Map<String, String>> imagesOf(Map<String, Object> extra) {
        if (extra == null) {
            return java.util.List.of();
        }
        Object v = extra.get("images");
        if (!(v instanceof java.util.List<?> list)) {
            return java.util.List.of();
        }
        java.util.List<Map<String, String>> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            // `m` 是 Map<?, ?>，不能直接 getOrDefault(String, String)（泛型捕获不兼容）
            Object rawB64 = m.get("base64");
            String b64 = rawB64 == null ? "" : String.valueOf(rawB64);
            if (b64.isBlank()) {
                continue;
            }
            Object rawMime = m.get("mimeType");
            String mime = rawMime == null ? "image/png" : String.valueOf(rawMime);
            Map<String, String> one = new java.util.HashMap<>(2);
            one.put("base64", b64);
            one.put("mimeType", mime);
            out.add(one);
        }
        return out;
    }

    /** 拼 OpenAI 多模态 content（text + 若干 image_url）。 */
    private static java.util.List<Map<String, Object>> multimodalContent(
            String text, java.util.List<Map<String, String>> images) {
        java.util.List<Map<String, Object>> parts = new java.util.ArrayList<>(1 + images.size());
        parts.add(Map.of("type", "text", "text", text));
        for (Map<String, String> img : images) {
            parts.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "data:" + img.get("mimeType") + ";base64," + img.get("base64"))));
        }
        return parts;
    }

    /**
     * 把统一消息转成 OpenAI 协议的消息对象（含原生 function calling 的两跳）。
     *
     * <p>为什么不用 {@code Map.of(...)}：它**不接受 null 值**，而
     * {@code role=assistant} 且只请求工具时 {@code content} 恰恰是 null（这正是 OpenAI 的合法形态）；
     * {@code role=tool} 的消息还需要额外的 {@code tool_call_id} 字段 —— 键不同，
     * 只能是键值不固定的 {@code Map<String,Object>}。</p>
     *
     * <p>三种形态：</p>
     * <ul>
     *   <li>{@code assistant} + toolCalls → {@code {role, content(可 null), tool_calls[]}}；</li>
     *   <li>{@code tool} → {@code {role, tool_call_id, content}}（content 不可为 null，退化成空串）；</li>
     *   <li>其余 → {@code {role, content}}。</li>
     * </ul>
     *
     * <p>（包级可见而非 private：这是协议正确性最容易出错的一环，需要单测覆盖。）</p>
     */
    Map<String, Object> toOpenAiMessage(ModelAdapter.ChatMessage m) {
        if (m == null) {
            return null;
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        if (m.hasToolCalls()) {
            msg.put("role", "assistant");
            msg.put("content", m.content());
            List<Map<String, Object>> calls = new java.util.ArrayList<>();
            for (ModelAdapter.ToolCall c : m.toolCalls()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", c.name());
                // OpenAI 要求 arguments 是**字符串**（JSON 文本），不是对象
                fn.put("arguments", c.arguments() == null ? "{}" : c.arguments().toString());
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", c.id());
                call.put("type", "function");
                call.put("function", fn);
                calls.add(call);
            }
            msg.put("tool_calls", calls);
            return msg;
        }
        if (m.isToolResult()) {
            msg.put("role", "tool");
            msg.put("tool_call_id", m.toolCallId() == null ? "" : m.toolCallId());
            msg.put("content", m.content() == null ? "" : m.content());
            return msg;
        }
        msg.put("role", m.role());
        msg.put("content", m.content() == null ? "" : m.content());
        return msg;
    }

    /**
     * 解析 OpenAI 响应中的 tool_calls（{@code message.tool_calls[]}）。
     */
    private List<ModelAdapter.ToolCall> parseToolCalls(JsonNode message) {
        List<ModelAdapter.ToolCall> calls = new java.util.ArrayList<>();
        JsonNode arr = message == null ? null : message.path("tool_calls");
        if (arr != null && arr.isArray()) {
            for (JsonNode tc : arr) {
                String id = tc.path("id").asText(null);
                String fnName = tc.path("function").path("name").asText(null);
                String rawArgs = tc.path("function").path("arguments").asText(null);
                if (fnName == null || fnName.isBlank()) {
                    continue;
                }
                JsonNode arguments = null;
                if (rawArgs != null && !rawArgs.isBlank()) {
                    try {
                        arguments = JsonUtils.toJsonNode(rawArgs);
                    } catch (Exception e) {
                        log.warn("[model:{}] tool_calls 参数非合法 JSON，按文本兜底: {}", providerName, e.getMessage());
                        arguments = tools.jackson.databind.node.StringNode.valueOf(rawArgs);
                    }
                }
                calls.add(new ModelAdapter.ToolCall(id, fnName, arguments));
            }
        }
        return calls;
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
     * 从响应（或流式 delta）里取思考过程，**统一各家的字段名**（2026-10-19）。
     *
     * <p>OpenAI 兼容协议只是"大致兼容"，思考字段各家不同，按优先级尝试：</p>
     * <ol>
     *   <li>{@code reasoning_content} —— DeepSeek / Qwen / GLM / Kimi / MiniMax（最常见）；</li>
     *   <li>{@code reasoning} —— OpenRouter 及部分网关的纯文本字段；</li>
     *   <li>{@code reasoning_details[]} —— OpenRouter 的结构化块（逐块取 text / summary 拼接）。</li>
     * </ol>
     *
     * <p>都取不到就是空串 —— 普通模型本来就没有，不是错误。</p>
     */
    static String extractReasoning(JsonNode message) {
        if (message == null || message.isMissingNode()) {
            return "";
        }
        String direct = message.path("reasoning_content").asText("");
        if (!direct.isEmpty()) {
            return direct;
        }
        direct = message.path("reasoning").asText("");
        if (!direct.isEmpty()) {
            return direct;
        }
        JsonNode details = message.path("reasoning_details");
        if (details.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode d : details) {
                String t = d.path("text").asText("");
                if (t.isEmpty()) {
                    t = d.path("summary").asText("");
                }
                if (!t.isEmpty()) {
                    sb.append(t);
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return "";
    }

    /**
     * 发起对话请求；若上游以 **400** 拒绝且原因可能是思考参数，**去掉参数重试一次**。
     *
     * <p>为什么必须有这层兜底：思考参数是**厂商扩展**，同一个 provider 下老模型可能不认
     * （例如平台里 provider 配成 {@code openai} 却指向一个只支持 gpt-3.5 的网关）。
     * 没有降级的话，用户"换个模型"就会直接收到 400 —— 而**看不到思考过程是小事，
     * 对话不能用才是大事**。降级只在 400 时触发一次，不会掩盖 401/429 等真实错误。</p>
     */
    private Response postWithThinkingFallback(String url, ChatRequest req, String key, boolean stream)
            throws IOException {
        Map<String, Object> body = buildChatBody(req, stream, true);
        Response response = postWithRetry(url, body, key);
        if (response.code() == 400 && thinkingEnabled && ThinkingParams.hasParams(providerName)) {
            String err = readBody(response);
            response.close();
            log.warn("[model:{}] 上游 400，尝试去掉思考参数重试（provider 可能不支持该字段）：{}",
                    providerName, err == null ? "" : (err.length() > 200 ? err.substring(0, 200) : err));
            response = postWithRetry(url, buildChatBody(req, stream, false), key);
        }
        return response;
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