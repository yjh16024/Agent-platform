package com.agentplatform.core.plugin.marketplace;

import com.agentplatform.model.entity.AgentPlugin;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.repository.AgentPluginRepository;
import com.agentplatform.model.repository.PluginRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@code contributes.ui_scripts} → 界面贡献列表的转换（2026-10-19，D 档可执行界面）。
 *
 * <h3>为什么单独测这一层</h3>
 * 前端能不能加载到脚本，取决于这里输出的四个字段：{@code type=script} / {@code script} /
 * {@code style} / {@code isolate}。写错任何一个的表现都是**静默的**：
 * type 不对 → 前端当未知类型跳过；script 路径不对 → 404 白屏；isolate 缺省值搞反 → 插件
 * 以为自己是隔离的，实际直接改到了宿主。这些都不会报错。
 */
@ExtendWith(MockitoExtension.class)
class PluginUiScriptContributionTest {

    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private AgentPluginRepository agentPluginRepository;

    @InjectMocks
    private PluginService pluginService;

    /**
     * ★ `@InjectMocks` **不会**注入 `@Value` 字段 —— 它保持 boolean 的默认值 false，
     * 于是"可执行界面总开关"是关的、script 分支根本不执行。
     * 这个坑的表现是"三个用例全红但看不出原因"（逻辑本身完全正确）。
     */
    @BeforeEach
    void enableUiScripts() {
        ReflectionTestUtils.setField(pluginService, "uiScriptEnabled", true);
    }

    @Test
    @DisplayName("声明 ui_scripts → 输出 type=script，script/style/isolate/order 原样带出")
    void outputsScriptContribution() {
        stub("""
                {"id":"pet","slot":"global.overlay","entry":"web/pet.js",
                 "style":"web/pet.css","isolate":false,"order":5,"label":"小宠物"}
                """);

        List<Map<String, Object>> out = pluginService.listUiContributions("t1", "a1");

        assertEquals(1, out.size());
        Map<String, Object> one = out.get(0);
        assertEquals("script", one.get("type"));
        assertEquals("web/pet.js", one.get("script"));
        assertEquals("web/pet.css", one.get("style"));
        assertEquals(false, one.get("isolate"), "显式 false 必须原样带出（否则插件改不动宿主）");
        assertEquals(5, one.get("order"));
        assertEquals("p1", one.get("pluginId"));
        assertEquals("测试插件", one.get("pluginName"));
    }

    @Test
    @DisplayName("isolate 缺省 → true（安全默认值：Shadow DOM）")
    void isolateDefaultsToTrue() {
        stub("""
                {"id":"pet","slot":"global.overlay","entry":"web/pet.js"}
                """);

        List<Map<String, Object>> out = pluginService.listUiContributions("t1", "a1");

        assertEquals(1, out.size());
        assertEquals(true, out.get(0).get("isolate"));
    }

    @Test
    @DisplayName("entry 不在 web/ 下 → 丢弃该项（资源端点只服务 web/，声明了也是 404）")
    void rejectsEntryOutsideWeb() {
        stub("""
                {"id":"bad","slot":"global.overlay","entry":"../secret.js"}
                """);

        assertTrue(pluginService.listUiContributions("t1", "a1").isEmpty());
    }

    // ---------------- 辅助 ----------------

    /** 造一条"该智能体挂载了 p1，p1 声明了给定 ui_scripts"的桩。 */
    private void stub(String uiScriptJson) {
        Map<String, Object> script = parse(uiScriptJson);
        Map<String, Object> contributes = new LinkedHashMap<>();
        contributes.put("ui_scripts", List.of(script));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", "测试插件");
        manifest.put("contributes", contributes);

        PluginDef def = PluginDef.builder()
                .pluginId("p1")
                .tenantId("t1")
                .name("测试插件")
                .manifest(manifest)
                .build();
        when(pluginRepository.findByTenantIdAndPluginId("t1", "p1")).thenReturn(Optional.of(def));

        AgentPlugin binding = AgentPlugin.builder()
                .agentId("a1")
                .pluginId("p1")
                .enabled(true)
                .build();
        when(agentPluginRepository.findByAgentIdAndEnabledTrue("a1")).thenReturn(List.of(binding));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return com.agentplatform.common.util.JsonUtils.fromJson(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
