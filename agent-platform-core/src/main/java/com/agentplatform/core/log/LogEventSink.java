package com.agentplatform.core.log;

/**
 * 日志事件消费出口（可观测性外推）。
 * <p>
 * {@link LogService} 在每条日志落库/入内存兜底队列后，会把同一条 {@link LogEvent}
 * 依次派发给所有注入的 Sink（当前实现见 {@code observability/export/}）：
 * Micrometer 业务指标、Loki 日志推送、Tempo Span 合成。
 * 实现类必须保证 {@link #onEvent} 非阻塞、失败自隔离，绝不能把日志采集主流程拖垮。
 * </p>
 */
public interface LogEventSink {

    /**
     * 消费一条已完成富化（指纹/上下文）的日志事件。
     *
     * @param event 富化后的日志事件
     */
    void onEvent(LogEvent event);
}
