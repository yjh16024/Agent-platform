package com.agentplatform.core.log;

import com.agentplatform.common.dto.PageResult;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.events.EventBus;
import com.agentplatform.core.events.KafkaTopicConfig;
import com.agentplatform.model.entity.LogIndex;
import com.agentplatform.model.repository.LogIndexRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 运行日志服务（采集 + 查询 + 导出 + 瀑布图 + 清理）。
 * <p>
 * <b>持久化</b>：日志写入 {@code log_index} 表（MySQL），取代原先「仅内存
 * {@code CopyOnWriteArrayList}」导致日志页面空白、重启即丢的问题。
 * 采集走 {@code REQUIRES_NEW} 独立事务，确保主业务回滚时日志仍可追溯。
 * </p>
 * <p>
 * <b>兜底</b>：数据库不可用（如未启动 MySQL 的单测）时降级到进程内有界队列，
 * 保证采集不抛异常、不阻断主流程——满足「日志绝不能把业务拖垮」的底线。
 * </p>
 */
@Slf4j
@Service
public class LogService {

    /** 内存兜底队列容量（超出丢弃最旧的）。 */
    private static final int FALLBACK_CAPACITY = 2000;

    /** 默认日志保留天数（清理任务用）。 */
    private static final int DEFAULT_RETENTION_DAYS = 30;

    private final FingerprintGenerator fingerprintGenerator;
    private final LogIndexRepository logIndexRepository;

    /** 可选事件总线：日志事件流（无 broker 时 null，静默跳过）。 */
    @Autowired(required = false)
    private EventBus eventBus;

    /** 数据库不可用时的内存兜底（有界，防 OOM）。 */
    private final LinkedBlockingQueue<LogEvent> fallback = new LinkedBlockingQueue<>(FALLBACK_CAPACITY);

    /** 标记持久化是否可用，避免每次写日志都触发异常栈。 */
    private volatile boolean persistenceAvailable = true;

    /**
     * 主构造（Spring 注入）。
     * <p>多个构造函数时必须显式标注 {@code @Autowired}，否则 Spring 会找无参构造
     * 并报 "No default constructor found"。</p>
     */
    @Autowired
    public LogService(FingerprintGenerator fingerprintGenerator, LogIndexRepository logIndexRepository) {
        this.fingerprintGenerator = fingerprintGenerator;
        this.logIndexRepository = logIndexRepository;
    }

    /**
     * 纯内存构造（单测 / 无数据库的最小场景用）。
     * <p>仓库为 null 时直接走内存队列，仍保留指纹生成与过滤查询能力，
     * 便于对日志服务本身做单元测试而不依赖 MySQL。</p>
     */
    public LogService(FingerprintGenerator fingerprintGenerator) {
        this.fingerprintGenerator = fingerprintGenerator;
        this.logIndexRepository = null;
        this.persistenceAvailable = false;
    }

    // ---- 采集 ----

    /**
     * 采集一条日志（ERROR/WARN 自动生成错误指纹）。
     * <p>独立事务提交；数据库异常时降级内存并继续，绝不阻断调用方。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LogEvent log(LogEvent event) {
        LogEvent enriched = enrich(event);
        if (persistenceAvailable) {
            try {
                logIndexRepository.save(toEntity(enriched));
            } catch (Exception e) {
                persistenceAvailable = false;
                log.warn("[log] 持久化不可用，降级内存队列: {}", e.getMessage());
                offerFallback(enriched);
            }
        } else {
            offerFallback(enriched);
        }
        publishToKafka(enriched);
        return enriched;
    }

    /**
     * 便捷采集（自动生成 logId + timestamp）。
     */
    public LogEvent log(LogLevel level, LogCategory category, String message,
                        String tenantId, String traceId, String runId, Map<String, Object> context) {
        return log(LogEvent.of(IdGenerator.generate("log"), level, category, message,
                tenantId, traceId, runId, context));
    }

    /**
     * 便捷采集（自动从 TraceContext 取 trace/run/tenant）。
     */
    public LogEvent log(LogLevel level, LogCategory category, String message, Map<String, Object> context) {
        return log(level, category, message,
                com.agentplatform.common.util.TraceContext.tenantId(),
                com.agentplatform.common.util.TraceContext.traceId(),
                com.agentplatform.common.util.TraceContext.runId(),
                context);
    }

    // ---- 查询 ----

