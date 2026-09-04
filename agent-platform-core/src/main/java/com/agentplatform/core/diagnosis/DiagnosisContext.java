package com.agentplatform.core.diagnosis;

import com.agentplatform.core.log.LogCategory;
import com.agentplatform.core.log.LogLevel;
import com.agentplatform.core.log.LogEvent;

import java.util.Map;

/**
 * 诊断上下文（诊断引擎输入）。
 *
 * @param traceId     Trace ID
 * @param logEvent    原始日志事件
 * @param fingerprint 错误指纹
 * @param category    分类
 * @param message     错误消息
 * @param stackTrace  异常堆栈
 * @param context     补充上下文（插件 ID/模型名/配置等）
 */
public record DiagnosisContext(
        String traceId,
        LogEvent logEvent,
        String fingerprint,
        LogCategory category,
        String message,
        String stackTrace,
        Map<String, Object> context
) {
    /**
     * 从日志事件构建。
     */
    public static DiagnosisContext from(LogEvent event) {
        return new DiagnosisContext(
                event.traceId(), event, event.fingerprint(), event.category(),
                event.message(), event.stackTrace(), event.context());
    }

    /**
     * 是否含足够文本信息（用于向量/LLM 检索判断）。
     */
    public boolean hasErrorMessage() {
        return message != null && !message.isBlank();
    }
}