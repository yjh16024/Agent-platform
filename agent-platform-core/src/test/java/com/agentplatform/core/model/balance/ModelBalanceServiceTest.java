package com.agentplatform.core.model.balance;

import com.agentplatform.core.model.config.ModelConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型账户额度查询验证：用本地 HTTP 服务模拟各厂商余额接口，
 * 确认解析正确、不支持厂商有明确说明、异常被转成结构化结果（不抛异常）。
 * <p>不依赖真实 Key 与外网。</p>
 */
class ModelBalanceServiceTest {

    private static final String DEEPSEEK_BODY = """
            {
              "is_available": true,
              "balance_infos": [
                {"currency": "CNY", "total_balance": "110.00",
                 "granted_balance": "10.00", "topped_up_balance": "100.00"}
              ]
            }
            """;

    private static final String MOONSHOT_BODY = """
            {
              "code": 0,
              "data": {"available_balance": 49.58894, "voucher_balance": 46.58893, "cash_balance": 3.00001},
              "scode": "0x0",
              "status": true
            }
            """;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 起一个本地 HTTP 服务，返回固定响应。 */
    private String startServer(String path, String body, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ModelBalanceService service(ModelConfigService.ChatBinding chat,
                                        ModelConfigService.EmbeddingBinding embedding) {
        ModelConfigService cfg = mock(ModelConfigService.class);
        when(cfg.getChat()).thenReturn(chat);
        when(cfg.getEmbedding()).thenReturn(embedding);
        return new ModelBalanceService(cfg);
    }

    /** DeepSeek：解析 total_balance / 赠金 / 充值，并验证 baseUrl 结尾的 /v1 被正确剥离。 */
    @Test
    void deepseekParsesBalanceAndStripsV1FromBaseUrl() throws Exception {
        String base = startServer("/user/balance", DEEPSEEK_BODY, 200);
        ModelBalanceService svc = service(
                new ModelConfigService.ChatBinding("deepseek", "deepseek-chat", base + "/v1", "sk-test"),
                ModelConfigService.EmbeddingBinding.empty());

        List<ModelBalanceView> all = svc.queryAll();

        ModelBalanceView chat = all.get(0);
        assertTrue(chat.ok(), "应查询成功，实际 message=" + chat.message());
        assertEquals("CNY", chat.currency());
        assertEquals("110.00", chat.available());
        assertTrue(chat.items().stream().anyMatch(i -> "充值余额".equals(i.label()) && "100.00".equals(i.value())));
        assertTrue(chat.items().stream().anyMatch(i -> "赠金余额".equals(i.label()) && "10.00".equals(i.value())));
        assertEquals(base + "/user/balance", chat.baseUrl(), "DeepSeek 应请求 /user/balance（剥离 /v1）");

        assertFalse(all.get(1).configured(), "嵌入模型未配置时应标记 configured=false");
    }

    /** Moonshot：解析 data.available_balance，货币默认 CNY。 */
    @Test
    void moonshotParsesAvailableBalance() throws Exception {
        String base = startServer("/v1/users/me/balance", MOONSHOT_BODY, 200);
        ModelBalanceService svc = service(
                new ModelConfigService.ChatBinding("moonshot", "moonshot-v1-8k", base + "/v1", "sk-test"),
                ModelConfigService.EmbeddingBinding.empty());

        ModelBalanceView chat = svc.queryAll().get(0);

        assertTrue(chat.ok(), "应查询成功，实际 message=" + chat.message());
        assertEquals("CNY", chat.currency());
        assertEquals("49.58894", chat.available());
        assertTrue(chat.items().stream().anyMatch(i -> "代金券余额".equals(i.label())));
    }

    /** 不支持余额的厂商：明确回 supported=false + 原因，不冒充查询成功。 */
    @Test
    void unsupportedVendorReturnsExplicitReason() {
        ModelBalanceService svc = service(
                new ModelConfigService.ChatBinding("openai", "gpt-4o-mini", "https://api.openai.com/v1", "sk-test"),
                ModelConfigService.EmbeddingBinding.empty());

        ModelBalanceView chat = svc.queryAll().get(0);

        assertTrue(chat.configured());
        assertFalse(chat.supported(), "OpenAI 应标记为不支持余额查询");
        assertFalse(chat.ok());
        assertNotNull(chat.message());
        assertTrue(chat.message().contains("未开放余额查询接口"), "实际=" + chat.message());
    }

    /** 未配置 Key：提示先填 Key，而不是报网络错误。 */
    @Test
    void missingKeyIsReportedClearly() {
        ModelBalanceService svc = service(
                new ModelConfigService.ChatBinding("deepseek", "deepseek-chat", null, null),
                ModelConfigService.EmbeddingBinding.empty());

        ModelBalanceView chat = svc.queryAll().get(0);

        assertFalse(chat.ok());
        assertTrue(chat.message().contains("API Key"), "实际=" + chat.message());
    }

    /** 端点不可达：转成 ok=false，绝不抛异常拖垮接口。 */
    @Test
    void unreachableEndpointFailsGracefully() {
        ModelBalanceService svc = service(
                new ModelConfigService.ChatBinding("deepseek", "deepseek-chat", "http://127.0.0.1:1/v1", "sk-test"),
                ModelConfigService.EmbeddingBinding.empty());

        ModelBalanceView chat = svc.queryAll().get(0);

        assertFalse(chat.ok());
        assertTrue(chat.message().startsWith("查询失败："), "实际=" + chat.message());
    }

    /** 401：错误信息里带状态码，便于用户判断 Key 是否失效。 */
    @Test
    void unauthorizedShowsHttpStatus() throws Exception {
        String base = startServer("/user/balance", "{\"error\":\"invalid key\"}", 401);
        ModelBalanceService svc = service(
                new ModelConfigService.ChatBinding("deepseek", "deepseek-chat", base, "sk-bad"),
                ModelConfigService.EmbeddingBinding.empty());

        ModelBalanceView chat = svc.queryAll().get(0);

        assertFalse(chat.ok());
        assertTrue(chat.message().contains("401"), "实际=" + chat.message());
    }
}
