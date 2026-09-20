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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件运行时（生命周期 + 按智能体隔离）单元测试。
 *
 * <p>用测试内部定义的替身插件，不依赖生产代码里的示例插件 —— 这样插件替身被删除时，
 * 测试不会跟着失效。</p>
 */
class PluginRuntimeTest {

    /** 测试替身：只挂 before_llm 的内置插件。 */
    static class TestHookPlugin implements AgentHook {

        static final String ID = "plugin_test_hook";

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
            return null;
        }
    }

    private PluginRuntime runtime;
    private ExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry(new ToolRegistry());
        runtime = new PluginRuntime(registry, List.of(new TestHookPlugin()));
    }

    @Test
    @DisplayName("attach 内置插件并注册贡献")
    void attachBuiltin() {
        runtime.attach(TestHookPlugin.ID, "agent_1", "t1", Map.of());
        assertTrue(runtime.isAttached("agent_1", TestHookPlugin.ID));
        assertFalse(registry.hooksAt(HookPoint.before_llm, "agent_1").isEmpty());
    }

    @Test
    @DisplayName("attach 幂等：同一智能体重复 attach 不重复注册 Hook")
    void attachIdempotent() {
        runtime.attach(TestHookPlugin.ID, "agent_1", "t1", Map.of());
        runtime.attach(TestHookPlugin.ID, "agent_1", "t1", Map.of());
        assertEquals(1, registry.hooksAt(HookPoint.before_llm, "agent_1").size());
    }

    @Test
    @DisplayName("detach 反注册该智能体的贡献")
    void detachUnregisters() {
        runtime.attach(TestHookPlugin.ID, "agent_1", "t1", Map.of());
        runtime.detach("agent_1", TestHookPlugin.ID);
        assertFalse(runtime.isAttached("agent_1", TestHookPlugin.ID));
        assertTrue(registry.hooksAt(HookPoint.before_llm, "agent_1").isEmpty());
    }

    @Test
    @DisplayName("钩子按智能体隔离：挂到 A 的钩子在 B 上不可见（回归：此前是全局生效）")
    void hooksAreIsolatedPerAgent() {
        runtime.attach(TestHookPlugin.ID, "agent_1", "t1", Map.of());
        assertFalse(registry.hooksAt(HookPoint.before_llm, "agent_1").isEmpty());
        assertTrue(registry.hooksAt(HookPoint.before_llm, "agent_2").isEmpty(),
                "agent_1 挂载的插件钩子不应出现在 agent_2 上");
    }

    @Test
    @DisplayName("同一插件可分别挂到多台智能体，卸载一台不影响另一台（回归：此前第二台会被幂等跳过）")
    void samePluginOnTwoAgents() {
        runtime.attach(TestHookPlugin.ID, "agent_1", "t1", Map.of());
        runtime.attach(TestHookPlugin.ID, "agent_2", "t1", Map.of());

        assertFalse(registry.hooksAt(HookPoint.before_llm, "agent_1").isEmpty());
        assertFalse(registry.hooksAt(HookPoint.before_llm, "agent_2").isEmpty());

        runtime.detach("agent_1", TestHookPlugin.ID);

        assertTrue(registry.hooksAt(HookPoint.before_llm, "agent_1").isEmpty());
        assertFalse(registry.hooksAt(HookPoint.before_llm, "agent_2").isEmpty(),
                "卸载 agent_1 不应连带卸掉 agent_2 的插件");
        assertTrue(runtime.isAttached(TestHookPlugin.ID), "仍有智能体在用，插件实例应保留");
    }

    @Test
    @DisplayName("detachEverywhere 清掉所有智能体上的挂载")
    void detachEverywhereClearsAll() {
        runtime.attach(TestHookPlugin.ID, "agent_1", "t1", Map.of());
        runtime.attach(TestHookPlugin.ID, "agent_2", "t1", Map.of());

        List<String> agents = runtime.detachEverywhere(TestHookPlugin.ID);

        assertEquals(2, agents.size());
        assertFalse(runtime.isAttached(TestHookPlugin.ID));
        assertTrue(registry.hooksAt(HookPoint.before_llm, "agent_1").isEmpty());
        assertTrue(registry.hooksAt(HookPoint.before_llm, "agent_2").isEmpty());
    }

    @Test
    @DisplayName("attach 不存在的插件抛异常")
    void attachMissingThrows() {
        assertThrows(Exception.class, () -> runtime.attach("plugin_ghost", "agent_1", "t1", Map.of()));
    }

    @Test
    @DisplayName("在 attach 作用域外注册钩子会被拒绝（避免悄悄退化成全局注册）")
    void registerOutsideScopeRejected() {
        assertThrows(IllegalStateException.class,
                () -> registry.registerHook(HookPoint.before_llm, new TestHookPlugin()));
    }
}
