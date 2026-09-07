package com.agentplatform.core.observability;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.model.entity.LogIndex;
import com.agentplatform.model.repository.LogIndexRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体可观测性接口（阶段 A，应用内零外部依赖）。
 * <p>
 * 在既有「运行日志」之上增加面向 Agent 运行的可观测视角：
 * <ul>
 *   <li>{@code /runs}：按 agent 聚合出每次运行摘要（状态/耗时/模型/LLM/工具/token）；</li>
 *   <li>{@code /runs/{traceId}/timeline}：某次运行按时间线输出各阶段事件（llm/tool/rag…）。</li>
 * </ul>
 * 数据均来自 {@code log_index}，与运行日志页同源，保证页面可直接跳转下钻。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final ObservabilityService observabilityService;
    private final LogIndexRepository logIndexRepository;

    /** 最近运行摘要（可按 agent 过滤）。 */
    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String agentId,
            @RequestParam(defaultValue = "20") int limit) {
        int lim = limit <= 0 || limit > 100 ? 20 : limit;
        return ApiResponse.ok(observabilityService.runSummaries(tenantId, agentId, lim));
    }

    /** 某次运行的阶段时间线（下钻）。 */
    @GetMapping("/runs/{traceId}/timeline")
    public ApiResponse<List<Map<String, Object>>> timeline(@PathVariable String traceId) {
        List<LogIndex> logs;
        try {
            logs = logIndexRepository.findByTraceIdOrderByTimestampAscIdAsc(traceId);
        } catch (Exception e) {
            return ApiResponse.ok(List.of());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LogIndex l : logs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", l.getTimestamp() == null ? "" : l.getTimestamp().toString());
            row.put("category", l.getCategory());
            row.put("level", l.getLevel());
            row.put("agentId", l.getAgentId());
            row.put("message", l.getMessage());
            rows.add(row);
        }
        return ApiResponse.ok(rows);
    }
}
