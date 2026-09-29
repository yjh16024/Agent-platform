package com.agentplatform.core.plugin.builtin;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.multimodal.QuotaService;
import com.agentplatform.core.plugin.builtin.BuiltinUsageGuardPlugin.GuardConfig;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link BuiltinUsageGuardPlugin} 的测试。
 *
 * <h3>三组重点</h3>
 * <ol>
 *   <li><b>记账真的落了</b>：用<b>真实的</b> {@code QuotaService}（它的依赖都是
 *       {@code @Autowired(required = false)}，可空，内部有内存兜底）验证 token 真的被累加 ——
 *       这正是"声明了却没人递增"的那个缺口。</li>
 *   <li><b>预警每天只推一次</b>：越过阈值之后<b>每轮都满足条件</b>，不设闸门就会把群刷屏 ——
 *       而刷屏的结果是用户把通知静音，于是预警彻底失效。</li>
 *   <li><b>阈值回落要重置</b>：否则"用完 → 调高配额 → 再次逼近"时不会提醒第二次。</li>
 * </ol>
 */
class BuiltinUsageGuardPluginTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---------------------------------------------------------------- 元信息与配置

    @Test
    @DisplayName("元信息：贡献 get_usage 工具，且只订阅运行完成事件")
    void metadata() {
        var plugin = newPlugin(new QuotaService());
        assertEquals("builtin_usage_guard", plugin.id());
        assertNotNull(plugin.name());
        assertEquals(1, plugin.provideTools().size());
        assertEquals("get_usage", plugin.provideTools().get(0).name());
        assertEquals(java.util.Set.of(EventTypes.AGENT_RUN_COMPLETED), plugin.eventTypes());
    }

    @Test
    @DisplayName("默认阈值 80%，且被夹在 10~100（免得配个 0 把每条消息都变成预警）")
    void warnPercentDefaultsAndClamped() {
        assertEquals(80, GuardConfig.of(JsonUtils.toJsonNode("{}")).warnPercent());

        assertEquals(10, GuardConfig.of(JsonUtils.toJsonNode("{\"warnPercent\":0}")).warnPercent());
        assertEquals(100, GuardConfig.of(JsonUtils.toJsonNode("{\"warnPercent\":999}")).warnPercent());
        assertEquals(50, GuardConfig.of(JsonUtils.toJsonNode("{\"warnPercent\":50}")).warnPercent());

        assertFalse(GuardConfig.of(JsonUtils.toJsonNode("{}")).hasWebhook());
    }

    // ---------------------------------------------------------------- ★ 记账

    @Test
    @DisplayName("★ 记账：事件里的 prompt/completion tokens 真的被累加进配额")
    void recordsTokensIntoQuota() {
        // 真实 QuotaService：全依赖可空，Redis 缺失时走内存计数器
        QuotaService quota = new QuotaService();
        var plugin = newPlugin(quota);
        plugin.onAttach(ctx("agent-a", "{}"));

        plugin.onEvent(completed("t1", "agent-a", 120, 80));
        plugin.onEvent(completed("t1", "agent-a", 30, 20));

        assertEquals(250, quota.usage("t1", BuiltinUsageGuardPlugin.QUOTA_TOKEN),
                "两轮共 250 token 应被累计");
    }

    @Test
    @DisplayName("token 为 0（上游流式不返回 usage）时不记账，也不出错")
    void zeroTokensAreIgnored() {
        QuotaService quota = new QuotaService();
        var plugin = newPlugin(quota);
        plugin.onAttach(ctx("agent-a", "{}"));

        plugin.onEvent(completed("t1", "agent-a", 0, 0));

        assertEquals(0, quota.usage("t1", BuiltinUsageGuardPlugin.QUOTA_TOKEN));
    }

    @Test
    @DisplayName("没挂本插件的智能体：事件不记账（按 agentId 隔离）")
    void ignoresAgentsWithoutPlugin() {
        QuotaService quota = new QuotaService();
        var plugin = newPlugin(quota);
        plugin.onAttach(ctx("agent-a", "{}"));

        plugin.onEvent(completed("t1", "agent-b", 100, 100));

        assertEquals(0, quota.usage("t1", BuiltinUsageGuardPlugin.QUOTA_TOKEN));
    }

    // ---------------------------------------------------------------- ★ 预警

    @Test
    @DisplayName("★ 越过阈值推送一次；同一天再超也不再推（否则每轮一条，用户会静音）")
    void warnsOncePerDay() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = startServer(exchange -> {
            hits.incrementAndGet();
            respond(exchange, "ok");
        });

        QuotaService quota = Mockito.mock(QuotaService.class);
        when(quota.list("t1")).thenReturn(List.of(
                Map.of("quotaType", "tokens", "limit", 1000L, "used", 850L),
                Map.of("quotaType", "model_calls", "limit", 10000L, "used", 10L)));
        var plugin = newPlugin(quota);
        plugin.onAttach(ctx("agent-a", "{\"webhookUrl\":\"" + url + "\",\"channel\":\"generic\"}"));

        plugin.onEvent(completed("t1", "agent-a", 100, 100));
        plugin.onEvent(completed("t1", "agent-a", 100, 100));
        plugin.onEvent(completed("t1", "agent-a", 100, 100));

        assertEquals(1, hits.get(), "同一天只应推送一次");
    }

    @Test
    @DisplayName("★ 预警正文要说清哪一项超了、超了多少")
    void warningMentionsWhichQuota() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String url = startServer(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "ok");
        });

        QuotaService quota = Mockito.mock(QuotaService.class);
        when(quota.list("t1")).thenReturn(List.of(
                Map.of("quotaType", "tokens", "limit", 1000L, "used", 900L)));
        var plugin = newPlugin(quota);
        plugin.onAttach(ctx("agent-a", "{\"webhookUrl\":\"" + url + "\",\"channel\":\"generic\"}"));

        plugin.onEvent(completed("t1", "agent-a", 10, 10));

        String text = JsonUtils.toJsonNode(body.get()).path("text").asText();
        assertTrue(text.contains("Token"), text);
        assertTrue(text.contains("900"), "要给出已用量：" + text);
        assertTrue(text.contains("1000"), "要给出上限：" + text);
    }

    @Test
    @DisplayName("未达阈值不推送")
    void silentBelowThreshold() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = startServer(exchange -> {
            hits.incrementAndGet();
            respond(exchange, "ok");
        });
        QuotaService quota = Mockito.mock(QuotaService.class);
        when(quota.list("t1")).thenReturn(List.of(
                Map.of("quotaType", "tokens", "limit", 1000L, "used", 100L)));
        var plugin = newPlugin(quota);
        plugin.onAttach(ctx("agent-a", "{\"webhookUrl\":\"" + url + "\"}"));

        plugin.onEvent(completed("t1", "agent-a", 10, 10));

        assertEquals(0, hits.get());
    }

    @Test
    @DisplayName("★ 没配 webhook：不推送，但照样记账（只统计不预警是合法的用法）")
    void recordsWithoutWebhook() {
        QuotaService quota = Mockito.mock(QuotaService.class);
        when(quota.list("t1")).thenReturn(List.of());
        var plugin = newPlugin(quota);
        plugin.onAttach(ctx("agent-a", "{}"));

        plugin.onEvent(completed("t1", "agent-a", 40, 60));

        Mockito.verify(quota).recordUsage(eq("t1"), eq(BuiltinUsageGuardPlugin.QUOTA_TOKEN), eq(100L));
        Mockito.verify(quota, Mockito.never()).usage(any(), any());
    }

    @Test
    @DisplayName("★ 记账抛异常时不影响事件派发（其它订阅者不该被连累）")
    void recordFailureIsSwallowed() {
        QuotaService quota = Mockito.mock(QuotaService.class);
        when(quota.recordUsage(any(), any(), anyLong())).thenThrow(new RuntimeException("redis down"));
        when(quota.list("t1")).thenReturn(List.of());
        var plugin = newPlugin(quota);
        plugin.onAttach(ctx("agent-a", "{}"));

        // 不该抛
        plugin.onEvent(completed("t1", "agent-a", 10, 10));
    }

    // ---------------------------------------------------------------- 工具渲染

    @Test
    @DisplayName("工具：渲染出各项用量与占比")
    void rendersUsage() {
        String text = BuiltinUsageGuardPlugin.render("t1", List.of(
                Map.of("quotaType", "tokens", "limit", 1000L, "used", 250L),
                Map.of("quotaType", "model_calls", "limit", 10000L, "used", 12L)));

        assertTrue(text.contains("Token"), text);
        assertTrue(text.contains("250"), text);
        assertTrue(text.contains("25%"), "要给出占比，用户才能一眼判断：" + text);
        assertTrue(text.contains("模型调用"), text);
        assertTrue(text.contains("租户"), "要说明累计口径：" + text);
    }

    @Test
    @DisplayName("工具：没有上限时说明「未设上限」而不是显示一个荒唐的大数字")
    void rendersWithoutLimit() {
        String text = BuiltinUsageGuardPlugin.render("t1", List.of(
                Map.of("quotaType", "tokens", "limit", Long.MAX_VALUE, "used", 5L)));

        assertTrue(text.contains("未设上限"), text);
    }

    // ---------------------------------------------------------------- 工具

    private BuiltinUsageGuardPlugin newPlugin(QuotaService quota) {
        return new BuiltinUsageGuardPlugin(quota);
    }

    private PluginContext ctx(String agentId, String json) {
        return new PluginContext(agentId, "t1", JsonUtils.toJsonNode(json), null);
    }

    private DomainEvent completed(String tenantId, String agentId, int prompt, int completion) {
        return DomainEvent.ofAgent(EventTypes.AGENT_RUN_COMPLETED, tenantId, agentId, "run-1",
                DomainEvent.payload("agent_id", agentId, "agent_name", "客服助手",
                        "model", "deepseek-chat",
                        "prompt_tokens", prompt, "completion_tokens", completion));
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private String startServer(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
