package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 节点执行器（画布「HTTP」节点）。
 * <p>
 * 配置项：
 * <ul>
 *   <li>{@code url} —— 请求地址，支持 {@code ${var}}（必填）</li>
 *   <li>{@code method} —— GET / POST / PUT / DELETE，默认 GET</li>
 *   <li>{@code headers} —— Map 或 JSON 字符串，值支持 {@code ${var}}</li>
 *   <li>{@code body} —— 请求体文本，支持 {@code ${var}}（GET 时忽略）</li>
 * </ul>
 * 输出：{@code {status, body}}；网络异常直接抛出（由工作流执行入口透出）。
 * </p>
 */
@Slf4j
@Component
public class HttpNodeExecutor implements NodeExecutor {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    /** 响应体截断上限，避免把超大响应灌进上下文。 */
    private static final int MAX_BODY_CHARS = 20_000;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(60))
            .build();

    @Override
    public NodeType type() {
        return NodeType.Http;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        String rawUrl = NodeConfigs.str(node, "url", "endpoint");
        if (NodeConfigs.blank(rawUrl)) {
            throw new IllegalArgumentException("Http node " + node.id() + " requires config.url");
        }
        String url = ctx.resolveString(rawUrl);
        String method = NodeConfigs.strOr(node, "GET", "method").toUpperCase();

        Request.Builder builder = new Request.Builder().url(url);
        NodeConfigs.map(node, "headers").forEach((k, v) -> {
            String value = v instanceof String s ? ctx.resolveString(s) : String.valueOf(v);
            builder.header(k, value);
        });

        RequestBody body = null;
        if (!"GET".equals(method) && !"DELETE".equals(method)) {
            String rawBody = NodeConfigs.strOr(node, "", "body", "payload");
            String payload = rawBody.isEmpty() ? "" : ctx.resolveString(rawBody);
            body = RequestBody.create(payload, JSON);
        } else if ("DELETE".equals(method)) {
            body = RequestBody.create("", JSON);
        }
        builder.method(method, body);

        try (Response response = client.newCall(builder.build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            String trimmed = text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) + "…(truncated)" : text;
            log.debug("Http node {} {} -> {}", node.id(), method, response.code());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", response.code());
            out.put("body", trimmed);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Http node " + node.id() + " failed: " + e.getMessage(), e);
        }
    }
}
