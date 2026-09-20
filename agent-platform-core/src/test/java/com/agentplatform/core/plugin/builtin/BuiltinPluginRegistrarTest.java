package com.agentplatform.core.plugin.builtin;

import com.agentplatform.core.plugin.runtime.ExtensionRegistry;
import com.agentplatform.core.plugin.runtime.PluginRuntime;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.model.entity.AgentPlugin;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.repository.AgentPluginRepository;
import com.agentplatform.model.repository.PluginRepository;
import com.agentplatform.plugin.sdk.AgentHook;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.Plugin;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.HookPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 内置插件注册器单元测试：新建 / 更新 / 清理失效记录。
 */
class BuiltinPluginRegistrarTest {

    /** 替身：带展示信息 + 一个钩子 + 一个工具。 */
    static class DescribedPlugin implements AgentHook, ToolProvider, PluginDescriptor {

        static final String ID = "plugin_test_described";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String version() {
            return "2.0.0";
        }

        @Override
        public String name() {
            return "测试内置插件";
        }

        @Override
        public String description() {
            return "用于验证自动注册";
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
            return null;
        }

        @Override
        public List<PluginTool> provideTools() {
            return List.of(PluginTool.of("test_tool", "测试工具", (args, ctx) -> null));
        }
    }

    private static PluginRuntime runtimeOf(List<Plugin> plugins) {
        return new PluginRuntime(new ExtensionRegistry(new ToolRegistry()), plugins);
    }

    @Test
    @DisplayName("代码里有、库里没有 → 新建 plugin_def（__platform__ / 无制品 / 带贡献清单）")
    void createsMissingRecord() {
        PluginRepository repo = mock(PluginRepository.class);
        AgentPluginRepository bindings = mock(AgentPluginRepository.class);
        when(repo.findByTenantIdAndPluginId(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.findByTenantIdOrderByUpdatedAtDesc(anyString())).thenReturn(List.of());

        new BuiltinPluginRegistrar(runtimeOf(List.of(new DescribedPlugin())), repo, bindings).sync();

        ArgumentCaptor<PluginDef> captor = ArgumentCaptor.forClass(PluginDef.class);
        verify(repo).save(captor.capture());
        PluginDef saved = captor.getValue();

        assertEquals(DescribedPlugin.ID, saved.getPluginId());
        assertEquals("__platform__", saved.getTenantId());
        assertEquals("测试内置插件", saved.getName());
        assertEquals("用于验证自动注册", saved.getDescription());
        assertEquals("2.0.0", saved.getLatestVersion());
        assertNull(saved.getArtifactUri(), "内置插件没有制品，artifact_uri 必须为空");
        assertNotNull(saved.getManifest(), "manifest 是 NOT NULL 列，必须写入");
        assertEquals("builtin", ((Map<?, ?>) saved.getManifest().get("entry")).get("type"));
    }

    @Test
    @DisplayName("库里已存在 → 覆盖更新，不新建")
    void updatesExistingRecord() {
        PluginRepository repo = mock(PluginRepository.class);
        AgentPluginRepository bindings = mock(AgentPluginRepository.class);
        PluginDef stale = PluginDef.builder()
                .pluginId(DescribedPlugin.ID)
                .tenantId("__platform__")
                .name("旧名字")
                .latestVersion("1.0.0")
                .manifest(Map.of("id", DescribedPlugin.ID))
                .build();
        when(repo.findByTenantIdAndPluginId(anyString(), anyString())).thenReturn(Optional.of(stale));
        when(repo.findByTenantIdOrderByUpdatedAtDesc(anyString())).thenReturn(List.of(stale));

        new BuiltinPluginRegistrar(runtimeOf(List.of(new DescribedPlugin())), repo, bindings).sync();

        verify(repo).save(stale);
        verify(repo, never()).delete(any(PluginDef.class));
        assertEquals("测试内置插件", stale.getName(), "应被代码里的最新元信息覆盖");
        assertEquals("2.0.0", stale.getLatestVersion());
    }

    @Test
    @DisplayName("库里标着内置但代码里已不存在 → 删除记录并解除绑定（避免僵尸条目）")
    void prunesMissingRecord() {
        PluginRepository repo = mock(PluginRepository.class);
        AgentPluginRepository bindings = mock(AgentPluginRepository.class);
        PluginDef orphan = PluginDef.builder()
                .pluginId("plugin_removed")
                .tenantId("__platform__")
                .name("已被删除的插件")
                .manifest(Map.of("id", "plugin_removed"))
                .build();
        AgentPlugin bound = AgentPlugin.builder().agentId("agent_1").pluginId("plugin_removed").build();
        when(repo.findByTenantIdOrderByUpdatedAtDesc(anyString())).thenReturn(List.of(orphan));
        when(bindings.findByPluginId("plugin_removed")).thenReturn(List.of(bound));

        new BuiltinPluginRegistrar(runtimeOf(List.of()), repo, bindings).sync();

        verify(bindings).delete(bound);
        verify(repo).delete(orphan);
    }

    @Test
    @DisplayName("没有内置插件时不做任何写入")
    void noBuiltinsNoWrites() {
        PluginRepository repo = mock(PluginRepository.class);
        AgentPluginRepository bindings = mock(AgentPluginRepository.class);
        when(repo.findByTenantIdOrderByUpdatedAtDesc(anyString())).thenReturn(List.of());

        new BuiltinPluginRegistrar(runtimeOf(List.of()), repo, bindings).sync();

        verify(repo, never()).save(any(PluginDef.class));
        verify(repo, never()).delete(any(PluginDef.class));
    }
}
