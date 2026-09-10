package com.agentplatform.core.observability.export;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogEventSink;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tempo Span 导出器（阶段 B：Agent 执行链路 → Tempo）。
 * <p>
 * 从 {@code LogService} 的既有事件流（run.* / llm.* / tool.*，同一 trace 串行出现）
 * 合成父子 Span 树：{@code run → llm.chat / tool.call}，以 Zipkin v2 JSON 协议
 * （Tempo 原生接收端点 {@code POST {url}/api/v2/spans}）上报，零额外依赖。
 * </p>
 * <p>
 * 应用内部 traceId 经 {@link TraceIdMapper} 映射为 32 位 hex，与 Loki 行内
 * {@code trace_id} 一致，从而 Grafana 中「日志 ↔ Trace ↔ 指标」可相互关联。
 * </p>
 */
@Slf4j
@Component
public class TempoSpanExporter implements LogEventSink {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String SERVICE_NAME = "agent-platform-core";

    private static final Pattern PROVIDER_MODEL = Pattern.compile("provider=(\\S+)\\s+model=(\\S+)");
    private static final Pattern LATENCY_MS = Pattern.compile("latency=(\\d+)ms");
    private static final Pattern TOKENS = Pattern.compile("tokens=(\\d+)");
    private static final Pattern TOOL_NAME = Pattern.compile("name=(\\S+)");
    private static final Pattern TOOL_SUCCESS = Pattern.compile("success=(true|false)");

    /** appTraceId → 运行状态（随 run 结束自动清理）。 */
    private final Map<String, TraceState> traces = new ConcurrentHashMap<>();

    private final ObsExportProperties properties;
    private final ObjectMapper mapper = JsonUtils.mapper();
    private OkHttpClient client;
    private volatile long lastErrorLog = 0;

