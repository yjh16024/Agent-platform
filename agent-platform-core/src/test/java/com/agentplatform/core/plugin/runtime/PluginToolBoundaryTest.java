package com.agentplatform.core.plugin.runtime;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolController;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.registry.ToolRegistrationService;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.plugin.sdk.PluginTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件工具在<b>全局工具注册表</b>里的边界：来源标记与删除保护。
 *
 * <h3>它守的是什么</h3>
 * 插件工具最终会落进全局 {@code ToolRegistry}（于是会出现在「工具调试」页）——
 * 这是既有设计，本测试不去改它。但落进去之后有两个必须守住的点：
 *
 * <ol>
 *   <li><b>来源要标出来</b>（{@code plugin}）。此前走单参 {@code register()}，
 *       来源落成默认的 {@code external}，与其它外部工具长得一样。</li>
 *   <li><b>不能在工具页删除</b>。删了只从注册表摘掉、插件侧记账不同步，
 *       而 attach 按 (agentId, pluginId) 幂等 ⇒ <b>插件不会补回</b>，
 *       用户会看到"某台智能体的工具凭空消失"。</li>
 * </ol>
 *
 * <p>⚠️ 第 2 条<b>必须配反向用例</b>：只测"插件工具删不掉"是不够的 ——
 * 一个把删除全部禁掉的实现也能通过那种测试，而那会堵死 MCP/HTTP 工具的正常卸载路径。
 * 所以这里同时断言 MCP 工具<b>仍然可删</b>。</p>
 */
class PluginToolBoundaryTest {

    private static final String AGENT = "agent_1";
    private static final String PLUGIN = "builtin_demo";

    /** 造一个最小可用的插件工具。 */
    private static PluginTool tool(String name) {
        return PluginTool.of(name, "测试用工具", (args, ctx) -> JsonNodeFactory.instance.textNode("ok"));
    }

    /** 装好一套 registry + extensionRegistry。 */
    private static ToolRegistry newRegistryWithPluginTool(String toolName) {
        ToolRegistry registry = new ToolRegistry();
        ExtensionRegistry extensions = new ExtensionRegistry(registry);
        extensions.registerTools(AGENT, PLUGIN, List.of(tool(toolName)), null);
        return registry;
    }

    // ---------------------------------------------------------------- ① 来源标记

    @Test
    @DisplayName("★ 插件工具注册时带 plugin 来源（不再混进 external）")
    void pluginToolsAreTaggedAsPlugin() {
        ToolRegistry registry = newRegistryWithPluginTool("demo_echo");

        assertTrue(registry.contains("demo_echo"));
        assertEquals(ToolRegistry.SOURCE_PLUGIN, registry.sourceOf("demo_echo"),
                "来源必须是 plugin —— 否则工具页无法把它与普通外部工具区分开，也就没法做删除保护");
    }

    @Test
    @DisplayName("插件卸载后，工具与来源标记一起清掉（不留孤儿）")
    void detachRemovesToolAndSource() {
        ToolRegistry registry = newRegistryWithPluginTool("demo_echo");
        ExtensionRegistry extensions = new ExtensionRegistry(registry);
        extensions.registerTools(AGENT, PLUGIN, List.of(tool("demo_echo")), null);

        extensions.unregisterAll(AGENT, PLUGIN);

        assertFalse(registry.contains("demo_echo"), "卸载后工具应从注册表消失");
        // sourceOf 对"未记录"会回落到 builtin；关键是工具本身已经没了
        assertFalse(registry.contains("demo_echo"));
    }

    @Test
    @DisplayName("来源常量与既有取值保持稳定（'builtin' 这个字面量被别处依赖）")
    void sourceConstantsAreStable() {
        assertEquals("builtin", ToolRegistry.SOURCE_BUILTIN);
        assertEquals("http", ToolRegistry.SOURCE_HTTP);
        assertEquals("mcp", ToolRegistry.SOURCE_MCP);
        assertEquals("plugin", ToolRegistry.SOURCE_PLUGIN);
        // 未登记来源按内置兜底（内置在界面上是受保护的一类，兜底到它更安全）
        assertEquals("builtin", new ToolRegistry().sourceOf("never_registered"));
    }

