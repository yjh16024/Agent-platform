package com.agentplatform.core.plugin.builtin;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.plugin.builtin.BuiltinImageGenPlugin.ImageConfig;
import com.agentplatform.plugin.sdk.PluginContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuiltinImageGenPlugin} 的测试。
 *
 * <h3>这里守的两件事</h3>
 * <ol>
 *   <li><b>图片必须以「链接」形式回到对话里</b>。平台的 {@code after_llm} 附加产物前端只渲染
 *       {@code audioUrl}，所以走"返回 URL、让模型写进正文"这条路是当前唯一能让用户看到图的办法。
 *       这一点要是被改成返回 base64，用户就会看到几百 KB 的乱码塞进对话。</li>
 *   <li><b>未配密钥要说清"缺什么、去哪配"</b>，而不是静默失败 —— 否则用户会以为工具坏了。</li>
 * </ol>
 */
class BuiltinImageGenPluginTest {

    private final BuiltinImageGenPlugin plugin = new BuiltinImageGenPlugin();

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
    @DisplayName("元信息：贡献 generate_image 工具，schema 里有 prompt 与 size")
    void metadata() {
        assertEquals("builtin_image_gen", plugin.id());
        assertNotNull(plugin.name());
        assertEquals(1, plugin.provideTools().size());

        var tool = plugin.provideTools().get(0);
        assertEquals("generate_image", tool.name());
        assertNotNull(tool.inputSchema());
        assertTrue(tool.inputSchema().path("properties").has("prompt"));
        assertTrue(tool.inputSchema().path("properties").has("size"));
        assertTrue(tool.inputSchema().path("required").toString().contains("prompt"));
    }

    @Test
    @DisplayName("端点拼接容错：裸域名 / 带 v1 / 带尾斜杠 / 完整端点")
    void imagesUrlTolerance() {
        String expected = "https://api.example.com/v1/images/generations";
        for (String base : List.of("https://api.example.com", "https://api.example.com/",
                "https://api.example.com/v1", "https://api.example.com/v1/",
                "https://api.example.com/v1/images/generations")) {
            assertEquals(expected,
                    ImageConfig.of(JsonUtils.toJsonNode("{\"baseUrl\":\"" + base + "\"}")).imagesUrl(),
                    "填法 " + base + " 应解析成同一端点");
        }
    }

    @Test
    @DisplayName("默认指向硅基流动 Kolors（用户若已配了硅基流动的密钥可直接复用）")
    void defaultsPointToSiliconFlow() {
        ImageConfig cfg = ImageConfig.of(JsonUtils.toJsonNode("{}"));

        assertFalse(cfg.hasApiKey());
        assertTrue(cfg.imagesUrl().contains("siliconflow"), cfg.imagesUrl());
        assertTrue(cfg.model().contains("Kolors"), cfg.model());
    }

    @Test
    @DisplayName("超时被夹到下限（生成很慢，但也不能小到必然失败）")
    void timeoutClamped() {
        ImageConfig cfg = ImageConfig.of(JsonUtils.toJsonNode("{\"timeoutMs\":1}"));
        assertTrue(cfg.timeoutMs() >= 1000);
    }

    // ---------------------------------------------------------------- 未配置

    @Test
    @DisplayName("★ 未配密钥：明确说清缺什么、去哪配（而不是让模型以为是自己参数错了）")
    void missingKeyGivesActionableError() {
        plugin.onAttach(ctx("agent-a", "{}"));

        var ex = assertThrows(IllegalStateException.class,
                () -> plugin.provideTools().get(0).handler()
                        .execute(JsonUtils.toJsonNode("{\"prompt\":\"一只猫\"}"), ctx("agent-a", "{}")));

        assertTrue(ex.getMessage().contains("API Key"), ex.getMessage());
        assertTrue(ex.getMessage().contains("挂载配置"), "要指出去哪配：" + ex.getMessage());
    }

    @Test
    @DisplayName("prompt 为空时抛出可读异常")
    void emptyPromptThrows() {
        plugin.onAttach(ctx("agent-a", "{\"apiKey\":\"k\"}"));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> plugin.provideTools().get(0).handler()
                        .execute(JsonUtils.toJsonNode("{}"), ctx("agent-a", "{}")));