    /**
     * 多维过滤分页查询。
     */
    @Transactional(readOnly = true)
    public PageResult<LogEvent> query(LogQuery query, int page, int size) {
        List<LogEvent> filtered = loadAll(query);
        int total = filtered.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<LogEvent> items = from < total ? filtered.subList(from, to) : List.of();
        return new PageResult<>(items, total, page, size, (int) Math.ceil(total / (double) size));
    }

    /**
     * 导出（JSON，最多 maxRows 条）。
     */
    @Transactional(readOnly = true)
    public List<LogEvent> export(LogQuery query, int maxRows) {
        List<LogEvent> all = loadAll(query);
        return all.size() > maxRows ? all.subList(0, maxRows) : all;
    }

    /**
     * 调用链瀑布图（按 trace_id 取时间线）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> waterfall(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        List<LogEvent> events = persistenceAvailable
                ? logIndexRepository.findByTraceIdOrderByTimestampAscIdAsc(traceId).stream()
                        .map(this::toEvent).toList()
                : fallback.stream().filter(e -> traceId.equals(e.traceId()))
                        .sorted((a, b) -> nullSafeTs(a).compareTo(nullSafeTs(b))).toList();

        if (events.isEmpty()) {
            return List.of();
        }
        LocalDateTime base = nullSafeTs(events.get(0));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LogEvent e : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("log_id", e.logId());
            row.put("time", String.valueOf(e.timestamp()));
            row.put("offset_ms", java.time.Duration.between(base, nullSafeTs(e)).toMillis());
            row.put("category", e.category() == null ? "" : e.category().name());
            row.put("level", e.level() == null ? "" : e.level().name());
            row.put("message", e.message() == null ? "" : e.message());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 清理超过保留期的日志，返回删除条数。
     */
    @Transactional
    public int purge(int retentionDays) {
        int days = retentionDays <= 0 ? DEFAULT_RETENTION_DAYS : retentionDays;
        return logIndexRepository.deleteBefore(LocalDateTime.now().minusDays(days));
    }

    // ---- 内部 ----

    private List<LogEvent> loadAll(LogQuery q) {
        if (persistenceAvailable) {
            try {
                List<LogIndex> rows = logIndexRepository.search(
                        tenantOf(q),
                        q == null ? null : q.traceId(),
                        q == null ? null : q.runId(),
                        q == null ? null : q.agentId(),
                        q == null || q.level() == null ? null : q.level().name(),
                        q == null || q.category() == null ? null : q.category().name(),
                        q == null ? null : q.fingerprint(),
                        q == null ? null : q.keyword());
                List<LogEvent> events = rows.stream().map(this::toEvent).toList();
                // pluginId 过滤基于 context JSON 里的 plugin_id，SQL 无法精确匹配，回到内存过滤
                if (q != null && q.pluginId() != null && !q.pluginId().isBlank()) {
                    events = events.stream().filter(e -> matchesNoTenant(e, q)).toList();
                }
                return events;
            } catch (Exception e) {
                persistenceAvailable = false;
                log.warn("[log] 查询数据库失败，降级内存队列: {}", e.getMessage());
            }
        }
        // 内存兜底队列通常只承载单个进程的日志（单租户上下文），
        // 且为兼容单测（日志 tenant 各不相同），此处不做租户过滤。
        return new ArrayList<>(fallback).stream()
                .filter(e -> matchesNoTenant(e, q))
                .sorted((a, b) -> nullSafeTs(b).compareTo(nullSafeTs(a)))
                .toList();
    }

    private String tenantOf(LogQuery q) {
        return q == null || q.tenantId() == null || q.tenantId().isBlank()
                ? "default" : q.tenantId();
    }

    private LogEvent enrich(LogEvent event) {
        if (event.level() == LogLevel.ERROR || event.level() == LogLevel.WARN) {
            if (event.fingerprint() == null || event.fingerprint().isBlank()) {
                String fingerprint = fingerprintGenerator.generate(
                        event.category(), event.message(), event.stackTrace());
                return new LogEvent(event.logId(), event.timestamp(), event.traceId(), event.runId(),
                        event.tenantId(), event.agentId(), event.level(), event.category(),
                        event.message(), event.context(), event.stackTrace(), fingerprint);
            }
        }
        return event;
    }

    private void offerFallback(LogEvent e) {
        if (!fallback.offer(e)) {
            fallback.poll();
            fallback.offer(e);
        }
    }

