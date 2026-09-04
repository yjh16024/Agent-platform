package com.agentplatform.core.log;

/**
 * 日志查询过滤条件。
 * <p>各字段为空表示不过滤。{@code tenantId} 用于多租户隔离（缺省 default）。</p>
 *
 * @param tenantId    租户（多租户隔离）
 * @param traceId     Trace ID（全链路）
 * @param runId       运行实例
 * @param agentId     智能体
 * @param pluginId    插件
 * @param skillId     Skill
 * @param level       级别
 * @param category    类别
 * @param fingerprint 错误指纹
 * @param keyword     全文关键词
 */
public record LogQuery(
        String tenantId,
        String traceId,
        String runId,
        String agentId,
        String pluginId,
        String skillId,
        LogLevel level,
        LogCategory category,
        String fingerprint,
        String keyword
) {
    /** 无租户语义的便捷构造（走 default 租户）。 */
    public LogQuery(String traceId, String runId, String agentId, String pluginId, String skillId,
                    LogLevel level, LogCategory category, String fingerprint, String keyword) {
        this("default", traceId, runId, agentId, pluginId, skillId, level, category, fingerprint, keyword);
    }
}
