package com.agentplatform.core.plugin.builtin;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.plugin.builtin.BuiltinNotifierPlugin.NotifyConfig;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuiltinNotifierPlugin} 的测试。
 *
 * <h3>为什么重点测「body 形状」</h3>
 * 飞书、钉钉、企业微信、Slack 的群机器人 webhook <b>长得像但字段名不同</b>：
 * 飞书要 {@code msg_type} + {@code content.text}，钉钉/企微要 {@code msgtype} + {@code text.content}。
 * <b>发错的症状是"配了但收不到"</b> —— 对方多半静默丢弃或回一个含义模糊的码，
 * 而人会先去怀疑 webhook 地址填错了。这类错误编译期完全看不出来，只能在测试里钉住。
 *
 * <p>另一组重点是<b>默认开关</b>：失败推送默认开、完成推送默认关。
 * 后者若默认开，一场十几轮的对话会把群刷屏 —— 那是"功能可用但没人愿意开着"的典型。</p>
 */
class BuiltinNotifierPluginTest {

    private final BuiltinNotifierPlugin plugin = new BuiltinNotifierPlugin();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---------------------------------------------------------------- 元信息

    @Test
    @DisplayName("元信息：同时贡献工具与事件订阅，且只订阅可订阅的两个事件")
    void metadata() {
        assertEquals("builtin_notifier", plugin.id());
        assertNotNull(plugin.name());
        assertNotNull(plugin.description());
        assertEquals(1, plugin.provideTools().size());
        assertEquals("send_notification", plugin.provideTools().get(0).name());

        assertEquals(2, plugin.eventTypes().size(), "只应订阅可订阅的事件");
        assertTrue(plugin.eventTypes().contains(EventTypes.AGENT_RUN_FAILED));
        assertTrue(plugin.eventTypes().contains(EventTypes.AGENT_RUN_COMPLETED));
        // 租户级事件刻意不接受（它没有 agent 维度，派发会打破隔离模型）
        assertFalse(plugin.eventTypes().contains(EventTypes.QUOTA_EXCEEDED));
    }

    // ---------------------------------------------------------------- ★ body 形状

    @Test
    @DisplayName("★ 飞书用 msg_type + content.text（写成钉钉那种形状它会静默丢弃）")
    void feishuBodyShape() {
        JsonNode body = JsonUtils.toJsonNode(
                BuiltinNotifierPlugin.buildBody(config("feishu"), "部署失败"));

        assertEquals("text", body.path("msg_type").asText(), "飞书是 msg_type（下划线）");
        assertEquals("部署失败", body.path("content").path("text").asText(), "正文在 content.text");
        assertTrue(body.path("msgtype").isMissingNode(), "飞书不该出现 msgtype");
    }

    @Test
    @DisplayName("★ 钉钉/企微用 msgtype + text.content（与飞书正好相反）")
    void dingtalkAndWecomBodyShape() {
        for (String channel : List.of("dingtalk", "wecom")) {
            JsonNode body = JsonUtils.toJsonNode(
                    BuiltinNotifierPlugin.buildBody(config(channel), "构建完成"));
            assertEquals("text", body.path("msgtype").asText(), channel + " 是 msgtype（无下划线）");
            assertEquals("构建完成", body.path("text").path("content").asText(),
                    channel + " 正文在 text.content");
        }
    }

    @Test
    @DisplayName("Slack 与通用 webhook 用最简单的 {text}")
    void slackAndGenericBodyShape() {
        for (String channel : List.of("slack", "generic")) {
            JsonNode body = JsonUtils.toJsonNode(
                    BuiltinNotifierPlugin.buildBody(config(channel), "你好"));
            assertEquals("你好", body.path("text").asText(), channel + " 用顶层 text");
        }
    }

    // ---------------------------------------------------------------- 默认开关

    @Test
    @DisplayName("默认：失败推送开、完成推送关（后者若默认开会把群刷屏）")
    void defaultSwitches() {
        NotifyConfig cfg = NotifyConfig.of(JsonUtils.toJsonNode("{\"webhookUrl\":\"https://x\"}"));

        assertTrue(cfg.notifyOnFailed(), "失败必须默认推送 —— 这正是本插件存在的理由");
        assertFalse(cfg.notifyOnCompleted(), "完成默认不推送：一场对话十几轮，每轮一条会刷屏");
        assertFalse(cfg.hasSecret());
    }

