package com.agentplatform.core.agent.runtime;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.common.util.TraceContext;
import com.agentplatform.core.agent.dto.AgentRunRequest;
import com.agentplatform.core.agent.dto.AgentRunResponse;
import com.agentplatform.core.agent.service.AgentService;
import com.agentplatform.core.log.LogCategory;
import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogLevel;
import com.agentplatform.core.log.LogService;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.rag.ConversationAttachmentService;
import com.agentplatform.core.rag.retriever.HybridRetriever;
import com.agentplatform.core.rag.retriever.RetrievalResult;
import com.agentplatform.model.entity.DocumentEntity;
import com.agentplatform.model.repository.AgentPluginRepository;
import com.agentplatform.model.repository.DocumentRepository;
import com.agentplatform.core.multimodal.FileUploadService;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.core.multimodal.QuotaService;
import com.agentplatform.core.plugin.runtime.AgentPipeline;
import com.agentplatform.core.plugin.runtime.ExtensionRegistry;
import com.agentplatform.core.plugin.runtime.PipelineResult;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.core.session.SessionService;
import com.agentplatform.core.session.SessionSummaryService;
import com.agentplatform.core.skill.SkillService;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolSchemas;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.entity.Session;
import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.Persona;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent Runtime（统一 /agent/run 执行引擎）。
 * <p>
 * 每次 run 启动时按 agent_id 加载 EffectiveConfig，合并人格 → 系统提示词，
 * 填充 {{var}} 模板变量，再经 ModelRouter 调用模型。支持 stream 流式输出。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntimeService {

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_\\-\\.]+)\\s*}}");

    private final AgentService agentService;
    private final ModelRouter modelRouter;
    private final AgentPipeline agentPipeline;
    private final PluginRuntime pluginRuntime;
    private final QuotaService quotaService;

    /** 会话持久化（可选：单测中未注入时退化为无记忆单轮）。 */
    @Autowired(required = false)
    private SessionService sessionService;

    /** 会话早期摘要（中期记忆，可选：未注入时不做摘要续接，保持原行为）。 */
    @Autowired(required = false)
    private SessionSummaryService sessionSummaryService;

    /** 模型绑定解析（可选：单测中未注入时退化为全局默认，凭证走适配器默认）。 */
    @Autowired(required = false)
    private ModelBindingService modelBindingService;

    /** 运行日志采集（可选：单测中未注入时静默跳过，保证既有单测不受影响）。 */
    @Autowired(required = false)
    private LogService logService;

    /** Skill 注入（可选：未注入时不追加 Skill 提示词，保持旧行为与单测可用）。 */
    @Autowired(required = false)
    private SkillService skillService;

    /** 附件读取（方案 A：对话拖入即读；未注入时 file part 报"服务不可用"而非崩溃）。 */
    @Autowired(required = false)
    private FileUploadService fileUploadService;

    /** RAG 混合检索（可选：未注入时跳过知识库注入，保持旧行为与单测可用）。 */
    @Autowired(required = false)
    private HybridRetriever hybridRetriever;

    /** 对话附件自动摄取（方案 C，可选：未注入时拖入文档仍按原路径只注入文本，不进知识库）。 */
    @Autowired(required = false)
    private ConversationAttachmentService attachmentService;

    /** 文档仓储（可选：用于把 docId 映射成可读文件名，填充引用来源）。 */
    @Autowired(required = false)
    private DocumentRepository documentRepository;

    /** 工具注册中心（可选：未注入时对话不启用 function calling，保持旧行为与单测可用）。 */
    @Autowired(required = false)
    private ToolRegistry toolRegistry;

    /** 工具执行器（可选：与注册中心配套，执行模型请求的工具调用）。 */
    @Autowired(required = false)
    private ToolExecutor toolExecutor;

    /** 扩展注册表（可选：用于剔除「其它智能体」的插件工具，实现按智能体隔离）。 */
    @Autowired(required = false)
    private ExtensionRegistry extensionRegistry;

    /** 插件绑定仓储（可选：运行时兜底热加载时回填挂载时保存的 config，避免重启后配置丢失）。 */
    @Autowired(required = false)
    private AgentPluginRepository agentPluginRepository;

    /** 工具调用循环最大轮数（防止模型在 tool_calls 里死循环）。 */
    static final int MAX_TOOL_ROUNDS = 5;

    /** 默认模型 provider / 名称（智能体未显式配置时兜底到真实模型）。 */
    @Value("${agent-platform.model.default-provider:deepseek}")
    private String defaultProvider;
    @Value("${agent-platform.model.default-name:deepseek-chat}")
    private String defaultModel;

    /**
     * 执行一次 Agent 运行（同步）。
     */
    public AgentRunResponse run(AgentRunRequest req) {
        String runId = req.runId() != null ? req.runId() : IdGenerator.generate("run");
        String traceId = IdGenerator.generate("trace");
        String tenantId = req.tenantId();

        return TraceContext.withContext(traceId, runId, tenantId, () -> {
            AgentDefinition agent = agentService.getOrThrow(tenantId, req.agentId());

            logTo(LogLevel.INFO, LogCategory.agent, "run.start agent=" + agent.getName()
                            + " mode=" + req.mode() + " session=" + req.sessionId(),
                    traceId, runId, tenantId, agent.getAgentId());

            // ⓪ 配额校验（多租户）
            if (quotaService != null) {
                quotaService.checkAndIncrement(tenantId, "model_calls", null);
            }

            // ② 合并生成参数（provider/模型未配置时兜底到默认真实模型）
            GenerationConfig gc = mergeGenerationConfig(agent.getGenerationConfig(), req.model());
            ModelBindingService.ResolvedModel resolved = resolveModelBinding(agent, gc);
            final String provider = resolved.provider();
            final String model = resolved.model();
            // ③ 组装用户消息（取最后一条 user 消息；parts[] 含 file 时注入文件文本）
            String userMessage = extractUserMessage(req, tenantId);
            // ③.0 提取图片附件（方案 B：image part → 字节 → base64，随请求走 Spring AI 视觉通道）
            final List<Map<String, String>> imageData = collectImageData(req, tenantId);
            final Map<String, Object> imageExtra = imageData.isEmpty() ? Map.of() : Map.of("images", imageData);

            // ④.0 会话解析（提前到提示词组装前：历史较长时需读取早期摘要；sessionId 为空返回 null 不持久化）
            Session session = sessionService == null ? null
                    : sessionService.resolve(tenantId, agent.getAgentId(), req.userId(), req.sessionId(), userMessage);

            // ③.1 组装基础系统提示词（人格 → 提示词合并 + 变量填充 + Skill 注入）
            String basePrompt = withSkillPrompts(agent,
                    assembleSystemPrompt(agent.getPersona(), agent.getSystemPrompt(), req));
            // ③.2 RAG 知识库自动检索（请求/智能体绑定知识库时，把命中片段注入上下文并生成引用；
            // 未绑定知识库/检索失败时 retrieveKnowledge 返回 null，等价于普通对话）
            // ③.3 对话附件自动摄取（方案 C）：文档拖入 → 归入租户附件库 → 并入本次检索范围
            String attachmentKbId = ensureAttachments(req, tenantId);
            RagRender rag = retrieveKnowledge(agent, req, userMessage, attachmentKbId);
            final String systemPrompt = rag == null || rag.systemBlock() == null || rag.systemBlock().isBlank()
                    ? basePrompt
                    : basePrompt + rag.systemBlock();

            // ④.6 早期会话摘要续接（历史被截断丢弃的早期轮次压缩进上下文，让「记忆」跨长对话保留）
            String earlySummary = earlySummaryOf(session);
            final String effectivePrompt = earlySummary == null
                    ? systemPrompt
                    : systemPrompt + "\n\n## 早期会话摘要（较早轮次已压缩，仅作背景参考）\n" + earlySummary;

            // ④ 确保插件已挂载（热加载）
            ensurePluginsAttached(agent, tenantId);

            // ④.5 历史回放（会话已在上方解析；让对话「有记忆」）
            final List<ModelAdapter.ChatMessage> sessionHistory = loadHistory(req, tenantId);

            // ⑤ 经插件 Hook 管线调用模型（before_llm → LLM → after_llm）
            final long[] latency = {0L};
            final int[] usageTokens = {0, 0};
            // ⑤.0 解析工具声明（ToolsConfig.enabled + allowed 白名单）
            final List<ModelAdapter.ToolSpec> toolSpecs = resolveToolSpecs(req);
            PipelineResult pipeline;
            try {
                pipeline = agentPipeline.run(agent.getAgentId(), runId, userMessage, msg -> {
                    // 工具调用循环：仅当请求显式启用工具且注册中心可用时走工具链路
                    if (!toolSpecs.isEmpty() && toolExecutor != null) {
                        return runToolLoop(provider, model, effectivePrompt, msg, gc, resolved,
                                toolSpecs, sessionHistory, traceId, runId, tenantId, agent, usageTokens, imageExtra);
                    }
                    // 普通单轮 LLM 调用（无工具）
                    logTo(LogLevel.INFO, LogCategory.llm, "llm.call provider=" + provider
                                    + " model=" + model + " routing=" + resolved.routing()
                                    + " history=" + sessionHistory.size() + " chars=" + msg.length(),
                            traceId, runId, tenantId, agent.getAgentId());
                    long t0 = System.currentTimeMillis();
                    ModelAdapter.ChatRequest chatReq = new ModelAdapter.ChatRequest(
                            model, effectivePrompt, msg, gc.temperature(), gc.maxTokens(), imageExtra, sessionHistory,
                            resolved.baseUrl(), resolved.apiKey());
                    ModelAdapter.ChatResponse resp = modelRouter.chat(provider, chatReq);
                    latency[0] = resp.latencyMs();
                    usageTokens[0] = resp.promptTokens();
                    usageTokens[1] = resp.completionTokens();
                    logTo(LogLevel.INFO, LogCategory.llm, "llm.done provider=" + provider
                                    + " model=" + model
                                    + " latency=" + (System.currentTimeMillis() - t0) + "ms"
                                    + " tokens=" + (usageTokens[0] + usageTokens[1]),
                            traceId, runId, tenantId, agent.getAgentId());
                    return resp.content();
                });
            } catch (Exception e) {
                logTo(LogLevel.ERROR, LogCategory.agent,
                        "run.failed agent=" + agent.getName() + " model=" + model
                                + " error=" + e.getMessage(),
                        traceId, runId, tenantId, agent.getAgentId(), stackTraceOf(e));
                throw e;
            }

            // ⑥ 计量与响应（含插件附加产物如 audio_url；RAG 检索命中时附带引用溯源）
            AgentRunResponse.Usage usage = new AgentRunResponse.Usage(
                    usageTokens[0], usageTokens[1], 0.0);
            AgentRunResponse resp = AgentRunResponse.of(
                    runId, req.sessionId(), req.mode(), pipeline.reply(), traceId, usage);
            if (rag != null && !rag.references().isEmpty()) {
                resp = new AgentRunResponse(resp.runId(), resp.sessionId(), resp.mode(),
                        resp.output(), resp.traceId(), resp.usage(), rag.references(), resp.plugins());
            }
            if (pipeline.extras() != null && pipeline.extras().containsKey("audio_url")) {
                resp = new AgentRunResponse(resp.runId(), resp.sessionId(), resp.mode(),
                        new AgentRunResponse.Output("assistant", resp.output().content(),
                                String.valueOf(pipeline.extras().get("audio_url"))),
                        resp.traceId(), resp.usage(), resp.references(), resp.plugins());
            }
            // ⑦ 保存会话记忆（user + assistant 一轮），失败不阻断主流程
            if (session != null && sessionService != null) {
                try {
                    sessionService.recordExchange(tenantId, session.getSessionId(), runId,
                            userMessage, pipeline.reply(), model);
                } catch (Exception e) {
                    log.warn("Failed to persist session exchange: {}", e.getMessage());
                }
            }

            logTo(LogLevel.INFO, LogCategory.agent, "run.completed agent=" + agent.getName()
                            + " model=" + model + " latency=" + latency[0] + "ms"
                            + " shortCircuit=" + pipeline.shortCircuited(),
                    traceId, runId, tenantId, agent.getAgentId());
            log.info("Run {} completed: agent={}, model={}, shortCircuit={}",
                    runId, req.agentId(), model, pipeline.shortCircuited());
            return resp;
        });
    }

    /**
     * 采集一条运行日志（LogService 未注入时静默跳过，保证单测与最小依赖场景可用）。
     */
    private void logTo(LogLevel level, LogCategory category, String message,
                       String traceId, String runId, String tenantId, String agentId) {
        logTo(level, category, message, traceId, runId, tenantId, agentId, null);
    }

    private void logTo(LogLevel level, LogCategory category, String message,
                       String traceId, String runId, String tenantId, String agentId, String stackTrace) {
        if (logService == null) {
            return;
        }
        try {
            logService.log(new LogEvent(IdGenerator.generate("log"), java.time.LocalDateTime.now(),
                    traceId, runId, tenantId, agentId, level, category, message,
                    null, stackTrace, null));
        } catch (Exception e) {
            log.warn("Failed to collect run log: {}", e.getMessage());
        }
    }

    /** 截断异常堆栈（避免超长日志撑爆字段，且做基础脱敏）。 */
    private String stackTraceOf(Throwable t) {
        if (t == null) {
            return null;
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) + "\n...[truncated]" : s;
    }

    /**
     * 解析本轮可用的工具声明（P1 功能 calling）。
     * <p>
     * 仅当请求显式 {@code tools.enabled=true} 才启用；{@code allowed} 白名单
     * 为空时使用注册中心全部工具（内置 calc/search 及 HTTP/MCP 注册工具）。
     * 工具注册中心未注入时返回空（等价不启用）。
     * </p>
     */
    private List<ModelAdapter.ToolSpec> resolveToolSpecs(AgentRunRequest req) {
        if (toolRegistry == null || req.tools() == null) {
            return List.of();
        }
        AgentRunRequest.ToolsConfig tc = req.tools();
        if (!Boolean.TRUE.equals(tc.enabled())) {
            return List.of();
        }
        List<String> allowed = tc.allowed();
        boolean allowAll = allowed == null || allowed.isEmpty();
        // 插件工具最终落在全局 ToolRegistry 里，这里必须把「其它智能体」的插件工具剔除，
        // 否则 agent A 挂的插件工具会出现在 agent B 的可用工具列表里（按智能体隔离）。
        java.util.Set<String> foreignPluginTools = extensionRegistry == null
                ? java.util.Set.of()
                : extensionRegistry.pluginToolNamesExcept(req.agentId());
        List<ModelAdapter.ToolSpec> specs = new ArrayList<>();
        for (Tool tool : toolRegistry.all()) {
            if (tool == null
                    || foreignPluginTools.contains(tool.name())
                    || (!allowAll && !allowed.contains(tool.name()))) {
                continue;
            }
            // schema 规范化：null / 缺 type 会让厂商直接 400（schema must be 'type: object'）
            specs.add(new ModelAdapter.ToolSpec(tool.name(), tool.description(),
                    ToolSchemas.orEmpty(tool.inputSchema())));
        }
        return specs;
    }

    /**
     * 执行工具调用循环（P1）：注入 tools → 模型返回 tool_calls → 逐个执行 →
     * 文本回灌下一轮 → 直至模型不再请求工具。
     * <p>
     * 采用「工具结果文本注入」的轻量闭环：把每次调用名/入参/结果拼为文本追加到
     * 用户消息后重新请求。优点是兼容 OpenAI / Anthropic / 本地适配器且不动全局
     * 消息协议；代价是不走原生 tool-role 消息，足够支撑 calc/search 等单步工具。
     * </p>
     */
    private String runToolLoop(
            String provider, String model, String systemPrompt, String userMessage,
            GenerationConfig gc, ModelBindingService.ResolvedModel resolved,
            List<ModelAdapter.ToolSpec> tools, List<ModelAdapter.ChatMessage> history,
            String traceId, String runId, String tenantId, AgentDefinition agent, int[] usageTokens,
            Map<String, Object> extraImages) {
        String rolling = userMessage;
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            boolean hasTools = round == 0 && tools != null && !tools.isEmpty();
            logTo(LogLevel.INFO, LogCategory.llm, "llm.call provider=" + provider + " model=" + model
                            + " routing=" + resolved.routing() + " round=" + round
                            + " tools=" + (hasTools ? tools.size() : 0)
                            + " history=" + history.size() + " chars=" + rolling.length(),
                    traceId, runId, tenantId, agent.getAgentId());
            long t0 = System.currentTimeMillis();
            ModelAdapter.ChatRequest chatReq = new ModelAdapter.ChatRequest(
                    model, systemPrompt, rolling, gc.temperature(), gc.maxTokens(), extraImages, history,
                    resolved.baseUrl(), resolved.apiKey(),
                    hasTools ? tools : List.of(),
                    hasTools ? "auto" : null);
            ModelAdapter.ChatResponse resp = modelRouter.chat(provider, chatReq);
            usageTokens[0] += resp.promptTokens();
            usageTokens[1] += resp.completionTokens();
            logTo(LogLevel.INFO, LogCategory.llm, "llm.done provider=" + provider + " model=" + model
                            + " round=" + round + " latency=" + (System.currentTimeMillis() - t0) + "ms"
                            + " tokens=" + (resp.promptTokens() + resp.completionTokens())
                            + " toolCalls=" + (resp.toolCalls() == null ? 0 : resp.toolCalls().size()),
                    traceId, runId, tenantId, agent.getAgentId());
            if (resp.toolCalls() == null || resp.toolCalls().isEmpty()) {
                return resp.content() == null ? "" : resp.content();
            }
            // 逐个执行模型请求的工具
            StringBuilder observations = new StringBuilder();
            for (ModelAdapter.ToolCall call : resp.toolCalls()) {
                long toolT0 = System.nanoTime();
                ToolResult tr;
                try {
                    tr = toolExecutor == null
                            ? ToolResult.fail("tool executor not configured")
                            : toolExecutor.run(call.name(), call.arguments(),
                            ToolContext.of(tenantId, agent.getAgentId(), runId));
                } catch (Exception e) {
                    tr = ToolResult.fail(e.getMessage() == null ? "tool execution error" : e.getMessage());
                }
                long toolLatencyMs = (System.nanoTime() - toolT0) / 1_000_000L;
                String outputText = tr.output() == null
                        ? (tr.error() == null ? "" : tr.error())
                        : (tr.output().isTextual() ? tr.output().asText() : tr.output().toString());
                logTo(LogLevel.INFO, LogCategory.tool, "tool.call name=" + call.name()
                                + " success=" + tr.success() + " latency=" + toolLatencyMs + "ms args="
                                + (call.arguments() == null ? "{}" : call.arguments().toString()),
                        traceId, runId, tenantId, agent.getAgentId());
                observations.append("工具[").append(call.name()).append("] 执行")
                        .append(tr.success() ? "成功" : "失败")
                        .append("，结果：").append(outputText).append("\n");
            }
            rolling = rolling + "\n\n[工具调用结果]\n" + observations
                    + "\n请依据上述工具结果继续回答用户问题；如无进一步工具可调用，直接给出最终答复。";
        }
        logTo(LogLevel.WARN, LogCategory.tool, "tool loop reached max rounds " + MAX_TOOL_ROUNDS
                        + " agent=" + agent.getName(),
                traceId, runId, tenantId, agent.getAgentId());
        return "（工具调用超过最大轮次，已停止；请重试或补充说明）";
    }

    /**
     * RAG 知识库自动检索（方案 B）。
     * <p>
     * 知识库范围解析顺序：请求级 {@code context.rag.knowledgeBaseIds} 优先；
     * 否则回退智能体 {@code capabilities.knowledgeBaseIds}（默认绑定）。
     * 命中片段拼成系统提示词附文（带来源编号），供模型作答引用；
     * 检索结果同时生成 {@code references} 返回前端做引用溯源。
     * 任何失败（未绑定知识库 / 检索异常）均优雅降级为 null，不阻断对话。
     * </p>
     */
    private RagRender retrieveKnowledge(AgentDefinition agent, AgentRunRequest req, String userMessage,
                                        String extraKbId) {
        AgentRunRequest.ContextConfig ctx = req.context();
        if (hybridRetriever == null) {
            return null;
        }
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }

        // ① 解析知识库范围
        List<String> kbIds = new ArrayList<>();
        Boolean useRag = ctx == null ? null : ctx.useRag();
        if (ctx != null && ctx.rag() != null && ctx.rag().knowledgeBaseIds() != null
                && !ctx.rag().knowledgeBaseIds().isEmpty()) {
            kbIds.addAll(ctx.rag().knowledgeBaseIds());
        }
        if (kbIds.isEmpty() && agent.getCapabilities() != null
                && agent.getCapabilities().knowledgeBaseIds() != null) {
            kbIds.addAll(agent.getCapabilities().knowledgeBaseIds());
        }
        // 对话附件库（方案 C）并入本次检索范围
        if (extraKbId != null && !extraKbId.isBlank() && !kbIds.contains(extraKbId)) {
            kbIds.add(extraKbId);
        }
        if (kbIds.isEmpty() || Boolean.FALSE.equals(useRag)) {
            return null;
        }

        // ② 检索参数（请求可覆盖）
        int topK = 5;
        double scoreThreshold = 0.0;
        if (ctx != null && ctx.rag() != null) {
            if (ctx.rag().topK() != null && ctx.rag().topK() > 0) {
                topK = ctx.rag().topK();
            }
            if (ctx.rag().scoreThreshold() != null) {
                scoreThreshold = ctx.rag().scoreThreshold();
            }
        }

        // ③ 执行混合检索（异常降级，不阻断主流程）
        List<RetrievalResult> hits;
        try {
            hits = hybridRetriever.search(kbIds, userMessage, topK, scoreThreshold, null, true);
        } catch (Exception e) {
            log.warn("RAG retrieval skipped: {}", e.getMessage());
            return null;
        }
        if (hits == null || hits.isEmpty()) {
            return null;
        }

        // ④ 组装系统提示词附文 + 引用溯源
        Map<String, String> docTitleCache = new LinkedHashMap<>();
        StringBuilder block = new StringBuilder("\n\n## 知识库资料（只读参考）\n");
        block.append("请优先依据下列资料作答；引用时标注〔来源编号〕；资料未覆盖的内容请明确说明\"知识库中未找到\"，不要编造。\n");
        List<AgentRunResponse.Reference> refs = new ArrayList<>();
        int idx = 1;
        for (RetrievalResult hit : hits) {
            String docId = hit.source();
            String title = docId == null ? "未知文档"
                    : docTitleCache.computeIfAbsent(docId, this::lookupDocTitle);
            block.append("〔来源").append(idx).append("〕").append(title);
            if (hit.page() != null) {
                block.append(" 第").append(hit.page()).append("页");
            }
            block.append("\n").append(hit.content() == null ? "" : hit.content()).append("\n\n");
            refs.add(new AgentRunResponse.Reference(
                    hit.chunkId(), title, hit.page(), hit.score()));
            idx++;
        }
        return new RagRender(block.toString(), refs);
    }

    /** 把 docId 映射为可读文件名（查找失败回退原 ID）。 */
    private String lookupDocTitle(String docId) {
        try {
            if (documentRepository != null) {
                DocumentEntity doc = documentRepository.findByDocId(docId).orElse(null);
                if (doc != null && doc.getTitle() != null && !doc.getTitle().isBlank()) {
                    return doc.getTitle();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve doc title {}: {}", docId, e.getMessage());
        }
        return docId;
    }

    /** RAG 检索产物：注入提示词的附文 + 引用列表。 */
    private record RagRender(String systemBlock, List<AgentRunResponse.Reference> references) {
    }

    /**
     * 读取会话早期摘要（历史过长时压缩续接）。任何失败降级返回 null，不阻断对话。
     */
    private String earlySummaryOf(Session session) {
        if (sessionSummaryService == null || session == null) {
            return null;
        }
        try {
            return sessionSummaryService.summaryFor(session);
        } catch (Exception e) {
            log.warn("Failed to load session summary: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取最后一条用户消息中的图片附件（image part → 文件字节 → base64）。
     * <p>供 Spring AI 通道的视觉模型使用；文件读取/解码失败降级为无图（不阻断文本对话）。</p>
     */
    private List<Map<String, String>> collectImageData(AgentRunRequest req, String tenantId) {
        List<Map<String, String>> images = new ArrayList<>();
        if (fileUploadService == null) {
            return images;
        }
        AgentRunRequest.Message last = lastUserMessage(req);
        Object content = last == null ? null : last.content();
        if (!(content instanceof List<?> parts)) {
            return images;
        }
        try {
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> m) || !"image".equals(strOf(m.get("type")))) {
                    continue;
                }
                String fileId = strOf(m.get("fileId"));
                if (fileId.isBlank()) {
                    continue;
                }
                FileUploadService.Download dl = fileUploadService.download(tenantId, fileId);
                if (dl == null || dl.bytes() == null || dl.bytes().length == 0) {
                    log.warn("Image {} not found or empty, skip", fileId);
                    continue;
                }
                String mime = strOf(m.get("mimeType"));
                if (mime.isBlank()) {
                    mime = "image/png";
                }
                Map<String, String> item = new java.util.HashMap<>(2);
                item.put("mimeType", mime);
                item.put("base64", java.util.Base64.getEncoder().encodeToString(dl.bytes()));
                images.add(item);
            }
        } catch (Exception e) {
            log.warn("Collect image parts failed: {}", e.getMessage());
        }
        return images;
    }

    /**
     * 自动摄取对话拖入的文档附件（方案 C），返回租户附件库 ID（无文档附件或失败返回 null）。
     * <p>file part 仍会走原有文本注入；这里额外把文档归入知识库，使回答可获得引用溯源。</p>
     */
    private String ensureAttachments(AgentRunRequest req, String tenantId) {
        if (attachmentService == null || fileUploadService == null) {
            return null;
        }
        AgentRunRequest.Message last = lastUserMessage(req);
        Object content = last == null ? null : last.content();
        if (!(content instanceof List<?> parts)) {
            return null;
        }
        String kbId = null;
        try {
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> m) || !"file".equals(strOf(m.get("type")))) {
                    continue;
                }
                String fileId = strOf(m.get("fileId"));
                String fileName = strOf(m.get("fileName"));
                if (fileId.isBlank() || !ConversationAttachmentService.isDocumentFile(fileName)) {
                    continue;
                }
                FileUploadService.Download dl = fileUploadService.download(tenantId, fileId);
                if (dl == null || dl.bytes() == null || dl.bytes().length == 0) {
                    log.warn("Attachment {} not found or empty, skip ingest", fileId);
                    continue;
                }
                if (kbId == null) {
                    kbId = attachmentService.attachmentKbId(tenantId);
                }
                attachmentService.ensureDocument(tenantId, fileId, fileName, dl.bytes());
            }
        } catch (Exception e) {
            log.warn("Ensure attachments failed: {}", e.getMessage());
            return kbId;
        }
        return kbId;
    }

    /**
     * 确保智能体挂载的插件已热加载（幂等）。
     *
     * <p><b>2026-09-20 修（坑 3：重启后 config 丢失）</b>：此前这里无条件传 {@code Map.of()}，
     * 不读绑定里保存的 config —— 于是重启后端后第一次运行时，插件是以<b>空配置</b>被装上的，
     * 挂载时填的 {@code {"rules":{...}}} 之类会静默失效（内置那三个插件规则写死，所以看不出来）。
     * 现在改为从 {@code agent_plugin} 回填 config，并尊重绑定的 enabled 开关。</p>
     *
     * <p>同时按 {@code (agentId, pluginId)} 判断是否已挂载 —— 同一插件挂到多台智能体时，
     * 每台都要各自 attach 一次（旧实现按 pluginId 幂等，第二台会被跳过）。</p>
     */
    private void ensurePluginsAttached(AgentDefinition agent, String tenantId) {
        Capabilities caps = agent.getCapabilities();
        if (caps == null || caps.pluginIds() == null || caps.pluginIds().isEmpty()) {
            return;
        }
        String agentId = agent.getAgentId();
        for (String pluginId : caps.pluginIds()) {
            if (pluginRuntime.isAttached(agentId, pluginId)) {
                continue;   // 本机已挂载，无需重复
            }
            Map<String, Object> config = Map.of();
            if (agentPluginRepository != null) {
                var binding = agentPluginRepository.findByAgentIdAndPluginId(agentId, pluginId);
                if (binding.isPresent()) {
                    var b = binding.get();
                    if (b.getEnabled() != null && !b.getEnabled()) {
                        continue;   // 绑定已停用，不该被兜底装回来
                    }
                    if (b.getConfig() != null) {
                        config = b.getConfig();
                    }
                }
            }
            try {
                pluginRuntime.attach(pluginId, agentId, tenantId, config);
            } catch (Exception e) {
                // 兜底失败不影响本轮对话：常见原因是插件已被删除、capabilities 里留了历史 id
                log.debug("Skip attaching plugin {} for agent {}: {}", pluginId, agentId, e.getMessage());
            }
        }
    }

    /**
     * 流式执行（SSE）。
     */
    public Flux<AgentRunResponse.Output> runStream(AgentRunRequest req) {
        String runId = req.runId() != null ? req.runId() : IdGenerator.generate("run");
        String traceId = IdGenerator.generate("trace");
        String tenantId = req.tenantId();

        return TraceContext.withContext(traceId, runId, tenantId, () -> {
            AgentDefinition agent = agentService.getOrThrow(tenantId, req.agentId());
            String userMessage = extractUserMessage(req, tenantId);
            String basePrompt = withSkillPrompts(agent,
                    assembleSystemPrompt(agent.getPersona(), agent.getSystemPrompt(), req));
            RagRender rag = retrieveKnowledge(agent, req, userMessage, null);
            final String systemPrompt = rag == null || rag.systemBlock() == null || rag.systemBlock().isBlank()
                    ? basePrompt
                    : basePrompt + rag.systemBlock();
            GenerationConfig gc = mergeGenerationConfig(agent.getGenerationConfig(), req.model());
            ModelBindingService.ResolvedModel resolved = resolveModelBinding(agent, gc);
            String provider = resolved.provider();
            String model = resolved.model();

            ModelAdapter.ChatRequest chatReq = new ModelAdapter.ChatRequest(
                    model, systemPrompt, userMessage, gc.temperature(), gc.maxTokens(), Map.of(), List.of(),
                    resolved.baseUrl(), resolved.apiKey());
            return modelRouter.route(provider, com.agentplatform.core.model.ModelCapability.TEXT)
                    .stream(chatReq)
                    .filter(d -> !d.finished() && d.text() != null && !d.text().isEmpty())
                    .map(d -> new AgentRunResponse.Output("assistant", d.text(), null));
        });
    }

    /**
     * 追加已挂载 Skill 的提示词（SKILL.md 正文）。
     * <p>
     * Skill 以标准目录 {@code skills/<name>/SKILL.md} 存储，正文即能力说明/操作规范。
     * 智能体通过 {@code capabilities.skillIds} 引用，运行时把正文拼接到系统提示词，
     * 实现「挂载即生效」；单个 Skill 读取失败不阻断主流程（降级跳过）。
     * </p>
     */
    public String withSkillPrompts(AgentDefinition agent, String systemPrompt) {
        if (skillService == null || agent.getCapabilities() == null) {
            return systemPrompt;
        }
        List<String> ids = agent.getCapabilities().skillIds();
        if (ids == null || ids.isEmpty()) {
            return systemPrompt;
        }
        StringBuilder sb = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        for (String skillId : ids) {
            try {
                com.agentplatform.model.entity.SkillDef skill =
                        skillService.get(agent.getTenantId(), skillId);
                if (skill.getPromptTemplate() != null && !skill.getPromptTemplate().isBlank()) {
                    sb.append("\n\n## Skill：").append(skill.getName()).append("\n")
                            .append(skill.getPromptTemplate().trim());
                }
            } catch (Exception e) {
                log.warn("Failed to load skill {} for agent {}: {}", skillId, agent.getAgentId(), e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * 组装系统提示词：人格翻译 + 模板变量填充。
     */
    public String assembleSystemPrompt(Persona persona, String systemPromptTemplate, AgentRunRequest req) {
        StringBuilder sb = new StringBuilder();

        // 人格 → 提示词段
        if (persona != null) {
            if (persona.role() != null && !persona.role().isBlank()) {
                sb.append("你是").append(persona.role()).append("。\n");
            }
            if (persona.tone() != null && !persona.tone().isBlank()) {
                sb.append("语气：").append(persona.tone()).append("。\n");
            }
            if (persona.style() != null && !persona.style().isBlank()) {
                sb.append("风格：").append(persona.style()).append("。\n");
            }
            if (persona.warmth() != null && persona.warmth() >= 0.8) {
                sb.append("回复时体现共情与亲和力。\n");
            }
            if (persona.forbidden() != null && !persona.forbidden().isEmpty()) {
                sb.append("禁区：绝不").append(String.join("、", persona.forbidden())).append("。\n");
            }
        }

        // 系统提示词模板 + 变量填充
        if (systemPromptTemplate != null && !systemPromptTemplate.isBlank()) {
            sb.append(fillTemplate(systemPromptTemplate, req));
        }
        return sb.toString().trim();
    }

    /**
     * 填充 {{var}} 模板变量（运行时由上下文/用户参数填充）。
     */
    public String fillTemplate(String template, AgentRunRequest req) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("user", req.userId() == null ? "" : req.userId());
        vars.put("tenant", req.tenantId());
        vars.put("agent_id", req.agentId() == null ? "" : req.agentId());

        Matcher m = TEMPLATE_VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = vars.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 合并生成参数（请求级覆盖 Agent 保存值）。
     */
    public GenerationConfig mergeGenerationConfig(GenerationConfig base, AgentRunRequest.ModelOverride override) {
        if (override == null) {
            return base == null ? GenerationConfig.defaults() : base;
        }
        GenerationConfig overrideCfg = new GenerationConfig(
                override.name(), override.provider(), override.temperature(),
                null, null, null, null, null, null, null, null, null);
        return base == null ? overrideCfg : base.merge(overrideCfg);
    }

    /**
     * 解析最终生效的模型绑定（provider/model/baseUrl/apiKey）。
     * <p>优先走 {@link ModelBindingService}（含智能体模型绑定 + 凭证解密 + 全局回退）；
     * 单测未注入时退化为旧的 provider/model 解析，凭证走适配器默认。</p>
     */
    private ModelBindingService.ResolvedModel resolveModelBinding(AgentDefinition agent, GenerationConfig gc) {
        if (modelBindingService != null) {
            return modelBindingService.resolve(agent);
        }
        return new ModelBindingService.ResolvedModel(resolveProvider(gc), resolveModel(gc), null, null,
                ModelBindingService.Routing.GATEWAY);
    }

    /**
     * 解析有效 provider：显式配置优先，否则用默认真实模型。
     */
    private String resolveProvider(GenerationConfig gc) {
        String p = gc == null ? null : gc.provider();
        if (p != null && !p.isBlank()) {
            return p;
        }
        return (defaultProvider == null || defaultProvider.isBlank()) ? "auto" : defaultProvider;
    }

    /**
     * 解析有效模型名：显式配置优先，否则用默认模型名。
     */
    private String resolveModel(GenerationConfig gc) {
        String m = gc == null ? null : gc.model();
        if (m != null && !m.isBlank()) {
            return m;
        }
        return (defaultModel == null || defaultModel.isBlank()) ? "deepseek-chat" : defaultModel;
    }

    /**
     * 从会话库回放历史（多轮上下文），失败退化为单轮。
     */
    private List<ModelAdapter.ChatMessage> loadHistory(AgentRunRequest req, String tenantId) {
        if (sessionService == null || req.sessionId() == null || req.sessionId().isBlank()) {
            return List.of();
        }
        int maxTurns = req.context() != null && req.context().maxHistoryTurns() != null
                ? req.context().maxHistoryTurns() : 10;
        try {
            return sessionService.recentMessages(tenantId, req.sessionId(), maxTurns * 2).stream()
                    .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                    .map(m -> new ModelAdapter.ChatMessage(m.role(), m.content()))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to load session history: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 提取最后一条用户消息文本（兼容纯文本与 parts[]，含 file 附件读取）。
     * <p>方案 A：消息 content 为 parts[] 时，text 块直接拼接；file 块经
     * {@link FileUploadService#readText} 把文件内容解析为文本并注入上下文。</p>
     */
    private String extractUserMessage(AgentRunRequest req, String tenantId) {
        AgentRunRequest.Message last = lastUserMessage(req);
        Object content = last == null ? null : last.content();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            return renderParts(parts, tenantId);
        }
        return content == null ? "" : String.valueOf(content);
    }

    private AgentRunRequest.Message lastUserMessage(AgentRunRequest req) {
        if (req.messages() == null || req.messages().isEmpty()) {
            return null;
        }
        for (int i = req.messages().size() - 1; i >= 0; i--) {
            AgentRunRequest.Message msg = req.messages().get(i);
            if ("user".equals(msg.role())) {
                return msg;
            }
        }
        return null;
    }

    /**
     * 组装 parts[] 为单个文本：text 直接拼接；file 下载解析后注入
     * {@code [文件：name]} 区块；不支持的 part 以占位说明代替（不阻断）。
     */
    private String renderParts(List<?> parts, String tenantId) {
        StringBuilder text = new StringBuilder();
        int fileCount = 0;
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> m)) {
                text.append(part == null ? "" : part).append('\n');
                continue;
            }
            String type = strOf(m.get("type"));
            if ("text".equals(type)) {
                String t = strOf(m.get("text"));
                if (!t.isBlank()) {
                    text.append(t).append('\n');
                }
            } else if ("file".equals(type)) {
                String fileId = strOf(m.get("fileId"));
                String fileName = strOf(m.get("fileName"));
                if (fileCount >= 4) {
                    text.append("\n[已跳过额外文件：" ).append(fileName).append("，单次最多读取 4 个文件]");
                    continue;
                }
                fileCount++;
                text.append("\n\n[文件：").append(fileName).append("]\n");
                text.append(readAttachedFile(tenantId, fileId, fileName));
            } else {
                text.append("\n[").append(type == null ? "未知" : type).append(" 类型暂不支持直接读取]\n");
            }
        }
        return text.toString().trim();
    }

    /** 读取附件文本（失败不阻断，把错误信息作为该文件的"内容"返回）。 */
    private String readAttachedFile(String tenantId, String fileId, String fileName) {
        if (fileUploadService == null) {
            return "(文件读取服务不可用)";
        }
        try {
            FileUploadService.FileText ft = fileUploadService.readText(tenantId, fileId);
            String body = ft.text() == null ? "" : ft.text();
            if (ft.truncated()) {
                body = body + "\n…（内容过长已截断，仅展示前 100k 字符）";
            }
            return body;
        } catch (Exception e) {
            log.warn("Read attached file {} failed: {}", fileId, e.getMessage());
            return "(文件读取失败：" + e.getMessage() + ")";
        }
    }

    private static String strOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}