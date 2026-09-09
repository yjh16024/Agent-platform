package com.agentplatform.core.model.springai;

import com.agentplatform.core.model.adapter.ModelAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring AI 通道端到端验证：用本地 HTTP 服务模拟 OpenAI 兼容响应，
 * 确认「Spring AI ChatModel → 项目 ModelAdapter 契约」的解析与映射正确。
 * <p>不依赖真实 Key 与外网，可在 CI 中稳定运行。</p>
 */
class SpringAiModelAdapterTest {

    private static final String OPENAI_RESPONSE = """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "deepseek-chat",
              "choices": [
                {"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "hello from spring ai"}}
              ],
              "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
            }
            """;

    @Test
    void chatParsesOpenAiCompatibleResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = OPENAI_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            SpringAiChatModelFactory factory = new SpringAiChatModelFactory(null);
            SpringAiModelAdapter adapter =
                    new SpringAiModelAdapter("openai", factory, baseUrl, "sk-test-key");

            ModelAdapter.ChatRequest req = new ModelAdapter.ChatRequest(
                    "deepseek-chat", "你是助手", "你好", 0.7, 256, Map.of());

            ModelAdapter.ChatResponse resp = adapter.chat(req);

            assertEquals("hello from spring ai", resp.content());
            assertEquals(11, resp.promptTokens());
            assertEquals(7, resp.completionTokens());
            assertTrue(resp.latencyMs() >= 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatIsCachedPerCredential() {
        SpringAiChatModelFactory factory = new SpringAiChatModelFactory(null);
        var first = factory.chatModel("deepseek", "http://127.0.0.1:1", "sk-a");
        var second = factory.chatModel("deepseek", "http://127.0.0.1:1", "sk-a");
        var third = factory.chatModel("deepseek", "http://127.0.0.1:1", "sk-b");
        assertEquals(first, second, "相同凭证应复用同一 ChatModel 实例");
        assertTrue(first != third, "不同凭证应构建不同实例");
    }
}
