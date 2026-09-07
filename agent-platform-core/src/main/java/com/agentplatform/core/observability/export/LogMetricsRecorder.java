package com.agentplatform.core.observability.export;

import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogEventSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Micrometer 业务指标采集（阶段 B/C：Token / 延迟 / 错误率 / 工具调用等）。
 * <p>
 * 复用 {@code LogService} 的唯一出口，从既有结构化埋点日志（run.* / llm.* / tool.*）中
 * 解析数值写入 Prometheus 指标端点 {@code /actuator/prometheus}（Prometheus 已就位，
 * Grafana 直接消费）。不改变采集语义：LLM 每轮 llm.done 带 provider/model/latency/tokens，
 * run.completed / run.failed 表达一次运行的最终状态。
 * </p>
 * <p>注册的指标：</p>
 * <ul>
 *   <li>{@code agent_run_total{status=ok|failed|aborted, agent}}</li>
 *   <li>{@code agent_run_latency_seconds{status, agent}}（run.start → run.completed/failed）</li>
 *   <li>{@code agent_llm_calls_total{provider, model}}</li>
 *   <li>{@code agent_llm_tokens_total{provider, model}}</li>
 *   <li>{@code agent_llm_latency_seconds_sum/count/max{provider, model}}</li>
 *   <li>{@code agent_tool_calls_total{name, success}}</li>
 *   <li>{@code log_events_total{level, category}}（任意结构化日志，错误率来源）</li>
 * </ul>
 */
@Slf4j
@Component
public class LogMetricsRecorder implements LogEventSink {

    private static final Pattern PROVIDER_MODEL = Pattern.compile("provider=(\\S+)\\s+model=(\\S+)");
    private static final Pattern LATENCY_MS = Pattern.compile("latency=(\\d+)ms");
    private static final Pattern TOKENS = Pattern.compile("tokens=(\\d+)");
    private static final Pattern TOOL_NAME = Pattern.compile("name=(\\S+)");
    private static final Pattern TOOL_SUCCESS = Pattern.compile("success=(true|false)");

    /** 运行计时起点：traceId → startNano。 */
    private final Map<String, Long> runStartedNanos = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public LogMetricsRecorder(MeterRegistry meterRegistry, ObsExportProperties properties) {
        this.meterRegistry = meterRegistry;
        this.enabled = properties.isMetricsEnabled();
        if (enabled) {
            log.info("[obs-metrics] enabled: agent run / llm / tool business metrics -> /actuator/prometheus");
        }
    }

    @Override
    public void onEvent(LogEvent event) {
        if (!enabled || event == null) {
            return;
        }
        try {
            String msg = event.message() == null ? "" : event.message();

            // ① 任意日志计数（级别 × 类别，业务错误率来源）
            counter("log.events",
                    "level", levelOf(event), "category", categoryOf(event)).increment();

            // ② 运行开始 / 结束（run 计时 + 结果计数）
            if (msg.startsWith("run.start")) {
                runStartedNanos.put(traceKey(event), System.nanoTime());
            } else if (msg.startsWith("run.completed")) {
                recordRunResult(event, "ok", msg);
            } else if (msg.startsWith("run.failed")) {
                recordRunResult(event, "failed", msg);
            } else if (msg.startsWith("run.aborted")) {
                recordRunResult(event, "aborted", msg);
            }

            // ③ LLM 调用与耗时
            if (msg.startsWith("llm.done")) {
                Matcher pm = PROVIDER_MODEL.matcher(msg);
                String provider = pm.find() ? pm.group(1) : "unknown";
                String model = pm.group(2);
                Matcher lm = LATENCY_MS.matcher(msg);
                if (lm.find()) {
                    meterRegistry.timer("agent.llm.latency",
                            "provider", provider, "model", model)
                            .record(Duration.ofMillis(Long.parseLong(lm.group(1))));
                }
                Matcher tm = TOKENS.matcher(msg);
                if (tm.find()) {
                    counter("agent.llm.tokens", "provider", provider, "model", model)
                            .increment(Long.parseLong(tm.group(1)));
                }
                counter("agent.llm.calls", "provider", provider, "model", model).increment();
            }

            // ④ 工具调用结果
            if (msg.startsWith("tool.call")) {
                Matcher nm = TOOL_NAME.matcher(msg);
                String name = nm.find() ? nm.group(1) : "unknown";
                Matcher sm = TOOL_SUCCESS.matcher(msg);
                boolean success = !sm.find() || Boolean.parseBoolean(sm.group(1));
                counter("agent.tool.calls", "name", name, "success", String.valueOf(success)).increment();
            }
        } catch (Exception e) {
            // 指标采集失败绝不影响主流程（尽力而为）
            log.warn("[obs-metrics] record failed: {}", e.getMessage());
        }
    }

    private void recordRunResult(LogEvent event, String status, String msg) {
        String key = traceKey(event);
        Long startNano = runStartedNanos.remove(key);
        counter("agent.run", "status", status, "agent", agentOf(event)).increment();
        if (startNano != null) {
            meterRegistry.timer("agent.run.latency", "status", status, "agent", agentOf(event))
                    .record(Duration.ofNanos(System.nanoTime() - startNano));
        }
        // 清理兜底，避免只出现 run.failed 无 run.start 时计时表膨胀
        runStartedNanos.remove(key);
    }

    private Counter counter(String name, String... tags) {
        return meterRegistry.counter(name, tags);
    }

    private static String traceKey(LogEvent e) {
        return e.traceId() == null ? "trace_" + e.logId() : e.traceId();
    }

    private static String levelOf(LogEvent e) {
        return e.level() == null ? "info" : e.level().name().toLowerCase();
    }

    private static String categoryOf(LogEvent e) {
        return e.category() == null ? "system" : e.category().name();
    }

    private static String agentOf(LogEvent e) {
        return e.agentId() == null || e.agentId().isBlank() ? "unknown" : e.agentId();
    }
}
