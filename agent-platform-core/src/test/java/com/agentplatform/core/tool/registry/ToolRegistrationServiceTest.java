package com.agentplatform.core.tool.registry;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.executor.HttpApiTool;
import com.agentplatform.model.entity.ToolRegistration;
import com.agentplatform.model.repository.ToolRegistrationRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTTP 工具注册持久化 + 天气工具验证。
 */
class ToolRegistrationServiceTest {

    /** 城市名应被 URL 编码后填入 endpoint 占位符（北京 → %E5%8C%97%E4%BA%AC）。 */
    @Test
    void cityIsUrlEncodedIntoEndpoint() throws Exception {
        AtomicReference<String> requestedUri = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedUri.set(exchange.getRequestURI().toString());
            byte[] body = "北京: ☀️ +23°C".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpApiTool tool = new HttpApiTool("weather", "查询天气", null, base + "/{city}?format=3", "GET");

            ObjectNode args = JsonUtils.mapper().createObjectNode();
            args.put("city", "北京");
            var result = tool.execute(args, null);

            assertTrue(result.success(), "工具应执行成功");
            assertTrue(requestedUri.get().contains("%E5%8C%97%E4%BA%AC"),
                    "城市名应做 UTF-8 URL 编码，实际 URI=" + requestedUri.get());
            assertTrue(requestedUri.get().contains("format=3"), "查询参数应保留");
            assertTrue(result.output().asText().contains("北京"), "应返回天气文本");
        } finally {
            server.stop(0);
        }
    }

    /** 注册工具应落库，重启（新 service 实例）后应能恢复。 */
    @Test
    void registeredToolIsPersistedAndRestored() {
        ToolRegistrationRepository repository = mock(ToolRegistrationRepository.class);
        when(repository.save(any(ToolRegistration.class)))
                .thenAnswer(inv -> inv.getArgument(0, ToolRegistration.class));
        when(repository.findByEnabledTrue()).thenReturn(List.of());
        when(repository.findByTenantIdAndToolName(any(), any())).thenReturn(Optional.empty());

        ToolRegistry registry = new ToolRegistry();
        ToolRegistrationService service = new ToolRegistrationService(registry);
        service.repository = repository;

        ObjectNode schema = JsonUtils.mapper().createObjectNode();
        service.register("default", "my_api", "我的接口", "https://example.com/{id}", "GET", schema);

        assertTrue(registry.contains("my_api"), "注册后应可用");
        verify(repository).save(any(ToolRegistration.class));

        // 模拟重启：全新注册中心 + 从库加载
        ToolRegistry freshRegistry = new ToolRegistry();
        ToolRegistration row = ToolRegistration.builder()
                .tenantId("default")
                .toolName("my_api")
                .description("我的接口")
                .endpoint("https://example.com/{id}")
                .method("GET")
                .enabled(true)
                .build();
        when(repository.findByEnabledTrue()).thenReturn(List.of(row));

        ToolRegistrationService restarted = new ToolRegistrationService(freshRegistry);
        restarted.repository = repository;
        restarted.loadAll();

        assertTrue(freshRegistry.contains("my_api"), "重启后应从数据库恢复工具");
        assertEquals("https://example.com/{id}", ((HttpApiTool) freshRegistry.get("my_api")).getEndpoint());
    }

    /** 内置天气工具应在启动补齐流程中自动注册。 */
    @Test
    void weatherToolIsSeededOnStartup() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistrationService service = new ToolRegistrationService(registry);
        service.repository = null;   // 无 DB 场景也要可用

        service.loadAll();

        assertTrue(registry.contains(ToolRegistrationService.WEATHER_TOOL), "应自动注册天气工具");
        HttpApiTool weather = (HttpApiTool) registry.get(ToolRegistrationService.WEATHER_TOOL);
        assertEquals("https://wttr.in/{city}?format=3", weather.getEndpoint());
        assertEquals("GET", weather.getMethod());
        assertNotNull(weather.getInputSchema(), "应带 city 入参 schema");
        assertTrue(weather.getInputSchema().toString().contains("city"));
    }
}