    @Test
    @DisplayName("未配 webhook 时不推送也不报错（挂件可能只想用它的工具）")
    void noWebhookIsSilent() {
        plugin.onAttach(ctx("agent-a", "{}"));
        // 没有 webhook 就什么都不该发生；关键是**不能抛异常**
        plugin.onEvent(event("agent-a", EventTypes.AGENT_RUN_FAILED, "boom"));
    }

    @Test
    @DisplayName("只关心失败时，完成事件不该触发推送")
    void completedEventSkippedWhenDisabled() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        String url = startServer(received);
        plugin.onAttach(ctx("agent-a",
                "{\"webhookUrl\":\"" + url + "\",\"channel\":\"generic\"}"));

        plugin.onEvent(event("agent-a", EventTypes.AGENT_RUN_COMPLETED, null));

        assertEquals(null, received.get(), "完成推送默认关，不该发出请求");
    }

    // ---------------------------------------------------------------- 真实 HTTP

    @Test
    @DisplayName("★ 失败事件会真的推到 webhook，且正文带上智能体名与错误")
    void failedEventIsPushed() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        String url = startServer(received);
        plugin.onAttach(ctx("agent-a",
                "{\"webhookUrl\":\"" + url + "\",\"channel\":\"generic\"}"));

        plugin.onEvent(event("agent-a", EventTypes.AGENT_RUN_FAILED, "连接超时"));

        assertNotNull(received.get(), "必须发出请求");
        String text = JsonUtils.toJsonNode(received.get()).path("text").asText();
        assertTrue(text.contains("失败"), "正文要说清发生了什么：" + text);
        assertTrue(text.contains("客服助手"), "正文要带智能体名：" + text);
        assertTrue(text.contains("连接超时"), "正文要带错误详情：" + text);
    }

    @Test
    @DisplayName("★ 上游报错时静默返回 false，绝不抛异常（通知不能拖垮主流程）")
    void upstreamErrorIsSwallowed() throws Exception {
        String url = startServer(exchange -> {
            byte[] msg = "{\"errcode\":40001,\"errmsg\":\"invalid webhook\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, msg.length);
            exchange.getResponseBody().write(msg);
        });
        plugin.onAttach(ctx("agent-a",
                "{\"webhookUrl\":\"" + url + "\",\"channel\":\"generic\"}"));

        // 不该抛
        plugin.onEvent(event("agent-a", EventTypes.AGENT_RUN_FAILED, "boom"));
    }

    @Test
    @DisplayName("工具：未配 webhook 时明确抛出可读提示，让模型知道去让用户配置")
    void toolWithoutWebhookGivesActionableError() {
        plugin.onAttach(ctx("agent-a", "{}"));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> plugin.provideTools().get(0).handler().execute(
                        JsonUtils.toJsonNode("{\"message\":\"你好\"}"),
                        ctx("agent-a", "{}")));

        assertTrue(ex.getMessage().contains("webhook"), "要说清缺什么：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("挂载配置"), "要指出去哪配：" + ex.getMessage());
    }

    // ---------------------------------------------------------------- 工具

    private NotifyConfig config(String channel) {
        return NotifyConfig.of(JsonUtils.toJsonNode(
                "{\"channel\":\"" + channel + "\",\"webhookUrl\":\"https://example.com/hook\"}"));
    }

    private PluginContext ctx(String agentId, String json) {
        return new PluginContext(agentId, "t1", JsonUtils.toJsonNode(json), null);
    }

    private DomainEvent event(String agentId, String type, String error) {
        Map<String, Object> payload = error == null
                ? DomainEvent.payload("agent_id", agentId, "agent_name", "客服助手", "model", "deepseek-chat")
                : DomainEvent.payload("agent_id", agentId, "agent_name", "客服助手",
                "model", "deepseek-chat", "error", error);
        return DomainEvent.ofAgent(type, "t1", agentId, "run-1", payload);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private String startServer(AtomicReference<String> sink) throws Exception {
        return startServer(exchange -> sink.set(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private String startServer(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.handle(exchange);
                if (exchange.getResponseCode() < 0) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().close();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }
}
