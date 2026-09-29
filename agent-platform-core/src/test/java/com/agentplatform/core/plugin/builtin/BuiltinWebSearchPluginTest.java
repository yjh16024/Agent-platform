package com.agentplatform.core.plugin.builtin;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.plugin.builtin.BuiltinWebSearchPlugin.SearchConfig;
import com.agentplatform.plugin.sdk.PluginContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuiltinWebSearchPlugin} 的测试。
 *
 * <h3>两条最值得钉住的行为</h3>
 * <ol>
 *   <li><b>未配密钥也能用</b>。这是"用户会不会真的开这个工具"的决定因素 ——
 *       如果必须先注册账号才有任何反应，绝大多数人不会走到第二步。</li>
 *   <li><b>配了密钥要按 Tavily 协议发请求</b>。字段名写错（把 {@code max_results}
 *       写成 {@code maxResults}）编译期完全看不出来，运行时只是"没结果" —— 典型的难查故障。
 *       所以这里对着本地 mock 服务断言真实的请求体。</li>
 * </ol>
 */
class BuiltinWebSearchPluginTest {

    private final BuiltinWebSearchPlugin plugin = new BuiltinWebSearchPlugin();

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
    @DisplayName("元信息：贡献 web_search 工具，且带完整 JSON Schema")
    void metadata() {
        assertEquals("builtin_web_search", plugin.id());
        assertNotNull(plugin.name());
        // web_search + search_status（后者供界面角标显示"配没配密钥"）
        assertEquals(2, plugin.provideTools().size());

        var tool = plugin.provideTools().get(0);
        assertEquals("web_search", tool.name());
        assertNotNull(tool.inputSchema(), "schema 为 null 时 LLM 不知道该怎么填参数");
        assertEquals("object", tool.inputSchema().path("type").asText());
        assertEquals("string", tool.inputSchema().path("properties").path("query").path("type").asText());
        assertTrue(tool.inputSchema().path("required").toString().contains("query"));
    }

    // ---------------------------------------------------------------- 界面贡献

    @Test
    @DisplayName("★ 界面贡献：侧栏角标 + dataSource 指向的工具必须真的存在")
    void uiContributionShape() {
        var ui = plugin.provideUi();
        assertEquals(1, ui.size());

        var def = ui.get(0);
        assertEquals("badge", def.type());
        assertEquals("sidebar.footer.action", def.slot());
        assertEquals("搜索", def.label());
        assertNull(def.action(), "状态角标不可点击 —— 点了没有可做的事，不如安静待着");

        // ★ 这条是本模块的核心约定：dataSource/action 引用的工具必须在同一个插件里声明过，
        // 否则后端（PluginService.listUiContributions）会把它整条丢弃，表现为"元素根本不出现"。
        assertNotNull(def.dataSource());
        String tool = def.dataSource().tool();
        assertTrue(plugin.provideTools().stream().anyMatch(t -> t.name().equals(tool)),
                "dataSource 引用的工具 " + tool + " 必须出现在 provideTools() 里");
    }

    @Test
    @DisplayName("状态工具返回可读短文本（界面直接显示它，不是给机器解析的 JSON）")
    void statusToolReturnsShortText() {
        plugin.onAttach(ctx("agent-a", "{}"));
        var statusTool = plugin.provideTools().stream()
                .filter(t -> BuiltinWebSearchPlugin.STATUS_TOOL_NAME.equals(t.name()))
                .findFirst().orElseThrow();

        String none = statusTool.handler().execute(null, ctx("agent-a", "{}")).asText();
        assertEquals("未配密钥", none);

        plugin.onAttach(ctx("agent-b", "{\"apiKey\":\"tvly-x\"}"));
        String configured = statusTool.handler().execute(null, ctx("agent-b", "{}")).asText();
        assertEquals("已配密钥", configured);
    }

    @Test
    @DisplayName("★ 没配密钥自动走 DuckDuckGo 兜底（零配置可用）")
    void autoFallsBackToDuckDuckGo() {
        SearchConfig cfg = SearchConfig.of(JsonUtils.toJsonNode("{}"));

        assertFalse(cfg.hasApiKey());
        assertEquals("duckduckgo", cfg.effectiveProvider());
    }

    @Test
    @DisplayName("★ 配了密钥自动切到 Tavily（用户不必理解 provider 这个概念）")
    void autoPrefersTavilyWhenKeyPresent() {
        SearchConfig cfg = SearchConfig.of(JsonUtils.toJsonNode("{\"apiKey\":\"tvly-xxx\"}"));

        assertTrue(cfg.hasApiKey());
        assertEquals("tavily", cfg.effectiveProvider());
    }

    @Test
    @DisplayName("显式指定的 provider 优先于自动判断")
    void explicitProviderWins() {
        SearchConfig cfg = SearchConfig.of(JsonUtils.toJsonNode(
                "{\"apiKey\":\"tvly-xxx\",\"provider\":\"duckduckgo\"}"));

        assertEquals("duckduckgo", cfg.effectiveProvider());
    }

