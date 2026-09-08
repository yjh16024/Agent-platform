package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 远程 MCP 客户端（Streamable HTTP / JSON-RPC 传输）。
 * <p>
 * 覆盖把外部 MCP Server 的工具接入本平台工具注册中心所需的三个 JSON-RPC 方法：
 * {@code initialize}（握手）、{@code tools/list}（发现）、{@code tools/call}（执行）。
 * 响应兼容「单次 JSON 响应」与「SSE 封装（data: {...}）」两种形态，认证头由注册时传入。
 * </p>
 * <p><b>局限</b>：面向单请求-响应服务；需要服务端回调（会话保持 / 长连接推送）的完整
 * SSE 会话不在 MVP 范围。</p>
 */
@Slf4j
public class HttpMcpClient implements McpClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final String serverUrl;
    private final Map<String, String> headers;
    private final OkHttpClient httpClient;
    private final AtomicLong idSeq = new AtomicLong(1);

    public HttpMcpClient(String serverUrl, Map<String, String> headers) {
        this(serverUrl, headers, null);
    }

    public HttpMcpClient(String serverUrl, Map<String, String> headers, OkHttpClient httpClient) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw BizException.badRequest("MCP serverUrl 不能为空");
        }
        this.serverUrl = serverUrl.trim();
        this.headers = headers == null ? Map.of() : headers;
        this.httpClient = httpClient == null ? new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(30))
                .build() : httpClient;
    }

    @Override
    public String transport() {
        return "http";
    }

    @Override
    public List<McpToolSpec> listTools() {
        call("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agent-platform", "version", "1.0.0")));
        JsonNode result = call("tools/list", Map.of());
        List<McpToolSpec> tools = new ArrayList<>();
        JsonNode arr = result.path("tools");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                JsonNode schema = t.has("inputSchema") ? t.get("inputSchema") : t.get("input_schema");
                if (schema != null && schema.isNull()) {
                    schema = null;
                }
                tools.add(new McpToolSpec(
                        t.path("name").asText(),
                        t.path("description").asText(""),
                        schema));
            }
        }
        return tools;
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        JsonNode result = call("tools/call",
                Map.of("name", toolName, "arguments", arguments == null ? Map.of() : arguments));
        StringBuilder sb = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
        }
        boolean isError = result.path("isError").asBoolean(false);
        String text = sb.toString();
        if (isError) {
            throw BizException.internal("MCP 工具 " + toolName + " 执行失败: "
                    + (text.isEmpty() ? result.toString() : text));
        }
        return text;
    }

    /** 发起一次 JSON-RPC 调用并解析 result。 */
    private JsonNode call(String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", idSeq.getAndIncrement());
        payload.put("method", method);
        payload.put("params", params);

        Request.Builder builder = new Request.Builder()
                .url(serverUrl)
                .post(RequestBody.create(JsonUtils.toJson(payload), JSON))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        headers.forEach(builder::header);

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw BizException.internal("MCP 请求失败 HTTP " + response.code() + ": " + readBody(response));
            }
            String raw = readBody(response);
            JsonNode node = parseResponse(raw);
            if (node.has("error") && !node.path("error").isNull()) {
                JsonNode err = node.path("error");
                throw BizException.internal("MCP 方法 " + method + " 返回错误: "
                        + err.path("message").asText(err.toString()));
            }
            if (!node.has("result")) {
                throw BizException.internal("MCP 响应缺少 result 字段: " + raw);
            }
            return node.path("result");
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw BizException.internal("MCP 网络调用失败: " + e.getMessage(), e);
        }
    }

    /** 先试整体 JSON；失败则按 SSE 帧（data: {...}）解析。 */
    private JsonNode parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.internal("MCP 返回空响应");
        }
        try {
            return JsonUtils.toJsonNode(raw);
        } catch (Exception ignored) {
            // 继续尝试 SSE 解析
        }
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("data:")) {
                String data = t.substring(5).trim();
                if (!data.isEmpty() && !"[DONE]".equals(data)) {
                    try {
                        return JsonUtils.toJsonNode(data);
                    } catch (Exception ignored) {
                        // 找下一个 data 帧
                    }
                }
            }
        }
        throw BizException.internal("无法解析 MCP 响应: " + (raw.length() > 500 ? raw.substring(0, 500) : raw));
    }

    private String readBody(Response response) {
        try {
            if (response.body() != null) {
                return response.body().string();
            }
        } catch (IOException ignored) {
            // 网络异常在 call 中统一处理
        }
        return response.message();
    }
}
