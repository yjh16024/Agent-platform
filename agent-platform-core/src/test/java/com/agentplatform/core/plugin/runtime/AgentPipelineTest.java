package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.HookPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管线钩子点补全（2026-09-22）的单元测试。
 *
 * <p>覆盖 4 个可用钩子点各自的返回值语义、异常兜底的"有人兜 / 没人兜"两条路径、
 * 以及按智能体隔离。用测试内部替身插件，不依赖 plugin-example 模块（它对 core 不可见）。</p>
 */
class AgentPipelineTest {

    /** 替身钩子：由 lambda 决定返回值，便于逐点构造场景。 */
    static class StubHook implements AgentHook {

        private final String id;
        private final HookPoint point;
        private final Function<HookContext, Object> behavior;

        StubHook(String id, HookPoint point, Function<HookContext, Object> behavior) {
            this.id = id;
            this.point = point;
            this.behavior = behavior;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public void onAttach(PluginContext ctx) {
        }

        @Override
        public void onDetach(PluginContext ctx) {
        }

        @Override
        public HookPoint point() {
            return point;
        }

        @Override
        public Object invoke(HookContext ctx) {
            return behavior.apply(ctx);
        }
    }

    private ExtensionRegistry registry;
    private AgentPipeline pipeline;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry(new ToolRegistry());
        pipeline = new AgentPipeline(registry);
    }

    /** 在某智能体上注册一个钩子。 */
    private void hook(String agentId, String pluginId, HookPoint point, Function<HookContext, Object> behavior) {
        registry.registerHook(new ExtensionRegistry.Owner(agentId, pluginId),
                point, new StubHook(pluginId, point, behavior));
    }

    // ---------------- before_llm ----------------

    @Test
    @DisplayName("before_llm 返回 String → 短路，LLM 完全不被调用")
    void beforeLlmShortCircuits() {
        hook("a1", "p_short", HookPoint.before_llm, ctx -> "预设答复");
        boolean[] llmCalled = {false};

        PipelineResult r = pipeline.run("a1", "r1", "你好", msg -> {
            llmCalled[0] = true;
            return "LLM 回复";
        });

        assertEquals("预设答复", r.reply());
        assertTrue(r.shortCircuited());
        assertFalse(llmCalled[0], "短路时不应调用 LLM");
    }

    @Test
    @DisplayName("before_llm 返回 Map{input} → 改写输入，LLM 收到的是改写后的文本")
    void beforeLlmRewritesInput() {
        hook("a1", "p_rewrite", HookPoint.before_llm,
                ctx -> Map.of("input", ctx.input() + "（已补全上下文）"));
        String[] seen = {null};

        PipelineResult r = pipeline.run("a1", "r1", "原问题", msg -> {
            seen[0] = msg;
            return "收到：" + msg;
        });

        assertEquals("原问题（已补全上下文）", seen[0], "LLM 应收到改写后的输入");
        assertFalse(r.shortCircuited());
    }

    @Test
    @DisplayName("before_llm 返回 null → 透传，输入原样进入 LLM")
    void beforeLlmNullPassesThrough() {
        hook("a1", "p_noop", HookPoint.before_llm, ctx -> null);
        String[] seen = {null};

        pipeline.run("a1", "r1", "原问题", msg -> {
            seen[0] = msg;
            return "ok";
        });

        assertEquals("原问题", seen[0]);
    }

    // ---------------- after_llm ----------------

    @Test
    @DisplayName("after_llm 返回 Map → 进 extras，且不影响回复文本（这是它与 before_output 的分界）")
    void afterLlmAddsExtrasWithoutChangingReply() {
        hook("a1", "p_enrich", HookPoint.after_llm,
                ctx -> Map.of("audio_url", "http://x/a.mp3", "chars", 3));

        PipelineResult r = pipeline.run("a1", "r1", "hi", msg -> "LLM 原文");

        assertEquals("LLM 原文", r.reply(), "after_llm 不该改正文");
        assertEquals("http://x/a.mp3", r.extras().get("audio_url"));
        assertEquals(3, r.extras().get("chars"));
    }

    // ---------------- before_output ----------------

    @Test
    @DisplayName("before_output 返回 String → 替换最终输出（脱敏场景）")
    void beforeOutputReplacesReply() {
        hook("a1", "p_mask", HookPoint.before_output,
                ctx -> ctx.input().toString().replace("13800001111", "138****1111"));

        PipelineResult r = pipeline.run("a1", "r1", "hi", msg -> "请联系 13800001111");

        assertEquals("请联系 138****1111", r.reply());
    }

