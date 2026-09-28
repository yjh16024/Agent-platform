package com.agentplatform.core.plugin.builtin;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.plugin.builtin.BuiltinTtsPlugin.TtsConfig;
import com.agentplatform.plugin.sdk.HookContext;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.HookPoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuiltinTtsPlugin} 的测试。
 *
 * <h3>为什么必须测「真实 HTTP 那一跳」</h3>
 * 这个插件最容易坏的地方不是逻辑，而是<b>协议细节</b>：请求体里字段名叫 {@code input} 还是
 * {@code text}、{@code response_format} 的取值、音色串的格式、返回的是二进制还是 JSON。
 * 这些<b>全都不会让代码编译失败</b>，只会让用户点了半天没声音。
 *
 * <p>所以这里用 JDK 自带的 {@link HttpServer} 起一个真的本地 HTTP 服务当"厂商"，
 * 断言<b>发出去的请求体</b>与<b>拿回来的字节如何变成 data URI</b>。它不依赖外网、
 * 不需要新依赖，但覆盖了"协议写错了"这一类最难在本地发现的故障。</p>
 *
 * <p>另一组重点是<b>降级行为</b>：未配置密钥时产出占位音、合成失败时返回 null（不影响正文）、
 * 超长文本被截断 —— 这三条都是"出错时用户会怎么感知"的约定。</p>
 */
class BuiltinTtsPluginTest {

    private static final String WAV_PREFIX = "data:audio/wav;base64,";
    /** 一段 1x1 的假音频字节：内容不重要，重要的是"原样塞进 data URI"。 */
    private static final byte[] FAKE_MP3 = {0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00};

    private final BuiltinTtsPlugin plugin = new BuiltinTtsPlugin();

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
    @DisplayName("元信息：id / 钩子点 / 展示名（供市场页与 registrar 使用）")
    void metadata() {
        assertEquals("builtin_tts", plugin.id());
        assertEquals(HookPoint.after_llm, plugin.point(),
                "必须是 after_llm —— audio_url 只能经附加产物通道传出");
        assertNotNull(plugin.name());
        assertNotNull(plugin.description());
    }

    // ---------------------------------------------------------------- 未配置密钥：占位音

    @Test
    @DisplayName("未配置密钥：仍产出音频（占位音）并带 mock 标记，让「插件生效了」可见")
    void withoutApiKeyFallsBackToMock() {
        attach("agent-a", "{}");

        Object result = plugin.invoke(hook("agent-a", "你好"));

        assertInstanceOf(Map.class, result, "after_llm 必须返回 Map 才会被当作附加产物");
        Map<?, ?> extras = (Map<?, ?>) result;
        assertEquals(Boolean.TRUE, extras.get("mock"), "占位音必须带 mock 标记");
        assertTrue(String.valueOf(extras.get("audio_url")).startsWith(WAV_PREFIX));
        assertNotNull(extras.get("tts_hint"), "必须告诉用户「去填 Key」，否则他不知道该怎么办");
    }

