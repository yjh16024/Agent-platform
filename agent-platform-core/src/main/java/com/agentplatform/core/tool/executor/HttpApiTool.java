package com.agentplatform.core.tool.executor;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.time.Duration;

/**
 * 自定义 HTTP API 工具（动态注册）。
 * <p>将外部 HTTP API 封装为可被 LLM function calling 触发的工具。
 * 统一定义 name/description/inputSchema + endpoint，注册到 ToolRegistry 即用。</p>
 */
@Slf4j
@Getter
public class HttpApiTool implements Tool {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    private final String name;
    private final String description;
    private final JsonNode inputSchema;
    private final String endpoint;
    private final String method;

    public HttpApiTool(String name, String description, JsonNode inputSchema, String endpoint, String method) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.endpoint = endpoint;
        this.method = method == null ? "POST" : method.toUpperCase();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            Request.Builder builder = new Request.Builder().url(endpoint);
            RequestBody body = RequestBody.create(args == null ? "{}" : args.toString(), JSON);
            if ("GET".equals(method)) {
                builder.get();
            } else {
                builder.method(method, body);
            }
            try (Response response = HTTP.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) {
                    return ToolResult.fail("HTTP " + response.code() + ": " + response.message());
                }
                String respBody = response.body() == null ? "" : response.body().string();
                return ToolResult.ok(JsonUtils.toJsonNode(respBody));
            }
        } catch (Exception e) {
            log.warn("HTTP tool {} failed: {}", name, e.getMessage());
            return ToolResult.fail("HTTP tool error: " + e.getMessage());
        }
    }
}