    @Test
    @DisplayName("before_output 返回 Map{output} → 同样替换（兼容写法）")
    void beforeOutputAcceptsMapForm() {
        hook("a1", "p_mask2", HookPoint.before_output, ctx -> Map.of("output", "改写后"));

        PipelineResult r = pipeline.run("a1", "r1", "hi", msg -> "原始");

        assertEquals("改写后", r.reply());
    }

    @Test
    @DisplayName("before_output 返回 null → 保留 LLM 原输出")
    void beforeOutputNullKeepsReply() {
        hook("a1", "p_keep", HookPoint.before_output, ctx -> null);

        PipelineResult r = pipeline.run("a1", "r1", "hi", msg -> "原始");

        assertEquals("原始", r.reply());
    }

    @Test
    @DisplayName("before_output 在 after_llm 之后执行 → 附加产物基于原文、替换作用于最后输出")
    void beforeOutputRunsAfterAfterLlm() {
        hook("a1", "p_after", HookPoint.after_llm, ctx -> Map.of("audio_url", "u"));
        hook("a1", "p_out", HookPoint.before_output, ctx -> "[" + ctx.input() + "]");

        PipelineResult r = pipeline.run("a1", "r1", "hi", msg -> "正文");

        assertEquals("[正文]", r.reply());
        assertEquals("u", r.extras().get("audio_url"));
    }

    // ---------------- on_error ----------------

    @Test
    @DisplayName("on_error 返回 String → 兜底成功，异常不上抛，且 extras 标记 error_handled")
    void onErrorProvidesFallback() {
        hook("a1", "p_fb", HookPoint.on_error, ctx -> "服务繁忙，请稍后再试");

        PipelineResult r = pipeline.run("a1", "r1", "hi", msg -> {
            throw new IllegalStateException("model timeout");
        });

        assertEquals("服务繁忙，请稍后再试", r.reply());
        assertEquals(Boolean.TRUE, r.extras().get("error_handled"));
        assertEquals("model timeout", r.extras().get("error_message"));
    }

    @Test
    @DisplayName("on_error 能从 metadata 拿到原始异常与本次输入")
    void onErrorSeesExceptionAndInput() {
        Object[] seen = {null, null};
        hook("a1", "p_fb2", HookPoint.on_error, ctx -> {
            seen[0] = ctx.metadata().get("exception");
            seen[1] = ctx.metadata().get("original_input");
            return "兜底";
        });

        pipeline.run("a1", "r1", "原始输入", msg -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(seen[0] instanceof IllegalStateException, "metadata 里应带原始异常");
        assertEquals("原始输入", seen[1]);
    }

    @Test
    @DisplayName("没有 on_error 钩子时 → 异常照常上抛（保持\"没插件就没变化\"）")
    void withoutOnErrorExceptionPropagates() {
        IllegalStateException ex = new IllegalStateException("boom");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> pipeline.run("a1", "r1", "hi", msg -> {
                    throw ex;
                }));

        assertSame(ex, thrown);
    }

    @Test
    @DisplayName("on_error 返回 null → 视为不兜底，异常继续上抛（多插件分工场景）")
    void onErrorReturningNullStillPropagates() {
        hook("a1", "p_ignore", HookPoint.on_error, ctx -> null);

        assertThrows(IllegalStateException.class,
                () -> pipeline.run("a1", "r1", "hi", msg -> {
                    throw new IllegalStateException("boom");
                }));
    }

    // ---------------- 流式链路（2026-09-22 新增） ----------------

    @Test
    @DisplayName("beforeStream：before_llm 返回 String → 短路，调用方据此直接产出该文本")
    void beforeStreamShortCircuits() {
        hook("a1", "p_short", HookPoint.before_llm, ctx -> "预置答复");

        AgentPipeline.StreamHookResult r = pipeline.beforeStream("a1", "r1", "你好");

        assertTrue(r.shortCircuited());
        assertEquals("预置答复", r.shortCircuit());
    }

    @Test
    @DisplayName("beforeStream：返回 Map{input} → 改写流式输入，不短路")
    void beforeStreamRewritesInput() {
        hook("a1", "p_rw", HookPoint.before_llm, ctx -> Map.of("input", ctx.input() + "（改写）"));

        AgentPipeline.StreamHookResult r = pipeline.beforeStream("a1", "r1", "原问题");

        assertFalse(r.shortCircuited());
        assertEquals("原问题（改写）", r.message());
    }

