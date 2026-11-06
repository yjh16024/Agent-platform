package com.agentplatform.framework.starter;

import com.agentplatform.framework.api.RunResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 观测出口：框架的运行生命周期确实变成了一个 Micrometer observation。
 */
class MicrometerRunObserverTest {

    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final ObservationRegistry registry = ObservationRegistry.create();

    {
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                starts.incrementAndGet();
            }

            @Override
            public void onStop(Observation.Context context) {
                stops.incrementAndGet();
            }

            @Override
            public void onError(Observation.Context context) {
                errors.incrementAndGet();
            }
        });
    }

    @Test
    void 成功运行应产生一次start与一次stop() {
        MicrometerRunObserver observer = new MicrometerRunObserver(registry);

        observer.runStarted("r-1", "trace-1", "agent-1", "t-1");
        observer.runCompleted("r-1", 12L, new RunResult.Usage(3, 4));

        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
        assertEquals(0, errors.get());
    }

    @Test
    void 失败运行应记录error并stop() {
        MicrometerRunObserver observer = new MicrometerRunObserver(registry);

        observer.runStarted("r-2", "trace-2", "agent-1", "t-1");
        observer.runFailed("r-2", new IllegalStateException("上游挂了"));

        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
        assertEquals(1, errors.get());
    }

    @Test
    void 没有start就complete不应崩溃也不应多计() {
        MicrometerRunObserver observer = new MicrometerRunObserver(registry);

        observer.runCompleted("ghost", 1L, null);
        observer.runFailed("ghost", new RuntimeException("x"));

        assertEquals(0, starts.get());
        assertEquals(0, stops.get());
    }

    @Test
    void 观测名应对齐语义约定() {
        assertTrue(MicrometerRunObserver.OBSERVATION_NAME.startsWith("agent."));
    }
}