        assertTrue(ex.getMessage().contains("prompt"));
    }

    // ---------------------------------------------------------------- ★ 真实 HTTP

    @Test
    @DisplayName("★ 成功：返回图片链接，并明确要求模型把链接放进回答")
    void successReturnsUrl() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        String base = startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"data\":[{\"url\":\"https://cdn.example/img/abc.png\"}]}");
        });

        plugin.onAttach(ctx("agent-a",
                "{\"apiKey\":\"sk-img\",\"baseUrl\":\"" + base + "\",\"model\":\"Kolors\"}"));

        JsonNode result = plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"prompt\":\"一只坐在窗台的橘猫\",\"size\":\"768x1024\"}"),
                        ctx("agent-a", "{}"));

        // 端点与鉴权
        assertEquals("/v1/images/generations", path.get());
        assertEquals("Bearer sk-img", auth.get());

        // 请求体字段名（写错就是"编译能过、运行没图"）
        JsonNode sent = JsonUtils.toJsonNode(body.get());
        assertEquals("Kolors", sent.path("model").asText());
        assertEquals("一只坐在窗台的橘猫", sent.path("prompt").asText());
        assertEquals("768x1024", sent.path("size").asText(), "模型给的 size 应覆盖默认值");
        assertEquals(1, sent.path("n").asInt());
        assertEquals("url", sent.path("response_format").asText(),
                "必须要 URL —— base64 会把几百 KB 灌进对话，模型用不上还占满窗口");

        // 返回值：链接可点，且带上"请放进回答"的指令
        String text = result.asText();
        assertTrue(text.contains("https://cdn.example/img/abc.png"), text);
        assertTrue(text.contains("链接"), "要提示模型把链接放出来：" + text);
    }

    @Test
    @DisplayName("未给 size 时用配置里的默认值")
    void defaultSizeUsed() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String base = startServer(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"data\":[{\"url\":\"https://cdn.example/a.png\"}]}");
        });
        plugin.onAttach(ctx("agent-a",
                "{\"apiKey\":\"k\",\"baseUrl\":\"" + base + "\",\"size\":\"512x512\"}"));

        plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"prompt\":\"x\"}"), ctx("agent-a", "{}"));

        assertEquals("512x512", JsonUtils.toJsonNode(body.get()).path("size").asText());
    }

    @Test
    @DisplayName("★ b64_json：识别但明确说「不支持」——比返回一堆乱码或空结果有用得多")
    void inlineBase64IsExplicitlyRejected() throws Exception {
        String base = startServer(exchange -> respond(exchange,
                "{\"data\":[{\"b64_json\":\"iVBORw0KGgoAAAANSUhEUg\"}]}"));
        plugin.onAttach(ctx("agent-a", "{\"apiKey\":\"k\",\"baseUrl\":\"" + base + "\"}"));

        var ex = assertThrows(IllegalStateException.class,
                () -> plugin.provideTools().get(0).handler()
                        .execute(JsonUtils.toJsonNode("{\"prompt\":\"x\"}"), ctx("agent-a", "{}")));

        assertTrue(ex.getMessage().contains("内联图片") || ex.getMessage().contains("b64_json"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("Kolors"), "要给出可用的替代：" + ex.getMessage());
    }

    @Test
    @DisplayName("上游报错：带上状态码与响应片段，便于用户判断是密钥问题还是服务商问题")
    void upstreamErrorIsReadable() throws Exception {
        String base = startServer(exchange -> {
            byte[] msg = "{\"message\":\"insufficient balance\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, msg.length);
            exchange.getResponseBody().write(msg);
        });
        plugin.onAttach(ctx("agent-a", "{\"apiKey\":\"k\",\"baseUrl\":\"" + base + "\"}"));

        var ex = assertThrows(IllegalStateException.class,
                () -> plugin.provideTools().get(0).handler()
                        .execute(JsonUtils.toJsonNode("{\"prompt\":\"x\"}"), ctx("agent-a", "{}")));

        assertTrue(ex.getMessage().contains("402"), ex.getMessage());
        assertTrue(ex.getMessage().contains("insufficient balance"), "要带上上游原话：" + ex.getMessage());
    }

    @Test
    @DisplayName("★ 配置按 agentId 隔离")
    void configIsolatedPerAgent() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        String base = startServer(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{\"data\":[{\"url\":\"https://c/a.png\"}]}");
        });

        plugin.onAttach(ctx("agent-a", "{\"apiKey\":\"keyA\",\"baseUrl\":\"" + base + "\"}"));
        plugin.onAttach(ctx("agent-b", "{\"apiKey\":\"keyB\",\"baseUrl\":\"" + base + "\"}"));

        plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"prompt\":\"x\"}"), ctx("agent-b", "{}"));
        assertEquals("Bearer keyB", auth.get());

        plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"prompt\":\"x\"}"), ctx("agent-a", "{}"));
        assertEquals("Bearer keyA", auth.get());
    }

    // ---------------------------------------------------------------- 工具

    private PluginContext ctx(String agentId, String json) {
        return new PluginContext(agentId, "t1", JsonUtils.toJsonNode(json), null);
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

    private static void respond(HttpExchange exchange, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
