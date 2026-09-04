package com.agentplatform.core.log;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 结构化日志事件（全链路 Trace 串联）。
 * <p>通过 trace_id + run_id + tenant_id 三维度串联，一次运行的所有事件归属同一 Trace。</p>
 *
 * @param logId       日志 ID
 * @param timestamp   时间戳
 * @param traceId     Trace ID（OTel W3C Trace Context）
 * @param runId       运行实例 ID
 * @param tenantId    租户 ID
 * @param agentId     智能体 ID
 * @param level       级别
 * @param category    类别（agent/llm/plugin/skill/api/workflow/system）
 * @param message     消息
 * @param context     上下文（插件/模型/工具等结构化字段）
 * @param stackTrace  异常堆栈（脱敏后）
 * @param fingerprint 错误指纹（用于诊断匹配）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogEvent(
        String logId,
        LocalDateTime timestamp,
        String traceId,
        String runId,
        String tenantId,
        String agentId,
        LogLevel level,
        LogCategory category,
        String message,
        Map<String, Object> context,
        String stackTrace,
        String fingerprint
) {
    public static LogEvent of(String id, LogLevel level, LogCategory category, String message,
                              String tenantId, String traceId, String runId, Map<String, Object> context) {
        return new LogEvent(id, LocalDateTime.now(), traceId, runId, tenantId, null,
                level, category, message, context, null, null);
    }
}