    @Test
    @DisplayName("非法配置被夹到安全区间")
    void configClamped() {
        SearchConfig cfg = SearchConfig.of(JsonUtils.toJsonNode("{\"maxResults\":0,\"timeoutMs\":1}"));

        assertTrue(cfg.maxResults() >= 1, "maxResults 为 0 会永远查不到东西");
        assertTrue(cfg.timeoutMs() >= 500, "超时不能小到必然失败");
    }

    @Test
    @DisplayName("端点拼接容错：裸域名 / 带尾斜杠 / 完整端点都解析成同一地址")
    void tavilyUrlTolerance() {
        assertNotNull(SearchConfig.of(JsonUtils.toJsonNode("{}")).tavilyUrl());
        assertTrue(SearchConfig.of(JsonUtils.toJsonNode("{}")).tavilyUrl().startsWith("https://api.tavily.com"));

        for (String base : List.of("https://x.example", "https://x.example/",
                "https://x.example/search")) {
            assertEquals("https://x.example/search",
                    SearchConfig.of(JsonUtils.toJsonNode("{\"baseUrl\":\"" + base + "\"}")).tavilyUrl(),
                    "填法 " + base + " 应解析成同一端点");
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Test
    @DisplayName("★ 渲染成「标题/链接/摘要」三段式，并要求模型引用真实链接")
    void renderShape() {
        String text = BuiltinWebSearchPlugin.render("今天天气", "tavily",
                List.of(Map.of("title", "天气网", "url", "https://example.com/a", "snippet", "晴 25 度")),
                false);

        assertTrue(text.contains("天气网"));
        assertTrue(text.contains("https://example.com/a"), "链接必须原样保留，否则用户没法核对");
        assertTrue(text.contains("晴 25 度"));
        assertTrue(text.contains("引用"), "要提示模型引用真实链接，而不是自己编一个");
        assertFalse(text.contains("覆盖范围有限"), "配了密钥时不该出现兜底提示");
    }

    @Test
    @DisplayName("★ 兜底后端的结果必须标注「覆盖范围有限」——否则会被当成搜索坏了")
    void renderMarksLimited() {
        String text = BuiltinWebSearchPlugin.render("某冷门问题", "duckduckgo",
                List.of(Map.of("title", "t", "url", "https://x", "snippet", "s")),
                true);

        assertTrue(text.contains("duckduckgo"), "要说清实际用的哪个后端");
        assertTrue(text.contains("覆盖范围有限"), "要主动说明结果不丰富的原因与出路");
    }

    // ---------------------------------------------------------------- ★ 真实 HTTP

    @Test
    @DisplayName("★ 配了密钥：按 Tavily 协议发请求（字段名写错编译能过、运行却无结果）")
    void tavilyRequestShape() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        String base = startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"results":[
                      {"title":"标题一","url":"https://a.example/1","content":"摘要一"},
                      {"title":"标题二","url":"https://a.example/2","content":"摘要二"}
                    ]}
                    """);
        });

        plugin.onAttach(ctx("agent-a",
                "{\"apiKey\":\"tvly-test\",\"baseUrl\":\"" + base + "\",\"maxResults\":3}"));

        JsonNode result = plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"query\":\"人工智能\"}"), ctx("agent-a", "{}"));

        // ① 打了正确的端点
        assertEquals("/search", path.get());

        // ② 请求体字段名 —— 写错任何一个是"编译能过、运行无结果"的经典故障
        JsonNode sent = JsonUtils.toJsonNode(body.get());
        assertEquals("tvly-test", sent.path("api_key").asText(), "Tavily 用 api_key 而非 apiKey");
        assertEquals("人工智能", sent.path("query").asText());
        assertEquals(3, sent.path("max_results").asInt(), "Tavily 用 max_results 而非 maxResults");

        // ③ 结果被渲染成模型好用的形状
        String text = result.asText();
        assertTrue(text.contains("标题一") && text.contains("https://a.example/1"), text);
        assertTrue(text.contains("标题二"), text);
    }

    @Test
    @DisplayName("★ 上游报错时抛出可读异常（宿主会转成 ToolResult.fail 让模型换词重试）")
    void upstreamErrorIsReadable() throws Exception {
        String base = startServer(exchange -> {
            byte[] msg = "{\"detail\":\"invalid api key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, msg.length);
            exchange.getResponseBody().write(msg);
        });
        plugin.onAttach(ctx("agent-a", "{\"apiKey\":\"bad\",\"baseUrl\":\"" + base + "\"}"));

        var ex = assertThrows(IllegalStateException.class,
                () -> plugin.provideTools().get(0).handler()
                        .execute(JsonUtils.toJsonNode("{\"query\":\"x\"}"), ctx("agent-a", "{}")));

        assertTrue(ex.getMessage().contains("401"), "要带上上游状态码：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("重试"), "要给模型一个出路：" + ex.getMessage());
    }

    // ---------------------------------------------------------------- DuckDuckGo 解析

    @Test
    @DisplayName("★ DDG 兜底：AbstractText 与 RelatedTopics 都要用上，并标注覆盖有限")
    void duckDuckGoParsing() {
        String text = BuiltinWebSearchPlugin.parseDuckDuckGo(JsonUtils.toJsonNode("""
                {"Heading":"某词条","AbstractText":"这是词条摘要","AbstractURL":"https://ddg.example/1",
                 "RelatedTopics":[{"Text":"相关主题一","FirstURL":"https://ddg.example/2"},
                                   {"Text":"相关主题二","FirstURL":"https://ddg.example/3"}]}
                """), "某词条", 5);

        assertTrue(text.contains("这是词条摘要"), text);
        assertTrue(text.contains("https://ddg.example/1"), text);
        assertTrue(text.contains("相关主题一"), "RelatedTopics 也要用上 —— 兜底结果本来就少");
        assertTrue(text.contains("覆盖范围有限"), "必须让模型与用户都知道结果不丰富的原因");
    }

    @Test
    @DisplayName("★ DDG 完全没结果时给出「怎么办」而不是空白")
    void duckDuckGoEmptyGivesGuidance() {
        String text = BuiltinWebSearchPlugin.parseDuckDuckGo(
                JsonUtils.toJsonNode("{}"), "冷门问题", 5);

        assertTrue(text.contains("没查到"), text);
        assertTrue(text.contains("Tavily"), "要指出出路：去配一个真正的搜索密钥");
        assertTrue(text.contains("挂载配置"), "要指出去哪配");
    }

    @Test
    @DisplayName("maxResults 对 RelatedTopics 生效（不配会一次塞几十条进上下文）")
    void relatedTopicsRespectsMaxResults() {
        StringBuilder topics = new StringBuilder("[");
        for (int i = 0; i < 30; i++) {
            if (i > 0) {
                topics.append(',');
            }
            topics.append("{\"Text\":\"主题").append(i).append("\",\"FirstURL\":\"https://d/").append(i).append("\"}");
        }
        topics.append(']');

        String text = BuiltinWebSearchPlugin.parseDuckDuckGo(
                JsonUtils.toJsonNode("{\"RelatedTopics\":" + topics + "}"), "q", 3);

        assertTrue(text.contains("得到 3 条结果"), "应被截到 3 条：" + text.substring(0, 40));
        assertFalse(text.contains("主题9"), "第 9 条不该出现");
    }

    // ---------------------------------------------------------------- 参数与隔离

    @Test
    @DisplayName("参数缺失时抛出可读异常")
    void missingQueryThrows() {
        plugin.onAttach(ctx("agent-a", "{}"));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> plugin.provideTools().get(0).handler()
                        .execute(JsonUtils.toJsonNode("{}"), ctx("agent-a", "{}")));

        assertTrue(ex.getMessage().contains("query"));
    }

    @Test
    @DisplayName("中文关键词按 UTF-8 编码（不编码会 400，症状是「查什么都查不到」）")
    void chineseQueryIsEncoded() {
        String encoded = URLEncoder.encode("人工智能 最新进展", StandardCharsets.UTF_8);

        assertTrue(encoded.contains("%"), "中文必须被百分号编码：" + encoded);
        assertEquals("人工智能 最新进展",
                URLDecoder.decode(encoded, StandardCharsets.UTF_8), "编解码必须可逆");
    }

    @Test
    @DisplayName("★ 配置按 agentId 隔离：A 的密钥不会被 B 用上")
    void configIsolatedPerAgent() throws Exception {
        AtomicReference<String> authBody = new AtomicReference<>();
        String base = startServer(exchange -> {
            authBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"results\":[{\"title\":\"t\",\"url\":\"https://u\",\"content\":\"c\"}]}");
        });

        plugin.onAttach(ctx("agent-a", "{\"apiKey\":\"key-of-A\",\"baseUrl\":\"" + base + "\"}"));
        plugin.onAttach(ctx("agent-b", "{\"apiKey\":\"key-of-B\",\"baseUrl\":\"" + base + "\"}"));

        plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"query\":\"q\"}"), ctx("agent-b", "{}"));
        assertEquals("key-of-B", JsonUtils.toJsonNode(authBody.get()).path("api_key").asText(),
                "B 必须用 B 自己的密钥");

        plugin.provideTools().get(0).handler()
                .execute(JsonUtils.toJsonNode("{\"query\":\"q\"}"), ctx("agent-a", "{}"));
        assertEquals("key-of-A", JsonUtils.toJsonNode(authBody.get()).path("api_key").asText(),
                "A 不能被 B 覆盖");
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
