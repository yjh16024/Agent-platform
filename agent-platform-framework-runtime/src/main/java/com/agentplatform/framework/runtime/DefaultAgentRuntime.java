package com.agentplatform.framework.runtime;

import com.agentplatform.framework.api.AgentRuntime;
import com.agentplatform.framework.api.AttachmentResolver;
import com.agentplatform.framework.api.AuthorizationPolicy;
import com.agentplatform.framework.api.InputPart;
import com.agentplatform.framework.api.InterceptorChain;
import com.agentplatform.framework.api.InterceptorContext;
import com.agentplatform.framework.api.RunInterceptor;
import com.agentplatform.framework.api.RunObserver;
import com.agentplatform.framework.api.QuotaPolicy;
import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunEvent;
import com.agentplatform.framework.api.RunEvents;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.Tool;
import com.agentplatform.framework.api.ToolCatalog;
import com.agentplatform.framework.api.ToolContext;
import com.agentplatform.framework.api.ToolFilter;
import com.agentplatform.framework.api.ToolResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 最小可运行的智能体运行时。
 *
 * <p>它只做四件事：回放历史 → 调模型 → （必要时）跑工具循环 → 落会话并返回结果。
 * 不做的事一律留给宿主：提示词的人格 / Skill / RAG 装配、配额、鉴权、日志落库、插件钩子。
 *
 * <p><b>与平台 {@code AgentRuntimeService} 的关系</b>：后者是「平台语义」的实现（22 张表、
 * 四层记忆、插件管线），本类是「框架语义」的实现（只有会话 + 工具 + 事件）。
 * 平台会在 P2' 阶段改为调用本类并注入自己的能力。
 */
public class DefaultAgentRuntime implements AgentRuntime {

    private static final int CHUNK_SIZE = 24;

    private final ModelAdapter adapter;
    private final ToolExecutor toolExecutor;
    private final ToolCatalog catalog;
    private final SessionStore sessions;
    private final String systemPrompt;
    private final String defaultModel;
    private final double temperature;
    private final Integer maxTokens;
    private final int defaultMaxRounds;
    private final AuthorizationPolicy authorization;
    private final QuotaPolicy quota;
    private final InterceptorChain interceptors;
    private final AttachmentResolver attachmentResolver;
    private final RunObserver observer;