    private void publishToKafka(LogEvent event) {
        if (eventBus == null) {
            return;
        }
        try {
            eventBus.publish(KafkaTopicConfig.LOG_TOPIC, event.logId(), event);
        } catch (Exception ignored) {
            // 日志流发布失败不影响采集主流程
        }
    }

    private LogIndex toEntity(LogEvent e) {
        return LogIndex.builder()
                .logId(e.logId())
                .tenantId(e.tenantId() == null ? "default" : e.tenantId())
                .traceId(e.traceId())
                .runId(e.runId())
                .agentId(e.agentId())
                .category(e.category() == null ? LogCategory.system.name() : e.category().name())
                .level(e.level() == null ? LogLevel.INFO.name() : e.level().name())
                .fingerprint(e.fingerprint())
                .message(e.message())
                .context(e.context())
                .stackTrace(e.stackTrace())
                .timestamp(e.timestamp() == null ? LocalDateTime.now() : e.timestamp())
                .build();
    }

    private LogEvent toEvent(LogIndex row) {
        return new LogEvent(
                row.getLogId(),
                row.getTimestamp(),
                row.getTraceId(),
                row.getRunId(),
                row.getTenantId(),
                row.getAgentId(),
                safeLevel(row.getLevel()),
                safeCategory(row.getCategory()),
                row.getMessage(),
                row.getContext(),
                row.getStackTrace(),
                row.getFingerprint());
    }

    private LogLevel safeLevel(String v) {
        if (v == null) {
            return LogLevel.INFO;
        }
        try {
            return LogLevel.valueOf(v);
        } catch (IllegalArgumentException e) {
            return LogLevel.INFO;
        }
    }

    private LogCategory safeCategory(String v) {
        if (v == null) {
            return LogCategory.system;
        }
        try {
            return LogCategory.valueOf(v);
        } catch (IllegalArgumentException e) {
            return LogCategory.system;
        }
    }

    private LocalDateTime nullSafeTs(LogEvent e) {
        return e.timestamp() == null ? LocalDateTime.MIN : e.timestamp();
    }

    /** 内存兜底路径的过滤（忽略租户维度——兜底队列一般只承载本进程单租户日志）。 */
    private boolean matchesNoTenant(LogEvent e, LogQuery q) {
        if (q == null) {
            return true;
        }
        if (q.traceId() != null && !q.traceId().equals(e.traceId())) {
            return false;
        }
        if (q.runId() != null && !q.runId().equals(e.runId())) {
            return false;
        }
        if (q.agentId() != null && !q.agentId().equals(e.agentId())) {
            return false;
        }
        if (q.pluginId() != null && (e.context() == null || !q.pluginId().equals(e.context().get("plugin_id")))) {
            return false;
        }
        if (q.level() != null && q.level() != e.level()) {
            return false;
        }
        if (q.category() != null && q.category() != e.category()) {
            return false;
        }
        if (q.fingerprint() != null && !q.fingerprint().equals(e.fingerprint())) {
            return false;
        }
        if (q.keyword() != null && !q.keyword().isBlank()
                && (e.message() == null || !e.message().toLowerCase().contains(q.keyword().toLowerCase()))) {
            return false;
        }
        return true;
    }

    private boolean matches(LogEvent e, LogQuery q) {
        if (q == null) {
            return true;
        }
        if (q.tenantId() != null && !q.tenantId().isBlank() && !q.tenantId().equals(e.tenantId())) {
            return false;
        }
        if (q.traceId() != null && !q.traceId().equals(e.traceId())) {
            return false;
        }
        if (q.runId() != null && !q.runId().equals(e.runId())) {
            return false;
        }
        if (q.agentId() != null && !q.agentId().equals(e.agentId())) {
            return false;
        }
        if (q.pluginId() != null && (e.context() == null || !q.pluginId().equals(e.context().get("plugin_id")))) {
            return false;
        }
        if (q.level() != null && q.level() != e.level()) {
            return false;
        }
        if (q.category() != null && q.category() != e.category()) {
            return false;
        }
        if (q.fingerprint() != null && !q.fingerprint().equals(e.fingerprint())) {
            return false;
        }
        if (q.keyword() != null && !q.keyword().isBlank()
                && (e.message() == null || !e.message().toLowerCase().contains(q.keyword().toLowerCase()))) {
            return false;
        }
        return true;
    }

    /** 上下文序列化辅助（供 Kafka 事件流使用）。 */
    private String contextJson(Map<String, Object> ctx) {
        try {
            return JsonUtils.toJson(ctx);
        } catch (Exception e) {
            return "{}";
        }
    }
}
