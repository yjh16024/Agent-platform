package com.agentplatform.core.agent.runtime;

import com.agentplatform.framework.api.Principal;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.ToolContext;
import com.agentplatform.framework.api.ToolOptions;
import com.agentplatform.framework.api.ToolResult;
import com.agentplatform.framework.api.Tool;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.ModelCapability;
import com.agentplatform.framework.api.SessionHandle;

import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.common.util.TraceContext;
import com.agentplatform.core.agent.dto.AgentRunRequest;
import com.agentplatform.core.agent.dto.AgentRunResponse;
import com.agentplatform.core.agent.dto.RunStreamEvent;
import com.agentplatform.core.agent.service.AgentService;
import com.agentplatform.core.log.LogCategory;
import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogLevel;
import com.agentplatform.core.log.LogService;
import com.agentplatform.core.notification.NotificationService;
import com.agentplatform.core.memory.UserFactExtractor;
import com.agentplatform.core.rag.ConversationAttachmentService;
import com.agentplatform.core.rag.retriever.HybridRetriever;
import com.agentplatform.core.rag.retriever.RetrievalResult;
import com.agentplatform.model.entity.DocumentEntity;
import com.agentplatform.model.enums.NotificationLevel;
import com.agentplatform.model.enums.NotificationType;
import com.agentplatform.model.repository.AgentPluginRepository;
import com.agentplatform.model.repository.DocumentRepository;
import com.agentplatform.core.multimodal.FileUploadService;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.model.secret.ModelBindingService;
import com.agentplatform.core.multimodal.QuotaService;
import com.agentplatform.core.plugin.runtime.AgentPipeline;
import com.agentplatform.core.plugin.runtime.DomainEventBus;
import com.agentplatform.core.plugin.runtime.ExtensionRegistry;
import com.agentplatform.core.plugin.runtime.PipelineResult;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import com.agentplatform.core.session.SessionService;
import com.agentplatform.core.session.SessionSummaryService;
import com.agentplatform.core.skill.SkillService;
import com.agentplatform.core.tool.ToolSchemas;
import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.model.entity.AgentDefinition;
import com.agentplatform.model.record.Capabilities;
import com.agentplatform.model.record.GenerationConfig;
import com.agentplatform.model.record.Persona;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /*
     * 提示词模板变量 `{{var}}`。
     *
     * 字符类里的 `.` 不需要转义（只有类外才是"任意字符"），写成 `\\.` 会被静态检查
     * 标为"冗余转义"。`-` 放在末尾也不必转义。
     */
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.\\-]+)\\s*}}");

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

    /**
     * 框架引擎（可选，默认 {@code legacy}）。
     *
     * <p>它存在时并不代表立刻接管——只有 {@code agent-platform.runtime.engine=framework}
     * 且本轮请求满足条件时才会走它。默认关闭是刻意的：切换要以「行为等价」为前提。</p>
     */
    @Autowired(required = false)
    private FrameworkRunEngine frameworkRunEngine;

    /**
     * 跨会话向量记忆开关（2026-10-28）。
     *
     * <p><b>它是什么</b>：每轮对话会拿当前这句话去做一次 embedding，
     * 然后从**该用户其它会话**里召回语义相关的片段。</p>
     *
     * <p><b>为什么要开关</b>：这是**每轮同步执行**的，要花一次 embedding 调用
     * （约 200~500ms）。对 QQ 单聊场景，`/clear` 之后的记忆主要靠
     * {@code writeSessionMemory}（插件侧存的要点）+ 画像，向量记忆只是补充。</p>
     *
     * <p>默认开启（保持原行为）。觉得慢可以在 application.yml 里关掉：
     * {@code agent-platform.memory.recall-enabled: false}。</p>
     */
    @Value("${agent-platform.memory.recall-enabled:true}")
    private boolean memoryRecallEnabled;

    /** 用户长期画像（长期记忆，可选：未注入时不注入画像，保持原行为与单测可用）。 */
    @Autowired(required = false)
    private com.agentplatform.core.memory.UserFactService userFactService;

    /** 向量记忆（历史对话语义召回，可选：未注入时不召回，保持原行为与单测可用）。 */
    @Autowired(required = false)
    private com.agentplatform.core.memory.ConversationMemoryService conversationMemoryService;

    /**
     * 配置读取（2026-10-20）：用于取「输出格式」指引这类**可在平台设置里改**的提示词片段。
     *
     * <p>可选注入：单测里没有 Environment 时退化为用注册表里的默认值，行为不变。</p>
     */
    @Autowired(required = false)
    private org.springframework.core.env.Environment environment;

    /**
     * 工具调用记录收集器（前端「工具调用可视化」的数据来源）。
     *
     * <p>可选注入：缺失时退化为"不记录"，**不影响运行本身** ——
     * 它只服务于展示，不该成为能否跑通对话的条件。</p>
     */
    @Autowired(required = false)
    private com.agentplatform.core.tool.executor.ToolCallCollector toolCallCollector;

    /**
     * 流式链路要切到的调度器：**每个订阅一个虚拟线程**。
     *
     * <p>为什么不用 {@code Schedulers.boundedElastic()}：它的线程数是
     * {@code 10 × CPU} 且**有硬上限**。而这条链路会做**可能长时间阻塞**的事 ——
     * 最典型的是工具审批的"就地确认"（阻塞等用户点按钮，默认最多 5 分钟）。
     * 用固定大小的池，几个并发的待确认就能把它占满，**导致别的用户连流式都用不了** ——
     * 一个等待用户点按钮的操作，不该有这种全局影响。</p>
     *
     * <p>虚拟线程按需创建、阻塞时只占极少堆外内存，而且服务端已开启
     * {@code server.tomcat.threads.virtual.enabled}，整条链路是一致的。</p>
     */
    private static final Scheduler VIRTUAL_SCHEDULER =
            Schedulers.fromExecutor(Executors.newVirtualThreadPerTaskExecutor());

    /** 模型绑定解析（可选：单测中未注入时退化为全局默认，凭证走适配器默认）。 */
    @Autowired(required = false)
    private ModelBindingService modelBindingService;

    /** 运行日志采集（可选：单测中未注入时静默跳过，保证既有单测不受影响）。 */
    @Autowired(required = false)
    private LogService logService;

    /** 站内通知：运行失败时提醒发起人（可选：未注入时静默跳过，保证既有单测不受影响）。 */
    @Autowired(required = false)
    private NotificationService notificationService;

    /** 领域事件总线：把运行完成/失败派发给订阅了的插件（可选：未注入时静默跳过）。 */
    @Autowired(required = false)
    private DomainEventBus domainEventBus;

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

    /**
     * 插件配置里密钥字段的解密（可选：未注入时按原样使用）。
     *
     * <p>库中保存的密钥是密文（见 {@code PluginConfigSecrets}），而插件必须拿到<b>明文</b>
     * 才能去调厂商。漏掉这一步的表现是「配置明明填了、插件却用不了」——
     * 而每一段代码单独看都是对的，属于最难排查的一类问题，所以宁可在这里也判空兜住。</p>
     */
    @Autowired(required = false)
    private com.agentplatform.core.plugin.marketplace.PluginConfigSecrets pluginConfigSecrets;

    /**
     * 画像自动抽取（可选：未注入时完全不抽取，保持既有行为）。
     *
     * <p>调用点在对话收尾处，且是 **fire-and-forget** —— 它内部有开关（默认关）、
     * 自己吞掉全部异常，所以这里只需判空后调用，不必包 try/catch、也不该等它。</p>
     */
    @Autowired(required = false)
    private UserFactExtractor userFactExtractor;

    /**
     * 工具调用循环最大轮数（防止模型在 tool_calls 里死循环）。
     *
     * <p><b>2026-09-22：由写死的 5 提为可配置、默认 20。</b>旧值是为「文本注入」式回灌设的 ——
     * 那种做法每轮都要把累积的观察文本整段重发，轮数一多 token 就爆炸，所以只能压到 5。
     * 改用原生 tool-role 回灌后，每轮只在消息序列尾部追加两条（assistant + tool），
     * 上下文成本与轮数近似线性，20 轮是可接受的；而真实的编程类任务
     * （查文件 → 读内容 → 改 → 跑测试 → 再改）经常会用掉十几轮。</p>
     */
    @Value("${agent-platform.agent.tool.max-rounds:20}")
    private int maxToolRounds = 20;

    /** 默认模型 provider / 名称（智能体未显式配置时兜底到真实模型）。 */
    @Value("${agent-platform.model.default-provider:deepseek}")
    private String defaultProvider;
    @Value("${agent-platform.model.default-name:deepseek-chat}")
    private String defaultModel;

    /**
     * 运行前的「上下文准备」结果（仅为 {@link #prepare} 的载体，不对外暴露）。
     *
     * @param agent           已解析的智能体定义
     * @param userMessage     本轮用户消息（parts[] 含 file 时已注入文件文本）
     * @param imageExtra      图片附件（base64），无图时为 {@code Map.of()}
     * @param session         会话（{@code sessionId} 为空时为 null，表示不持久化）
     * @param gc              合并后的生成参数（请求级覆盖已生效）
     * @param resolved        解析后的模型绑定（provider/model/baseUrl/apiKey/routing）
     * @param rag             RAG 检索结果（含引用溯源；未命中时为 null）
     * @param effectivePrompt 最终系统提示词（人格 → Skill → RAG → 中期摘要 → 长期画像 → 向量召回）
     * @param sessionHistory  历史回放消息
     * @param toolSpecs       本轮可用工具声明（未启用时为空列表）
     */
    private record PreparedRun(
            AgentDefinition agent,
            String userMessage,
            Map<String, Object> imageExtra,
            SessionHandle session,
            GenerationConfig gc,
            ModelBindingService.ResolvedModel resolved,
            RagRender rag,
            String effectivePrompt,
            List<ModelAdapter.ChatMessage> sessionHistory,
            List<ModelAdapter.ToolSpec> toolSpecs) {
    }

    /**
     * 运行前的上下文准备 —— {@link #run} 与 {@link #runStream} 的**唯一**实现。
     *
     * <h3>为什么要抽这一步（2026-09-25）</h3>
     * 这两个入口原本**各自复制了一套完全相同的准备逻辑**（约 70 行）：配额校验 →
     * 会话解析 → 人格/Skill 组装 → RAG 检索 → 中期摘要 → 长期画像 → 向量召回 →
     * 插件热挂载 → 历史回放 → 工具声明。
     *
     * <p>而这段流程的**顺序是设计契约**（短期 → 中期 → 长期 → 向量，见
     * {@code ConversationMemoryService} 类注释）。两处复制意味着：只改一边就会造成
     * <b>「流式与非流式行为不一致」</b> —— 且这种 bug 只在开流式时显现，极难排查
     * （2026-09-22 之前的流式链路就曾长期缺失工具与钩子）。</p>
     *
     * <p>抽成一份之后，"改一处漏一处"在结构上不再可能。
     * 回归保护见 {@code AgentRuntimeServicePromptParityTest}。</p>
     *
     * @param streaming 仅影响启动日志里是否带 {@code streaming=true} 标记，
     *                  不改变准备结果 —— 两个入口拿到的 {@link PreparedRun} 必须等价。
     */
    private PreparedRun prepare(AgentRunRequest req, String runId, String traceId, String tenantId,
                                boolean streaming) {
        return prepare(req, runId, traceId, tenantId, streaming, true);
    }

    /**
     * 走框架运行时跑一轮（{@code agent-platform.runtime.engine=framework} 时）。
     *
     * <p><b>与 legacy 路径的三处刻意差异</b>：
     * <ol>
     *   <li><b>不预占配额</b>：{@code prepare(..., reserveQuota=false)}，
     *       配额由框架的 {@code QuotaPolicy} 在运行开始占一次。两处都占就是双计。</li>
     *   <li><b>不调 {@code recordExchange}</b>：框架的 {@code SessionStore}
     *       （平台实现是 {@code MessageTableSessionStore}）已经写同一张 message 表，再写一次就是双写。</li>
     *   <li><b>不跑平台侧收尾</b>：向量索引、画像抽取、失败通知都留在 legacy 路径——
     *       它们依赖平台独有的服务，强行搬进来等于把平台语义塞回框架。</li>
     * </ol>
     *
     * <p>工具调用记录直接取框架返回的 {@code RunResult.toolCalls()}，
     * 不再经过 {@code toolCallCollector}（那条收集器是 legacy 的旁路）。</p>
     */
    private AgentRunResponse runOnFramework(AgentRunRequest req, String runId, String traceId, String tenantId) {
        // 准备阶段同样复用：提示词组装、会话落库、模型绑定解析都由平台算好，框架不重算
        PreparedRun p = prepare(req, runId, traceId, tenantId, false, false);
        AgentDefinition agent = p.agent();

        RunCommand command = FrameworkRunDispatcher.toCommand(
                agent.getAgentId(),
                p.userMessage(),
                req.sessionId(),
                tenantId,
                principalOf(req, tenantId),
                agent,
                p.resolved(),
                p.effectivePrompt(),
                List.of(),
                ToolOptions.all(3),
                p.imageExtra());

        try {
            RunResult result = frameworkRunEngine.run(command);
            logTo(LogLevel.INFO, LogCategory.agent,
                    "run.completed engine=framework agent=" + agent.getName() + " model=" + result.model(),
                    traceId, runId, tenantId, agent.getAgentId());
            return FrameworkRunDispatcher.toResponse(result, req.mode(), traceId);
        } catch (RuntimeException e) {
            logTo(LogLevel.ERROR, LogCategory.agent,
                    "run.failed engine=framework agent=" + agent.getName() + " error=" + e.getMessage(),
                    traceId, runId, tenantId, agent.getAgentId(), stackTraceOf(e));
            throw e;
        }
    }

    /**
     * 组装调用者身份。没有 {@code user_id} 时返回 {@code null}——
     * 框架的默认策略放行，平台的 RBAC 策略（若启用）会 fail-closed 拒绝。
     */
    private Principal principalOf(AgentRunRequest req, String tenantId) {
        String userId = req.userId();
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return new Principal(userId, tenantId, Set.of());
    }

    /**
     * @param reserveQuota 是否在准备阶段预占配额。
     *                     框架引擎分支传 {@code false}：那里由 {@code QuotaPolicy} 在运行开始时占一次，
     *                     两处都占就是**双计**（不报错，但账单翻倍）。
     */
    private PreparedRun prepare(AgentRunRequest req, String runId, String traceId, String tenantId,
                                boolean streaming, boolean reserveQuota) {
        AgentDefinition agent = agentService.getOrThrow(tenantId, req.agentId());

        logTo(LogLevel.INFO, LogCategory.agent, "run.start agent=" + agent.getName()
                        + " mode=" + req.mode() + " session=" + req.sessionId()
                        + (streaming ? " streaming=true" : "")
                        + (frameworkRunEngine != null && frameworkRunEngine.isEnabled() ? " engine=framework" : ""),
                traceId, runId, tenantId, agent.getAgentId());

        // ⓪ 配额校验（多租户，流式同样计入一次调用）
        if (reserveQuota && quotaService != null) {
            quotaService.checkAndIncrement(tenantId, "model_calls", null);
        }

        // ① 合并生成参数（provider/模型未配置时兜底到默认真实模型）+ 解析模型绑定
        GenerationConfig gc = mergeGenerationConfig(agent.getGenerationConfig(), req.model());
        ModelBindingService.ResolvedModel resolved = resolveModelBinding(agent, gc);

        // ② 组装用户消息（取最后一条 user 消息；parts[] 含 file 时注入文件文本）
        String userMessage = extractUserMessage(req, tenantId);
        // ②.1 提取图片附件（方案 B：image part → 字节 → base64，随请求走视觉通道）
        List<Map<String, String>> imageData = collectImageData(req, tenantId);
        /*
         * extra 一并带上采样惩罚（2026-10-27）。
         *
         * <p>{@code frequency_penalty} / {@code presence_penalty} 是治「模型口癖」的：
         * DeepSeek 自带「不是A，而是B」「你以为是X，其实是Y」「首先/其次」这类句式，
         * 长对话里还会反复围绕同一主题打转。这两个值在 {@link GenerationConfig}
         * 里**早就有字段**，但从来没被传给适配器 —— 典型的"定义了没人用"。</p>
         *
         * <p>适配器的 extra 会原样 {@code body.putAll(...)}，所以这里塞进去就生效，
         * 不用动适配器。**没配就不传**（null 忽略），保持"默认行为不动"。</p>
         *
         * <p>中文对惩罚比较敏感，值调大了会句子断裂；前端默认留空，让用户自己试。</p>
         */
        Map<String, Object> extraMap = new java.util.LinkedHashMap<>();
        if (!imageData.isEmpty()) {
            extraMap.put("images", imageData);
        }
        if (gc.frequencyPenalty() != null) {
            extraMap.put("frequency_penalty", gc.frequencyPenalty());
        }
        if (gc.presencePenalty() != null) {
            extraMap.put("presence_penalty", gc.presencePenalty());
        }
        Map<String, Object> imageExtra = Map.copyOf(extraMap);

        // ③ 会话解析（提前到提示词组装前：历史较长时需读取早期摘要；sessionId 为空返回 null 不持久化）
        SessionHandle session = sessionService == null ? null
                : sessionService.resolve(tenantId, agent.getAgentId(), req.userId(), req.sessionId(), userMessage);

        // ④.1 组装基础系统提示词（人格 → 提示词合并 + 变量填充 + Skill 注入）
        // 顺序：人格/模板 → Skill 正文 → 输出格式说明（格式说明放最后，最不容易被长 Skill 淹没）
        String basePromptRaw = withSkillPrompts(agent,
                assembleSystemPrompt(agent.getPersona(), agent.getSystemPrompt(), req));
        /*
         * 「输出格式」指引按**渠道**决定（2026-10-20）。
         *
         * 网页对话会渲染 Markdown 与 chart，注入它能让排版更好；但 QQ / 短信 / 终端这类
         * 纯文本渠道传 `renderGuide=false` —— 模型**直接输出纯文本**，
         * 比"先让它输出 `**粗体**` 再在插件里擦掉标记"干净得多：
         * 事后清洗只能删符号，表格结构与嵌套列表早被压平了。
         */
        String basePrompt = Boolean.FALSE.equals(req.renderGuide())
                ? basePromptRaw
                : withRenderGuide(basePromptRaw);
        /*
         * 插件注入的运行时上下文（2026-10-27）。
         *
         * <p>QQ 插件把「现在几点、对方是谁、最近日记、分寸」这些放在
         * {@code metadata.injectContext} 里传过来，**不再拼进 userMessage** ——
         * 因为那会被 {@code persistExchange} 原样存进 {@code message} 表。</p>
         *
         * <p>实测后果：353 条 user 消息平均 **1654 字**，而她的回复只有 154 字，
         * 也就是说**每条里有 1500 字是注入**（占 83%）。历史回放时这些过期信息
         * 又被全部带回来，token 直接膨胀约 4 倍。</p>
         *
         * <p>拼到这里：**当轮可见、不落库**。</p>
         */
        Object injectCtx = req.metadata() == null ? null : req.metadata().get("injectContext");
        String withInject = (injectCtx instanceof String inj && !inj.isBlank())
                ? basePrompt + "\n\n" + inj
                : basePrompt;
        // ④.2 RAG 知识库自动检索（请求/智能体绑定知识库时，把命中片段注入上下文并生成引用；
        // 未绑定知识库/检索失败时 retrieveKnowledge 返回 null，等价于普通对话）
        // ④.3 对话附件自动摄取（方案 C）：文档拖入 → 归入租户附件库 → 并入本次检索范围
        String attachmentKbId = ensureAttachments(req, tenantId);
        RagRender rag = retrieveKnowledge(agent, req, userMessage, attachmentKbId);
        String promptWithRag = rag == null || rag.systemBlock() == null || rag.systemBlock().isBlank()
                ? withInject
                : withInject + rag.systemBlock();

        // ④.6 早期会话摘要续接（历史被截断丢弃的早期轮次压缩进上下文，让「记忆」跨长对话保留）
        String earlySummary = earlySummaryOf(session);
        String promptWithSummary = earlySummary == null
                ? promptWithRag
                : promptWithRag + "\n\n## 早期会话摘要（较早轮次已压缩，仅作背景参考）\n" + earlySummary;
        // 长期记忆（用户主动填写的画像）拼在中期摘要之后 ——
        // 与设计约定的召回顺序一致：短期（会话历史）→ 中期（摘要）→ 长期（画像）→ 向量
        String userProfile = profileOf(tenantId, req.userId());
        String promptWithProfile = userProfile == null
                ? promptWithSummary
                : promptWithSummary + userProfile;
        // 向量记忆（第四层）：按语义从该用户**其它会话**里召回相关片段
        String recalledHistory = recallHistory(tenantId, req.userId(), userMessage,
                session == null ? null : session.sessionId());
        String effectivePrompt = recalledHistory == null
                ? promptWithProfile
                : promptWithProfile + recalledHistory;

        // ⑤ 确保插件已挂载（热加载；否则本次运行的钩子根本不存在）
        ensurePluginsAttached(agent, tenantId);

        // ⑥ 历史回放（会话已在上方解析；让对话「有记忆」）+ 工具声明
        List<ModelAdapter.ChatMessage> sessionHistory = loadHistory(req, tenantId);
        List<ModelAdapter.ToolSpec> toolSpecs = resolveToolSpecs(req);

        return new PreparedRun(agent, userMessage, imageExtra, session, gc, resolved, rag,
                effectivePrompt, sessionHistory, toolSpecs);
    }

    /**
     * 执行一次 Agent 运行（同步）。
     */
    public AgentRunResponse run(AgentRunRequest req) {
        String runId = req.runId() != null ? req.runId() : IdGenerator.generate("run");
        String traceId = IdGenerator.generate("trace");
        String tenantId = req.tenantId();
        // 工具调用记录（可视化用）：按运行开始收集。
        // 刻意不套 try/finally —— 收集器在下次 begin 时会清理超时残留，
        // 而给这个长方法整体加一层 try 会把几百行的缩进一起改掉，得不偿失。
        if (toolCallCollector != null) {
            toolCallCollector.begin(runId);
        }

        return TraceContext.withContext(traceId, runId, tenantId, () -> {
            // 主链路分流（P2' 最后一步）：框架引擎启用时整轮交给框架运行时
            if (frameworkRunEngine != null && frameworkRunEngine.isEnabled()) {
                return runOnFramework(req, runId, traceId, tenantId);
            }

            // 准备阶段（与非流式共用唯一实现，见 prepare 的注释）
            final PreparedRun p = prepare(req, runId, traceId, tenantId, false);
            final AgentDefinition agent = p.agent();
            final String userMessage = p.userMessage();
            final Map<String, Object> imageExtra = p.imageExtra();
            final SessionHandle session = p.session();
            final GenerationConfig gc = p.gc();
            final ModelBindingService.ResolvedModel resolved = p.resolved();
            final String provider = resolved.provider();
            final String model = resolved.model();
            final RagRender rag = p.rag();
            final String effectivePrompt = p.effectivePrompt();
            final List<ModelAdapter.ChatMessage> sessionHistory = p.sessionHistory();
            final List<ModelAdapter.ToolSpec> toolSpecs = p.toolSpecs();

            // ⑤ 经插件 Hook 管线调用模型（before_llm → LLM → after_llm）
            final long[] latency = {0L};
            final int[] usageTokens = {0, 0};
            /*
             * 非流式的思考过程（2026-10-19）：`AgentPipeline.run` 的 llmAction 只返回文本，
             * reasoning 会在管线里丢掉 —— 所以在闭包外用一个单元素数组接住。
             * ⚠️ 工具调用链路（runToolLoop）不经过这里，目前不取思考过程（它内部多次调用模型，
             * "这一轮的思考"归哪一次本身就是个问题）。
             */
            final String[] reasoningHolder = {""};
            PipelineResult pipeline;
            try {
                pipeline = agentPipeline.run(agent.getAgentId(), runId, userMessage, msg -> {
                    // 工具调用循环：仅当请求显式启用工具且注册中心可用时走工具链路
                    if (!toolSpecs.isEmpty() && toolExecutor != null) {
                        return runToolLoop(provider, model, effectivePrompt, msg, gc, resolved,
                                toolSpecs, sessionHistory, traceId, runId, tenantId, agent, usageTokens,
                                imageExtra, session == null ? null : session.sessionId());
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
                    ModelAdapter.ChatResponse resp = chatWithRetry(provider, chatReq);
                    latency[0] = resp.latencyMs();
                    usageTokens[0] = resp.promptTokens();
                    usageTokens[1] = resp.completionTokens();
                    // 接住思考过程（普通模型为空串，不是错误）
                    reasoningHolder[0] = resp.reasoning() == null ? "" : resp.reasoning();
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
                notifyRunFailed(agent.getName(), model, e);
                // 领域事件：让"订阅了的插件"能对运行失败做反应（上报监控、切备用模型、
                // 写外部审计…）。与上面的通知是两条独立通道 —— 通知面向**人**、事件面向**代码**，
                // 且事件只派发给**这台智能体**上订阅了的插件（挂到别的智能体上的收不到）。
                publishAgentEvent(EventTypes.AGENT_RUN_FAILED, tenantId, agent.getAgentId(), runId,
                        DomainEvent.payload(
                                "agent_id", agent.getAgentId(),
                                "agent_name", agent.getName(),
                                "model", model,
                                "error", briefError(e)));
                throw e;
            }

            // ⑤.1 插件兜底（on_error）成功时 LLM 异常不会走到上面的 catch，
            //     这里补记一条 WARN —— 否则"失败"在日志与审计里会凭空消失，
            //     而用户实际收到的是兜底话术，事后排查会完全对不上账。
            if (pipeline.extras() != null
                    && Boolean.TRUE.equals(pipeline.extras().get("error_handled"))) {
                logTo(LogLevel.WARN, LogCategory.agent,
                        "run.degraded agent=" + agent.getName() + " model=" + model
                                + " error=" + pipeline.extras().get("error_message")
                                + "（已由插件 on_error 兜底）",
                        traceId, runId, tenantId, agent.getAgentId());
            }

            // ⑥ 计量与响应（含插件附加产物如 audio_url；RAG 检索命中时附带引用溯源）
            AgentRunResponse.Usage usage = new AgentRunResponse.Usage(
                    usageTokens[0], usageTokens[1], 0.0);
            AgentRunResponse resp = AgentRunResponse.of(
                    runId, req.sessionId(), req.mode(), pipeline.reply(), traceId, usage);
            if (rag != null && !rag.references().isEmpty()) {
                resp = new AgentRunResponse(resp.runId(), resp.sessionId(), resp.mode(),
                        resp.output(), resp.traceId(), resp.usage(), rag.references(), resp.plugins(),
                        resp.toolCalls());
            }
            if (pipeline.extras() != null && pipeline.extras().containsKey("audio_url")) {
                resp = new AgentRunResponse(resp.runId(), resp.sessionId(), resp.mode(),
                        new AgentRunResponse.Output("assistant", resp.output().content(),
                                String.valueOf(pipeline.extras().get("audio_url")),
                                resp.output().reasoning()),
                        resp.traceId(), resp.usage(), resp.references(), resp.plugins(), resp.toolCalls());
            }
            /*
             * 把思考过程挂到输出上（2026-10-19）。放在 audio 之后：
             * 这样重建 Output 时能一并带上 audioUrl，不会互相覆盖。
             */
            if (!reasoningHolder[0].isEmpty()) {
                resp = new AgentRunResponse(resp.runId(), resp.sessionId(), resp.mode(),
                        new AgentRunResponse.Output("assistant", resp.output().content(),
                                resp.output().audioUrl(), reasoningHolder[0]),
                        resp.traceId(), resp.usage(), resp.references(), resp.plugins(), resp.toolCalls());
            }
            // 工具调用记录（可视化）：从收集器取走（drain 同时清理该运行的槽位）
            if (toolCallCollector != null) {
                resp = resp.withToolCalls(toolCallCollector.drain(runId));
            }
            // ⑦ 保存会话记忆（user + assistant 一轮），失败不阻断主流程
            if (session != null && sessionService != null) {
                try {
                    sessionService.recordExchange(tenantId, session.sessionId(), runId,
                            userMessage, pipeline.reply(), model);
                } catch (Exception e) {
                    log.warn("Failed to persist session exchange: {}", e.getMessage());
                }
                // 向量记忆：索引本轮用户消息，供**其它会话**将来按语义召回
                //（放在落库之后：索引要靠回查刚写入的那条消息拿 messageId）
                indexConversationMemory(tenantId, req.userId(), session.sessionId(), userMessage);
            }

            // 画像自动抽取：fire-and-forget，产物是「待用户确认」的候选（不会直接生效）。
            // 放在收尾处、且不等它 —— 抽取失败或慢都不该影响用户拿到回复。
            if (userFactExtractor != null) {
                userFactExtractor.extractAsync(tenantId, req.userId(), req.sessionId(),
                        userMessage, pipeline.reply());
            }

            logTo(LogLevel.INFO, LogCategory.agent, "run.completed agent=" + agent.getName()
                            + " model=" + model + " latency=" + latency[0] + "ms"
                            + " shortCircuit=" + pipeline.shortCircuited(),
                    traceId, runId, tenantId, agent.getAgentId());
            log.info("Run {} completed: agent={}, model={}, shortCircuit={}",
                    runId, req.agentId(), model, pipeline.shortCircuited());
            // 领域事件：运行成功完成。典型订阅场景 —— 用量统计上报、结果二次加工、
            // 外部系统同步。payload 只放"确定稳定"的字段：订阅者读到的 key 就是契约。
            publishAgentEvent(EventTypes.AGENT_RUN_COMPLETED, tenantId, agent.getAgentId(), runId,
                    DomainEvent.payload(
                            "agent_id", agent.getAgentId(),
                            "agent_name", agent.getName(),
                            "model", model,
                            "short_circuited", pipeline.shortCircuited(),
                            "latency_ms", latency[0],
                            "prompt_tokens", usageTokens[0],
                            "completion_tokens", usageTokens[1]));
            return resp;
        });
    }

    /**
     * 发送"运行失败"通知（失败静默）。
     *
     * <p>收件人取当前登录用户，即发起本次运行的人。注意：若这次失败已被插件
     * {@code on_error} 兜底，走不到这里 —— 那种情况在上面的 {@code run.degraded}
     * 分支里另行处理（用户已拿到兜底回复，不该再收到一条"失败"提醒）。</p>
     */
    private void notifyRunFailed(String agentName, String model, Exception e) {
        if (notificationService == null) {
            return;
        }
        try {
            notificationService.notifyCurrent(NotificationType.task, NotificationLevel.error,
                    "智能体运行失败：" + agentName,
                    "模型 " + model + " 调用失败：" + briefError(e)
                            + "。可在「运行日志」查看完整堆栈。",
                    "/logs");
        } catch (Exception ex) {
            log.debug("发送运行失败通知失败（已忽略）：{}", ex.getMessage());
        }
    }

    /** 异常信息摘要（超长截断，避免文案撑爆通知正文列宽）。 */
    private static String briefError(Exception e) {
        String msg = e == null ? null : e.getMessage();
        if (msg == null || msg.isBlank()) {
            return "(无错误信息)";
        }
        return msg.length() <= 200 ? msg : msg.substring(0, 197) + "...";
    }

    /**
     * 发布 agent 级领域事件（失败静默，绝不影响主流程）。
     *
     * <p>与通知是两条独立通道：通知写库、面向<b>人</b>；事件只做进程内派发、面向
     * <b>订阅了的插件</b>。两者互不依赖 —— 没有订阅者时事件派发是零成本的空操作。</p>
     */
    private void publishAgentEvent(String type, String tenantId, String agentId, String runId,
                                   Map<String, Object> payload) {
        if (domainEventBus == null) {
            return;
        }
        try {
            domainEventBus.publish(DomainEvent.ofAgent(type, tenantId, agentId, runId, payload));
        } catch (Exception e) {
            log.debug("发布事件 {} 失败（已忽略）：{}", type, e.getMessage());
        }
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
            /*
             * ⚠️ 单个工具的 schema 出问题**绝不能拖垮整场对话**。
             *
             * inputSchema() 各工具是**手写字符串**（见各 fs 工具），写错一个转义就会抛异常；
             * 而本方法在 prepare() 里、是发起对话的必经之路 —— 不兜住的话，后果是
             * "**对话功能整个不可用**"，用户只看到一句语焉不详的报错
             * （2026-09-28 实际故障：FsGrepTool 少了一层转义，整轮对话直接失败，
             * 且因为当时异常没记堆栈，定位花了很久）。
             *
             * 正确处置：**跳过这个工具并留痕** —— 模型少一个工具，远好过完全不能对话。
             */
            ModelAdapter.ToolSpec spec;
            try {
                // schema 规范化：null / 缺 type 会让厂商直接 400（schema must be 'type: object'）
                spec = new ModelAdapter.ToolSpec(tool.name(), tool.description(),
                        ToolSchemas.orEmpty(tool.inputSchema()));
            } catch (Exception e) {
                log.warn("[tool] 跳过 schema 非法的工具「{}」（不影响其余工具可用）：{}",
                        tool.name(), e.getMessage());
                continue;
            }
            specs.add(spec);
        }
        return specs;
    }

    /**
     * 执行工具调用循环（P1）：注入 tools → 模型返回 tool_calls → 逐个执行 →
     * 以<b>原生 tool-role 消息</b>回灌下一轮 → 直至模型不再请求工具。
     *
     * <h3>2026-09-22：由「文本注入」改为原生 function calling 协议</h3>
     * 旧做法把每次调用的名称/入参/结果拼成一段文本、追加到用户消息后重新请求。它确实
     * 兼容性好（不动消息协议），但代价很实在：
     * <ul>
     *   <li>模型看到的是「聊天记录里的旁白」而不是协议级的工具结果 —— 多轮时容易把
     *       历史里的旧结果误当成当前轮的结果；</li>
     *   <li>每轮都要把累积的观察文本整段重发，token 随轮数近似平方增长；</li>
     *   <li>工具返回的结构化 JSON（{@code LocalMcpClient}/{@code HttpMcpClient} 都返回 JSON）
     *       被压成自然语言，模型得再解析一次，丢字段很常见。</li>
     * </ul>
     * 现在改为标准两跳：请求带 {@code tools} → 模型回 {@code assistant(tool_calls)} →
     * 执行后回 {@code role=tool} 消息（带 {@code tool_call_id}）→ 再问一次。
     * 消息序列由 {@code history} 承载，所以<b>第二轮起 {@code userMessage} 传 null</b>
     * （各适配器已支持空 userMessage 不再补一条空消息）。
     *
     * <p>⚠️ <b>工具声明每轮都会带上</b>（旧实现只在第一轮给）—— 否则模型从第二轮起
     * 就再也调不到工具，多步任务会中途「失忆」。</p>
     *
     * <p>⚠️ <b>assistant(tool_calls) 这条消息必须记回序列</b>：工具结果消息要靠
     * {@code tool_call_id} 指回它，缺了上游会因「引用了不存在的调用」直接 400。</p>
     */
    private String runToolLoop(
            String provider, String model, String systemPrompt, String userMessage,
            GenerationConfig gc, ModelBindingService.ResolvedModel resolved,
            List<ModelAdapter.ToolSpec> tools, List<ModelAdapter.ChatMessage> history,
            String traceId, String runId, String tenantId, AgentDefinition agent, int[] usageTokens,
            Map<String, Object> extraImages, String sessionId) {
        // 本轮对话序列：会话历史 + 当前用户消息；此后每轮的 assistant(tool_calls) 与
        // tool 结果都追加在尾部，下一轮整段作为 history 发出。
        List<ModelAdapter.ChatMessage> convo = new ArrayList<>(history);
        // 仅第一轮带用户消息；此后消息都在 convo 里，重复发送等于重复提问。
        String nextUserMessage = userMessage;
        boolean toolDeclared = tools != null && !tools.isEmpty();
        /*
         * 搜图轮数护栏（2026-10-22）。
         *
         * 提示词允许它"三批都不满意就换关键词重搜"，但那是**软约束** ——
         * 模型可能一直搜下去，每批 3 张图都进多模态 token 预算。
         * 这里硬性限制最多 3 批，第 4 次直接告诉它别再搜了。
         */
        int imageSearchCalls = 0;

        for (int round = 0; round < maxToolRounds; round++) {
            logTo(LogLevel.INFO, LogCategory.llm, "llm.call provider=" + provider + " model=" + model
                            + " routing=" + resolved.routing() + " round=" + round
                            + " tools=" + (toolDeclared ? tools.size() : 0)
                            + " history=" + convo.size() + " chars="
                            + (nextUserMessage == null ? 0 : nextUserMessage.length()),
                    traceId, runId, tenantId, agent.getAgentId());
            long t0 = System.currentTimeMillis();
            ModelAdapter.ChatRequest chatReq = new ModelAdapter.ChatRequest(
                    model, systemPrompt, nextUserMessage, gc.temperature(), gc.maxTokens(), extraImages, convo,
                    resolved.baseUrl(), resolved.apiKey(),
                    toolDeclared ? tools : List.of(),
                    toolDeclared ? "auto" : null);
            /*
             * 走重试包装（2026-10-28 修）。
             *
             * <p><b>这里原来直接调 modelRouter.chat</b>，没走 {@link #chatWithRetry} ——
             * 而**带工具的场景全都走这条路径**（工具循环）。所以上次加的重试只对
             * "直答"分支生效，实际一次都没跑过。</p>
             *
             * <p>实测后果：用户发表情包 → 上游 120 秒无响应 → 直接 timeout，
             * 日志里连"重试"两个字都没有。</p>
             */
            ModelAdapter.ChatResponse resp = chatWithRetry(provider, chatReq);
            usageTokens[0] += resp.promptTokens();
            usageTokens[1] += resp.completionTokens();
            logTo(LogLevel.INFO, LogCategory.llm, "llm.done provider=" + provider + " model=" + model
                            + " round=" + round + " latency=" + (System.currentTimeMillis() - t0) + "ms"
                            + " tokens=" + (resp.promptTokens() + resp.completionTokens())
                            + " toolCalls=" + (resp.toolCalls() == null ? 0 : resp.toolCalls().size())
                            // 工具循环是否带得上思考链，只看这一行（0 也要带，见 toOpenAiMessage）
                            + " reasoningLen=" + (resp.reasoning() == null ? "null" : resp.reasoning().length()),
                    traceId, runId, tenantId, agent.getAgentId());

            if (resp.toolCalls() == null || resp.toolCalls().isEmpty()) {
                return resp.content() == null ? "" : resp.content();
            }

            /*
             * ① 把模型这一轮的 assistant(tool_calls) 原样记回序列（见方法注释的第二条警告）。
             *
             * **必须带上 reasoning**（2026-10-21）：thinking 模型要求回灌工具结果时，
             * 上一轮 assistant 消息里的 `reasoning_content` 原样带回，否则第二轮请求直接
             * HTTP 400 "The reasoning_content in the thinking mode must be passed back to the API"。
             * 这个错只在"模型调了工具"时出现 —— 普通问答是单轮请求，不会暴露。
             */
            convo.add(ModelAdapter.ChatMessage.assistantToolCalls(resp.toolCalls(), resp.reasoning()));

            /*
             * 本轮要注入的图片 / 是否触发了"搜图上限"提醒。
             *
             * 必须声明在循环外：tool 消息与 user 消息的**顺序**有协议约束，
             * 详见循环里那段注释。
             */
            List<String> roundPreviews = new ArrayList<>();
            boolean previewCapped = false;

            // ② 逐个执行，并以 role=tool 消息回灌（一次调用一条，id 一一对应）
            for (ModelAdapter.ToolCall call : resp.toolCalls()) {
                long toolT0 = System.nanoTime();
                ToolResult tr;
                try {
                    tr = toolExecutor == null
                            ? ToolResult.fail("tool executor not configured")
                            : toolExecutor.run(call.name(), call.arguments(),
                            ToolContext.of(tenantId, agent.getAgentId(), runId, sessionId));
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
                // ⚠️ 失败也必须回灌：模型等不到这次调用的结果，就会一直重试同一个工具。
                convo.add(ModelAdapter.ChatMessage.tool(call.id(), outputText));

                /*
                 * 图片预览（2026-10-22）：先**收集**，等本轮的 tool 消息全部就位再注入。
                 *
                 * ⚠️ 不能直接在这里 add —— OpenAI 协议要求 `assistant(tool_calls)` 后面
                 * **紧跟着全部对应的 tool 消息**。模型一轮里调了两个工具时，若在两条 tool
                 * 之间插一条 user 消息，上游会直接拒掉：
                 *   "An assistant message with 'tool_calls' must be followed by tool
                 *    messages responding to each 'tool_call_id'."
                 * 所以收集完，在 for 循环外面统一追加。
                 *
                 * 为什么硬编码工具名：这是**唯一**会返回"一批候选图片"的工具，
                 * 输出格式也固定（每张图一行缩进的 URL）。做成通用机制要给
                 * `ToolResult` 加字段（99 个构造点），收益不匹配。
                 */
                if ("image_search".equals(call.name())) {
                    imageSearchCalls++;
                    if (imageSearchCalls <= 3) {
                        roundPreviews.addAll(extractImageUrls(outputText, 3));
                    } else if (imageSearchCalls == 4) {
                        previewCapped = true;
                    }
                } else if ("generate_image".equals(call.name())) {
                    /*
                     * 她自己画出来的图也要回喂给她看（2026-10-22）。
                     *
                     * 不然她只会拿到一串 OSS 链接，原话是"我看不到自己画的头像"——
                     * 没法判断画得怎么样，也没法在下次描述时改进。
                     *
                     * ⚠️ 这里用**宽松提取**而不是 extractImageUrls：
                     * 百炼返回的是 OSS 签名链接（`.../1d/a2/2026xxx?Expires=...`），
                     * **往往没有 .jpg 扩展名**，按扩展名过滤会一张都抓不到。
                     */
                    roundPreviews.addAll(extractFirstHttpUrl(outputText, 1));
                }
            }

            /*
             * ③ 图片与提醒：必须在本轮**全部 tool 消息之后**追加。
             *
             * 放在这里而不是循环里的原因是协议顺序 —— `assistant(tool_calls)` 与它的
             * `tool` 结果必须连续，中间插 user 消息会被上游判为
             * "insufficient tool messages following tool_calls message"。
             */
            if (!roundPreviews.isEmpty()) {
                List<String> previews = dedupeUrls(roundPreviews, 3);
                convo.add(ModelAdapter.ChatMessage.withImages(
                        "user",
                        "这是刚拿到的 " + previews.size() + " 张图，可以直接看画面。"
                                + "如果都不合适，就换个描述或关键词再来一次。",
                        previews));
                logTo(LogLevel.INFO, LogCategory.tool,
                        "image.preview injected=" + previews.size() + " batch=" + imageSearchCalls,
                        traceId, runId, tenantId, agent.getAgentId());
            } else if (previewCapped) {
                convo.add(ModelAdapter.ChatMessage.withImages(
                        "user",
                        "已经搜了 3 批了，别再换关键词了。从上面看过的图里挑一张，"
                                + "或者这轮不换、只写 avatarWish。",
                        List.of()));
                logTo(LogLevel.WARN, LogCategory.tool,
                        "image.preview capped at 3 batches", traceId, runId, tenantId, agent.getAgentId());
            }

            // ④ 后续轮次不再重复发送用户消息
            nextUserMessage = null;
        }
        logTo(LogLevel.WARN, LogCategory.tool, "tool loop reached max rounds " + maxToolRounds
                        + " agent=" + agent.getName(),
                traceId, runId, tenantId, agent.getAgentId());
        return "（工具调用超过最大轮次，已停止；请重试或补充说明）";
    }

    /**
     * 从工具输出文本里抽图片地址（2026-10-22）。
     *
     * <p>只认**带图片扩展名**的 http(s) 地址 —— {@code image_search} 的返回里每张图占一行
     * （`   1. 标题` 换行 `   URL`），直接正则扫最省事。</p>
     *
     * <p>为什么不解析成结构化数据：那样要给 {@code ToolResult} 加字段，
     * 而它有 99 个构造点，改造成本与收益不匹配。</p>
     *
     * @param max 最多取几张。默认调用方传 3：每张图都会进多模态 token 预算，
     *            给太多既贵又容易让模型"选择困难"
     */
    /** 去重 + 截断（一轮里可能调了多次 image_search，图片会重复）。 */
    private static List<String> dedupeUrls(List<String> urls, int max) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String u : urls) {
            if (out.size() >= max) {
                break;
            }
            if (u != null && !u.isBlank() && seen.add(u)) {
                out.add(u);
            }
        }
        return out;
    }

    /**
     * 宽松提取：抓前 {@code max} 个 http(s) 链接，**不看扩展名**。
     *
     * <p>用于 {@code generate_image} 的返回值 —— 百炼/硅基流动给的是 OSS 签名链接，
     * 通常没有 {@code .jpg} 后缀，按扩展名过滤会全部漏掉。</p>
     */
    private static List<String> extractFirstHttpUrl(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || max <= 0) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s\"'<>]+")
                .matcher(text);
        while (m.find() && out.size() < max) {
            out.add(m.group());
        }
        return out;
    }

    static List<String> extractImageUrls(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || max <= 0) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s\"'<>]+\\.(?:jpe?g|png|gif|webp)(?:\\?[^\\s\"'<>]*)?")
                .matcher(text);
        while (m.find() && out.size() < max) {
            out.add(m.group());
        }
        return out;
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
     * 读取用户的长期画像（渲染好的整段提示词附文）。
     *
     * <p>多层级记忆里「长期」这一层：内容由**用户自己填写**，跨会话、跨智能体长期有效。
     * 与中期摘要一样按"读失败就跳过"处理 —— 画像读不出来最多是这轮少一点背景，
     * 不该让用户发不出消息。</p>
     *
     * <p>召回顺序遵循设计约定：短期（会话历史）→ 中期（早期摘要）→ 长期（画像）→ 向量，
     * 所以这里拼在早期摘要之后。</p>
     */
    private String profileOf(String tenantId, String userId) {
        if (userFactService == null || userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return userFactService.render(tenantId, userId);
        } catch (Exception e) {
            log.warn("Failed to load user profile: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按语义召回该用户的历史对话片段（向量记忆）。
     *
     * <p>多层级记忆的第四层，也是唯一"按内容相关性"而非"按时间近远"取的一层。
     * 排除当前会话 —— 它的内容已经通过短期缓存与历史回放进上下文了，
     * 再召回一遍等于同一段话说两遍（详见 {@code ConversationMemoryService} 类注释）。</p>
     */
    private String recallHistory(String tenantId, String userId, String query, String currentSessionId) {
        // 开关关掉时直接跳过，省一次 embedding（2026-10-28）
        if (!memoryRecallEnabled) {
            return null;
        }
        if (conversationMemoryService == null || userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return conversationMemoryService.recall(tenantId, userId, query, currentSessionId);
        } catch (Exception e) {
            log.warn("Failed to recall conversation memory: {}", e.getMessage());
            return null;
        }
    }

    /** 异步索引本轮用户消息进向量记忆（失败静默，绝不阻塞对话）。 */
    private void indexConversationMemory(String tenantId, String userId, String sessionId, String userMessage) {
        if (conversationMemoryService == null || userId == null || userId.isBlank()) {
            return;
        }
        try {
            conversationMemoryService.indexAsync(tenantId, userId, sessionId, userMessage);
        } catch (Exception e) {
            log.debug("Failed to schedule conversation memory indexing: {}", e.getMessage());
        }
    }

    /**
     * 调模型，遇到**瞬时网络故障**重试一次（2026-10-26）。
     *
     * <p><b>为什么需要</b>：实测硅基流动偶发 120 秒不返回，直接把整轮对话废掉 ——
     * 用户看到的是"我发了消息她一个字都没回"，日志里只有一条
     * {@code MODEL_UPSTREAM_ERROR: 模型调用网络异常: timeout}。而同一句话重发一次
     * 基本就正常了，说明是上游抖动，不是内容有问题。</p>
     *
     * <p><b>只重试瞬时故障</b>（超时 / 连接重置 / 502~504）。4xx 一律不重试 ——
     * 参数错、key 错、余额不足，重试只是再失败一次，白白让用户多等两分钟。</p>
     *
     * <p><b>代价</b>：真卡住时要等 120 + 2 + 120 秒。这是有意的取舍 ——
     * 绝大多数情况下第二次就成功，用户只多等 2 秒。</p>
     */
    private ModelAdapter.ChatResponse chatWithRetry(String provider, ModelAdapter.ChatRequest chatReq) {
        int attempt = 0;
        while (true) {
            try {
                return modelRouter.chat(provider, chatReq);
            } catch (RuntimeException e) {
                if (!isTransientFailure(e)) {
                    throw e;
                }
                /*
                 * 带图超时 → 去掉图片重试（2026-10-28）。
                 *
                 * <p><b>为什么单独处理</b>：实测带图请求失败率 30~40%，
                 * 而**所有 timeout 都发生在带图请求上**（纯文字 445 次中位 2.9 秒，
                 * 带图 10 次中位 7 秒、最慢 42 秒）。硅基流动的视觉通道明显不稳。</p>
                 *
                 * <p>但**不能干脆不传图** —— 用户明确要求"她能读懂表情包"，
                 * 而且历史上确实读通过。所以改成兜底：先带图照常试，
                 * 超时了才去掉图重试一次 —— 那时能回话比看清图重要。</p>
                 *
                 * <p>代价：最坏 120（超时）+ 3（无图响应）≈ 2 分钟。
                 * 但比现在"等 120 秒然后一个字都不回"强。</p>
                 */
                if (isTimeout(e) && hasImages(chatReq)) {
                    log.warn("[agent] 带图请求超时（{}），去掉图片重试一次 —— 先保证能回话",
                            briefError(e));
                    return chatWithRetry(provider, withoutImages(chatReq));
                }
                /*
                 * 限流多给一次机会（2026-10-28）。
                 *
                 * 429 "System is too busy" 是上游排队，隔几秒基本就过了；
                 * 而它是**频繁发生**的（比 timeout 常见得多），
                 * 只重试一次的话，赶上高峰照样会丢消息。
                 */
                boolean limited = isRateLimited(e);
                int maxRetry = limited ? 2 : 1;
                if (attempt >= maxRetry) {
                    throw e;
                }
                // 限流退避更久：6s → 12s；其它照旧 2s（用户等不了太久）
                long waitMs = limited ? (long) (6000L * (1L << attempt)) : 2000L;
                log.warn("[agent] 模型调用失败（{}），{} 秒后重试（第 {} 次）",
                        briefError(e), waitMs / 1000, attempt + 1);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                attempt++;
            }
        }
    }

    /** 是不是"重试可能成功"的那类错误。 */
    private boolean isTransientFailure(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("timeout")
                || m.contains("超时")
                || m.contains("网络异常")
                || m.contains("connection reset")
                || m.contains("connect timed out")
                || m.contains("connection refused")
                // 限流（2026-10-28）：硅基流动 429 "System is too busy now" 是瞬时的
                || isRateLimited(e)
                || m.contains(" 502")
                || m.contains(" 503")
                || m.contains(" 504");
    }

    /**
     * 是不是「上游限流」（2026-10-28）。
     *
     * <p>实测硅基流动返回过 {@code HTTP 429 {"code":50609,"message":"Request was rejected
     * due to rate limiting. Details: System is too busy now."}} —— 这是**上游繁忙**，
     * 不是我们的请求有问题，隔几秒重试通常就过了。</p>
     *
     * <p>之前 {@code isTransientFailure} 只认 timeout / 5xx，429 直接抛出去，
     * 用户看到的效果就是「发了消息她一个字都没回」。</p>
     */
    private boolean isRateLimited(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains(" 429")
                || m.contains("rate limit")
                || m.contains("too busy")
                || m.contains("too many requests");
    }

    /** 是不是超时（单独判一下，带图兜底只对超时生效）。 */
    private boolean isTimeout(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("timeout") || m.contains("超时");
    }

    /** 这个请求里有没有图片（extra.images 或历史里带图的消息）。 */
    private boolean hasImages(ModelAdapter.ChatRequest req) {
        Object imgs = req.extra() == null ? null : req.extra().get("images");
        if (imgs instanceof java.util.Collection<?> c && !c.isEmpty()) {
            return true;
        }
        if (req.history() != null) {
            for (ModelAdapter.ChatMessage m : req.history()) {
                if (m.imageUrls() != null && !m.imageUrls().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 复制一份请求，**去掉所有图片**（2026-10-28）。
     *
     * <p>用于「带图超时」的兜底重试。图片在 {@code extra.images}（本轮附件）
     * 和 {@code history[].imageUrls}（工具找回来的图）两个地方，都要剥。</p>
     */
    private ModelAdapter.ChatRequest withoutImages(ModelAdapter.ChatRequest req) {
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        if (req.extra() != null) {
            req.extra().forEach((k, v) -> {
                if (!"images".equals(k)) {
                    extra.put(k, v);
                }
            });
        }
        java.util.List<ModelAdapter.ChatMessage> history = req.history() == null
                ? java.util.List.of()
                : req.history().stream()
                        .map(m -> (m.imageUrls() == null || m.imageUrls().isEmpty())
                                ? m
                                : new ModelAdapter.ChatMessage(
                                        m.role(), m.content(), m.toolCallId(),
                                        m.toolCalls(), m.reasoning(), java.util.List.of()))
                        .toList();
        return new ModelAdapter.ChatRequest(
                req.model(), req.systemPrompt(), req.userMessage(),
                req.temperature(), req.maxTokens(), extra, history,
                req.baseUrl(), req.apiKey(), req.tools(), req.toolChoice());
    }

    /**
     * 读取会话早期摘要（历史过长时压缩续接）。任何失败降级返回 null，不阻断对话。
     */
    private String earlySummaryOf(SessionHandle session) {
        if (sessionSummaryService == null || session == null) {
            return null;
        }
        try {
            return sessionSummaryService.summaryForHandle(session);
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
        /*
         * 不再因为 fileUploadService 缺失就整体返回（2026-11-03）。
         *
         * 内联 base64 那条路根本不碰文件服务，把它挡在这里毫无道理 ——
         * 反而会让"图片直传"这个新能力在无文件服务的环境下失效。
         */
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
                /*
                 * 取图片字节：**优先内联 base64，其次 fileId**（2026-11-03）。
                 *
                 * <h3>为什么要加内联这一路</h3>
                 *
                 * <p><b>旧链路的问题</b>：插件每收到一张图，都要先 POST
                 * {@code /api/v1/files/base64} 换成 fileId，平台再 download 回来。
                 * 结果每一张聊天里的图都落进 {@code file_asset} 表，
                 * 「文件」管理页被 {@code qq-*.png} 堆满，而且**没有任何清理任务**。</p>
                 *
                 * <p>这些图只是"给模型看一眼"的临时载体，不是用户的文件资产 ——
                 * 把它们和用户主动上传的文档混在一个库里，是设计上的错位。</p>
                 *
                 * <p><b>行业做法</b>：多模态消息协议本来就支持直接内联 base64
                 * （OpenAI 的 {@code image_url.url} 可放 data URL，Anthropic 的
                 * {@code source.type=base64}），不需要中间文件库。这样一次请求
                 * 就完事，服务端不留痕迹。</p>
                 *
                 * <p><b>兼容性</b>：老的 {@code fileId} 方式继续保留 ——
                 * 用户手动上传的附件、历史消息里的图片引用仍走这条路。</p>
                 */
                byte[] bytes = null;
                String srcTag = null;

                // ① 内联 base64（含 data URL 前缀的也认）
                String inline = strOf(m.get("data"));
                if (!inline.isBlank()) {
                    try {
                        String b64 = inline;
                        if (b64.startsWith("data:")) {
                            int comma = b64.indexOf(',');
                            if (comma > 0) {
                                b64 = b64.substring(comma + 1);
                            }
                        }
                        bytes = java.util.Base64.getDecoder().decode(b64);
                        srcTag = "inline";
                    } catch (Exception e) {
                        log.warn("内联图片解码失败，回退 fileId：{}", e.getMessage());
                    }
                }

                // ② 回退 fileId
                String fileId = strOf(m.get("fileId"));
                if (bytes == null || bytes.length == 0) {
                    if (fileId.isBlank()) {
                        continue;
                    }
                    if (fileUploadService == null) {
                        log.warn("Image {} 需要文件服务但未配置，跳过", fileId);
                        continue;
                    }
                    FileUploadService.Download dl = fileUploadService.download(tenantId, fileId);
                    if (dl == null || dl.bytes() == null || dl.bytes().length == 0) {
                        log.warn("Image {} not found or empty, skip", fileId);
                        continue;
                    }
                    bytes = dl.bytes();
                    srcTag = fileId;
                }
                /*
                 * 以**文件头**为准，不信插件声明的 mimeType（2026-11-02）。
                 *
                 * <p><b>实测事故</b>：QQ 图片 CDN 的 content-type 经常是
                 * {@code application/octet-stream} 或为空，插件只能兜底成
                 * {@code image/png}，而实际内容是 JPEG（文件名就是 .jpg）。
                 * 上游校验很严，直接拒绝：</p>
                 *
                 * <pre>
                 *   The declared image MIME type (image/png) does not match
                 *   the actual image format (jpeg); the image_url data URL
                 *   must declare the real image format.
                 * </pre>
                 *
                 * <p>后果是**带图的消息全部不回复**，而纯文字正常 ——
                 * 从用户视角看就是"她忽然不理我了"，且日志里只有一行 400。</p>
                 *
                 * <p>文件头是文件自己带的，不会撒谎。插件那边已经改了，
                 * 这里再兜一层：即使插件传错，平台也能纠正。</p>
                 */
                String declared = strOf(m.get("mimeType"));
                String mime = sniffImageMime(bytes);
                if (!declared.isBlank() && !declared.equals(mime)) {
                    log.debug("Image {} mime 不一致：declared={} actual={}，用实际格式",
                            srcTag, declared, mime);
                }
                Map<String, String> item = new java.util.HashMap<>(2);
                item.put("mimeType", mime);
                item.put("base64", java.util.Base64.getEncoder().encodeToString(bytes));
                images.add(item);
            }
        } catch (Exception e) {
            log.warn("Collect image parts failed: {}", e.getMessage());
        }
        return images;
    }

    /**
     * 按文件头判断图片真实格式（2026-11-02）。
     *
     * <p>只认上游接受的四种（png / jpeg / gif / webp），认不出就退回 png ——
     * 与其因为格式声明不符被 400 拒掉，不如给个上游至少能解析的类型。</p>
     *
     * <p><b>为什么不查 content-type / 扩展名</b>：两者都是外部给的，
     * 都可能与实际内容不符（QQ CDN 就是典型例子）。魔数在文件自身，
     * 只有它能作为判据。</p>
     *
     * @param b 原始字节；{@code null} 或过短时返回 {@code image/png}
     */
    private static String sniffImageMime(byte[] b) {
        if (b == null || b.length < 12) {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47) {
            return "image/png";
        }
        // GIF: 47 49 46
        if ((b[0] & 0xFF) == 0x47 && (b[1] & 0xFF) == 0x49 && (b[2] & 0xFF) == 0x46) {
            return "image/gif";
        }
        // WEBP: RIFF....WEBP
        if ((b[0] & 0xFF) == 0x52 && (b[1] & 0xFF) == 0x49
                && (b[8] & 0xFF) == 0x57 && (b[9] & 0xFF) == 0x45
                && (b[10] & 0xFF) == 0x42 && (b[11] & 0xFF) == 0x50) {
            return "image/webp";
        }
        return "image/png";
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
                        // 库里的密钥字段是密文 —— 插件要用明文才能调厂商
                        config = pluginConfigSecrets == null
                                ? b.getConfig()
                                : pluginConfigSecrets.reveal(b.getConfig());
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
     *
     * <h3>2026-09-22：补齐插件钩子与工具调用（此前是「裸模型调用」）</h3>
     * 旧实现只有一句 {@code modelRouter.route(...).stream(chatReq)}，于是<b>流式开关一打开，
     * 工具调用与插件钩子会一起静默失效</b>。这也是长期记在 memory 里的
     * 「钩子只在非流式链路生效」的**真正根因** —— 症状表现在钩子，病灶在这里。
     *
     * <p>现在流式链路与本服务的 {@link #run} 对齐了这几件事：配额计次、会话解析与
     * <b>会话持久化</b>（旧实现连历史都不存，等于流式对话没有记忆）、RAG / Skill / 摘要注入、
     * 插件热挂载、插件钩子、工具调用、日志与领域事件。</p>
     *
     * <p><b>与非流式仅剩两处差异，且都是有原因的</b>：</p>
     * <ul>
     *   <li><b>不跑 {@code after_llm} / {@code before_output}</b> —— 流式内容已逐块推给前端，
     *       后端此时再改写也改不动已经显示出去的文字，硬套只会造成「钩子日志显示成功、
     *       用户看到的仍是原文」这种最难排查的状态。需要输出治理请用 {@code before_llm}
     *       前置改写，或改用非流式（详见 {@link AgentPipeline#beforeStream}）；</li>
     *   <li><b>工具轮不流式</b> —— 工具调用天然是「模型说完 → 宿主执行 → 再问一次」的往返，
     *       中间没有可展示的 token。所以先用非流式跑完工具循环，再把最终答复分块推流，
     *       而不是让用户盯着空白等。</li>
     * </ul>
     */
    public Flux<RunStreamEvent> runStream(AgentRunRequest req) {
        String runId = req.runId() != null ? req.runId() : IdGenerator.generate("run");
        String traceId = IdGenerator.generate("trace");
        String tenantId = req.tenantId();
        // 工具调用记录（可视化）：与非流式同一套收集机制
        if (toolCallCollector != null) {
            toolCallCollector.begin(runId);
        }

        return TraceContext.withContext(traceId, runId, tenantId, () -> {
            // 准备阶段（与非流式共用唯一实现，见 prepare 的注释）
            final PreparedRun p = prepare(req, runId, traceId, tenantId, true);
            final AgentDefinition agent = p.agent();
            final String userMessage = p.userMessage();
            final Map<String, Object> imageExtra = p.imageExtra();
            final SessionHandle session = p.session();
            final GenerationConfig gc = p.gc();
            final ModelBindingService.ResolvedModel resolved = p.resolved();
            final String provider = resolved.provider();
            final String model = resolved.model();
            final String effectivePrompt = p.effectivePrompt();
            final List<ModelAdapter.ChatMessage> sessionHistory = p.sessionHistory();
            final List<ModelAdapter.ToolSpec> toolSpecs = p.toolSpecs();

            // ⑧ 前置钩子 before_llm：短路则直接产出该文本，改写则用改写后的消息去调模型
            AgentPipeline.StreamHookResult pre =
                    agentPipeline.beforeStream(agent.getAgentId(), runId, userMessage);
            if (pre.shortCircuited()) {
                String direct = pre.shortCircuit();
                logTo(LogLevel.INFO, LogCategory.agent, "run.completed agent=" + agent.getName()
                                + " shortCircuit=true streaming=true", traceId, runId, tenantId, agent.getAgentId());
                persistExchange(session, tenantId, runId, userMessage, direct, model);
                // 短路回复同样是"一轮完整回复"：附加产物（TTS 语音等）在此一并产出
                Map<String, Object> shortCircuitExtras = sanitizeExtras(
                        agentPipeline.afterStream(agent.getAgentId(), runId, direct));
                // ⚠️ 结束帧**每个分支都必须发**：以前由 Controller 统一追加一个 run.completed，
                // 现在它要带上工具调用记录、只能由这里给出 —— 漏一个分支，前端就一直等不到结束。
                return Flux.just(RunStreamEvent.delta(direct),
                        RunStreamEvent.completed(drainToolCalls(runId), shortCircuitExtras));
            }
            final String streamMessage = pre.message();

            /*
             * 流式下真正不生效的只剩 before_output —— 它要改写的是正文，而正文已经逐块发出去了，
             * 事后替换只会造成"日志说成功、用户看原文"。after_llm 产出的是附加产物（不改正文），
             * 已改为在 finishStream 里执行、随结束帧下发（2026-09-28），所以不再对它告警。
             */
            agentPipeline.warnStreamingUnsupported(agent.getAgentId(), "before_output");

            // ⑨ 有工具：先非流式跑完工具循环，再把最终答复分块推流
            if (!toolSpecs.isEmpty() && toolExecutor != null) {
                return Flux.defer(() -> {
                    final int[] usageTokens = {0, 0};
                    String answer;
                    try {
                        answer = runToolLoop(provider, model, effectivePrompt, streamMessage, gc, resolved,
                                toolSpecs, sessionHistory, traceId, runId, tenantId, agent, usageTokens,
                                imageExtra, session == null ? null : session.sessionId());
                    } catch (Exception e) {
                        String fallback = agentPipeline.onStreamError(agent.getAgentId(), runId, streamMessage, e);
                        if (fallback == null) {
                            logTo(LogLevel.ERROR, LogCategory.agent,
                                    "run.failed agent=" + agent.getName() + " model=" + model
                                            + " error=" + e.getMessage() + " streaming=true",
                                    traceId, runId, tenantId, agent.getAgentId(), stackTraceOf(e));
                            notifyRunFailed(agent.getName(), model, e);
                            return Flux.error(e);
                        }
                        answer = fallback;
                        logTo(LogLevel.WARN, LogCategory.agent,
                                "run.degraded agent=" + agent.getName() + " streaming=true"
                                        + " error=" + e.getMessage() + "（已由插件 on_error 兜底）",
                                traceId, runId, tenantId, agent.getAgentId());
                    }
                    Map<String, Object> toolLoopExtras = finishStream(agent, tenantId, runId, traceId,
                            model, userMessage, answer, session, usageTokens, true, req.userId());
                    // 工具记录在工具循环**跑完之后**取 —— 此刻才完整
                    return Flux.fromIterable(chunkText(answer))
                            .concatWith(Flux.just(
                                    RunStreamEvent.completed(drainToolCalls(runId), toolLoopExtras)));
                }).subscribeOn(VIRTUAL_SCHEDULER);
            }

            // ⑩ 无工具：真流式；同时累积全文用于会话持久化，并用 on_error 兜底失败
            final StringBuilder collected = new StringBuilder();
            /*
             * ★ 流式用量（2026-09-29 新增）。
             *
             * 此前这个数组是写在下面 Flux.defer 里的 {0,0} 字面量 —— 也就是**流式链路的
             * token 用量从来没被采集过**，只有"带工具的那条"（走非流式 call）才准。
             * 而流式是默认用法，于是用量统计长期只在关掉流式时才是对的。
             *
             * 现在从流的 **done 帧**（`ChatDelta.aggregate`，见 SpringAiModelAdapter.stream）取值。
             * ⚠️ 必须放在 defer 外面、并用 doOnNext 在 filter **之前**捕获：
             * done 帧的 text 是空串，会被下面的 filter 滤掉 —— 一旦滤掉，它携带的用量也就丢了。
             */
            final int[] streamUsage = {0, 0};
            return modelRouter.route(provider, ModelCapability.TEXT)
                    .stream(new ModelAdapter.ChatRequest(
                            model, effectivePrompt, streamMessage, gc.temperature(), gc.maxTokens(),
                            imageExtra, sessionHistory, resolved.baseUrl(), resolved.apiKey()))
                    .doOnNext(d -> {
                        // done 帧带 usage；普通数据帧 aggregate 为 null，跳过即可
                        if (d.finished() && d.aggregate() != null) {
                            streamUsage[0] = d.aggregate().promptTokens();
                            streamUsage[1] = d.aggregate().completionTokens();
                        }
                    })
                    /*
                     * 思考帧（2026-10-19）：`ChatDelta.reasoning` 非空时 text 是空串 ——
                     * 旧的 filter 会把它整帧滤掉，用户的思维链就永远看不到。
                     * 这里放行，并在 map 里分流成 `reasoning` 事件。
                     *
                     * ⚠️ `collected` **只累积正文**：思考不写进会话记录，
                     * 否则下次摘要/向量检索会把它的自我独白当成回答内容。
                     */
                    .filter(d -> !d.finished()
                            && ((d.reasoning() != null && !d.reasoning().isEmpty())
                                || (d.text() != null && !d.text().isEmpty())))
                    /*
                     * 一帧可能同时带思考与正文（各适配器的行为不一致）—— 这里拆成两个事件。
                     * 早先写成 map + 三元判断，后果是"有思考的那一帧把正文吞掉"：
                     * 用户看到回答中间缺一段，而且完全看不出原因。
                     */
                    .flatMap(d -> {
                        java.util.List<RunStreamEvent> out = new java.util.ArrayList<>(2);
                        if (d.reasoning() != null && !d.reasoning().isEmpty()) {
                            out.add(RunStreamEvent.reasoning(d.reasoning()));
                        }
                        if (d.text() != null && !d.text().isEmpty()) {
                            collected.append(d.text());
                            out.add(RunStreamEvent.delta(d.text()));
                        }
                        return out.isEmpty() ? Flux.<RunStreamEvent>empty() : Flux.fromIterable(out);
                    })
                    .concatWith(Flux.defer(() -> {
                        // 流正常结束后收尾：存会话 + 记日志 + 派发事件 + 附加产物（TTS 语音等）
                        Map<String, Object> extras = finishStream(agent, tenantId, runId, traceId, model,
                                userMessage, collected.toString(), session, streamUsage, false, req.userId());
                        return Flux.just(RunStreamEvent.completed(drainToolCalls(runId), extras));
                    }))
                    .onErrorResume(err -> {
                        // Flux 的 onErrorResume 回调给的是 Throwable，而管线与日志都按 Exception 建模
                        // （非 Exception 的 Throwable，如 Error，包一层以保证兜底链路统一）。
                        Exception e = err instanceof Exception ex ? ex : new RuntimeException(err);
                        // 失败：先问 on_error 要兜底话术，拿到就把兜底当成一次流式输出推出去
                        String fallback = agentPipeline.onStreamError(agent.getAgentId(), runId, streamMessage, e);
                        if (fallback == null) {
                            logTo(LogLevel.ERROR, LogCategory.agent,
                                    "run.failed agent=" + agent.getName() + " model=" + model
                                            + " error=" + e.getMessage() + " streaming=true",
                                    traceId, runId, tenantId, agent.getAgentId(), stackTraceOf(e));
                            notifyRunFailed(agent.getName(), model, e);
                            return Flux.error(e);
                        }
                        logTo(LogLevel.WARN, LogCategory.agent,
                                "run.degraded agent=" + agent.getName() + " streaming=true"
                                        + " error=" + e.getMessage() + "（已由插件 on_error 兜底）",
                                traceId, runId, tenantId, agent.getAgentId());
                        persistExchange(session, tenantId, runId, userMessage, fallback, model);
                        // 兜底回复同样是完整正文：语音等附加产物照常产出
                        Map<String, Object> fallbackExtras = sanitizeExtras(
                                agentPipeline.afterStream(agent.getAgentId(), runId, fallback));
                        return Flux.fromIterable(chunkText(fallback))
                                .concatWith(Flux.just(
                                        RunStreamEvent.completed(drainToolCalls(runId), fallbackExtras)));
                    });
        });
    }

    /**
     * 流式收尾：持久化会话 + 记完成日志 + 派发领域事件 + <b>收集 after_llm 附加产物</b>。
     *
     * <p>抽出来是因为「工具轮」与「真流式」两条分支的收尾完全一致 ——
     * 分开写迟早会漏掉其中一条（旧实现的流式链路就既没存会话也没发事件）。</p>
     *
     * <h3>★ 为什么这里要跑 after_llm（2026-09-28 改）</h3>
     * 此前流式链路对 {@code after_llm} 只打一条"不生效"的告警。但那对<b>附加产物</b>
     * （TTS 的 {@code audio_url}）是不必要的限制：它不改动任何已推送的正文，
     * 与随结束帧下发的 {@code toolCalls} 完全同构。禁用的后果是
     * <b>TTS 在默认（流式）用法下彻底不可用，且用户从界面上看不出任何原因</b>。
     *
     * <p>{@code before_output} 仍然不跑 —— 它要改的是正文，而正文已经发出去了，
     * 那种"日志说成功、用户看原文"的失败模式才是真正该避免的（见 {@link AgentPipeline}）。</p>
     *
     * @return 供结束帧下发的附加产物；无产物时为空 Map
     */
    private Map<String, Object> finishStream(AgentDefinition agent, String tenantId, String runId,
                                             String traceId, String model, String userMessage,
                                             String answer, SessionHandle session, int[] usageTokens,
                                             boolean toolLoop, String userId) {
        persistExchange(session, tenantId, runId, userMessage, answer, model);
        // 向量记忆：把本轮用户消息异步索引进索引，供**其它会话**将来按语义召回。
        // 放在落库之后：索引要靠回查刚写入的那条消息拿 messageId。
        if (session != null) {
            indexConversationMemory(tenantId, userId, session.sessionId(), userMessage);
        }
        // 画像自动抽取：与非流式走同一个入口（fire-and-forget，见 UserFactExtractor 的类注释）
        if (userFactExtractor != null) {
            userFactExtractor.extractAsync(tenantId, userId,
                    session == null ? null : session.sessionId(), userMessage, answer);
        }
        logTo(LogLevel.INFO, LogCategory.agent, "run.completed agent=" + agent.getName()
                        + " model=" + model + " streaming=true toolLoop=" + toolLoop
                        + " shortCircuit=false",
                traceId, runId, tenantId, agent.getAgentId());
        publishAgentEvent(EventTypes.AGENT_RUN_COMPLETED, tenantId, agent.getAgentId(), runId,
                DomainEvent.payload(
                        "agent_id", agent.getAgentId(),
                        "agent_name", agent.getName(),
                        "model", model,
                        "streaming", Boolean.TRUE,
                        "short_circuited", Boolean.FALSE,
                        "prompt_tokens", usageTokens == null ? 0 : usageTokens[0],
                        "completion_tokens", usageTokens == null ? 0 : usageTokens[1]));

        // after_llm 附加产物（TTS 语音等）。与工具记录同属"事后补发"，共用结束帧。
        Map<String, Object> extras = agentPipeline.afterStream(agent.getAgentId(), runId, answer);
        Map<String, Object> safe = sanitizeExtras(extras);

        /*
         * 本轮用量（2026-10-19）：随结束帧下发。
         *
         * 此前 `AgentRunResponse.Usage` 只在**非流式**返回里有，流式链路把 streamUsage 拿去
         * 写日志与事件后就丢了 —— 于是"这轮花了多少 tokens"在默认（流式）用法下前端拿不到，
         * 插件更拿不到。这里放进 extras（与 audio_url 同一通道），一次改动同时喂给：
         * 前端「本轮消耗」提示、以及需要用量做展示的插件。
         *
         * ⚠️ 拿不到时**不下发**（有些兼容网关流式不返回 usage）——
         * 下发 `0` 会让消费方以为"这轮没消耗"，而那与"不知道"是两件事。
         */
        Map<String, Object> withUsage = new LinkedHashMap<>(safe);
        int promptTokens = usageTokens == null ? 0 : usageTokens[0];
        int completionTokens = usageTokens == null ? 0 : usageTokens[1];
        if (promptTokens > 0 || completionTokens > 0) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("promptTokens", promptTokens);
            usage.put("completionTokens", completionTokens);
            usage.put("totalTokens", promptTokens + completionTokens);
            withUsage.put("usage", usage);
        }

        if (!withUsage.isEmpty()) {
            logTo(LogLevel.INFO, LogCategory.agent, "run.extras agent=" + agent.getName()
                            + " keys=" + withUsage.keySet() + " streaming=true",
                    traceId, runId, tenantId, agent.getAgentId());
        }
        return withUsage;
    }

    /** extras 单值长度上限（字符）。语音 data URI 会很长，但它必须是完整的 —— 所以只挡明显的异常值。 */
    private static final int EXTRA_VALUE_LIMIT = 8_000_000;

    /**
     * 把插件给的 extras 净化成**一定可 JSON 序列化**的简单结构。
     *
     * <h3>★ 为什么必须净化（不是"防御性编程"那么轻）</h3>
     * 这些值来自第三方插件，类型完全不受控（可能塞进 Throwable、InputStream、自定义 Bean）。
     * 而它们会被 Jackson 直接写进 SSE 帧 —— <b>一旦某个值序列化不了，抛异常的是整个响应流</b>：
     * 用户看到的不是"少了段语音"，而是<b>整轮对话中断</b>。
     *
     * <p>所以规则是保守的：只放行 {@code String/Number/Boolean/null} 以及它们的
     * Map/List 组合；其余一律 {@code String.valueOf} 降级（保住信息、绝不抛异常）；
     * 超长值直接丢弃并留日志。</p>
     *
     * @return 可安全序列化的 Map（永不为 null）
     */
    private Map<String, Object> sanitizeExtras(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = e.getValue();
            if (value instanceof CharSequence cs) {
                String s = cs.toString();
                if (s.length() > EXTRA_VALUE_LIMIT) {
                    log.warn("[extras] 丢弃超长值 key={} length={}（上限 {}）", key, s.length(), EXTRA_VALUE_LIMIT);
                    continue;
                }
                out.put(key, s);
            } else if (value instanceof Number || value instanceof Boolean || value == null) {
                out.put(key, value);
            } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
                // 嵌套结构转成 JSON 文本：宁可前端拿到字符串，也不要冒序列化失败的风险
                out.put(key, com.agentplatform.common.util.JsonUtils.toJson(value));
            } else {
                // Throwable、Stream、任意 Bean… 一律降级为字符串（保住"有个东西"这个信息）
                out.put(key, String.valueOf(value));
            }
        }
        return out;
    }

    /** 保存一轮对话（失败不阻断，流式与非流式共用）。 */
    private void persistExchange(SessionHandle session, String tenantId, String runId,
                                 String userMessage, String reply, String model) {
        if (session == null || sessionService == null) {
            return;
        }
        try {
            sessionService.recordExchange(tenantId, session.sessionId(), runId, userMessage, reply, model);
        } catch (Exception e) {
            log.warn("Failed to persist streaming session exchange: {}", e.getMessage());
        }
    }

    /**
     * 把完整文本切成小块，模拟流式推送。
     *
     * <p>用于「工具轮」分支：那一段必须等工具往返完成才有最终文本，没法真流式；
     * 直接一次性推出整段，用户会看到长时间空白后文字突然整块出现。切成小块至少
     * 让渲染是渐进的（且前端无需区分两种来源）。</p>
     */
    private static List<RunStreamEvent> chunkText(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int step = 24;
        List<RunStreamEvent> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += step) {
            out.add(RunStreamEvent.delta(text.substring(i, Math.min(text.length(), i + step))));
        }
        return out;
    }

    /**
     * 取走本次运行的工具调用记录（工具调用可视化）。
     *
     * <p>内部判空让调用方无需到处检查收集器是否装配 —— 它缺失时返回空列表，
     * 而空列表在 {@code RunStreamEvent.completed} 里会收敛成 null（不出现在 JSON 里）。</p>
     */
    private List<com.agentplatform.core.tool.executor.ToolCallRecord> drainToolCalls(String runId) {
        return toolCallCollector == null ? List.of() : toolCallCollector.drain(runId);
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
     * 追加「输出格式」说明：告诉模型界面上支持哪些写法。
     *
     * <h3>★ 为什么必须由平台主动说</h3>
     * 前端现在会渲染 Markdown（标题/列表/表格/粗体/代码块）与 {@code ```chart} 图表围栏。
     * 但<b>模型不会自己知道这件事</b> —— 它的先验是"我输出的是纯文本"，
     * 于是会把数据排成一行行文字（"甲：120，乙：86"），而不会画成图。
     * <b>能力做完了却不告诉模型，等于没做</b> —— 这类"实现了但从未被触发"的坑，
     * 在本项目已经出现过（流式下的钩子），所以这里显式注入。
     *
     * <p>反过来也要克制：只说清格式与<b>使用边界</b>。
     * 若不写"只在确有数据时使用"，模型会倾向于每段都附一张图，反而更难读。</p>
     *
     * <p>说明内容必须与前端实现严格对应（前端只支持 bar/line/pie 三种，
     * 见 {@code pages/chat/markdown/ChartBlock.tsx}）—— 多写一种，模型就会输出渲染不出来的东西。</p>
     */
    public String withRenderGuide(String systemPrompt) {
        /*
         * 内容改成**可配置**（2026-10-20）：从平台设置读，键 `agent-platform.prompt.render-guide`，
         * 默认值就是原先硬编码的这段。理由：它直接决定模型输出的风格，用户有权查看与修改 ——
         * 藏在代码里等于"平台偷偷往你的提示词里加东西"。
         *
         * 留空则不注入（某些部署可能根本不想要 Markdown 指引）。
         */
        String guide = environment == null
                ? com.agentplatform.core.settings.PlatformSettingRegistry.DEFAULT_RENDER_GUIDE
                : environment.getProperty(
                        com.agentplatform.core.settings.PlatformSettingRegistry.PROMPT_RENDER_GUIDE,
                        com.agentplatform.core.settings.PlatformSettingRegistry.DEFAULT_RENDER_GUIDE);
        String base = systemPrompt == null ? "" : systemPrompt;
        if (guide == null || guide.isBlank()) {
            return base;
        }
        return base + "\n" + guide;
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
            } else if ("image".equals(type)) {
                /*
                 * 图片（2026-10-30 修）。
                 *
                 * <p>原来掉进下面的 else，渲染成「[image 类型暂不支持直接读取]」——
                 * 她看到这句话就以为**根本没收到图**，于是说「图上真没有」。</p>
                 *
                 * <p>实情是：发图当轮图片通过视觉通道（imageExtra → base64）传过去了，
                 * 她当时能看到；只是**历史里不带字节**，回放时只剩这一行。
                 * 所以文案必须写成"有过一张图"，而不是"不支持"。</p>
                 */
                int n = 1;
                Object cnt = m.get("count");
                if (cnt instanceof Number num && num.intValue() > 1) {
                    n = num.intValue();
                }
                text.append("\n[你当时收到过 ").append(n).append(" 张图片]");
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