    public TempoSpanExporter(ObsExportProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        if (!properties.tempoEnabled()) {
            return;
        }
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(3))
                .writeTimeout(Duration.ofSeconds(3))
                .build();
        log.info("[obs-tempo] span exporter started -> {}", properties.getTempoUrl());
    }

    @Override
    public void onEvent(LogEvent event) {
        if (!properties.tempoEnabled() || event == null) {
            return;
        }
        try {
            String msg = event.message() == null ? "" : event.message();
            String key = event.traceId();
            if (key == null || key.isBlank()) {
                return;
            }
            if (msg.startsWith("run.start")) {
                traces.computeIfAbsent(key, k -> new TraceState(key)).beginRun();
            } else if (msg.startsWith("llm.call")) {
                TraceState st = traces.get(key);
                if (st != null) {
                    st.openLlm(msg);
                }
            } else if (msg.startsWith("llm.done")) {
                TraceState st = traces.get(key);
                if (st != null) {
                    st.closeLlm(event, msg);
                }
            } else if (msg.startsWith("tool.call")) {
                TraceState st = traces.get(key);
                if (st != null) {
                    st.recordTool(event, msg);
                }
            } else if (msg.startsWith("run.completed") || msg.startsWith("run.failed")
                    || msg.startsWith("run.aborted")) {
                TraceState st = traces.remove(key);
                if (st != null) {
                    st.finishRun(event, msg);
                }
            }
        } catch (Exception e) {
            log.warn("[obs-tempo] span build failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 一次运行的内存态（同一 trace 的事件按时间顺序串行出现）
    // ------------------------------------------------------------------

    private final class TraceState {

        private final String appTraceId;
        private OpenSpan run;
        private OpenSpan llm;
        private final List<Map<String, Object>> buffered = new ArrayList<>();

        private TraceState(String appTraceId) {
            this.appTraceId = appTraceId;
        }

        void beginRun() {
            if (run != null) {
                return;
            }
            run = new OpenSpan("agent.run");
            run.spanId = TraceIdMapper.randomSpanId();
            run.parentId = null;
            run.startMicros = nowMicros();
        }

        void openLlm(String msg) {
            if (run == null) {
                return;
            }
            OpenSpan s = new OpenSpan("llm.chat");
            s.spanId = TraceIdMapper.randomSpanId();
            s.parentId = run.spanId;
            s.startMicros = nowMicros();
            Matcher pm = PROVIDER_MODEL.matcher(msg);
            if (pm.find()) {
                s.tags.put("provider", pm.group(1));
                s.tags.put("model", pm.group(2));
            }
            llm = s;
        }

        void closeLlm(LogEvent e, String msg) {
            OpenSpan s = llm;
            if (s == null) {
                return;
            }
            llm = null;
            Matcher pm = PROVIDER_MODEL.matcher(msg);
            if (pm.find()) {
                s.tags.put("provider", pm.group(1));
                s.tags.put("model", pm.group(2));
            }
            Matcher tm = TOKENS.matcher(msg);
            if (tm.find()) {
                s.tags.put("tokens", tm.group(1));
            }
            applyLatency(s, msg);
            buffered.add(toZipkin(s));
        }

        void recordTool(LogEvent e, String msg) {
            if (run == null) {
                return;
            }
            OpenSpan s = new OpenSpan("tool.call");
            s.spanId = TraceIdMapper.randomSpanId();
            s.parentId = run.spanId;
            Matcher nm = TOOL_NAME.matcher(msg);
            if (nm.find()) {
                s.tags.put("tool", nm.group(1));
            }
            Matcher sm = TOOL_SUCCESS.matcher(msg);
            boolean ok = !sm.find() || Boolean.parseBoolean(sm.group(1));
            s.tags.put("success", String.valueOf(ok));
            if (!ok) {
                s.tags.put("error", "true");
            }
            applyLatency(s, msg);
            buffered.add(toZipkin(s));
        }

        void finishRun(LogEvent e, String msg) {
            if (run == null) {
                return;
            }
            if (msg.startsWith("run.failed")) {
                run.tags.put("status", "error");
                run.tags.put("error", "true");
            } else if (msg.startsWith("run.aborted")) {
                run.tags.put("status", "aborted");
            } else {
                run.tags.put("status", "ok");
            }
            run.tags.put("agent", e.agentId() == null ? "" : e.agentId());
            buffered.add(toZipkin(run));
            run = null;
            llm = null;
            exportTrace();
        }

        /** 若消息带 latency=NNms，则以此刻为结束点回推开始时间，否则按开启时刻补算。 */
        private void applyLatency(OpenSpan s, String msg) {
            Matcher lm = LATENCY_MS.matcher(msg);
            if (!lm.find()) {
                return;
            }
            long micros = Long.parseLong(lm.group(1)) * 1000L;
            s.durationMicros = micros;
            if (s.startMicros == 0) {
                s.startMicros = nowMicros() - micros;
            }
        }

        /** 转 Zipkin v2 JSON 结构。 */
        Map<String, Object> toZipkin(OpenSpan s) {
            long duration = s.durationMicros > 0
                    ? s.durationMicros : (nowMicros() - s.startMicros);
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("traceId", TraceIdMapper.toHexTraceId(appTraceId));
            span.put("id", s.spanId);
            if (s.parentId != null) {
                span.put("parentId", s.parentId);
            }
            span.put("name", s.name);
            span.put("timestamp", s.startMicros);
            span.put("duration", Math.max(1L, duration));
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("serviceName", SERVICE_NAME);
            span.put("localEndpoint", endpoint);
            if (!s.tags.isEmpty()) {
                span.put("tags", s.tags);
            }
            return span;
        }

        /** run 结束时统一把整棵缓冲树一次推送（保证父子完整、减少请求数）。 */
        void exportTrace() {
            if (buffered.isEmpty()) {
                return;
            }
            List<Map<String, Object>> payload = new ArrayList<>(buffered);
            buffered.clear();
            postSpans(payload);
        }
    }

    /** Span 工作结构。 */
    private static final class OpenSpan {
        private final String name;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private String spanId;
        private String parentId;
        private long startMicros;
        private long durationMicros;

        private OpenSpan(String name) {
            this.name = name;
        }
    }

    // ------------------------------------------------------------------

    private void postSpans(List<Map<String, Object>> spans) {
        try {
            String json = mapper.writeValueAsString(spans);
            try (Response resp = client.newCall(new Request.Builder()
                    .url(properties.getTempoUrl() + "/api/v2/spans")
                    .post(RequestBody.create(json, JSON))
                    .build()).execute()) {
                if (!resp.isSuccessful() && shouldLogError()) {
                    log.warn("[obs-tempo] push spans status={} count={}", resp.code(), spans.size());
                }
            }
        } catch (Exception e) {
            if (shouldLogError()) {
                log.warn("[obs-tempo] push spans failed ({} spans dropped): {}", spans.size(), e.getMessage());
            }
        }
    }

    /** 失败日志限频（最多 1 次/10s），避免刷屏。 */
    private boolean shouldLogError() {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 10_000) {
            lastErrorLog = now;
            return true;
        }
        return false;
    }

    private static long nowMicros() {
        return System.currentTimeMillis() * 1000L;
    }
}