    private DefaultAgentRuntime(Builder b) {
        this.adapter = b.adapter;
        this.catalog = (b.catalog != null) ? b.catalog : b.registry;
        this.toolExecutor = new ToolExecutor(this.catalog, b.filters);
        this.sessions = b.sessions;
        this.systemPrompt = b.systemPrompt;
        this.defaultModel = b.defaultModel;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.defaultMaxRounds = b.defaultMaxRounds;
        this.authorization = (b.authorization == null) ? AuthorizationPolicy.PERMISSIVE : b.authorization;
        this.quota = (b.quota == null) ? QuotaPolicy.NOOP : b.quota;
        this.interceptors = new InterceptorChain(b.interceptors);
        this.attachmentResolver = b.attachmentResolver;
        this.observer = (b.observer == null) ? RunObserver.NOOP : b.observer;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public RunResult run(RunCommand command) {
        return execute(command, newRunId());
    }

    /**
     * 流式运行。
     *
     * <p><b>v0 的实现方式（必须说清）</b>：同步执行一遍，再把结果回放成事件流
     * （工具事件 → 正文分片 → 终止事件）。这样做的原因是真实的增量流式需要模型适配器
     * 逐帧透传，而工具循环下的帧序有协议约束；这部分留到 P2' 接入 Spring AI 通道时再做。
     *
     * <p>对调用方的承诺不变：事件流满足 {@link RunEvent} 的四条契约，
     * 且终止事件里的 {@link RunResult} 与非流式返回值完全一致。
     */
    @Override
    public Flux<RunEvent> runStream(RunCommand command) {
        return Flux.defer(() -> {
            String runId = newRunId();
            RunEvents events = new RunEvents(runId);
            List<RunEvent> out = new ArrayList<>();
            out.add(events.started(runId, command.agentId(), command.sessionId()));
            try {
                RunResult result = execute(command, runId);
                for (RunResult.ToolCall call : result.toolCalls()) {
                    out.add(events.toolStarted(call.callId(), call.toolName(), null));
                    out.add(events.toolFinished(call.callId(), call.toolName(),
                            call.ok(), call.elapsedMs(), call.errorCode()));
                }
                if (result.hasReasoning()) {
                    out.add(events.reasoning(result.reasoning()));
                }
                for (String chunk : chunks(result.reply())) {
                    out.add(events.delta(chunk));
                }
                out.add(events.completed(result));
            } catch (RuntimeException e) {
                out.add(events.failed("RUN_FAILED", e.getMessage(), false));
            }
            RunEvents.validate(out);
            return Flux.fromIterable(out);
        });
    }

    /**
     * 观测包装（M2）：把一次运行的生命周期交给 {@link RunObserver}。
     *
     * <p>观测调用一律 try/catch 吞掉——**观测失败绝不能影响业务**，
     * 这是所有可观测性接入的第一条纪律。</p>
     */
    private RunResult execute(RunCommand command, String runId) {
        long startedAt = System.currentTimeMillis();
        try {
            observer.runStarted(runId, runId, command.agentId(), command.tenantId());
        } catch (RuntimeException ignored) {
            // 观测实现的问题不该冒泡
        }
        try {
            RunResult result = executeInternal(command, runId);
            try {
                observer.runCompleted(runId, System.currentTimeMillis() - startedAt, result.usage());
            } catch (RuntimeException ignored) {
                // 同上
            }
            return result;
        } catch (RuntimeException e) {
            try {
                observer.runFailed(runId, e);
            } catch (RuntimeException ignored) {
                // 同上
            }
            throw e;
        }
    }

    private RunResult executeInternal(RunCommand command, String runId) {
        String sessionId = command.sessionId();
        String model = (command.model() == null || command.model().isBlank()) ? defaultModel : command.model();
        // 平台会按人格 / 模板 / Skill 组装提示词，按轮传入；未传时用运行时默认值
        String effectiveSystemPrompt = (command.systemPrompt() == null || command.systemPrompt().isBlank())
                ? systemPrompt : command.systemPrompt();
        String tenantId = command.tenantId();

        // B3：服务层鉴权出口 —— 先判「能不能跑」，再判「能跑多少」
        authorization.check(command.principal(), AuthorizationPolicy.Actions.AGENT_INVOKE);
        // B2：配额在运行开始时预占一次（与平台「闸门」口径一致）
        quota.reserve(tenantId, QuotaPolicy.Metrics.MODEL_CALLS, 1);

        List<ModelAdapter.ChatMessage> convo = new ArrayList<>(
                sessionId == null ? List.of() : sessions.load(sessionId));

        List<ModelAdapter.ToolSpec> specs = toolSpecs(command);
        boolean toolDeclared = !specs.isEmpty();
        int maxRounds = command.tools().maxRounds() > 0 ? command.tools().maxRounds() : defaultMaxRounds;
        int rounds = toolDeclared ? maxRounds : 1;

        InterceptorContext ctx = new InterceptorContext(runId, command.agentId(), tenantId,
                sessionId, command.text());
        ctx = interceptors.applyBefore(ctx);

        // 附件解析：框架不碰存储，解析结果由宿主提供（文本拼进输入，图片随请求下发）
        StringBuilder attachmentText = new StringBuilder();
        List<String> imageUrls = new ArrayList<>();
        if (attachmentResolver != null) {
            for (InputPart.Ref ref : command.refs()) {
                AttachmentResolver.ResolvedAttachment resolved =
                        attachmentResolver.resolve(ref.kind(), ref.id());
                if (resolved == null) {
                    continue;
                }
                if (resolved.isFailure()) {
                    // 失败必须可见：写进输入，模型能据此如实回答「这个文件读不了」，
                    // 用户也不会以为附件被静默丢掉
                    if (!attachmentText.isEmpty()) {
                        attachmentText.append("\n\n");
                    }
                    attachmentText.append("[附件 ").append(ref.kind()).append(':').append(ref.id())
                            .append(" 未能读取：").append(resolved.failure()).append(']');
                    continue;
                }
                if (resolved.isText()) {
                    if (!attachmentText.isEmpty()) {
                        attachmentText.append("\n\n");
                    }
                    attachmentText.append(resolved.text());
                } else if (resolved.isImage()) {
                    imageUrls.add(resolved.imageUrl());
                }
            }
        }

        String userText = attachmentText.isEmpty()
                ? ctx.input()
                : ctx.input() + "\n\n" + attachmentText;
        Map<String, Object> extra = new java.util.LinkedHashMap<>(command.extra());
        if (!imageUrls.isEmpty()) {
            extra.put("images", imageUrls);
        }
        String reply = "";
        String reasoning = "";
        int inputTokens = 0;
        int outputTokens = 0;
        List<RunResult.ToolCall> toolCalls = new ArrayList<>();

        String shortCircuit = interceptors.shortCircuit(ctx);
        if (shortCircuit != null) {
            // 拦截器短路：不发起模型调用（对齐平台 before_llm 的短路语义）
            reply = shortCircuit;
        } else {
            String nextUserMessage = userText;
            try {
                for (int round = 0; round < rounds; round++) {
                    // R13：每请求凭证透传，避免「智能体级模型绑定」静默失效
                    ModelAdapter.ChatRequest request = new ModelAdapter.ChatRequest(
                            model, effectiveSystemPrompt, nextUserMessage, temperature, maxTokens,
                            extra, convo,
                            command.credentials().baseUrl(), command.credentials().apiKey(),
                            toolDeclared ? specs : List.of(),
                            toolDeclared ? "auto" : null);
                    ModelAdapter.ChatResponse response = adapter.chat(request);

                    inputTokens += response.promptTokens();
                    outputTokens += response.completionTokens();
                    if (response.reasoning() != null && !response.reasoning().isBlank()) {
                        reasoning = response.reasoning();
                    }

                    if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
                        reply = response.content() == null ? "" : response.content();
                        break;
                    }

                    /*
                     * 没声明工具却收到工具调用（模型或适配器异常）。
                     * 此时不能执行，也不能报"达到最大轮次"——那会掩盖真正的问题。
                     * 直接采用它给的正文；正文为空就返回空串，由调用方判断。
                     */
                    if (!toolDeclared) {
                        reply = response.content() == null ? "" : response.content();
                        break;
                    }

                    convo.add(ModelAdapter.ChatMessage.assistantToolCalls(response.toolCalls(), response.reasoning()));

                    for (ModelAdapter.ToolCall call : response.toolCalls()) {
                        long started = System.currentTimeMillis();
                        ToolResult result;
                        try {
                            result = toolExecutor.run(call.name(), call.arguments(),
                                    new ToolContext(tenantId, command.agentId(), runId, sessionId));
                        } catch (RuntimeException e) {
                            // 工具不存在 / 过滤器抛错都要回灌给模型，否则它会一直重试同一个调用
                            result = ToolResult.fail(e.getMessage());
                        }
                        long elapsed = System.currentTimeMillis() - started;
                        toolCalls.add(new RunResult.ToolCall(call.id(), call.name(), result.success(), elapsed,
                                result.success() ? null : "TOOL_ERROR",
                                call.arguments() == null ? null : call.arguments().toString(),
                                result.success() ? textOf(result) : null));
                        convo.add(ModelAdapter.ChatMessage.tool(call.id(), textOf(result)));
                    }

                    nextUserMessage = null;
                    if (round == rounds - 1) {
                        reply = "（工具调用达到最大轮次 " + rounds + "，已停止）";
                    }
                }
            } catch (RuntimeException e) {
                String fallback = interceptors.applyError(ctx, e);
                if (fallback == null) {
                    throw e;
                }
                reply = fallback;
            }

            // 输出替换（脱敏 / 合规改写的唯一落点）：只在模型路径生效，短路结果不再二次改写
            reply = interceptors.applyAfter(ctx, reply);
        }

        if (sessionId != null) {
            sessions.append(sessionId, userText, reply);
        }

        // R12：拦截器产物随结果带出（音频地址 / 图表数据等），框架只搬运不解释
        Map<String, Object> artifacts = interceptors.artifacts(ctx, reply);

        return new RunResult(runId, runId, command.agentId(), sessionId, reply,
                reasoning, adapter.provider(), model,
                new RunResult.Usage(inputTokens, outputTokens),
                toolCalls, List.of(), artifacts);
    }

