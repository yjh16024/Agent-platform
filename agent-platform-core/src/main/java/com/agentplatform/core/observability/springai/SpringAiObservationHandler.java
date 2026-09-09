package com.agentplatform.core.observability.springai;

import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.common.util.TraceContext;
import com.agentplatform.core.log.LogCategory;
import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogLevel;
import com.agentplatform.core.log.LogService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring AI 观测桥接：把 ChatModel 的 Observation 转成平台既有格式的运行日志。
 * <p>
 * 之所以走「日志」而不是另起一套指标链路：平台已有 {@code LogService → LogEventSink}
 * 出口，{@code TempoSpanExporter} 正是按 {@code llm.call} / {@code llm.done} 消息前缀合成 span 树，
 * {@code LogMetricsRecorder} 也是从这些消息里解析 {@code provider / model / latency / tokens}。
 * 因此这里按同样格式产出事件，Loki / Tempo / Prometheus 三条出口<b>自动生效，无需改动</b>。
 * </p>
 * <p>Spring Boot 会自动把 {@code ObservationHandler} Bean 注册到 ObservationRegistry。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent-platform.springai.enabled", havingValue = "true", matchIfMissing = false)
public class SpringAiObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    private static final Object START_NANOS = new Object();

    private final LogService logService;

    public SpringAiObservationHandler(@Autowired(required = false) LogService logService) {
        this.logService = logService;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStart(ChatModelObservationContext context) {
        context.put(START_NANOS, System.nanoTime());
        emit(LogLevel.INFO, "llm.call provider=spring-ai model=" + modelOf(context) + " routing=springai");
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        Long start = (Long) context.get(START_NANOS);
        long latencyMs = start == null ? 0L : (System.nanoTime() - start) / 1_000_000L;
        emit(LogLevel.INFO, "llm.done provider=spring-ai model=" + modelOf(context)
                + " latency=" + latencyMs + "ms tokens=" + tokensOf(context));
    }

    @Override
    public void onError(ChatModelObservationContext context) {
        emit(LogLevel.ERROR, "llm.failed provider=spring-ai model=" + modelOf(context)
                + " error=" + (context.getError() == null ? "unknown" : context.getError().getMessage()));
    }

    private static String modelOf(ChatModelObservationContext context) {
        Prompt prompt = context.getRequest();
        if (prompt != null && prompt.getOptions() instanceof ChatOptions options && options.getModel() != null) {
            return options.getModel();
        }
        return "unknown";
    }

    private static int tokensOf(ChatModelObservationContext context) {
        ChatResponse response = context.getResponse();
        if (response == null || response.getMetadata() == null) {
            return 0;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return 0;
        }
        Integer total = usage.getTotalTokens();
        if (total != null) {
            return total;
        }
        int prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        return prompt + completion;
    }

    private void emit(LogLevel level, String message) {
        if (logService == null) {
            return;
        }
        try {
            logService.log(LogEvent.of(IdGenerator.generate("log"), level, LogCategory.llm, message,
                    TraceContext.tenantId(), TraceContext.traceId(), TraceContext.runId(), null));
        } catch (Exception e) {
            log.warn("Spring AI observation -> log failed: {}", e.getMessage());
        }
    }
}
