package com.agentplatform.core.observability.export;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogEventSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loki 日志导出器（阶段 B：LogService 同一份事件 → Loki）。
 * <p>
 * 使用 Loki 原生 JSON push 接口 {@code POST {url}/loki/api/v1/push}，零额外依赖
 * （复用已有 OkHttp + Jackson）。攒批异步发送：容量由 {@code queue-capacity} 控制，
 * 每 {@code flush-interval-ms} 或攒满 {@code batch-size} 推送一次。
 * </p>
 * <p>
 * 标签保持低基数：{@code service_name/tenant/level/category}；高基数信息
 * （trace_id / run_id / agent_id / message / context）放日志行 JSON 内，
 * 供 Grafana LogQL json 解析与 derivedFields 联动 Trace。推送失败仅告警、丢弃，
 * 绝不影响采集主流程。
 * </p>
 */
@Slf4j
@Component
public class LokiLogExporter implements LogEventSink {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String SERVICE_NAME = "agent-platform-core";

    private final ObsExportProperties properties;
    private final ObjectMapper mapper = JsonUtils.mapper();
    private final AtomicLong dropWarn = new AtomicLong();

    private ArrayBlockingQueue<LogEvent> queue;
    private ScheduledExecutorService scheduler;
    private OkHttpClient client;

    public LokiLogExporter(ObsExportProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        if (!properties.lokiEnabled()) {
            return;
        }
        this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(3))
                .writeTimeout(Duration.ofSeconds(3))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "obs-loki-exporter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush, properties.getLokiFlushIntervalMs(),
                properties.getLokiFlushIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("[obs-loki] exporter started -> {}", properties.getLokiUrl());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (queue != null) {
            flush();
        }
    }

    @Override
    public void onEvent(LogEvent event) {
        if (!properties.lokiEnabled() || event == null || queue == null) {
            return;
        }
        if (!queue.offer(event)) {
            long n = dropWarn.incrementAndGet();
            if (n <= 3 || n % 100 == 0) {
                log.warn("[obs-loki] queue full, drop log events (total dropped warning #{})", n);
            }
        }
    }

    /** 攒批推送（工作线程）。 */
    private void flush() {
        if (queue.isEmpty()) {
            return;
        }
        List<LogEvent> batch = new ArrayList<>(Math.min(queue.size(), properties.getLokiBatchSize()));
        queue.drainTo(batch, properties.getLokiBatchSize());
        if (batch.isEmpty()) {
            return;
        }
        try {
            String body = buildPushPayload(batch);
            postPush(body);
        } catch (Exception e) {
            log.warn("[obs-loki] push failed ({} events dropped): {}", batch.size(), e.getMessage());
        }
    }

    /** 组装 Loki JSON push 请求体（按 label 分组 streams）。 */
    private String buildPushPayload(List<LogEvent> batch) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (LogEvent e : batch) {
            Map<String, String> labels = labelsOf(e);
            String key = labels.toString();
            Map<String, Object> stream = grouped.computeIfAbsent(key,
                    k -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("stream", labels);
                        s.put("values", new ArrayList<>());
                        return s;
                    });
            @SuppressWarnings("unchecked")
            List<List<String>> values = (List<List<String>>) stream.get("values");
            values.add(List.of(epochNsOf(e), lineOf(e)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("streams", new ArrayList<>(grouped.values()));
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("[obs-loki] serialize payload failed: {}", e.getMessage());
            return "{\"streams\":[]}";
        }
    }

    private Map<String, String> labelsOf(LogEvent e) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("service_name", SERVICE_NAME);
        labels.put("tenant", e.tenantId() == null || e.tenantId().isBlank() ? "default" : e.tenantId());
        labels.put("level", e.level() == null ? "info" : e.level().name().toLowerCase());
        labels.put("category", e.category() == null ? "system" : e.category().name());
        return labels;
    }

    /** 日志行 JSON：高基数字段全部放在行内，trace_id 用 hex 以便与 Tempo 联动。 */
    private String lineOf(LogEvent e) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("log_id", e.logId());
        line.put("ts", e.timestamp() == null ? "" : e.timestamp().toString());
        line.put("message", e.message());
        line.put("level", e.level() == null ? "info" : e.level().name().toLowerCase());
        line.put("category", e.category() == null ? "system" : e.category().name());
        line.put("trace_id", TraceIdMapper.toHexTraceId(e.traceId()));
        line.put("app_trace_id", e.traceId());
        line.put("run_id", e.runId());
        line.put("agent_id", e.agentId());
        if (e.context() != null && !e.context().isEmpty()) {
            line.put("context", e.context());
        }
        if (e.stackTrace() != null && !e.stackTrace().isBlank()) {
            line.put("stack_trace", e.stackTrace());
        }
        if (e.fingerprint() != null && !e.fingerprint().isBlank()) {
            line.put("fingerprint", e.fingerprint());
        }
        try {
            return mapper.writeValueAsString(line);
        } catch (Exception ex) {
            return "{\"message\":" + quote(e.message()) + "}";
        }
    }

    /** 事件时间戳 → Loki 纳秒（取日志时间；无则当前时间）。 */
    private static String epochNsOf(LogEvent e) {
        long ms = e.timestamp() == null
                ? System.currentTimeMillis()
                : e.timestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return Long.toString(ms * 1_000_000L);
    }

    private void postPush(String jsonBody) {
        try {
            Request.Builder rb = new Request.Builder()
                    .url(properties.getLokiUrl() + "/loki/api/v1/push")
                    .post(RequestBody.create(jsonBody, JSON));
            if (properties.getLokiTenantId() != null) {
                rb.header("X-Scope-OrgID", properties.getLokiTenantId());
            }
            try (Response resp = client.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("[obs-loki] push status={} body={}", resp.code(),
                            resp.body() == null ? "" : resp.body().string());
                }
            }
        } catch (Exception e) {
            log.warn("[obs-loki] push error: {}", e.getMessage());
        }
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
