package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.HookPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 管线（Hook 织入）单元测试：短路、附加产物、按智能体隔离。
 *
 * <p>用测试内部替身插件，不依赖生产代码里的示例插件。</p>
 */
class AgentPipelineTest {

    /** 替身：命中关键词即短路。 */
    static class ShortCircuitPlugin implements AgentHook {

        static final String ID = "plugin_test_short_circuit";

        @Override
        public String id() {
            return ID;
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
            return HookPoint.before_llm;
        }

        @Override
        public Object invoke(HookContext ctx) {
            String message = ctx.input() == null ? "" : ctx.input().toString();
            return message.contains("营业时间") ? "我们的营业时间是 9:00-18:00。" : null;
        }
    }

    /** 替身：after_llm 注入附加产物。 */
    static class ExtrasPlugin implements AgentHook {

        static final String ID = "plugin_test_extras";

        @Override
        public String id() {
            return ID;
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
            return HookPoint.after_llm;
        }

        @Override
        public Object invoke(HookContext ctx) {
            int len = ctx.input() == null ? 0 : ctx.input().toString().length();
            return Map.of("reply_chars", len);
        }
    }

    private ExtensionRegistry registry;
    private AgentPipeline pipeline;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry(new ToolRegistry());
        pipeline = new AgentPipeline(registry);
    }

    /** 按真实路径注册：registerPlugin 从 PluginContext 取归属。 */
    private void register(AgentHook plugin, String agentId) {
        PluginContext ctx = new PluginContext(agentId, "t1", null, registry);
        registry.registerPlugin(plugin, ctx);
    }

    @Test
    @DisplayName("前置钩子：命中关键词短路（不调 LLM）")
    void hookShortCircuits() {
        register(new ShortCircuitPlugin(), "agent_1");

        AtomicBoolean llmCalled = new AtomicBoolean(false);
        PipelineResult result = pipeline.run("agent_1", "run_1", "你们营业时间是什么？", msg -> {
            llmCalled.set(true);
            return "LLM 回复";
        });

        assertTrue(result.shortCircuited());
        assertFalse(llmCalled.get(), "命中短路钩子时不应调 LLM");
        assertTrue(result.reply().contains("营业时间"));
    }

    @Test
    @DisplayName("未命中关键词时正常调 LLM")
    void noKeywordCallsLlm() {
        register(new ShortCircuitPlugin(), "agent_1");

        AtomicBoolean llmCalled = new AtomicBoolean(false);
        PipelineResult result = pipeline.run("agent_1", "run_1", "你好", msg -> {
            llmCalled.set(true);
            return "LLM 回复";
        });

        assertFalse(result.shortCircuited());
        assertTrue(llmCalled.get());
        assertEquals("LLM 回复", result.reply());
    }

    @Test
    @DisplayName("后置钩子：after_llm 注入附加产物")
    void extraInjected() {
        register(new ExtrasPlugin(), "agent_1");

        PipelineResult result = pipeline.run("agent_1", "run_1", "你好", msg -> "这是回复");
        assertFalse(result.shortCircuited());
        assertEquals(4, result.extras().get("reply_chars"));
    }

    @Test
    @DisplayName("按智能体隔离：A 上的短路钩子不会影响 B（回归：此前是全局生效）")
    void hooksAreIsolatedPerAgent() {
        register(new ShortCircuitPlugin(), "agent_1");

        AtomicBoolean llmCalled = new AtomicBoolean(false);
        PipelineResult result = pipeline.run("agent_2", "run_2", "你们营业时间是什么？", msg -> {
            llmCalled.set(true);
            return "B 的 LLM 回复";
        });

        assertFalse(result.shortCircuited(), "agent_1 的钩子不应在 agent_2 上短路");
        assertTrue(llmCalled.get());
    }

    @Test
    @DisplayName("HookContext 带上 agentId / runId（此前恒为 null）")
    void hookContextCarriesRunIdentity() {
        AtomicBoolean checked = new AtomicBoolean(false);
        AgentHook probe = new AgentHook() {
            @Override
            public String id() {
                return "plugin_test_probe";
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
                return HookPoint.before_llm;
            }

            @Override
            public Object invoke(HookContext ctx) {
                assertEquals("agent_9", ctx.agentId());
                assertEquals("run_9", ctx.runId());
                checked.set(true);
                return null;
            }
        };
        register(probe, "agent_9");

        pipeline.run("agent_9", "run_9", "你好", msg -> "回复");
        assertTrue(checked.get());
    }

    @Test
    @DisplayName("插件工具按智能体隔离：A 的工具不出现在 B 的可查询集合里")
    void pluginToolsAreScopedPerAgent() {
        PluginContext ctxA = new PluginContext("agent_1", "t1", null, registry);
        registry.runInAttachScope("agent_1", "plugin_x", () -> registry.registerTools(
                "agent_1", "plugin_x",
                List.of(com.agentplatform.plugin.sdk.PluginTool.of("tool_x", "测试工具", (args, c) -> null)),
                ctxA));

        assertTrue(registry.pluginToolNamesOf("agent_1").contains("tool_x"));
        assertTrue(registry.pluginToolNamesExcept("agent_2").contains("tool_x"),
                "对 agent_2 而言 tool_x 属于他人插件工具，应被剔除");
        assertFalse(registry.pluginToolNamesExcept("agent_1").contains("tool_x"));
    }
}
