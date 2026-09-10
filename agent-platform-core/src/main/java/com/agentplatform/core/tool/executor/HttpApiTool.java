package com.agentplatform.core.tool.executor;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import tools.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * 解析响应：优先按 JSON 解析（多数天气 / 数据 API 返回 JSON），失败时
     * 降级为纯文本节点，避免模型只看到"解析错误"而丢失原始内容。
     */
    private JsonNode parseResponse(String body) {
        if (body == null) {
            return tools.jackson.databind.node.StringNode.valueOf("");
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return tools.jackson.databind.node.StringNode.valueOf("");
        }
        // 启发式：仅当像 JSON 时才尝试解析（避免误把 HTML 喂给 Jackson）
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[') {
            try {
                return JsonUtils.toJsonNode(trimmed);
            } catch (Exception e) {
                log.debug("HTTP tool {} response is not strict JSON, fallback to text", name);
            }
        }
        return tools.jackson.databind.node.StringNode.valueOf(trimmed);
    }

    /** 匹配 endpoint 中的 {@code {param}} 占位符。 */
    private static final Pattern URL_PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            String url = buildUrl(args);
            Request.Builder builder = new Request.Builder().url(url);
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
                return ToolResult.ok(parseResponse(respBody));
            }
        } catch (Exception e) {
            log.warn("HTTP tool {} failed: {}", name, e.getMessage());
            return ToolResult.fail("HTTP tool error: " + e.getMessage());
        }
    }

    /**
     * 按 args 解析最终请求 URL：支持 endpoint 中的 {@code {param}} 占位符替换。
     * <p>
     * 例：endpoint={@code https://wttr.in/{city}} + args={{"city":"北京"}}
     * → {@code https://wttr.in/%E5%8C%97%E4%BA%AC}（值做 URL 编码，支持中文）。
     * 无占位符时返回原 endpoint；占位符缺参时报错并提示缺哪些参数。
     * </p>
     */
    private String buildUrl(JsonNode args) {
        if (endpoint == null || !endpoint.contains("{")) {
            return endpoint;
        }
        Matcher matcher = URL_PLACEHOLDER.matcher(endpoint);
        StringBuilder sb = new StringBuilder();
        StringBuilder missing = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = argValue(args, key);
            if (value == null) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement("{" + key + "}"));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(encode(value)));
            }
        }
        matcher.appendTail(sb);
        if (missing.length() > 0) {
            throw new IllegalArgumentException("缺少必需参数（URL 占位符未填）：" + missing);
        }
        return sb.toString();
    }

    /** 取参数值文本：字符串 / 数字 / 布尔 / null 均支持。 */
    private String argValue(JsonNode args, String key) {
        if (args == null || !args.has(key) || args.path(key).isNull()) {
            return null;
        }
        JsonNode v = args.get(key);
        return v.isTextual() ? v.asText() : v.toString();
    }

    /** URL 编码（空格转 %20，兼容 UTF-8 中文）。 */
    private String encode(String value) {
        String enc = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return enc.replace("+", "%20");
    }
}