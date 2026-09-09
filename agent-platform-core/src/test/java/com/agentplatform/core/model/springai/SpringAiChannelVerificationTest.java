package com.agentplatform.core.model.springai;

import com.agentplatform.core.model.adapter.MockModelAdapter;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.adapter.OpenAiCompatibleAdapter;
import com.agentplatform.core.model.factory.ModelProviderFactory;
import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring AI 通道「真跑」验证：
 * <ol>
 *   <li>真实 Spring 容器下开关装配是否正确（开→Spring AI 适配器，关→原自研适配器）；</li>
 *   <li>经 ModelRouter 打本地 HTTP 服务，验证同步调用端到端；</li>
 *   <li>验证流式 SSE 解析；</li>
 *   <li>验证原生 tool-role 工具循环（工具真被执行）。</li>
 * </ol>
 * 不依赖真实 Key 与外网，可在 CI 稳定运行。
 */
class SpringAiChannelVerificationTest {

    // ---------- 1. 开关装配（真实 Spring 容器） ----------

    @Test
    void whenEnabled_thenSpringAiAdapterIsUsed() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "agent-platform.springai.enabled=true",
                        "spring.ai.openai.base-url=http://127.0.0.1:9",
                        "spring.ai.openai.api-key=sk-test",
                        "agent-platform.model.user-agent=test-ua")
                .withUserConfiguration(SpringAiChatModelFactory.class, SpringAiToolBridge.class,
                        ModelProviderFactory.class)
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null, "容器应启动成功");
                    assertTrue(ctx.getBeansOfType(SpringAiChatModelFactory.class).size() == 1,
                            "启用时应存在 Spring Ai 模型工厂");
                    ModelProviderFactory factory = ctx.getBean(ModelProviderFactory.class);
                    assertTrue(factory.get("deepseek") instanceof SpringAiModelAdapter,
                            "deepseek 应走 Spring AI 适配器");
                    assertTrue(factory.get("anthropic") instanceof SpringAiModelAdapter,
                            "anthropic 应走 Spring AI 适配器");
                    assertTrue(factory.get("local") instanceof MockModelAdapter,
                            "local 仍走 Mock（Spring AI 不覆盖本地兜底）");
                });
    }

    @Test
    void whenDisabled_thenLegacyAdapterIsUsed() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "agent-platform.springai.enabled=false",
                        "spring.ai.openai.base-url=http://127.0.0.1:9",
                        "spring.ai.openai.api-key=sk-test")
                .withUserConfiguration(SpringAiChatModelFactory.class, SpringAiToolBridge.class,
                        ModelProviderFactory.class)
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null, "容器应启动成功");
                    assertTrue(ctx.getBeansOfType(SpringAiChatModelFactory.class).isEmpty(),
                            "未启用时不应存在 Spring Ai 模型工厂");
                    ModelProviderFactory factory = ctx.getBean(ModelProviderFactory.class);
                    assertTrue(factory.get("deepseek") instanceof OpenAiCompatibleAdapter,
                            "未启用时应回到原自研适配器");
                });
    }

    // ---------- 2. 同步调用端到端（经 ModelRouter） ----------

    @Test
    void chatThroughRouterHitsHttpServer() throws Exception {
        try (TestServer server = TestServer.json(OPENAI_RESPONSE)) {
            ModelProviderFactory mpf = new ModelProviderFactory(server.baseUrl(), "sk-test");
            mpf.setSpringAiChatModelFactory(new SpringAiChatModelFactory(null));
            ModelRouter router = new ModelRouter(mpf);

            ModelAdapter.ChatResponse resp = router.chat("deepseek", new ModelAdapter.ChatRequest(
                    "deepseek-chat", "你是助手", "你好", 0.7, 200, Map.of(), List.of(),
                    server.baseUrl(), "sk-test"));

            assertEquals("hello from spring ai", resp.content());
            assertEquals(11, resp.promptTokens());
            assertEquals(7, resp.completionTokens());
            assertEquals(1, server.requestCount(), "应恰好发起一次 HTTP 调用");
        }
    }

    // ---------- 3. 流式 SSE ----------

    @Test
    void streamParsesSseDeltas() throws Exception {
        try (TestServer server = TestServer.sse(List.of("hello", " world"))) {
            SpringAiChatModelFactory springAi = new SpringAiChatModelFactory(null);
            SpringAiModelAdapter adapter =
                    new SpringAiModelAdapter("openai", springAi, server.baseUrl(), "sk-test");

            List<String> chunks = new ArrayList<>();
            adapter.stream(new ModelAdapter.ChatRequest("deepseek-chat", null, "hi", 0.7, 100, Map.of()))
                    .doOnNext(d -> {
                        if (d.text() != null && !d.text().isEmpty()) {
                            chunks.add(d.text());
                        }
                    })
                    .blockLast();

            assertEquals("hello world", String.join("", chunks), "SSE 增量应能拼接成完整文本");
        }
    }

    // ---------- 4. 原生 tool-role 工具循环 ----------

    @Test
    void toolLoopExecutesToolThenReturnsFinalAnswer() throws Exception {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回显输入";
            }

            @Override
            public JsonNode inputSchema() {
                return null;
            }

            @Override
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                executed.set(true);
                return ToolResult.ok("echoed:" + args.path("text").asText(""));
            }
        });
        ToolExecutor executor = new ToolExecutor(registry, List.of());
        SpringAiToolBridge bridge = new SpringAiToolBridge(registry, executor);

        // 首轮返回 tool_calls，第二轮（请求里带 tool 消息）返回最终答案
        TestServer.Handler handler = body -> body.contains("\"role\":\"tool\"")
                ? FINAL_RESPONSE
                : TOOL_CALL_RESPONSE;

        try (TestServer server = TestServer.handler(handler)) {
            SpringAiModelAdapter adapter = new SpringAiModelAdapter(
                    "openai", new SpringAiChatModelFactory(null), server.baseUrl(), "sk-test", bridge);

            ModelAdapter.ChatResponse resp = adapter.chat(new ModelAdapter.ChatRequest(
                    "deepseek-chat", null, "请调用 echo", 0.7, 200, Map.of(), List.of(),
                    server.baseUrl(), "sk-test",
                    List.of(new ModelAdapter.ToolSpec("echo", "回显输入", null)), "auto"));

            assertTrue(executed.get(), "工具应被真实执行");
            assertEquals("工具调用完成", resp.content());
            assertEquals(2, server.requestCount(), "应恰好两轮：请求工具 → 回灌结果");
        }
    }

    // ---------- 5. 视觉通道（图片以 data URL 发送） ----------

    @Test
    void visionImageIsSentAsDataUrl() throws Exception {
        AtomicReference<String> lastBody = new AtomicReference<>();
        TestServer.Handler capture = body -> {
            lastBody.set(body);
            return OPENAI_RESPONSE;
        };

        try (TestServer server = TestServer.handler(capture)) {
            SpringAiModelAdapter adapter = new SpringAiModelAdapter(
                    "openai", new SpringAiChatModelFactory(null), server.baseUrl(), "sk-test");

            java.util.HashMap<String, Object> image = new java.util.HashMap<>();
            image.put("mimeType", "image/png");
            image.put("base64", "aGVsbG8tdmlzaW9u");
            Map<String, Object> extra = new java.util.HashMap<>();
            extra.put("images", List.of(image));

            adapter.chat(new ModelAdapter.ChatRequest(
                    "gpt-4o-mini", null, "这张图里有什么？", 0.7, 100, extra, List.of(),
                    server.baseUrl(), "sk-test"));

            assertTrue(lastBody.get().contains("data:image/png;base64,aGVsbG8tdmlzaW9u"),
                    "图片应以 data URL 随请求发送，实际 body=" + abbreviate(lastBody.get()));
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : (s.length() > 400 ? s.substring(0, 400) + "…" : s);
    }

    // ==================== 测试数据与工具 ====================

    private static final String OPENAI_RESPONSE = """
            {
              "id": "chatcmpl-1", "object": "chat.completion", "created": 1700000000, "model": "deepseek-chat",
              "choices": [{"index": 0, "finish_reason": "stop",
                           "message": {"role": "assistant", "content": "hello from spring ai"}}],
              "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
            }
            """;

    private static final String TOOL_CALL_RESPONSE = """
            {
              "id": "chatcmpl-2", "object": "chat.completion", "created": 1700000000, "model": "deepseek-chat",
              "choices": [{"index": 0, "finish_reason": "tool_calls",
                           "message": {"role": "assistant", "content": "",
                             "tool_calls": [{"id": "call_1", "type": "function",
                               "function": {"name": "echo", "arguments": "{\\"text\\":\\"hi\\"}"}}]}}],
              "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}
            }
            """;

    private static final String FINAL_RESPONSE = """
            {
              "id": "chatcmpl-3", "object": "chat.completion", "created": 1700000000, "model": "deepseek-chat",
              "choices": [{"index": 0, "finish_reason": "stop",
                           "message": {"role": "assistant", "content": "工具调用完成"}}],
              "usage": {"prompt_tokens": 9, "completion_tokens": 4, "total_tokens": 13}
            }
            """;

    /** 极简本地 HTTP 服务，模拟 OpenAI 兼容端点（同步 JSON 或 SSE）。 */
    static final class TestServer implements AutoCloseable {

        interface Handler {
            String response(String requestBody);
        }

        private final HttpServer server;
        private int requests = 0;

        private TestServer(Handler handler, boolean sse) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    requests++;
                    respond(exchange, handler.response(readBody(exchange)), sse);
                });
                server.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static TestServer json(String body) {
            return new TestServer(b -> body, false);
        }

        static TestServer handler(Handler h) {
            return new TestServer(h, false);
        }

        static TestServer sse(List<String> deltas) {
            return new TestServer(b -> {
                StringBuilder sb = new StringBuilder();
                for (String d : deltas) {
                    sb.append("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,")
                            .append("\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"")
                            .append(d).append("\"},\"finish_reason\":null}]}\n\n");
                }
                sb.append("data: [DONE]\n\n");
                return sb.toString();
            }, true);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int requestCount() {
            return requests;
        }

        private static String readBody(HttpExchange exchange) {
            try (InputStream in = exchange.getRequestBody();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        private static void respond(HttpExchange exchange, String body, boolean sse) {
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type",
                        sse ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception ignored) {
                // 测试环境忽略写回异常
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
