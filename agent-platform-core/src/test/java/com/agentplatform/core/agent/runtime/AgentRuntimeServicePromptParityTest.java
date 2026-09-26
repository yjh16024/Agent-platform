package com.agentplatform.core.agent.runtime;

import com.agentplatform.core.agent.dto.AgentRunRequest;
import com.agentplatform.core.agent.service.AgentService;
import com.agentplatform.core.memory.ConversationMemoryService;
import com.agentplatform.core.memory.UserFactService;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.plugin.runtime.AgentPipeline;
import com.agentplatform.core.plugin.runtime.ExtensionRegistry;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.model.entity.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「两个入口的提示词必须一致」—— 等价性测试（2026-09-25 加）。
 *
 * <h3>为什么需要这个测试</h3>
 * {@code run()}（非流式）与 {@code runStream()}（流式）**各自复制了一套完全相同的
 * 上下文准备逻辑**（配额 → 会话解析 → 人格/Skill 组装 → RAG → 中期摘要 → 长期画像 →
 * 向量召回 → 插件挂载 → 历史回放 → 工具声明），约 70 行逐字重复。
 *
 * <p>那段逻辑的顺序是**设计契约**（短期 → 中期 → 长期 → 向量，见
 * {@code ConversationMemoryService} 类注释），但两处复制意味着：<b>只改一边就会造成
 * 「流式与非流式行为不一致」</b>。这个测试就是那道网 —— 任何一边的注入被增删或调序，
 * 它都会红。</p>
 *
 * <h3>两条断言缺一不可</h3>
 * <ol>
 *   <li>{@code assertEquals}：两个入口的 {@code systemPrompt} 逐字相同；</li>
 *   <li>{@code assertTrue(contains(...))}：**注入确实发生过** ——
 *       否则如果两个入口都漏了注入，第一条也会通过（假阴性）。</li>
 * </ol>
 *
 * <h3>为什么不用 StepVerifier</h3>
 * 本测试只需「跑完 + 抓到请求」，{@code blockLast()} 已足够。
 * {@code StepVerifier} 服务于时序/背压断言，引入 reactortest 依赖不值当。
 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeServicePromptParityTest {

    /** 长期画像的哨兵串（证明第四层之前的那层被注入）。 */
    private static final String PROFILE_MARK = "[[LONG_TERM_PROFILE]]";
    /** 向量召回的哨兵串（证明最后一层被注入，且位于画像之后）。 */
    private static final String VECTOR_MARK = "[[VECTOR_RECALL]]";

    @Mock
    private AgentService agentService;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private ModelAdapter modelAdapter;

    private AgentRuntimeService service;

    @BeforeEach
    void setUp() {
        ExtensionRegistry registry = new ExtensionRegistry(new ToolRegistry());
        service = new AgentRuntimeService(agentService, modelRouter,
                new AgentPipeline(registry), new PluginRuntime(registry, List.of()), null);

        // 长期记忆与向量记忆是 @Autowired(required=false) 的私有字段 ——
        // 用反射注入（与 WorkspaceServiceTest 同一手法），以便让「注入顺序」真的被走到。
        UserFactService facts = mock(UserFactService.class);
        lenient().when(facts.render(any(), any())).thenReturn(PROFILE_MARK);

        ConversationMemoryService vector = mock(ConversationMemoryService.class);
        lenient().when(vector.recall(any(), any(), any(), any())).thenReturn(VECTOR_MARK);

        ReflectionTestUtils.setField(service, "userFactService", facts);
        ReflectionTestUtils.setField(service, "conversationMemoryService", vector);
    }

    private AgentDefinition agentDef() {
        AgentDefinition a = mock(AgentDefinition.class);
        lenient().when(a.getAgentId()).thenReturn("agent_abcd");
        lenient().when(a.getName()).thenReturn("测试智能体");
        lenient().when(a.getSystemPrompt()).thenReturn("你是客服助手");
        lenient().when(a.getPersona()).thenReturn(null);
        // capabilities 为 null ⇒ ensurePluginsAttached 直接返回，无需 PluginRuntime 真实装配
        lenient().when(a.getCapabilities()).thenReturn(null);
        return a;
    }

    private AgentRunRequest req() {
        return new AgentRunRequest(
                null, "agent", "agent_abcd", null, null, null,
                null,
                List.of(new AgentRunRequest.Message("user", "帮我退个货")),
                "sess_1",
                null, null, null, Boolean.FALSE,
                Map.of("tenant_id", "t1", "user_id", "u1"));
    }

    @Test
    @DisplayName("run 与 runStream 组装的 systemPrompt 必须逐字相同，且记忆注入顺序为 长期→向量")
    void promptsAreIdenticalAcrossBothEntryPoints() {
        // 先构造（其内部含 stubbing），再放进 when(...) —— 否则触发 Mockito UnfinishedStubbing
        AgentDefinition agent = agentDef();
        when(agentService.getOrThrow("t1", "agent_abcd")).thenReturn(agent);
        when(modelRouter.chat(any(), any())).thenReturn(
                new ModelAdapter.ChatResponse("同步回答", 1, 1, 0.0, 1L));
        when(modelRouter.route(any(), any())).thenReturn(modelAdapter);
        // 两个增量：一个正文（finished=false，会进 delta 帧）、一个终止标记（finished=true，被 filter 掉）
        when(modelAdapter.stream(any())).thenReturn(Flux.just(
                new ModelAdapter.ChatDelta("流式回答", false, null),
                new ModelAdapter.ChatDelta("", true, null)));

        ArgumentCaptor<ModelAdapter.ChatRequest> syncCap =
                ArgumentCaptor.forClass(ModelAdapter.ChatRequest.class);
        ArgumentCaptor<ModelAdapter.ChatRequest> streamCap =
                ArgumentCaptor.forClass(ModelAdapter.ChatRequest.class);

        // ---- 非流式入口 ----
        service.run(req());
        verify(modelRouter).chat(any(), syncCap.capture());

        // ---- 流式入口（无工具链路 ⇒ 走真流式分支）----
        service.runStream(req()).blockLast();
        verify(modelAdapter).stream(streamCap.capture());

        String syncPrompt = syncCap.getValue().systemPrompt();
        String streamPrompt = streamCap.getValue().systemPrompt();

        // ① 一致性：两个入口必须给出同一份提示词
        assertEquals(syncPrompt, streamPrompt,
                "run 与 runStream 的 systemPrompt 必须逐字一致 —— 不一致说明两处准备逻辑被改偏了");

        // ② 真值：注入确实发生过（否则上面那条在"两边都漏"时也会通过）
        assertTrue(syncPrompt.contains("你是客服助手"), "基础人格提示词必须在内");
        assertTrue(syncPrompt.contains(PROFILE_MARK), "长期画像必须被注入");
        assertTrue(syncPrompt.contains(VECTOR_MARK), "向量召回必须被注入");

        // ③ 顺序：设计约定为 短期 → 中期 → 长期 → 向量，故画像必须排在向量召回之前
        assertTrue(syncPrompt.indexOf(PROFILE_MARK) < syncPrompt.indexOf(VECTOR_MARK),
                "长期画像必须位于向量召回之前（四层记忆的召回顺序是设计契约）");
    }
}
