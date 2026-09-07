package com.agentplatform.core.observability;

import com.agentplatform.model.entity.LogIndex;
import com.agentplatform.model.repository.LogIndexRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用内可观测性聚合服务（阶段 A，零外部依赖）。
 * <p>
 * 读取 {@code log_index} 中 Agent 运行时已有的结构化埋点（run.start / run.completed /
 * run.failed / llm.call / llm.done / tool.call 等），按 traceId 聚合成一次智能体运行的
 * 摘要：状态、耗时、模型、LLM 调用数、工具调用与失败数、token 消耗、是否短路等。
 * 数据源与「运行日志」一致，便于页面在同一 traceId 下进一步下钻时间线。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservabilityService {

    private static final Pattern LATENCY_MS = Pattern.compile("latency=(\\d+)ms");
    private static final Pattern TOKENS = Pattern.compile("tokens=(\\d+)");
    private static final Pattern MODEL = Pattern.compile("model=(\\S+)");

    private final LogIndexRepository logIndexRepository;

    /**
     * 聚合最近若干次运行（按 agent 可选过滤）。
     *
     * @param tenantId 租户
     * @param agentId  智能体（可空，空=全部）
     * @param limit    返回的运行条数上限
     */
    public List<Map<String, Object>> runSummaries(String tenantId, String agentId, int limit) {
        // ① 拉取最近 agent 类日志（时间倒序），截取前 limit 个不重复 trace
        List<LogIndex> agentLogs = safeSearch(tenantId, agentId);
        List<String> targetTraces = new ArrayList<>();
        for (LogIndex l : agentLogs) {
            if (!isRunMarker(l.getMessage())) {
                continue;
            }
            String trace = l.getTraceId();
            if (trace == null || trace.isBlank() || targetTraces.contains(trace)) {
                continue;
            }
            targetTraces.add(trace);
            if (targetTraces.size() >= limit) {
                break;
            }
        }

        // ② 逐 trace 拉取完整时间线并聚合
        List<Map<String, Object>> result = new ArrayList<>();
        for (String trace : targetTraces) {
            List<LogIndex> all = logIndexRepository.findByTraceIdOrderByTimestampAscIdAsc(trace);
            Map<String, Object> row = aggregate(trace, all);
            if (row != null) {
                result.add(row);
            }
        }
        return result;
    }

    /** 对某 trace 的全部日志做聚合。 */
    private Map<String, Object> aggregate(String traceId, List<LogIndex> logs) {
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("traceId", traceId);

        String runId = null;
        String agentId = null;
        String model = null;
        LocalDateTime startedAt = null;
        boolean failed = false;
        boolean shortCircuit = false;
        long latencyMs = -1;
        long tokenTotal = 0;
        int llmCalls = 0;
        int toolCalls = 0;
        int toolFailures = 0;
        int ragCalls = 0;

        for (LogIndex l : logs) {
            if (runId == null) {
                runId = l.getRunId();
            }
            if (agentId == null) {
                agentId = l.getAgentId();
            }
            String msg = l.getMessage() == null ? "" : l.getMessage();
            // 运行开始时间（取最早的 run.start / run.completed 均可，这里用第一条日志兜底）
            if (startedAt == null || (l.getTimestamp() != null && l.getTimestamp().isBefore(startedAt))) {
                startedAt = l.getTimestamp();
            }
            if (msg.startsWith("run.failed")) {
                failed = true;
            }
            if (msg.startsWith("run.completed")) {
                shortCircuit = msg.contains("shortCircuit=true");
            }
            if (msg.startsWith("llm.call")) {
                llmCalls++;
            }
            if (msg.startsWith("llm.done")) {
                Matcher m = MODEL.matcher(msg);
                if (m.find()) {
                    model = m.group(1);
                }
                Matcher t = TOKENS.matcher(msg);
                if (t.find()) {
                    tokenTotal += Long.parseLong(t.group(1));
                }
            }
            if (msg.startsWith("tool.call")) {
                toolCalls++;
                if (msg.contains("success=false")) {
                    toolFailures++;
                }
            }
            if (msg.contains("Dense retrieval") || msg.contains("sparse")) {
                ragCalls++;
            }
            if (msg.contains("llm.done") || msg.contains("run.completed")) {
                Matcher lm = LATENCY_MS.matcher(msg);
                if (lm.find()) {
                    latencyMs = Math.max(latencyMs, Long.parseLong(lm.group(1)));
                }
            }
        }
        // run.failed 时也可能没有 run.completed 行；拿最后一条错误行近似失败耗时
        if (latencyMs < 0) {
            latencyMs = 0;
        }
        row.put("runId", runId == null ? traceId : runId);
        row.put("agentId", agentId == null ? "" : agentId);
        row.put("model", model == null ? "" : model);
        row.put("status", failed ? "failed" : "ok");
        row.put("shortCircuit", shortCircuit);
        row.put("startedAt", startedAt == null ? "" : startedAt.toString());
        row.put("latencyMs", latencyMs);
        row.put("llmCalls", llmCalls);
        row.put("toolCalls", toolCalls);
        row.put("toolFailures", toolFailures);
        row.put("ragCalls", ragCalls);
        row.put("tokens", tokenTotal);
        return row;
    }

    /** 是否为本平台 agent 运行标记日志。 */
    private boolean isRunMarker(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.startsWith("run.start") || msg.startsWith("run.completed")
                || msg.startsWith("run.failed") || msg.startsWith("run.aborted");
    }

    /** 仓库查询失败时降级为空（可观测性不应拖垮查询）。 */
    private List<LogIndex> safeSearch(String tenantId, String agentId) {
        try {
            return logIndexRepository.search(
                    tenantId, null, null, agentId, null, "agent", null, null);
        } catch (Exception e) {
            log.warn("[obs] load agent logs failed: {}", e.getMessage());
            return List.of();
        }
    }
}