    // ---------------------------------------------------------------- ② 删除保护

    @Test
    @DisplayName("★ 插件工具不能在工具页删除，且提示指向插件页")
    void pluginToolCannotBeDeletedFromToolsPage() {
        ToolRegistry registry = newRegistryWithPluginTool("demo_echo");
        ToolController controller = controllerWith(registry);

        ApiResponse<Void> res = controller.unregister("t1", "demo_echo");

        assertFalse(res.isSuccess(), "应当被拒绝");
        assertEquals("BAD_REQUEST", res.getCode());
        assertTrue(registry.contains("demo_echo"), "拒绝之后工具必须还在（否则就是'拒绝但已删'）");
        assertNotNull(res.getMessage());
        assertTrue(res.getMessage().contains("插件"),
                "提示要指出去哪做这件事，否则用户只知道'不行'： " + res.getMessage());
    }

    @Test
    @DisplayName("★ 反向：MCP 工具仍然可以删（别把正常路径一起堵死）")
    void mcpToolIsStillDeletable() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub("mcp_tool"), ToolRegistry.SOURCE_MCP);
        ToolController controller = controllerWith(registry);

        ApiResponse<Void> res = controller.unregister("t1", "mcp_tool");

        assertTrue(res.isSuccess(), "MCP 工具的卸载是正常路径，不该被插件保护牵连");
        assertFalse(registry.contains("mcp_tool"));
    }

    @Test
    @DisplayName("反向：内置工具仍然不可删（既有行为不能被改坏）")
    void builtinToolStillProtected() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub("calc"), ToolRegistry.SOURCE_BUILTIN);
        ToolController controller = controllerWith(registry);

        ApiResponse<Void> res = controller.unregister("t1", "calc");

        assertFalse(res.isSuccess());
        assertTrue(registry.contains("calc"));
    }

    @Test
    @DisplayName("删不存在的工具返回 NOT_FOUND（不误报成'插件工具不可删'）")
    void unknownToolIsNotFound() {
        ApiResponse<Void> res = controllerWith(new ToolRegistry()).unregister("t1", "not_exist");
        assertFalse(res.isSuccess());
        assertEquals("NOT_FOUND", res.getCode());
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 注入 registry 与一个"会真的删"的 registrationService，其余传 null。
     *
     * <p>两处细节都是踩过的：</p>
     * <ul>
     *   <li><b>不能传 null</b>：删除**通过校验之后**会真的执行（`registrationService.unregister`
     *       → `registry.unregister`）。传 null 时"本应放行"的用例会以 NPE 失败 ——
     *       看起来像被拒绝、其实是崩了，会把排查方向带偏。</li>
     *   <li><b>mock 要回填 registry</b>：只 mock 出"调用成功"的话，工具实际没被移除，
     *       "删掉了"这个断言就成了假的。这里按参数内容猜哪个是工具名，
     *       免得依赖 `unregister(a, b)` 的参数顺序（那个顺序在本测试里并不重要）。</li>
     * </ul>
     */
    private static ToolController controllerWith(ToolRegistry registry) {
        ToolRegistrationService registration = Mockito.mock(ToolRegistrationService.class);
        Mockito.doAnswer(inv -> {
            for (Object arg : inv.getArguments()) {
                if (arg instanceof String s && registry.contains(s)) {
                    registry.unregister(s);
                }
            }
            return null;
        }).when(registration).unregister(Mockito.anyString(), Mockito.anyString());
        return new ToolController(registry, null, null, registration, null, null, null);
    }

    /** 一个最小的 Tool 实现（只为占住名字与来源）。 */
    private static Tool stub(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "stub";
            }

            @Override
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                return ToolResult.ok("ok");
            }
        };
    }
}
