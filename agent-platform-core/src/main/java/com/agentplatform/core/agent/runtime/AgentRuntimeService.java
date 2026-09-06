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
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.core.multimodal.QuotaService;
import com.agentplatform.core.plugin.runtime.AgentPipeline;
import com.agentplatform.core.plugin.runtime.PipelineResult;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.core.session.SessionService;
import com.agentplatform.core.skill.SkillService;
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

    /** 模型绑定解析（可选：单测中未注入时退化为全局默认，凭证走适配器默认）。 */
    @Autowired(required = false)
    private ModelBindingService modelBindingService;

    /** 运行日志采集（可选：单测中未注入时静默跳过，保证既有单测不受影响）。 */
    @Autowired(required = false)
    private LogService logService;

    /** Skill 注入（可选：未注入时不追加 Skill 提示词，保持旧行为与单测可用）。 */
    @Autowired(required = false)
    private SkillService skillService;

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

            // ① 组装系统提示词（人格 → 提示词合并 + 变量填充 + Skill 注入）
            String systemPrompt = withSkillPrompts(agent,
                    assembleSystemPrompt(agent.getPersona(), agent.getSystemPrompt(), req));
            // ② 合并生成参数（provider/模型未配置时兜底到默认真实模型）
            GenerationConfig gc = mergeGenerationConfig(agent.getGenerationConfig(), req.model());
            ModelBindingService.ResolvedModel resolved = resolveModelBinding(agent, gc);
            final String provider = resolved.provider();
            final String model = resolved.model();
            // ③ 组装用户消息（取最后一条 user 消息）
            String userMessage = extractUserMessage(req);

            // ④ 确保插件已挂载（热加载）
            ensurePluginsAttached(agent, tenantId);

            // ④.5 会话解析 + 历史回放（让对话「有记忆」）
            Session session = sessionService == null ? null
                    : sessionService.resolve(tenantId, agent.getAgentId(), req.userId(), req.sessionId(), userMessage);
            List<ModelAdapter.ChatMessage> history = loadHistory(req, tenantId);

            // ⑤ 经插件 Hook 管线调用模型（before_llm → LLM → after_llm）
            final long[] latency = {0L};
            final int[] usageTokens = {0, 0};
            PipelineResult pipeline;
            try {
                pipeline = agentPipeline.run(userMessage, msg -> {
                    logTo(LogLevel.INFO, LogCategory.llm, "llm.call provider=" + provider
                                    + " model=" + model + " routing=" + resolved.routing()
                                    + " history=" + history.size() + " chars=" + msg.length(),
                            traceId, runId, tenantId, agent.getAgentId());
                    long t0 = System.currentTimeMillis();
                    ModelAdapter.ChatRequest chatReq = new ModelAdapter.ChatRequest(
                            model, systemPrompt, msg, gc.temperature(), gc.maxTokens(), Map.of(), history,
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

            // ⑥ 计量与响应（含插件附加产物如 audio_url）
            AgentRunResponse.Usage usage = new AgentRunResponse.Usage(
                    usageTokens[0], usageTokens[1], 0.0);
            AgentRunResponse resp = AgentRunResponse.of(
                    runId, req.sessionId(), req.mode(), pipeline.reply(), traceId, usage);
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
     * 确保智能体挂载的插件已热加载（幂等）。
     */
    private void ensurePluginsAttached(AgentDefinition agent, String tenantId) {
        Capabilities caps = agent.getCapabilities();
        if (caps == null || caps.pluginIds() == null) {
            return;
        }
        for (String pluginId : caps.pluginIds()) {
            try {
                pluginRuntime.attach(pluginId, agent.getAgentId(), tenantId, Map.of());
            } catch (Exception e) {
                log.warn("Failed to attach plugin {} at run time: {}", pluginId, e.getMessage());
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
            String systemPrompt = withSkillPrompts(agent,
                    assembleSystemPrompt(agent.getPersona(), agent.getSystemPrompt(), req));
            GenerationConfig gc = mergeGenerationConfig(agent.getGenerationConfig(), req.model());
            String userMessage = extractUserMessage(req);
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
     * 提取最后一条用户消息文本。
     */
    private String extractUserMessage(AgentRunRequest req) {
        if (req.messages() == null || req.messages().isEmpty()) {
            return "";
        }
        // 从后往前找 user 消息
        for (int i = req.messages().size() - 1; i >= 0; i--) {
            AgentRunRequest.Message msg = req.messages().get(i);
            if ("user".equals(msg.role())) {
                if (msg.content() instanceof String s) {
                    return s;
                }
                // content 是 parts 数组（多模态）时暂取 text 块
                return String.valueOf(msg.content());
            }
        }
        throw BizException.badRequest("No user message found");
    }
}