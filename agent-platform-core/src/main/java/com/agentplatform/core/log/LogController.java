package com.agentplatform.core.log;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.common.dto.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运行日志接口（查询 / 瀑布图 / 导出 / 采集）。
 */
@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogService logService;

    /** 分页查询。 */
    @GetMapping
    public ApiResponse<PageResult<LogEvent>> query(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String pluginId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String fingerprint,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        LogQuery query = new LogQuery(tenantId, traceId, runId, agentId, pluginId, null,
                level == null ? null : LogLevel.valueOf(level),
                category == null ? null : LogCategory.valueOf(category),
                fingerprint, keyword);
        return ApiResponse.ok(logService.query(query, page, size));
    }

    /** 导出（JSON）。 */
    @PostMapping("/export")
    public ApiResponse<List<LogEvent>> export(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody LogQuery query,
            @RequestParam(defaultValue = "100000") int maxRows) {
        LogQuery scoped = new LogQuery(tenantId, query.traceId(), query.runId(), query.agentId(),
                query.pluginId(), query.skillId(), query.level(), query.category(),
                query.fingerprint(), query.keyword());
        return ApiResponse.ok(logService.export(scoped, maxRows));
    }

    /** 清理超过保留期的日志。 */
    @DeleteMapping("/purge")
    public ApiResponse<Map<String, Object>> purge(@RequestParam(defaultValue = "30") int retentionDays) {
        int deleted = logService.purge(retentionDays);
        return ApiResponse.ok(Map.of("deleted", deleted, "retention_days", retentionDays), "purged");
    }

    /** 调用链瀑布图。 */
    @GetMapping("/traces/{traceId}/waterfall")
    public ApiResponse<List<Map<String, Object>>> waterfall(@PathVariable String traceId) {
        return ApiResponse.ok(logService.waterfall(traceId));
    }

    /** 采集日志（供各模块上报）。 */
    @PostMapping
    public ApiResponse<LogEvent> collect(@RequestBody LogEvent event) {
        return ApiResponse.ok(logService.log(event));
    }
}