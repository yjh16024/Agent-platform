package com.agentplatform.framework.api;

import java.util.List;
import java.util.Map;

/**
 * 一次运行的完整结果（非流式返回，也是流式终止事件 {@code RunEvent.Completed} 的载荷）。
 *
 * <p>这里刻意**不含**平台表结构信息（如 message 主键、租户档案指针），
 * 只保留「调用方需要知道的东西」：说了什么、怎么来的、花了多少、调了哪些工具。</p>
 *
 * @param artifacts 插件/拦截器产出的附加物（2026-11-06，R12）：如 TTS 音频地址、图表数据。
 *                  框架不解释内容，只负责把它从拦截器带到调用方；没有时为空 Map。
 */
public record RunResult(
        String runId,
        String traceId,
        String agentId,
        String sessionId,
        String reply,
        String reasoning,
        String provider,
        String model,
        Usage usage,
        List<ToolCall> toolCalls,
        List<Citation> citations,
        Map<String, Object> artifacts) {

    public RunResult {
        toolCalls = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
        citations = (citations == null) ? List.of() : List.copyOf(citations);
        usage = (usage == null) ? Usage.EMPTY : usage;
        artifacts = (artifacts == null) ? Map.of() : Map.copyOf(artifacts);
    }

    /** 兼容签名：不携带 artifacts（v0.3 之前的调用点）。 */
    public RunResult(String runId, String traceId, String agentId, String sessionId, String reply,
                     String reasoning, String provider, String model, Usage usage,
                     List<ToolCall> toolCalls, List<Citation> citations) {
        this(runId, traceId, agentId, sessionId, reply, reasoning, provider, model,
                usage, toolCalls, citations, Map.of());
    }

    /** 推理模型的思考过程是否可展示（不进会话历史，避免污染后续召回）。 */
    public boolean hasReasoning() {
        return reasoning != null && !reasoning.isBlank();
    }

    public boolean usedTools() {
        return !toolCalls.isEmpty();
    }

    public boolean hasArtifacts() {
        return !artifacts.isEmpty();
    }

    /** token 用量；{@code cachedTokens} 允许为 null（并非所有厂商都返回）。 */
    public record Usage(int inputTokens, int outputTokens, Integer cachedTokens) {

        public static final Usage EMPTY = new Usage(0, 0, null);

        public Usage {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("token 用量不能为负");
            }
        }

        public Usage(int inputTokens, int outputTokens) {
            this(inputTokens, outputTokens, null);
        }

        public int totalTokens() {
            return inputTokens + outputTokens;
        }

        public Usage plus(Usage other) {
            if (other == null) {
                return this;
            }
            Integer cached = (cachedTokens == null && other.cachedTokens == null)
                    ? null
                    : (cachedTokens == null ? 0 : cachedTokens) + (other.cachedTokens == null ? 0 : other.cachedTokens);
            return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens, cached);
        }
    }

    /**
     * 一次工具调用的摘要。
     *
     * <p>只留摘要不留完整结果：结果可能很大，且可能含敏感数据；需要复查请走宿主自己的日志出口。</p>
     *
     * <p><b>为什么带 {@code arguments}/{@code output}（2026-11-06，R14）</b>：前端要展示
     * "agent 到底查了什么、拿到了什么"，只有工具名与成败是不够的——用户无法判断它是查过才答、
     * 还是没查就编。为此加两个字段，并**在构造器里强制截断**（任何路径都绕不过去），
     * 上限与平台 {@code ToolCallRecord} 保持同一组数字。</p>
     *
     * @param errorCode 失败时的错误码（成功为 null），用于调用方做分类重试
     * @param arguments 入参 JSON 摘要（已截断；可能为 null）
     * @param output    结果文本摘要（已截断；失败时为 null）
     */
    public record ToolCall(String callId, String toolName, boolean ok, long elapsedMs, String errorCode,
                           String arguments, String output) {

        /** 入参保留上限：够看清"改哪个文件的哪一段"，又不至于把整个文件搬进响应。 */
        public static final int MAX_ARGUMENT_CHARS = 2000;

        /** 结果保留上限：结果才是用户最想看的，但仍要拦得住 grep 的大批量命中。 */
        public static final int MAX_OUTPUT_CHARS = 4000;

        public ToolCall {
            arguments = truncate(arguments, MAX_ARGUMENT_CHARS);
            output = truncate(output, MAX_OUTPUT_CHARS);
        }

        /** 兼容签名：只有元信息（v0.3 之前的调用点）。 */
        public ToolCall(String callId, String toolName, boolean ok, long elapsedMs, String errorCode) {
            this(callId, toolName, ok, elapsedMs, errorCode, null, null);
        }

        private static String truncate(String text, int max) {
            if (text == null || text.length() <= max) {
                return text;
            }
            return text.substring(0, max) + "…（已截断，原长 " + text.length() + " 字符）";
        }
    }

    /** 知识库引用（没有 RAG 时为空列表）。 */
    public record Citation(String documentId, String title, Integer chunkIndex, double score) {
    }
}