    /** 只暴露本轮允许的工具；{@code ToolOptions.allows} 已经处理了开关与白名单。 */
    private List<ModelAdapter.ToolSpec> toolSpecs(RunCommand command) {
        if (!command.tools().enabled()) {
            return List.of();
        }
        List<ModelAdapter.ToolSpec> specs = new ArrayList<>();
        for (Tool tool : catalog.all()) {
            if (command.tools().allows(tool.name())) {
                specs.add(new ModelAdapter.ToolSpec(tool.name(), tool.description(), tool.inputSchema()));
            }
        }
        return specs;
    }

    private static String textOf(ToolResult result) {
        if (!result.success()) {
            return result.error() == null ? "工具执行失败" : result.error();
        }
        JsonNode output = result.output();
        if (output == null) {
            return "";
        }
        return output.isTextual() ? output.asText() : output.toString();
    }

    private static List<String> chunks(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
        }
        return chunks;
    }

    private static String newRunId() {
        return "run-" + UUID.randomUUID();
    }

    public static final class Builder {

        private ModelAdapter adapter;
        private ToolRegistry registry = new ToolRegistry();
        private ToolCatalog catalog;
        private List<ToolFilter> filters = List.of();
        private SessionStore sessions = new InMemorySessionStore();
        private String systemPrompt;
        private String defaultModel = "framework-default";
        private double temperature = 0.7;
        private Integer maxTokens;
        private int defaultMaxRounds = 5;
        private AuthorizationPolicy authorization;
        private QuotaPolicy quota;
        private List<RunInterceptor> interceptors = List.of();
        private AttachmentResolver attachmentResolver;
        private RunObserver observer;

        public Builder adapter(ModelAdapter adapter) {
            this.adapter = adapter;
            return this;
        }

        /** 复用宿主已经装配好的注册表（starter 场景），而不是自建一个。 */
        /** 使用宿主提供的只读工具目录（平台注册中心）。与 {@link #registry(ToolRegistry)} 二选一。 */
        public Builder catalog(ToolCatalog catalog) {
            this.catalog = catalog;
            return this;
        }

        public Builder registry(ToolRegistry registry) {
            if (registry != null) {
                this.registry = registry;
            }
            return this;
        }

        public Builder tool(Tool tool) {
            this.registry.register(tool);
            return this;
        }

        public Builder tools(List<Tool> tools) {
            if (tools != null) {
                tools.forEach(this::tool);
            }
            return this;
        }

        public Builder filters(List<ToolFilter> filters) {
            this.filters = (filters == null) ? List.of() : List.copyOf(filters);
            return this;
        }

        public Builder sessions(SessionStore sessions) {
            this.sessions = sessions;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder defaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder authorization(AuthorizationPolicy authorization) {
            this.authorization = authorization;
            return this;
        }

        /** 附件解析器：null 时 InputPart.Ref 会被忽略（不报错，只是不带上）。 */
        /** 观测出口：null 时用 NOOP（框架不强制接观测）。 */
        public Builder observer(RunObserver observer) {
            this.observer = observer;
            return this;
        }

        public Builder attachments(AttachmentResolver attachmentResolver) {
            this.attachmentResolver = attachmentResolver;
            return this;
        }

        public Builder interceptors(List<RunInterceptor> interceptors) {
            this.interceptors = (interceptors == null) ? List.of() : List.copyOf(interceptors);
            return this;
        }

        public Builder quota(QuotaPolicy quota) {
            this.quota = quota;
            return this;
        }

        public Builder defaultMaxRounds(int defaultMaxRounds) {
            this.defaultMaxRounds = defaultMaxRounds;
            return this;
        }

        public DefaultAgentRuntime build() {
            if (adapter == null) {
                throw new IllegalStateException("adapter 必填：框架没有默认模型");
            }
            return new DefaultAgentRuntime(this);
        }
    }
}
