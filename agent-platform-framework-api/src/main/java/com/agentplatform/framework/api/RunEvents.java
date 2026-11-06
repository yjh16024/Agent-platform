package com.agentplatform.framework.api;

import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件流的构造器与不变量校验。
 *
 * <p>存在的意义是**别让每个实现各写一遍序号管理**：序号错了、发了两个终止事件、
 * 终止后又冒出一条 delta——这些都不是业务 bug，是契约 bug，应该由框架统一挡住。
 */
public final class RunEvents {

    private final String runId;
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong();

    public RunEvents(String runId) {
        this(runId, Clock.systemUTC());
    }

    public RunEvents(String runId, Clock clock) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 必填");
        }
        this.runId = runId;
        this.clock = (clock == null) ? Clock.systemUTC() : clock;
    }

    public String runId() {
        return runId;
    }

    public RunEvent.Started started(String traceId, String agentId, String sessionId) {
        return new RunEvent.Started(runId, next(), now(), traceId, agentId, sessionId);
    }

    public RunEvent.TextDelta delta(String delta) {
        return new RunEvent.TextDelta(runId, next(), now(), delta);
    }

    public RunEvent.ReasoningDelta reasoning(String delta) {
        return new RunEvent.ReasoningDelta(runId, next(), now(), delta);
    }

    public RunEvent.ToolStarted toolStarted(String callId, String toolName, JsonNode arguments) {
        return new RunEvent.ToolStarted(runId, next(), now(), callId, toolName, arguments);
    }

    public RunEvent.ToolFinished toolFinished(String callId, String toolName,
                                              boolean ok, long elapsedMs, String errorCode) {
        return new RunEvent.ToolFinished(runId, next(), now(), callId, toolName, ok, elapsedMs, errorCode);
    }

    public RunEvent.Completed completed(RunResult result) {
        return new RunEvent.Completed(runId, next(), now(), result);
    }

    public RunEvent.Failed failed(String errorCode, String message, boolean retryable) {
        return new RunEvent.Failed(runId, next(), now(), errorCode, message, retryable);
    }

    private long next() {
        return seq.incrementAndGet();
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 校验一条完整事件流是否满足 {@link RunEvent} 的四条契约。
     *
     * @throws IllegalStateException 违反契约时抛出（这是实现方的 bug，不是调用方的错）
     */
    public static void validate(List<RunEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalStateException("事件流不能为空");
        }
        RunEvent first = events.get(0);
        if (!(first instanceof RunEvent.Started)) {
            throw new IllegalStateException("第一个事件必须是 Started，实际为 " + first.getClass().getSimpleName());
        }

        String runId = first.runId();
        boolean terminated = false;

        for (int i = 0; i < events.size(); i++) {
            RunEvent event = events.get(i);
            long expected = i + 1L;

            if (event == null) {
                throw new IllegalStateException("第 " + expected + " 个事件为 null");
            }
            if (!runId.equals(event.runId())) {
                throw new IllegalStateException("事件 runId 不一致：" + runId + " vs " + event.runId());
            }
            if (event.seq() != expected) {
                throw new IllegalStateException("事件序号应为 " + expected + "，实际为 " + event.seq());
            }
            if (terminated) {
                throw new IllegalStateException("终止事件之后不得再发事件（多出 " + event.getClass().getSimpleName() + "）");
            }
            if (event.terminal()) {
                terminated = true;
                if (i != events.size() - 1) {
                    throw new IllegalStateException("终止事件必须是最后一个，位置 " + expected);
                }
            }
        }

        if (!terminated) {
            throw new IllegalStateException("事件流缺少终止事件（Completed 或 Failed）");
        }
    }
}
