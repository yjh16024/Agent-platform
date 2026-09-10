package com.agentplatform.core.model.springai;

import com.agentplatform.core.model.ModelCapability;
import com.agentplatform.core.model.adapter.ModelAdapter;
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

    @Override
    public Flux<ChatDelta> stream(ChatRequest request) {
        ChatModel model = modelFor(request);
        return model.stream(new Prompt(messages(request), options(request, false)))
                .map(r -> new ChatDelta(textOf(r), false, null))
                .concatWith(Flux.just(new ChatDelta("", true, null)))
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
                List.of());
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
