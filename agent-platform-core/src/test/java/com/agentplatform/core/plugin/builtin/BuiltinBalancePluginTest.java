package com.agentplatform.core.plugin.builtin;

import com.agentplatform.core.model.balance.ModelBalanceView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuiltinBalancePlugin} 的渲染测试。
 *
 * <h3>为什么重点测"四种状态分开表达"</h3>
 * 余额查询有四种彼此无关的结果，而它们对用户的含义完全不同：
 * <pre>
 *   未配置    → 你去把模型配好就行
 *   不支持    → 换个方式看（去厂商控制台），重试一万次也没用
 *   查询失败  → 可能是网络/密钥，值得重试
 *   成功      → 给出数字
 * </pre>
 * 混成一句"查询失败"，用户会去排查根本不存在的问题 —— 而"不支持"这一支尤其容易被误判成故障：
 * 硅基流动的余额接口已于 2026-08-14 下线，这类"厂商没这个接口"的情形必须原样说明。
 */
class BuiltinBalancePluginTest {

    private final BuiltinBalancePlugin plugin = new BuiltinBalancePlugin(null);

    @Test
    @DisplayName("元信息：贡献 get_balance 工具，且不需要任何参数")
    void metadata() {
        assertEquals("builtin_balance", plugin.id());
        assertNotNull(plugin.name());
        assertNotNull(plugin.description());
        assertEquals(1, plugin.provideTools().size());

        var tool = plugin.provideTools().get(0);
        assertEquals("get_balance", tool.name());
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().path("type").asText());
        // 无参数：用户问"还剩多少钱"时，模型不需要决定任何东西
        assertTrue(tool.inputSchema().path("properties").isObject());
        assertTrue(tool.inputSchema().path("required").isMissingNode());
    }

    // ---------------------------------------------------------------- 四种状态

    @Test
    @DisplayName("★ 成功：带上金额、币种与明细，并提示「别补充推测」")
    void rendersSuccess() {
        String text = BuiltinBalancePlugin.render(List.of(view(
                "chat", "deepseek", "deepseek-chat", true, true, true,
                "CNY", "42.50",
                List.of(new ModelBalanceView.Item("总余额", "42.50"),
                        new ModelBalanceView.Item("赠金", "10.00")),
                null)));

        assertTrue(text.contains("对话模型"), text);
        assertTrue(text.contains("deepseek-chat"), text);
        assertTrue(text.contains("42.50"), text);
        assertTrue(text.contains("CNY"), text);
        assertTrue(text.contains("赠金"), "明细要带出来：" + text);
        assertTrue(text.contains("实时"), "要说明数据来源，避免模型把它当成缓存值");
        assertTrue(text.contains("不要"), "要抑制模型对金额的发挥：" + text);
    }

    @Test
    @DisplayName("★ 不支持：原样带出厂商侧说明，并指向控制台（重试无意义的要说明）")
    void rendersUnsupported() {
        String text = BuiltinBalancePlugin.render(List.of(view(
                "chat", "siliconflow", "Qwen/Qwen2.5", true, false, false,
                null, null, null,
                "硅基流动已于 2026-08-14 下线余额查询接口")));

        assertTrue(text.contains("无法查询"), text);
        assertTrue(text.contains("硅基流动"), text);
        assertTrue(text.contains("控制台"), "要给出可行出路：" + text);
    }

    @Test
    @DisplayName("★ 未配置：说清「没配」而不是「查不到」")
    void rendersNotConfigured() {
        String text = BuiltinBalancePlugin.render(List.of(view(
                "embedding", null, null, false, false, false,
                null, null, null, null)));

        assertTrue(text.contains("嵌入模型"), text);
        assertTrue(text.contains("未配置"), text);
        assertTrue(text.contains("模型设置"), "要指出去哪配：" + text);
    }

    @Test
    @DisplayName("★ 查询失败：区别于「不支持」—— 这一支才值得重试")
    void rendersFailure() {
        String text = BuiltinBalancePlugin.render(List.of(view(
                "chat", "deepseek", "deepseek-chat", true, true, false,
                null, null, null, "HTTP 401 Unauthorized")));

        assertTrue(text.contains("查询失败"), text);
        assertTrue(text.contains("401"), "要带上上游原因：" + text);
        assertTrue(text.contains("重试"), "失败是可以重试的 —— 与「不支持」必须区分开");
    }

    @Test
    @DisplayName("多条绑定一起渲染（对话模型 + 嵌入模型）")
    void rendersBothBindings() {
        String text = BuiltinBalancePlugin.render(List.of(
                view("chat", "deepseek", "deepseek-chat", true, true, true, "CNY", "10.00", null, null),
                view("embedding", "moonshot", "moonshot-embedding", true, true, true, "CNY", "8.00", null, null)));

        assertTrue(text.contains("对话模型"), text);
        assertTrue(text.contains("嵌入模型"), text);
        assertTrue(text.contains("10.00") && text.contains("8.00"), text);
    }

    @Test
    @DisplayName("没有任何绑定时给出可执行的下一步（而不是空白）")
    void rendersEmpty() {
        String text = BuiltinBalancePlugin.render(List.of());

        assertTrue(text.contains("模型设置"), text);
    }

    @Test
    @DisplayName("明细项缺值时不崩、不显示 null")
    void toleratesMissingItemValue() {
        String text = BuiltinBalancePlugin.render(List.of(view(
                "chat", "deepseek", "m", true, true, true, "CNY", "1.00",
                List.of(new ModelBalanceView.Item("现金", null)), null)));

        assertTrue(text.contains("现金"), text);
        assertTrue(!text.contains("null"), "不要把 null 打到界面上：" + text);
    }

    // ---------------------------------------------------------------- 参数

    @Test
    @DisplayName("参数可空（工具无入参，模型传 null 也能走到查询逻辑）")
    void acceptsNullArgs() {
        // plugin 的 balanceService 是 null → 会在调用时抛 IllegalStateException，
        // 但绝不能是 NullPointerException（那说明参数处理写错了）
        var ex = assertThrows(IllegalStateException.class,
                () -> plugin.provideTools().get(0).handler().execute(null, null));

        assertTrue(ex.getMessage().contains("余额查询失败"), ex.getMessage());
    }

    // ---------------------------------------------------------------- 工具

    private static ModelBalanceView view(String binding, String provider, String model,
                                         boolean configured, boolean supported, boolean ok,
                                         String currency, String available,
                                         List<ModelBalanceView.Item> items, String message) {
        return new ModelBalanceView(binding, provider, model, "https://api.example.com",
                configured, supported, ok, currency, available, items, message, 1_700_000_000_000L);
    }
}
