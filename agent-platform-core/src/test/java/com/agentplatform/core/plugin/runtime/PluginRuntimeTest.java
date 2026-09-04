package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.plugin.builtin.AutoReplyPlugin;
import com.agentplatform.core.tool.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件运行时（生命周期）单元测试。
 */
class PluginRuntimeTest {

    private PluginRuntime runtime;
    private ExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry(new ToolRegistry());
        runtime = new PluginRuntime(registry, List.of(new AutoReplyPlugin()));
    }

    @Test
    @DisplayName("attach 内置插件并注册贡献")
    void attachBuiltin() {
        runtime.attach(AutoReplyPlugin.PLUGIN_ID, "agent_1", "t1", Map.of());
        assertTrue(runtime.isAttached(AutoReplyPlugin.PLUGIN_ID));
        // 钩子已注册到 before_llm
        assertFalse(registry.hooksAt(com.agentplatform.plugin.sdk.model.HookPoint.before_llm).isEmpty());
    }

    @Test
    @DisplayName("attach 幂等（不重复注册 Hook）")
    void attachIdempotent() {
        runtime.attach(AutoReplyPlugin.PLUGIN_ID, "agent_1", "t1", Map.of());
        runtime.attach(AutoReplyPlugin.PLUGIN_ID, "agent_1", "t1", Map.of());
        assertEquals(1, registry.hooksAt(com.agentplatform.plugin.sdk.model.HookPoint.before_llm).size());
    }

    @Test
    @DisplayName("detach 反注册")
    void detachUnregisters() {
        runtime.attach(AutoReplyPlugin.PLUGIN_ID, "agent_1", "t1", Map.of());
        runtime.detach(AutoReplyPlugin.PLUGIN_ID);
        assertFalse(runtime.isAttached(AutoReplyPlugin.PLUGIN_ID));
        assertTrue(registry.hooksAt(com.agentplatform.plugin.sdk.model.HookPoint.before_llm).isEmpty());
    }

    @Test
    @DisplayName("attach 不存在的插件抛异常")
    void attachMissingThrows() {
        assertThrows(Exception.class, () -> runtime.attach("plugin_ghost", "agent_1", "t1", Map.of()));
    }
}