    @Test
    @DisplayName("占位音是真正可解析的 WAV（交给 JDK 音频解析器验证，而不是只看前缀）")
    void mockAudioIsDecodableWav() throws Exception {
        attach("agent-a", "{}");
        String uri = (String) ((Map<?, ?>) plugin.invoke(hook("agent-a", "你好"))).get("audio_url");

        byte[] wav = Base64.getDecoder().decode(uri.substring(WAV_PREFIX.length()));

        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat format = in.getFormat();
            assertEquals(8000f, format.getSampleRate());
            assertEquals(1, format.getChannels());
            assertTrue(in.getFrameLength() > 0, "必须有实际音频帧，不能是个空壳 WAV");
        }
    }

    @Test
    @DisplayName("空文本不合成（工具轮里模型只发工具调用，没有正文可念）")
    void blankInputProducesNothing() {
        attach("agent-a", "{}");
        assertNull(plugin.invoke(hook("agent-a", "   ")));
        assertNull(plugin.invoke(new HookContext(null, "agent-a", "r1", null)));
    }

    // ---------------------------------------------------------------- 真实 HTTP 调用

    @Test
    @DisplayName("★ 配了密钥：按 OpenAI 兼容协议发出请求，并把返回的音频字节转成 data URI")
    void synthesizesViaHttp() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();

        String baseUrl = startFakeVendor(exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, FAKE_MP3.length);
            exchange.getResponseBody().write(FAKE_MP3);
        });

        attach("agent-a", """
                {"apiKey":"sk-test-123","baseUrl":"%s","model":"FunAudioLLM/CosyVoice2-0.5B",
                 "voice":"FunAudioLLM/CosyVoice2-0.5B:alex","format":"mp3","speed":1.0}
                """.formatted(baseUrl));

        Map<?, ?> extras = (Map<?, ?>) plugin.invoke(hook("agent-a", "你好，世界"));

        // ① 端点与鉴权头
        assertEquals("/v1/audio/speech", receivedPath.get(), "必须打 OpenAI 兼容端点");
        assertEquals("Bearer sk-test-123", receivedAuth.get());

        // ② 请求体字段名 —— 写错任何一个是"编译能过、用户没声音"的经典故障
        JsonNode body = JsonUtils.toJsonNode(receivedBody.get());
        assertEquals("你好，世界", body.path("input").asText(), "文本字段必须叫 input");
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", body.path("model").asText());
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:alex", body.path("voice").asText());
        assertEquals("mp3", body.path("response_format").asText(), "格式字段必须叫 response_format");

        // ③ 响应字节 -> data URI（含 MIME 映射：mp3 的标准 MIME 是 audio/mpeg，写成 audio/mp3 浏览器不播）
        String uri = String.valueOf(extras.get("audio_url"));
        assertTrue(uri.startsWith("data:audio/mpeg;base64,"), "实际收到的是：" + uri.substring(0, Math.min(40, uri.length())));
        assertArrayEqualsBase64(FAKE_MP3, uri.substring("data:audio/mpeg;base64,".length()));

        // ④ 成功时不该出现 mock 标记（否则前端会把真实语音当占位音）
        assertNull(extras.get("mock"));
        assertEquals("mp3", extras.get("format"));
        assertEquals(5, extras.get("chars"));
    }

    @Test
    @DisplayName("★ 厂商报错：返回 null 而不是抛异常（语音是附加产物，绝不能拖垮整轮对话）")
    void vendorErrorDoesNotBreakTheRun() throws Exception {
        String baseUrl = startFakeVendor(exchange -> {
            byte[] msg = "{\"code\":30001,\"message\":\"balance is insufficient\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, msg.length);
            exchange.getResponseBody().write(msg);
        });
        attach("agent-a", "{\"apiKey\":\"sk-bad\",\"baseUrl\":\"%s\"}".formatted(baseUrl));

        assertNull(plugin.invoke(hook("agent-a", "你好")), "失败必须静默降级为「本轮无音频」");
    }

    @Test
    @DisplayName("厂商返回 200 但 0 字节：同样视为失败（避免前端拿到播不出来的空音频）")
    void emptyAudioIsTreatedAsFailure() throws Exception {
        String baseUrl = startFakeVendor(exchange -> exchange.sendResponseHeaders(200, -1));
        attach("agent-a", "{\"apiKey\":\"sk-x\",\"baseUrl\":\"%s\"}".formatted(baseUrl));

        assertNull(plugin.invoke(hook("agent-a", "你好")));
    }

    @Test
    @DisplayName("超过 maxChars 的文本被截断，并在 extras 里说明（用户能解释「为什么只念了前半段」）")
    void longTextIsTruncated() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        String baseUrl = startFakeVendor(exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, FAKE_MP3.length);
            exchange.getResponseBody().write(FAKE_MP3);
        });
        attach("agent-a", "{\"apiKey\":\"sk-x\",\"baseUrl\":\"%s\",\"maxChars\":5}".formatted(baseUrl));

        Map<?, ?> extras = (Map<?, ?>) plugin.invoke(hook("agent-a", "一二三四五六七八九十"));

        assertEquals("一二三四五", JsonUtils.toJsonNode(receivedBody.get()).path("input").asText());
        assertEquals(Boolean.TRUE, extras.get("truncated"));
        assertEquals(10, extras.get("full_chars"));
    }

    // ---------------------------------------------------------------- 配置隔离与解析

    @Test
    @DisplayName("★ 同一插件挂到两个智能体：各用各的密钥，互不覆盖（单例字段缓存的经典坑）")
    void configIsIsolatedPerAgent() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        String baseUrl = startFakeVendor(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, FAKE_MP3.length);
            exchange.getResponseBody().write(FAKE_MP3);
        });

        attach("agent-a", "{\"apiKey\":\"sk-key-of-A\",\"baseUrl\":\"%s\"}".formatted(baseUrl));
        attach("agent-b", "{\"apiKey\":\"sk-key-of-B\",\"baseUrl\":\"%s\"}".formatted(baseUrl));

        plugin.invoke(hook("agent-b", "来自 B 的文本"));
        assertEquals("Bearer sk-key-of-B", auth.get(), "B 必须用 B 自己的密钥");

        plugin.invoke(hook("agent-a", "来自 A 的文本"));
        assertEquals("Bearer sk-key-of-A", auth.get(), "A 不能被 B 的配置覆盖");
    }

    @Test
    @DisplayName("未挂载过的智能体：回落到默认配置（不抛异常）")
    void unknownAgentUsesDefaults() {
        attach("agent-a", "{\"apiKey\":\"sk-x\"}");
        // 从未 attach 过 agent-ghost：不得 NPE
        Object result = plugin.invoke(hook("agent-ghost", "你好"));
        assertNotNull(result);
    }

    @Test
    @DisplayName("卸载后配置被清理")
    void detachClearsConfig() {
        attach("agent-a", "{\"apiKey\":\"sk-x\"}");
        plugin.onDetach(new PluginContext("agent-a", "t1", null, null));
        // 已卸载：回落到默认（无密钥 → 占位音）
        Map<?, ?> extras = (Map<?, ?>) plugin.invoke(hook("agent-a", "你好"));
        assertEquals(Boolean.TRUE, extras.get("mock"));
    }

    @Test
    @DisplayName("baseUrl 拼接容错：裸域名 / 带 v1 / 带尾斜杠 / 完整端点，都应得到同一个 speech 端点")
    void speechUrlTolerance() {
        String expected = "https://api.example.com/v1/audio/speech";
        assertEquals(expected, config("https://api.example.com").speechUrl());
        assertEquals(expected, config("https://api.example.com/").speechUrl());
        assertEquals(expected, config("https://api.example.com/v1").speechUrl());
        assertEquals(expected, config("https://api.example.com/v1/").speechUrl());
        assertEquals(expected, config("https://api.example.com/v1/audio/speech").speechUrl());
    }

    @Test
    @DisplayName("非法配置值被夹到安全区间（避免用户填 0 或负数导致请求异常）")
    void configValuesAreClamped() {
        TtsConfig cfg = TtsConfig.of(JsonUtils.toJsonNode(
                "{\"maxChars\":0,\"timeoutMs\":1,\"speed\":99}"));

        assertTrue(cfg.maxChars() >= 1, "maxChars 不能是 0 —— 否则永远合成不出东西");
        assertTrue(cfg.timeoutMs() >= 500, "超时不能小到必然失败");
        assertTrue(cfg.speed() <= 4.0, "语速必须夹在厂商允许范围内");
    }

    @Test
    @DisplayName("未配置时使用默认厂商（硅基流动）与默认音色")
    void defaultsPointToSiliconFlow() {
        TtsConfig cfg = TtsConfig.of(JsonUtils.toJsonNode("{}"));

        assertFalse(cfg.hasApiKey());
        assertEquals("https://api.siliconflow.cn/v1/audio/speech", cfg.speechUrl());
        assertTrue(cfg.model().contains("CosyVoice"), "默认模型应为 CosyVoice2");
    }

    @Test
    @DisplayName("配置为 null（老绑定没有 config 列）时不抛异常")
    void nullConfigIsSafe() {
        TtsConfig cfg = TtsConfig.of((JsonNode) null);
        assertFalse(cfg.hasApiKey());
        assertNotNull(cfg.speechUrl());
    }

    // ---------------------------------------------------------------- 工具

    private void attach(String agentId, String configJson) {
        plugin.onAttach(new PluginContext(agentId, "t1", JsonUtils.toJsonNode(configJson), null));
    }

    private HookContext hook(String agentId, String input) {
        return new HookContext(input, agentId, "run-1", null);
    }

    private TtsConfig config(String baseUrl) {
        return TtsConfig.of(JsonUtils.toJsonNode("{\"baseUrl\":\"" + baseUrl + "\"}"));
    }

    /** 假厂商的处理器。刻意不用 {@code Consumer} —— 它的 {@code accept} 不声明异常，而 HttpExchange 的操作会抛 IOException。 */
    @FunctionalInterface
    private interface VendorHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    /** 起一个本地假厂商，返回可用的 baseUrl（不含 /v1，由插件自己拼）。 */
    private String startFakeVendor(VendorHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/speech", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            } catch (Exception e) {
                // 处理器写错了就让请求以 500 结束，测试自然会红 —— 不静默吞掉
                throw new RuntimeException(e);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void assertArrayEqualsBase64(byte[] expected, String actualBase64) {
        byte[] actual = Base64.getDecoder().decode(actualBase64);
        assertEquals(expected.length, actual.length, "音频字节数应与厂商返回一致");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "第 " + i + " 个字节不一致");
        }
    }
}
