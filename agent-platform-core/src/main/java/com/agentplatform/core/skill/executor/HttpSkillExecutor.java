package com.agentplatform.core.skill.executor;

import com.agentplatform.common.util.JsonUtils;
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
 * HTTP 执行器：把 Skill 作为远程服务调用（webhook / 微服务形态）。
 * <p>
 * 端点取自 {@code args.endpoint} 或 {@code command}（须为 http/https），
 * 以 POST + JSON 提交 {skill_id, skill, command, args}，2xx 视为成功、响应体作为输出。
 * </p>
 */
@Slf4j
@Component
public class HttpSkillExecutor implements SkillExecutor {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String type() {
        return "http";
    }

    @Override
    public String description() {
        return "调用 Skill 关联的 HTTP 端点（POST JSON：skill/command/args）";
    }

    @Override
    public boolean supports(SkillCommand command) {
        if (command == null) {
            return false;
        }
        if (command.type() != null && type().equalsIgnoreCase(command.type())) {
            return true;
        }
        String endpoint = endpointOf(command);
        return endpoint != null && (endpoint.startsWith("http://") || endpoint.startsWith("https://"));
    }

    @Override
    public SkillExecutionResult execute(SkillCommand command) {
        long t0 = System.currentTimeMillis();
        String endpoint = endpointOf(command);
        if (endpoint == null) {
            return SkillExecutionResult.fail(
                    "缺少 HTTP 端点（args.endpoint 或 command 传 http(s)://...）", type(),
                    System.currentTimeMillis() - t0);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skill_id", command.skillId());
        payload.put("skill", command.skillName());
        payload.put("command", command.command());
        payload.put("args", command.args() == null ? Map.of() : command.args());
        try (Response resp = httpClient.newCall(new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(JsonUtils.toJson(payload), JSON))
                .build()).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                return SkillExecutionResult.fail("HTTP " + resp.code() + ": " + body, type(),
                        System.currentTimeMillis() - t0);
            }
            return SkillExecutionResult.ok(body, type(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("[skills] http executor failed: {}", e.getMessage());
            return SkillExecutionResult.fail(e.getMessage(), type(), System.currentTimeMillis() - t0);
        }
    }

    private String endpointOf(SkillCommand command) {
        Map<String, Object> args = command.args();
        if (args != null && args.get("endpoint") != null) {
            return String.valueOf(args.get("endpoint"));
        }
        String c = command.command();
        if (c != null && (c.startsWith("http://") || c.startsWith("https://"))) {
            return c;
        }
        return null;
    }
}