    @Test
    @DisplayName("beforeStream：无钩子 → 原样透传")
    void beforeStreamPassesThroughWithoutHooks() {
        AgentPipeline.StreamHookResult r = pipeline.beforeStream("a1", "r1", "原样");

        assertFalse(r.shortCircuited());
        assertEquals("原样", r.message());
    }

    @Test
    @DisplayName("beforeStream：按智能体隔离（挂 a1 的不影响 a2）")
    void beforeStreamIsolatedPerAgent() {
        hook("a1", "p_short", HookPoint.before_llm, ctx -> "a1 的答复");

        assertTrue(pipeline.beforeStream("a1", "r1", "hi").shortCircuited());
        assertFalse(pipeline.beforeStream("a2", "r2", "hi").shortCircuited());
    }

    @Test
    @DisplayName("beforeStream：不触发 after_llm / before_output（流式下它们改不动已推送的内容）")
    void beforeStreamIgnoresPostHooks() {
        boolean[] touched = {false};
        hook("a1", "p_after", HookPoint.after_llm, ctx -> {
            touched[0] = true;
            return Map.of("k", "v");
        });
        hook("a1", "p_out", HookPoint.before_output, ctx -> {
            touched[0] = true;
            return "改写";
        });

        AgentPipeline.StreamHookResult r = pipeline.beforeStream("a1", "r1", "hi");

        assertEquals("hi", r.message());
        assertFalse(r.shortCircuited());
        assertFalse(touched[0], "流式前置阶段不该触发 after_llm / before_output");
    }

    @Test
    @DisplayName("onStreamError：有 on_error 钩子 → 拿到兜底话术")
    void onStreamErrorReturnsFallback() {
        hook("a1", "p_fb", HookPoint.on_error, ctx -> "流式兜底");

        assertEquals("流式兜底",
                pipeline.onStreamError("a1", "r1", "hi", new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("onStreamError：没有兜底钩子 → 返回 null（调用方据此把错误透传出去）")
    void onStreamErrorReturnsNullWithoutHook() {
        assertNull(pipeline.onStreamError("a1", "r1", "hi", new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("warnStreamingUnsupported：对未注册的钩子点不打日志（不抛异常即可）")
    void warnStreamingUnsupportedIsSafeWithoutHooks() {
        pipeline.warnStreamingUnsupported("a1", "before_output");
        pipeline.warnStreamingUnsupported("a1", "after_llm");
    }

    // ---------------- 隔离与健壮性 ----------------

    @Test
    @DisplayName("钩子按智能体隔离：挂到 a1 的 before_output 不影响 a2")
    void hooksIsolatedPerAgent() {
        hook("a1", "p_mask", HookPoint.before_output, ctx -> "被改写了");

        assertEquals("被改写了", pipeline.run("a1", "r1", "hi", m -> "原始").reply());
        assertEquals("原始", pipeline.run("a2", "r2", "hi", m -> "原始").reply());
    }

    @Test
    @DisplayName("单个钩子抛异常 → 降级跳过，不影响主流程与其它钩子")
    void brokenHookDoesNotBreakPipeline() {
        hook("a1", "p_broken", HookPoint.after_llm, ctx -> {
            throw new RuntimeException("插件自身 bug");
        });
        hook("a1", "p_ok", HookPoint.after_llm, ctx -> Map.of("survived", true));

        PipelineResult r = pipeline.run("a1", "r1", "hi", msg -> "正文");

        assertEquals("正文", r.reply());
        assertEquals(true, r.extras().get("survived"), "坏钩子不应阻止后面的钩子");
    }

    @Test
    @DisplayName("after_llm 的附加产物不会被误当成文本改写")
    void extrasNotMistakenForText() {
        // 这里故意给一个含 reply 键之外的附加产物，验证 pickString 只认约定键
        hook("a1", "p_extra", HookPoint.after_llm, ctx -> Map.of("audio_url", "u", "chars", 9));

        PipelineResult r = pipeline.run("a1", "r1", "hi", msg -> "正文");

        assertEquals("正文", r.reply());
        assertNull(r.extras().get("output"), "附加产物里不该凭空冒出新键");
    }
}
