package com.agentplatform.core.model.springai;

import com.agentplatform.framework.api.Tool;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.ModelCapability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring AI 版模型适配器（实现项目自有的 {@link ModelAdapter} 契约）。
 * <p>
 * 定位：只替换「HTTP 协议与厂商差异」这一层（OpenAI 兼容 / Anthropic 各一个 ChatModel），
 * 上层 {@code ModelRouter} 路由、{@code ModelBindingService} 三级凭证回退、{@code ModelKeyCrypto}
 * 加密与掩码逻辑<b>一行未改</b>。
 * </p>
 * <p>
 * 每请求凭证（{@code ChatRequest#baseUrl()/#apiKey()}）优先，缺省时用工厂全局默认值，
 * 与原有 {@code OpenAiCompatibleAdapter} 语义一致。
 * </p>
 * <p>
 * 工具调用：启用 Spring AI 且存在工具桥接时走<b>原生 tool-role 循环</b>
 * （assistant(tool_calls) → tool(result) → …），轮次上限与旧实现一致（5 轮）；
 * 未启用工具时退化为单次调用。
 * </p>
 */
@Slf4j
public class SpringAiModelAdapter implements ModelAdapter {

    /** 工具调用循环最大轮数（与 AgentRuntimeService 保持一致，防模型死循环）。 */
    static final int MAX_TOOL_ROUNDS = 5;

    private final String provider;
    private final SpringAiChatModelFactory factory;
    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final SpringAiToolBridge toolBridge;

    public SpringAiModelAdapter(String provider, SpringAiChatModelFactory factory,
                                String defaultBaseUrl, String defaultApiKey) {
        this(provider, factory, defaultBaseUrl, defaultApiKey, null);
    }

    public SpringAiModelAdapter(String provider, SpringAiChatModelFactory factory,
                                String defaultBaseUrl, String defaultApiKey, SpringAiToolBridge toolBridge) {
        this.provider = provider;
        this.factory = factory;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultApiKey = defaultApiKey;
        this.toolBridge = toolBridge;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long t0 = System.currentTimeMillis();
        ChatModel model = modelFor(request);

        boolean useTools = toolBridge != null && request.tools() != null && !request.tools().isEmpty();
        if (!useTools) {
            org.springframework.ai.chat.model.ChatResponse resp =
                    model.call(new Prompt(messages(request), options(request, false)));
            return toPlatformResponse(resp, t0, null, null);
        }

        // 原生 tool-role 工具循环（自行控制轮次上限）
        List<Message> conversation = messages(request);
        ChatOptions opts = options(request, true);
        int[] usage = new int[]{0, 0};
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            org.springframework.ai.chat.model.ChatResponse resp = model.call(new Prompt(conversation, opts));
            usage[0] += promptTokens(resp);
            usage[1] += completionTokens(resp);

            AssistantMessage assistant = resp.getResult() == null ? null : resp.getResult().getOutput();
            List<AssistantMessage.ToolCall> calls = assistant == null ? null : assistant.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                return toPlatformResponse(resp, t0, usage[0], usage[1]);
            }
            conversation.add(assistant);
            conversation.add(toolBridge.execute(calls));
            log.debug("Spring AI tool round {} executed {} call(s)", round, calls.size());
        }
        log.warn("Spring AI tool loop reached max rounds {}", MAX_TOOL_ROUNDS);
        return new ChatResponse("（工具调用超过最大轮次，已停止；请重试或补充说明）",
                usage[0], usage[1], 0.0, System.currentTimeMillis() - t0, List.of());
    }

    /**
     * 流式对话。
     *
     * <h3>★ done 帧为什么要带上 usage（2026-09-29）</h3>
     * 上游的用量只能从流的<b>末尾</b>拿到（OpenAI 兼容协议在最后一块带 usage，或由 metadata 给出）。
     * 而改造前这里给 done 帧传的是 {@code null}，于是所有流式调用在平台侧<b>用量恒为 0</b> ——
     * 而流式恰恰是默认用法，结果是"用量统计只在关掉流式时才准确"这种没人会想到的表现
     * （见 {@code AgentRuntimeService} 里 {@code final int[] usage = {0, 0}} 的由来）。
     *
     * <p>修法很轻：{@link ChatDelta} 本来就有 {@code aggregate} 字段可承载完整响应，
     * 只是没人填。这里在流的末尾把累积到的 prompt/completion tokens 装进去。</p>
     *
     * <p><b>拿不到时仍是 0</b>：不少兼容网关在流式下不返回 usage（上游行为，平台无法控制）。
     * 所以消费方（用量统计）应当把 token 视为"尽力而为"，别把 0 当成"没消耗"。</p>
     */
    @Override
    public Flux<ChatDelta> stream(ChatRequest request) {
        ChatModel model = modelFor(request);
        // 累积用量：流是逐块到来的，只有把每块的 metadata 累加起来才能得到一轮的总量
        final int[] usage = new int[]{0, 0};
        return model.stream(new Prompt(messages(request), options(request, false)))
                .doOnNext(r -> {
                    // 用量在 doOnNext 里累积（而不是 map）：下面 flatMap 会把一个 chunk 拆成多帧，
                    // 放在 map 里会重复累加。
                    usage[0] += promptTokens(r);
                    usage[1] += completionTokens(r);
                })
                .flatMap(r -> {
                    /*
                     * 思考与正文**拆成两帧**（2026-10-19）：同一个 chunk 可能同时带
                     * reasoning 与 text，而 `ChatDelta` 约定"一帧只有一种"。
                     * 不拆的后果是有思考的那一帧把正文吞掉（用户看到回答缺一段）。
                     */
                    java.util.List<ChatDelta> out = new java.util.ArrayList<>(2);
                    String reasoning = reasoningOf(r);
                    if (!reasoning.isEmpty()) {
                        out.add(new ChatDelta("", false, null, reasoning));
                    }
                    String text = textOf(r);
                    if (!text.isEmpty()) {
                        out.add(new ChatDelta(text, false, null));
                    }
                    return out.isEmpty() ? Flux.<ChatDelta>empty() : Flux.fromIterable(out);
                })
                .concatWith(Flux.defer(() -> {
                    // 用 defer 是为了拿到**此时**的累积值 —— 直接 Flux.just(...) 会在订阅前求值，
                    // 那时流还没跑，usage 还是 0。
                    ChatResponse agg = new ChatResponse("", usage[0], usage[1], 0.0, 0L, List.of());
                    return Flux.just(new ChatDelta("", true, agg));
                }))
                .onErrorResume(e -> Flux.just(new ChatDelta("", true, null)));
    }

    private ChatModel modelFor(ChatRequest request) {
        String baseUrl = request.baseUrl() != null && !request.baseUrl().isBlank() ? request.baseUrl() : defaultBaseUrl;
        String apiKey = request.apiKey() != null && !request.apiKey().isBlank() ? request.apiKey() : defaultApiKey;
        return factory.chatModel(provider, baseUrl, apiKey);
    }

    private List<Message> messages(ChatRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        if (request.history() != null) {
            for (ChatMessage m : request.history()) {
                if (m == null || m.content() == null || m.content().isBlank()) {
                    continue;
                }
                // 原生 function calling 的 tool 结果消息**不在此通道消费**：Spring AI 有它自己的
                // 工具执行 Advisor 链，本适配器只做「system / user / assistant」文本回放。
                // （不加这一条，工具结果会被当成一条 UserMessage 塞进上下文，污染对话。）
                if (m.isToolResult()) {
                    continue;
                }
                if ("assistant".equalsIgnoreCase(m.role())) {
                    messages.add(new AssistantMessage(m.content()));
                } else if ("user".equalsIgnoreCase(m.role())) {
                    messages.add(new UserMessage(m.content()));
                }
            }
        }
        messages.add(buildUserMessage(request.userMessage(), request.extra()));
        return messages;
    }

    /**
     * 构建用户消息：若请求携带图片（{@code extra["images"]}，元素 {@code {mimeType, base64}}），
     * 则以多模态 {@code UserMessage(text, media)} 发送（供支持视觉的模型理解图片）；
     * 无图片退化为普通文本消息。
     */
    private static UserMessage buildUserMessage(String text, Map<String, Object> extra) {
        List<Media> medias = mediasOf(extra);
        String body = text == null ? "" : text;
        if (medias.isEmpty()) {
            return new UserMessage(body);
        }
        return UserMessage.builder().text(body).media(medias).build();
    }

    private static List<Media> mediasOf(Map<String, Object> extra) {
        List<Media> medias = new ArrayList<>();
        if (extra == null) {
            return medias;
        }
        Object raw = extra.get("images");
        if (!(raw instanceof List<?> list)) {
            return medias;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object b64 = m.get("base64");
            Object mimeObj = m.get("mimeType");
            if (b64 == null || String.valueOf(b64).isBlank()) {
                continue;
            }
            String mime = mimeObj == null || String.valueOf(mimeObj).isBlank() ? "image/png" : String.valueOf(mimeObj);
            try {
                medias.add(new Media(MimeType.valueOf(mime),
                        URI.create("data:" + mime + ";base64," + b64)));
            } catch (Exception e) {
                log.warn("Skip invalid image part: {}", e.getMessage());
            }
        }
        return medias;
    }

    private ChatOptions options(ChatRequest request, boolean withTools) {
        if ("anthropic".equals(SpringAiChatModelFactory.family(provider))) {
            AnthropicChatOptions.Builder b = AnthropicChatOptions.builder();
            if (request.model() != null) {
                b.model(request.model());
            }
            if (request.temperature() != null) {
                b.temperature(request.temperature());
            }
            if (request.maxTokens() != null) {
                b.maxTokens(request.maxTokens());
            }
            if (withTools) {
                // Spring AI 2.0：工具调用循环已上移到 Advisor 链，ChatModel 不再内部执行工具，
                // 故原先的 internalToolExecutionEnabled(false) 已移除（该方法在 2.0 不存在）。
                // 本平台仍由 SpringAiToolBridge 手动驱动 tool-role 往返。
                b.toolCallbacks(toolBridge.callbacks(request.tools()));
            }
            return b.build();
        }
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder();
        if (request.model() != null) {
            b.model(request.model());
        }
        if (request.temperature() != null) {
            b.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            b.maxTokens(request.maxTokens());
        }
        if (withTools) {
            // Spring AI 2.0：同上，工具循环上移至 Advisor 链，ChatModel 不再内部执行工具。
            b.toolCallbacks(toolBridge.callbacks(request.tools()));
        }
        /*
         * 这里**刻意不发**思考参数：Spring AI 的 `ChatResponse` 抽象里没有 reasoning 的位置，
         * 发了也拿不到（只会白花思考的 token）。所以开启思考时上层的工厂根本不会走这条通道
         * （见 `ModelProviderFactory`：thinking 开启 → 一律走自研适配器）。
         */
        return b.build();
    }

    private ChatResponse toPlatformResponse(org.springframework.ai.chat.model.ChatResponse resp, long t0,
                                            Integer promptTokens, Integer completionTokens) {
        return new ChatResponse(
                textOf(resp),
                promptTokens != null ? promptTokens : promptTokens(resp),
                completionTokens != null ? completionTokens : completionTokens(resp),
                0.0,
                System.currentTimeMillis() - t0,
                List.of(),
                reasoningOf(resp));
    }

    /**
     * 取思考过程（2026-10-19）。
     *
     * <h3>为什么用反射</h3>
     * 走 Spring AI 通道时，思考内容只能从 `AssistantMessage` 的 metadata 里找
     * （key 为 {@code reasoning_content}），而 {@code getMetadata()} 在不同 Spring AI 版本/厂商实现上
     * 有与没有不确定 —— 直接调用会让编译依赖具体版本。反射取不到就当没有（普通模型本来也没有），
     * 绝不能让"取不到思考"影响正文。
     */
    private String reasoningOf(org.springframework.ai.chat.model.ChatResponse resp) {
        try {
            if (resp == null || resp.getResult() == null) {
                return "";
            }
            /*
             * 两个位置都要查（2026-10-19 补）：
             * ① `Generation.getMetadata()` —— Ollama 把思考放这里（key = thinking）；
             * ② `AssistantMessage.getMetadata()` —— Spring AI 官方文档给 DeepSeek 的例子
             *    用的就是这里（key = **reasoningContent**，驼峰）。
             *
             * ⚠️ 驼峰这个坑我踩过：一开始只查 `reasoning_content`（下划线），
             * 结果明明有值也读不到。现在两种都试。
             */
            String fromGeneration = pickFromGenerationMetadata(resp.getResult());
            if (!fromGeneration.isEmpty()) {
                return fromGeneration;
            }
            Object out = resp.getResult().getOutput();
            if (out == null) {
                return "";
            }
            Object meta = out.getClass().getMethod("getMetadata").invoke(out);
            if (meta instanceof java.util.Map<?, ?> m) {
                String v = pickReasoning(m);
                if (!v.isEmpty()) {
                    return v;
                }
                /*
                 * 诊断（DEBUG）：这里恒为空时的**已知原因**是 ——
                 * 走 OpenAI 兼容适配器时，底层 OpenAI Java SDK 的对象模型不含
                 * `reasoning_content`，解析阶段就丢了，Spring AI 只能填个空值。
                 * 官方文档的 `metadata.get("reasoningContent")` 只在使用
                 * `spring-ai-starter-model-deepseek` 时才成立。
                 */
                log.debug("[springai] 未取到 reasoning；metadata keys={}", m.keySet());
            }
        } catch (Exception ignored) {
            // 该版本/该厂商没有 metadata：正常情况
        }
        return "";
    }

    /**
     * 从 {@code Generation} 的 metadata 里取思考内容（Ollama 的 {@code thinking} 放在这里）。
     *
     * <p>用反射是因为 {@code Generation.getMetadata()} 返回的是 {@code ChatGenerationMetadata}
     * 而不是 {@code Map} —— 它提供 {@code get(String)} 之类的访问器，但不同版本形态不一致，
     * 直接绑定类型会让编译依赖具体版本。</p>
     */
    private static String pickFromGenerationMetadata(Object result) {
        try {
            Object meta = result.getClass().getMethod("getMetadata").invoke(result);
            if (meta == null) {
                return "";
            }
            if (meta instanceof java.util.Map<?, ?> m) {
                return pickReasoning(m);
            }
            for (String key : new String[]{"reasoningContent", "reasoning_content", "thinking", "reasoning"}) {
                try {
                    Object v = meta.getClass().getMethod("get", String.class).invoke(meta, key);
                    if (v != null && !String.valueOf(v).isEmpty()) {
                        return String.valueOf(v);
                    }
                } catch (NoSuchMethodException ignored) {
                    // 该版本没有单个 key 的访问器
                }
            }
        } catch (Exception ignored) {
            // 拿不到就当没有
        }
        return "";
    }

    /** 按已知 key 优先级取思考内容（各家拼写不同，按出现频率排序）。 */
    private static String pickReasoning(java.util.Map<?, ?> m) {
        if (m == null || m.isEmpty()) {
            return "";
        }
        for (String key : new String[]{"reasoningContent", "reasoning_content", "thinking", "reasoning"}) {
            Object v = m.get(key);
            if (v != null && !String.valueOf(v).isEmpty()) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static String textOf(org.springframework.ai.chat.model.ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return "";
        }
        String text = resp.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static int promptTokens(org.springframework.ai.chat.model.ChatResponse resp) {
        Usage usage = usage(resp);
        return usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    }

    private static int completionTokens(org.springframework.ai.chat.model.ChatResponse resp) {
        Usage usage = usage(resp);
        return usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    }

    private static Usage usage(org.springframework.ai.chat.model.ChatResponse resp) {
        return resp == null || resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
    }
}
