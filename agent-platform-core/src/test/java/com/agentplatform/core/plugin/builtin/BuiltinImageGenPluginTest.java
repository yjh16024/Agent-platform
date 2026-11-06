package com.agentplatform.core.plugin.builtin;

import com.agentplatform.framework.api.Tool;

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
    @DisplayName("★ 未配密钥：走免费通道（Pollinations），而不是直接报错（2026-10-22 改）")
    void missingKeyFallsBackToFreeChannel() {
        plugin.onAttach(ctx("agent-a", "{}"));

        var out = plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"prompt\":\"一只猫\",\"size\":\"512x768\"}"), ctx("agent-a", "{}"));

        String text = out.asText();
        /*
         * 以前这里抛 "尚未配置 API Key" —— 等于没配 key 就用不了生图。
         * 现在缺密钥自动降级到免费通道，至少能用（质量差一些）。
         */
        assertTrue(text.contains("image.pollinations.ai"), "应给出免费通道地址：" + text);
        // 尺寸要传进去，否则用户设的尺寸被忽略
        assertTrue(text.contains("width=512") && text.contains("height=768"), "尺寸没生效：" + text);
    }

    @Test
    @DisplayName("未配密钥时的提示要说明\"配上 Key 可以更好\"")
    void freeChannelMentionsUpgrade() {
        plugin.onAttach(ctx("agent-a", "{}"));

        var out = plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"prompt\":\"一只猫\"}"), ctx("agent-a", "{}"));

        assertTrue(out.asText().contains("API Key"), "应提示可以去配 Key：" + out.asText());
    }

    @Test
    @DisplayName("★ 自动识别厂商：地址或模型指向百炼 → dashscope；无 Key → pollinations")
    void resolveProviderAuto() {
        // ① 只填了百炼的 Key，没改地址/模型 —— 最常见的踩坑场景
        ImageConfig onlyKey = ImageConfig.of(JsonUtils.toJsonNode("{\"apiKey\":\"sk-bailian\"}"));
        assertEquals("openai", onlyKey.resolveProvider(), "只有 Key 时按 OpenAI 兼容处理");

        // ② 地址写了百炼 —— 走 compatible-mode（/api/v1 那套只支持异步，很多账号没开通）
        ImageConfig byUrl = ImageConfig.of(JsonUtils.toJsonNode(
                "{\"apiKey\":\"sk-1\",\"baseUrl\":\"https://ws-xxx.cn-beijing.maas.aliyuncs.com/apps/anthropic\"}"));
        assertEquals("openai", byUrl.resolveProvider());
        // 没写 compatible-mode 时自动补上
        assertEquals("https://ws-xxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/images/generations",
                byUrl.imagesUrl(byUrl.effectiveBaseUrl("openai")));

        // ③ 模型名是 wanx
        ImageConfig byModel = ImageConfig.of(JsonUtils.toJsonNode(
                "{\"apiKey\":\"sk-1\",\"model\":\"wanx2.1-t2i-turbo\"}"));
        assertEquals("openai", byModel.resolveProvider());

        // ④ 没有 Key → 免费通道（不再报错）
        ImageConfig noKey = ImageConfig.of(JsonUtils.toJsonNode("{}"));
        assertEquals("pollinations", noKey.resolveProvider());

        // ⑤ 显式指定优先于自动判断
        ImageConfig explicit = ImageConfig.of(JsonUtils.toJsonNode(
                "{\"apiKey\":\"sk-1\",\"provider\":\"dashscope\"}"));
        assertEquals("dashscope", explicit.resolveProvider());
    }

    @Test
    @DisplayName("★ 百炼 OpenAI 兼容模式（compatible-mode）：要判成 openai，走同步 /v1/images/generations")
    void compatibleModeUsesOpenAiProtocol() {
        ImageConfig cfg = ImageConfig.of(JsonUtils.toJsonNode(
                "{\"apiKey\":\"sk-1\","
                        + "\"baseUrl\":\"https://ws-xxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1\","
                        + "\"model\":\"qwen-image-2.1-turbo\"}"));

        // 地址里同时有 aliyuncs，但 compatible-mode 必须优先 —— 否则会拼成 /api/v1/... 报 url error
        assertEquals("openai", cfg.resolveProvider());
        // 拼出来的端点就是阿里云文档里写的那个
        assertEquals("https://ws-xxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/images/generations",
                cfg.imagesUrl());
    }

    @Test
    @DisplayName("百炼协议：用户没改默认值时，地址与模型会被纠正到阿里云")
    void dashScopeDefaultsAreCorrected() {
        ImageConfig cfg = ImageConfig.of(JsonUtils.toJsonNode(
                "{\"apiKey\":\"sk-1\",\"provider\":\"dashscope\"}"));

        // 默认 baseUrl 是硅基流动、默认模型是 Kolors —— 对百炼都是错的，
        // 不纠正就会拿百炼的 Key 去请求硅基流动，直接 401
        assertEquals("https://dashscope.aliyuncs.com", cfg.effectiveBaseUrl("dashscope"));
        assertEquals("wanx2.1-t2i-turbo", cfg.effectiveModel("dashscope"));
    }

    @Test
    @DisplayName("★ 百炼地址：只保留 origin，丢掉任意路径后缀（用户实测踩坑）")
    void dashScopeBaseKeepsOriginOnly() {
        // 用户填了 Claude 兼容端点，拼出来变成 .../apps/anthropic/api/v1/... → 404 "Not support"
        assertEquals("https://ws-t3qovzma6049f7vv.cn-beijing.maas.aliyuncs.com",
                BuiltinImageGenPlugin.dashScopeBase(
                        "https://ws-t3qovzma6049f7vv.cn-beijing.maas.aliyuncs.com/apps/anthropic"));

        // 另一种常见错误填法：兼容模式路径
        assertEquals("https://dashscope.aliyuncs.com",
                BuiltinImageGenPlugin.dashScopeBase("https://dashscope.aliyuncs.com/compatible-mode/v1"));

        // 干净地址原样保留
        assertEquals("https://dashscope.aliyuncs.com",
                BuiltinImageGenPlugin.dashScopeBase("https://dashscope.aliyuncs.com"));

        // 末尾斜杠
        assertEquals("https://dashscope.aliyuncs.com",
                BuiltinImageGenPlugin.dashScopeBase("https://dashscope.aliyuncs.com/"));

        // 没填 → 默认
        assertEquals("https://dashscope.aliyuncs.com", BuiltinImageGenPlugin.dashScopeBase(null));
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
