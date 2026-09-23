package com.agentplatform.core.model.adapter;

import com.agentplatform.core.model.ModelCapability;
import tools.jackson.databind.JsonNode;
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
     * <p>{@code tools} 为可用工具定义（function calling，OpenAI 兼容厂商与 Anthropic
     * 均支持）；为空表示不启用工具调用。{@code toolChoice} 为工具选择策略（如
     * {@code auto} / {@code required} / {@code "name"}），可空。</p>
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
            String apiKey,
            List<ToolSpec> tools,
            String toolChoice
    ) {
        /** 兼容旧调用：无历史、无每请求凭证。 */
        public ChatRequest(String model, String systemPrompt, String userMessage,
                           Double temperature, Integer maxTokens, Map<String, Object> extra, List<ChatMessage> history) {
            this(model, systemPrompt, userMessage, temperature, maxTokens, extra, history, null, null, List.of(), null);
        }

        /** 兼容旧调用：无历史列表。 */
        public ChatRequest(String model, String systemPrompt, String userMessage,
                           Double temperature, Integer maxTokens, Map<String, Object> extra) {
            this(model, systemPrompt, userMessage, temperature, maxTokens, extra, List.of(), null, null, List.of(), null);
        }

        /** 兼容旧调用：有历史与每请求凭证，但不启用工具。 */
        public ChatRequest(String model, String systemPrompt, String userMessage,
                           Double temperature, Integer maxTokens, Map<String, Object> extra,
                           List<ChatMessage> history, String baseUrl, String apiKey) {
            this(model, systemPrompt, userMessage, temperature, maxTokens, extra, history, baseUrl, apiKey, List.of(), null);
        }

        public static ChatRequest of(String model, String userMessage) {
            return new ChatRequest(model, null, userMessage, 0.7, null, Map.of());
        }
    }

    /**
     * 工具定义（function calling 声明，随请求下发给模型）。
     *
     * @param name        工具名（须与 {@code ToolRegistry} 注册名一致）
     * @param description 工具描述（供 LLM 判断何时调用）
     * @param inputSchema 入参 JSON Schema（可空）
     */
    record ToolSpec(String name, String description, JsonNode inputSchema) {

        public static ToolSpec of(String name, String description) {
            return new ToolSpec(name, description, null);
        }
    }

    /**
     * 模型请求的工具调用（出现在模型返回中，供运行时执行后回灌）。
     *
     * @param id        调用 ID（回灌 tool 消息时需携带）
     * @param name      工具名
     * @param arguments 调用参数（JSON 对象，可为 null）
     */
    record ToolCall(String id, String name, JsonNode arguments) {
    }

    /**
     * 通用对话消息（多轮历史项）。
     *
     * <h3>为什么需要 {@code toolCallId} 与 {@code toolCalls}（2026-09-22 加）</h3>
     * 原生 function calling 是**两跳**协议，缺少任一跳厂商都会直接 400：
     * <ol>
     *   <li>模型先返回 {@code assistant} 消息，其 {@code tool_calls} 里带若干 {@link ToolCall}（各有 {@code id}）；</li>
     *   <li>宿主执行完工具后，必须回一条 <b>{@code role="tool"}</b> 的消息，
     *       用 {@code tool_call_id} 指回第 1 跳里那次调用。</li>
     * </ol>
     * 此前平台把工具结果**拼成文本追加到用户消息**（见 {@code AgentRuntimeService.runToolLoop} 的旧注释），
     * 因此不需要这两跳、{@link ChatMessage} 也就只有 role/content。
     * 改为原生协议后必须能表达「哪次调用的结果」，故补上这两个字段。
     *
     * <p>各角色对字段的使用：</p>
     * <table border="1">
     *   <caption>role 与字段对应关系</caption>
     *   <tr><th>role</th><th>content</th><th>toolCallId</th><th>toolCalls</th></tr>
     *   <tr><td>system / user</td><td>正文</td><td>空</td><td>空</td></tr>
     *   <tr><td>assistant（普通）</td><td>正文</td><td>空</td><td>空</td></tr>
     *   <tr><td>assistant（请求工具）</td><td>可能为空</td><td>空</td><td><b>有值</b></td></tr>
     *   <tr><td><b>tool（工具执行结果）</b></td><td>结果的文本形式</td><td><b>必有值</b></td><td>空</td></tr>
     * </table>
     *
     * @param role       system / user / assistant / tool
     * @param content    文本内容（{@code role=assistant} 且只请求工具时可能为空）
     * @param toolCallId 仅 {@code role=tool} 使用：对应 {@link ToolCall#id()}
     * @param toolCalls  仅 {@code role=assistant} 使用：本轮请求的工具调用
     */
    record ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {

        /** 兼容旧调用：普通文本消息。 */
        public ChatMessage(String role, String content) {
            this(role, content, null, List.of());
        }

        /** 工具执行结果消息（回灌给模型）。 */
        public static ChatMessage tool(String toolCallId, String content) {
            return new ChatMessage("tool", content, toolCallId, List.of());
        }

        /** 请求了工具的 assistant 消息（回灌时需原样带回，模型据此对齐 tool_call_id）。 */
        public static ChatMessage assistantToolCalls(List<ToolCall> calls) {
            return new ChatMessage("assistant", null, null, calls == null ? List.of() : calls);
        }

        public boolean isToolResult() {
            return "tool".equalsIgnoreCase(role);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
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
     * <p>{@code toolCalls} 为模型请求的工具调用（启用工具时）；无工具调用时为空列表，
     * 此时 {@code content} 为最终文本。</p>
     */
    record ChatResponse(
            String content,
            int promptTokens,
            int completionTokens,
            double costUsd,
            long latencyMs,
            List<ToolCall> toolCalls
    ) {
        /** 兼容旧调用：无工具调用。 */
        public ChatResponse(String content, int promptTokens, int completionTokens, double costUsd, long latencyMs) {
            this(content, promptTokens, completionTokens, costUsd, latencyMs, List.of());
        }
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