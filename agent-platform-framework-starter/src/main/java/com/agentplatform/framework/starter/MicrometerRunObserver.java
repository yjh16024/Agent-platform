package com.agentplatform.framework.starter;

import com.agentplatform.framework.api.RunObserver;
import com.agentplatform.framework.api.RunResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把框架的运行生命周期接到 Micrometer Observation（M2：观测走标准）。
 *
 * <p>于是嵌入方只要装了 OTel / Prometheus 的 registry，就能直接拿到
 * {@code agent.run} 这个 span，不需要理解我们的日志表结构。</p>
 *
 * <h3>为什么用 highCardinality 而不是 lowCardinality</h3>
 * <p>智能体 ID、租户 ID、trace ID 都是**高基数**标签：放进 lowCardinality 会把
 * 指标维度打爆（每个智能体一条时间序列）。span 属性用高基数是正常做法，
 * 指标用它们才是事故。真正低基数的只有 {@code outcome}。</p>
 *
 * <p><b>失败即静默</b>：观测层任何异常都不能影响业务，调用方已经兜住异常，
 * 这里也不额外抛。</p>
 */
public class MicrometerRunObserver implements RunObserver {

    /** 观测名：与 OTel 语义约定里的 gen_ai 风格保持一致。 */
    public static final String OBSERVATION_NAME = "agent.run";

    private final ObservationRegistry registry;
    private final Map<String, Observation> running = new ConcurrentHashMap<>();

    public MicrometerRunObserver(ObservationRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("ObservationRegistry 必填");
        }
        this.registry = registry;
    }

    @Override
    public void runStarted(String runId, String traceId, String agentId, String tenantId) {
        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
                .highCardinalityKeyValue("agent.id", String.valueOf(agentId))
                .highCardinalityKeyValue("tenant.id", String.valueOf(tenantId))
                .highCardinalityKeyValue("trace.id", String.valueOf(traceId))
                .start();
        running.put(runId, observation);
    }

    @Override
    public void runCompleted(String runId, long elapsedMs, RunResult.Usage usage) {
        Observation observation = running.remove(runId);
        if (observation == null) {
            return;
        }
        observation.lowCardinalityKeyValue("outcome", "success");
        observation.highCardinalityKeyValue("elapsed.ms", String.valueOf(elapsedMs));
        if (usage != null) {
            observation.highCardinalityKeyValue("gen_ai.usage.input_tokens",
                    String.valueOf(usage.inputTokens()));
            observation.highCardinalityKeyValue("gen_ai.usage.output_tokens",
                    String.valueOf(usage.outputTokens()));
        }
        observation.stop();
    }

    @Override
    public void runFailed(String runId, RuntimeException error) {
        Observation observation = running.remove(runId);
        if (observation == null) {
            return;
        }
        observation.lowCardinalityKeyValue("outcome", "error");
        if (error != null) {
            observation.error(error);
        }
        observation.stop();
    }
}
